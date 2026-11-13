import Foundation

/// Polls the OpenHaldex-C6 controller's HTTP API over its Wi-Fi access point, like the
/// Android OpenHaldexSource. OpenHaldex stays the only thing that controls the Haldex;
/// mode changes are requests to it (POST /api/mode).
final class OpenHaldexSource {
    private let host: String
    private let session: URLSession
    private var thread: Thread?
    private let lock = NSLock()
    private var commands: [() -> Void] = []
    private let wake = DispatchSemaphore(value: 0)

    init(host: String) {
        self.host = host
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 1.5
        cfg.timeoutIntervalForResource = 3
        cfg.waitsForConnectivity = false
        // The OpenHaldex access point has no internet; it must still be used.
        cfg.allowsConstrainedNetworkAccess = true
        cfg.allowsExpensiveNetworkAccess = true
        session = URLSession(configuration: cfg)
    }

    func start() {
        let t = Thread { [weak self] in self?.loop() }
        t.name = "openhaldex"
        thread = t
        t.start()
    }

    func stop() {
        thread?.cancel()
        wake.signal()
        session.invalidateAndCancel()
    }

    func setMode(_ mode: Int) { enqueue { [weak self] in _ = self?.post("/api/mode", "{\"mode\":\(mode)}") } }
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
        var fails = 0
        while !cancelled {
            runCommands()
            guard let body = get("/api/dashboard"),
                  let o = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] else {
                fails += 1
                if fails >= 3 { hub.setStatus("oh", "no reply") }
                _ = wake.wait(timeout: .now() + 1)
                continue
            }
            fails = 0
            hub.setStatus("oh", "connected")
            parse(o)
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

    private func request(_ path: String, body: String?) -> (Int, Data)? {
        guard let url = URL(string: "http://\(host)\(path)") else { return nil }
        var req = URLRequest(url: url)
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

    private func post(_ path: String, _ json: String) -> Data? {
        request(path, body: json)?.1
    }
}
