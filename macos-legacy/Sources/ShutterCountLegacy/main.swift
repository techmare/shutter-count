import AppKit
import ShutterCore

final class AppDelegate: NSObject, NSApplicationDelegate {
    private var window: NSWindow!
    private let camera = LegacyCameraManager()
    private var panes: [NSView] = []
    private let container = NSView()
    private let tabs = NSSegmentedControl(labels: ["Photo", "Camera", "History"], trackingMode: .selectOne, target: nil, action: nil)
    private var photo: PhotoPane!
    private var history: HistoryPane!

    func applicationDidFinishLaunching(_ n: Notification) {
        Style.registerFonts()
        buildMenu()
        photo = PhotoPane()
        history = HistoryPane()
        panes = [photo, CameraPane(manager: camera), history]

        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 760, height: 760),
                          styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
        window.title = "Shutter Count"
        window.minSize = NSSize(width: 620, height: 560)
        window.appearance = NSAppearance(named: .aqua)
        window.setFrameAutosaveName("Main")

        let root = NSView()
        root.wantsLayer = true
        root.layer?.backgroundColor = Style.paper.cgColor
        tabs.target = self
        tabs.action = #selector(switchPane)
        tabs.selectedSegment = 0
        tabs.translatesAutoresizingMaskIntoConstraints = false
        container.translatesAutoresizingMaskIntoConstraints = false
        root.addSubview(tabs)
        root.addSubview(container)
        NSLayoutConstraint.activate([
            tabs.topAnchor.constraint(equalTo: root.topAnchor, constant: 14), tabs.centerXAnchor.constraint(equalTo: root.centerXAnchor),
            container.topAnchor.constraint(equalTo: tabs.bottomAnchor, constant: 6),
            container.leadingAnchor.constraint(equalTo: root.leadingAnchor), container.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            container.bottomAnchor.constraint(equalTo: root.bottomAnchor),
        ])
        window.contentView = root
        show(0)
        window.center()
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        Store.shared.onChange = { [weak self] in self?.history.reload() }
        camera.start()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }

    func application(_ sender: NSApplication, openFile filename: String) -> Bool {
        tabs.selectedSegment = 0
        show(0)
        photo.read(URL(fileURLWithPath: filename))
        return true
    }

    @objc func switchPane() { show(tabs.selectedSegment) }

    private func show(_ i: Int) {
        container.subviews.forEach { $0.removeFromSuperview() }
        let v = panes[i]
        v.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(v)
        NSLayoutConstraint.activate([v.leadingAnchor.constraint(equalTo: container.leadingAnchor), v.trailingAnchor.constraint(equalTo: container.trailingAnchor),
                                     v.topAnchor.constraint(equalTo: container.topAnchor), v.bottomAnchor.constraint(equalTo: container.bottomAnchor)])
        if i == 2 { history.reload() }
    }

    // MARK: menus

    @objc func toggleShowWear(_ m: NSMenuItem) {
        Prefs.showWear.toggle()
        m.state = Prefs.showWear ? .on : .off
        panes.forEach { $0.needsDisplay = true; $0.subviews.forEach { s in s.needsLayout = true } }
        container.subviews.first.map { redraw($0) }
    }
    @objc func toggleReportWear(_ m: NSMenuItem) {
        Prefs.reportWear.toggle()
        m.state = Prefs.reportWear ? .on : .off
    }
    @objc func openPhoto() { tabs.selectedSegment = 0; show(0); photo.choose() }
    @objc func paneFromMenu(_ m: NSMenuItem) { tabs.selectedSegment = m.tag; show(m.tag) }

    private func redraw(_ v: NSView) {
        if let p = v as? PanelView { p.invalidateIntrinsicContentSize(); p.needsDisplay = true }
        v.subviews.forEach(redraw)
    }

    private func buildMenu() {
        let main = NSMenu()
        let appItem = NSMenuItem()
        main.addItem(appItem)
        let app = NSMenu()
        app.addItem(withTitle: "About Shutter Count", action: #selector(NSApplication.orderFrontStandardAboutPanel(_:)), keyEquivalent: "")
        app.addItem(.separator())
        let show = app.addItem(withTitle: "Show Shutter Life", action: #selector(toggleShowWear(_:)), keyEquivalent: "")
        show.target = self
        show.state = Prefs.showWear ? .on : .off
        let rep = app.addItem(withTitle: "Include Shutter Life in Reports", action: #selector(toggleReportWear(_:)), keyEquivalent: "")
        rep.target = self
        rep.state = Prefs.reportWear ? .on : .off
        app.addItem(.separator())
        app.addItem(withTitle: "Hide Shutter Count", action: #selector(NSApplication.hide(_:)), keyEquivalent: "h")
        app.addItem(withTitle: "Quit Shutter Count", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        appItem.submenu = app

        let fileItem = NSMenuItem()
        main.addItem(fileItem)
        let file = NSMenu(title: "File")
        file.addItem(withTitle: "Open Photo…", action: #selector(openPhoto), keyEquivalent: "o").target = self
        file.addItem(withTitle: "Close Window", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w")
        fileItem.submenu = file

        let editItem = NSMenuItem()
        main.addItem(editItem)
        let edit = NSMenu(title: "Edit")
        edit.addItem(withTitle: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        edit.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        edit.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        edit.addItem(withTitle: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        editItem.submenu = edit

        let viewItem = NSMenuItem()
        main.addItem(viewItem)
        let view = NSMenu(title: "View")
        for (i, t) in ["Photo", "Camera", "History"].enumerated() {
            let m = view.addItem(withTitle: t, action: #selector(paneFromMenu(_:)), keyEquivalent: "\(i + 1)")
            m.tag = i
            m.target = self
        }
        viewItem.submenu = view

        let winItem = NSMenuItem()
        main.addItem(winItem)
        let win = NSMenu(title: "Window")
        win.addItem(withTitle: "Minimize", action: #selector(NSWindow.performMiniaturize(_:)), keyEquivalent: "m")
        winItem.submenu = win
        NSApp.windowsMenu = win
        NSApp.mainMenu = main
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.regular)
app.run()
