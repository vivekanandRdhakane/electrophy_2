package com.example.electrophy_app

import org.junit.Assert.*
import org.junit.Test

class BatteryStatusTest {
    @Test fun decodesLittleEndianVoltage() {
        val status = BatteryStatusDecoder.decode(byteArrayOf(0xAA.toByte(), 0x57, 1, 0x06, 0x0F))
        assertEquals(BatteryStatus(3846, 49), status)
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

    @Test fun statusDoesNotShowOldBatteryWhenDisconnected() {
        assertEquals("Disconnected", connectionStatusLabel(ConnectionState.Disconnected, BatteryStatus(4200, 100)))
        assertEquals("Connected · --%", connectionStatusLabel(ConnectionState.Connected, null))
        assertEquals("Connected · 100%", connectionStatusLabel(ConnectionState.Connected, BatteryStatus(4200, 100)))
    }
}
