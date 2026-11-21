import Foundation

/// Polls the OpenHaldex-C6 controller's HTTP API over its Wi-Fi access point, like the
/// Android OpenHaldexSource. OpenHaldex stays the only thing that controls the Haldex;
/// mode changes are requests to it (POST /api/mode).
final class OpenHaldexSource {
    private let address: OhAddress
    private var finder: OhFinder?
    private var host: String { address.host }
    private let session: URLSession
    private var thread: Thread?
    private let lock = NSLock()
    private var commands: [() -> Void] = []
    private let wake = DispatchSemaphore(value: 0)

    init(host setting: String) {
        address = OhAddress(setting: setting)
        let cfg = URLSessionConfiguration.ephemeral
        // The C6 shares one radio between Wi-Fi and BLE; about 1 in 40 replies takes 2-5 s (#1061).
        cfg.timeoutIntervalForRequest = 4
        cfg.timeoutIntervalForResource = 6
        cfg.waitsForConnectivity = false
        // The OpenHaldex access point has no internet; it must still be used.
        cfg.allowsConstrainedNetworkAccess = true
        cfg.allowsExpensiveNetworkAccess = true
        session = URLSession(configuration: cfg)
    }

    func start() {
        if address.auto {
            finder = OhFinder { [weak self] found in
                self?.address.found(found)
                self?.showAddress()
            }
            finder?.search()
        }
        showAddress()
        let t = Thread { [weak self] in self?.loop() }
        t.name = "openhaldex"
        thread = t
        t.start()
    }

    private func showAddress() { Hub.shared.setStatus("ohAt", address.shown) }

    func stop() {
        finder?.stop()
        thread?.cancel()
        wake.signal()
        session.invalidateAndCancel()
    }

    func setMode(_ mode: Int) {
        enqueue { [weak self] in
            _ = self?.post("/api/mode", "{\"mode\":\(mode)}", timeout: 1) // never answered by the firmware
            self?.lastSettings = .distantPast // re-read the flags that can make the controller ignore it
        }
    }

    private var lastSettings = Date.distantPast
    func readDtcs() { enqueue { [weak self] in self?.dtcJob(clear: false) } }
    func clearDtcs() { enqueue { [weak self] in self?.dtcJob(clear: true) } }

    private func enqueue(_ cmd: @escaping () -> Void) {
        lock.lock(); commands.append(cmd); lock.unlock()
        wake.signal()
    }

    private func runCommands() {
        while true {
            lock.lock()
            let cmd = commands.isEmpty ? nil : commands.removeFirst()
            lock.unlock()
            guard let c = cmd else { return }
            c()
        }
    }

    private var cancelled: Bool { Thread.current.isCancelled }

