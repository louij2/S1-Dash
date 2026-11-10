import SwiftUI
import WebKit

/// The dash screens are the Android app's web UI. With no native bridge yet,
/// the UI's built-in demo data runs, so this build shows made-up readings.
@main
struct S1DashApp: App {
    var body: some Scene {
        WindowGroup {
            DashView()
                .background(Color.black)
                .statusBarHidden(true)
                .persistentSystemOverlays(.hidden)
        }
    }
}

struct DashView: UIViewRepresentable {
    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        // Script errors and a layout check go to the app's log, since there's no console on the phone.
        config.userContentController.add(context.coordinator, name: "log")
        config.userContentController.addUserScript(WKUserScript(source: """
            window.onerror = function (m, s, l) { window.webkit.messageHandlers.log.postMessage("error: " + m + " at " + s + ":" + l); };
            """, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        let web = WKWebView(frame: .zero, configuration: config)
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
        return web
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
            print("S1Dash: \(message.body)")
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
