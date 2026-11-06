package com.example.electrophy_app

/**
 * Canonical default configuration for Raw Data Mode.
 * Whenever switching from any experiment back to Raw Data Mode, the sensor
 * and ViewModel parameters are restored to these defaults.
 */
object RawDataDefaults {
    val mode: GraphMode = GraphMode.LOW_G
    const val lowGRange: String = "2g"
    const val highGRange: String = "320g"
    const val gyroRange: String = "2000dps"
    const val lowGOdr: String = "15hz"
    const val highGOdr: String = "480hz"
    const val gyroOdr: String = "15hz"
    const val timeWindowSec: Float = 5f
    const val isFilterEnabled: Boolean = false
    const val filterAlpha: Float = 0.2f
    const val isAutoTriggerEnabled: Boolean = false
    const val autoTriggerAxis: String = "Z"
    const val autoTriggerCondition: String = "Below"
    const val autoTriggerThreshold: Float = 50f
    const val autoTriggerDelayMs: Long = 200L
    const val isPaused: Boolean = false
}

/**
 * Encapsulates the hardware-applied sensor configuration confirmed from either:
 * 1. Verified packet headers (binary AA55 / AA58 frames containing actual fs/mode codes).
 * 2. Explicit acknowledgement strings (e.g. CFG_OK / CFG_ERR) returned by firmware.
 *
 * This provides provenance for downstream experiment analysis and separates
 * user-desired settings from what the IMU has actually applied.
 */
data class AppliedConfiguration(
    val mode: GraphMode = RawDataDefaults.mode,
    val lowGRange: String = RawDataDefaults.lowGRange,
    val highGRange: String = RawDataDefaults.highGRange,
    val gyroRange: String = RawDataDefaults.gyroRange,
    val lowGOdr: String = RawDataDefaults.lowGOdr,
    val highGOdr: String = RawDataDefaults.highGOdr,
    val gyroOdr: String = RawDataDefaults.gyroOdr,
    val configId: Long = 0L,
    val isConfirmed: Boolean = false,
    val lastError: String? = null
)

