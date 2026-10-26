import Foundation
import ShutterCore

/// One saved check.
struct HistoryEntry: Codable, Identifiable, Equatable {
    var id = UUID()
    var time = Date()
    var camera: String
    var serial: String?
    var count: Int
    var ratedLife: Int?
    var ratedOfficial = false
    /// "Photo" or "USB"
    var source: String
    var note = ""
    var includesElectronic = false
}

/// This body was checked before: newest earlier check, number of checks, newest note.
struct Seen: Equatable {
    let last: HistoryEntry
    let times: Int
    let note: String?
}

/// What a result card shows, from a photo or a camera.
struct Reading: Identifiable {
    let id = UUID()
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
            details: r.details, warnings: r.warnings, problem: r.shutterCount == nil ? (r.problem ?? "No shutter count found in this file.") : nil,
            includesElectronic: r.includesElectronic)
    }
}

final class HistoryStore {
    private let url: URL = {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Shutter Count", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("history.json")
    }()

    func load() -> [HistoryEntry] {
        guard let d = try? Data(contentsOf: url) else { return [] }
        let dec = JSONDecoder()
        dec.dateDecodingStrategy = .iso8601
        return (try? dec.decode([HistoryEntry].self, from: d)) ?? []
    }

    func save(_ entries: [HistoryEntry]) {
        let enc = JSONEncoder()
        enc.dateEncodingStrategy = .iso8601
        enc.outputFormatting = [.prettyPrinted]
        if let d = try? enc.encode(Array(entries.prefix(1000))) { try? d.write(to: url, options: .atomic) }
    }
}

@MainActor
final class AppModel: ObservableObject {
    @Published var history: [HistoryEntry]
    @Published var photoResults: [Reading] = []
    @Published var readingPhotos = false
    private let store = HistoryStore()

    init() { history = store.load() }

    // MARK: photos

    func readPhotos(_ urls: [URL]) {
        let files = urls.filter { !$0.hasDirectoryPath }
        guard !files.isEmpty else { return }
        readingPhotos = true
        Task {
            var out: [Reading] = []
            for url in files {
                let parsed: PhotoReading = await Task.detached(priority: .userInitiated) {
                    let access = url.startAccessingSecurityScopedResource()
                    defer { if access { url.stopAccessingSecurityScopedResource() } }
                    guard let h = try? FileHandle(forReadingFrom: url) else { return PhotoReader.failure("Couldn't open this file.") }
                    defer { try? h.close() }
                    // camera data sits near the start; RAW files can be large, so read at most 12 MB
                    let data = (try? h.read(upToCount: 12 * 1024 * 1024)) ?? Data()
                    return PhotoReader.read(data)
                }.value
                out.append(record(Reading.from(parsed, fileName: url.lastPathComponent)))
            }
            photoResults = out
            readingPhotos = false
        }
    }

    // MARK: history

    /// Adds a successful reading to History and attaches the Seen-before info.
    func record(_ r: Reading) -> Reading {
        guard let count = r.count else { return r }
        var r = r
        r.seen = seenBefore(r.serial)
        let e = HistoryEntry(camera: r.camera, serial: r.serial, count: count, ratedLife: r.ratedLife, ratedOfficial: r.ratedOfficial,
                             source: r.source, includesElectronic: r.includesElectronic)
        history.insert(e, at: 0)
        store.save(history)
        r.historyId = e.id
        return r
    }

    /// Earlier checks of the same body, matched by serial number only.
    func seenBefore(_ serial: String?) -> Seen? {
        guard let s = serial, !s.isEmpty else { return nil }
        let earlier = history.filter { $0.serial == s }
        guard let last = earlier.first else { return nil }
        return Seen(last: last, times: earlier.count, note: earlier.first { !$0.note.isEmpty }?.note)
    }

    func note(for id: UUID?) -> String { history.first { $0.id == id }?.note ?? "" }

    func setNote(_ id: UUID?, _ note: String) {
        guard let i = history.firstIndex(where: { $0.id == id }) else { return }
        history[i].note = note
        store.save(history)
    }

    func delete(_ id: UUID) {
        history.removeAll { $0.id == id }
        store.save(history)
    }
}
