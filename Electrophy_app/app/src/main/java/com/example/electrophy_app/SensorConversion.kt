package com.example.electrophy_app

/**
 * Centralized conversion factors and helpers for LSM6DSV320X sensors.
 *
 * Values are taken directly from the official ST driver (lsm6dsv320x_reg.c).
 * - Low-G sensitivity is in mg/LSB.
 * - High-G sensitivity is in g/LSB (driver defines mg/LSB, divided by 1000 for g).
 * - Gyroscope sensitivity is in mdps/LSB.
 */
object SensorConversion {
    /**
     * 12-bit packed values are produced in firmware via `raw >> 4`.
     * To reconstruct the 16-bit scale, multiply by 16 (2^4).
     */
    const val PACKED12_MULTIPLIER: Float = 16f

    // Low-G sensitivities in mg/LSB
    // ST driver: lsm6dsv320x_from_fs{2,4,8,16}_to_mg
    private val LOW_G_SENSITIVITIES_MG = floatArrayOf(
        0.061f, // 0: ±2g
        0.122f, // 1: ±4g
        0.244f, // 2: ±8g
        0.488f  // 3: ±16g
    )

    // High-G sensitivities in g/LSB
    // ST driver: lsm6dsv320x_from_fs{32,64,128,256,320}_to_mg / 1000.0f
    private val HIGH_G_SENSITIVITIES_G = floatArrayOf(
        0.000976f, // 0: ±32g  (0.976 mg/LSB)
        0.001952f, // 1: ±64g  (1.952 mg/LSB)
        0.003904f, // 2: ±128g (3.904 mg/LSB)
        0.007808f, // 3: ±256g (7.808 mg/LSB)
        0.010417f  // 4: ±320g (10.417 mg/LSB)
    )

    // Gyroscope sensitivities in mdps/LSB
    // ST driver: lsm6dsv320x_from_fs{125,250,500,1000,2000,4000}_to_mdps
    private val GYRO_SENSITIVITIES_MDPS = mapOf(
        0 to 4.375f,  // 0: ±125dps
        1 to 8.75f,   // 1: ±250dps
        2 to 17.50f,  // 2: ±500dps
        3 to 35.0f,   // 3: ±1000dps
        4 to 70.0f,   // 4: ±2000dps
        5 to 140.0f   // 5: ±4000dps
    )

    /**
     * Returns sensitivity in mg/LSB for the given Low-G full-scale code,
     * or null if the range code is unknown/invalid.
     */
    fun getLowGSensitivityMg(xlFs: Int): Float? {
        return if (xlFs in LOW_G_SENSITIVITIES_MG.indices) LOW_G_SENSITIVITIES_MG[xlFs] else null
    }

    /**
     * Returns sensitivity in g/LSB for the given High-G full-scale code,
     * or null if the range code is unknown/invalid.
     */
    fun getHighGSensitivityG(hgFs: Int): Float? {
        return if (hgFs in HIGH_G_SENSITIVITIES_G.indices) HIGH_G_SENSITIVITIES_G[hgFs] else null
    }

    /**
     * Returns sensitivity in mdps/LSB for the given Gyroscope full-scale code,
     * or null if the range code is unknown/invalid.
     */
    fun getGyroSensitivityMdps(gyFs: Int): Float? {
        return GYRO_SENSITIVITIES_MDPS[gyFs]
    }

    /**
     * Returns the effective scaling factor for Low-G samples (mg per LSB or packed LSB).
     * Returns null if [xlFs] is unknown.
     */
    fun getLowGScale(xlFs: Int, packed12: Boolean): Float? {
        val sens = getLowGSensitivityMg(xlFs) ?: return null
        return if (packed12) sens * PACKED12_MULTIPLIER else sens
    }

    /**
     * Returns the effective scaling factor for High-G samples (g per LSB or packed LSB).
     * Returns null if [hgFs] is unknown.
     */
    fun getHighGScale(hgFs: Int, packed12: Boolean): Float? {
        val sens = getHighGSensitivityG(hgFs) ?: return null
        return if (packed12) sens * PACKED12_MULTIPLIER else sens
    }

    /**
     * Returns the effective scaling factor for Gyroscope samples (mdps per LSB or packed LSB).
     * Returns null if [gyFs] is unknown.
     */
    fun getGyroScale(gyFs: Int, packed12: Boolean): Float? {
        val sens = getGyroSensitivityMdps(gyFs) ?: return null
        return if (packed12) sens * PACKED12_MULTIPLIER else sens
    }

    /**
     * Converts a raw High-G reading to acceleration in g.
     * Returns null if [hgFs] is unknown.
     */
    fun rawHighGToG(rawCount: Number, hgFs: Int, packed12: Boolean): Float? {
        val scale = getHighGScale(hgFs, packed12) ?: return null
        return rawCount.toFloat() * scale
    }

    /**
     * Converts a raw Low-G reading to acceleration in mg.
     * Returns null if [xlFs] is unknown.
     */
    fun rawLowGToMg(rawCount: Number, xlFs: Int, packed12: Boolean): Float? {
        val scale = getLowGScale(xlFs, packed12) ?: return null
        return rawCount.toFloat() * scale
    }

    /**
     * Converts a raw Gyro reading to angular velocity in mdps.
     * Returns null if [gyFs] is unknown.
     */
    fun rawGyroToMdps(rawCount: Number, gyFs: Int, packed12: Boolean): Float? {
        val scale = getGyroScale(gyFs, packed12) ?: return null
        return rawCount.toFloat() * scale
    }
}

