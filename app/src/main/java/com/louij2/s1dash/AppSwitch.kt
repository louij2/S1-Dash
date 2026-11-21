package com.louij2.s1dash

/** Helpers for the S1 Dash <-> CarPlay switch (#1060). */
object AppSwitch {
    private val HINTS = listOf("carplay", "car play", "zlink", "autokit", "carlink", "carbit", "tlink", "boxtohome", "phonelink", "easyconnect", "cplay")

    /** Whether an app's package or name looks like the one that shows CarPlay, to list it first in the picker. */
    fun looksLikeCarPlay(pkg: String, name: String): Boolean {
        val s = (pkg + " " + name).lowercase()
        return HINTS.any { it in s }
    }
}
