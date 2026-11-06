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

    /**
     * Maps a hardware Low-G full-scale code to its command/UI suffix string.
     */
    fun getLowGRangeSuffix(xlFs: Int): String? = when (xlFs) {
        0 -> "2g"
        1 -> "4g"
        2 -> "8g"
        3 -> "16g"
        else -> null
    }

    /**
     * Maps a hardware High-G full-scale code to its command/UI suffix string.
     */
    fun getHighGRangeSuffix(hgFs: Int): String? = when (hgFs) {
        0 -> "32g"
        1 -> "64g"
        2 -> "128g"
        3 -> "256g"
        4 -> "320g"
        else -> null
    }

    /**
     * Maps a hardware Gyroscope full-scale code to its command/UI suffix string.
     */
    fun getGyroRangeSuffix(gyFs: Int): String? = when (gyFs) {
        1 -> "250dps"
        2 -> "500dps"
        3 -> "1000dps"
        4 -> "2000dps"
        5 -> "4000dps"
        else -> null
    }

    /**
     * Reconstructs the nominal ODR suffix string from an acquisition period in nanoseconds.
     */
    fun parsePeriodNsToOdrSuffix(periodNs: Long): String? {
        if (periodNs <= 0L) return null
        val hz = 1_000_000_000.0 / periodNs
        return when {
            hz in 1.4..2.3 -> "1hz875"
            hz in 6.0..9.0 -> "7hz5"
            hz in 12.0..18.0 -> "15hz"
            hz in 24.0..36.0 -> "30hz"
            hz in 48.0..72.0 -> "60hz"
            hz in 96.0..144.0 -> "120hz"
            hz in 192.0..288.0 -> "240hz"
            hz in 384.0..576.0 -> "480hz"
            hz in 768.0..1152.0 -> "960hz"
            hz in 1536.0..2304.0 -> "1920hz"
            hz in 3072.0..4608.0 -> "3840hz"
            hz in 6144.0..9216.0 -> "7680hz"
            else -> null
        }
    }

    /**
     * Converts a rate suffix string (e.g. "1hz875", "120hz") to numeric Hz.
     */
    fun parseOdrSuffixToHz(suffix: String): Float = when (suffix.lowercase()) {
        "off" -> 0f
        "1hz875" -> 1.875f
        "7hz5" -> 7.5f
        "15hz" -> 15f
        "30hz" -> 30f
        "60hz" -> 60f
        "120hz" -> 120f
        "240hz" -> 240f
        "480hz" -> 480f
        "960hz" -> 960f
        "1920hz" -> 1920f
        "3840hz" -> 3840f
        "7680hz" -> 7680f
        else -> 0f
    }

    /**
     * Default ratio of full-scale range to flag near-saturation (60 g on ±64 g = 0.9375 = 93.75%).
     */
    const val DEFAULT_SATURATION_RATIO: Float = 60f / 64f

    /**
     * Parses the numeric full-scale acceleration limit in g from a range suffix string.
     * Supports High-G ("32g", "64g", "128g", "256g", "320g") and Low-G ("2g", "4g", "8g", "16g"),
     * as well as formatted strings like "±64 g".
     */
    fun parseRangeLimitG(rangeSuffix: String): Float? {
        val clean = rangeSuffix.replace("±", "").replace("g", "").replace("G", "").trim()
        return when (clean) {
            "2" -> 2f
            "4" -> 4f
            "8" -> 8f
            "16" -> 16f
            "32" -> 32f
            "64" -> 64f
            "128" -> 128f
            "256" -> 256f
            "320" -> 320f
            else -> clean.toFloatOrNull()
        }
    }

    /**
     * Returns the next higher range suffix for a given range suffix,
     * or null if the sensor is already at its maximum range.
     */
    fun getNextHigherRangeSuffix(rangeSuffix: String): String? {
        val clean = rangeSuffix.replace("±", "").replace("g", "").replace("G", "").trim().lowercase()
        return when (clean) {
            "2" -> "4g"
            "4" -> "8g"
            "8" -> "16g"
            "16" -> "32g"
            "32" -> "64g"
            "64" -> "128g"
            "128" -> "256g"
            "256" -> "320g"
            "320" -> null
            else -> null
        }
    }

    /**
     * Returns a human-friendly display label for a range suffix (e.g. "64g" -> "±64 g").
     */
    fun formatRangeLabel(rangeSuffix: String): String {
        val limit = parseRangeLimitG(rangeSuffix)
        return if (limit != null) {
            val intLimit = limit.toInt()
            if (limit == intLimit.toFloat()) "±$intLimit g" else "±$limit g"
        } else {
            rangeSuffix
        }
    }

    /**
     * Evaluates whether an observed peak acceleration (in g) is near or at the full-scale limit.
     *
     * @param peakG Observed peak acceleration (signed or absolute).
     * @param rangeSuffix Range suffix string (e.g. "64g", "32g", "128g", "256g", "320g", "16g", etc.).
     * @param thresholdRatio Ratio of full-scale limit to trigger warning (defaults to 0.9375 = 60/64).
     */
    fun checkRangeSaturation(
        peakG: Float,
        rangeSuffix: String,
        thresholdRatio: Float = DEFAULT_SATURATION_RATIO
    ): RangeSaturationResult {
        val limit = parseRangeLimitG(rangeSuffix) ?: return RangeSaturationResult(
            isNearSaturation = false,
            peakG = peakG,
            limitG = 0f,
            saturationThresholdG = 0f,
            ratio = 0f,
            currentRangeSuffix = rangeSuffix,
            currentRangeLabel = formatRangeLabel(rangeSuffix),
            recommendedRangeSuffix = null,
            recommendedRangeLabel = null
        )
        val absPeak = kotlin.math.abs(peakG)
        val threshold = limit * thresholdRatio
        val isNear = absPeak >= threshold
        val ratio = if (limit > 0f) absPeak / limit else 0f
        val nextSuffix = getNextHigherRangeSuffix(rangeSuffix)
        val nextLabel = nextSuffix?.let { formatRangeLabel(it) }

        return RangeSaturationResult(
            isNearSaturation = isNear,
            peakG = peakG,
            limitG = limit,
            saturationThresholdG = threshold,
            ratio = ratio,
            currentRangeSuffix = rangeSuffix,
            currentRangeLabel = formatRangeLabel(rangeSuffix),
            recommendedRangeSuffix = nextSuffix,
            recommendedRangeLabel = nextLabel
        )
    }
}

/**
 * Result data class describing whether an observed peak acceleration approaches or exceeds
 * the full-scale saturation limit of the active range.
 */
data class RangeSaturationResult(
    val isNearSaturation: Boolean,
    val peakG: Float,
    val limitG: Float,
    val saturationThresholdG: Float,
    val ratio: Float,
    val currentRangeSuffix: String,
    val currentRangeLabel: String,
    val recommendedRangeSuffix: String?,
    val recommendedRangeLabel: String?
)


