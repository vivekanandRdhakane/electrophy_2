package com.example.electrophy_app

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Firmware diagnostic v1. Counters are unsigned, cumulative since boot. */
data class FirmwareDiagnostics(
    val mode: Int, val uptimeMs: Long,
    val acquired: Long, val submitted: Long, val overflow: Long, val discarded: Long,
    val coalesced: Long, val timeouts: Long, val readErrors: Long, val retries: Long,
    val notifyErrors: Long, val bytes: Long, val packets: Long, val pendingSamples: Long,
    val queueDepth: Int, val queuePeak: Int, val freeMbufs: Int, val mtu: Int,
    val intervalUnits: Int, val txOctets: Int, val rssi: Int,
    val txPhy: Int, val rxPhy: Int, val paused: Boolean
)

object StreamDiagnosticsDecoder {
    fun isDiagnostic(bytes: ByteArray) = bytes.size >= 2 &&
        bytes[0] == 0xaa.toByte() && bytes[1] == 0x56.toByte()

    fun decode(bytes: ByteArray): FirmwareDiagnostics? {
        if (!isDiagnostic(bytes) || bytes.size != 72 || bytes[2] != 1.toByte()) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val mode = bytes[3].toInt() and 255
        if (mode !in 0..4) return null
        b.position(4)
        fun u32() = b.int.toLong() and 0xffff_ffffL
        fun u16() = b.short.toInt() and 65535
        return FirmwareDiagnostics(
            mode, u32(), u32(), u32(), u32(), u32(), u32(), u32(), u32(),
            u32(), u32(), u32(), u32(), u32(), u16(), u16(), u16(), u16(),
            u16(), u16(), b.get().toInt(), b.get().toInt() and 255,
            b.get().toInt() and 255, b.get().toInt() and 1 != 0
        )
    }
}

data class FirmwareRates(val acquired: Long, val submitted: Long, val bytes: Long)

class FirmwareRateTracker {
    private var previous: FirmwareDiagnostics? = null
    @Synchronized fun reset() { previous = null }
    @Synchronized fun update(frame: FirmwareDiagnostics): FirmwareRates? {
        val old = previous
        previous = frame
        if (old == null || old.mode != frame.mode || old.paused != frame.paused) return null
        fun delta(a: Long, b: Long) = (a - b) and 0xffff_ffffL
        val elapsed = delta(frame.uptimeMs, old.uptimeMs)
        // An apparent backwards jump is a reboot/stale frame, not counter wrap.
        if (elapsed == 0L || elapsed > 60_000) return null
        return FirmwareRates(
            delta(frame.acquired, old.acquired) * 1000 / elapsed,
            delta(frame.submitted, old.submitted) * 1000 / elapsed,
            delta(frame.bytes, old.bytes) * 1000 / elapsed
        )
    }
}

data class ReceiveMetrics(val samplesPerSecond: Int, val packetsPerSecond: Int,
                          val sequenceGaps: Long, val invalidPackets: Long)

/** Count at decode time, independently of chart refresh or recording. */
class ReceiveRateMeter {
    private var startedMs = 0L
    private var samples = 0L
    private var packets = 0L
    private var lastSequence: Int? = null
    private var gaps = 0L
    private var invalid = 0L

    @Synchronized fun reset(nowMs: Long) {
        startedMs = nowMs
        samples = 0; packets = 0; gaps = 0; invalid = 0; lastSequence = null
    }
    @Synchronized fun record(count: Int, sequence: Int? = null) {
        if (count <= 0) return
        samples += count
        packets++
        if (sequence != null) {
            lastSequence?.let { if (sequence != it) gaps += (sequence - it - 1) and 255 }
            lastSequence = sequence
        }
    }
    @Synchronized fun invalidPacket() { invalid++ }
    @Synchronized fun snapshot(nowMs: Long): ReceiveMetrics {
        val elapsed = (nowMs - startedMs).coerceAtLeast(1)
        val result = ReceiveMetrics((samples * 1000 / elapsed).toInt(),
            (packets * 1000 / elapsed).toInt(), gaps, invalid)
        startedMs = nowMs; samples = 0; packets = 0
        return result
    }
}
