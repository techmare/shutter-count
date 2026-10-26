import AppKit
import CoreText
import ShutterCore

extension NSColor {
    convenience init(hex: UInt32, alpha: CGFloat = 1) {
        self.init(srgbRed: CGFloat((hex >> 16) & 0xff) / 255, green: CGFloat((hex >> 8) & 0xff) / 255,
                  blue: CGFloat(hex & 0xff) / 255, alpha: alpha)
    }
}

/// Same palette and type as the other Shutter Count apps.
enum Style {
    static let paper = NSColor(hex: 0xE9EBEE)
    static let ink = NSColor(hex: 0x1C1F23)
    static let muted = NSColor(hex: 0x5E646C)
    static let rule = NSColor(hex: 0xD3D7DC)
    static let panel = NSColor(hex: 0x2A2E33)
    static let panelText = NSColor(hex: 0xF2F3F4)
    static let panelMuted = NSColor(hex: 0xA9AFB6)
    static let tick = NSColor(hex: 0x5A6067)
    static let signal = NSColor(hex: 0xFFC21A)
    static let ok = NSColor(hex: 0x3FA35E)
    static let mid = NSColor(hex: 0xE5932A)
    static let high = NSColor(hex: 0xE0533A)
    static let dimDigit = NSColor(hex: 0x6B7178)

    static func registerFonts() {
        guard let dir = Bundle.main.resourceURL?.appendingPathComponent("Fonts"),
              let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return }
        for f in files where f.pathExtension.lowercased() == "ttf" {
            CTFontManagerRegisterFontsForURL(f as CFURL, .process, nil)
        }
    }

    static func display(_ size: CGFloat, bold: Bool = true) -> NSFont {
        NSFont(name: bold ? "BarlowCondensed-Bold" : "BarlowCondensed-SemiBold", size: size) ?? .boldSystemFont(ofSize: size)
    }
    static func text(_ size: CGFloat, medium: Bool = false) -> NSFont {
        NSFont(name: medium ? "BarlowSemiCondensed-Medium" : "BarlowSemiCondensed-Regular", size: size) ?? .systemFont(ofSize: size)
    }

    static func wearColor(_ w: CameraModels.Wear) -> NSColor {
        switch w {
        case .likeNew, .good: return ok
        case .used: return mid
        case .high, .beyond: return high
        }
    }
}

/// Drawing helpers that assume a flipped (top-left origin) context.
enum Draw {
    @discardableResult
    static func text(_ s: String, _ font: NSFont, _ color: NSColor, x: CGFloat, top: CGFloat, align: NSTextAlignment = .left, width: CGFloat = 0) -> CGSize {
        let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color]
        let str = NSAttributedString(string: s, attributes: attrs)
        let size = str.size()
        var px = x
        if align == .right { px = x + width - size.width } else if align == .center { px = x + (width - size.width) / 2 }
        str.draw(at: NSPoint(x: px, y: top))
        return size
    }

    static func wheels(count: Int?, in rect: CGRect, gap: CGFloat, groupGap: CGFloat, radius: CGFloat, font: NSFont) {
        let target = count ?? 0
        let digits = String(target)
        let n = max(6, digits.count)
        let groups = (n - 1) / 3
        let w = (rect.width - gap * CGFloat(n - 1 - groups) - groupGap * CGFloat(groups)) / CGFloat(n)
        let padded = Array(String(repeating: "0", count: n - digits.count) + digits)
        let gradient = NSGradient(colors: [NSColor(hex: 0x15181B), NSColor(hex: 0x30353B), NSColor(hex: 0x3B4046), NSColor(hex: 0x30353B), NSColor(hex: 0x15181B)],
                                  atLocations: [0, 0.2, 0.5, 0.8, 1], colorSpace: .sRGB)
        var x = rect.minX
        for i in 0..<n {
            let r = CGRect(x: x, y: rect.minY, width: w, height: rect.height)
            gradient?.draw(in: NSBezierPath(roundedRect: r, xRadius: radius, yRadius: radius), angle: 90)
            let dim = count == nil || target == 0 || i < n - digits.count
            let str = NSAttributedString(string: String(padded[i]), attributes: [.font: font, .foregroundColor: dim ? Style.dimDigit : NSColor.white])
            let s = str.size()
            str.draw(at: NSPoint(x: r.midX - s.width / 2, y: r.midY - s.height / 2))
            let remaining = n - 1 - i
            x += w + (remaining > 0 && remaining % 3 == 0 ? groupGap : gap)
        }
    }

    static func scale(count: Int, rated: Int, in rect: CGRect, tickW: CGFloat, major: CGFloat, minor: CGFloat, marker: CGFloat, track: NSColor) {
        let wear = CameraModels.wear(count, rated)
        let fraction = CGFloat(min(1, max(0, Double(count) / Double(rated))))
        let base = rect.minY + major
        for t in 0...40 {
            let x = rect.minX + CGFloat(t) * (rect.width - tickW) / 40
            let h = t % 10 == 0 ? major : minor
            let on = count > 0 && CGFloat(t) / 40 <= fraction
            (on ? Style.wearColor(wear) : track).setFill()
            NSBezierPath(roundedRect: CGRect(x: x, y: base - h, width: tickW, height: h), xRadius: tickW / 2, yRadius: tickW / 2).fill()
        }
        let mx = rect.minX + fraction * (rect.width - tickW) + tickW / 2
        let tri = NSBezierPath()
        tri.move(to: NSPoint(x: mx, y: base + marker * 0.25))
        tri.line(to: NSPoint(x: mx - marker * 0.5, y: base + marker))
        tri.line(to: NSPoint(x: mx + marker * 0.5, y: base + marker))
        tri.close()
        Style.signal.setFill()
        tri.fill()
    }
}

