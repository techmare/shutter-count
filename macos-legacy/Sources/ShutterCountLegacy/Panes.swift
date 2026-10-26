import AppKit
import ShutterCore

// MARK: Photo

final class DropZone: NSView {
    var onFile: ((URL) -> Void)?
    private var hot = false { didSet { needsDisplay = true } }

    override init(frame: NSRect) {
        super.init(frame: frame)
        registerForDraggedTypes([.fileURL])
    }
    required init?(coder: NSCoder) { fatalError() }

    override func draw(_ dirtyRect: NSRect) {
        let p = NSBezierPath(roundedRect: bounds.insetBy(dx: 1, dy: 1), xRadius: 14, yRadius: 14)
        (hot ? Style.signal.withAlphaComponent(0.15) : NSColor.white.withAlphaComponent(0.55)).setFill()
        p.fill()
        p.setLineDash([6, 4], count: 2, phase: 0)
        p.lineWidth = 1.5
        (hot ? Style.signal : Style.rule).setStroke()
        p.stroke()
    }
    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation { hot = true; return .copy }
    override func draggingExited(_ sender: NSDraggingInfo?) { hot = false }
    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        hot = false
        guard let url = (sender.draggingPasteboard.readObjects(forClasses: [NSURL.self], options: nil) as? [URL])?.first else { return false }
        onFile?(url)
        return true
    }
}

final class PhotoPane: NSView {
    private let scroll = ScrollingStack()
    private let presenter = ResultPresenter()
    private let drop = DropZone()
    private var busy = false

