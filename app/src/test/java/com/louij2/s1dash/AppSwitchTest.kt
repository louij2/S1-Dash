package com.louij2.s1dash

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
}
