import Foundation
import ImageCaptureCore
import ShutterCore

/// Finds cameras through ImageCaptureCore (the framework Image Capture uses) and reads them:
/// - Nikon, Sony and Canon R: the newest photo on the card, first 512 KB, parsed like the Photo tab.
/// - Older Canons: the camera's own counter through read-only PTP status commands.
@MainActor
final class CameraManager: NSObject, ObservableObject {

    enum State: Equatable {
        case idle, busy(String), error(String)
    }

    @Published var cameras: [ICCameraDevice] = []
    @Published var selectedID: String?
    @Published var state: State = .idle
    @Published var result: Reading?
    @Published var diagnostics = ""

    private let browser = ICDeviceBrowser()
    private var readyWaiters: [String: CheckedContinuation<Void, Never>] = [:]

    var selected: ICCameraDevice? { cameras.first { $0.uuidString == selectedID } ?? cameras.first }

    func start() {
        browser.delegate = self
        let mask = ICDeviceTypeMask.camera.rawValue | ICDeviceLocationTypeMask.local.rawValue
        browser.browsedDeviceTypeMask = ICDeviceTypeMask(rawValue: mask) ?? .camera
        browser.start()
    }

    // MARK: reading

    func read(model: AppModel) {
        guard let camera = selected else { return }
        result = nil
        diagnostics = ""
        state = .busy("Connecting to \(camera.name ?? "the camera")…")
        Task {
            var log: [String] = []
            do {
                camera.delegate = self
                try await open(camera)
                state = .busy("Reading \(camera.name ?? "the camera")…")
                await waitUntilReady(camera)
                let name = camera.name ?? "Camera"
                log.append("Camera: \(name), capabilities: \(camera.capabilities.joined(separator: ", "))")
                let canPTP = camera.capabilities.contains(ICDeviceCapability.cameraDeviceCanAcceptPTPCommands.rawValue)
                var info: PTPDeviceInfo?
                let channel = ICPTPChannel(camera)
                if canPTP, let r = try? await channel.transact(0x1001, [], dataOut: nil), r.ok {
                    info = PTP.parseDeviceInfo(r.data)
                }
                let model0 = (info?.model).flatMap { $0.isEmpty ? nil : $0 } ?? name
                let isCanon = (info?.manufacturer ?? name).localizedCaseInsensitiveContains("canon") || name.localizedCaseInsensitiveContains("canon")
                let canonModel = model0.lowercased().hasPrefix("canon") ? model0 : "Canon \(model0)"

                var reading: Reading
                if isCanon && CameraModels.canonPhotoOffset(canonModel) == nil {
                    guard canPTP, let ops = info?.operations else {
                        throw Failure("This Canon doesn't accept the status commands needed to read its counter on this Mac.")
                    }
                    let res = await CanonCounterReader(channel).read(operations: ops)
                    log += res.log
                    let rating = CameraModels.rating(.canon, canonModel)
                    reading = Reading(
                        camera: canonModel, serial: res.serial, count: res.count, ratedLife: rating?.value, ratedOfficial: rating?.official ?? false,
                        method: "USB (lecture directe)", source: "USB",
                        details: [("Method", res.method ?? "None"), ("Firmware", info?.version ?? "")].filter { !$0.1.isEmpty },
                        problem: res.count == nil ? "This Canon doesn't share its shutter count over USB in a way this app can read safely." : nil)
                } else {
                    reading = try await readNewestPhoto(camera, log: &log)
                }
                result = model.record(reading)
                state = .idle
            } catch {
                state = .error((error as? Failure)?.message ?? error.localizedDescription)
            }
            diagnostics = log.joined(separator: "\n")
            camera.requestCloseSession(options: nil) { _ in }
        }
    }

    private func readNewestPhoto(_ camera: ICCameraDevice, log: inout [String]) async throws -> Reading {
        let exts = ["JPG", "JPEG", "NEF", "NRW", "ARW", "SRF", "SR2", "CR2", "CR3", "HIF", "HEIF"]
        let files = (camera.mediaFiles ?? []).compactMap { $0 as? ICCameraFile }
            .filter { exts.contains(($0.name ?? "").components(separatedBy: ".").last?.uppercased() ?? "") }
        log.append("Image files on card: \(files.count)")
        guard !files.isEmpty else { throw Failure("No photo found on the camera's card. Take a photo, then read again.") }
        func date(_ f: ICCameraFile) -> Date { f.exifCreationDate ?? f.creationDate ?? f.fileCreationDate ?? .distantPast }
        let newestDate = files.map(date).max() ?? .distantPast
        let sameShot = files.filter { abs(date($0).timeIntervalSince(newestDate)) < 1 }
        let newest = sameShot.first { ($0.name ?? "").uppercased().hasSuffix(".JPG") } ?? sameShot.first ?? files[0]
        let name = newest.name ?? "photo"
        log.append("Newest: \(name), \(newest.fileSize) bytes")

        var data = try await readData(newest, length: min(Int64(newest.fileSize), 512 * 1024))
        var parsed = PhotoReader.read(data)
        if parsed.shutterCount == nil && Int64(data.count) < Int64(newest.fileSize) && newest.fileSize < 80 * 1024 * 1024 {
            data = try await readData(newest, length: Int64(newest.fileSize))
            parsed = PhotoReader.read(data)
        }
        log.append("Read: \(parsed.displayName), count \(parsed.shutterCount.map(String.init) ?? "none")")
        var r = Reading.from(parsed, fileName: name, source: "USB", method: "USB (dernière photo)")
        if parsed.make == nil { r.camera = camera.name ?? r.camera }
        let created = date(newest)
        if created != .distantPast { r.photoDate = Fmt.date(created) }
        return r
    }

