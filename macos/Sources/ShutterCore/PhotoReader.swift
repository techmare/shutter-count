import Foundation

/// A result read from a photo file.
public struct PhotoReading: Sendable {
    public let brand: Brand
    public let make: String?
    public let model: String?
    public let displayName: String
    /// Main shutter count, or nil when the file doesn't carry one.
    public let shutterCount: Int?
    public let ratedLife: Int?
    /// True when `ratedLife` is the maker's published figure, false for an estimate.
    public let ratedOfficial: Bool
    public let serial: String?
    public let dateTaken: String?
    public let software: String?
    public let details: [(String, String)]
    public let warnings: [String]
    /// Why there is no count, when `shutterCount` is nil.
    public let problem: String?

    /// The counter includes electronic-shutter shots (Canon R, Nikon Z).
    public var includesElectronic: Bool {
        brand == .canon || details.contains { $0.1.contains("includes electronic") }
    }
}

/// Reads the shutter count stored inside Nikon, Sony and Canon R photos (JPEG, NEF, ARW, CR3).
/// A line-for-line port of the Android app's PhotoShutterReader.
public enum PhotoReader {

    static let tagMake = 0x010F, tagModel = 0x0110, tagSoftware = 0x0131, tagExif = 0x8769
    static let tagMakerNote = 0x927C, tagDateOriginal = 0x9003, tagBodySerial = 0xA431

    static let missingMakerNote =
        "This photo is missing the camera's private data (maker notes). That usually means it was resized, edited or sent through an app like WhatsApp. Use the original file from the memory card."
    static let canonUnsupported =
        "This Canon doesn't store a readable shutter count in photos. Photo reading works for the EOS R50, R100, RP, R8, R6, R6 Mark II, R5 and M50 Mark II. Older Canons (DIGIC 5 and before) can be read over USB."

    public static func read(_ data: Data) -> PhotoReading { read(Array(data)) }

    public static func read(_ data: [UInt8]) -> PhotoReading {
        if isCr3(data) { return readCr3(data) }
        guard let ts = findTiffStart(data) else {
            return failure("This file has no camera data (EXIF). Use the original JPEG or RAW file straight from the camera.")
        }
        let little: Bool
        if data[ts] == 0x49 && data[ts + 1] == 0x49 { little = true }
        else if data[ts] == 0x4D && data[ts + 1] == 0x4D { little = false }
        else { return failure("Unrecognised photo format.") }
        let tiff = Tiff(data, base: ts, little: little)
        guard let ifd0Off = tiff.u32(ts + 4), let ifd0 = tiff.readIfd(ts + ifd0Off) else { return failure("Corrupt photo data.") }

        let make = ifd0.find(tagMake).flatMap(tiff.string)
        let model = ifd0.find(tagModel).flatMap(tiff.string)
        let software = ifd0.find(tagSoftware).flatMap(tiff.string)
        let exif = ifd0.find(tagExif).flatMap(tiff.int).flatMap { tiff.readIfd(ts + $0) }
        let dateTaken = exif?.find(tagDateOriginal).flatMap(tiff.string)
        let bodySerial = exif?.find(tagBodySerial).flatMap(tiff.string)
        let makerNote = exif?.find(tagMakerNote)

        let brand = CameraModels.brandOf(make)
        let ctx = Ctx(tiff: tiff, brand: brand, make: make, model: model, software: software, dateTaken: dateTaken, bodySerial: bodySerial)
        var warnings: [String] = []
        if let sw = software, Rx.has("photoshop|lightroom|snapseed|gimp|capture one|picsart|whatsapp|instagram", sw, ignoreCase: true) {
            warnings.append("This photo was edited with \(sw). Edited photos may have missing or altered data; use an untouched file from the camera.")
        }
        switch brand {
        case .nikon: return readNikon(ctx, makerNote, &warnings)
        case .sony: return readSony(ctx, makerNote, &warnings)
        case .canon: return readCanon(ctx, makerNote, &warnings)
        case .other:
            return ctx.result(nil, warnings: warnings, problem: make == nil
                ? "This photo has no camera make. It may come from a phone, an editor or a messaging app."
                : "\(make!) cameras are not supported. This app reads Nikon, Sony and recent Canon R photos, and older Canons over USB.")
        }
    }

    // MARK: Canon

