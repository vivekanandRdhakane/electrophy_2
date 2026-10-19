package com.example.electrophy_app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class StreamDiagnosticsTest {
    private fun frame(): ByteArray {
        val b = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0xaa.toByte()).put(0x56).put(1).put(1)
        // uptime, acquired, submitted, overflow, discarded, coalesced, timeouts,
        // read errors, retries, notify errors, bytes, packets, pending
        listOf(2000, 15360, 15000, 111, 0, 17, 2, 0, 3, 0, 68000, 140, 249)
            .forEach { b.putInt(it) }
        listOf(3, 6, 42, 517, 6, 251).forEach { b.putShort(it.toShort()) }
        b.put((-63).toByte()).put(2).put(2).put(0)
        return b.array()
    }

    @Test fun decodesFirmwareLayoutAndSignedRssi() {
        val d = StreamDiagnosticsDecoder.decode(frame())!!
        assertEquals(15360L, d.acquired)
        assertEquals(249L, d.pendingSamples)
        assertEquals(517, d.mtu)
        assertEquals(251, d.txOctets)
        assertEquals(-63, d.rssi)
        assertEquals(2, d.rxPhy)
        assertFalse(d.paused)
    }

    @Test fun rejectsTruncatedUnknownAndSamplePackets() {
        assertNull(StreamDiagnosticsDecoder.decode(frame().copyOf(71)))
        assertNull(StreamDiagnosticsDecoder.decode(frame().also { it[2] = 2 }))
        assertNull(StreamDiagnosticsDecoder.decode(frame().also { it[1] = 0x55 }))
        assertNull(StreamDiagnosticsDecoder.decode(frame().also { it[3] = 5 }))
    }

    @Test fun ratesUseFirmwareTimeAndResetOnModeOrPauseChange() {
        val tracker = FirmwareRateTracker()
        val d = StreamDiagnosticsDecoder.decode(frame())!!
        assertNull(tracker.update(d))
        val next = d.copy(uptimeMs = 4000, acquired = d.acquired + 15360,
            submitted = d.submitted + 14600, bytes = d.bytes + 66000)
        assertEquals(FirmwareRates(7680, 7300, 33000), tracker.update(next))
        assertNull(tracker.update(next.copy(mode = 2)))
        assertNull(tracker.update(next.copy(mode = 2, paused = true)))
        assertNull(tracker.update(d.copy(uptimeMs = 100))) // reboot
    }

    @Test fun handlesUnsignedCounterAndClockWrap() {
        val d = StreamDiagnosticsDecoder.decode(frame())!!
        val tracker = FirmwareRateTracker()
        tracker.update(d.copy(uptimeMs = 0xfffffff0, acquired = 0xfffffff0,
            submitted = 0xfffffff0, bytes = 0xfffffff0))
        assertEquals(FirmwareRates(1000, 1000, 1000),
            tracker.update(d.copy(uptimeMs = 1984, acquired = 1984, submitted = 1984, bytes = 1984)))
    }

    @Test fun receiveRateCountsAllSamplesWrapsSequenceAndGoesToZeroWhenIdle() {
        val meter = ReceiveRateMeter()
        meter.reset(100)
        meter.record(100, 254); meter.record(100, 255); meter.record(100, 0)
        meter.record(100, 2); meter.invalidPacket()
        val active = meter.snapshot(2100)
        assertEquals(200, active.samplesPerSecond)
        assertEquals(1L, active.sequenceGaps)
        assertEquals(1L, active.invalidPackets)
        assertEquals(0, meter.snapshot(4100).samplesPerSecond)
        meter.reset(5000)
        meter.record(100, 40)
        assertEquals(0L, meter.snapshot(7000).sequenceGaps)
    }
}
