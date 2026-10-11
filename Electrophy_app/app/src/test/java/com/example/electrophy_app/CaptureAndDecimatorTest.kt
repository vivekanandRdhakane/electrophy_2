package com.example.electrophy_app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Acceptance and regression test suite for Task E011.
 *
 * Verifies duration-aware buffer retention, peak-preserving chart decimation,
 * and immutable frozen measurement captures.
 */
class CaptureAndDecimatorTest {

    @Test
    fun testDurationBoundedBuffer_retainsAtLeast35Seconds_atMultipleODRs() {
        val odrList = listOf(50, 120, 240, 480, 1000)

        for (odr in odrList) {
            val buffer = DurationBoundedBuffer(maxDurationMs = 35_000f, maxPoints = 50_000)
            val dtMs = 1000f / odr
            val totalSeconds = 40 // Stream 40 seconds of data

            val batchSize = odr // 1 second batches
            var currentTimeMs = 0f

            for (sec in 0 until totalSeconds) {
                val batch = (0 until batchSize).map {
                    currentTimeMs += dtMs
                    AxisPoint(x = 10f, y = 20f, z = 30f, time = currentTimeMs)
                }
                buffer.append(batch)
            }

            // The buffer must retain at least 35,000 ms of history
            val duration = buffer.durationMs()
            assertTrue("Expected duration >= 35,000ms for ODR $odr, got $duration", duration >= 35_000f)

            // A 30-second window (30,000 ms) must fit fully inside the retained buffer
            val points = buffer.getPoints()
            val latestTime = points.last().time
            val thirtySecsAgo = latestTime - 30_000f
            val oldestTime = points.first().time

            assertTrue("Oldest time ($oldestTime) must be <= 30s ago ($thirtySecsAgo)", oldestTime <= thirtySecsAgo)
        }
    }

    @Test
    fun testDurationBoundedBuffer_respectsSafetyMaxPointsCap() {
        val maxCap = 5_000
        val buffer = DurationBoundedBuffer(maxDurationMs = 100_000f, maxPoints = maxCap)

        // Add 10,000 points
        val points = (1..10_000).map {
            AxisPoint(x = 0f, y = 0f, z = 0f, time = it.toFloat())
        }
        buffer.append(points)

        assertEquals(maxCap, buffer.size())
        assertEquals(10_000f, buffer.lastOrNull()?.time)
        assertEquals(5_001f, buffer.firstOrNull()?.time)
    }

    @Test
    fun testChartDecimator_preservesIsolatedPeakImpulse() {
        // Create 10,000 flat points (at 1000 Hz, 10 seconds of data)
        val n = 10_000
        val points = ArrayList<AxisPoint>(n)
        for (i in 0 until n) {
            // An isolated spike of 50.0g at sample 5,432
            val zVal = if (i == 5432) 50.0f else 1.0f
            points.add(AxisPoint(x = 0f, y = 0f, z = zVal, time = i.toFloat()))
        }

        // Decimate down to at most 1,200 points
        val target = 1200
        val decimated = ChartDecimator.decimate(points, maxTargetPoints = target)

        assertTrue("Decimated size ${decimated.size} must be <= $target", decimated.size <= target)

        // The isolated 50g spike MUST be preserved in the decimated list!
        val maxZ = decimated.maxOf { it.z }
        assertEquals("Isolated 50g peak must be preserved in decimated output", 50.0f, maxZ, 0.001f)

        // Temporal order must be strictly monotonically increasing
        for (i in 1 until decimated.size) {
            assertTrue("Decimated points must be chronological", decimated[i].time > decimated[i - 1].time)
        }

        // First and last points must be preserved
        assertEquals(points.first().time, decimated.first().time, 0.001f)
        assertEquals(points.last().time, decimated.last().time, 0.001f)
    }

    @Test
    fun testChartDecimator_smallListsReturnedUntouched() {
        val small = (1..500).map { AxisPoint(1f, 2f, 3f, it.toFloat()) }
        val result = ChartDecimator.decimate(small, maxTargetPoints = 1200)
        assertEquals(small.size, result.size)
        assertEquals(small, result)
    }