    // MARK: ImageCaptureCore plumbing

    private func open(_ camera: ICCameraDevice) async throws {
        if camera.hasOpenSession { return }
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            camera.requestOpenSession(options: nil) { err in
                if let err { c.resume(throwing: err) } else { c.resume() }
            }
        }
    }

    private func waitUntilReady(_ camera: ICCameraDevice) async {
        if camera.contentCatalogPercentCompleted >= 100 { return }
        let key = camera.uuidString ?? ""
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            readyWaiters[key] = c
            // don't wait forever on cameras that never report a complete catalog
            Task { try? await Task.sleep(nanoseconds: 20_000_000_000); self.ready(key) }
        }
    }

    fileprivate func ready(_ key: String) {
        readyWaiters.removeValue(forKey: key)?.resume()
    }

    private func readData(_ file: ICCameraFile, length: Int64) async throws -> [UInt8] {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<[UInt8], Error>) in
            file.requestReadData(atOffset: 0, length: off_t(length)) { data, err in
                if let data { c.resume(returning: Array(data)) }
                else { c.resume(throwing: err ?? Failure("The camera didn't send the photo.")) }
            }
        }
    }

    struct Failure: Error { let message: String; init(_ m: String) { message = m } }
}

extension CameraManager: ICDeviceBrowserDelegate {
    nonisolated func deviceBrowser(_ browser: ICDeviceBrowser, didAdd device: ICDevice, moreComing: Bool) {
        guard let cam = device as? ICCameraDevice else { return }
        Task { @MainActor in
            if !self.cameras.contains(where: { $0 === cam }) { self.cameras.append(cam) }
            if self.selectedID == nil { self.selectedID = cam.uuidString }
        }
    }

    nonisolated func deviceBrowser(_ browser: ICDeviceBrowser, didRemove device: ICDevice, moreGoing: Bool) {
        Task { @MainActor in
            self.cameras.removeAll { $0 === device }
            if self.selectedID == device.uuidString { self.selectedID = self.cameras.first?.uuidString }
        }
    }
}

extension CameraManager: ICCameraDeviceDelegate {
    nonisolated func didRemove(_ device: ICDevice) {}
    nonisolated func device(_ device: ICDevice, didOpenSessionWithError error: Error?) {}
    nonisolated func device(_ device: ICDevice, didCloseSessionWithError error: Error?) {}
    nonisolated func cameraDevice(_ camera: ICCameraDevice, didAdd items: [ICCameraItem]) {}
    nonisolated func cameraDevice(_ camera: ICCameraDevice, didRemove items: [ICCameraItem]) {}
    nonisolated func cameraDevice(_ camera: ICCameraDevice, didReceiveThumbnail thumbnail: CGImage?, for item: ICCameraItem, error: Error?) {}
    nonisolated func cameraDevice(_ camera: ICCameraDevice, didReceiveMetadata metadata: [AnyHashable: Any]?, for item: ICCameraItem, error: Error?) {}
    nonisolated func cameraDevice(_ camera: ICCameraDevice, didRenameItems items: [ICCameraItem]) {}
    nonisolated func cameraDeviceDidChangeCapability(_ camera: ICCameraDevice) {}
    nonisolated func cameraDevice(_ camera: ICCameraDevice, didReceivePTPEvent eventData: Data) {}
    nonisolated func cameraDeviceDidRemoveAccessRestriction(_ device: ICDevice) {}
    nonisolated func cameraDeviceDidEnableAccessRestriction(_ device: ICDevice) {}
    nonisolated func deviceDidBecomeReady(withCompleteContentCatalog device: ICCameraDevice) {
        let key = device.uuidString ?? ""
        Task { @MainActor in self.ready(key) }
    }
}

/// PTP passthrough over ImageCaptureCore's open session.
final class ICPTPChannel: PTPChannel {
    private let camera: ICCameraDevice
    private var tid = 0x1000

    init(_ camera: ICCameraDevice) { self.camera = camera }

    func transact(_ op: Int, _ params: [Int], dataOut: [UInt8]?) async throws -> PTPResponse {
        tid += 1
        let cmd = Data(PTP.commandContainer(op, tid: tid, params: params))
        return try await withCheckedThrowingContinuation { (c: CheckedContinuation<PTPResponse, Error>) in
            camera.requestSendPTPCommand(cmd, outData: dataOut.map { Data($0) }) { data, response, error in
                if let error { c.resume(throwing: error); return }
                let (code, ps) = PTP.parseResponse(Array(response)) ?? (0, [])
                c.resume(returning: PTPResponse(code: code, params: ps, data: PTP.stripDataHeader(Array(data))))
            }
        }
    }
}
