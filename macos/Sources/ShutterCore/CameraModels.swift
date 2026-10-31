import Foundation

public enum Brand: String, Codable, Sendable {
    case nikon = "Nikon", sony = "Sony", canon = "Canon", other = "Other"
}

extension String {
    func dropPrefix(_ p: String) -> String { hasPrefix(p) ? String(dropFirst(p.count)) : self }
}

/// Display names, shutter ratings and wear. Same tables as the Android app.
public enum CameraModels {

    public static func brandOf(_ make: String?) -> Brand {
        guard let m = make?.uppercased() else { return .other }
        if m.contains("NIKON") { return .nikon }
        if m.contains("SONY") { return .sony }
        if m.contains("CANON") { return .canon }
        return .other
    }

    /// "ILCE-7M3" -> "Sony α7 III", "NIKON Z 6_2" -> "Nikon Z6 II".
    public static func displayName(_ brand: Brand, _ model: String?) -> String {
        let raw = (model ?? "").trimmingCharacters(in: .whitespaces)
        if raw.isEmpty { return brand.rawValue }
        switch brand {
        case .sony: return "Sony " + sonyName(raw)
        case .nikon: return "Nikon " + nikonName(raw)
        case .canon: return raw.uppercased().hasPrefix("CANON") ? raw : "Canon \(raw)"
        case .other: return raw
        }
    }

    private static func roman(_ s: String) -> String {
        var r = s
        for (k, v) in [("M2$", " II"), ("M3$", " III"), ("M4$", " IV"), ("M5$", " V"), ("M6$", " VI")] {
            r = Rx.replace(k, in: r, with: v)
        }
        return r
    }

    private static func sonyName(_ code: String) -> String {
        if code.hasPrefix("ILCE-") { return "α" + roman(code.dropPrefix("ILCE-")) }
        if code.hasPrefix("ILCA-") { return "α" + roman(code.dropPrefix("ILCA-")) }
        if code.hasPrefix("SLT-") { return "α" + code.dropPrefix("SLT-").dropPrefix("A") }
        if code.hasPrefix("ILME-") { return code.dropPrefix("ILME-") }
        return code
    }

    private static func nikonName(_ model: String) -> String {
        var m = model.dropPrefix("NIKON").trimmingCharacters(in: .whitespaces)
        m = Rx.replace("^Z\\s+", in: m, with: "Z")
        return m.replacingOccurrences(of: "_2", with: " II").replacingOccurrences(of: "_3", with: " III")
    }

    /// A shutter rating. `official` = published by the maker; otherwise an estimate for the camera's class.
    public struct Rating: Equatable, Sendable {
        public let value: Int
        public let official: Bool
    }

    private static func off(_ v: Int) -> Rating { Rating(value: v, official: true) }
    private static func est(_ v: Int) -> Rating { Rating(value: v, official: false) }

    public static func rating(_ brand: Brand, _ model: String?) -> Rating? {
        guard let m = model?.uppercased().trimmingCharacters(in: .whitespaces) else { return nil }
        switch brand {
        case .nikon: return nikonRated(m.dropPrefix("NIKON").trimmingCharacters(in: .whitespaces))
        case .sony: return sonyRated(m)
        case .canon: return canonRated(m.dropPrefix("CANON").trimmingCharacters(in: .whitespaces))
        case .other: return nil
        }
    }

    private static func nikonRated(_ m: String) -> Rating? {
        let z = m.replacingOccurrences(of: " ", with: "")
        if z == "Z9" || z == "Z8" { return nil }
        if Rx.full("Z(6|7|6_2|7_2)", z) { return off(200_000) }
        if Rx.full("Z(5|5_2|6_3|F)", z) { return est(200_000) }
        if Rx.full("Z(30|50|50_2|FC)", z) { return est(100_000) }
        if Rx.full("D(3|3S|3X)", m) { return off(300_000) }
        if Rx.full("D(4|4S|5|6)", m) { return off(400_000) }
        if Rx.full("D(500|800|800E|810|810A|850)", m) { return off(200_000) }
        if m == "DF" || Rx.full("D(300|300S|600|610|700|750|780|7000|7100|7200|7500)", m) { return off(150_000) }
        if Rx.full("D(200|90|80)", m) { return off(100_000) }
        if Rx.full("D(5\\d00|3\\d00|40|40X|60)", m) { return est(100_000) }
        if Rx.full("D(2H|2HS|2X|2XS)", m) { return off(150_000) }
        if Rx.full("D(70|70S|50|100|1|1H|1X)", m) { return est(50_000) }
        return nil
    }

    private static func sonyRated(_ m: String) -> Rating? {
        if m == "ILCE-9M3" { return nil }
        if Rx.full("ILCE-(7RM2|7RM3A?|9)", m) { return off(500_000) }
        if Rx.full("ILCE-(1|1M2|9M2|7RM4A?|7RM5|7SM3)", m) { return est(500_000) }
        if Rx.full("ILCA-99M2", m) { return est(300_000) }
        if Rx.full("ILCE-(7|7R|7S|7M2|7SM2|7M3|7M4|7M5|7C|7CM2|7CR)", m) { return est(200_000) }
        if Rx.full("ILCE-(6300|6400|6500|6600|6700)", m) { return est(200_000) }
        if Rx.full("ILCE-(5000|5100|6000|6100|3000|3500|QX1)", m) { return est(100_000) }
        if m.hasPrefix("ZV-E10") { return est(100_000) }
        if m.hasPrefix("NEX-") || m.hasPrefix("SLT-") || m.hasPrefix("ILCA-") { return est(100_000) }
        return nil
    }

