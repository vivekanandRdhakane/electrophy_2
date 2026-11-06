package com.example.electrophy_app

/**
 * Encapsulates the hardware-applied sensor configuration confirmed from either:
 * 1. Verified packet headers (binary AA55 / AA58 frames containing actual fs/mode codes).
 * 2. Explicit acknowledgement strings (e.g. CFG_OK / CFG_ERR) returned by firmware.
 *
 * This provides provenance for downstream experiment analysis and separates
 * user-desired settings from what the IMU has actually applied.
 */
data class AppliedConfiguration(
    val mode: GraphMode = GraphMode.LOW_G,
    val lowGRange: String = "2g",
    val highGRange: String = "320g",
    val gyroRange: String = "2000dps",
    val lowGOdr: String = "15hz",
    val highGOdr: String = "480hz",
    val gyroOdr: String = "15hz",
    val configId: Long = 0L,
    val isConfirmed: Boolean = false,
    val lastError: String? = null
)

