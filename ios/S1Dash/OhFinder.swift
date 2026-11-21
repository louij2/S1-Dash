import Foundation

/// Which address to poll OpenHaldex at (#992), like the Android OhAddress. With "auto",
/// OpenHaldex is found by name: the firmware advertises "openhaldex" (_http._tcp) on its own AP
/// and in bridge mode on another network, such as a hotspot whose subnet changes. The last
/// address found is kept, and 192.168.1.1 (its own AP) is the fallback.
final class OhAddress {
    static let fallback = "192.168.1.1"
    private static let lastFoundKey = "s1.ohLastFound"
    private let lock = NSLock()
    let auto: Bool
    /// Last good reply (or start); search again only after 20 s without one, then every 20 s (#1061).
    private var lastGood = Date()
    private var lastSearch = Date()
    private var _host: String
    private var _source: String

    init(setting: String) {
        let s = setting.trimmingCharacters(in: .whitespaces)
        auto = s.isEmpty || s.lowercased() == "auto"
        if !auto { _host = s; _source = "typed" }
        else if let last = UserDefaults.standard.string(forKey: OhAddress.lastFoundKey) { _host = last; _source = "last found" }
        else { _host = OhAddress.fallback; _source = "default" }
    }

    var host: String { lock.lock(); defer { lock.unlock() }; return _host }
    var shown: String { lock.lock(); defer { lock.unlock() }; return "\(_host) (\(_source))" }

    /// A poll failed. Returns true when it's time to look for OpenHaldex again.
    func failed() -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard auto else { return false }
        let now = Date()
        guard now.timeIntervalSince(lastGood) >= 20, now.timeIntervalSince(lastSearch) >= 20 else { return false }
        lastSearch = now
        if _host != OhAddress.fallback { _host = OhAddress.fallback; _source = "default" }
        return true
    }

    func answered() { lock.lock(); lastGood = Date(); lock.unlock() }

    func found(_ hostPort: String) {
        lock.lock(); defer { lock.unlock() }
        guard auto else { return }
        _host = hostPort; _source = "found by name"; lastGood = Date()
        UserDefaults.standard.set(hostPort, forKey: OhAddress.lastFoundKey)
    }

    static func isOpenHaldex(_ name: String) -> Bool {
        let n = name.lowercased()
        return n == "openhaldex" || n.hasPrefix("openhaldex (")
    }
}

/// Browses Bonjour for the "openhaldex" HTTP service and resolves it to its host name and port
/// (openhaldex.local). URLSession looks the .local name up itself, over IPv4 or IPv6.
/// NetService rather than Network.framework: its resolve gives the name without opening a
/// connection, which is all a URL needs. Runs on the main run loop.
final class OhFinder: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
    private var browser: NetServiceBrowser?
    private var service: NetService?
    private let onFound: (String) -> Void

    init(onFound: @escaping (String) -> Void) { self.onFound = onFound }

    func search() {
        DispatchQueue.main.async { [weak self] in
            guard let self = self, self.browser == nil else { return }
            let b = NetServiceBrowser()
            b.delegate = self
            self.browser = b
            print("S1Dash: looking for OpenHaldex")
            b.searchForServices(ofType: "_http._tcp.", inDomain: "local.")
        }
    }

    func stop() { DispatchQueue.main.async { [weak self] in self?.stopNow() } }

    private func stopNow() {
        browser?.stop(); browser = nil
        service?.stop(); service = nil
    }

    func netServiceBrowser(_ b: NetServiceBrowser, didFind s: NetService, moreComing: Bool) {
        guard service == nil, OhAddress.isOpenHaldex(s.name) else { return }
        service = s
        s.delegate = self
        s.resolve(withTimeout: 5)
    }

    func netServiceBrowser(_ b: NetServiceBrowser, didNotSearch errorDict: [String: NSNumber]) {
        print("S1Dash: couldn't look for OpenHaldex: \(errorDict)")
        browser = nil
    }

    func netServiceDidResolveAddress(_ s: NetService) {
        guard let name = s.hostName, s.port > 0 else { return }
        let host = name.hasSuffix(".") ? String(name.dropLast()) : name
        let found = s.port == 80 ? host : "\(host):\(s.port)"
        print("S1Dash: found OpenHaldex at \(found)")
        onFound(found)
        stopNow() // found it; search again only if it stops answering
    }

    func netService(_ s: NetService, didNotResolve errorDict: [String: NSNumber]) {
        print("S1Dash: couldn't resolve OpenHaldex: \(errorDict)")
        service = nil
    }
}
