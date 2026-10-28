import XCTest
@testable import ShutterCore

final class ShutterCoreTests: XCTestCase {

    /// Little-endian TIFF builder for synthetic photos.
    private struct Buf {
        var b = [UInt8](repeating: 0, count: 16384)
        var pos = 0
        mutating func put(_ bytes: [UInt8]) { for x in bytes { b[pos] = x; pos += 1 } }
        mutating func u16(_ v: Int) { put([UInt8(v & 0xff), UInt8((v >> 8) & 0xff)]) }
        mutating func u32(_ v: Int) { put([UInt8(v & 0xff), UInt8((v >> 8) & 0xff), UInt8((v >> 16) & 0xff), UInt8((v >> 24) & 0xff)]) }
        mutating func str(_ s: String) { put(Array(s.utf8) + [0]) }
        mutating func entry(_ tag: Int, _ type: Int, _ n: Int, _ v: Int) { u16(tag); u16(type); u32(n); u32(v) }
    }

    private func sonyTiff(_ model: String, _ offset: Int, _ count: Int, header: Bool = true) -> [UInt8] {
        var t = Buf()
        t.put(Array("II".utf8)); t.u16(42); t.u32(8)
        t.pos = 8; t.u16(3)
        t.entry(0x10F, 2, 5, 200); t.entry(0x110, 2, model.count + 1, 220); t.entry(0x8769, 4, 1, 300); t.u32(0)
        t.pos = 200; t.str("SONY")
        t.pos = 220; t.str(model)
        t.pos = 300; t.u16(1); t.entry(0x927C, 7, (header ? 12 : 0) + 18, 400); t.u32(0)
        t.pos = 400
        if header { t.put(Array("SONY DSC ".utf8) + [0, 0, 0]) }
        t.u16(1); t.entry(0x9050, 7, 944, 1000); t.u32(0)
        var plain = [UInt8](repeating: 0, count: 944)
        for i in 0..<3 { plain[offset + i] = UInt8((count >> (8 * i)) & 0xff) }
        plain[offset + 3] = 0x55 // high byte is masked off
        t.pos = 1000; t.put(SonyCipher.encipher(plain))
        return t.b
    }

    private func canonTiff(_ model: String, _ offset: Int, _ count: Int) -> [UInt8] {
        var t = Buf()
        t.put(Array("II".utf8)); t.u16(42); t.u32(8)
        t.pos = 8; t.u16(3)
        t.entry(0x10F, 2, 6, 200); t.entry(0x110, 2, model.count + 1, 220); t.entry(0x8769, 4, 1, 300); t.u32(0)
        t.pos = 200; t.str("Canon")
        t.pos = 220; t.str(model)
        t.pos = 300; t.u16(1); t.entry(0x927C, 7, 30, 400); t.u32(0)
        t.pos = 400; t.u16(2); t.entry(0x000C, 4, 1, 123456789); t.entry(0x000D, 7, offset + 64, 1000); t.u32(0)
        t.pos = 1000 + offset; t.u32(count)
        return t.b
    }

    func testCipherRoundTrip() {
        let all = (0..<256).map { UInt8($0) }
        XCTAssertEqual(SonyCipher.decipher(SonyCipher.encipher(all)), all)
        XCTAssertEqual(SonyCipher.encipher([7])[0], 0x5e)
    }

    func testSonyModels() {
        let cases: [(String, Int, Int)] = [("ILCE-7M3", 0x3a, 23456), ("ILCE-7M4", 0x3a, 777), ("ILCE-6400", 0x3a, 9999),
                                           ("ILCE-6700", 0x0a, 4321), ("ILCE-6000", 0x4c, 87654), ("SLT-A99V", 0x32, 5000)]
        for (m, off, c) in cases {
            for h in [true, false] { XCTAssertEqual(PhotoReader.read(sonyTiff(m, off, c, header: h)).shutterCount, c, m) }
        }
        XCTAssertNil(PhotoReader.read(sonyTiff("ILCE-9M3", 0x3a, 10)).shutterCount)
    }

    func testCanonPhotos() {
        XCTAssertEqual(PhotoReader.read(canonTiff("Canon EOS R50", 0x0d29, 12345)).shutterCount, 12345)
        XCTAssertEqual(PhotoReader.read(canonTiff("Canon EOS R100", 0x0acf, 623)).shutterCount, 623)
        XCTAssertEqual(PhotoReader.read(canonTiff("Canon EOS RP", 0x0960, 2347)).shutterCount, 2347)
        XCTAssertEqual(PhotoReader.read(canonTiff("Canon EOS M50m2", 0x0acf, 8912)).shutterCount, 8912)
        XCTAssertEqual(PhotoReader.read(canonTiff("Canon EOS R6", 0x0af1, 45678)).shutterCount, 45678)
        let r = PhotoReader.read(canonTiff("Canon EOS R8", 0x0d29, 777))
        XCTAssertEqual(r.serial, "123456789")
        XCTAssertTrue(r.includesElectronic)
        XCTAssertNil(PhotoReader.read(canonTiff("Canon EOS 5D Mark IV", 0x0d29, 5)).shutterCount)
    }

    func testGarbage() {
        XCTAssertNil(PhotoReader.read([UInt8]()).shutterCount)
        XCTAssertNil(PhotoReader.read((0..<5000).map { UInt8(($0 * 31) & 0xff) }).shutterCount)
    }

