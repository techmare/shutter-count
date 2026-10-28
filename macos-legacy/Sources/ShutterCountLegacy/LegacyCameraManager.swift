import Foundation
import ImageCaptureCore
import ShutterCore

/// ImageCaptureCore without async/await, so it runs on Mojave.
/// Uses the block APIs on 10.15+ and the older delegate/selector APIs on 10.14.
/// - Nikon, Sony and Canon R: newest photo on the card (first 512 KB).
/// - Older Canons: the camera's own counter through read-only PTP status commands.
final class LegacyCameraManager: NSObject {

    enum State { case idle, busy(String), error(String) }

    private(set) var cameras: [ICCameraDevice] = []
    var selectedIndex = 0
    private(set) var state: State = .idle { didSet { notify() } }
    private(set) var result: Reading?
    private(set) var diagnostics = ""
    var onChange: (() -> Void)?

    private let browser = ICDeviceBrowser()
    private var readyWaiter: (() -> Void)?
    private var pendingRead: ((Data?, Error?) -> Void)?
    private var tid = 0x1000
    private var log: [String] = []

    var selected: ICCameraDevice? { cameras.indices.contains(selectedIndex) ? cameras[selectedIndex] : cameras.first }
    var busy: Bool { if case .busy = state { return true }; return false }

    private func notify() { DispatchQueue.main.async { self.onChange?() } }

    func start() {
        browser.delegate = self
        let mask = ICDeviceTypeMask.camera.rawValue | ICDeviceLocationTypeMask.local.rawValue
        browser.browsedDeviceTypeMask = ICDeviceTypeMask(rawValue: mask) ?? .camera
        browser.start()
    }

    // MARK: flow

    func read(done: @escaping (Reading) -> Reading) {
        guard let camera = selected, !busy else { return }
        result = nil
        diagnostics = ""
        log = []
        let name = camera.name ?? "Camera"
        state = .busy("Connecting to \(name)…")
        camera.delegate = self
        open(camera) { [weak self] err in
            guard let self = self else { return }
            if let err = err { self.fail(camera, err.localizedDescription); return }
            self.state = .busy("Reading \(name)…")
            self.waitUntilReady(camera) { self.identify(camera, done: done) }
        }
    }

    private func identify(_ camera: ICCameraDevice, done: @escaping (Reading) -> Reading) {
        let name = camera.name ?? "Camera"
        log.append("Camera: \(name), capabilities: \(camera.capabilities.joined(separator: ", "))")
        let canPTP = camera.capabilities.contains("ICCameraDeviceCanAcceptPTPCommands")
        let proceed: (PTPDeviceInfo?) -> Void = { info in
            let model0 = (info?.model).flatMap { $0.isEmpty ? nil : $0 } ?? name
            let isCanon = (info?.manufacturer ?? "").localizedCaseInsensitiveContains("canon") || name.localizedCaseInsensitiveContains("canon")
            let canonModel = model0.lowercased().hasPrefix("canon") ? model0 : "Canon \(model0)"
            if isCanon && CameraModels.canonPhotoOffset(canonModel) == nil && CameraModels.isCanonR(canonModel) {
                    // Older Canons' status commands made an EOS RP stop answering: don't send them to R bodies.
                    self.fail(camera, "This Canon R keeps its shutter count inside its photos, and reading this model isn't supported yet. Support is added by comparing two of its photos taken a few shots apart."); return
                } else if isCanon && CameraModels.canonPhotoOffset(canonModel) == nil {
                guard canPTP, let ops = info?.operations else {
                    self.fail(camera, "This Canon doesn't accept the status commands needed to read its counter on this Mac."); return
                }
                CanonCounterReader.read(operations: ops, send: { op, params, cb in self.send(camera, op, params, cb) }) { res in
                    self.log += res.log
                    let rating = CameraModels.rating(.canon, canonModel)
                    let r = Reading(
                        camera: canonModel, serial: res.serial, count: res.count, ratedLife: rating?.value, ratedOfficial: rating?.official ?? false,
                        method: "USB (lecture directe)", source: "USB",
                        details: [("Method", res.method ?? "None"), ("Firmware", info?.version ?? "")].filter { !$0.1.isEmpty },
                        problem: res.count == nil ? "This Canon doesn't share its shutter count over USB in a way this app can read safely." : nil)
                    self.finish(camera, done(r))
                }
            } else {
                self.readNewestPhoto(camera, done: done)
            }
        }
        if canPTP {
            send(camera, 0x1001, []) { r in proceed((r?.ok ?? false) ? PTP.parseDeviceInfo(r!.data) : nil) }
        } else {
            proceed(nil)
        }
    }

