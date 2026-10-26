import SwiftUI
import ShutterCore

// MARK: instrument panel

/// The graphite panel: camera, serial, rolling odometer, wear scale.
struct InstrumentPanel<Footer: View>: View {
    @Environment(\.palette) private var p
    var title: String
    var titleDim = false
    var trailing: String?
    var count: Int?
    var caption: String
    var ratedLife: Int?
    var ratedOfficial = false
    var showWear = true
    @ViewBuilder var footer: () -> Footer

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                Text(title).font(Fonts.display(26, bold: false)).foregroundStyle(titleDim ? p.panelMuted : p.panelText)
                Spacer(minLength: 12)
                if let trailing { Text(trailing).font(Fonts.text(14)).foregroundStyle(p.panelMuted).lineLimit(1) }
            }
            Odometer(value: count).padding(.top, 16)
            Text(caption).font(Fonts.text(16)).foregroundStyle(p.panelMuted).padding(.top, 10)
            if let count, let rated = ratedLife, showWear {
                WearScale(count: count, rated: rated, official: ratedOfficial).padding(.top, 18)
                let wear = CameraModels.wear(count, rated)
                let pct = CameraModels.wearPercent(count, rated)
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Text(wear.rawValue).font(Fonts.display(26, bold: false)).foregroundStyle(p.panelText)
                    Text((pct == 0 && count > 0 ? "Under 1%" : "\(pct)%") + (ratedOfficial ? " of rated life used" : " of estimated life used"))
                        .font(Fonts.text(16)).foregroundStyle(p.panelMuted)
                }
                .padding(.top, 14)
            } else if count != nil && showWear {
                Text("Rated shutter life for this model isn't known, so wear can't be estimated.")
                    .font(Fonts.text(13)).foregroundStyle(p.panelMuted).padding(.top, 10)
            }
            footer()
        }
        .padding(.horizontal, 22).padding(.vertical, 20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(p.panel, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

extension InstrumentPanel where Footer == EmptyView {
    init(title: String, titleDim: Bool = false, trailing: String? = nil, count: Int?, caption: String,
         ratedLife: Int? = nil, ratedOfficial: Bool = false, showWear: Bool = true) {
        self.init(title: title, titleDim: titleDim, trailing: trailing, count: count, caption: caption,
                  ratedLife: ratedLife, ratedOfficial: ratedOfficial, showWear: showWear, footer: { EmptyView() })
    }
}

// MARK: odometer

/// Mechanical counter: rolls up once when a new count arrives; higher wheels turn as the one below passes 9.
struct Odometer: View {
    let value: Int?
    @State private var shown: Double = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        OdometerWheels(position: shown, target: value ?? 0, empty: value == nil)
            .accessibilityElement()
            .accessibilityLabel(value.map { "\(Fmt.count($0)) shutter actuations" } ?? "No count")
            .onAppear(perform: roll)
            .onChange(of: value) { _ in roll() }
    }

    private func roll() {
        guard let v = value else { shown = 0; return }
        if reduceMotion { shown = Double(v); return }
        shown = 0
        withAnimation(.easeOut(duration: 1.5)) { shown = Double(v) }
    }
}

private struct OdometerWheels: View, Animatable {
    var position: Double
    let target: Int
    let empty: Bool
    var animatableData: Double {
        get { position }
        set { position = newValue }
    }

    var body: some View {
        let digits = max(6, String(target).count)
        let significant = empty || target == 0 ? 0 : String(target).count
        HStack(spacing: 5) {
            ForEach((0..<digits).reversed(), id: \.self) { i in
                Wheel(position: wheelPosition(i), dim: i >= significant)
                if i % 3 == 0 && i != 0 { Spacer().frame(width: 8) }
            }
        }
    }

    private func wheelPosition(_ i: Int) -> Double {
        let unit = pow(10.0, Double(i))
        if i == 0 { return position }
        let base = floor(position / unit)
        let rem = position - base * unit
        return base + max(0, rem - (unit - 1))
    }
}

private struct Wheel: View {
    let position: Double
    let dim: Bool
    var body: some View {
        GeometryReader { geo in
            let h = geo.size.height
            let whole = floor(position)
            let digit = Int(whole.truncatingRemainder(dividingBy: 10))
            let frac = position - whole
            ZStack {
                LinearGradient(stops: [
                    .init(color: Color(hex: 0x15181B), location: 0), .init(color: Color(hex: 0x30353B), location: 0.2),
                    .init(color: Color(hex: 0x3B4046), location: 0.5), .init(color: Color(hex: 0x30353B), location: 0.8),
                    .init(color: Color(hex: 0x15181B), location: 1)], startPoint: .top, endPoint: .bottom)
                digitText(digit).offset(y: -frac * h)
                if frac > 0.001 { digitText((digit + 1) % 10).offset(y: (1 - frac) * h) }
            }
            .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
        }
        .frame(height: 72)
    }

    private func digitText(_ d: Int) -> some View {
        Text(String(d)).font(Fonts.display(54)).foregroundStyle(dim ? Color(hex: 0x6B7178) : .white)
    }
}

// MARK: wear scale

struct WearScale: View {
    @Environment(\.palette) private var p
    let count: Int
    let rated: Int
    let official: Bool

    var body: some View {
        let wear = CameraModels.wear(count, rated)
        let fill = wearColor(wear)
        let fraction = min(1, max(0, Double(count) / Double(rated)))
        VStack(spacing: 4) {
            Canvas { ctx, size in
                let ticks = 40, tickW: CGFloat = 2, major: CGFloat = 16, minor: CGFloat = 9, base = major
                for t in 0...ticks {
                    let x = CGFloat(t) * (size.width - tickW) / CGFloat(ticks)
                    let h = t % 10 == 0 ? major : minor
                    let on = Double(t) / Double(ticks) <= fraction && count > 0
                    ctx.fill(Path(roundedRect: CGRect(x: x, y: base - h, width: tickW, height: h), cornerRadius: 1), with: .color(on ? fill : p.panelTick))
                }
                let mx = CGFloat(fraction) * (size.width - tickW) + tickW / 2
                var tri = Path()
                tri.move(to: CGPoint(x: mx, y: base + 3)); tri.addLine(to: CGPoint(x: mx - 6, y: base + 11)); tri.addLine(to: CGPoint(x: mx + 6, y: base + 11)); tri.closeSubpath()
                ctx.fill(tri, with: .color(Palette.signal))
            }
            .frame(height: 28)
            HStack {
                Text("0"); Spacer(); Text(Fmt.count(rated / 2)); Spacer()
                Text("\(Fmt.count(rated)) " + (official ? "rated" : "est."))
            }
            .font(Fonts.text(13)).foregroundStyle(p.panelMuted)
        }
    }
}

func wearColor(_ w: CameraModels.Wear) -> Color {
    switch w {
    case .likeNew, .good: return Palette.ok
    case .used: return Palette.mid
    case .high, .beyond: return Palette.high
    }
}

// MARK: quiet blocks

struct DetailRows: View {
    @Environment(\.palette) private var p
    let rows: [(String, String)]
    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(rows.enumerated()), id: \.offset) { i, row in
                HStack(alignment: .firstTextBaseline) {
                    Text(row.0).foregroundStyle(p.muted).frame(width: 120, alignment: .leading)
                    Text(row.1).foregroundStyle(p.ink).textSelection(.enabled)
                    Spacer()
                }
                .font(Fonts.text(15))
                .padding(.vertical, 8)
                if i < rows.count - 1 { Divider().overlay(p.rule) }
            }
        }
        .padding(.horizontal, 6)
    }
}

