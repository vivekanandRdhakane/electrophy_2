package com.example.electrophy_app

/** AA58/v2 timing. IDs/counters are unsigned 32-bit; time is monotonic ns since boot. */
data class PacketTiming(
    val bootId: Long, val segmentId: Long, val firstSample: Long,
    val firstTimeNs: Long, val periodNs: Long, val flags: Int
)

data class TimedBatch(
    val timesNs: List<Long>, val indices: List<Long>, val plotTimesMs: List<Float>,
    val newSegment: Boolean, val gapSamples: Long, val discontinuity: Boolean
)

/** No phone clock or delivery time participates in this mapping. */
class AcquisitionTimeline {
    private var previous: PacketTiming? = null
    private var expectedIndex = 0L
    private var lastNs = -1L
    private var originNs = 0L
    private var mode = -1
    private var ranges = emptyList<Int>()

    @Synchronized fun reset() { previous = null; lastNs = -1; mode = -1; ranges = emptyList() }

    /** Null rejects duplicate/reordered data or an unannounced clock/config change. */
    @Synchronized fun accept(packet: DecodedPacket): TimedBatch? {
        val t = packet.timing ?: return null
        val old = previous
        val segment = old == null || old.bootId != t.bootId || old.segmentId != t.segmentId
        val configuration = listOf(packet.xlFs, packet.hgFs, packet.gyFs)
        if (!segment && (mode != packet.mode || ranges != configuration || old!!.periodNs != t.periodNs)) return null
        if (old != null && old.bootId == t.bootId && old.segmentId != t.segmentId &&
            ((t.segmentId - old.segmentId) and 0xffff_ffffL) >= 0x8000_0000L) return null
        // First observed packet establishes the boundary. Attaching/Clear midway
        // through a segment must not count all earlier samples as transport loss.
        val gap = if (segment) 0L else (t.firstSample - expectedIndex) and 0xffff_ffffL
        if (!segment && (gap >= 0x8000_0000L || t.firstTimeNs <= lastNs)) return null
        // A continuous ID sequence must also have a continuous device timeline.
        if (!segment) {
            val expectedNs = lastNs + (gap + 1) * t.periodNs
            if (t.firstTimeNs != expectedNs) return null
        }
        if (segment) originNs = t.firstTimeNs
        val ns = List(packet.sampleCount) { t.firstTimeNs + it * t.periodNs }
        val indices = List(packet.sampleCount) { (t.firstSample + it) and 0xffff_ffffL }
        previous = t; mode = packet.mode; ranges = configuration
        expectedIndex = (t.firstSample + packet.sampleCount) and 0xffff_ffffL
        lastNs = ns.last()
        return TimedBatch(ns, indices, ns.map { ((it - originNs) / 1_000_000.0).toFloat() },
            segment, gap, segment && old != null || gap > 0 || segment && t.flags and 2 != 0)
    }
}
