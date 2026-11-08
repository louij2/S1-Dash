package com.louij2.s1dash

/** What the Android Auto lists show, kept free of Android so it can be unit tested. */
object CarRows {
    data class Row(val id: String, val title: String, val subtitle: String = "")

    val MODES = listOf("Stock", "FWD", "50 : 50", "60 : 40", "75 : 25", "Expert")
    private const val MODE_PREFIX = "mode:"

    fun modeId(mode: Int) = "$MODE_PREFIX$mode"
    fun modeFrom(id: String?): Int? =
        id?.takeIf { it.startsWith(MODE_PREFIX) }?.removePrefix(MODE_PREFIX)?.toIntOrNull()?.takeIf { it in MODES.indices }

    private fun value(label: String, x: Double?, decimals: Int, unit: String, note: String = ""): Row =
        Row("v:$label", label + " " + if (x == null) "--" else "%.${decimals}f%s".format(x, unit), note)

    fun live(get: (String) -> Double?, ecuStatus: String?): List<Row> {
        val out = ArrayList<Row>()
        val load = get("clutchLoad")
        if (get("clutchWarn") == 1.0) out += Row("w:clutch", "Clutch slipping", "%.0f %% slip".format(get("clutchSlip") ?: 0.0))
        else if (load != null && load >= ClutchModel.LOAD_HOT_PCT) out += Row("w:clutch", "Clutch hot", "Estimate")
        val oil = get("oil")
        if (oil != null && oil >= 130) out += Row("w:oil", "Oil hot", "%.0f °C".format(oil))
        val coolant = get("coolant")
        if (coolant != null && coolant >= 110) out += Row("w:coolant", "Coolant hot", "%.0f °C".format(coolant))
        out += value("Boost", get("boost"), 2, " bar")
        out += value("Oil", oil, 0, " °C")
        out += value("Coolant", coolant, 0, " °C")
        out += value("Haldex clutch", get("hxClutchTemp"), 0, " °C", get("hxLock")?.let { "Lock %.0f %%".format(it) } ?: "")
        out += value("Clutch load", load, 0, " %", "Estimate")
        out += value("Knock", get("knockAvg"), 2, "°")
        out += value("Engine", get("rpm"), 0, " rpm")
        out += Row("v:ecu", "ECU", ecuStatus ?: "--")
        return out
    }

    fun modes(current: Int?, haldexStatus: String?): List<Row> = MODES.mapIndexed { i, name ->
        Row(modeId(i), if (i == current) "$name · active" else name, if (i == 0) "OpenHaldex: ${haldexStatus ?: "--"}" else "")
    }
}