    private func readNewestPhoto(_ camera: ICCameraDevice, done: @escaping (Reading) -> Reading) {
        let exts = ["JPG", "JPEG", "NEF", "NRW", "ARW", "SRF", "SR2", "CR2", "CR3", "HIF", "HEIF"]
        let files = (camera.mediaFiles ?? []).compactMap { $0 as? ICCameraFile }
            .filter { exts.contains(($0.name ?? "").components(separatedBy: ".").last?.uppercased() ?? "") }
        log.append("Image files on card: \(files.count)")
        guard !files.isEmpty else { fail(camera, "No photo found on the camera's card. Take a photo, then read again."); return }
        func date(_ f: ICCameraFile) -> Date {
            if #available(macOS 10.15, *), let d = f.exifCreationDate { return d }
            return f.creationDate ?? .distantPast
        }
        let newestDate = files.map(date).max() ?? .distantPast
        let sameShot = files.filter { abs(date($0).timeIntervalSince(newestDate)) < 1 }
        let newest = sameShot.first { ($0.name ?? "").uppercased().hasSuffix(".JPG") } ?? sameShot.first ?? files[0]
        let fileName = newest.name ?? "photo"
        let size = Int64(newest.fileSize)
        log.append("Newest: \(fileName), \(size) bytes")

        let finishWith: ([UInt8]) -> Void = { data in
            let parsed = PhotoReader.read(data)
            self.log.append("Read: \(parsed.displayName), count \(parsed.shutterCount.map(String.init) ?? "none")")
            var r = Reading.from(parsed, fileName: fileName, source: "USB", method: "USB (dernière photo)")
            if parsed.make == nil { r.camera = camera.name ?? r.camera }
            let created = date(newest)
            if created != .distantPast { r.photoDate = Fmt.date(created) }
            self.finish(camera, done(r))
        }
        readData(camera, newest, length: min(size, 512 * 1024)) { data, err in
            guard let data = data else { self.fail(camera, err?.localizedDescription ?? "The camera didn't send the photo."); return }
            let bytes = Array(data)
            if PhotoReader.read(bytes).shutterCount == nil && Int64(bytes.count) < size && size < 80 * 1024 * 1024 {
                self.readData(camera, newest, length: size) { full, _ in finishWith(full.map(Array.init) ?? bytes) }
            } else {
                finishWith(bytes)
            }
        }
    }

    private func finish(_ camera: ICCameraDevice, _ r: Reading) {
        DispatchQueue.main.async {
            self.result = r
            self.diagnostics = self.log.joined(separator: "\n")
            self.state = .idle
            self.close(camera)
        }
    }

    private func fail(_ camera: ICCameraDevice, _ message: String) {
        DispatchQueue.main.async {
            self.diagnostics = self.log.joined(separator: "\n")
            self.state = .error(message)
            self.close(camera)
        }
    }

    // MARK: ImageCaptureCore plumbing

    private var openCallback: ((Error?) -> Void)?

