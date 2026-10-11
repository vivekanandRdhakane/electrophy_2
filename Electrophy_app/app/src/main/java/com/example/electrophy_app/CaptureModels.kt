package com.example.electrophy_app

import java.util.UUID

/**
 * Quality status flags associated with a measurement capture.
 */
enum class CaptureQualityFlag {
    MISSING_BASELINE,      // Baseline could not be reliably determined before trigger
    CLIPPED,               // Sensor reading reached measurement dynamic range limits
    GAP_DETECTED,          // Timestamp discontinuity or packet drop occurred during capture
    INCOMPLETE_ENDPOINT    // Capture ended before signal returned to quiescent baseline
}

/**
 * Explicit, immutable representation of a captured measurement interval.
 *
 * Distinct from transient chart display history. Once created with [isFrozen] = true,
 * points and calculated metrics remain unchanged regardless of subsequent live chart scrolling.
 */
data class MeasurementCapture(
    val id: String = UUID.randomUUID().toString(),
    val startTimeMs: Float,
    val endTimeMs: Float,
    val triggerTimeMs: Float? = null,
    val baselineValue: Float = 0f,
    val points: List<AxisPoint>,
    val sourceMode: GraphMode,
    val odrHz: Float,
    val qualityFlags: Set<CaptureQualityFlag> = emptySet(),
    val isFrozen: Boolean = true
) {
    /**
     * Duration of the captured interval in milliseconds.
     */
    val durationMs: Float get() = (endTimeMs - startTimeMs).coerceAtLeast(0f)

    /**
     * Sample count of this capture.
     */
    val sampleCount: Int get() = points.size

    /**
     * Computes the integral ∫(signal_z - baseline) dt over the frozen capture points.
     */
    fun computeZImpulse(dt: Float = if (odrHz > 0f) 1f / odrHz else 0.01f): Float {
        if (points.isEmpty() || dt <= 0f) return 0f
        var integral = 0f
        for (p in points) {
            val net = p.z - baselineValue
            integral += net * dt
        }
        return integral
    }

    /**
     * Computes the absolute peak extrema across X, Y, and Z axes.
     */
    fun computePeakAbs(): Triple<Float, Float, Float> {
        var maxX = 0f
        var maxY = 0f
        var maxZ = 0f
        for (p in points) {
            if (kotlin.math.abs(p.x) > kotlin.math.abs(maxX)) maxX = p.x
            if (kotlin.math.abs(p.y) > kotlin.math.abs(maxY)) maxY = p.y
            if (kotlin.math.abs(p.z) > kotlin.math.abs(maxZ)) maxZ = p.z
        }
        return Triple(maxX, maxY, maxZ)
    }
}

/**
 * Duration-bounded ring buffer for high-rate sensor streaming.
 *
 * Retains samples to cover at least [maxDurationMs] of continuous history
 * (e.g., 35 seconds to guarantee a complete 30-second viewport with pre-history headroom).
 * Enforces [maxPoints] as a safety limit against memory exhaustion at extreme rates.
 */
class DurationBoundedBuffer(
    val maxDurationMs: Float = 35_000f,
    val maxPoints: Int = 50_000
) {
    private val buffer = ArrayList<AxisPoint>()

    @Synchronized
    fun append(newPoints: List<AxisPoint>) {
        if (newPoints.isEmpty()) return
        buffer.addAll(newPoints)
        trim()
    }

    @Synchronized
    fun append(point: AxisPoint) {
        buffer.add(point)
        trim()
    }

    private fun trim() {
        if (buffer.isEmpty()) return

        val latestTime = buffer.last().time
        val cutoffTime = latestTime - maxDurationMs

        // If buffer spans more than maxDurationMs, find index of oldest point >= cutoffTime
        if (buffer.first().time < cutoffTime) {
            var low = 0
            var high = buffer.size - 1
            var cutoffIndex = -1

            while (low <= high) {
                val mid = (low + high) ushr 1
                if (buffer[mid].time < cutoffTime) {
                    cutoffIndex = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }

            // Keep the last sample at or before cutoffTime as an anchor, so the retained span
            // (latestTime - buffer.first().time) is guaranteed to be >= maxDurationMs.
            if (cutoffIndex > 0) {
                buffer.subList(0, cutoffIndex).clear()
            }
        }

        // Safety cap against extreme sample counts
        if (buffer.size > maxPoints) {
            buffer.subList(0, buffer.size - maxPoints).clear()
        }
    }

    @Synchronized
    fun getPoints(): List<AxisPoint> = ArrayList(buffer)

    @Synchronized
    fun size(): Int = buffer.size

    @Synchronized
    fun isEmpty(): Boolean = buffer.isEmpty()

    @Synchronized
    fun isNotEmpty(): Boolean = buffer.isNotEmpty()

    @Synchronized
    fun firstOrNull(): AxisPoint? = buffer.firstOrNull()

    @Synchronized
    fun lastOrNull(): AxisPoint? = buffer.lastOrNull()

    @Synchronized
    fun durationMs(): Float {
        if (buffer.size < 2) return 0f
        return (buffer.last().time - buffer.first().time).coerceAtLeast(0f)
    }

    @Synchronized
    fun clear() {
        buffer.clear()
    }

    /**
     * Extracts a frozen [MeasurementCapture] from the current buffer within [startT..endT].
     */
    @Synchronized
    fun captureInterval(
        startT: Float,
        endT: Float,
        triggerTimeMs: Float? = null,
        sourceMode: GraphMode = GraphMode.LOW_G,
        odrHz: Float = 120f,
        baselineSampleCount: Int = 10
    ): MeasurementCapture {
        val slice = buffer.filter { it.time in startT..endT }
        val quality = mutableSetOf<CaptureQualityFlag>()

        if (slice.isEmpty()) {
            quality.add(CaptureQualityFlag.INCOMPLETE_ENDPOINT)
            return MeasurementCapture(
                startTimeMs = startT,
                endTimeMs = endT,
                triggerTimeMs = triggerTimeMs,
                baselineValue = 0f,
                points = emptyList(),
                sourceMode = sourceMode,
                odrHz = odrHz,
                qualityFlags = quality,
                isFrozen = true
            )
        }

        // Compute baseline from the initial pre-event samples of the slice
        val baselineSamples = slice.take(baselineSampleCount)
        val baseline = if (baselineSamples.isNotEmpty()) {
            baselineSamples.map { it.z }.average().toFloat()
        } else {
            quality.add(CaptureQualityFlag.MISSING_BASELINE)
            0f
        }

        return MeasurementCapture(
            startTimeMs = slice.first().time,
            endTimeMs = slice.last().time,
            triggerTimeMs = triggerTimeMs,
            baselineValue = baseline,
            points = ArrayList(slice),
            sourceMode = sourceMode,
            odrHz = odrHz,
            qualityFlags = quality,
            isFrozen = true
        )
    }
}