    private static func canonRated(_ m: String) -> Rating? {
        var s = m.dropPrefix("EOS").trimmingCharacters(in: .whitespaces)
        s = Rx.replace("\\s+", in: s, with: " ").replacingOccurrences(of: "R6M2", with: "R6 MARK II")
        if Rx.full("-?1D X MARK (II|III)", s) { return off(500_000) }
        if Rx.full("-?1D X", s) { return off(400_000) }
        if Rx.full("-?1D MARK IV", s) { return off(300_000) }
        if Rx.full("R(1|3|5|5 MARK II)", s) { return off(500_000) }
        if s == "R5 C" { return est(500_000) }
        if s == "R6" { return off(300_000) }
        if Rx.full("R6 MARK (II|III)", s) { return est(300_000) }
        if s == "R" { return off(200_000) }
        if s == "RP" { return off(100_000) }
        if s == "R7" { return est(200_000) }
        if Rx.full("R(8|10|50|50 V|100)", s) { return est(100_000) }
        if Rx.full("5D( MARK (II|III|IV))?|5DS R?", s) { return off(150_000) }
        if s == "7D MARK II" { return off(200_000) }
        if s == "7D" { return off(150_000) }
        if Rx.full("6D( MARK II)?|60D|70D|80D|750D|760D", s) { return off(100_000) }
        if s == "90D" { return est(100_000) }
        if Rx.full("\\d{2}D|\\d{3}D|\\d{4}D|M\\d.*|REBEL.*|KISS.*", s) { return est(100_000) }
        return nil
    }

    /// Where Canon R bodies keep their counter in a photo's CameraInfo block, or nil.
    /// A Canon EOS R mirrorless body ("Canon EOS RP", "Canon EOS R10"), whatever its photo support.
    public static func isCanonR(_ model: String?) -> Bool {
        (model ?? "").trimmingCharacters(in: .whitespaces).range(of: #"\bEOS R\w*$"#, options: [.regularExpression, .caseInsensitive]) != nil
    }

    /// Canon's newer generation (firmware "3-1.0.1", all EOS R bodies) keeps the count in its JPG photos;
    /// an EOS RP and a 250D stopped answering after the older bodies' event commands.
    public static func isPhotoCountCanon(_ model: String?, _ firmware: String?) -> Bool {
        isCanonR(model) || (firmware ?? "").trimmingCharacters(in: .whitespaces).hasPrefix("3-")
    }

    public static func canonPhotoOffset(_ model: String?) -> Int? {
        guard let m = model else { return nil }
        if Rx.has("\\bEOS (R6m2|R6 Mark II|R8|R50)$", m) { return 0x0d29 }
        if Rx.has("\\bEOS R[56]$", m) { return 0x0af1 }
        // R100: found by comparing photos 5 shots apart (623 -> 628), survives a card format
        // M50 Mark II: same spot, two photos 4 shots apart went 8908 -> 8912
        if Rx.has("\\bEOS (R100|M50m2|M50 Mark II)$", m) { return 0x0acf }
        // RP: found by comparing two photos 4 shots apart (2343 -> 2347)
        if Rx.has("\\bEOS RP$", m) { return 0x0960 }
        // From review-site sample JPGs (Imaging Resource), checked against the known R5/R6/R50/R8 spots
        if Rx.has("\\bEOS (R7|R10)$", m) { return 0x0c93 }
        if Rx.has("\\bEOS M6 Mark II$", m) { return 0x0ac7 }
        if Rx.has("\\bEOS (M50|Kiss M)$", m) { return 0x0188 }
        if Rx.has("\\bEOS R3$", m) { return 0x0fa9 }
        // ExifTool (CameraInfoG5XII), JPEG files
        if Rx.has("\\bPowerShot G5 X Mark II$", m) { return 0x0293 }
        return nil
    }

    public enum Wear: String, Sendable {
        case likeNew = "Like new", good = "Good", used = "Used", high = "High wear", beyond = "Beyond rating"

        public var french: String {
            switch self {
            case .likeNew: return "Comme neuf"
            case .good: return "Bon état"
            case .used: return "Usure moyenne"
            case .high: return "Usure élevée"
            case .beyond: return "Au-delà de la durée nominale"
            }
        }
    }

    public static func wear(_ count: Int, _ rated: Int) -> Wear {
        let pct = Double(count) / Double(rated)
        if pct < 0.10 { return .likeNew }
        if pct < 0.40 { return .good }
        if pct < 0.75 { return .used }
        if pct <= 1.0 { return .high }
        return .beyond
    }

    public static func wearPercent(_ count: Int, _ rated: Int) -> Int { Int(Double(count) * 100.0 / Double(rated)) }
}
