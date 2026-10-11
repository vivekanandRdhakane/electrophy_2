package com.example.electrophy_app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression and acceptance tests for [SensorConversion] (Task E001).
 *
 * Verifies high-g, low-g, and gyro sensitivity scaling against the official
 * ST LSM6DSV320X register driver (lsm6dsv320x_reg.c).
 */
class SensorConversionTest {

    private val floatTolerance = 1e-4f

    @Test
    fun acceptanceCriterion_packedValue600At320gIs100g() {
        // Packed code 600 at FS code 4 (±320g) with packed12 (* 16)
        // 600 * 16 * 0.010417 = 9600 * 0.010417 = 100.0032 g
        val result = SensorConversion.rawHighGToG(600, hgFs = 4, packed12 = true)
        assertNotNull("Expected valid result for FS code 4", result)
        assertEquals(100.0032f, result!!, floatTolerance)

        // Verify it does NOT evaluate to the faulty 93.744g
        assertNotEquals(93.744f, result, 0.01f)
    }

    @Test
    fun highGSensitivities_matchOfficialSTDriver() {
        // High-G sensitivities: [0.000976, 0.001952, 0.003904, 0.007808, 0.010417] g/LSB
        val expected = floatArrayOf(0.000976f, 0.001952f, 0.003904f, 0.007808f, 0.010417f)
        for (fs in expected.indices) {
            val sens = SensorConversion.getHighGSensitivityG(fs)
            assertNotNull("FS $fs must be defined", sens)
            assertEquals(expected[fs], sens!!, 1e-6f)
        }
    }

    @Test
    fun highGFormats_raw16AndPacked12() {
        val count = 1000
        val fs = 4 // ±320g
        val raw16Val = SensorConversion.rawHighGToG(count, hgFs = fs, packed12 = false)
        val packed12Val = SensorConversion.rawHighGToG(count, hgFs = fs, packed12 = true)

        assertNotNull(raw16Val)
        assertNotNull(packed12Val)

        // 1000 * 0.010417 = 10.417 g
        assertEquals(10.417f, raw16Val!!, floatTolerance)
        // 1000 * 16 * 0.010417 = 166.672 g
        assertEquals(166.672f, packed12Val!!, floatTolerance)
        // Packed12 must be exactly 16x raw16
        assertEquals(raw16Val * 16f, packed12Val, floatTolerance)
    }

    @Test
    fun lowGSensitivities_matchOfficialSTDriver() {
        // Low-G sensitivities in mg/LSB: [0.061, 0.122, 0.244, 0.488]
        val expected = floatArrayOf(0.061f, 0.122f, 0.244f, 0.488f)
        for (fs in expected.indices) {
            val sens = SensorConversion.getLowGSensitivityMg(fs)
            assertNotNull("FS $fs must be defined", sens)
            assertEquals(expected[fs], sens!!, 1e-6f)
        }
    }

    @Test
    fun gyroSensitivities_matchOfficialSTDriver() {
        val expected = mapOf(
            0 to 4.375f,
            1 to 8.75f,
            2 to 17.50f,
            3 to 35.0f,
            4 to 70.0f,
            5 to 140.0f
        )
        for ((fs, expSens) in expected) {
            val sens = SensorConversion.getGyroSensitivityMdps(fs)
            assertNotNull("Gyro FS $fs must be defined", sens)
            assertEquals(expSens, sens!!, 1e-6f)
        }
    }

    @Test
    fun boundaryAndNegativeValues_areSymmetric() {
        // High-G packed boundary values
        val minPacked12 = -2048
        val maxPacked12 = 2047
        val minG = SensorConversion.rawHighGToG(minPacked12, hgFs = 4, packed12 = true)!!
        val maxG = SensorConversion.rawHighGToG(maxPacked12, hgFs = 4, packed12 = true)!!

        assertEquals(-341.34426f, minG, 0.01f)
        assertEquals(341.17764f, maxG, 0.01f)

        // Negative symmetry check: x and -x produce negated results
        val pos600 = SensorConversion.rawHighGToG(600, hgFs = 4, packed12 = true)!!
        val neg600 = SensorConversion.rawHighGToG(-600, hgFs = 4, packed12 = true)!!
        assertEquals(-pos600, neg600, floatTolerance)

        // Raw16 boundary values
        val minRaw16 = -32768
        val maxRaw16 = 32767
        val minRawG = SensorConversion.rawHighGToG(minRaw16, hgFs = 4, packed12 = false)!!
        val maxRawG = SensorConversion.rawHighGToG(maxRaw16, hgFs = 4, packed12 = false)!!

        assertEquals(-341.34426f, minRawG, 0.01f)
        assertEquals(341.33384f, maxRawG, 0.01f)
    }

    @Test
    fun unknownRangeIDs_areRejected() {
        // High-G only supports 0..4
        assertNull(SensorConversion.getHighGSensitivityG(-1))
        assertNull(SensorConversion.getHighGSensitivityG(5))
        assertNull(SensorConversion.getHighGSensitivityG(255))
        assertNull(SensorConversion.rawHighGToG(600, hgFs = 5, packed12 = true))

        // Low-G only supports 0..3
        assertNull(SensorConversion.getLowGSensitivityMg(-1))
        assertNull(SensorConversion.getLowGSensitivityMg(4))
        assertNull(SensorConversion.getLowGSensitivityMg(255))
        assertNull(SensorConversion.rawLowGToMg(100, xlFs = 4, packed12 = false))

        // Gyro only supports 0..5
        assertNull(SensorConversion.getGyroSensitivityMdps(-1))
        assertNull(SensorConversion.getGyroSensitivityMdps(6))
        assertNull(SensorConversion.getGyroSensitivityMdps(255))
        assertNull(SensorConversion.rawGyroToMdps(100, gyFs = 6, packed12 = false))
    }
}

