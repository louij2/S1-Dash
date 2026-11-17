import CoreMotion
import UIKit

/// The iPhone's motion sensor as gLat / gLon in the hub, like the Android GSensor: lateral
/// (+ = pushed right, so a left turn) and longitudinal (+ = pushed forward, so braking).
/// CoreMotion already separates gravity from the phone's own acceleration, so any mount tilt
/// works; the screen's left-right edge is taken as across the car.
final class GMotion {
    private let manager = CMMotionManager()

    func start() {
        guard manager.isDeviceMotionAvailable else {
            Hub.shared.setStatus("g", "no motion sensor")
            return
        }
        Hub.shared.setStatus("g", "learning")
        manager.deviceMotionUpdateInterval = 0.05
        manager.startDeviceMotionUpdates(to: .main) { motion, _ in
            guard let m = motion, let g = GMotion.felt(m, GMotion.screenRight()) else { return }
            Hub.shared.setStatus("g", "ok")
            Hub.shared.put("gLat", g.0)
            Hub.shared.put("gLon", g.1)
        }
    }

    func stop() { manager.stopDeviceMotionUpdates() }

    /// The screen's rightward edge in device axes for the current interface orientation.
    private static func screenRight() -> SIMD3<Double> {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        switch scene?.interfaceOrientation {
        case .landscapeLeft: return SIMD3(0, 1, 0)       // device turned clockwise, top to the right
        case .landscapeRight: return SIMD3(0, -1, 0)     // device turned anticlockwise, top to the left
        case .portraitUpsideDown: return SIMD3(-1, 0, 0)
        default: return SIMD3(1, 0, 0)
        }
    }

    private static func felt(_ m: CMDeviceMotion, _ r0: SIMD3<Double>) -> (Double, Double)? {
        let up = -SIMD3(m.gravity.x, m.gravity.y, m.gravity.z)
        let ul = (up * up).sum().squareRoot()
        guard ul > 0.1 else { return nil }
        let u = up / ul
        var right = r0 - u * (r0 * u).sum()
        let rl = (right * right).sum().squareRoot()
        guard rl > 0.01 else { return nil }  // screen edge pointing up: no across-car axis
        right /= rl
        let forward = SIMD3(u.y * right.z - u.z * right.y, u.z * right.x - u.x * right.z, u.x * right.y - u.y * right.x)
        let felt = -SIMD3(m.userAcceleration.x, m.userAcceleration.y, m.userAcceleration.z) // already in g
        return ((felt * right).sum(), (felt * forward).sum())
    }
}
