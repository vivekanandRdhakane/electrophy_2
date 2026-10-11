package com.example.electrophy_app

/**
 * Hysteresis and exponential smoothing controller for chart Y-axis auto-scaling.
 *
 * Prevents vertical flickering and jittering when points scroll off the screen
 * by providing asymmetric adaptation: expands promptly to accommodate new peaks
 * and contracts very smoothly and gradually when old peaks roll off.
 */
class SmoothAxisScaler(
    val minSpan: Float = 2.0f,
    val paddingPercent: Float = 0.12f,
    val expandAlpha: Float = 0.60f,
    val contractAlpha: Float = 0.02f
) {
    var minVal: Float = -100f
        private set
    var maxVal: Float = 100f
        private set

    private var isInitialized = false

    /**
     * Updates the smooth Y bounds given the raw data range [dataMin..dataMax].
     *
     * @param dataMin Minimum Y value observed across active visible datasets.
     * @param dataMax Maximum Y value observed across active visible datasets.
     * @param immediate If true, sets bounds directly without smoothing (e.g. upon unpause or reset).
     * @return Pair of (smoothMin, smoothMax) to apply to axisLeft.
     */
    fun update(dataMin: Float, dataMax: Float, immediate: Boolean = false): Pair<Float, Float> {
        if (dataMin.isNaN() || dataMax.isNaN() || dataMin.isInfinite() || dataMax.isInfinite()) {
            return minVal to maxVal
        }

        val rawSpan = (dataMax - dataMin).coerceAtLeast(minSpan)
        val targetMin = dataMin - rawSpan * paddingPercent
        val targetMax = dataMax + rawSpan * paddingPercent

        if (!isInitialized || immediate) {
            minVal = targetMin
            maxVal = targetMax
            isInitialized = true
        } else {
            // Asymmetric smoothing:
            // When target bounds expand beyond current visible range, adapt faster
            // When target bounds contract inside current range, adapt very slowly to eliminate jitter
            val alphaMin = if (targetMin < minVal) expandAlpha else contractAlpha
            val alphaMax = if (targetMax > maxVal) expandAlpha else contractAlpha

            minVal += (targetMin - minVal) * alphaMin
            maxVal += (targetMax - maxVal) * alphaMax
        }

        // Guarantee minimum visible span
        if (maxVal - minVal < minSpan) {
            val mid = (minVal + maxVal) / 2f
            minVal = mid - minSpan / 2f
            maxVal = mid + minSpan / 2f
        }

        return minVal to maxVal
    }

    fun reset() {
        isInitialized = false
    }
}