    @Test
    fun testMeasurementCapture_frozenImmutability_resistsBufferEviction() {
        val buffer = DurationBoundedBuffer(maxDurationMs = 5_000f, maxPoints = 10_000)

        // Stream initial 4 seconds of baseline data + an impulse peak at t = 2000ms
        val initialPoints = (0 until 400).map { i ->
            val t = i * 10f // 0ms to 3990ms (100 Hz)
            val z = if (t in 2000f..2100f) 25f else 1f // Baseline = 1g, Peak = 25g
            AxisPoint(x = 0f, y = 0f, z = z, time = t)
        }
        buffer.append(initialPoints)

        // Capture the event from 1500ms to 2500ms
        val capture = buffer.captureInterval(
            startT = 1500f,
            endT = 2500f,
            triggerTimeMs = 2000f,
            sourceMode = GraphMode.LOW_G,
            odrHz = 100f,
            baselineSampleCount = 10
        )

        assertTrue(capture.isFrozen)
        assertEquals(1.0f, capture.baselineValue, 0.01f)
        val initialCaptureCount = capture.sampleCount
        val initialImpulse = capture.computeZImpulse(dt = 0.01f)
        assertTrue("Impulse should be positive", initialImpulse > 0f)

        // Now stream 20 more seconds of new data into the buffer, completely evicting the captured time interval!
        val laterPoints = (400 until 2400).map { i ->
            val t = i * 10f // 4000ms to 23990ms
            AxisPoint(x = 0f, y = 0f, z = 5f, time = t)
        }
        buffer.append(laterPoints)

        // Verify the original points were evicted from the sliding buffer
        val oldestInBuffer = buffer.firstOrNull()?.time ?: 0f
        assertTrue("Original capture time (1500ms) should be evicted from buffer (oldest = $oldestInBuffer)", oldestInBuffer > 2500f)

        // BUT the frozen capture MUST remain completely unchanged!
        assertEquals("Frozen sample count must not change", initialCaptureCount, capture.sampleCount)
        assertEquals("Frozen baseline must not change", 1.0f, capture.baselineValue, 0.01f)
        assertEquals("Frozen impulse integral must not change", initialImpulse, capture.computeZImpulse(dt = 0.01f), 1e-4f)
        assertFalse(capture.qualityFlags.contains(CaptureQualityFlag.MISSING_BASELINE))
    }

    @Test
    fun testMeasurementCapture_qualityFlags() {
        val buffer = DurationBoundedBuffer(maxDurationMs = 10_000f)
        // Empty capture should be flagged as INCOMPLETE_ENDPOINT
        val emptyCapture = buffer.captureInterval(100f, 200f)
        assertTrue(emptyCapture.qualityFlags.contains(CaptureQualityFlag.INCOMPLETE_ENDPOINT))
    }

    @Test
    fun testSmoothAxisScaler_initializationAndPadding() {
        val scaler = SmoothAxisScaler(minSpan = 2.0f, paddingPercent = 0.10f)
        val (minVal, maxVal) = scaler.update(0f, 10f)

        // 10% of span 10 = 1.0 padding
        assertEquals(-1.0f, minVal, 0.01f)
        assertEquals(11.0f, maxVal, 0.01f)
    }

    @Test
    fun testSmoothAxisScaler_asymmetricAdaptation_preventsFlickerOnPointExit() {
        val scaler = SmoothAxisScaler(
            minSpan = 2.0f,
            paddingPercent = 0.10f,
            expandAlpha = 0.60f,
            contractAlpha = 0.02f
        )

        // Baseline: signal fluctuates between 0 and 100
        val (initMin, initMax) = scaler.update(0f, 100f)
        assertEquals(-10f, initMin, 0.01f)
        assertEquals(110f, initMax, 0.01f)

        // Point scrolls off the left screen edge: raw max suddenly drops from 100 to 20
        // Without smoothing, max would instantly jump to 22, causing a violent 80% vertical scale jump (flicker)
        val (step1Min, step1Max) = scaler.update(0f, 20f)

        // With smooth scaling at contractAlpha = 0.02, maxVal barely moves on single frame:
        // Target max = 20 + 2 = 22. delta = 22 - 110 = -88. change = -88 * 0.02 = -1.76.
        // New max ≈ 108.24 (less than 2% change!)
        assertTrue("Max should contract slowly, preventing curve vibration", step1Max > 105f)
        assertEquals(110f + (22f - 110f) * 0.02f, step1Max, 0.05f)

        // Conversely, when a sharp peak arrives (0 to 200), expansion is fast (expandAlpha = 0.60)
        val (expandMin, expandMax) = scaler.update(0f, 200f)
        // Target max = 200 + 20 = 220. delta = 220 - 108.24 = 111.76. change = 111.76 * 0.60 = 67.05.
        // New max ≈ 175.3
        assertTrue("Expansion should quickly track the new spike", expandMax > 170f)
    }

    @Test
    fun testSmoothAxisScaler_immediateMode_and_reset() {
        val scaler = SmoothAxisScaler(minSpan = 2.0f, paddingPercent = 0.10f)
        scaler.update(0f, 100f)

        // In immediate mode (e.g. paused scrub), bounds snap directly without lag
        val (snapMin, snapMax) = scaler.update(10f, 20f, immediate = true)
        assertEquals(9.0f, snapMin, 0.01f)
        assertEquals(21.0f, snapMax, 0.01f)

        // Reset clears state
        scaler.reset()
        val (freshMin, freshMax) = scaler.update(50f, 60f)
        assertEquals(49.0f, freshMin, 0.01f)
        assertEquals(61.0f, freshMax, 0.01f)
    }
}

