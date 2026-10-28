import SwiftUI
import AppKit
import UniformTypeIdentifiers
import ShutterCore

enum Pane: String, CaseIterable, Identifiable {
    case photo = "Photo", camera = "Camera", history = "History"
    var id: String { rawValue }
    var icon: String {
        switch self {
        case .photo: return "photo"
        case .camera: return "cable.connector"
        case .history: return "clock.arrow.circlepath"
        }
    }
}

struct ContentView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var cameras: CameraManager
    @State private var section: Pane? = .photo
    @Environment(\.palette) private var p

    var body: some View {
        NavigationSplitView {
            List(Pane.allCases, selection: $section) { s in
                Label(s.rawValue, systemImage: s.icon).tag(s)
            }
            .navigationSplitViewColumnWidth(min: 170, ideal: 190)
        } detail: {
            Group {
                switch section ?? .photo {
                case .photo: PhotoView()
                case .camera: CameraView()
                case .history: HistoryView()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(p.paper)
        }
        .onAppear { cameras.start() }
        .onChange(of: cameras.cameras.count) { n in
            if n > 0 && section == .photo && model.photoResults.isEmpty { section = .camera }
        }
    }
}

// MARK: result card shared by Photo and Camera

struct ResultView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.palette) private var p
    @AppStorage("showWear") private var showWear = true
    @AppStorage("reportWear") private var reportWear = true
    let reading: Reading
    @State private var reportURL: URL?
    @State private var editingNote = false
    @State private var noteText = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            InstrumentPanel(
                title: reading.camera, titleDim: reading.count == nil,
                trailing: reading.serial.map { "S/N \($0)" },
                count: reading.count,
                caption: reading.count != nil ? "shutter actuations" : "No shutter count in this file",
                ratedLife: reading.ratedLife, ratedOfficial: reading.ratedOfficial, showWear: showWear)
            if let c = reading.count, let seen = reading.seen { SeenBeforeBanner(seen: seen, count: c) }
            if let problem = reading.problem { Note(text: problem, warning: true) }
            ForEach(reading.warnings, id: \.self) { Note(text: $0, warning: true) }

            DetailRows(rows: rows)

            if let count = reading.count {
                let data = ReportData(camera: reading.camera, serial: reading.serial, count: count,
                                      ratedLife: reportWear ? reading.ratedLife : nil, ratedOfficial: reading.ratedOfficial,
                                      method: reading.method, includesElectronic: reading.includesElectronic)
                HStack(spacing: 10) {
                    if let url = reportURL {
                        ShareLink(item: url, preview: SharePreview("Rapport \(reading.camera)", image: Image(nsImage: NSImage(contentsOf: url) ?? NSImage()))) {
                            Text("Share report")
                        }
                        .buttonStyle(PrimaryButtonStyle())
                    }
                    Button("Save image…") { ReportExport.saveAs(data) }.buttonStyle(SecondaryButtonStyle())
                    Button("Copy image") { ReportExport.copy(data) }.buttonStyle(SecondaryButtonStyle())
                    Button(model.note(for: reading.historyId).isEmpty ? "Add note" : "Edit note") {
                        noteText = model.note(for: reading.historyId); editingNote = true
                    }
                    .buttonStyle(SecondaryButtonStyle())
                }
                .task(id: "\(reading.id)-\(reportWear)") { reportURL = ReportExport.png(data) }
                if reading.photoDate != nil {
                    Note(text: "This is the count when the photo was taken. For today's count, use a photo taken just now.")
                }
            }
        }
        .popover(isPresented: $editingNote) {
            VStack(alignment: .leading, spacing: 10) {
                Text("Note").font(Fonts.display(20, bold: false))
                TextField("Customer, price, condition", text: $noteText, axis: .vertical).lineLimit(3...6).frame(width: 300)
                HStack { Spacer(); Button("Save note") { model.setNote(reading.historyId, noteText); editingNote = false }.keyboardShortcut(.defaultAction) }
            }
            .padding(16)
        }
    }

    private var rows: [(String, String)] {
        var r: [(String, String)] = []
        if let f = reading.fileName { r.append(("File", f)) }
        if let d = reading.photoDate { r.append(("Photo taken", d)) }
        r.append(contentsOf: reading.details)
        return r
    }
}