/// The graphite instrument panel for a reading, drawn in English for the app.
final class PanelView: NSView {
    var title = "" { didSet { needsDisplay = true; invalidateIntrinsicContentSize() } }
    var titleDim = false
    var serial: String?
    var count: Int?
    var caption = ""
    var rated: Int?
    var official = false

    override var isFlipped: Bool { true }
    private var showsWear: Bool { count != nil && rated != nil && Prefs.showWear }
    override var intrinsicContentSize: NSSize { NSSize(width: NSView.noIntrinsicMetric, height: showsWear ? 310 : 200) }

    override func draw(_ dirtyRect: NSRect) {
        let r = bounds
        Style.panel.setFill()
        NSBezierPath(roundedRect: r, xRadius: 18, yRadius: 18).fill()
        let pad: CGFloat = 22
        let inner = r.width - 2 * pad
        Draw.text(title, Style.display(26, bold: false), titleDim ? Style.panelMuted : Style.panelText, x: pad, top: 16)
        if let s = serial, !s.isEmpty {
            Draw.text("S/N \(s)", Style.text(14), Style.panelMuted, x: pad, top: 24, align: .right, width: inner)
        }
        Draw.wheels(count: count, in: CGRect(x: pad, y: 60, width: inner, height: 72), gap: 5, groupGap: 12, radius: 7, font: Style.display(54))
        Draw.text(caption, Style.text(16), Style.panelMuted, x: pad, top: 142)
        if let c = count, let rated = rated, Prefs.showWear {
            Draw.scale(count: c, rated: rated, in: CGRect(x: pad, y: 178, width: inner, height: 30), tickW: 2, major: 16, minor: 9, marker: 12, track: Style.tick)
            Draw.text("0", Style.text(13), Style.panelMuted, x: pad, top: 208)
            Draw.text(Fmt.count(rated / 2), Style.text(13), Style.panelMuted, x: pad, top: 208, align: .center, width: inner)
            Draw.text("\(Fmt.count(rated)) " + (official ? "rated" : "est."), Style.text(13), Style.panelMuted, x: pad, top: 208, align: .right, width: inner)
            let wear = CameraModels.wear(c, rated)
            let pct = CameraModels.wearPercent(c, rated)
            let size = Draw.text(wear.rawValue, Style.display(26, bold: false), Style.panelText, x: pad, top: 238)
            Draw.text((pct == 0 && c > 0 ? "Under 1%" : "\(pct)%") + (official ? " of rated life used" : " of estimated life used"),
                      Style.text(16), Style.panelMuted, x: pad + size.width + 10, top: 246)
        }
    }
}

/// The customer report card, 1080 × 1350 PNG, French, no shop branding.
enum Report {
    struct Data {
        var camera: String
        var serial: String?
        var count: Int
        var rated: Int?
        var official: Bool
        var method: String
        var time = Date()
        var includesElectronic: Bool
    }

    static func png(_ r: Data) -> Foundation.Data? {
        let W: CGFloat = 1080, H: CGFloat = 1350, P: CGFloat = 72
        guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(W), pixelsHigh: Int(H), bitsPerSample: 8, samplesPerPixel: 4,
                                         hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0),
              let base = NSGraphicsContext(bitmapImageRep: rep) else { return nil }
        let cg = base.cgContext
        cg.translateBy(x: 0, y: H); cg.scaleBy(x: 1, y: -1)
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(cgContext: cg, flipped: true)
        defer { NSGraphicsContext.restoreGraphicsState() }

