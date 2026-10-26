import SwiftUI
import AppKit
import UniformTypeIdentifiers
import ShutterCore

/// What goes on a shared report.
struct ReportData {
    var camera: String
    var serial: String?
    var count: Int
    /// nil hides the wear scale (setting off, or unknown model).
    var ratedLife: Int?
    var ratedOfficial = false
    var method: String
    var time = Date()
    var includesElectronic = false
}

/// The customer report card, 1080 × 1350, French, no shop branding.
struct ReportCard: View {
    let r: ReportData
    private let muted = Color(hex: 0xA9AFB6)
    private let text = Color(hex: 0xF2F3F4)

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Rapport de déclenchements").font(Fonts.text(38, medium: true)).foregroundStyle(muted)
            Text(r.camera).font(Fonts.display(84)).foregroundStyle(text).lineLimit(1).minimumScaleFactor(0.55).padding(.top, 18)
            if let s = r.serial, !s.isEmpty {
                Text("N° de série \(s)").font(Fonts.text(36)).foregroundStyle(muted).padding(.top, 6)
            }
            ReportOdometer(count: r.count).padding(.top, 36)
            Text("déclenchements").font(Fonts.text(38)).foregroundStyle(muted).padding(.top, 22)
            if let rated = r.ratedLife {
                let wear = CameraModels.wear(r.count, rated)
                let pct = CameraModels.wearPercent(r.count, rated)
                ReportScale(count: r.count, rated: rated, official: r.ratedOfficial).padding(.top, 40)
                HStack(alignment: .firstTextBaseline, spacing: 24) {
                    Text(wear.french).font(Fonts.display(64, bold: false)).foregroundStyle(text)
                    Text((pct == 0 && r.count > 0 ? "moins de 1 %" : "\(pct) %") + (r.ratedOfficial ? " de la durée de vie nominale" : " de la durée de vie estimée"))
                        .font(Fonts.text(36)).foregroundStyle(muted)
                }
                .padding(.top, 40)
            }
            Spacer(minLength: 0)
            VStack(spacing: 0) {
                row("Méthode", r.method)
                row("Date du contrôle", Fmt.date(r.time))
            }
            if r.includesElectronic {
                Text("Le compteur inclut les déclenchements en obturateur électronique.")
                    .font(Fonts.text(30)).foregroundStyle(muted).padding(.top, 14)
            }
        }
        .padding(72)
        .frame(width: 1080, height: 1350, alignment: .topLeading)
        .background(Color(hex: 0x23272C))
    }

    private func row(_ k: String, _ v: String) -> some View {
        VStack(spacing: 0) {
            Rectangle().fill(Color(hex: 0x353A40)).frame(height: 2)
            HStack {
                Text(k).font(Fonts.text(32)).foregroundStyle(muted)
                Spacer()
                Text(v).font(Fonts.text(34, medium: true)).foregroundStyle(text)
            }
            .padding(.vertical, 18)
        }
    }
}

private struct ReportOdometer: View {
    let count: Int
    var body: some View {
        let digits = String(count)
        let wheels = max(6, digits.count)
        let padded = Array(String(repeating: "0", count: wheels - digits.count) + digits)
        HStack(spacing: 12) {
            ForEach(0..<wheels, id: \.self) { i in
                ZStack {
                    LinearGradient(stops: [
                        .init(color: Color(hex: 0x15181B), location: 0), .init(color: Color(hex: 0x30353B), location: 0.2),
                        .init(color: Color(hex: 0x3B4046), location: 0.5), .init(color: Color(hex: 0x30353B), location: 0.8),
                        .init(color: Color(hex: 0x15181B), location: 1)], startPoint: .top, endPoint: .bottom)
                    Text(String(padded[i])).font(Fonts.display(176))
                        .foregroundStyle(i < wheels - digits.count ? Color(hex: 0x6B7178) : Color(hex: 0xF2F3F4))
                }
                .frame(height: 220)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                let remaining = wheels - 1 - i
                if remaining > 0 && remaining % 3 == 0 { Spacer().frame(width: 16) }
            }
        }
    }
}

private struct ReportScale: View {
    let count: Int, rated: Int, official: Bool
    var body: some View {
        let wear = CameraModels.wear(count, rated)
        let fraction = min(1, max(0, Double(count) / Double(rated)))
        VStack(spacing: 18) {
            Canvas { ctx, size in
                let ticks = 40, tickW: CGFloat = 4, major: CGFloat = 44, minor: CGFloat = 24, base = major
                for t in 0...ticks {
                    let x = CGFloat(t) * (size.width - tickW) / CGFloat(ticks)
                    let h = t % 10 == 0 ? major : minor
                    let on = count > 0 && Double(t) / Double(ticks) <= fraction
                    ctx.fill(Path(roundedRect: CGRect(x: x, y: base - h, width: tickW, height: h), cornerRadius: 2), with: .color(on ? wearColor(wear) : Color(hex: 0x4A5057)))
                }
                let mx = CGFloat(fraction) * (size.width - tickW) + tickW / 2
                var tri = Path()
                tri.move(to: CGPoint(x: mx, y: base + 8)); tri.addLine(to: CGPoint(x: mx - 16, y: base + 30)); tri.addLine(to: CGPoint(x: mx + 16, y: base + 30)); tri.closeSubpath()
                ctx.fill(tri, with: .color(Palette.signal))
            }
            .frame(height: 76)
            HStack {
                Text("0"); Spacer(); Text(Fmt.count(rated / 2)); Spacer()
                Text("\(Fmt.count(rated)) " + (official ? "(nominale)" : "(estimée)"))
            }
            .font(Fonts.text(30)).foregroundStyle(Color(hex: 0xA9AFB6))
        }
    }
}

enum ReportExport {
    /// Renders the card to a PNG in a temporary folder and returns its URL.
    @MainActor
    static func png(_ r: ReportData) -> URL? {
        let renderer = ImageRenderer(content: ReportCard(r: r))
        renderer.scale = 1
        guard let cg = renderer.cgImage else { return nil }
        let rep = NSBitmapImageRep(cgImage: cg)
        guard let data = rep.representation(using: .png, properties: [:]) else { return nil }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("ShutterCountReports", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let safe = r.camera.replacingOccurrences(of: "[^A-Za-z0-9]+", with: "-", options: .regularExpression).trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        let url = dir.appendingPathComponent("Rapport-\(safe)-\(r.count).png")
        do { try data.write(to: url, options: .atomic) } catch { return nil }
        return url
    }

    @MainActor
    static func saveAs(_ r: ReportData) {
        guard let src = png(r) else { return }
        let panel = NSSavePanel()
        panel.nameFieldStringValue = src.lastPathComponent
        panel.allowedContentTypes = [.png]
        if panel.runModal() == .OK, let dst = panel.url {
            try? FileManager.default.removeItem(at: dst)
            try? FileManager.default.copyItem(at: src, to: dst)
        }
    }

    @MainActor
    static func copy(_ r: ReportData) {
        guard let url = png(r), let img = NSImage(contentsOf: url) else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.writeObjects([img])
    }
}
