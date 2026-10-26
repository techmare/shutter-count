// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "ShutterCount",
    platforms: [.macOS(.v13)],
    targets: [
        .target(name: "ShutterCore"),
        .executableTarget(name: "ShutterCount", dependencies: ["ShutterCore"]),
        .testTarget(name: "ShutterCoreTests", dependencies: ["ShutterCore"]),
    ]
)
