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

    // Approximate standard 4.2 V single-cell LiPo curve, relative to the
    // firmware's 3.3 V cutoff. Capacity alone cannot determine state of charge.
    private val curve = listOf(
        3300 to 0, 3500 to 5, 3600 to 10, 3700 to 20, 3750 to 30,
        3800 to 40, 3850 to 50, 3900 to 60, 4000 to 75, 4100 to 90, 4200 to 100
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
