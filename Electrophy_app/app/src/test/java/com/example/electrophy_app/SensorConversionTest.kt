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

    @Test
    fun parseRangeLimitG_parsesHighGAndLowGCorrectly() {
        assertEquals(2f, SensorConversion.parseRangeLimitG("2g")!!, floatTolerance)
        assertEquals(4f, SensorConversion.parseRangeLimitG("4g")!!, floatTolerance)
        assertEquals(8f, SensorConversion.parseRangeLimitG("8g")!!, floatTolerance)
        assertEquals(16f, SensorConversion.parseRangeLimitG("16g")!!, floatTolerance)
        assertEquals(32f, SensorConversion.parseRangeLimitG("32g")!!, floatTolerance)
        assertEquals(64f, SensorConversion.parseRangeLimitG("64g")!!, floatTolerance)
        assertEquals(128f, SensorConversion.parseRangeLimitG("128g")!!, floatTolerance)
        assertEquals(256f, SensorConversion.parseRangeLimitG("256g")!!, floatTolerance)
        assertEquals(320f, SensorConversion.parseRangeLimitG("320g")!!, floatTolerance)

        // Formatted strings with ±, whitespace, and units
        assertEquals(64f, SensorConversion.parseRangeLimitG("±64 g")!!, floatTolerance)
        assertEquals(320f, SensorConversion.parseRangeLimitG("±320g")!!, floatTolerance)
        assertEquals(32f, SensorConversion.parseRangeLimitG(" 32 G ")!!, floatTolerance)
    }

    @Test
    fun getNextHigherRangeSuffix_stepsUpCorrectly() {
        assertEquals("64g", SensorConversion.getNextHigherRangeSuffix("32g"))
        assertEquals("128g", SensorConversion.getNextHigherRangeSuffix("64g"))
        assertEquals("256g", SensorConversion.getNextHigherRangeSuffix("128g"))
        assertEquals("320g", SensorConversion.getNextHigherRangeSuffix("256g"))
        assertNull("320g is the maximum range and cannot step up", SensorConversion.getNextHigherRangeSuffix("320g"))

        assertEquals("4g", SensorConversion.getNextHigherRangeSuffix("2g"))
        assertEquals("8g", SensorConversion.getNextHigherRangeSuffix("4g"))
        assertEquals("16g", SensorConversion.getNextHigherRangeSuffix("8g"))
        assertEquals("32g", SensorConversion.getNextHigherRangeSuffix("16g"))
    }

    @Test
    fun checkRangeSaturation_triggersAtOrAbove60gFor64gRange() {
        // User specification: ±64g range with observed peak around it (e.g. above 60g)
        val belowThreshold = SensorConversion.checkRangeSaturation(58.0f, "64g")
        org.junit.Assert.assertFalse("58g on 64g is below 93.75% threshold", belowThreshold.isNearSaturation)

        val atThreshold = SensorConversion.checkRangeSaturation(60.0f, "64g")
        org.junit.Assert.assertTrue("60g on 64g must trigger near-saturation", atThreshold.isNearSaturation)
        assertEquals("128g", atThreshold.recommendedRangeSuffix)
        assertEquals("±128 g", atThreshold.recommendedRangeLabel)

        val aboveThreshold = SensorConversion.checkRangeSaturation(62.5f, "64g")
        org.junit.Assert.assertTrue("62.5g on 64g must trigger near-saturation", aboveThreshold.isNearSaturation)

        // Negative peak symmetry
        val negativePeak = SensorConversion.checkRangeSaturation(-61.2f, "64g")
        org.junit.Assert.assertTrue("-61.2g on 64g must trigger near-saturation", negativePeak.isNearSaturation)
    }

    @Test
    fun checkRangeSaturation_appliesToOtherRanges() {
        // 32g: threshold = 30.0g
        org.junit.Assert.assertFalse(SensorConversion.checkRangeSaturation(28.0f, "32g").isNearSaturation)
        val sat32 = SensorConversion.checkRangeSaturation(30.5f, "32g")
        org.junit.Assert.assertTrue(sat32.isNearSaturation)
        assertEquals("64g", sat32.recommendedRangeSuffix)

        // 128g: threshold = 120.0g
        org.junit.Assert.assertFalse(SensorConversion.checkRangeSaturation(115.0f, "128g").isNearSaturation)
        val sat128 = SensorConversion.checkRangeSaturation(121.0f, "128g")
        org.junit.Assert.assertTrue(sat128.isNearSaturation)
        assertEquals("256g", sat128.recommendedRangeSuffix)

        // 256g: threshold = 240.0g
        org.junit.Assert.assertFalse(SensorConversion.checkRangeSaturation(230.0f, "256g").isNearSaturation)
        val sat256 = SensorConversion.checkRangeSaturation(245.0f, "256g")
        org.junit.Assert.assertTrue(sat256.isNearSaturation)
        assertEquals("320g", sat256.recommendedRangeSuffix)

        // 320g: threshold = 300.0g (maximum range, no higher range)
        org.junit.Assert.assertFalse(SensorConversion.checkRangeSaturation(290.0f, "320g").isNearSaturation)
        val sat320 = SensorConversion.checkRangeSaturation(305.0f, "320g")
        org.junit.Assert.assertTrue(sat320.isNearSaturation)
        assertNull("320g has no higher range", sat320.recommendedRangeSuffix)

        // Low-G 8g: threshold = 7.5g
        org.junit.Assert.assertFalse(SensorConversion.checkRangeSaturation(7.0f, "8g").isNearSaturation)
        val sat8 = SensorConversion.checkRangeSaturation(7.6f, "8g")
        org.junit.Assert.assertTrue(sat8.isNearSaturation)
        assertEquals("16g", sat8.recommendedRangeSuffix)
    }
}

