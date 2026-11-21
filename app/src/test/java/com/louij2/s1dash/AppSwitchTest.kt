package com.louij2.s1dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSwitchTest {
    @Test fun carPlayAppsAreRecognised() {
        assertTrue(AppSwitch.looksLikeCarPlay("com.zjinnova.zlink", "ZLink"))
        assertTrue(AppSwitch.looksLikeCarPlay("com.autokit.app", "AutoKit"))
        assertTrue(AppSwitch.looksLikeCarPlay("cn.manstep.phonemirrorBox", "CarPlay"))
        assertFalse(AppSwitch.looksLikeCarPlay("com.app.simostools", "Simos Tools"))
        assertFalse(AppSwitch.looksLikeCarPlay("com.android.chrome", "Chrome"))
    }

    @Test fun theLikeliestScreenOfASystemAppIsChosen() {
        assertEquals("com.x.CarPlayActivity", AppSwitch.bestActivity(listOf("com.x.SettingsActivity", "com.x.CarPlayActivity", "com.x.MainActivity")))
        assertEquals("com.x.MainActivity", AppSwitch.bestActivity(listOf("com.x.AboutActivity", "com.x.MainActivity")))
        assertEquals("com.x.AboutActivity", AppSwitch.bestActivity(listOf("com.x.AboutActivity")))
        assertEquals(null, AppSwitch.bestActivity(emptyList()))
    }
}
