import Foundation

public struct PTPResponse: Sendable {
    public let code: Int
    public let params: [Int]
    public let data: [UInt8]
    public var ok: Bool { code == 0x2001 }
    public init(code: Int, params: [Int], data: [UInt8]) { self.code = code; self.params = params; self.data = data }
}

#if !LEGACY
/// Something that can run one PTP operation (ImageCaptureCore on the Mac, a fake in tests).
@available(macOS 10.15, *)
public protocol PTPChannel: AnyObject {
    func transact(_ op: Int, _ params: [Int], dataOut: [UInt8]?) async throws -> PTPResponse
}
#endif

public struct PTPDeviceInfo: Sendable {
    public let vendorExtensionId: Int
    public let operations: Set<Int>
    public let properties: Set<Int>
    public let manufacturer: String
    public let model: String
    public let version: String
    public let serial: String
}

public enum PTP {
    /// Command container: length, type 1, code, transaction id, params (all little-endian).
    public static func commandContainer(_ op: Int, tid: Int, params: [Int]) -> [UInt8] {
        var b: [UInt8] = []
        appendLE32(&b, 12 + 4 * params.count)
        appendLE16(&b, 1)
        appendLE16(&b, op)
        appendLE32(&b, tid)
        for p in params { appendLE32(&b, p) }
        return b
    }

    /// Response container -> (code, params).
    public static func parseResponse(_ d: [UInt8]) -> (Int, [Int])? {
        guard d.count >= 12 else { return nil }
        let code = d.le16(6)
        var ps: [Int] = []
        var i = 12
        while i + 4 <= min(d.count, d.le32(0)) { ps.append(d.le32(i)); i += 4 }
        return (code, ps)
    }

    /// Drops a data-container header if the transport left one on.
    public static func stripDataHeader(_ d: [UInt8]) -> [UInt8] {
        if d.count >= 12, d.le32(0) == d.count, d.le16(4) == 2 { return Array(d[12...]) }
        return d
    }

    public static func parseDeviceInfo(_ d: [UInt8]) -> PTPDeviceInfo {
        var r = Reader(d)
        _ = r.u16()
        let ext = r.u32()
        _ = r.u16(); _ = r.str(); _ = r.u16()
        let ops = Set(r.arr16())
        _ = r.arr16()
        let props = Set(r.arr16())
        _ = r.arr16(); _ = r.arr16()
        return PTPDeviceInfo(vendorExtensionId: ext, operations: ops, properties: props,
                             manufacturer: r.str(), model: r.str(), version: r.str(), serial: r.str())
    }

    /// Sequential little-endian reader for PTP datasets. Short data reads as zero / empty.
    public struct Reader {
        let d: [UInt8]
        var pos = 0
        public init(_ d: [UInt8]) { self.d = d }
        public mutating func u8() -> Int { defer { pos += 1 }; return pos < d.count ? Int(d[pos]) : 0 }
        public mutating func u16() -> Int { defer { pos += 2 }; return pos + 2 <= d.count ? d.le16(pos) : 0 }
        public mutating func u32() -> Int { defer { pos += 4 }; return pos + 4 <= d.count ? d.le32(pos) : 0 }
        public mutating func arr16() -> [Int] {
            let n = u32()
            guard n >= 0, n <= 4096 else { return [] }
            return (0..<n).map { _ in u16() }
        }
        public mutating func str() -> String {
            let n = u8()
            guard n > 0 else { return "" }
            var units: [UInt16] = []
            for _ in 0..<n { let c = u16(); if c != 0 { units.append(UInt16(c)) } }
            return String(decoding: units, as: UTF16.self).trimmingCharacters(in: .whitespaces)
        }
    }
}

/// Result of reading an older Canon's own counter over USB.
public struct CanonCounterResult: Sendable {
    public let count: Int?
    public let serial: String?
    public let method: String?
    public let log: [String]
}

/// Older Canon EOS bodies (DIGIC 5 and earlier) expose their shutter counter as property D1AC,
/// directly or through the EOS event stream, which also carries the body serial (D1AF).
/// Only read-only status operations are used; no factory or write commands exist here.
public final class CanonCounterReader {
    /// Callback-style channel for systems without Swift concurrency (Mojave).
    public typealias Send = (_ op: Int, _ params: [Int], _ done: @escaping (PTPResponse?) -> Void) -> Void

