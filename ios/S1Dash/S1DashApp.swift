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
    func makeUIView(context: Context) -> WKWebView {
        let web = WKWebView(frame: .zero, configuration: WKWebViewConfiguration())
        web.isOpaque = false
        web.backgroundColor = .black
        web.scrollView.isScrollEnabled = false
        web.scrollView.bounces = false
        if let dir = Bundle.main.url(forResource: "ui", withExtension: nil) {
            web.loadFileURL(dir.appendingPathComponent("index.html"), allowingReadAccessTo: dir)
        }
        return web
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
