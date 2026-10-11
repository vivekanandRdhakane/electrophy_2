package com.example.electrophy_app

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Result of attempting to decode a binary IMU packet.
 */
sealed class DecodeResult {
    data class Success(val packet: DecodedPacket) : DecodeResult()
    data class Error(val reason: String) : DecodeResult()
}

/**
 * Validated and decoded packet containing per-sensor samples.
 */
data class DecodedPacket(
    val mode: Int,
    val sampleCount: Int,
    val xlFs: Int,
    val hgFs: Int,
    val gyFs: Int,
    val seq: Int,
    val fmt: Int,
    val lowG: List<AxisPoint>,
    val highG: List<AxisPoint>,
    val gyro: List<AxisPoint>
)

/**
 * Strict BitReader for unpacking 12-bit signed sensor values from MSB-first streams.
 * Throws [IllegalArgumentException] on bit exhaustion rather than manufacturing fake zero samples.
 */
class StrictBitReader(private val data: ByteArray, startBit: Int = 0) {
    var bitPos: Int = startBit
        private set

    fun remainingBits(): Int = (data.size * 8) - bitPos

    fun readBits(n: Int): Int {
        if (bitPos + n > data.size * 8) {
            throw IllegalArgumentException(
                "BitReader exhausted: requested $n bits at bitPos $bitPos, total available bits ${data.size * 8}"
            )
        }
        var v = 0
        for (i in 0 until n) {
            val byte = data[bitPos shr 3].toInt() and 0xFF
            val bit = (byte ushr (7 - (bitPos and 7))) and 1
            v = (v shl 1) or bit
            bitPos++
        }
        return v
    }

    /**
     * Reads 12 bits and sign-extends them from 12-bit two's complement to signed Int.
     */
    fun readSigned12(): Int {
        val v = readBits(12)
        return if (v and 0x800 != 0) v - 0x1000 else v
    }
}

/**
 * Strict binary packet validator and decoder for Electrophy IMU streams.
 *
 * Enforces magic header, valid stream modes, supported formats, valid range codes,
 * and exact payload length requirements before producing any sample tuples.
 */
object PacketDecoder {
    const val MAGIC_0: Byte = 0xAA.toByte()
    const val MAGIC_1: Byte = 0x55.toByte()
    const val HEADER_SIZE: Int = 9

    const val FMT_RAW16: Int = 0
    const val FMT_PACKED12: Int = 1

    const val MODE_ALL: Int = 0
    const val MODE_LOW_ACC: Int = 1
    const val MODE_HIGH_ACC: Int = 2
    const val MODE_BOTH_ACC: Int = 3
    const val MODE_ONLY_GYRO: Int = 4

    /**
     * Returns the number of 3-axis sensors active in the given [mode].
     */
    fun getAxesForMode(mode: Int): Int? = when (mode) {
        MODE_ALL -> 9        // Low-G (3) + High-G (3) + Gyro (3)
        MODE_LOW_ACC -> 3    // Low-G (3)
        MODE_HIGH_ACC -> 3   // High-G (3)
        MODE_BOTH_ACC -> 6   // Low-G (3) + High-G (3)
        MODE_ONLY_GYRO -> 3  // Gyro (3)
        else -> null
    }

    /**
     * Calculates the minimum total packet byte length (header + payload) required
     * to hold [sampleCount] samples for [mode] in [fmt].
     */
    fun calculateRequiredBytes(mode: Int, sampleCount: Int, fmt: Int): Int? {
        val axes = getAxesForMode(mode) ?: return null
        if (sampleCount <= 0) return null

        val payloadBytes = when (fmt) {
            FMT_RAW16 -> sampleCount * axes * 2
            FMT_PACKED12 -> {
                val totalBits = sampleCount * axes * 12
                (totalBits + 7) / 8
            }
            else -> return null
        }
        return HEADER_SIZE + payloadBytes
    }