    /// Same steps as `read(operations:)`, driven by callbacks so it runs on macOS 10.14.
    public static func read(operations: Set<Int>, send: @escaping Send, completion: @escaping (CanonCounterResult) -> Void) {
        var log: [String] = []
        var count: Int?
        var serial: String?
        var method: String?
        func hex(_ v: Int) -> String { String(format: "%04X", v) }
        func finish() { completion(CanonCounterResult(count: count, serial: serial, method: method, log: log)) }

        func events() {
            guard [opSetRemoteMode, opSetEventMode, opGetEvent].allSatisfy(operations.contains) else {
                log.append("EOS event stream: not offered by this camera"); finish(); return
            }
            func reset() {
                send(opSetEventMode, [0]) { _ in send(opSetRemoteMode, [0]) { _ in finish() } }
            }
            func poll(_ attempt: Int) {
                guard attempt < 4 else { reset(); return }
                send(opGetEvent, []) { r in
                    guard let r, r.ok else { log.append("GetEvent: \(r.map { hex($0.code) } ?? "no answer")"); reset(); return }
                    if count == nil, let v = findProp(r.data, propCounter), v.count >= 4, (1...20_000_000).contains(v.le32(0)) {
                        count = v.le32(0); method = "Standard counter (EOS event)"
                    }
                    if serial == nil, let v = findProp(r.data, propSerial) { serial = ascii(v) }
                    log.append("GetEvent #\(attempt + 1): \(r.data.count) bytes")
                    if (count != nil && serial != nil) || r.data.count <= 8 { reset() } else { poll(attempt + 1) }
                }
            }
            func request(_ props: [Int]) {
                guard let p = props.first, operations.contains(opRequestProp) else { poll(0); return }
                send(opRequestProp, [p]) { r in
                    log.append("RequestDevicePropValue \(hex(p)): \(r.map { hex($0.code) } ?? "no answer")")
                    request(Array(props.dropFirst()))
                }
            }
            send(opSetRemoteMode, [1]) { remote in
                send(opSetEventMode, [1]) { event in
                    log.append("SetRemoteMode: \(remote.map { hex($0.code) } ?? "-"), SetEventMode: \(event.map { hex($0.code) } ?? "-")")
                    if remote?.ok == true && event?.ok == true { request([propCounter, propSerial]) } else { reset() }
                }
            }
        }

        send(opGetPropValue, [propCounter]) { r in
            log.append("GetDevicePropValue D1AC: \(r.map { hex($0.code) } ?? "no answer"), \(r?.data.count ?? 0) bytes")
            if let r, r.ok, r.data.count >= 4, (1...20_000_000).contains(r.data.le32(0)) {
                count = r.data.le32(0); method = "Standard counter (PTP property)"
            }
            events()
        }
    }

    public static let propCounter = 0xD1AC
    public static let propSerial = 0xD1AF
    static let opGetPropValue = 0x1015
    static let opSetRemoteMode = 0x9114
    static let opSetEventMode = 0x9115
    static let opGetEvent = 0x9116
    static let opRequestProp = 0x9127
    static let evtPropChanged = 0xC189

#if !LEGACY
    private let ch: AnyObject
    private var log: [String] = []

    @available(macOS 10.15, *)
    public init(_ channel: PTPChannel) { ch = channel }

    private func hex(_ v: Int) -> String { String(format: "%04X", v) }

    @available(macOS 10.15, *)
    public func read(operations: Set<Int>) async -> CanonCounterResult {
        let ch = self.ch as! PTPChannel
        log = []
        var count: Int?
        var serial: String?
        var method: String?
        do {
            let r = try await ch.transact(Self.opGetPropValue, [Self.propCounter], dataOut: nil)
            log.append("GetDevicePropValue D1AC: \(hex(r.code)), \(r.data.count) bytes")
            if r.ok, r.data.count >= 4 {
                let v = r.data.le32(0)
                if (1...20_000_000).contains(v) { count = v; method = "Standard counter (PTP property)" }
            }
        } catch { log.append("GetDevicePropValue D1AC: \(error.localizedDescription)") }

        if [Self.opSetRemoteMode, Self.opSetEventMode, Self.opGetEvent].allSatisfy(operations.contains) {
            do {
                let remote = try await ch.transact(Self.opSetRemoteMode, [1], dataOut: nil)
                let event = try await ch.transact(Self.opSetEventMode, [1], dataOut: nil)
                log.append("SetRemoteMode: \(hex(remote.code)), SetEventMode: \(hex(event.code))")
                if remote.ok && event.ok {
                    if operations.contains(Self.opRequestProp) {
                        for p in [Self.propCounter, Self.propSerial] {
                            let rq = try await ch.transact(Self.opRequestProp, [p], dataOut: nil)
                            log.append("RequestDevicePropValue \(hex(p)): \(hex(rq.code))")
                        }
                    }
                    for attempt in 0..<4 {
                        let r = try await ch.transact(Self.opGetEvent, [], dataOut: nil)
                        guard r.ok else { log.append("GetEvent: \(hex(r.code))"); break }
                        if count == nil, let v = Self.findProp(r.data, Self.propCounter), v.count >= 4, (1...20_000_000).contains(v.le32(0)) {
                            count = v.le32(0); method = "Standard counter (EOS event)"
                        }
                        if serial == nil, let v = Self.findProp(r.data, Self.propSerial) { serial = Self.ascii(v) }
                        log.append("GetEvent #\(attempt + 1): \(r.data.count) bytes")
                        if (count != nil && serial != nil) || r.data.count <= 8 { break }
                    }
                }
                _ = try? await ch.transact(Self.opSetEventMode, [0], dataOut: nil)
                _ = try? await ch.transact(Self.opSetRemoteMode, [0], dataOut: nil)
            } catch { log.append("EOS events: \(error.localizedDescription)") }
        } else {
            log.append("EOS event stream: not offered by this camera")
        }
        return CanonCounterResult(count: count, serial: serial, method: method, log: log)
    }

#endif

    /// Value bytes of a property-changed record in a Canon EOS GetEvent dataset.
    public static func findProp(_ d: [UInt8], _ wanted: Int) -> [UInt8]? {
        var p = 0
        while p + 8 <= d.count {
            let size = d.le32(p), type = d.le32(p + 4)
            if size < 8 || type == 0 || p + size > d.count { break }
            if type == evtPropChanged, size >= 12, d.le32(p + 8) == wanted { return Array(d[(p + 12)..<(p + size)]) }
            p += size
        }
        return nil
    }

    public static func ascii(_ b: [UInt8]) -> String? {
        let end = b.firstIndex(of: 0) ?? b.count
        let s = String(decoding: b[0..<end], as: UTF8.self).trimmingCharacters(in: .whitespaces)
        return (4...32).contains(s.count) && s.allSatisfy({ $0.isLetter || $0.isNumber }) ? s : nil
    }
}