    init() {
        super.init(frame: .zero)
        scroll.translatesAutoresizingMaskIntoConstraints = false
        addSubview(scroll)
        NSLayoutConstraint.activate([scroll.leadingAnchor.constraint(equalTo: leadingAnchor), scroll.trailingAnchor.constraint(equalTo: trailingAnchor),
                                     scroll.topAnchor.constraint(equalTo: topAnchor), scroll.bottomAnchor.constraint(equalTo: bottomAnchor)])
        drop.onFile = { [weak self] in self?.read($0) }
        let inner = NSStackView(views: [label("Drop a photo straight from the camera", Style.display(24, bold: false)),
                                        label("JPG, NEF, ARW, CR2, CR3 or HEIF — not a copy from Photos, WhatsApp or the web.", Style.text(14), Style.muted),
                                        button("Choose Photo…", self, #selector(choose))])
        inner.orientation = .vertical
        inner.spacing = 8
        inner.translatesAutoresizingMaskIntoConstraints = false
        drop.addSubview(inner)
        NSLayoutConstraint.activate([inner.centerXAnchor.constraint(equalTo: drop.centerXAnchor), inner.centerYAnchor.constraint(equalTo: drop.centerYAnchor),
                                     drop.heightAnchor.constraint(equalToConstant: 150)])
        reset()
    }
    required init?(coder: NSCoder) { fatalError() }

    private func reset() {
        scroll.clear()
        scroll.add(drop)
    }

    @objc func choose() {
        let p = NSOpenPanel()
        p.allowedFileTypes = ["jpg", "jpeg", "nef", "nrw", "arw", "srf", "sr2", "cr2", "cr3", "heic", "heif", "hif", "tif", "tiff", "dng"]
        p.allowsMultipleSelection = false
        p.beginSheetModal(for: window!) { [weak self] r in if r == .OK, let u = p.url { self?.read(u) } }
    }

    func read(_ url: URL) {
        guard !busy else { return }
        busy = true
        reset()
        let spinner = NSProgressIndicator()
        spinner.style = .spinning
        spinner.controlSize = .small
        spinner.startAnimation(nil)
        let row = NSStackView(views: [spinner, label("Reading \(url.lastPathComponent)…")])
        scroll.add(row, fill: false)
        DispatchQueue.global(qos: .userInitiated).async {
            var data = Data()
            if let h = try? FileHandle(forReadingFrom: url) { data = h.readData(ofLength: 12 * 1024 * 1024); h.closeFile() }
            let parsed = data.isEmpty ? PhotoReader.failure("Couldn't open this file.") : PhotoReader.read(data)
            DispatchQueue.main.async {
                self.busy = false
                let r = Store.shared.record(Reading.from(parsed, fileName: url.lastPathComponent))
                self.reset()
                self.presenter.show(r, in: self.scroll)
            }
        }
    }
}

// MARK: Camera

final class CameraPane: NSView {
    private let manager: LegacyCameraManager
    private let scroll = ScrollingStack()
    private let presenter = ResultPresenter()
    private let popup = NSPopUpButton()
    private var readButton: NSButton!

    init(manager: LegacyCameraManager) {
        self.manager = manager
        super.init(frame: .zero)
        scroll.translatesAutoresizingMaskIntoConstraints = false
        addSubview(scroll)
        NSLayoutConstraint.activate([scroll.leadingAnchor.constraint(equalTo: leadingAnchor), scroll.trailingAnchor.constraint(equalTo: trailingAnchor),
                                     scroll.topAnchor.constraint(equalTo: topAnchor), scroll.bottomAnchor.constraint(equalTo: bottomAnchor)])
        popup.target = self
        popup.action = #selector(pick)
        readButton = button("Read Camera", self, #selector(read))
        readButton.keyEquivalent = "\r"
        manager.onChange = { [weak self] in self?.refresh() }
        refresh()
    }
    required init?(coder: NSCoder) { fatalError() }

    @objc func pick() { manager.selectedIndex = popup.indexOfSelectedItem }
    @objc func read() { manager.read { Store.shared.record($0) } }
    @objc func copyDiagnostics() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(manager.diagnostics, forType: .string)
    }

    func refresh() {
        scroll.clear()
        scroll.add(label("Read a camera over USB", Style.display(26, bold: false)), fill: false)
        scroll.add(label("Plug the camera in with its USB cable and switch it on. Quit Photos, Image Capture and Lightroom if they open, then press Read.",
                         Style.text(14), Style.muted, wrap: true))
        popup.removeAllItems()
        if manager.cameras.isEmpty {
            popup.addItem(withTitle: "No camera found")
            popup.isEnabled = false
        } else {
            popup.addItems(withTitles: manager.cameras.map { $0.name ?? "Camera" })
            popup.selectItem(at: min(manager.selectedIndex, manager.cameras.count - 1))
            popup.isEnabled = !manager.busy
        }
        readButton.isEnabled = !manager.cameras.isEmpty && !manager.busy
        let row = NSStackView(views: [popup, readButton])
        row.spacing = 10
        scroll.add(row, fill: false)

        switch manager.state {
        case .busy(let msg):
            let spinner = NSProgressIndicator()
            spinner.style = .spinning
            spinner.controlSize = .small
            spinner.startAnimation(nil)
            scroll.add(NSStackView(views: [spinner, label(msg)]), fill: false)
        case .error(let msg):
            scroll.add(Notice(title: "Couldn't read the camera", body: msg, color: Style.high))
        case .idle:
            if let r = manager.result { presenter.show(r, in: scroll) }
        }
        if !manager.diagnostics.isEmpty && !manager.busy {
            scroll.add(button("Copy Diagnostics", self, #selector(copyDiagnostics)), fill: false)
        }
        scroll.add(label("Nikon, Sony and Canon R bodies are read from the newest photo on the card. Older Canon EOS bodies are read from their own counter with read-only status commands.",
                         Style.text(12), Style.muted, wrap: true))
    }
}

// MARK: History

final class HistoryPane: NSView, NSTableViewDataSource, NSTableViewDelegate, NSSearchFieldDelegate {
    private let table = NSTableView()
    private let search = NSSearchField()
    private var rows: [HistoryEntry] = []
    private let empty = label("No checks yet.", Style.text(15), Style.muted)

    init() {
        super.init(frame: .zero)
        search.placeholderString = "Search camera, serial or note"
        search.delegate = self
        search.translatesAutoresizingMaskIntoConstraints = false
        for (id, title, w) in [("time", "Date", 130.0), ("camera", "Camera", 170.0), ("serial", "Serial", 130.0), ("count", "Shutter", 90.0), ("note", "Note", 200.0)] {
            let c = NSTableColumn(identifier: NSUserInterfaceItemIdentifier(id))
            c.title = title
            c.width = CGFloat(w)
            c.isEditable = id == "note"
            table.addTableColumn(c)
        }
        table.dataSource = self
        table.delegate = self
        table.usesAlternatingRowBackgroundColors = true
        table.rowHeight = 24
        let menu = NSMenu()
        menu.addItem(withTitle: "Save Report…", action: #selector(saveReport), keyEquivalent: "").target = self
        menu.addItem(withTitle: "Copy Report", action: #selector(copyReport), keyEquivalent: "").target = self
        menu.addItem(.separator())
        menu.addItem(withTitle: "Delete", action: #selector(deleteRow), keyEquivalent: "").target = self
        table.menu = menu
        let sv = NSScrollView()
        sv.documentView = table
        sv.hasVerticalScroller = true
        sv.translatesAutoresizingMaskIntoConstraints = false
        empty.translatesAutoresizingMaskIntoConstraints = false
        addSubview(search); addSubview(sv); addSubview(empty)
        NSLayoutConstraint.activate([
            search.topAnchor.constraint(equalTo: topAnchor, constant: 16), search.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 20),
            search.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -20),
            sv.topAnchor.constraint(equalTo: search.bottomAnchor, constant: 12), sv.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 20),
            sv.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -20), sv.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -20),
            empty.centerXAnchor.constraint(equalTo: sv.centerXAnchor), empty.centerYAnchor.constraint(equalTo: sv.centerYAnchor),
        ])
        reload()
    }
    required init?(coder: NSCoder) { fatalError() }

    func reload() {
        Store.shared.load()
        let q = search.stringValue.lowercased()
        rows = Store.shared.history.filter {
            q.isEmpty || $0.camera.lowercased().contains(q) || ($0.serial ?? "").lowercased().contains(q) || $0.note.lowercased().contains(q)
        }
        empty.isHidden = !rows.isEmpty
        table.reloadData()
    }

    func controlTextDidChange(_ obj: Notification) { if obj.object as? NSSearchField === search { reload() } }

    func numberOfRows(in tableView: NSTableView) -> Int { rows.count }

    func tableView(_ tableView: NSTableView, objectValueFor tableColumn: NSTableColumn?, row: Int) -> Any? {
        let e = rows[row]
        switch tableColumn?.identifier.rawValue {
        case "time": return Fmt.date(e.time)
        case "camera": return e.camera
        case "serial": return e.serial ?? "—"
        case "count": return Fmt.count(e.count)
        default: return e.note
        }
    }

    func tableView(_ tableView: NSTableView, setObjectValue object: Any?, for tableColumn: NSTableColumn?, row: Int) {
        guard tableColumn?.identifier.rawValue == "note" else { return }
        Store.shared.setNote(rows[row].id, object as? String ?? "")
        rows[row].note = object as? String ?? ""
    }

    private var clicked: HistoryEntry? {
        let i = table.clickedRow >= 0 ? table.clickedRow : table.selectedRow
        return rows.indices.contains(i) ? rows[i] : nil
    }
    private func report(_ e: HistoryEntry) -> Report.Data {
        Report.Data(camera: e.camera, serial: e.serial, count: e.count, rated: Prefs.reportWear ? e.ratedLife : nil, official: e.ratedOfficial,
                    method: e.source == "USB" ? "USB" : "Photo (données EXIF)", time: e.time, includesElectronic: e.includesElectronic)
    }
    @objc func saveReport() { if let e = clicked { Report.saveAs(report(e), window: window) } }
    @objc func copyReport() { if let e = clicked { Report.copy(report(e)) } }
    @objc func deleteRow() { if let e = clicked { Store.shared.delete(e.id); reload() } }
}
