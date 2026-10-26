// swift-tools-version:5.9
import PackageDescription

// Mojave / Catalina build. Shares ShutterCore with the main Mac app (symlinked),
// compiled without its async/await parts so nothing needs the Swift concurrency runtime.
let package = Package(
    name: "ShutterCountLegacy",
    platforms: [.macOS(.v10_14)],
    targets: [
        .target(name: "ShutterCore", swiftSettings: [.define("LEGACY")]),
        .executableTarget(name: "ShutterCountLegacy", dependencies: ["ShutterCore"]),
    ]
)
