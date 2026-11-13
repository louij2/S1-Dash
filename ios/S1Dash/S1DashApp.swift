import SwiftUI
import WebKit

/// The dash screens are the Android app's web UI. Bridge.swift gives them the same
/// `window.S1` bridge as Android, fed by OpenHaldex over Wi-Fi (or the demo data).
@main
struct S1DashApp: App {
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            DashView()
                .background(Color.black)
                .statusBarHidden(true)
                .persistentSystemOverlays(.hidden)
        }
        .onChange(of: phase) { p in
            // Read the car only while the dash is on screen, and keep the screen on meanwhile.
            if p == .active {
                Hub.shared.acquireSources("screen")
                UIApplication.shared.isIdleTimerDisabled = true
            } else if p == .background {
                Hub.shared.releaseSources("screen")
                UIApplication.shared.isIdleTimerDisabled = false
            }
        }
    }
}

struct DashView: UIViewRepresentable {
    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        // Script errors and a layout check go to the app's log, since there's no console on the phone.
        config.userContentController.add(context.coordinator, name: "log")
        config.userContentController.add(context.coordinator.bridge, name: "s1")
        context.coordinator.bridge.install(on: config.userContentController)
        Hub.shared.acquireSources("screen")
        let web = WKWebView(frame: .zero, configuration: config)
        context.coordinator.bridge.web = web
        web.navigationDelegate = context.coordinator
        web.isOpaque = false
        web.backgroundColor = .black
        web.scrollView.isScrollEnabled = false
        web.scrollView.bounces = false
        if #available(iOS 16.4, *) { web.isInspectable = true }
        if let dir = Bundle.main.url(forResource: "ui", withExtension: nil) {
            web.loadFileURL(dir.appendingPathComponent("index.html"), allowingReadAccessTo: dir)
        } else {
            print("S1Dash: ui folder missing from the app")
        }
        context.coordinator.bridge.startPushing()
        return web
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
        let bridge = Bridge()

        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
            print("S1Dash: \(message.body)")
        }

        /// Fresh settings and readings go into the page before each load, since the UI reads them at start.
        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            if action.targetFrame?.isMainFrame != false { bridge.install(on: webView.configuration.userContentController) }
            decisionHandler(.allow)
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            webView.evaluateJavaScript("innerWidth + 'x' + innerHeight + ' screen=' + ((document.querySelector('.screen.on') || {}).id) + ' stage=' + document.getElementById('stage').style.width") { r, e in
                print("S1Dash: loaded \(r ?? e ?? "")")
            }
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            print("S1Dash: load failed \(error)")
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            print("S1Dash: load failed \(error)")
        }
    }
}