/// A note with a coloured edge: red for problems, grey for guidance.
struct Note: View {
    @Environment(\.palette) private var p
    let text: String
    var warning = false
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            RoundedRectangle(cornerRadius: 2).fill(warning ? Palette.high : p.muted.opacity(0.5)).frame(width: 3)
            Text(text).font(Fonts.text(15)).foregroundStyle(warning ? p.ink : p.muted).fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 6)
    }
}

/// Numbered how-to. Only for real sequences.
struct Steps: View {
    @Environment(\.palette) private var p
    let title: String
    let steps: [String]
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(Fonts.display(20, bold: false)).foregroundStyle(p.ink)
            ForEach(Array(steps.enumerated()), id: \.offset) { i, s in
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Text("\(i + 1)").font(Fonts.display(17, bold: false)).foregroundStyle(p.muted).frame(width: 16, alignment: .leading)
                    Text(s).font(Fonts.text(15)).foregroundStyle(p.ink).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .padding(.horizontal, 6)
    }
}

/// This serial number is already in History. A lower count than last time is flagged.
struct SeenBeforeBanner: View {
    @Environment(\.palette) private var p
    let seen: Seen
    let count: Int
    var body: some View {
        let diff = count - seen.last.count
        let lower = diff < 0
        VStack(alignment: .leading, spacing: 4) {
            Text(lower ? "Count is lower than last time" : "Seen before").font(Fonts.display(20, bold: false))
            Text((seen.times == 1 ? "Checked once before" : "Checked \(seen.times) times before")
                 + ": \(Fmt.date(seen.last.time)) at \(Fmt.count(seen.last.count)) shots"
                 + (diff > 0 ? ", \(Fmt.count(diff)) since then." : diff == 0 ? ", no new shots since." : "."))
            if lower {
                Text("It was \(Fmt.count(seen.last.count)) and is now \(Fmt.count(count)). The shutter may have been replaced or the counter reset, or this is a different camera.")
            }
            if let n = seen.note, !n.isEmpty { Text("Note: \(n)").foregroundStyle(p.muted) }
        }
        .font(Fonts.text(15))
        .foregroundStyle(p.ink)
        .padding(.horizontal, 16).padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background((lower ? Palette.high.opacity(0.14) : Palette.signal.opacity(0.18)), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.palette) private var p
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Fonts.text(16, medium: true))
            .padding(.horizontal, 20).padding(.vertical, 10)
            .foregroundStyle(Color(hex: 0x1C1F23))
            .background(Palette.signal.opacity(configuration.isPressed ? 0.8 : 1), in: Capsule())
    }
}

struct SecondaryButtonStyle: ButtonStyle {
    @Environment(\.palette) private var p
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Fonts.text(16, medium: true))
            .padding(.horizontal, 20).padding(.vertical, 10)
            .foregroundStyle(p.ink)
            .overlay(Capsule().stroke(p.muted.opacity(0.6), lineWidth: 1.5))
            .contentShape(Capsule())
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}
