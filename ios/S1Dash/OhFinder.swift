import Foundation
import Network

/// Which address to poll OpenHaldex at (#992), like the Android OhAddress. With "auto",
/// OpenHaldex is found by name: the firmware advertises "openhaldex" (_http._tcp) on its own AP
/// and in bridge mode on another network, such as a hotspot whose subnet changes. The last
/// address found is kept, and 192.168.1.1 (its own AP) is the fallback.
final class OhAddress {
    static let fallback = "192.168.1.1"
    private static let lastFoundKey = "s1.ohLastFound"
    private let lock = NSLock()
    let auto: Bool
    private var fails = 0
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
        fails += 1
        guard fails % 3 == 0 else { return false }
        if _host != OhAddress.fallback { _host = OhAddress.fallback; _source = "default" }
        return true
    }

    func answered() { lock.lock(); fails = 0; lock.unlock() }

    func found(_ hostPort: String) {
        lock.lock(); defer { lock.unlock() }
        guard auto else { return }
        _host = hostPort; _source = "found by name"; fails = 0
        UserDefaults.standard.set(hostPort, forKey: OhAddress.lastFoundKey)
    }

    static func isOpenHaldex(_ name: String) -> Bool {
        let n = name.lowercased()
        return n == "openhaldex" || n.hasPrefix("openhaldex (")
    }
}

/// Browses Bonjour for the "openhaldex" HTTP service and resolves it to an IPv4 host:port.
final class OhFinder {
    private let queue = DispatchQueue(label: "ohfinder")
    private var browser: NWBrowser?
    private var resolving: NWConnection?
    private let onFound: (String) -> Void

    init(onFound: @escaping (String) -> Void) { self.onFound = onFound }

    func search() {
        queue.async { [weak self] in
            guard let self = self, self.browser == nil else { return }
            let b = NWBrowser(for: .bonjour(type: "_http._tcp", domain: nil), using: .tcp)
            b.browseResultsChangedHandler = { [weak self] results, _ in
                print("S1Dash: Bonjour sees \(results.map { "\($0.endpoint)" })")
                for r in results {
                    if case let .service(name, _, _, _) = r.endpoint, OhAddress.isOpenHaldex(name) {
                        self?.resolve(r.endpoint)
                        return
                    }
                }
            }
            b.stateUpdateHandler = { [weak self] state in
                print("S1Dash: looking for OpenHaldex: \(state)")
                if case .failed = state { self?.stopOnQueue() }
            }
            self.browser = b
            b.start(queue: self.queue)
        }
    }

    func stop() { queue.async { [weak self] in self?.stopOnQueue() } }

    private func stopOnQueue() {
        browser?.cancel(); browser = nil
        resolving?.cancel(); resolving = nil
    }

    /// Connecting is how Network.framework resolves a Bonjour service; IPv4 only, for a plain URL.
    private func resolve(_ endpoint: NWEndpoint) {
        guard resolving == nil else { return }
        let params = NWParameters.tcp
        if let ip = params.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options { ip.version = .v4 }
        let c = NWConnection(to: endpoint, using: params)
        resolving = c
        c.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                if case let .hostPort(host, port)? = c.currentPath?.remoteEndpoint, case let .ipv4(addr) = host {
                    let ip = "\(addr)".components(separatedBy: "%")[0]
                    print("S1Dash: found OpenHaldex at \(ip):\(port.rawValue)")
                    self?.onFound(port.rawValue == 80 ? ip : "\(ip):\(port.rawValue)")
                    self?.stopOnQueue() // found it; search again only if it stops answering
                } else {
                    print("S1Dash: OpenHaldex resolved to \(String(describing: c.currentPath?.remoteEndpoint)), not IPv4")
                    c.cancel(); self?.resolving = nil
                }
            case .failed(let e):
                print("S1Dash: couldn't reach the OpenHaldex service: \(e)")
                if self?.resolving === c { self?.resolving = nil }
            case .cancelled:
                if self?.resolving === c { self?.resolving = nil }
            default: break
            }
        }
        c.start(queue: queue)
    }
}
