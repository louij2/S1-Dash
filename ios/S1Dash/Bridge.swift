import Foundation
import WebKit

/// Gives the web UI the same `window.S1` bridge the Android app has. Android's bridge
/// answers synchronously; WKWebView can't, so a small script keeps a copy of the answers
/// that Swift pushes in ten times a second, and the actions go to Swift as messages.
final class Bridge: NSObject, WKScriptMessageHandler {
    weak var web: WKWebView?
    private var timer: Timer?

    private static let version: String = {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        return v + " (iPhone)"
    }()

    /// Answers for the parts the iPhone app doesn't have yet (ECU dongle, CAL flashing).
    private static let fixed: [String: Any] = [
        "version": version,
        "ecuDtcs": "{}",
        "clutchInfo": "{\"gears\":[]}",
        "pairedDevices": "[]",
        "calState": "{\"state\":\"\",\"message\":\"Flashing from the iPhone isn't supported yet. Use S1 Dash and SimosTools on Android.\",\"slots\":[]}",
    ]

    private func state() -> [String: Any] {
        let hub = Hub.shared
        return ["snapshot": hub.snapshot(), "settings": hub.settingsJSON(),
                "haldexDtcs": hub.haldexDtcsJSON(), "logInfo": Logger.shared.infoJSON()]
    }

    /// The scripts that run before the page's own: error logging and the bridge with its first answers.
    func install(on controller: WKUserContentController) {
        controller.removeAllUserScripts()
        controller.addUserScript(WKUserScript(source: """
            window.onerror = function (m, s, l) { window.webkit.messageHandlers.log.postMessage("error: " + m + " at " + s + ":" + l); };
            """, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        let first = Hub.shared.json(Bridge.fixed.merging(state()) { a, _ in a })
        controller.addUserScript(WKUserScript(source: "var __s1first = \(first);\n" + Bridge.shim,
                                              injectionTime: .atDocumentStart, forMainFrameOnly: true))
    }

    func startPushing() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in self?.push() }
    }

    func stopPushing() {
        timer?.invalidate()
        timer = nil
    }

    private func push() {
        guard let web = web else { return }
        web.evaluateJavaScript("window.__s1push && window.__s1push(\(Hub.shared.json(state())))", completionHandler: nil)
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any], let m = body["m"] as? String else { return }
        let a = body["a"] as? [Any] ?? []
        let hub = Hub.shared
        switch m {
        case "setSetting":
            if a.count == 2, let k = a[0] as? String { hub.setSetting(k, "\(a[1])") }
        case "setMode":
            if let n = a.first as? NSNumber { hub.setMode(n.intValue) }
        case "resetPeak": hub.resetPeak()
        case "haldexDtcRead": hub.haldexDtcRead()
        case "haldexDtcClear": hub.haldexDtcClear()
        case "logStart": Logger.shared.start()
        case "logStop": Logger.shared.stop()
        case "reconnect": hub.restartSources()
        default: break  // ECU, clutch and CAL actions: not on iPhone yet
        }
        push()
    }

    /// ES2017, same as app.js.
    private static let shim = """
        (function () {
          var c = __s1first;
          function post(m, a) { try { window.webkit.messageHandlers.s1.postMessage({ m: m, a: a || [] }); } catch (e) {} }
          function get(k) { return function () { return c[k]; }; }
          function act(m) { return function () { post(m, Array.prototype.slice.call(arguments)); }; }
          window.__s1push = function (o) { for (var k in o) c[k] = o[k]; };
          window.S1 = {
            snapshot: get("snapshot"), settings: get("settings"), version: get("version"),
            setSetting: function (k, v) {
              var s = JSON.parse(c.settings); s[k] = String(v); c.settings = JSON.stringify(s);
              post("setSetting", [k, String(v)]);
            },
            setMode: act("setMode"), resetPeak: act("resetPeak"), pollFocus: act("pollFocus"),
            clutchInfo: get("clutchInfo"), clutchReset: act("clutchReset"),
            haldexDtcs: get("haldexDtcs"), haldexDtcRead: act("haldexDtcRead"), haldexDtcClear: act("haldexDtcClear"),
            ecuDtcs: get("ecuDtcs"), ecuDtcRead: act("ecuDtcRead"), ecuDtcClear: act("ecuDtcClear"),
            logStart: act("logStart"), logStop: act("logStop"), logInfo: get("logInfo"),
            pairedDevices: get("pairedDevices"), openSimosTools: function () { return false; }, reconnect: act("reconnect"),
            calState: get("calState"), calPick: act("calPick"), calClear: act("calClear"), calPrepare: act("calPrepare"),
            calStart: function () { return "Flashing from the iPhone isn't supported yet."; },
            calReopen: act("calReopen"), calFinished: act("calFinished"), calCancel: act("calCancel"), calFinalise: act("calFinalise")
          };
        })();
        """
}
