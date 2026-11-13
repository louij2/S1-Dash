import Foundation

/// Shared state between the data sources and the screens, like the Android app's DataHub.
/// Sources call `put`; the web view and CarPlay read `snapshot()`. Values older than
/// `staleSeconds` are left out, so the screens show "--" instead of a frozen reading.
final class Hub {
    static let shared = Hub()
    static let staleSeconds = 3.0

    static let defaults: [String: String] = [
        "theme": "s1",              // s1 | quattro | mmi
        "ohEnabled": "1",
        "ohHost": "192.168.1.1",    // OpenHaldex access point address
        "ecuAdapter": "none",       // only "none" works on iPhone so far
        "ecuMac": "",
        "bridgeName": "BLE_TO_ISOTP20",
        "dtcIndicator": "1",
        "boostTarget": "1.8",       // expected peak boost, bar, marked on the dial
        "demo": "0",
        "uiScale": "100",
    ]
    private static let restartKeys: Set<String> = ["ohEnabled", "ohHost", "ecuAdapter", "ecuMac", "bridgeName", "demo"]

    /// Values OpenHaldex can stand in for when the ECU gives nothing. The ECU wins when both exist.
    private static let fallback = ["oil": "ohOil", "coolant": "ohCoolant", "boost": "ohBoost"]

    private let lock = NSLock()
    private var values: [String: Double] = [:]
    private var stamps: [String: Date] = [:]
    private var status: [String: String] = [:]
    private var peak = 0.0
    private var haldexDtcs = "{}"
    private let store = UserDefaults.standard

    private var openHaldex: OpenHaldexSource?
    private var demo: DemoSource?
    private var owners = Set<String>()

    // MARK: settings

    func setting(_ key: String) -> String {
        store.string(forKey: "s1." + key) ?? Hub.defaults[key] ?? ""
    }

    func settingsJSON() -> String {
        json(Dictionary(uniqueKeysWithValues: Hub.defaults.keys.map { ($0, setting($0)) }))
    }

    func setSetting(_ key: String, _ value: String) {
        guard Hub.defaults[key] != nil else { return }
        store.set(value, forKey: "s1." + key)
        if Hub.restartKeys.contains(key) { restartSources() }
    }

    // MARK: values

    func put(_ key: String, _ v: Double?) {
        guard let v = v, v.isFinite else { return }
        lock.lock(); defer { lock.unlock() }
        values[key] = v
        stamps[key] = Date()
        if key == "boost" && v > peak { peak = v }
    }

    func setStatus(_ key: String, _ value: String?) {
        lock.lock(); defer { lock.unlock() }
        status[key] = value
    }

    func resetPeak() {
        lock.lock(); defer { lock.unlock() }
        peak = 0
    }

    func setHaldexDtcs(_ body: String) {
        lock.lock(); defer { lock.unlock() }
        haldexDtcs = body
    }

    func haldexDtcsJSON() -> String {
        lock.lock(); defer { lock.unlock() }
        return haldexDtcs
    }

    private func freshLocked(_ key: String, _ now: Date) -> Double? {
        guard let t = stamps[key], now.timeIntervalSince(t) <= Hub.staleSeconds else { return nil }
        return values[key]
    }

    func get(_ key: String) -> Double? {
        lock.lock(); defer { lock.unlock() }
        let now = Date()
        return freshLocked(key, now) ?? Hub.fallback[key].flatMap { freshLocked($0, now) }
    }

    func snapshot() -> String {
        let logging = Logger.shared.active
        lock.lock()
        let now = Date()
        var v: [String: Double] = [:]
        for (k, value) in values where freshLocked(k, now) != nil { v[k] = value }
        for (k, fb) in Hub.fallback where v[k] == nil { if let x = freshLocked(fb, now) { v[k] = x } }
        let o: [String: Any] = ["v": v, "s": status, "peak": peak, "logging": logging,
                                "t": Int(now.timeIntervalSince1970 * 1000)]
        lock.unlock()
        return json(o)
    }

    // MARK: sources

    /// The phone screen and CarPlay both need the data, so it runs until the last of them lets go.
    func acquireSources(_ owner: String) {
        lock.lock()
        let first = owners.isEmpty
        owners.insert(owner)
        lock.unlock()
        if first { startSources() }
    }