    /**
     * Strictly validates and decodes a raw binary BLE packet.
     *
     * Returns [DecodeResult.Success] only if the entire frame is structurally intact,
     * all range codes are valid, and all samples decode without buffer exhaustion.
     */
    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.size < HEADER_SIZE) {
            return DecodeResult.Error("Packet smaller than header: size=${bytes.size} < $HEADER_SIZE")
        }

        if (bytes[0] != MAGIC_0 || bytes[1] != MAGIC_1) {
            return DecodeResult.Error(
                "Invalid magic: expected [0xAA, 0x55], got [0x${(bytes[0].toInt() and 0xFF).toString(16)}, 0x${(bytes[1].toInt() and 0xFF).toString(16)}]"
            )
        }

        val mode = bytes[2].toInt() and 0xFF
        val sampleCount = bytes[3].toInt() and 0xFF
        val xlFs = bytes[4].toInt() and 0xFF
        val hgFs = bytes[5].toInt() and 0xFF
        val gyFs = bytes[6].toInt() and 0xFF
        val seq = bytes[7].toInt() and 0xFF
        val fmt = bytes[8].toInt() and 0xFF

        if (sampleCount == 0) {
            return DecodeResult.Error("Sample count is zero")
        }

        val axes = getAxesForMode(mode)
            ?: return DecodeResult.Error("Unknown stream mode: $mode")

        if (fmt != FMT_RAW16 && fmt != FMT_PACKED12) {
            return DecodeResult.Error("Unsupported payload format: $fmt")
        }

        val packed12 = (fmt == FMT_PACKED12)

        // Validate range codes for active sensor streams
        val xlScale = if (mode == MODE_ALL || mode == MODE_LOW_ACC || mode == MODE_BOTH_ACC) {
            SensorConversion.getLowGScale(xlFs, packed12)
                ?: return DecodeResult.Error("Invalid Low-G range code xlFs=$xlFs for mode=$mode")
        } else 0f

        val hgScale = if (mode == MODE_ALL || mode == MODE_HIGH_ACC || mode == MODE_BOTH_ACC) {
            SensorConversion.getHighGScale(hgFs, packed12)
                ?: return DecodeResult.Error("Invalid High-G range code hgFs=$hgFs for mode=$mode")
        } else 0f

        val gyScale = if (mode == MODE_ALL || mode == MODE_ONLY_GYRO) {
            SensorConversion.getGyroScale(gyFs, packed12)
                ?: return DecodeResult.Error("Invalid Gyro range code gyFs=$gyFs for mode=$mode")
        } else 0f

        // Validate packet payload length
        val requiredBytes = calculateRequiredBytes(mode, sampleCount, fmt)
            ?: return DecodeResult.Error("Failed to calculate required packet length")

        if (bytes.size < requiredBytes) {
            return DecodeResult.Error(
                "Truncated packet: buffer size=${bytes.size} < requiredBytes=$requiredBytes for sampleCount=$sampleCount, mode=$mode, fmt=$fmt"
            )
        }

        val lowGList = mutableListOf<AxisPoint>()
        val highGList = mutableListOf<AxisPoint>()
        val gyroList = mutableListOf<AxisPoint>()

        try {
            val bitReader = if (packed12) StrictBitReader(bytes, startBit = HEADER_SIZE * 8) else null
            val byteBuffer = if (!packed12) {
                ByteBuffer.wrap(bytes, HEADER_SIZE, bytes.size - HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            } else null

            fun readAxis(scale: Float): Float {
                val raw = if (packed12) {
                    bitReader!!.readSigned12()
                } else {
                    byteBuffer!!.short.toInt()
                }
                return raw * scale
            }

            for (i in 0 until sampleCount) {
                when (mode) {
                    MODE_LOW_ACC -> {
                        lowGList.add(AxisPoint(readAxis(xlScale), readAxis(xlScale), readAxis(xlScale)))
                    }
                    MODE_HIGH_ACC -> {
                        highGList.add(AxisPoint(readAxis(hgScale), readAxis(hgScale), readAxis(hgScale)))
                    }
                    MODE_BOTH_ACC -> {
                        val lgX = readAxis(xlScale); val lgY = readAxis(xlScale); val lgZ = readAxis(xlScale)
                        val hgX = readAxis(hgScale); val hgY = readAxis(hgScale); val hgZ = readAxis(hgScale)
                        lowGList.add(AxisPoint(lgX, lgY, lgZ))
                        highGList.add(AxisPoint(hgX, hgY, hgZ))
                    }
                    MODE_ONLY_GYRO -> {
                        gyroList.add(AxisPoint(readAxis(gyScale), readAxis(gyScale), readAxis(gyScale)))
                    }
                    MODE_ALL -> {
                        val lgX = readAxis(xlScale); val lgY = readAxis(xlScale); val lgZ = readAxis(xlScale)
                        val hgX = readAxis(hgScale); val hgY = readAxis(hgScale); val hgZ = readAxis(hgScale)
                        val gyX = readAxis(gyScale); val gyY = readAxis(gyScale); val gyZ = readAxis(gyScale)
                        lowGList.add(AxisPoint(lgX, lgY, lgZ))
                        highGList.add(AxisPoint(hgX, hgY, hgZ))
                        gyroList.add(AxisPoint(gyX, gyY, gyZ))
                    }
                }
            }
        } catch (e: Exception) {
            return DecodeResult.Error("Decoding failed due to read exception: ${e.message}")
        }

        return DecodeResult.Success(
            DecodedPacket(
                mode = mode,
                sampleCount = sampleCount,
                xlFs = xlFs,
                hgFs = hgFs,
                gyFs = gyFs,
                seq = seq,
                fmt = fmt,
                lowG = lowGList,
                highG = highGList,
                gyro = gyroList
            )
        )
    }
}

