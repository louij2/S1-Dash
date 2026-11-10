import Foundation

/// Made-up readings for the CarPlay screen until the app reads OpenHaldex and the
/// ECU itself. Same idea as the Android app's DemoSource: a pull every ~12 s.
final class DashData {
    static let shared = DashData()
    static let modes = ["Stock", "FWD", "50 : 50", "60 : 40", "75 : 25", "Expert"]

    struct Reading {
        let label: String
        let value: String
    }

    var mode = 2
    private let start = Date()

    func readings() -> [Reading] {
        let t = Date().timeIntervalSince(start)
        let pull = max(0, sin(t * 0.5))
        let boost = pull * 1.78 - 0.55 * (1 - pull)
        return [
            Reading(label: "Boost", value: String(format: "%.2f bar", boost)),
            Reading(label: "Oil", value: String(format: "%.0f °C", min(104, 62 + t * 0.25))),
            Reading(label: "Coolant", value: String(format: "%.0f °C", min(90, 70 + t * 0.4))),
            Reading(label: "Haldex clutch", value: String(format: "%.0f °C", min(70, 30 + t * 0.15 + pull * 6))),
            Reading(label: "Haldex lock", value: String(format: "%.0f %%", 30 + pull * 60)),
            Reading(label: "Engine", value: String(format: "%.0f rpm", 1500 + pull * 4800)),
        ]
    }
}
