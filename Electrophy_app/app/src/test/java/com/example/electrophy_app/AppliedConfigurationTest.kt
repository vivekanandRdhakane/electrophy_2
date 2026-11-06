package com.example.electrophy_app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit and regression tests for Task E007:
 * Atomic applied configuration, valid ODR modes (including 1.875 Hz low-power mode),
 * and separation of desired, pending, and applied configuration states.
 */
class AppliedConfigurationTest {

    @Test
    fun lowGRangeSuffixMapping_validAndInvalid() {
        assertEquals("2g", SensorConversion.getLowGRangeSuffix(0))
        assertEquals("4g", SensorConversion.getLowGRangeSuffix(1))
        assertEquals("8g", SensorConversion.getLowGRangeSuffix(2))
        assertEquals("16g", SensorConversion.getLowGRangeSuffix(3))

        assertNull(SensorConversion.getLowGRangeSuffix(-1))
        assertNull(SensorConversion.getLowGRangeSuffix(4))
        assertNull(SensorConversion.getLowGRangeSuffix(255))
    }

    @Test
    fun highGRangeSuffixMapping_validAndInvalid() {
        assertEquals("32g", SensorConversion.getHighGRangeSuffix(0))
        assertEquals("64g", SensorConversion.getHighGRangeSuffix(1))
        assertEquals("128g", SensorConversion.getHighGRangeSuffix(2))
        assertEquals("256g", SensorConversion.getHighGRangeSuffix(3))
        assertEquals("320g", SensorConversion.getHighGRangeSuffix(4))

        assertNull(SensorConversion.getHighGRangeSuffix(-1))
        assertNull(SensorConversion.getHighGRangeSuffix(5))
        assertNull(SensorConversion.getHighGRangeSuffix(100))
    }

    @Test
    fun gyroRangeSuffixMapping_validAndInvalid() {
        assertEquals("250dps", SensorConversion.getGyroRangeSuffix(1))
        assertEquals("500dps", SensorConversion.getGyroRangeSuffix(2))
        assertEquals("1000dps", SensorConversion.getGyroRangeSuffix(3))
        assertEquals("2000dps", SensorConversion.getGyroRangeSuffix(4))
        assertEquals("4000dps", SensorConversion.getGyroRangeSuffix(5))

        assertNull(SensorConversion.getGyroRangeSuffix(0))
        assertNull(SensorConversion.getGyroRangeSuffix(-1))
        assertNull(SensorConversion.getGyroRangeSuffix(6))
    }

    @Test
    fun odrPeriodNsMapping_includes1Hz875AndHighRates() {
        // 1.875 Hz -> period ~533,333,333 ns
        assertEquals("1hz875", SensorConversion.parsePeriodNsToOdrSuffix(533_333_333L))
        // 7.5 Hz -> period ~133,333,333 ns
        assertEquals("7hz5", SensorConversion.parsePeriodNsToOdrSuffix(133_333_333L))
        // 15 Hz -> period ~66,666,667 ns
        assertEquals("15hz", SensorConversion.parsePeriodNsToOdrSuffix(66_666_667L))
        // 120 Hz -> period ~8,333,333 ns
        assertEquals("120hz", SensorConversion.parsePeriodNsToOdrSuffix(8_333_333L))
        // 480 Hz -> period ~2,083,333 ns
        assertEquals("480hz", SensorConversion.parsePeriodNsToOdrSuffix(2_083_333L))
        // 7680 Hz -> period ~130,208 ns
        assertEquals("7680hz", SensorConversion.parsePeriodNsToOdrSuffix(130_208L))

        // Invalid period bounds
        assertNull(SensorConversion.parsePeriodNsToOdrSuffix(0L))
        assertNull(SensorConversion.parsePeriodNsToOdrSuffix(-100L))
    }

