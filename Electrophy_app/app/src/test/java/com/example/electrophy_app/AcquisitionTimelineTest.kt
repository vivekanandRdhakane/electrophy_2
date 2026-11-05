package com.example.electrophy_app

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AcquisitionTimelineTest {
    private fun frame(first: Long = 0, ns: Long = 1_000_000_000L, count: Int = 3,
                      boot: Int = 7, segment: Int = 2, period: Int = 130208): ByteArray {
        val b = ByteBuffer.allocate(36 + count * 6).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0xaa.toByte()).put(0x58).put(1).put(count.toByte())
        b.put(0).put(0).put(1).put(0).put(0) // low-g, RAW16
        b.put(2).put(1).put(36)
        b.putInt(boot).putInt(segment).putInt(first.toInt()).putLong(ns).putInt(period)
        repeat(count) { b.putShort(100).putShort(-100).putShort(0) }
        return b.array()
    }
    private fun packet(bytes: ByteArray) = (PacketDecoder.decode(bytes) as DecodeResult.Success).packet

    @Test fun wireLayoutAndScalingAreDecoded() {
        assertFalse(BatteryStatusDecoder.isBatteryFrame(frame()))
        assertFalse(StreamDiagnosticsDecoder.isDiagnostic(frame()))
        val p = packet(frame())
        assertEquals(36 + 18, frame().size)
        assertEquals(7L, p.timing!!.bootId)
        assertEquals(1_000_000_000L, p.timing.firstTimeNs)
        assertEquals(130208L, p.timing.periodNs)
        assertEquals(6.1f, p.lowG[0].x, 0.0001f)
        assertEquals(-6.1f, p.lowG[0].y, 0.0001f)
    }

    @Test fun deliveryJitterAndBatchSizesCannotChangeAcquisitionTimes() {
        val batched = AcquisitionTimeline()
        val split = AcquisitionTimeline()
        val expected = listOf(1_000_000_000L, 1_000_130_208L, 1_000_260_416L)
        assertEquals(expected, batched.accept(packet(frame()))!!.timesNs)
        val actual = (0..2).flatMap { i ->
            // No delivery clock argument exists: delayed callbacks cannot alter these times.
            split.accept(packet(frame(i.toLong(), expected[i], 1)))!!.timesNs
        }
        assertEquals(expected, actual)
    }

    @Test fun missingPacketPreservesGapWithoutStretchingNextBatch() {
        val clock = AcquisitionTimeline()
        clock.accept(packet(frame()))
        val next = clock.accept(packet(frame(6, 1_000_781_248L)))!!
        assertEquals(3L, next.gapSamples)
        assertTrue(next.discontinuity)
        assertEquals(130208L, next.timesNs[1] - next.timesNs[0])
        assertEquals(0.781248f, next.plotTimesMs.first(), 0.000001f)
    }

    @Test fun counterWrapAndLongUptimeRetainNanoseconds() {
        val clock = AcquisitionTimeline()
        val start = 900_000_000_000_000L
        clock.accept(packet(frame(0xffff_fffeL, start, 2)))
        val next = clock.accept(packet(frame(0, start + 260416, 2)))!!
        assertEquals(0L, next.gapSamples)
        assertEquals(start + 260416, next.timesNs.first())
        assertEquals(0.260416f, next.plotTimesMs.first(), 0.000001f)
    }

    @Test fun duplicateReorderAndUnannouncedChangesAreRejected() {
        val c = AcquisitionTimeline()
        val p = packet(frame())
        assertNotNull(c.accept(p))
        assertNull(c.accept(p))
        assertNull(c.accept(packet(frame(1, 1_000_130_208L))))
        assertNull(c.accept(packet(frame(3, 1_000_390_624L, period = 200000))))
        assertNull(c.accept(packet(frame(3, 1_000_390_625L))))
        assertNotNull(c.accept(packet(frame(3, 1_000_390_624L))))
    }

    @Test fun pauseConfigurationReconnectAndClearEstablishNewOrigins() {
        val c = AcquisitionTimeline()
        c.accept(packet(frame()))
        val resumed = c.accept(packet(frame(ns = 50_000_000_000L, segment = 3)))!!
        assertTrue(resumed.newSegment)
        assertTrue(resumed.discontinuity)
        assertEquals(0f, resumed.plotTimesMs.first())
        assertNull(c.accept(packet(frame(segment = 2)))) // old segment
        assertTrue(c.accept(packet(frame(boot = 8)))!!.newSegment)
        c.reset()
        assertEquals(0f, c.accept(packet(frame()))!!.plotTimesMs.first())
    }

    @Test fun malformedVersionsLengthsAndOverflowCannotEmitSamples() {
        val valid = frame()
        for (size in 0 until valid.size) assertTrue(PacketDecoder.decode(valid.copyOf(size)) is DecodeResult.Error)
        for (offset in listOf(9, 10, 11)) {
            val bad = valid.copyOf(); bad[offset] = 127
            assertTrue(PacketDecoder.decode(bad) is DecodeResult.Error)
        }
        assertTrue(PacketDecoder.decode(frame(period = 0)) is DecodeResult.Error)
        assertTrue(PacketDecoder.decode(frame(ns = Long.MAX_VALUE)) is DecodeResult.Error)
        assertTrue(PacketDecoder.decode(valid + byteArrayOf(0)) is DecodeResult.Error)
        val combined = valid.copyOf(); combined[2] = 0
        assertTrue(PacketDecoder.decode(combined) is DecodeResult.Error)
    }

    @Test fun legacyPacketsRemainExplicitlyUntimed() {
        val v2 = frame()
        val legacy = v2.copyOfRange(0, 9) + v2.copyOfRange(36, v2.size)
        legacy[1] = 0x55
        assertNull(packet(legacy).timing)
    }

    @Test fun packed12V2UsesExtendedHeaderAndSignedOddPayload() {
        val bytes = frame(count = 1).copyOf(41)
        bytes[8] = 1
        byteArrayOf(0x12, 0x3f, 0xff.toByte(), 0x80.toByte(), 0).copyInto(bytes, 36)
        val p = packet(bytes)
        assertEquals(291 * 0.976f, p.lowG.single().x, 0.0001f)
        assertEquals(-0.976f, p.lowG.single().y, 0.0001f)
        assertEquals(-2048 * 0.976f, p.lowG.single().z, 0.0001f)
    }

    @Test fun unknownUpstreamLossBreaksHistoryOnlyAtSegmentStart() {
        val c = AcquisitionTimeline()
        val first = frame(); first[10] = 3
        assertTrue(c.accept(packet(first))!!.discontinuity)
        val second = frame(3, 1_000_390_624L); second[10] = 3
        assertFalse(c.accept(packet(second))!!.discontinuity)
        c.reset()
        val attached = c.accept(packet(frame(500, 8_000_000_000L)))!!
        assertEquals(0L, attached.gapSamples) // Clear is not a loss of 500 samples
    }
}
