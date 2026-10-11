package com.example.electrophy_app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Acceptance and regression test suite for PacketDecoder (Task E002).
 *
 * Verifies strict decoding, MTU boundaries, odd sample counts, truncation detection,
 * invalid headers and range IDs, and random garbage rejection.
 */
class PacketDecoderTest {

    // Helper BitWriter to generate 12-bit packed streams MSB-first (matching firmware pack)
    private class BitWriter {
        private val bits = mutableListOf<Int>()

        fun writeBits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                bits.add((value ushr i) and 1)
            }
        }

        fun toByteArray(): ByteArray {
            val byteCount = (bits.size + 7) / 8
            val out = ByteArray(byteCount)
            for (i in bits.indices) {
                if (bits[i] == 1) {
                    out[i / 8] = (out[i / 8].toInt() or (1 shl (7 - (i % 8)))).toByte()
                }
            }
            return out
        }
    }

    private fun buildRaw16Packet(
        mode: Int,
        sampleCount: Int,
        xlFs: Int = 0,
        hgFs: Int = 0,
        gyFs: Int = 0,
        seq: Int = 1,
        values: List<Short>
    ): ByteArray {
        val totalBytes = 9 + values.size * 2
        val bb = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(0xAA.toByte())
        bb.put(0x55.toByte())
        bb.put(mode.toByte())
        bb.put(sampleCount.toByte())
        bb.put(xlFs.toByte())
        bb.put(hgFs.toByte())
        bb.put(gyFs.toByte())
        bb.put(seq.toByte())
        bb.put(PacketDecoder.FMT_RAW16.toByte())
        for (v in values) {
            bb.putShort(v)
        }
        return bb.array()
    }

    private fun buildPacked12Packet(
        mode: Int,
        sampleCount: Int,
        xlFs: Int = 0,
        hgFs: Int = 0,
        gyFs: Int = 0,
        seq: Int = 1,
        values: List<Int>
    ): ByteArray {
        val writer = BitWriter()
        for (v in values) {
            writer.writeBits(v and 0xFFF, 12)
        }
        val payload = writer.toByteArray()
        val out = ByteArray(9 + payload.size)
        out[0] = 0xAA.toByte()
        out[1] = 0x55.toByte()
        out[2] = mode.toByte()
        out[3] = sampleCount.toByte()
        out[4] = xlFs.toByte()
        out[5] = hgFs.toByte()
        out[6] = gyFs.toByte()
        out[7] = seq.toByte()
        out[8] = PacketDecoder.FMT_PACKED12.toByte()
        System.arraycopy(payload, 0, out, 9, payload.size)
        return out
    }

    @Test
    fun testValidRaw16_allModes() {
        // Mode 1: LOW_ACC (3 axes per sample)
        val rawLow = buildRaw16Packet(
            mode = PacketDecoder.MODE_LOW_ACC,
            sampleCount = 2,
            xlFs = 0, // ±2g -> 0.061 mg/LSB
            values = listOf(1000, -1000, 2000, 3000, -3000, 4000)
        )
        val resLow = PacketDecoder.decode(rawLow)
        assertTrue(resLow is DecodeResult.Success)
        val pktLow = (resLow as DecodeResult.Success).packet
        assertEquals(2, pktLow.sampleCount)
        assertEquals(2, pktLow.lowG.size)
        assertEquals(1000 * 0.061f, pktLow.lowG[0].x, 0.01f)
        assertEquals(-1000 * 0.061f, pktLow.lowG[0].y, 0.01f)
        assertEquals(2000 * 0.061f, pktLow.lowG[0].z, 0.01f)

        // Mode 2: HIGH_ACC (3 axes per sample)
        val rawHigh = buildRaw16Packet(
            mode = PacketDecoder.MODE_HIGH_ACC,
            sampleCount = 1,
            hgFs = 4, // ±320g -> 0.010417 g/LSB
            values = listOf(600, -600, 9600)
        )
        val resHigh = PacketDecoder.decode(rawHigh)
        assertTrue(resHigh is DecodeResult.Success)
        val pktHigh = (resHigh as DecodeResult.Success).packet
        assertEquals(1, pktHigh.sampleCount)
        assertEquals(1, pktHigh.highG.size)
        assertEquals(600 * 0.010417f, pktHigh.highG[0].x, 0.001f)
        assertEquals(-600 * 0.010417f, pktHigh.highG[0].y, 0.001f)
        assertEquals(9600 * 0.010417f, pktHigh.highG[0].z, 0.001f)

        // Mode 4: ONLY_GYRO (3 axes per sample)
        val rawGyro = buildRaw16Packet(
            mode = PacketDecoder.MODE_ONLY_GYRO,
            sampleCount = 1,
            gyFs = 0, // ±125 dps -> 4.375 mdps/LSB
            values = listOf(100, 200, 300)
        )
        val resGyro = PacketDecoder.decode(rawGyro)
        assertTrue(resGyro is DecodeResult.Success)
        val pktGyro = (resGyro as DecodeResult.Success).packet
        assertEquals(1, pktGyro.gyro.size)
        assertEquals(100 * 4.375f, pktGyro.gyro[0].x, 0.01f)

        // Mode 3: BOTH_ACC (6 axes per sample: Low-G then High-G)
        val rawBoth = buildRaw16Packet(
            mode = PacketDecoder.MODE_BOTH_ACC,
            sampleCount = 1,
            xlFs = 0,
            hgFs = 0, // ±32g -> 0.000976 g/LSB
            values = listOf(10, 20, 30, 40, 50, 60)
        )
        val resBoth = PacketDecoder.decode(rawBoth)
        assertTrue(resBoth is DecodeResult.Success)
        val pktBoth = (resBoth as DecodeResult.Success).packet
        assertEquals(1, pktBoth.lowG.size)
        assertEquals(1, pktBoth.highG.size)
        assertEquals(10 * 0.061f, pktBoth.lowG[0].x, 0.01f)
        assertEquals(40 * 0.000976f, pktBoth.highG[0].x, 0.0001f)

        // Mode 0: ALL (9 axes per sample: Low-G, High-G, Gyro)
        val rawAll = buildRaw16Packet(
            mode = PacketDecoder.MODE_ALL,
            sampleCount = 1,
            xlFs = 0,
            hgFs = 0,
            gyFs = 0,
            values = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
        )
        val resAll = PacketDecoder.decode(rawAll)
        assertTrue(resAll is DecodeResult.Success)
        val pktAll = (resAll as DecodeResult.Success).packet
        assertEquals(1, pktAll.lowG.size)
        assertEquals(1, pktAll.highG.size)
        assertEquals(1, pktAll.gyro.size)
    }

    @Test
    fun testValidPacked12_allModes_withNegativeValues() {
        // Test signed 12-bit values: positive 100, negative -100, max pos 2047, min neg -2048
        val packedValues = listOf(100, -100, 2047, -2048, 0, 500)
        val pktBytes = buildPacked12Packet(
            mode = PacketDecoder.MODE_LOW_ACC,
            sampleCount = 2,
            xlFs = 0, // ±2g packed12 scale = 0.061f * 16 = 0.976f
            values = packedValues
        )
        val res = PacketDecoder.decode(pktBytes)
        assertTrue(res is DecodeResult.Success)
        val pkt = (res as DecodeResult.Success).packet
        assertEquals(2, pkt.lowG.size)

        val scale = SensorConversion.getLowGScale(0, packed12 = true)!!
        assertEquals(100 * scale, pkt.lowG[0].x, 0.01f)
        assertEquals(-100 * scale, pkt.lowG[0].y, 0.01f)
        assertEquals(2047 * scale, pkt.lowG[0].z, 0.01f)
        assertEquals(-2048 * scale, pkt.lowG[1].x, 0.01f)
        assertEquals(0f, pkt.lowG[1].y, 0.01f)
        assertEquals(500 * scale, pkt.lowG[1].z, 0.01f)
    }

    @Test
    fun testOddPackedSampleCounts() {
        // Odd sample counts: 1 sample, 3 samples, 7 samples in packed12
        for (count in listOf(1, 3, 7)) {
            val axes = 3 // MODE_LOW_ACC
            val totalValues = count * axes
            val values = (1..totalValues).map { it * 10 }
            val bytes = buildPacked12Packet(
                mode = PacketDecoder.MODE_LOW_ACC,
                sampleCount = count,
                xlFs = 0,
                values = values
            )
            // Verify expected byte length: 9 + ceil(count * 3 * 12 / 8)
            val expectedPayloadBits = count * 3 * 12
            val expectedPayloadBytes = (expectedPayloadBits + 7) / 8
            assertEquals(9 + expectedPayloadBytes, bytes.size)

            val res = PacketDecoder.decode(bytes)
            assertTrue("Failed for sample count $count", res is DecodeResult.Success)
            val decoded = (res as DecodeResult.Success).packet
            assertEquals(count, decoded.sampleCount)
            assertEquals(count, decoded.lowG.size)
        }
    }

    @Test
    fun testMtuBoundaries_mtu23_185_247_517() {
        // MTU 23: Max ATT payload = 23 - 3 = 20 bytes.
        // In ALL mode (9 axes):
        // Raw16: 1 sample = 18 bytes payload + 9 bytes header = 27 bytes > 20 bytes.
        // Packed12: 1 sample = ceil(9 * 12 / 8) = 14 bytes payload + 9 bytes header = 23 bytes > 20 bytes.
        // Therefore, ALL mode cannot fit even 1 sample at MTU 23.
        // A packet claiming 1 sample at MTU 23 (truncated to 20 bytes) MUST be rejected by decoder.
        val truncatedAll = ByteArray(20) { 0 }
        truncatedAll[0] = 0xAA.toByte()
        truncatedAll[1] = 0x55.toByte()
        truncatedAll[2] = PacketDecoder.MODE_ALL.toByte()
        truncatedAll[3] = 1 // sampleCount = 1
        truncatedAll[8] = PacketDecoder.FMT_PACKED12.toByte()
        val resMtu23All = PacketDecoder.decode(truncatedAll)
        assertTrue(resMtu23All is DecodeResult.Error)

        // However, in LOW_ACC mode (3 axes):
        // Packed12: 1 sample = ceil(36/8) = 5 bytes payload + 9 header = 14 bytes <= 20 bytes.
        // Packed12: 2 samples = ceil(72/8) = 9 bytes payload + 9 header = 18 bytes <= 20 bytes.
        // These fit within MTU 23!
        val validMtu23 = buildPacked12Packet(
            mode = PacketDecoder.MODE_LOW_ACC,
            sampleCount = 2,
            xlFs = 0,
            values = listOf(1, 2, 3, 4, 5, 6)
        )
        assertTrue(validMtu23.size <= 20)
        val resMtu23Low = PacketDecoder.decode(validMtu23)
        assertTrue(resMtu23Low is DecodeResult.Success)

        // MTU 185: max packet = 182 bytes.
        // In LOW_ACC raw16 (6 bytes/sample), max samples = (182 - 9) / 6 = 28 samples (9 + 168 = 177 bytes).
        val validMtu185 = buildRaw16Packet(
            mode = PacketDecoder.MODE_LOW_ACC,
            sampleCount = 28,
            xlFs = 0,
            values = (1..(28 * 3)).map { it.toShort() }
        )
        assertTrue(validMtu185.size <= 182)
        val resMtu185 = PacketDecoder.decode(validMtu185)
        assertTrue(resMtu185 is DecodeResult.Success)

        // MTU 247: max packet = 244 bytes.
        // In BOTH_ACC raw16 (12 bytes/sample), max samples = (244 - 9) / 12 = 19 samples (9 + 228 = 237 bytes).
        val validMtu247 = buildRaw16Packet(
            mode = PacketDecoder.MODE_BOTH_ACC,
            sampleCount = 19,
            xlFs = 0,
            hgFs = 0,
            values = (1..(19 * 6)).map { it.toShort() }
        )
        assertTrue(validMtu247.size <= 244)
        val resMtu247 = PacketDecoder.decode(validMtu247)
        assertTrue(resMtu247 is DecodeResult.Success)

        // MTU 517: capped at 512 bytes.
        // In ALL mode raw16 (18 bytes/sample), max samples = (512 - 9) / 18 = 27 samples (9 + 486 = 495 bytes).
        val validMtu517 = buildRaw16Packet(
            mode = PacketDecoder.MODE_ALL,
            sampleCount = 27,
            xlFs = 0,
            hgFs = 0,
            gyFs = 0,
            values = (1..(27 * 9)).map { it.toShort() }
        )
        assertTrue(validMtu517.size <= 512)
        val resMtu517 = PacketDecoder.decode(validMtu517)
        assertTrue(resMtu517 is DecodeResult.Success)
    }

    @Test
    fun testTruncationBoundaries_neverEmitsSamples() {
        val validPacket = buildRaw16Packet(
            mode = PacketDecoder.MODE_ALL,
            sampleCount = 2,
            xlFs = 0,
            hgFs = 0,
            gyFs = 0,
            values = (1..18).map { it.toShort() }
        )
        val reqLen = validPacket.size

        // Test every truncation boundary from 0 up to reqLen - 1
        for (len in 0 until reqLen) {
            val truncated = validPacket.copyOfRange(0, len)
            val res = PacketDecoder.decode(truncated)
            assertTrue("Expected Error for length $len, got $res", res is DecodeResult.Error)
        }
    }

    @Test
    fun testInvalidHeadersAndRangeCodes() {
        // Bad magic 0x00 0x00
        val badMagic = buildRaw16Packet(mode = 1, sampleCount = 1, values = listOf(1, 2, 3))
        badMagic[0] = 0x00
        badMagic[1] = 0x00
        assertTrue(PacketDecoder.decode(badMagic) is DecodeResult.Error)

        // Bad magic 0x55 0xAA (reversed)
        badMagic[0] = 0x55
        badMagic[1] = 0xAA.toByte()
        assertTrue(PacketDecoder.decode(badMagic) is DecodeResult.Error)

        // Sample count 0
        val zeroCount = buildRaw16Packet(mode = 1, sampleCount = 0, values = emptyList())
        assertTrue(PacketDecoder.decode(zeroCount) is DecodeResult.Error)

        // Unknown mode 5 or 255
        val unknownMode = buildRaw16Packet(mode = 5, sampleCount = 1, values = listOf(1, 2, 3))
        assertTrue(PacketDecoder.decode(unknownMode) is DecodeResult.Error)

        // Unknown format 2
        val unknownFmt = buildRaw16Packet(mode = 1, sampleCount = 1, values = listOf(1, 2, 3))
        unknownFmt[8] = 2.toByte()
        assertTrue(PacketDecoder.decode(unknownFmt) is DecodeResult.Error)

        // Invalid range code: xlFs = 99 for LOW_ACC
        val invalidXlFs = buildRaw16Packet(mode = 1, sampleCount = 1, xlFs = 99, values = listOf(1, 2, 3))
        assertTrue(PacketDecoder.decode(invalidXlFs) is DecodeResult.Error)

        // Invalid range code: hgFs = 99 for HIGH_ACC
        val invalidHgFs = buildRaw16Packet(mode = 2, sampleCount = 1, hgFs = 99, values = listOf(1, 2, 3))
        assertTrue(PacketDecoder.decode(invalidHgFs) is DecodeResult.Error)

        // Invalid range code: gyFs = 99 for ONLY_GYRO
        val invalidGyFs = buildRaw16Packet(mode = 4, sampleCount = 1, gyFs = 99, values = listOf(1, 2, 3))
        assertTrue(PacketDecoder.decode(invalidGyFs) is DecodeResult.Error)
    }

    @Test
    fun testRandomGarbageFuzz_neverCrashes() {
        val rng = Random(42)
        for (i in 0 until 500) {
            val length = rng.nextInt(0, 600)
            val garbage = ByteArray(length)
            rng.nextBytes(garbage)

            val res = PacketDecoder.decode(garbage)
            // Garbage must either cleanly decode (astronomically unlikely) or return DecodeResult.Error
            // under NO circumstance should it throw an unhandled exception or crash.
            assertNotNull(res)
        }
    }

    @Test
    fun testStrictBitReader_exhaustionThrows() {
        val data = byteArrayOf(0x12, 0x34) // 16 bits
        val reader = StrictBitReader(data, startBit = 0)
        assertEquals(16, reader.remainingBits())

        // Read 12 bits -> 4 bits remain
        reader.readSigned12()
        assertEquals(4, reader.remainingBits())

        // Reading another 12 bits should fail
        try {
            reader.readSigned12()
            fail("Expected IllegalArgumentException on bit exhaustion")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("BitReader exhausted"))
        }
    }
}

