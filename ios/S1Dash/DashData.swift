import Foundation

/// What the CarPlay screen shows, read from the same Hub as the phone screen
/// (OpenHaldex over Wi-Fi, or the demo data when Settings › Demo is on).
final class DashData {
    static let shared = DashData()
    static let modes = ["Stock", "FWD", "50 : 50", "60 : 40", "75 : 25", "Expert"]

    struct Reading {
        let label: String
        let value: String
    }

    var mode: Int? { Hub.shared.get("hxMode").map { Int($0) } }

    func setMode(_ m: Int) { Hub.shared.setMode(m) }

    var source: String {
        Hub.shared.setting("demo") == "1" ? "Demo data" : "OpenHaldex"
    }

    func readings() -> [Reading] {
        let hub = Hub.shared
        func f(_ key: String, _ format: String) -> String {
            hub.get(key).map { String(format: format, $0) } ?? "--"
        }
        return [
            Reading(label: "Boost", value: f("boost", "%.2f bar")),
            Reading(label: "Oil", value: f("oil", "%.0f °C")),
            Reading(label: "Coolant", value: f("coolant", "%.0f °C")),
            Reading(label: "Haldex clutch", value: f("hxClutchTemp", "%.0f °C")),
            Reading(label: "Haldex lock", value: f("hxLock", "%.0f %%")),
            Reading(label: "Engine", value: hub.get("rpm") != nil ? f("rpm", "%.0f rpm") : f("ohRpm", "%.0f rpm")),
        ]
    }
}
