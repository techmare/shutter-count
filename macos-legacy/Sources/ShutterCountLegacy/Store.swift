import Foundation
import ShutterCore

/// One saved check. Same file and format as the main Mac app, so both share History.
struct HistoryEntry: Codable, Equatable {
    var id = UUID()
    var time = Date()
    var camera: String
    var serial: String?
    var count: Int
    var ratedLife: Int?
    var ratedOfficial = false
    var source: String
    var note = ""
    var includesElectronic = false
}

struct Seen {
    let last: HistoryEntry
    let times: Int
    let note: String?
}

/// What a result card shows.
struct Reading {
    var camera: String
    var serial: String?
    var count: Int?
    var ratedLife: Int?
    var ratedOfficial = false
    var method: String
    var source: String
    var fileName: String?
    var photoDate: String?
    var details: [(String, String)] = []
    var warnings: [String] = []
    var problem: String?
    var includesElectronic = false
    var seen: Seen?
    var historyId: UUID?

    static func from(_ r: PhotoReading, fileName: String?, source: String = "Photo", method: String = "Photo (données EXIF)") -> Reading {
        Reading(
            camera: r.make != nil ? r.displayName : (fileName ?? "Unknown camera"),
            serial: r.serial, count: r.shutterCount, ratedLife: r.ratedLife, ratedOfficial: r.ratedOfficial,
            method: method, source: source, fileName: fileName,
            photoDate: r.dateTaken.map(PhotoReader.prettyExifDate),
            details: r.details, warnings: r.warnings,
            problem: r.shutterCount == nil ? (r.problem ?? "No shutter count found in this file.") : nil,
            includesElectronic: r.includesElectronic)
    }
}

enum Prefs {
    static var showWear: Bool {
        get { UserDefaults.standard.object(forKey: "showWear") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "showWear") }
    }
    static var reportWear: Bool {
        get { UserDefaults.standard.object(forKey: "reportWear") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "reportWear") }
    }
}

final class Store {
    static let shared = Store()
    private(set) var history: [HistoryEntry] = []
    var onChange: (() -> Void)?

    private let url: URL = {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Shutter Count", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("history.json")
    }()

    init() { load() }

    func load() {
        guard let d = try? Data(contentsOf: url) else { return }
        let dec = JSONDecoder()
        dec.dateDecodingStrategy = .iso8601
        history = (try? dec.decode([HistoryEntry].self, from: d)) ?? []
    }

    private func save() {
        let enc = JSONEncoder()
        enc.dateEncodingStrategy = .iso8601
        enc.outputFormatting = [.prettyPrinted]
        if let d = try? enc.encode(Array(history.prefix(1000))) { try? d.write(to: url, options: .atomic) }
        onChange?()
    }

    /// Adds a successful reading to History and attaches the Seen-before info.
    func record(_ r: Reading) -> Reading {
        guard let count = r.count else { return r }
        load() // pick up checks made in the other app
        var r = r
        r.seen = seenBefore(r.serial)
        let e = HistoryEntry(camera: r.camera, serial: r.serial, count: count, ratedLife: r.ratedLife, ratedOfficial: r.ratedOfficial,
                             source: r.source, includesElectronic: r.includesElectronic)
        history.insert(e, at: 0)
        save()
        r.historyId = e.id
        return r
    }

    func seenBefore(_ serial: String?) -> Seen? {
        guard let s = serial, !s.isEmpty else { return nil }
        let earlier = history.filter { $0.serial == s }
        guard let last = earlier.first else { return nil }
        return Seen(last: last, times: earlier.count, note: earlier.first { !$0.note.isEmpty }?.note)
    }

    func note(_ id: UUID?) -> String { history.first { $0.id == id }?.note ?? "" }

    func setNote(_ id: UUID?, _ note: String) {
        guard let i = history.firstIndex(where: { $0.id == id }) else { return }
        history[i].note = note
        save()
    }

    func delete(_ id: UUID) {
        history.removeAll { $0.id == id }
        save()
    }
}