    private func loop() {
        let hub = Hub.shared
        var lastDtc = Date.distantPast
        var lastGood = Date()
        // Lost means no good reply for 8 s, not a count of misses: one slow C6 reply isn't a drop (#1061).
        func lost(_ why: String) { if Date().timeIntervalSince(lastGood) > 8 { hub.setStatus("oh", why) } }
        while !cancelled {
            runCommands()
            guard let body = get("/api/dashboard"),
                  let o = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] else {
                lost("no reply")
                if address.failed() { // moved (bridge mode on a hotspot)? look for it by name
                    showAddress()
                    finder?.search()
                }
                _ = wake.wait(timeout: .now() + 1)
                continue
            }
            lastGood = Date()
            address.answered()
            hub.setStatus("oh", "connected")
            parse(o)
            // Flags that make OpenHaldex ignore mode requests, on connect and every 30 s.
            if Date().timeIntervalSince(lastSettings) > 30 {
                lastSettings = Date()
                if let d = get("/api/settings"), let s = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] {
                    readSettings(s)
                }
            }
            // Haldex fault codes on connect and every 5 minutes.
            if Date().timeIntervalSince(lastDtc) > 300 {
                lastDtc = Date()
                dtcJob(clear: false)
            }
            _ = wake.wait(timeout: .now() + 0.2)
        }
    }

    private func num(_ o: [String: Any], _ k: String) -> Double? {
        if let n = o[k] as? NSNumber { return n.doubleValue }
        return nil
    }

    private func parse(_ o: [String: Any]) {
        let hub = Hub.shared
        hub.put("hxMode", num(o, "mode"))
        hub.put("hxLock", num(o, "lockActual") ?? num(o, "haldexEngagement"))
        hub.put("hxLockTarget", num(o, "lockTarget"))
        if let p = o["tempProtection"] as? Bool { hub.put("hxTempProt", p ? 1 : 0) }
        hub.put("ohRpm", num(o, "rpm"))
        // OpenHaldex 11.x: boost is gauge mbar (clamped at 0), oil/coolant are whole °C from
        // Kombi_2 (null when stale). Used only when the ECU gives nothing (Hub fallback).
        if let b = num(o, "boost") { hub.put("ohBoost", b / 1000) }
        hub.put("ohOil", num(o, "oilTemp"))
        hub.put("ohCoolant", num(o, "coolantTemp"))
        hub.put("speed", num(o, "speed"))
        hub.put("throttle", num(o, "throttle"))
        if let u = o["uds"] as? [String: Any] {
            hub.put("hxVolts", num(u, "terminalVoltage"))
            hub.put("hxClutchTemp", num(u, "clutchTemp"))
            hub.put("hxModuleTemp", num(u, "moduleTemp"))
            hub.put("hxFinTemp", num(u, "coolingFinTemp"))
            hub.put("hxClutchCurrent", num(u, "clutchCurrent"))
        }
        hub.setStatus("ohDiagTool", (o["diagToolActive"] as? Bool) == true ? "1" : "0")
        // Force-mode triggers (#1049); null while OpenHaldex has no chassis CAN, so they go stale.
        for (k, key, whenTrue) in [("tcOn", "hxTcOff", false), ("asrOn", "hxAsrOff", false),
                                   ("hazardActive", "hxHazard", true), ("extButtonActive", "hxExtBtn", true)] {
            if let b = o[k] as? Bool { hub.put(key, b == whenTrue ? 1 : 0) }
        }
        if let d = o["driveMode"] as? [String: Any], let f = num(d, "forcing") { hub.put("hxDriveForce", f) }
    }

    /// With Disable controller on, OpenHaldex ignores every mode request (its own button still works);
    /// in standalone mode it turns Stock into "keep the last mode". The UI explains a tap that didn't take.
    private func readSettings(_ o: [String: Any]) {
        Hub.shared.setStatus("ohDisabled", (o["disableController"] as? Bool) == true ? "1" : "0")
        Hub.shared.setStatus("ohStandalone", (o["isStandalone"] as? Bool) == true ? "1" : "0")
        // Which triggers force a mode, to what, and which wins: the UI works out the mode in use (#1049).
        func pair(_ en: String, _ val: String) -> [Any] { [(o[en] as? Bool) == true, (o[val] as? NSNumber)?.intValue ?? -1] }
        Hub.shared.setStatus("ohForce", Hub.shared.json([
            "tc": pair("tcForceMode", "tcForceModeValue"), "hz": pair("hazardForceMode", "hazardForceModeValue"),
            "ext": pair("extButtonForceMode", "extBtnForceModeValue"), "prio": (o["forceModesPriority"] as? NSNumber)?.intValue ?? 0,
        ]))
    }

    /// Runs a Haldex DTC read (or clear) job on the controller and waits for the result.
    private func dtcJob(clear: Bool) {
        let ok = clear ? post("/api/dtc/clear", "{\"confirm\":\"CLEAR\"}") : post("/api/dtc/read", "{}")
        guard ok != nil else { return }
        for _ in 0..<20 {
            Thread.sleep(forTimeInterval: 0.5)
            if cancelled { return }
            guard let body = get("/api/dtc"),
                  let o = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] else { continue }
            let state = o["state"] as? String
            if state == "done" || state == "error" {
                Hub.shared.setHaldexDtcs(String(data: body, encoding: .utf8) ?? "{}")
                if state == "done" { Hub.shared.put("dtcHaldex", Double((o["dtcs"] as? [Any])?.count ?? 0)) }
                return
            }
        }
    }

    // MARK: HTTP, blocking on the source's own thread

    private func request(_ path: String, body: String?, timeout: TimeInterval = 4) -> (Int, Data)? {
        guard let url = URL(string: "http://\(host)\(path)") else { return nil }
        var req = URLRequest(url: url)
        req.timeoutInterval = timeout
        if let b = body {
            req.httpMethod = "POST"
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = b.data(using: .utf8)
        }
        let done = DispatchSemaphore(value: 0)
        var result: (Int, Data)?
        session.dataTask(with: req) { data, resp, _ in
            if let h = resp as? HTTPURLResponse { result = (h.statusCode, data ?? Data()) }
            done.signal()
        }.resume()
        done.wait()
        return result
    }

    private func get(_ path: String) -> Data? {
        guard let (code, data) = request(path, body: nil), code == 200 else { return nil }
        return data
    }

    private func post(_ path: String, _ json: String, timeout: TimeInterval = 4) -> Data? {
        request(path, body: json, timeout: timeout)?.1
    }
}
