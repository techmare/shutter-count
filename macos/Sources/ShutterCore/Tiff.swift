import Foundation

struct IfdEntry {
    let tag: Int
    let type: Int
    let count: Int
    /// Absolute position of the value bytes in the buffer.
    let valuePos: Int
    let size: Int
}

extension Array where Element == IfdEntry {
    func find(_ tag: Int) -> IfdEntry? { first { $0.tag == tag } }
}

/// Minimal, bounds-checked TIFF/EXIF reader. Every read returns nil on short or corrupt data.
final class Tiff {
    let buf: [UInt8]
    /// Absolute position that IFD value offsets are relative to.
    let base: Int
    var little: Bool

    init(_ buf: [UInt8], base: Int, little: Bool) {
        self.buf = buf; self.base = base; self.little = little
    }

    func u8(_ p: Int) -> Int? { p >= 0 && p < buf.count ? Int(buf[p]) : nil }

    func u16(_ p: Int) -> Int? {
        guard p >= 0, p + 2 <= buf.count else { return nil }
        let a = Int(buf[p]), b = Int(buf[p + 1])
        return little ? a | (b << 8) : (a << 8) | b
    }

    func u32(_ p: Int) -> Int? {
        guard p >= 0, p + 4 <= buf.count else { return nil }
        let b0 = Int(buf[p]), b1 = Int(buf[p + 1]), b2 = Int(buf[p + 2]), b3 = Int(buf[p + 3])
        return little ? b0 | (b1 << 8) | (b2 << 16) | (b3 << 24) : (b0 << 24) | (b1 << 16) | (b2 << 8) | b3
    }

    static func typeSize(_ t: Int) -> Int {
        switch t {
        case 1, 2, 6, 7: return 1
        case 3, 8: return 2
        case 4, 9, 11, 13: return 4
        case 5, 10, 12: return 8
        default: return 1
        }
    }

    func readIfd(_ pos: Int) -> [IfdEntry]? {
        guard let n = u16(pos), n > 0, n <= 1000, pos + 2 + n * 12 <= buf.count else { return nil }
        var out: [IfdEntry] = []
        out.reserveCapacity(n)
        for i in 0..<n {
            let e = pos + 2 + i * 12
            guard let tag = u16(e), let type = u16(e + 2), let cnt = u32(e + 4) else { return nil }
            let size = Tiff.typeSize(type) * cnt
            let vp: Int
            if size <= 4 { vp = e + 8 } else {
                guard let off = u32(e + 8) else { return nil }
                vp = base + off
            }
            out.append(IfdEntry(tag: tag, type: type, count: cnt, valuePos: vp, size: size))
        }
        return out
    }

    func looksLikeIfd(_ pos: Int) -> Bool {
        guard let e = readIfd(pos), !e.isEmpty else { return false }
        return e.allSatisfy { (1...13).contains($0.type) }
    }

    func bytes(_ e: IfdEntry) -> [UInt8]? {
        guard e.size > 0, e.valuePos >= 0, e.valuePos + e.size <= buf.count else { return nil }
        return Array(buf[e.valuePos..<(e.valuePos + e.size)])
    }

    func string(_ e: IfdEntry) -> String? {
        guard let b = bytes(e) else { return nil }
        let end = b.firstIndex(of: 0) ?? b.count
        let s = String(decoding: b[0..<end], as: UTF8.self).trimmingCharacters(in: .whitespaces)
        return s.isEmpty ? nil : s
    }

    /// First integer value of a SHORT or LONG entry.
    func int(_ e: IfdEntry) -> Int? {
        switch e.type {
        case 3, 8: return u16(e.valuePos)
        case 4, 9, 13: return u32(e.valuePos)
        case 1, 7: return u8(e.valuePos)
        default: return nil
        }
    }
}

/// Sony's maker-note substitution cipher: c = b^3 mod 249 for bytes 2...247.
enum SonyCipher {
    private static let dec: [UInt8] = {
        var t = (0..<256).map { UInt8($0) }
        for b in 2...247 { t[(b * b * b) % 249] = UInt8(b) }
        return t
    }()
    private static let enc: [UInt8] = {
        var t = (0..<256).map { UInt8($0) }
        for b in 2...247 { t[b] = UInt8((b * b * b) % 249) }
        return t
    }()
    static func decipher(_ s: [UInt8]) -> [UInt8] { s.map { dec[Int($0)] } }
    static func encipher(_ s: [UInt8]) -> [UInt8] { s.map { enc[Int($0)] } }
}
