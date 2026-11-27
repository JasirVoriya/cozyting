import Darwin
import Foundation

/// Cross-platform POSIX (`getifaddrs`) LAN IPv4 discovery helper (zero `#if os(...)`).
public enum NetworkInterfaceHelper {
    /// Returns the preferred local IPv4 address for LAN mDNS / MiPlay binding (e.g., `en0` Wi-Fi).
    /// Returns `""` if no non-loopback IPv4 interface is active (L1 falls back to system routing).
    public static func primaryLocalIPv4() -> String {
        var ifaddrPtr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddrPtr) == 0, let firstAddr = ifaddrPtr else {
            return ""
        }
        defer { freeifaddrs(ifaddrPtr) }

        var candidates: [(priority: Int, ip: String)] = []
        var cursor: UnsafeMutablePointer<ifaddrs>? = firstAddr
        while let current = cursor {
            defer { cursor = current.pointee.ifa_next }
            let flags = Int32(current.pointee.ifa_flags)
            guard (flags & IFF_UP) != 0,
                  (flags & IFF_RUNNING) != 0,
                  (flags & IFF_LOOPBACK) == 0,
                  let sa = current.pointee.ifa_addr,
                  sa.pointee.sa_family == UInt8(AF_INET) else {
                continue
            }

            var hostBuf = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let res = getnameinfo(
                sa,
                socklen_t(sa.pointee.sa_len),
                &hostBuf,
                socklen_t(hostBuf.count),
                nil,
                0,
                NI_NUMERICHOST
            )
            guard res == 0 else { continue }
            let ip = String(decoding: hostBuf.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
            guard isUsableLANIPv4(ip) else { continue }

            let name = String(cString: current.pointee.ifa_name)
            let priority = interfacePriority(name: name, ip: ip)
            candidates.append((priority, ip))
        }

        return candidates.sorted { $0.priority < $1.priority }.first?.ip ?? ""
    }

    /// Validates a dotted-quad IPv4 string suitable for manual speaker addition.
    public static func isValidIPv4(_ raw: String) -> Bool {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        var addr = in_addr()
        return trimmed.withCString { inet_pton(AF_INET, $0, &addr) == 1 }
    }

    /// Rejects loopback (`127.x`), link-local (`169.254.x`), and `0.0.0.0`.
    public static func isUsableLANIPv4(_ ip: String) -> Bool {
        guard isValidIPv4(ip) else { return false }
        if ip.hasPrefix("127.") || ip.hasPrefix("169.254.") || ip == "0.0.0.0" {
            return false
        }
        return true
    }

    static func interfacePriority(name: String, ip: String) -> Int {
        let isRFC1918 = ip.hasPrefix("192.168.") || ip.hasPrefix("10.") || is172Private(ip)
        let ifaceRank: Int
        if name == "en0" {
            ifaceRank = 0
        } else if name.hasPrefix("en") {
            ifaceRank = 1
        } else if name.hasPrefix("bridge") {
            ifaceRank = 3
        } else if name.hasPrefix("utun") || name.hasPrefix("awdl") || name.hasPrefix("llw") {
            ifaceRank = 10
        } else {
            ifaceRank = 5
        }
        return ifaceRank * 10 + (isRFC1918 ? 0 : 5)
    }

    private static func is172Private(_ ip: String) -> Bool {
        let parts = ip.split(separator: ".")
        guard parts.count == 4, parts[0] == "172", let second = Int(parts[1]) else {
            return false
        }
        return (16...31).contains(second)
    }
}