        let text = NSColor(hex: 0xF2F3F4), muted = NSColor(hex: 0xA9AFB6)
        NSColor(hex: 0x23272C).setFill(); CGRect(x: 0, y: 0, width: W, height: H).fill()
        let inner = W - 2 * P
        var y: CGFloat = 80
        Draw.text("Rapport de déclenchements", Style.text(38, medium: true), muted, x: P, top: y)
        y += 60
        var size: CGFloat = 84
        while NSAttributedString(string: r.camera, attributes: [.font: Style.display(size)]).size().width > inner && size > 48 { size -= 2 }
        Draw.text(r.camera, Style.display(size), text, x: P, top: y)
        y += size + 14
        if let s = r.serial, !s.isEmpty {
            Draw.text("N° de série \(s)", Style.text(36), muted, x: P, top: y)
            y += 54
        }
        y += 24
        Draw.wheels(count: r.count, in: CGRect(x: P, y: y, width: inner, height: 220), gap: 12, groupGap: 28, radius: 14, font: Style.display(176))
        y += 240
        Draw.text("déclenchements", Style.text(38), muted, x: P, top: y)
        y += 80
        if let rated = r.rated {
            Draw.scale(count: r.count, rated: rated, in: CGRect(x: P, y: y, width: inner, height: 76), tickW: 4, major: 44, minor: 24, marker: 30, track: NSColor(hex: 0x4A5057))
            y += 92
            Draw.text("0", Style.text(30), muted, x: P, top: y)
            Draw.text(Fmt.count(rated / 2), Style.text(30), muted, x: P, top: y, align: .center, width: inner)
            Draw.text("\(Fmt.count(rated)) " + (r.official ? "(nominale)" : "(estimée)"), Style.text(30), muted, x: P, top: y, align: .right, width: inner)
            y += 72
            let wear = CameraModels.wear(r.count, rated)
            let pct = CameraModels.wearPercent(r.count, rated)
            let s = Draw.text(wear.french, Style.display(64, bold: false), text, x: P, top: y)
            Draw.text((pct == 0 && r.count > 0 ? "moins de 1 %" : "\(pct) %") + (r.official ? " de la durée de vie nominale" : " de la durée de vie estimée"),
                      Style.text(36), muted, x: P + s.width + 24, top: y + 20)
        }
        // details anchored to the bottom
        var by = H - P - 2 * 72 - (r.includesElectronic ? 52 : 0)
        for (k, v) in [("Méthode", r.method), ("Date du contrôle", Fmt.date(r.time))] {
            NSColor(hex: 0x353A40).setFill(); CGRect(x: P, y: by, width: inner, height: 2).fill()
            Draw.text(k, Style.text(32), muted, x: P, top: by + 16)
            Draw.text(v, Style.text(34, medium: true), text, x: P, top: by + 16, align: .right, width: inner)
            by += 72
        }
        if r.includesElectronic {
            Draw.text("Le compteur inclut les déclenchements en obturateur électronique.", Style.text(30), muted, x: P, top: by + 10)
        }
        return rep.representation(using: .png, properties: [:])
    }

    static func fileURL(_ r: Data) -> URL? {
        guard let png = png(r) else { return nil }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("ShutterCountReports", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let safe = r.camera.replacingOccurrences(of: "[^A-Za-z0-9]+", with: "-", options: .regularExpression).trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        let url = dir.appendingPathComponent("Rapport-\(safe)-\(r.count).png")
        return (try? png.write(to: url, options: .atomic)) != nil ? url : nil
    }

    static func saveAs(_ r: Data, window: NSWindow?) {
        guard let src = fileURL(r) else { return }
        let panel = NSSavePanel()
        panel.nameFieldStringValue = src.lastPathComponent
        panel.allowedFileTypes = ["png"]
        let done: (NSApplication.ModalResponse) -> Void = { resp in
            guard resp == .OK, let dst = panel.url else { return }
            try? FileManager.default.removeItem(at: dst)
            try? FileManager.default.copyItem(at: src, to: dst)
        }
        if let w = window { panel.beginSheetModal(for: w, completionHandler: done) } else { done(panel.runModal()) }
    }

    static func copy(_ r: Data) {
        guard let url = fileURL(r), let img = NSImage(contentsOf: url) else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.writeObjects([img])
    }

    static func share(_ r: Data, from view: NSView) {
        guard let url = fileURL(r) else { return }
        NSSharingServicePicker(items: [url]).show(relativeTo: view.bounds, of: view, preferredEdge: .minY)
    }
}
