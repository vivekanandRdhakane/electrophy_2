package com.example.electrophy_app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance and regression tests for Median Filter (Task E005).
 *
 * Verifies bounds safety, odd window normalization, edge-clipped population handling,
 * and mathematical accuracy on constant, ramp, and impulse signals.
 */
class MedianFilterTest {

    private fun makePoints(vararg values: Float): List<ScalarPoint> {
        return values.mapIndexed { idx, v -> ScalarPoint(idx * 10f, v) }
    }

    @Test
    fun evenWindowSize_doesNotCrash_reproducesAuditProbe() {
        // Original bug: windowSize = 12 threw ArrayIndexOutOfBoundsException
        val points = makePoints(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 11f, 12f, 13f, 14f, 15f)
        val filtered = MathEngine.applyFilterLab(
            points = points,
            filterType = FilterLabType.MEDIAN,
            windowSize = 12
        )
        assertEquals(points.size, filtered.size)
        // With window normalized to 13 (half=6), middle sample points[7] (value=8) has window [2..14], median is 8.0
        assertEquals(8f, filtered[7].value, 1e-4f)
    }

    @Test
    fun everySelectableWindowSize_runsWithoutCrash() {
        val points = (1..60).map { ScalarPoint(it * 10f, it.toFloat()) }
        for (w in 1..65) {
            val filtered = MathEngine.applyFilterLab(
                points = points,
                filterType = FilterLabType.MEDIAN,
                windowSize = w
            )
            assertEquals(points.size, filtered.size)
        }
    }

    @Test
    fun emptyAndShortInput_handledSafely() {
        val empty = MathEngine.applyFilterLab(emptyList(), FilterLabType.MEDIAN, windowSize = 11)
        assertTrue(empty.isEmpty())

        val single = makePoints(42f)
        val singleOut = MathEngine.applyFilterLab(single, FilterLabType.MEDIAN, windowSize = 11)
        assertEquals(1, singleOut.size)
        assertEquals(42f, singleOut[0].value, 1e-4f)

        val pair = makePoints(10f, 20f)
        val pairOut = MathEngine.applyFilterLab(pair, FilterLabType.MEDIAN, windowSize = 11)
        assertEquals(2, pairOut.size)
        // For len=2, median is average (10 + 20) / 2 = 15
        assertEquals(15f, pairOut[0].value, 1e-4f)
        assertEquals(15f, pairOut[1].value, 1e-4f)
    }

    @Test
    fun largerThanInputWindows_handledSafely() {
        val points = makePoints(10f, 50f, 20f)
        val filtered = MathEngine.applyFilterLab(points, FilterLabType.MEDIAN, windowSize = 31)
        assertEquals(3, filtered.size)
        // At index 1, window covers all 3 samples: [10, 50, 20] -> sorted [10, 20, 50] -> median is 20
        assertEquals(20f, filtered[1].value, 1e-4f)
    }

    @Test
    fun constantSignal_preservesValues() {
        val points = makePoints(5f, 5f, 5f, 5f, 5f, 5f, 5f)
        val filtered = MathEngine.applyFilterLab(points, FilterLabType.MEDIAN, windowSize = 5)
        for (pt in filtered) {
            assertEquals(5f, pt.value, 1e-4f)
        }
    }

    @Test
    fun rampSignal_computesExpectedMedians() {
        // Linear ramp [1, 2, 3, 4, 5, 6, 7] with window 3 (half = 1)
        val points = makePoints(1f, 2f, 3f, 4f, 5f, 6f, 7f)
        val filtered = MathEngine.applyFilterLab(points, FilterLabType.MEDIAN, windowSize = 3)

        // At index 0 (len=2: [1, 2]): average is 1.5
        assertEquals(1.5f, filtered[0].value, 1e-4f)
        // At index 1 (len=3: [1, 2, 3]): median is 2.0
        assertEquals(2f, filtered[1].value, 1e-4f)
        // At index 2 (len=3: [2, 3, 4]): median is 3.0
        assertEquals(3f, filtered[2].value, 1e-4f)
        // At index 5 (len=3: [5, 6, 7]): median is 6.0
        assertEquals(6f, filtered[5].value, 1e-4f)
        // At index 6 (len=2: [6, 7]): average is 6.5
        assertEquals(6.5f, filtered[6].value, 1e-4f)
    }

    @Test
    fun isolatedImpulse_isCompletelyRejected() {
        // Impulse in middle of zeros
        val points = makePoints(0f, 0f, 0f, 100f, 0f, 0f, 0f)
        val filtered = MathEngine.applyFilterLab(points, FilterLabType.MEDIAN, windowSize = 5)

        // At index 3 (impulse): window [0, 0, 100, 0, 0] -> sorted [0, 0, 0, 0, 100] -> median is 0.0
        assertEquals(0f, filtered[3].value, 1e-4f)
    }

    @Test
    fun windowNormalization_effectiveSizeAgreement() {
        // Even sizes round up to odd
        assertEquals(3, MathEngine.normalizeMedianWindow(2))
        assertEquals(3, MathEngine.normalizeMedianWindow(3))
        assertEquals(5, MathEngine.normalizeMedianWindow(4))
        assertEquals(11, MathEngine.normalizeMedianWindow(10))
        assertEquals(13, MathEngine.normalizeMedianWindow(12))
        assertEquals(51, MathEngine.normalizeMedianWindow(50))
        assertEquals(51, MathEngine.normalizeMedianWindow(51))
        assertEquals(51, MathEngine.normalizeMedianWindow(100))
    }
}