    @Test
    fun parseOdrSuffixToHz_evaluatesCorrectly() {
        assertEquals(0f, SensorConversion.parseOdrSuffixToHz("off"), 1e-4f)
        assertEquals(1.875f, SensorConversion.parseOdrSuffixToHz("1hz875"), 1e-4f)
        assertEquals(7.5f, SensorConversion.parseOdrSuffixToHz("7hz5"), 1e-4f)
        assertEquals(15f, SensorConversion.parseOdrSuffixToHz("15hz"), 1e-4f)
        assertEquals(120f, SensorConversion.parseOdrSuffixToHz("120hz"), 1e-4f)
        assertEquals(480f, SensorConversion.parseOdrSuffixToHz("480hz"), 1e-4f)
        assertEquals(7680f, SensorConversion.parseOdrSuffixToHz("7680hz"), 1e-4f)
        assertEquals(0f, SensorConversion.parseOdrSuffixToHz("unknown"), 1e-4f)
    }

    @Test
    fun appliedConfiguration_initialAndConfirmedState() {
        val initial = AppliedConfiguration()
        assertFalse("Initial configuration must not be confirmed before hardware ACK", initial.isConfirmed)
        assertEquals(0L, initial.configId)
        assertNull(initial.lastError)

        val confirmed = initial.copy(
            mode = GraphMode.LOW_G,
            lowGRange = "4g",
            lowGOdr = "1hz875",
            configId = 12L,
            isConfirmed = true
        )
        assertTrue(confirmed.isConfirmed)
        assertEquals("4g", confirmed.lowGRange)
        assertEquals("1hz875", confirmed.lowGOdr)
        assertEquals(12L, confirmed.configId)
    }

    @Test
    fun appliedConfiguration_errorTracking() {
        val cfg = AppliedConfiguration(isConfirmed = true)
        val withError = cfg.copy(lastError = "Rejected by device: range_low_g_99g rc=-1")
        assertNotNull(withError.lastError)
        assertTrue(withError.lastError!!.contains("range_low_g_99g"))
    }

    @Test
    fun rawDataDefaults_matchesStandardAppStartupConfiguration() {
        assertEquals(GraphMode.LOW_G, RawDataDefaults.mode)
        assertEquals("2g", RawDataDefaults.lowGRange)
        assertEquals("320g", RawDataDefaults.highGRange)
        assertEquals("2000dps", RawDataDefaults.gyroRange)
        assertEquals("15hz", RawDataDefaults.lowGOdr)
        assertEquals("480hz", RawDataDefaults.highGOdr)
        assertEquals("15hz", RawDataDefaults.gyroOdr)
        assertEquals(5f, RawDataDefaults.timeWindowSec, 1e-4f)
        assertFalse(RawDataDefaults.isFilterEnabled)
        assertEquals(0.2f, RawDataDefaults.filterAlpha, 1e-4f)
        assertFalse(RawDataDefaults.isAutoTriggerEnabled)
        assertEquals("Z", RawDataDefaults.autoTriggerAxis)
        assertEquals("Below", RawDataDefaults.autoTriggerCondition)
        assertEquals(50f, RawDataDefaults.autoTriggerThreshold, 1e-4f)
        assertEquals(200L, RawDataDefaults.autoTriggerDelayMs)
        assertFalse(RawDataDefaults.isPaused)
    }

    @Test
    fun appliedConfiguration_matchesRawDataDefaultsByDefault() {
        val defaultApplied = AppliedConfiguration()
        assertEquals(RawDataDefaults.mode, defaultApplied.mode)
        assertEquals(RawDataDefaults.lowGRange, defaultApplied.lowGRange)
        assertEquals(RawDataDefaults.highGRange, defaultApplied.highGRange)
        assertEquals(RawDataDefaults.gyroRange, defaultApplied.gyroRange)
        assertEquals(RawDataDefaults.lowGOdr, defaultApplied.lowGOdr)
        assertEquals(RawDataDefaults.highGOdr, defaultApplied.highGOdr)
        assertEquals(RawDataDefaults.gyroOdr, defaultApplied.gyroOdr)
    }
}