// MARK: Photo

struct PhotoView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.palette) private var p
    @State private var dropping = false
    @State private var importing = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header
                if model.readingPhotos {
                    ProgressView().controlSize(.large).frame(maxWidth: .infinity).padding(40)
                } else if model.photoResults.isEmpty {
                    InstrumentPanel(title: "No camera yet", titleDim: true, count: nil,
                                    caption: "Drop photos from a Nikon, Sony or Canon R here to read their count")
                    Steps(title: "Before you start", steps: [
                        "Take a new photo with the camera, using the mechanical shutter (not silent mode).",
                        "Copy the original JPEG or RAW file to this Mac from the card.",
                        "Drop it on this window, or click Open photos.",
                    ])
                    Note(text: "Photos sent through WhatsApp or edited in an app lose the data this needs.")
                    Note(text: "Canon: the R50, R100, RP, R8, R6, R6 Mark II, R5 and M50 Mark II work from a photo. Older Canons are read in Camera.")
                } else {
                    ForEach(model.photoResults) { r in
                        ResultView(reading: r)
                        Divider().overlay(p.rule).padding(.vertical, 6)
                    }
                }
            }
            .padding(28)
            .frame(maxWidth: 820, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .overlay {
            if dropping {
                RoundedRectangle(cornerRadius: 20).strokeBorder(Palette.signal, style: StrokeStyle(lineWidth: 3, dash: [10, 6])).padding(12)
                    .allowsHitTesting(false)
            }
        }
        .onDrop(of: [.fileURL], isTargeted: $dropping) { providers in
            Task {
                var urls: [URL] = []
                for pr in providers {
                    if let url = try? await pr.loadItem(forTypeIdentifier: UTType.fileURL.identifier) as? URL { urls.append(url) }
                    else if let data = try? await pr.loadItem(forTypeIdentifier: UTType.fileURL.identifier) as? Data,
                            let url = URL(dataRepresentation: data, relativeTo: nil) { urls.append(url) }
                }
                await MainActor.run { model.readPhotos(urls) }
            }
            return true
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.image, .rawImage, .data], allowsMultipleSelection: true) { res in
            if case .success(let urls) = res { model.readPhotos(urls) }
        }
    }

    private var header: some View {
        HStack {
            Text("Shutter Count").font(Fonts.display(34)).foregroundStyle(p.ink)
            Spacer()
            Button("Open photos…") { importing = true }.buttonStyle(PrimaryButtonStyle()).keyboardShortcut("o")
        }
    }
}

// MARK: Camera

struct CameraView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var cameras: CameraManager
    @Environment(\.palette) private var p

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack {
                    Text("Camera over USB").font(Fonts.display(34)).foregroundStyle(p.ink)
                    Spacer()
                    if cameras.cameras.count > 1 {
                        Picker("Camera", selection: Binding(get: { cameras.selected?.uuidString }, set: { cameras.selectedID = $0 })) {
                            ForEach(cameras.cameras, id: \.self) { c in Text(c.name ?? "Camera").tag(c.uuidString) }
                        }
                        .labelsHidden().frame(width: 220)
                    }
                    Button("Read camera") { cameras.read(model: model) }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(cameras.selected == nil || isBusy)
                        .keyboardShortcut("r")
                }

                switch cameras.state {
                case .busy(let msg):
                    InstrumentPanel(title: cameras.selected?.name ?? "Camera", count: nil, caption: msg) {
                        ProgressView().controlSize(.small).tint(Palette.signal).padding(.top, 12)
                    }
                case .error(let msg):
                    InstrumentPanel(title: "Couldn't read the camera", titleDim: true, count: nil, caption: "Nothing was changed on the camera")
                    Note(text: msg, warning: true)
                    tips
                case .idle:
                    if let r = cameras.result {
                        ResultView(reading: r)
                    } else if let cam = cameras.selected {
                        InstrumentPanel(title: cam.name ?? "Camera", count: nil, caption: "Connected and ready. Take one photo first, then click Read camera.")
                    } else {
                        InstrumentPanel(title: "No camera connected", titleDim: true, count: nil, caption: "Plug in a Nikon, Sony or Canon with a USB cable")
                        tips
                    }
                }
                if !cameras.diagnostics.isEmpty {
                    DisclosureGroup("Diagnostic details") {
                        Text(cameras.diagnostics).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .font(Fonts.text(14)).foregroundStyle(p.muted)
                }
            }
            .padding(28)
            .frame(maxWidth: 820, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
    }

    private var isBusy: Bool { if case .busy = cameras.state { return true } else { return false } }

    private var tips: some View {
        Steps(title: "Connect a camera", steps: [
            "Take one photo first. Nikon, Sony and Canon R are read from the newest photo on the card.",
            "Turn off the camera's Wi-Fi and Bluetooth. They block the USB port.",
            "Connect it with a USB cable and turn it on. On Sony, set USB Connection to MTP.",
            "Quit Photos, Image Capture or EOS Utility if they open, then click Read camera.",
        ])
    }
}

