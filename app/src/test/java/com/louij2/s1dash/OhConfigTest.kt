package com.louij2.s1dash

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OhConfigTest {
    /** Shaped like GET /api/settings on firmware 11.10. */
    private fun live(gen: Int = 51, tweak: (JSONObject) -> Unit = {}) = JSONObject()
        .put("haldexGeneration", gen).put("FW_VERSION", "11.10.0").put("mode", 2)
        .put("isStandalone", false).put("disableController", false).put("liveDiagEnabled", true)
        .put("tcForceMode", true).put("tcForceModeValue", 2).put("lockReleaseRatePerSec", 120.0)
        .put("disengageUnderSpeed", 0).put("ledBrightness", 40).put("bpkCeilingNm", 220)
        .put("blePasskey", 482913).put("analyzerMode", false)
        .put("throttleArray", JSONArray(listOf(0, 10, 40, 80))).put("speedArray", JSONArray(listOf(0, 40, 120)))
        .put("lockArray", JSONArray().put(JSONArray(listOf(0, 0, 0))).put(JSONArray(listOf(20, 30, 10))).put(JSONArray(listOf(60, 50, 20))).put(JSONArray(listOf(90, 70, 30))))
        .put("frameBlocks", JSONArray().put(JSONObject().put("bit", 0).put("name", "Motor_14").put("canId", 0x3BE).put("enabled", true))
            .put(JSONObject().put("bit", 1).put("name", "ESP_07").put("canId", 0x1A6).put("enabled", false)))
        .also(tweak)

    @Test fun sameConfigIsSame() {
        assertEquals(OhConfig.Verdict.SAME, OhConfig.verdict(OhConfig.changes(live(), live())))
    }

    @Test fun modeAndStatusArentConfig() {
        // The mode changes on every tap from S1 Dash; it isn't a settings change.
        val ch = OhConfig.changes(live(), live { it.put("mode", 4).put("analyzerMode", true).put("FW_VERSION", "11.11.0") })
        assertTrue(ch.toString(), ch.isEmpty())
    }

    @Test fun wholeNumbersCompareTheSameWrittenEitherWay() {
        val ch = OhConfig.changes(live { it.put("lockReleaseRatePerSec", 120) }, live { it.put("lockReleaseRatePerSec", 120.0) })
        assertTrue(ch.isEmpty())
    }

    @Test fun oneSettingChangedIsSmall() {
        val ch = OhConfig.changes(live(), live { it.put("ledBrightness", 80) })
        assertEquals(listOf("ledBrightness"), ch.map { it.key })
        assertEquals(OhConfig.Verdict.SMALL, OhConfig.verdict(ch))
    }

    @Test fun factoryResetIsSuspect() {
        // A reset puts the generation back to 1 and most settings to their defaults.
        val reset = live(OhConfig.FACTORY_GENERATION) {
            it.put("tcForceMode", false).put("liveDiagEnabled", false).put("ledBrightness", 128)
            it.put("lockArray", JSONArray().put(JSONArray(listOf(0, 0, 0))).put(JSONArray(listOf(0, 0, 0))).put(JSONArray(listOf(0, 0, 0))).put(JSONArray(listOf(0, 0, 0))))
        }
        val ch = OhConfig.changes(live(), reset)
        assertEquals(OhConfig.Verdict.SUSPECT, OhConfig.verdict(ch))
        assertTrue(OhConfig.describe(ch), OhConfig.describe(ch).startsWith("haldexGeneration 1 (was 51)"))
    }

    @Test fun manyChangesAtOnceAreSuspectEvenOnTheSameGeneration() {
        val ch = OhConfig.changes(live(), live {
            it.put("isStandalone", true).put("disableController", true).put("liveDiagEnabled", false)
                .put("tcForceMode", false).put("ledBrightness", 1).put("bpkCeilingNm", 100)
        })
        assertEquals(OhConfig.Verdict.SUSPECT, OhConfig.verdict(ch))
    }

    @Test fun frameEditFlipIsAChange() {
        val ch = OhConfig.changes(live(), live { it.getJSONArray("frameBlocks").getJSONObject(1).put("enabled", true) })
        assertEquals(listOf("frameEdit 1 ESP_07"), ch.map { it.key })
    }

    @Test fun newFirmwareSettingsArentChanges() {
        val old = live().also { it.remove("bpkCeilingNm") }
        assertTrue(OhConfig.changes(old, live()).isEmpty())
    }

    @Test fun backupMatchesTheWebPageExportAndHasNoPasskey() {
        val b = OhConfig.backup(live(), "OpenHaldex-C6", true, "Tbox", true, "2026-11-27T10:00:00Z", "head unit")
        assertEquals("OpenHaldex-C6", b.getString("_product"))
        assertEquals("11.10.0", b.getString("_fwVersion"))
        assertFalse(b.toString().contains("blePasskey"))
        assertFalse(b.toString().contains("482913"))
        assertEquals("Tbox", b.getJSONObject("wifiSta").getString("ssid"))
        assertTrue(OhConfig.valid(b))
        // Round trip through text, as on disk.
        assertTrue(OhConfig.changes(JSONObject(b.toString()).getJSONObject("settings"), live()).isEmpty())
    }

    @Test fun notABackupIsRefused() {
        assertFalse(OhConfig.valid(JSONObject().put("_product", "other").put("settings", live())))
        assertFalse(OhConfig.valid(JSONObject().put("_product", "OpenHaldex-C6").put("settings", JSONObject())))
        assertFalse(OhConfig.valid(null))
    }

    @Test fun restoreGoesInTheWebPageOrder() {
        val b = OhConfig.backup(live(), "OpenHaldex-C6", true, "Tbox", true, "t", "d")
        val steps = OhConfig.restoreSteps(b)
        assertEquals("""{"haldexGeneration":51}""", steps[0].second)
        assertEquals("/api/tune", steps[1].first)
        assertTrue(JSONObject(steps[1].second).has("lockArray"))
        val general = JSONObject(steps[2].second)
        assertEquals(true, general.getBoolean("tcForceMode"))
        assertFalse("not a setting", general.has("mode"))
        assertFalse(general.has("blePasskey"))
        assertEquals(listOf("""{"frameEditBit":0,"frameEditOn":true}""", """{"frameEditBit":1,"frameEditOn":false}"""),
            steps.drop(3).map { it.second })
    }

    @Test fun wifiOnlyWhereItDiffers() {
        val b = OhConfig.backup(live(), "OpenHaldex-C6", true, "Tbox", true, "t", "d")
        assertTrue(OhConfig.wifiSteps(b, "OpenHaldex-C6", "Tbox", "", "").isEmpty())
        // After a reset the bridge name is gone: put it back with the password typed in.
        val after = OhConfig.wifiSteps(b, "OpenHaldex-C6", "", "", "hotspotpw")
        assertEquals(listOf("/api/wifi/sta"), after.map { it.first })
        assertEquals("hotspotpw", JSONObject(after[0].second).getString("password"))
        val ap = OhConfig.wifiSteps(b, "OpenHaldex-reset", "Tbox", "appassword", "")
        assertEquals(listOf("/api/wifi/ssid", "/api/wifi"), ap.map { it.first })
    }

    @Test fun backupWithoutBridgeDoesntTurnBridgeOff() {
        val b = OhConfig.backup(live(), "OpenHaldex-C6", true, null, false, "t", "d")
        assertTrue(OhConfig.wifiSteps(b, "OpenHaldex-C6", "Tbox", "", "").isEmpty())
    }
}
