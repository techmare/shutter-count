import Foundation

/// Small regex helpers so the model tables read like the Android ones.
enum Rx {
    private static var cache: [String: NSRegularExpression] = [:]
    private static let lock = NSLock()

    private static func compile(_ pattern: String, _ caseInsensitive: Bool) -> NSRegularExpression {
        let key = (caseInsensitive ? "i:" : "s:") + pattern
        lock.lock(); defer { lock.unlock() }
        if let r = cache[key] { return r }
        let r = try! NSRegularExpression(pattern: pattern, options: caseInsensitive ? [.caseInsensitive] : [])
        cache[key] = r
        return r
    }

    /// True when the pattern occurs anywhere in `s` (Kotlin `containsMatchIn`).
    static func has(_ pattern: String, _ s: String, ignoreCase: Bool = false) -> Bool {
        let r = compile(pattern, ignoreCase)
        return r.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil
    }

    /// True when the whole of `s` matches (Kotlin `matches`).
    static func full(_ pattern: String, _ s: String) -> Bool {
        has("^(?:\(pattern))$", s)
    }

    /// Capture groups of the first match, or nil.
    static func groups(_ pattern: String, _ s: String) -> [String]? {
        let r = compile(pattern, false)
        guard let m = r.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) else { return nil }
        return (1..<m.numberOfRanges).map { i in
            Range(m.range(at: i), in: s).map { String(s[$0]) } ?? ""
        }
    }

    static func replace(_ pattern: String, in s: String, with template: String) -> String {
        let r = compile(pattern, false)
        return r.stringByReplacingMatches(in: s, range: NSRange(s.startIndex..., in: s), withTemplate: template)
    }
}

extension Array where Element == UInt8 {
    func startsWith(_ s: String, at pos: Int) -> Bool {
        let b = Array(s.utf8)
        if pos < 0 || pos + b.count > count { return false }
        for i in 0..<b.count where self[pos + i] != b[i] { return false }
        return true
    }

    func le16(_ p: Int) -> Int { Int(self[p]) | (Int(self[p + 1]) << 8) }
    func le32(_ p: Int) -> Int { Int(self[p]) | (Int(self[p + 1]) << 8) | (Int(self[p + 2]) << 16) | (Int(self[p + 3]) << 24) }
}

func appendLE16(_ a: inout [UInt8], _ v: Int) { a.append(UInt8(v & 0xff)); a.append(UInt8((v >> 8) & 0xff)) }
func appendLE32(_ a: inout [UInt8], _ v: Int) {
    a.append(UInt8(v & 0xff)); a.append(UInt8((v >> 8) & 0xff))
    a.append(UInt8((v >> 16) & 0xff)); a.append(UInt8((v >> 24) & 0xff))
}