// MARK: History

struct HistoryView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.palette) private var p
    @AppStorage("showWear") private var showWear = true
    @AppStorage("reportWear") private var reportWear = true
    @State private var search = ""

    var body: some View {
        let shown = model.history.filter { e in
            search.isEmpty || [e.camera, e.serial ?? "", e.note].contains { $0.localizedCaseInsensitiveContains(search) }
        }
        Group {
            if model.history.isEmpty {
                VStack(spacing: 8) {
                    Text("No checks yet").font(Fonts.display(26, bold: false)).foregroundStyle(p.ink)
                    Text("Each camera you read is saved here with its date. Add a note to keep the customer, price or condition with it.")
                        .font(Fonts.text(15)).foregroundStyle(p.muted).multilineTextAlignment(.center).frame(maxWidth: 420)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(shown) { e in row(e).listRowSeparatorTint(p.rule) }
                    .scrollContentBackground(.hidden)
                    .searchable(text: $search, prompt: "Camera, serial or note")
            }
        }
        .navigationTitle("History")
    }

    private func row(_ e: HistoryEntry) -> some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 2) {
                Text(e.camera).font(Fonts.display(19, bold: false)).foregroundStyle(p.ink)
                Text("\(Fmt.date(e.time)), from \(e.source == "USB" ? "USB" : "photo")" + (e.serial.map { ", S/N \($0)" } ?? ""))
                    .font(Fonts.text(13)).foregroundStyle(p.muted)
                if let rated = e.ratedLife, showWear {
                    let w = CameraModels.wear(e.count, rated)
                    HStack(spacing: 6) {
                        Circle().fill(wearColor(w)).frame(width: 8, height: 8)
                        Text("\(w.rawValue), \(CameraModels.wearPercent(e.count, rated))%").font(Fonts.text(13)).foregroundStyle(p.muted)
                    }
                }
                if !e.note.isEmpty { Text(e.note).font(Fonts.text(14)).foregroundStyle(p.ink) }
            }
            Spacer()
            Text(Fmt.count(e.count)).font(Fonts.display(24)).foregroundStyle(p.ink)
        }
        .padding(.vertical, 6)
        .contextMenu {
            let data = ReportData(camera: e.camera, serial: e.serial, count: e.count, ratedLife: reportWear ? e.ratedLife : nil,
                                  ratedOfficial: e.ratedOfficial, method: e.source == "USB" ? "USB" : "Photo (données EXIF)",
                                  time: e.time, includesElectronic: e.includesElectronic)
            Button("Save report image…") { ReportExport.saveAs(data) }
            Button("Copy report image") { ReportExport.copy(data) }
            Divider()
            Button("Delete", role: .destructive) { model.delete(e.id) }
        }
    }
}

// MARK: Settings

struct SettingsView: View {
    @AppStorage("showWear") private var showWear = true
    @AppStorage("reportWear") private var reportWear = true
    var body: some View {
        Form {
            Toggle("Show shutter life in the app", isOn: $showWear)
            Text("The rated life, wear scale and condition next to each count.").font(.caption).foregroundStyle(.secondary)
            Toggle("Include shutter life in reports", isOn: $reportWear)
            Text("Adds rated life, % used and condition to the report image you share with buyers.").font(.caption).foregroundStyle(.secondary)
        }
        .padding(20)
        .frame(width: 440)
    }
}
