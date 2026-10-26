import SwiftUI
import AppKit
import CoreText

/// Same palette as the Android app: aluminium paper, graphite panel, signal yellow.
struct Palette {
    let paper, ink, muted, rule, panel, panelText, panelMuted, panelTick: Color
    static let signal = Color(hex: 0xFFC21A)
    static let ok = Color(hex: 0x3FA35E)
    static let mid = Color(hex: 0xE5932A)
    static let high = Color(hex: 0xE0533A)

    static let light = Palette(
        paper: Color(hex: 0xE9EBEE), ink: Color(hex: 0x1C1F23), muted: Color(hex: 0x5E646C), rule: Color(hex: 0xD3D7DC),
        panel: Color(hex: 0x2A2E33), panelText: Color(hex: 0xF2F3F4), panelMuted: Color(hex: 0xA9AFB6), panelTick: Color(hex: 0x5A6067))
    static let dark = Palette(
        paper: Color(hex: 0x1E2125), ink: Color(hex: 0xE8EAED), muted: Color(hex: 0x9CA2A9), rule: Color(hex: 0x353A40),
        panel: Color(hex: 0x2C3137), panelText: Color(hex: 0xF2F3F4), panelMuted: Color(hex: 0xA9AFB6), panelTick: Color(hex: 0x5A6067))
}

private struct PaletteKey: EnvironmentKey { static let defaultValue = Palette.light }
extension EnvironmentValues {
    var palette: Palette {
        get { self[PaletteKey.self] }
        set { self[PaletteKey.self] = newValue }
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xff) / 255, green: Double((hex >> 8) & 0xff) / 255, blue: Double(hex & 0xff) / 255)
    }
}

/// Barlow Condensed for display, Barlow Semi Condensed for text. Bundled in Resources/Fonts.
enum Fonts {
    static func register() {
        guard let dir = Bundle.main.resourceURL?.appendingPathComponent("Fonts"),
              let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return }
        for f in files where f.pathExtension.lowercased() == "ttf" {
            CTFontManagerRegisterFontsForURL(f as CFURL, .process, nil)
        }
    }

    static func display(_ size: CGFloat, bold: Bool = true) -> Font {
        .custom(bold ? "BarlowCondensed-Bold" : "BarlowCondensed-SemiBold", size: size)
    }
    static func text(_ size: CGFloat, medium: Bool = false) -> Font {
        .custom(medium ? "BarlowSemiCondensed-Medium" : "BarlowSemiCondensed-Regular", size: size)
    }
}

/// Applies the palette for the current appearance.
struct Themed<Content: View>: View {
    @Environment(\.colorScheme) private var scheme
    @ViewBuilder let content: () -> Content
    var body: some View {
        content()
            .environment(\.palette, scheme == .dark ? .dark : .light)
            .tint(scheme == .dark ? Palette.signal : Palette.light.ink)
    }
}
