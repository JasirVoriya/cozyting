// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "CozyTingShared",
    platforms: [
        .macOS(.v14),
        .iOS(.v16),
    ],
    products: [
        .library(
            name: "CozyTingCoreKit",
            targets: ["CozyTingCoreKit"]
        ),
        .library(
            name: "CozyTingAudioKit",
            targets: ["CozyTingAudioKit"]
        ),
        .library(
            name: "CozyTingDesignKit",
            targets: ["CozyTingDesignKit"]
        ),
    ],
    targets: [
        .binaryTarget(
            name: "CozyTingCore",
            path: "../Frameworks/CozyTingCore.xcframework"
        ),
        .target(
            name: "CozyTingCoreKit",
            dependencies: ["CozyTingCore"]
        ),
        .target(
            name: "CozyTingAudioAtomics"
        ),
        .target(
            name: "CozyTingAudioKit",
            dependencies: ["CozyTingCoreKit", "CozyTingAudioAtomics"]
        ),
        .target(
            name: "CozyTingDesignKit",
            dependencies: ["CozyTingCoreKit"],
            resources: [
                .process("Resources"),
            ]
        ),
        .testTarget(
            name: "CozyTingCoreKitTests",
            dependencies: ["CozyTingCoreKit"]
        ),
        .testTarget(
            name: "CozyTingAudioKitTests",
            dependencies: ["CozyTingAudioKit"]
        ),
        .testTarget(
            name: "CozyTingDesignKitTests",
            dependencies: ["CozyTingDesignKit"]
        ),
    ]
)
