import Foundation
import Testing
@testable import CozyTingCoreKit

@Suite("Discovery, Formatter & LatencyPreset Tests")
struct DiscoveryAndFormatterTests {
    @Test("NetworkInterfaceHelper validates IPv4 and filters loopback/link-local")
    func networkInterfaceIPv4Validation() {
        #expect(NetworkInterfaceHelper.isValidIPv4("192.0.2.10") == true)
        #expect(NetworkInterfaceHelper.isValidIPv4("198.51.100.42") == true)
        #expect(NetworkInterfaceHelper.isValidIPv4("999.0.0.1") == false)
        #expect(NetworkInterfaceHelper.isValidIPv4("not-an-ip") == false)
        #expect(NetworkInterfaceHelper.isValidIPv4("") == false)

        #expect(NetworkInterfaceHelper.isUsableLANIPv4("192.0.2.10") == true)
        #expect(NetworkInterfaceHelper.isUsableLANIPv4("127.0.0.1") == false)
        #expect(NetworkInterfaceHelper.isUsableLANIPv4("169.254.1.5") == false)
        #expect(NetworkInterfaceHelper.isUsableLANIPv4("0.0.0.0") == false)
    }

    @Test("DeviceDisplayFormatter formats miName · roomName cleanly without duplication")
    func displayTitleFormatting() {
        let withRoom = Device(
            id: "192.0.2.10",
            name: "小爱音箱-0001",
            kind: .speaker,
            castable: true,
            selected: false,
            roomName: "客厅"
        )
        #expect(DeviceDisplayFormatter.displayTitle(for: withRoom) == "小爱音箱-0001 · 客厅")

        let alreadyContainsRoom = Device(
            id: "192.0.2.11",
            name: "客厅小爱音箱",
            kind: .speaker,
            castable: true,
            selected: false,
            roomName: "客厅"
        )
        #expect(DeviceDisplayFormatter.displayTitle(for: alreadyContainsRoom) == "客厅小爱音箱")

        let noRoom = Device(
            id: "192.0.2.12",
            name: "书房音箱",
            kind: .speaker,
            castable: true,
            selected: false,
            roomName: ""
        )
        #expect(DeviceDisplayFormatter.displayTitle(for: noRoom) == "书房音箱")
    }

    @Test("DeviceDisplayFormatter pins thisDevice first, then selected, then lastSelectedIds")
    func orderedDevicesPinningAndFiltering() {
        let thisDev = DeviceDisplayFormatter.makeThisDevice(displayName: "Mac", selected: true)
        let spkA = Device(id: "192.0.2.1", name: "Alpha", kind: .speaker, castable: true, selected: false)
        let spkB = Device(id: "192.0.2.2", name: "Beta", kind: .speaker, castable: true, selected: false)
        let spkC = Device(id: "192.0.2.3", name: "Gamma", kind: .speaker, castable: true, selected: false)
        let hidden = Device(id: "192.0.2.99", name: "OtherNode", kind: .speaker, castable: false, selected: false)

        let orderedDefault = DeviceDisplayFormatter.orderedDevices(
            [hidden, spkC, spkA, thisDev, spkB],
            lastSelectedIds: ["192.0.2.2", "192.0.2.3"],
            showAllDiscovered: false
        )
        #expect(orderedDefault.map(\.id) == ["this", "192.0.2.2", "192.0.2.3", "192.0.2.1"])

        let orderedAll = DeviceDisplayFormatter.orderedDevices(
            [hidden, spkC, spkA, thisDev, spkB],
            lastSelectedIds: ["192.0.2.2"],
            showAllDiscovered: true
        )
        #expect(orderedAll.map(\.id) == ["this", "192.0.2.2", "192.0.2.1", "192.0.2.3", "192.0.2.99"])
    }

    @Test("LatencyPreset maps 4 tiers + custom ms and delegates standard to L1 frequency rule")
    func latencyPresetMappings() {
        #expect(LatencyPreset(delayMs: 0) == .standard)
        #expect(LatencyPreset(delayMs: 400) == .balanced)
        #expect(LatencyPreset(delayMs: 250) == .low)
        #expect(LatencyPreset(delayMs: 150) == .ultra)
        #expect(LatencyPreset(delayMs: 650) == .custom(ms: 650))

        #expect(LatencyPreset.standard.effectiveDelayMs(wifiFrequencyMHz: 5180) == 800)
        #expect(LatencyPreset.standard.effectiveDelayMs(wifiFrequencyMHz: 2412) == 1000)
        #expect(LatencyPreset.standard.effectiveDelayMs(wifiFrequencyMHz: 0) == 1000)
        #expect(LatencyPreset.balanced.effectiveDelayMs(wifiFrequencyMHz: 5180) == 400)
    }
}
