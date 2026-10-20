package com.example.electrophy_app

data class BatteryStatus(val millivolts: Int, val percent: Int)

object BatteryStatusDecoder {
    fun isBatteryFrame(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0xAA.toByte() && bytes[1] == 0x57.toByte()

    fun decode(bytes: ByteArray): BatteryStatus? {
        if (!isBatteryFrame(bytes) || bytes.size != 5 || bytes[2] != 1.toByte()) return null
        val mv = (bytes[3].toInt() and 0xFF) or ((bytes[4].toInt() and 0xFF) shl 8)
        // Zero is the firmware's unavailable sentinel; reject implausible values.
        if (mv !in 2000..4500) return null
        return BatteryStatus(mv, percentage(mv))
    }

    // Empirical remaining-runtime estimate from the user's 48 min 15 s run.
    // Voltages were inferred by inverting the previous percentage curve;
    // they were not measured directly. Target = 100 * (1 - minutes / 48.25).
    // Repeated old percentages use their mean observation time. Keep 3300 mV
    // at 0% for firmware cutoff and clamp >=4020 mV (old 78%) to 100%.
    // This calibration assumes similar load/cell conditions. See BATTERY_CALIBRATION.md.
    private val curve = listOf(
        3300 to 0, 3340 to 3, 3380 to 6, 3420 to 11, 3460 to 18,
        3500 to 24, 3520 to 30, 3540 to 35, 3560 to 38, 3580 to 41,
        3600 to 44, 3610 to 46, 3620 to 48, 3630 to 51, 3650 to 54,
        3660 to 56, 3680 to 59, 3690 to 61, 3700 to 63, 3720 to 65,
        3725 to 67, 3730 to 69, 3765 to 71, 3775 to 73, 3790 to 75,
        3830 to 79, 3865 to 83, 3885 to 85, 3895 to 88, 3913 to 90,
        3933 to 92, 3953 to 94, 3973 to 96, 4020 to 100
    )

    fun percentage(mv: Int): Int {
        if (mv <= curve.first().first) return 0
        for (i in 1 until curve.size) {
            val (highMv, highPercent) = curve[i]
            val (lowMv, lowPercent) = curve[i - 1]
            if (mv <= highMv) {
                val span = highMv - lowMv
                return lowPercent + ((mv - lowMv) * (highPercent - lowPercent) + span / 2) / span
            }
        }
        return 100
    }
}

fun connectionStatusLabel(state: ConnectionState, battery: BatteryStatus?): String =
    if (state == ConnectionState.Connected) {
        battery?.let { "Connected · ${it.percent}%" } ?: "Connected · --%"
    } else state.name