    func testRatings() {
        XCTAssertEqual(CameraModels.rating(.nikon, "NIKON D750"), .init(value: 150_000, official: true))
        XCTAssertEqual(CameraModels.rating(.nikon, "NIKON Z 6_2"), .init(value: 200_000, official: true))
        XCTAssertEqual(CameraModels.rating(.canon, "Canon EOS 4000D"), .init(value: 100_000, official: false))
        XCTAssertEqual(CameraModels.rating(.canon, "Canon EOS-1D X Mark II"), .init(value: 500_000, official: true))
        XCTAssertEqual(CameraModels.rating(.canon, "Canon EOS R6m2"), .init(value: 300_000, official: false))
        XCTAssertEqual(CameraModels.rating(.sony, "ILCE-7RM3"), .init(value: 500_000, official: true))
        XCTAssertNil(CameraModels.rating(.nikon, "NIKON Z 8"))
        XCTAssertEqual(CameraModels.displayName(.nikon, "NIKON Z 6_2"), "Nikon Z6 II")
        XCTAssertEqual(CameraModels.displayName(.sony, "ILCE-7M3"), "Sony α7 III")
    }

    func testFormat() {
        XCTAssertEqual(Fmt.count(48633), "48 633")
        XCTAssertEqual(Fmt.ptpDate("20261026T141500"), "26/10/2026 14:15")
        XCTAssertEqual(PhotoReader.prettyExifDate("2025:03:18 14:55:02"), "18/03/2025 14:55")
    }

    func testCanonEventsAndPTP() {
        var ev: [UInt8] = []
        appendLE32(&ev, 16); appendLE32(&ev, 0xC189); appendLE32(&ev, 0xD1AC); appendLE32(&ev, 535)
        let sn = Array("283032001879".utf8) + [0]
        appendLE32(&ev, 12 + sn.count); appendLE32(&ev, 0xC189); appendLE32(&ev, 0xD1AF); ev += sn
        appendLE32(&ev, 8); appendLE32(&ev, 0)
        XCTAssertEqual(CanonCounterReader.findProp(ev, 0xD1AC)?.le32(0), 535)
        XCTAssertEqual(CanonCounterReader.findProp(ev, 0xD1AF).flatMap(CanonCounterReader.ascii), "283032001879")

        let cmd = PTP.commandContainer(0x1015, tid: 7, params: [0xD1AC])
        XCTAssertEqual(cmd.count, 16)
        XCTAssertEqual(cmd.le16(4), 1); XCTAssertEqual(cmd.le16(6), 0x1015); XCTAssertEqual(cmd.le32(12), 0xD1AC)
        var resp: [UInt8] = []
        appendLE32(&resp, 12); appendLE16(&resp, 3); appendLE16(&resp, 0x2001); appendLE32(&resp, 7)
        XCTAssertEqual(PTP.parseResponse(resp)?.0, 0x2001)
        var data: [UInt8] = []
        appendLE32(&data, 16); appendLE16(&data, 2); appendLE16(&data, 0x1015); appendLE32(&data, 7); appendLE32(&data, 535)
        XCTAssertEqual(PTP.stripDataHeader(data), [0x17, 0x02, 0, 0])
    }

    /// The callback version (used by the Mojave/Catalina app) gives the same result.
    func testCanonCounterReaderCallbacks() {
        var ops: [Int] = []
        let send: CanonCounterReader.Send = { op, _, done in
            ops.append(op)
            if op == 0x1015 { done(PTPResponse(code: 0x2005, params: [], data: [])); return }
            if op == 0x9116 {
                var ev: [UInt8] = []
                appendLE32(&ev, 16); appendLE32(&ev, 0xC189); appendLE32(&ev, 0xD1AC); appendLE32(&ev, 535)
                let sn = Array("283032001879".utf8) + [0]
                appendLE32(&ev, 12 + sn.count); appendLE32(&ev, 0xC189); appendLE32(&ev, 0xD1AF); ev += sn
                appendLE32(&ev, 8); appendLE32(&ev, 0)
                done(PTPResponse(code: 0x2001, params: [], data: ev)); return
            }
            done(PTPResponse(code: 0x2001, params: [], data: []))
        }
        var result: CanonCounterResult?
        CanonCounterReader.read(operations: [0x1015, 0x9114, 0x9115, 0x9116, 0x9127], send: send) { result = $0 }
        XCTAssertEqual(result?.count, 535)
        XCTAssertEqual(result?.serial, "283032001879")
        XCTAssertEqual(result?.method, "Standard counter (EOS event)")
        XCTAssertEqual(ops.suffix(2), [0x9115, 0x9114], "modes are reset at the end")
        XCTAssertFalse(ops.contains(0x9052))
    }

    /// A camera that answers the standard property: never touches anything else.
    func testCanonCounterReader() async {
        final class Fake: PTPChannel {
            var ops: [Int] = []
            func transact(_ op: Int, _ params: [Int], dataOut: [UInt8]?) async throws -> PTPResponse {
                ops.append(op)
                if op == 0x1015 { return PTPResponse(code: 0x2001, params: [], data: [0x17, 0x02, 0, 0]) }
                if op == 0x9116 {
                    var ev: [UInt8] = []
                    let sn = Array("123456789012".utf8) + [0]
                    appendLE32(&ev, 12 + sn.count); appendLE32(&ev, 0xC189); appendLE32(&ev, 0xD1AF); ev += sn
                    appendLE32(&ev, 8); appendLE32(&ev, 0)
                    return PTPResponse(code: 0x2001, params: [], data: ev)
                }
                return PTPResponse(code: 0x2001, params: [], data: [])
            }
        }
        let fake = Fake()
        let r = await CanonCounterReader(fake).read(operations: [0x1015, 0x9114, 0x9115, 0x9116, 0x9127])
        XCTAssertEqual(r.count, 535)
        XCTAssertEqual(r.serial, "123456789012")
        XCTAssertFalse(fake.ops.contains(0x9052), "never sends shutter-unit commands")
        XCTAssertFalse(fake.ops.contains(0x9053))
    }
}