    func releaseSources(_ owner: String) {
        lock.lock()
        let removed = owners.remove(owner) != nil
        let last = owners.isEmpty
        lock.unlock()
        if removed && last { stopSources() }
    }

    private func startSources() {
        if setting("demo") == "1" {
            demo = DemoSource()
            demo?.start()
            setStatus("oh", "demo")
            setStatus("ecu", "demo")
            return
        }
        if setting("ohEnabled") == "1" {
            openHaldex = OpenHaldexSource(host: setting("ohHost"))
            openHaldex?.start()
        } else {
            setStatus("oh", "off")
        }
        // The ECU dongle (BLE) isn't in the iPhone app yet.
        setStatus("ecu", setting("ecuAdapter") == "none" ? "off" : "not on iPhone yet")
    }

    private func stopSources() {
        openHaldex?.stop(); openHaldex = nil
        demo?.stop(); demo = nil
        lock.lock()
        values.removeAll(); stamps.removeAll(); status.removeAll()
        lock.unlock()
    }

    func restartSources() {
        lock.lock(); let running = !owners.isEmpty; lock.unlock()
        guard running else { return }
        stopSources()
        startSources()
    }

    func setMode(_ mode: Int) { openHaldex?.setMode(mode) }
    func haldexDtcRead() { openHaldex?.readDtcs() }
    func haldexDtcClear() { openHaldex?.clearDtcs() }

    func json(_ o: Any) -> String {
        guard let d = try? JSONSerialization.data(withJSONObject: o), let s = String(data: d, encoding: .utf8) else { return "{}" }
        return s
    }
}

/// Writes the same CSV columns as the Android logger, at 10 Hz, into the app's Documents/logs,
/// which shows in the Files app under On My iPhone › S1 Dash.
final class Logger {
    static let shared = Logger()

    static let columns = [
        "rpm", "boost", "map", "mapSp", "ambient", "iat", "coolant", "oil", "knockAvg",
        "knock1", "knock2", "knock3", "knock4", "timing", "wastegate", "put", "lambda", "afr", "stft", "ltft",
        "fpDi", "fpMpi", "pedal", "throttle", "speed", "ecuSpeed", "gear", "torque",
        "clutchSlip", "clutchLoad", "clutchWarn", "battery",
        "putSp", "lambdaSp", "torqueReq", "fpDiSp", "fpMpiSp", "lpfpDuty", "cruise", "boostDev", "lambdaDev", "fpDiDev", "fpMpiDev",
        "hxMode", "hxLock", "hxLockTarget", "hxClutchTemp", "hxModuleTemp", "hxVolts", "hxTempProt",
    ]

    private(set) var active = false
    private(set) var file: URL?
    private var thread: Thread?

    var dir: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let d = docs.appendingPathComponent("logs", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    var sessions: Int {
        ((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []).filter { $0.hasSuffix(".csv") }.count
    }

    func infoJSON() -> String {
        Hub.shared.json(["active": active, "sessions": sessions, "dir": "Files › S1 Dash › logs", "file": file?.lastPathComponent ?? ""])
    }

    func start() {
        guard !active else { return }
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd_HH-mm-ss"
        f.locale = Locale(identifier: "en_GB")
        let url = dir.appendingPathComponent("s1dash_\(f.string(from: Date())).csv")
        guard FileManager.default.createFile(atPath: url.path, contents: nil), let h = try? FileHandle(forWritingTo: url) else {
            print("S1Dash: can't create \(url.path)")
            return
        }
        file = url
        active = true
        let t = Thread { [weak self] in
            h.write(("time_ms," + Logger.columns.joined(separator: ",") + "\n").data(using: .utf8)!)
            let t0 = Date()
            while self?.active == true && !Thread.current.isCancelled {
                var row = String(Int(Date().timeIntervalSince(t0) * 1000))
                for c in Logger.columns {
                    row += ","
                    if let v = Hub.shared.get(c) { row += String(format: "%.3f", v) }
                }
                h.write((row + "\n").data(using: .utf8)!)
                Thread.sleep(forTimeInterval: 0.1)
            }
            try? h.close()
        }
        t.name = "logger"
        thread = t
        t.start()
    }

    func stop() {
        active = false
        thread?.cancel()
        thread = nil
    }
}
