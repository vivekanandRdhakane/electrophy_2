package com.example.electrophy_app

import org.junit.Assert.*
import org.junit.Test

class BatteryStatusTest {
    @Test fun decodesLittleEndianVoltage() {
        val status = BatteryStatusDecoder.decode(byteArrayOf(0xAA.toByte(), 0x57, 1, 0x06, 0x0F))
        assertEquals(BatteryStatus(3846, 81), status)
    }

    @Test fun rejectsInvalidFramesAndUnavailableReading() {
        for (frame in listOf(
            byteArrayOf(), byteArrayOf(0xAA.toByte(), 0x57),
            byteArrayOf(0xAA.toByte(), 0x57, 2, 0x68, 0x10),
            byteArrayOf(0xAA.toByte(), 0x55, 1, 0x68, 0x10),
            byteArrayOf(0xAA.toByte(), 0x57, 1, 0, 0),
            byteArrayOf(0xAA.toByte(), 0x57, 1, -1, -1)
        )) assertNull(BatteryStatusDecoder.decode(frame))
    }

    @Test fun percentageIsBoundedAndMonotonic() {
        assertEquals(0, BatteryStatusDecoder.percentage(3300))
        assertEquals(100, BatteryStatusDecoder.percentage(4200))
        var previous = 0
        for (mv in 2000..4500) {
            val percent = BatteryStatusDecoder.percentage(mv)
            assertTrue(percent in previous..100)
            previous = percent
        }
    }

    @Test fun fitsMeasuredRuntimeMilestones() {
        // Voltages inferred from the OLD mapping, paired with elapsed minutes.
        val milestones = listOf(
            4020 to 0.0, 3830 to 10.0, 3680 to 20.0,
            3560 to 30.0, 3340 to 47.0, 3300 to 48.0
        )
        for ((mv, minutes) in milestones) {
            val remaining = 100.0 * (1.0 - minutes / 48.25)
            assertEquals("At $minutes minutes", remaining,
                BatteryStatusDecoder.percentage(mv).toDouble(), 1.0)
        }
        assertEquals(100, BatteryStatusDecoder.percentage(4020))
        assertEquals(100, BatteryStatusDecoder.percentage(4300))
    }

    @Test fun repeatedReadingsUseMidpointTimeAndInterpolate() {
        // Old 4% was recorded at minutes 38, 39, 40, 41: midpoint 39.5.
        assertEquals(18, BatteryStatusDecoder.percentage(3460))
        // Halfway between calibrated 3380 mV / 6% and 3420 mV / 11%.
        assertEquals(9, BatteryStatusDecoder.percentage(3400))
    }

    @Test fun statusDoesNotShowOldBatteryWhenDisconnected() {
        assertEquals("Disconnected", connectionStatusLabel(ConnectionState.Disconnected, BatteryStatus(4200, 100)))
        assertEquals("Connected · --%", connectionStatusLabel(ConnectionState.Connected, null))
        assertEquals("Connected · 100%", connectionStatusLabel(ConnectionState.Connected, BatteryStatus(4200, 100)))
    }
}