    static func readCanon(_ ctx: Ctx, _ mn: IfdEntry?, _ warnings: inout [String]) -> PhotoReading {
        guard let offset = CameraModels.canonPhotoOffset(ctx.model) else { return ctx.result(nil, warnings: warnings, problem: canonUnsupported) }
        guard let mn else { return ctx.result(nil, warnings: warnings, problem: missingMakerNote) }
        let t = Tiff(ctx.tiff.buf, base: ctx.tiff.base, little: ctx.tiff.little)
        if !t.looksLikeIfd(mn.valuePos) { t.little.toggle() }
        guard let ifd = t.readIfd(mn.valuePos) else { return ctx.result(nil, warnings: warnings, problem: missingMakerNote) }
        return canonResult(ctx, t, ifd, offset, warnings)
    }

    static func canonResult(_ ctx: Ctx, _ t: Tiff, _ ifd: [IfdEntry], _ offset: Int, _ warnings: [String]) -> PhotoReading {
        guard let info = ifd.find(0x000D).flatMap(t.bytes), info.count >= offset + 4 else {
            return ctx.result(nil, warnings: warnings, problem: missingMakerNote)
        }
        guard let count = Tiff(info, base: 0, little: t.little).u32(offset), count > 0, count <= 5_000_000 else {
            return ctx.result(nil, warnings: warnings, problem: "Couldn't find a valid shutter count in this Canon photo. Try a JPEG or CR3 taken just now.")
        }
        let serial = ifd.find(0x000C).flatMap(t.int).flatMap { $0 > 0 ? String($0) : nil } ?? ctx.bodySerial
        return ctx.result(count, serial: serial, details: [("Note", "On this Canon the count includes electronic-shutter shots, so wear may look higher than the mechanical shutter's real use.")], warnings: warnings)
    }

    static func isCr3(_ d: [UInt8]) -> Bool { d.count > 12 && d.startsWith("ftyp", at: 4) && d.startsWith("crx ", at: 8) }

    /// CR3: metadata lives in CMT boxes (CMT1 = IFD0, CMT2 = EXIF, CMT3 = Canon maker note), each a small TIFF.
    static func readCr3(_ d: [UInt8]) -> PhotoReading {
        func box(_ type: String) -> Int? {
            let first = Array(type.utf8)[0]
            let limit = min(d.count - 8, 4 * 1024 * 1024)
            var i = 4
            while i < limit {
                if d[i] == first && d.startsWith(type, at: i) { return i + 4 }
                i += 1
            }
            return nil
        }
        func tiffAt(_ start: Int) -> (Tiff, [IfdEntry])? {
            guard start + 8 <= d.count else { return nil }
            let t = Tiff(d, base: start, little: d[start] == 0x49)
            guard let off = t.u32(start + 4), let ifd = t.readIfd(start + off) else { return nil }
            return (t, ifd)
        }
        guard let c1 = box("CMT1").flatMap(tiffAt) else { return failure("Couldn't read this CR3 file.") }
        let t1 = c1.0, ifd0 = c1.1
        let make = ifd0.find(tagMake).flatMap(t1.string)
        let model = ifd0.find(tagModel).flatMap(t1.string)
        let software = ifd0.find(tagSoftware).flatMap(t1.string)
        let exif = box("CMT2").flatMap(tiffAt)
        let dateTaken = exif.flatMap { e in e.1.find(tagDateOriginal).flatMap(e.0.string) }
        let bodySerial = exif.flatMap { e in e.1.find(tagBodySerial).flatMap(e.0.string) }
        let ctx = Ctx(tiff: t1, brand: .canon, make: make, model: model, software: software, dateTaken: dateTaken, bodySerial: bodySerial)
        guard let offset = CameraModels.canonPhotoOffset(model) else { return ctx.result(nil, problem: canonUnsupported) }
        guard let c3 = box("CMT3").flatMap(tiffAt) else { return ctx.result(nil, problem: missingMakerNote) }
        return canonResult(ctx, c3.0, c3.1, offset, [])
    }

    // MARK: Nikon