    private func open(_ camera: ICCameraDevice, _ cb: @escaping (Error?) -> Void) {
        if camera.hasOpenSession { cb(nil); return }
        if #available(macOS 10.15, *) {
            camera.requestOpenSession(options: nil) { err in DispatchQueue.main.async { cb(err) } }
        } else {
            openCallback = cb
            camera.requestOpenSession()
        }
    }

    private func close(_ camera: ICCameraDevice) {
        guard camera.hasOpenSession else { return }
        if #available(macOS 10.15, *) {
            camera.requestCloseSession(options: nil) { _ in }
        } else {
            camera.requestCloseSession()
        }
    }

    private func waitUntilReady(_ camera: ICCameraDevice, _ cb: @escaping () -> Void) {
        if camera.contentCatalogPercentCompleted >= 100 { cb(); return }
        var fired = false
        let once = { if !fired { fired = true; self.readyWaiter = nil; cb() } }
        readyWaiter = once
        // don't wait forever on cameras that never report a complete catalog
        DispatchQueue.main.asyncAfter(deadline: .now() + 20, execute: once)
    }

    private func send(_ camera: ICCameraDevice, _ op: Int, _ params: [Int], _ cb: @escaping (PTPResponse?) -> Void) {
        tid += 1
        let cmd = Data(PTP.commandContainer(op, tid: tid, params: params))
        let handle: (Data?, Data?, Error?) -> Void = { data, response, error in
            DispatchQueue.main.async {
                guard error == nil, let response = response else { cb(nil); return }
                let (code, ps) = PTP.parseResponse(Array(response)) ?? (0, [])
                cb(PTPResponse(code: code, params: ps, data: PTP.stripDataHeader(Array(data ?? Data()))))
            }
        }
        if #available(macOS 10.15, *) {
            camera.requestSendPTPCommand(cmd, outData: nil) { data, response, error in handle(data, response, error) }
        } else {
            pendingPTPHandle = handle
            camera.requestSendPTPCommand(cmd, outData: nil, sendCommandDelegate: self,
                                         didSendCommand: #selector(didSendPTPCommand(_:inData:response:error:contextInfo:)), contextInfo: nil)
        }
    }
    private var pendingPTPHandle: ((Data?, Data?, Error?) -> Void)?

    @objc func didSendPTPCommand(_ command: Data?, inData data: Data?, response: Data?, error: Error?, contextInfo: UnsafeMutableRawPointer?) {
        let h = pendingPTPHandle
        pendingPTPHandle = nil
        h?(data, response, error)
    }

    private func readData(_ camera: ICCameraDevice, _ file: ICCameraFile, length: Int64, _ cb: @escaping (Data?, Error?) -> Void) {
        let done: (Data?, Error?) -> Void = { d, e in DispatchQueue.main.async { cb(d, e) } }
        if #available(macOS 10.15, *) {
            file.requestReadData(atOffset: 0, length: off_t(length)) { data, err in done(data, err) }
        } else {
            pendingRead = done
            camera.requestReadData(from: file, atOffset: 0, length: off_t(length), readDelegate: self,
                                   didReadDataSelector: #selector(didReadData(_:fromFile:error:contextInfo:)), contextInfo: nil)
        }
    }

    @objc func didReadData(_ data: Data?, fromFile file: ICCameraFile?, error: Error?, contextInfo: UnsafeMutableRawPointer?) {
        let h = pendingRead
        pendingRead = nil
        h?(data, error)
    }
}

extension LegacyCameraManager: ICDeviceBrowserDelegate {
    func deviceBrowser(_ browser: ICDeviceBrowser, didAdd device: ICDevice, moreComing: Bool) {
        guard let cam = device as? ICCameraDevice else { return }
        DispatchQueue.main.async {
            if !self.cameras.contains(where: { $0 === cam }) { self.cameras.append(cam) }
            self.notify()
        }
    }

    func deviceBrowser(_ browser: ICDeviceBrowser, didRemove device: ICDevice, moreGoing: Bool) {
        DispatchQueue.main.async {
            self.cameras.removeAll { $0 === device }
            if self.selectedIndex >= self.cameras.count { self.selectedIndex = 0 }
            self.notify()
        }
    }
}

extension LegacyCameraManager: ICCameraDeviceDelegate {
    func didRemove(_ device: ICDevice) {}
    func device(_ device: ICDevice, didOpenSessionWithError error: Error?) {
        DispatchQueue.main.async {
            let cb = self.openCallback
            self.openCallback = nil
            cb?(error)
        }
    }
    func device(_ device: ICDevice, didCloseSessionWithError error: Error?) {}
    func cameraDevice(_ camera: ICCameraDevice, didAdd items: [ICCameraItem]) {}
    func cameraDevice(_ camera: ICCameraDevice, didRemove items: [ICCameraItem]) {}
    func cameraDevice(_ camera: ICCameraDevice, didReceiveThumbnail thumbnail: CGImage?, for item: ICCameraItem, error: Error?) {}
    func cameraDevice(_ camera: ICCameraDevice, didReceiveMetadata metadata: [AnyHashable: Any]?, for item: ICCameraItem, error: Error?) {}
    func cameraDevice(_ camera: ICCameraDevice, didRenameItems items: [ICCameraItem]) {}
    func cameraDeviceDidChangeCapability(_ camera: ICCameraDevice) {}
    func cameraDevice(_ camera: ICCameraDevice, didReceivePTPEvent eventData: Data) {}
    func cameraDeviceDidRemoveAccessRestriction(_ device: ICDevice) {}
    func cameraDeviceDidEnableAccessRestriction(_ device: ICDevice) {}
    func deviceDidBecomeReady(withCompleteContentCatalog device: ICCameraDevice) {
        DispatchQueue.main.async { self.readyWaiter?() }
    }
}
