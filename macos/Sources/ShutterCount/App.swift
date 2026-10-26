import SwiftUI

@main
struct ShutterCountApp: App {
    @StateObject private var model = AppModel()
    @StateObject private var cameras = CameraManager()

    init() { Fonts.register() }

    var body: some Scene {
        WindowGroup("Shutter Count") {
            Themed { ContentView() }
                .environmentObject(model)
                .environmentObject(cameras)
                .frame(minWidth: 820, minHeight: 640)
        }
        .windowToolbarStyle(.unified(showsTitle: false))
        .commands { CommandGroup(replacing: .newItem) {} }

        Settings {
            Themed { SettingsView() }
        }
    }
}
