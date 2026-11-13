import Foundation

/// Made-up but plausible readings for trying the screens without the car, the same
/// as the Android app's DemoSource: a pull every ~12 s on a slowly warming engine.
final class DemoSource {
    private var thread: Thread?

    func start() {
        let t = Thread { DemoSource.loop() }
        t.name = "demo"
        thread = t
        t.start()
    }

    func stop() { thread?.cancel() }

    private static func loop() {
        let hub = Hub.shared
        let t0 = Date()
        while !Thread.current.isCancelled {
            let t = Date().timeIntervalSince(t0)
            let pull = max(0, sin(t * 0.5))
            // Every third pull the clutch lets go, to show the slip warning.
            let slipping = Int(t / (4 * Double.pi)) % 3 == 2 && pull > 0.4
            let rpm = 1500 + pull * 4800
            hub.put("rpm", rpm)
            hub.put("ecuSpeed", rpm / 40)
            hub.put("gear", 3)
            hub.put("torque", 60 + pull * 310)
            hub.put("clutchSlip", slipping ? 8 : 0)
            hub.put("clutchLoad", 22 + pull * 72)
            hub.put("clutchWarn", slipping ? 1 : 0)
            hub.put("ambient", 101.3)
            hub.put("ambientBar", 1.013)
            hub.put("map", 101.3 + pull * 178 - 55 * (1 - pull))
            hub.put("mapSp", 101.3 + pull * 182)
            hub.put("boost", (pull * 178 - 55 * (1 - pull)) / 100)
            hub.put("coolant", min(90, 70 + t * 0.4))
            hub.put("oil", min(104, 62 + t * 0.25))
            hub.put("iat", 18 + pull * 9)
            for c in 1...4 { hub.put("knock\(c)", pull > 0.9 && c == 3 ? -1.5 : 0) }
            hub.put("knockAvg", pull > 0.9 ? -0.38 : 0)
            hub.put("timing", 8 + (1 - pull) * 14)
            hub.put("wastegate", 20 + pull * 70)
            hub.put("afr", 14.7 - pull * 2.9)
            hub.put("lambda", 1 - pull * 0.2)
            hub.put("lambdaSp", 1 - pull * 0.19)
            hub.put("lambdaDev", -pull * 0.01)
            hub.put("boostDev", -pull * 0.04)
            hub.put("ltft", 1.6)
            hub.put("stft", 0.8)
            hub.put("battery", 14.2)
            hub.put("hxMode", 2)
            hub.put("hxLock", 30 + pull * 60)
            hub.put("hxLockTarget", 30 + pull * 65)
            hub.put("hxClutchTemp", min(70, 30 + t * 0.15 + pull * 6))
            hub.put("hxModuleTemp", 24)
            hub.put("hxVolts", 13.9)
            hub.put("hxTempProt", 0)
            hub.put("dtcEcu", 2)
            hub.put("dtcHaldex", 0)
            Thread.sleep(forTimeInterval: 0.1)
        }
    }
}