    static func readNikon(_ ctx: Ctx, _ mn: IfdEntry?, _ warnings: inout [String]) -> PhotoReading {
        guard let mn else { return ctx.result(nil, warnings: warnings, problem: missingMakerNote) }
        let buf = ctx.tiff.buf
        let start = mn.valuePos
        guard start >= 0, start + 18 <= buf.count else { return ctx.result(nil, warnings: warnings, problem: missingMakerNote) }
        let t: Tiff
        let ifd: [IfdEntry]
        if buf.startsWith("Nikon\u{0}\u{2}", at: start) {
            let hdr = start + 10
            t = Tiff(buf, base: hdr, little: buf[hdr] == 0x49)
            guard let off = t.u32(hdr + 4), let i = t.readIfd(hdr + off) else { return ctx.result(nil, warnings: warnings, problem: missingMakerNote) }
            ifd = i
        } else if buf.startsWith("Nikon\u{0}\u{1}", at: start) {
            return ctx.result(nil, warnings: warnings, problem: "This older Nikon model does not record a shutter count.")
        } else {
            t = Tiff(buf, base: ctx.tiff.base, little: ctx.tiff.little)
            if !t.looksLikeIfd(start) { t.little.toggle() }
            guard let i = t.readIfd(start) else { return ctx.result(nil, warnings: warnings, problem: missingMakerNote) }
            ifd = i
        }
        let serial = ifd.find(0x001D).flatMap(t.string) ?? ctx.bodySerial
        guard let count = ifd.find(0x00A7).flatMap(t.int), count != 4294965247 else {
            return ctx.result(nil, serial: serial, warnings: warnings, problem: "This Nikon photo does not contain a shutter count (some older or compact models don't record it).")
        }
        var details: [(String, String)] = []
        if (ctx.model ?? "").uppercased().contains(" Z") {
            details.append(("Note", "On Nikon Z cameras this count includes electronic-shutter shots."))
        }
        return ctx.result(count, serial: serial, details: details, warnings: warnings)
    }

    // MARK: Sony

    enum SonyTable { case a, b, c, d }

    static func sonyTable(_ model: String, _ raw: [UInt8]) -> SonyTable? {
        if !Rx.has("^(DSC-|Stellar|ILCE-(1|6100|6300|6400|6500|6600|6700|7C|7M3|7M4|7M5|7RM2|7RM3A?|7RM4A?|7RM5|7RM6|7SM2|7SM3|9|9M2)|ILCA-99M2|ILME-(FX2|FX3)|ZV-)", model) { return .a }
        if Rx.has("^(ILCE-(6100A?|6300|6400A?|6500|6600|7C|7M3|7RM2|7RM3A?|7RM4A?|7SM2|9|9M2)|ILCA-99M2|ZV-E10)\\b", model) { return .b }
        if Rx.has("^(ILCE-(1\\b|7M4|7RM5|7SM3)|ILME-FX3)", model) { return .c }
        if Rx.has("^(ILCE-(6700|7CM2|7CR)|ILME-FX2|ZV-(E1|E10M2))\\b", model) { return .d }
        if Rx.has("^(ILCE-(1M2|7M5|7RM6))", model) && raw.count >= 5 && raw[0..<5].allSatisfy({ $0 == 0 }) { return .d }
        return nil
    }

    static func readSony(_ ctx: Ctx, _ mn: IfdEntry?, _ warnings: inout [String]) -> PhotoReading {
        let model = ctx.model ?? ""
        if model.hasPrefix("DSC-") || model.hasPrefix("ZV-1") {
            return ctx.result(nil, warnings: warnings, problem: "Sony compact cameras (RX100, ZV-1 and similar) don't record a shutter count in photos.")
        }
        guard let mn else { return ctx.result(nil, warnings: warnings, problem: missingMakerNote) }
        let buf = ctx.tiff.buf
        let start = mn.valuePos
        guard start >= 0, start + 14 <= buf.count else { return ctx.result(nil, warnings: warnings, problem: missingMakerNote) }
        let ifdPos = (buf.startsWith("SONY DSC ", at: start) || buf.startsWith("SONY CAM ", at: start) || buf.startsWith("SONY MOBILE", at: start)) ? start + 12 : start
        let t = Tiff(buf, base: ctx.tiff.base, little: ctx.tiff.little)
        if !t.looksLikeIfd(ifdPos) { t.little.toggle() }
        guard let ifd = t.readIfd(ifdPos) else { return ctx.result(nil, warnings: warnings, problem: missingMakerNote) }
        guard let raw = ifd.find(0x9050).flatMap(t.bytes) else {
            return ctx.result(nil, warnings: warnings, problem: "This Sony photo has no shutter-count block. The model may not record it, or the file was resized by an app (use the original file).")
        }
        guard let table = sonyTable(model, raw) else {
            return ctx.result(nil, warnings: warnings, problem: "The \(CameraModels.displayName(.sony, model)) does not store a readable shutter count in photos (or uses the electronic shutter only).")
        }
        let d = SonyCipher.decipher(raw)
        func u32(_ o: Int) -> Int? { o + 4 <= d.count ? d.le32(o) : nil }
        func masked(_ o: Int) -> Int? { u32(o).map { $0 & 0x00ff_ffff } }
        func byte(_ o: Int) -> Int? { o < d.count ? Int(d[o]) : nil }

        var details: [(String, String)] = []
        var count: Int?
        let rollModels = "^(ILCE-(7(R|S|M2)?|[56]000|5100|QX1))\\b"
        switch table {
        case .a:
            let flash = ((byte(0x31) ?? 0) & 1) == 1
            let sc = masked(0x32)
            let sc2 = (!flash && Rx.has(rollModels, model)) ? masked(0x4c) : nil
            var sc3: Int?
            if Rx.has("^(ILCE-(5100|QX1)|ILCA-(68|77M2))", model) { sc3 = u32(0x1a0) }
            else if Rx.has("^(SLT-A(58|99V?)|HV|NEX-(3N|5R|5T|6|VG900|VG30E)|ILCE-([35]000|3500))\\b", model) { sc3 = u32(0x1aa) }
            else if Rx.has("^(SLT-A(37|57|65|77)V?|Lunar|NEX-(F3|5N|7|VG20E))", model) { sc3 = u32(0x1bd) }
            if let v = sc3, v <= 0 { sc3 = nil }
            if let v = sc2, v > 0 { count = v }
            else if let v3 = sc3, let v = sc, v3 > v { count = v3 }
            else { count = sc }
            if sc2 == nil && flash && Rx.has(rollModels, model) {
                warnings.append("The flash fired in this photo. On this model the most accurate counter is skipped when the flash fires; take a new photo without flash for the best reading.")
            }
            if let c = count, c < 65536, sc2 == nil, sc3 == nil, model.hasPrefix("ILCE-7") {
                warnings.append("On this model the count can roll over after 65,536. If the camera looks heavily used, add 65,536.")
            }
        case .b, .c:
            count = masked(0x3a)
        case .d:
            count = masked(0x0a)
            details.append(("Note", "Counts mechanical-shutter shots only (electronic/silent shots are not included)."))
        }
        guard let c = count, c > 0 else {
            return ctx.result(nil, warnings: warnings, problem: "Couldn't find a shutter count in this Sony photo. If you used the silent/electronic shutter, take a new photo with the mechanical shutter.")
        }
        return ctx.result(c, details: details, warnings: warnings)
    }

