import Foundation

public enum Fmt {
    private static let number: NumberFormatter = {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.locale = Locale(identifier: "fr_FR")
        return f
    }()

    /// 48633 -> "48 633"
    public static func count(_ n: Int) -> String {
        (number.string(from: NSNumber(value: n)) ?? String(n))
            .replacingOccurrences(of: "\u{202F}", with: " ")
            .replacingOccurrences(of: "\u{00A0}", with: " ")
    }

    private static let dateF: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr_FR")
        f.dateFormat = "dd/MM/yyyy HH:mm"
        return f
    }()

    public static func date(_ d: Date) -> String { dateF.string(from: d) }

    /// "20261026T141500" -> "26/10/2026 14:15"
    public static func ptpDate(_ s: String) -> String? {
        guard let g = Rx.groups("^(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})", s) else { return nil }
        return "\(g[2])/\(g[1])/\(g[0]) \(g[3]):\(g[4])"
    }
}
