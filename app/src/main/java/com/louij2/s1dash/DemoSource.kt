package com.louij2.s1dash

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Made-up but plausible readings, for trying the screens without the car. */
class DemoSource {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({ loop() }, "demo").apply { start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
    }

    private fun loop() {
        val t0 = System.currentTimeMillis()
        while (running) {
            val t = (System.currentTimeMillis() - t0) / 1000.0
            // A pull every ~12 s on a slowly warming engine.
            val pull = max(0.0, sin(t * 0.5))
            val oil = min(104.0, 62.0 + t * 0.25)
            DataHub.put("rpm", 1500 + pull * 4800)
            DataHub.put("ambient", 101.3)
            DataHub.put("ambientBar", 1.013)
            DataHub.put("map", 101.3 + pull * 178 - 55 * (1 - pull))
            DataHub.put("mapSp", 101.3 + pull * 182)
            DataHub.put("boost", (pull * 178 - 55 * (1 - pull)) / 100.0)
            DataHub.put("coolant", min(90.0, 70.0 + t * 0.4))
            DataHub.put("oil", oil)
            DataHub.put("iat", 18 + pull * 9)
            for (c in 1..4) DataHub.put("knock$c", if (pull > 0.9 && c == 3) -1.5 else 0.0)
            DataHub.put("knockAvg", if (pull > 0.9) -0.38 else 0.0)
            DataHub.put("timing", 8 + (1 - pull) * 14)
            DataHub.put("wastegate", 20 + pull * 70)
            DataHub.put("afr", 14.7 - pull * 2.9)
            DataHub.put("lambda", 1.0 - pull * 0.2)
            DataHub.put("ltft", 1.6)
            DataHub.put("stft", 0.8)
            DataHub.put("battery", 14.2)
            DataHub.put("hxMode", 2.0)
            DataHub.put("hxLock", 30 + pull * 60)
            DataHub.put("hxLockTarget", 30 + pull * 65)
            DataHub.put("hxClutchTemp", min(70.0, 30 + t * 0.15 + pull * 6))
            DataHub.put("hxModuleTemp", 24.0)
            DataHub.put("hxVolts", 13.9)
            DataHub.put("hxTempProt", 0.0)
            DataHub.put("dtcEcu", 2.0)
            DataHub.put("dtcHaldex", 0.0)
            try { Thread.sleep(100) } catch (_: InterruptedException) { break }
        }
    }
}