    // MARK: helpers

    struct Ctx {
        let tiff: Tiff
        let brand: Brand
        let make: String?
        let model: String?
        let software: String?
        let dateTaken: String?
        let bodySerial: String?

        func result(_ count: Int?, serial: String?? = nil, details: [(String, String)] = [], warnings: [String] = [], problem: String? = nil) -> PhotoReading {
            let rating = CameraModels.rating(brand, model)
            return PhotoReading(
                brand: brand, make: make, model: model, displayName: CameraModels.displayName(brand, model),
                shutterCount: count, ratedLife: rating?.value, ratedOfficial: rating?.official ?? false,
                serial: serial ?? bodySerial, dateTaken: dateTaken, software: software,
                details: details, warnings: warnings, problem: problem)
        }
    }

    public static func failure(_ message: String) -> PhotoReading {
        PhotoReading(brand: .other, make: nil, model: nil, displayName: "Unknown camera", shutterCount: nil, ratedLife: nil,
                     ratedOfficial: false, serial: nil, dateTaken: nil, software: nil, details: [], warnings: [], problem: message)
    }

    /// TIFF header: start of file for NEF/ARW/TIFF, or inside APP1 for JPEG.
    static func findTiffStart(_ d: [UInt8]) -> Int? {
        guard d.count >= 8 else { return nil }
        if (d[0] == 0x49 && d[1] == 0x49) || (d[0] == 0x4D && d[1] == 0x4D) { return 0 }
        guard d[0] == 0xFF, d[1] == 0xD8 else { return nil }
        var p = 2
        while p + 4 <= d.count {
            if d[p] != 0xFF { p += 1; continue }
            let marker = Int(d[p + 1])
            if marker == 0xFF { p += 1; continue }
            if marker == 0xD9 || marker == 0xDA { return nil }
            if (0xD0...0xD7).contains(marker) || marker == 0x01 { p += 2; continue }
            let len = (Int(d[p + 2]) << 8) | Int(d[p + 3])
            if len < 2 { return nil }
            if marker == 0xE1 && d.startsWith("Exif\u{0}\u{0}", at: p + 4) && p + 18 <= d.count { return p + 10 }
            p += 2 + len
        }
        return nil
    }

    /// "2025:03:18 14:55:02" -> "18/03/2025 14:55"
    public static func prettyExifDate(_ s: String) -> String {
        guard let g = Rx.groups("^(\\d{4}):(\\d{2}):(\\d{2}) (\\d{2}):(\\d{2})", s) else { return s }
        return "\(g[2])/\(g[1])/\(g[0]) \(g[3]):\(g[4])"
    }
}
