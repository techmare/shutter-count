import AppKit
import ShutterCore

func label(_ s: String, _ font: NSFont = Style.text(14), _ color: NSColor = Style.ink, wrap: Bool = false) -> NSTextField {
    let f = wrap ? NSTextField(wrappingLabelWithString: s) : NSTextField(labelWithString: s)
    f.font = font
    f.textColor = color
    f.isSelectable = true
    return f
}

func button(_ title: String, _ target: AnyObject, _ action: Selector) -> NSButton {
    let b = NSButton(title: title, target: target, action: action)
    b.bezelStyle = .rounded
    return b
}

/// Flipped document view so scroll views start at the top.
final class FlippedView: NSView { override var isFlipped: Bool { true } }

/// Vertical stack inside a scroll view, filling the width.
final class ScrollingStack: NSScrollView {
    let stack = NSStackView()
    init() {
        super.init(frame: .zero)
        drawsBackground = false
        hasVerticalScroller = true
        autohidesScrollers = true
        let doc = FlippedView()
        doc.translatesAutoresizingMaskIntoConstraints = false
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 14
        stack.edgeInsets = NSEdgeInsets(top: 20, left: 24, bottom: 24, right: 24)
        stack.translatesAutoresizingMaskIntoConstraints = false
        doc.addSubview(stack)
        documentView = doc
        NSLayoutConstraint.activate([
            doc.widthAnchor.constraint(equalTo: contentView.widthAnchor),
            stack.leadingAnchor.constraint(equalTo: doc.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: doc.trailingAnchor),
            stack.topAnchor.constraint(equalTo: doc.topAnchor),
            stack.bottomAnchor.constraint(equalTo: doc.bottomAnchor),
        ])
    }
    required init?(coder: NSCoder) { fatalError() }

    func add(_ v: NSView, fill: Bool = true) {
        stack.addArrangedSubview(v)
        if fill { v.widthAnchor.constraint(equalTo: stack.widthAnchor, constant: -48).isActive = true }
    }
    func clear() { stack.arrangedSubviews.forEach { $0.removeFromSuperview() } }
}

/// Coloured rounded box with text, for warnings and the Seen-before notice.
final class Notice: NSView {
    init(title: String, body: String, color: NSColor) {
        super.init(frame: .zero)
        wantsLayer = true
        layer?.backgroundColor = color.withAlphaComponent(0.14).cgColor
        layer?.borderColor = color.withAlphaComponent(0.55).cgColor
        layer?.borderWidth = 1
        layer?.cornerRadius = 10
        let s = NSStackView(views: [label(title, Style.text(15, medium: true)), label(body, Style.text(14), Style.ink, wrap: true)])
        s.orientation = .vertical
        s.alignment = .leading
        s.spacing = 4
        s.translatesAutoresizingMaskIntoConstraints = false
        addSubview(s)
        NSLayoutConstraint.activate([
            s.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 14), s.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -14),
            s.topAnchor.constraint(equalTo: topAnchor, constant: 10), s.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -10),
        ])
    }
    required init?(coder: NSCoder) { fatalError() }
}

/// Fills a ScrollingStack with a reading: panel, notices, details, note, report buttons.
final class ResultPresenter: NSObject, NSTextFieldDelegate {
    private var reading: Reading?
    private weak var shareButton: NSButton?

    func show(_ r: Reading, in s: ScrollingStack) {
        reading = r
        let panel = PanelView()
        panel.title = r.camera
        panel.serial = r.serial
        panel.count = r.count
        panel.rated = r.ratedLife
        panel.official = r.ratedOfficial
        panel.caption = r.count == nil ? "No count found" : (r.includesElectronic ? "actuations, electronic shutter included" : "shutter actuations")
        s.add(panel)

        if let seen = r.seen {
            let diff = (r.count ?? 0) - seen.last.count
            var body = "Checked \(seen.times == 1 ? "once" : "\(seen.times) times") before. Last on \(Fmt.date(seen.last.time)) at \(Fmt.count(seen.last.count))"
            body += diff >= 0 ? " (+\(Fmt.count(diff)) since)." : " — the count is now LOWER, which shouldn't happen."
            if let n = seen.note { body += "\nNote: \(n)" }
            s.add(Notice(title: "Seen before", body: body, color: diff >= 0 ? Style.signal : Style.high))
        }
        if let p = r.problem { s.add(Notice(title: "Couldn't read the count", body: p, color: Style.high)) }
        for w in r.warnings { s.add(Notice(title: "Note", body: w, color: Style.mid)) }

        var rows: [(String, String)] = [("Method", r.method)]
        if let f = r.fileName { rows.append(("File", f)) }
        if let d = r.photoDate { rows.append(("Photo taken", d)) }
        if let rated = r.ratedLife { rows.append((r.ratedOfficial ? "Rated life" : "Estimated life", Fmt.count(rated))) }
        rows += r.details
        let grid = NSGridView(views: rows.map { [label($0.0, Style.text(14), Style.muted), label($0.1, Style.text(14, medium: true))] })
        grid.columnSpacing = 18
        grid.rowSpacing = 6
        s.add(grid, fill: false)

        if r.count != nil {
            let note = NSTextField(string: Store.shared.note(r.historyId))
            note.placeholderString = "Note for History (customer, condition…)"
            note.delegate = self
            note.font = Style.text(14)
            s.add(note)

            let share = button("Share…", self, #selector(share(_:)))
            shareButton = share
            let row = NSStackView(views: [button("Save Report…", self, #selector(save)), button("Copy Report", self, #selector(copyReport)), share])
            row.spacing = 10
            s.add(row, fill: false)
            s.add(label("The report is a French image for your customer, without shop branding.", Style.text(12), Style.muted), fill: false)
        }
    }

    func controlTextDidChange(_ obj: Notification) {
        guard let f = obj.object as? NSTextField else { return }
        Store.shared.setNote(reading?.historyId, f.stringValue)
    }

    private var report: Report.Data? {
        guard let r = reading, let c = r.count else { return nil }
        return Report.Data(camera: r.camera, serial: r.serial, count: c, rated: Prefs.reportWear ? r.ratedLife : nil,
                           official: r.ratedOfficial, method: r.method, includesElectronic: r.includesElectronic)
    }
    @objc func save() { if let d = report { Report.saveAs(d, window: NSApp.keyWindow) } }
    @objc func copyReport() { if let d = report { Report.copy(d) } }
    @objc func share(_ sender: NSButton) { if let d = report { Report.share(d, from: sender) } }
}
