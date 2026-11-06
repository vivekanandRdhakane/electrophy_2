package com.example.electrophy_app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Acceptance and regression test suite for Signal Comparator (Task E006).
 *
 * Verifies cross-correlation lag detection, polarity preservation for inverted signals,
 * correct user-language delay sign, constant-signal safety, and phase gating.
 */
class ComparatorTest {

    private fun makeSine(
        n: Int,
        freqHz: Float,
        odrHz: Float,
        delaySamples: Int = 0,
        inverted: Boolean = false,
        amplitude: Float = 1.0f
    ): List<ScalarPoint> {
        val dtMs = 1000f / odrHz
        val sign = if (inverted) -1.0f else 1.0f
        return (0 until n).map { i ->
            val t = (i - delaySamples) / odrHz
            val v = sign * amplitude * sin(2.0 * PI * freqHz * t).toFloat()
            ScalarPoint(i * dtMs, v)
        }
    }

    private fun makeBroadband(
        n: Int,
        odrHz: Float,
        seed: Long = 12345L,
        delaySamples: Int = 0,
        inverted: Boolean = false
    ): List<ScalarPoint> {
        val dtMs = 1000f / odrHz
        val rng = Random(seed)
        // Generate deterministic broadband sequence with distinct features
        val raw = (0 until (n + delaySamples + 10)).map {
            rng.nextFloat() * 2f - 1f
        }
        val sign = if (inverted) -1.0f else 1.0f
        return (0 until n).map { i ->
            val idx = i - delaySamples
            val v = if (idx in raw.indices) sign * raw[idx] else 0f
            ScalarPoint(i * dtMs, v)
        }
    }

    @Test
    fun testIdenticalSignals_zeroLag_unitCorrelation() {
        // 10 Hz sine at 240 Hz ODR, 240 samples (1 second)
        val sigA = makeSine(240, 10f, 240f)
        val sigB = makeSine(240, 10f, 240f)

        val res = MathEngine.compareSignals(sigA, sigB, 240f)

        assertEquals(1.0f, res.pearsonR, 1e-4f)
        assertEquals(1.0f, res.peakCrossCorrelation, 1e-4f)
        assertEquals(0.0f, res.optimalLagMs, 1e-4f)
        assertNotNull(res.estimatedPhaseDeg)
        assertEquals(0.0f, res.estimatedPhaseDeg!!, 1.0f)
    }

    @Test
    fun testDelayedSineSignal_reproducesAuditProbe_reportsCorrectLagAndPhase() {
        // Audit probe: 10 Hz sine delayed by 6 samples at 240 Hz
        // Sample period = 1000 / 240 = 4.1667 ms.
        // 6 samples * 4.1667 ms = 25.0 ms delay.
        // Dominant period = 100 ms. 25 ms delay = 90° phase shift.
        val odr = 240f
        val sigA = makeSine(240, 10f, odr, delaySamples = 0)
        val sigB = makeSine(240, 10f, odr, delaySamples = 6) // B lags A by 6 samples (25 ms)

        val res = MathEngine.compareSignals(sigA, sigB, odr)

        // Previous broken code initialized bestCorr = -2f and returned lag = 0 ms, peak = -1f.
        assertEquals(25.0f, res.optimalLagMs, 0.1f)
        assertEquals(1.0f, res.peakCrossCorrelation, 0.02f)
        assertNotNull(res.estimatedPhaseDeg)
        assertEquals(90.0f, res.estimatedPhaseDeg!!, 4.0f)
    }

    @Test
    fun testLeadingSineSignal_reportsNegativeLagAndPhase() {
        // 10 Hz sine where B leads A by 6 samples (-25 ms)
        val odr = 240f
        val sigA = makeSine(240, 10f, odr, delaySamples = 0)
        val sigB = makeSine(240, 10f, odr, delaySamples = -6)

        val res = MathEngine.compareSignals(sigA, sigB, odr)

        assertEquals(-25.0f, res.optimalLagMs, 0.1f)
        assertEquals(1.0f, res.peakCrossCorrelation, 0.02f)
        assertNotNull(res.estimatedPhaseDeg)
        assertEquals(-90.0f, res.estimatedPhaseDeg!!, 4.0f)
    }

    @Test
    fun testInvertedDelayedSignal_preservesNegativePolarity() {
        // Inverted broadband signal delayed by 10 samples at 200 Hz (50 ms delay)
        val odr = 200f
        val sigA = makeBroadband(120, odr, seed = 42L, delaySamples = 0, inverted = false)
        val sigB = makeBroadband(120, odr, seed = 42L, delaySamples = 10, inverted = true)

        val res = MathEngine.compareSignals(sigA, sigB, odr)

        assertEquals(50.0f, res.optimalLagMs, 0.1f)
        // Must preserve negative polarity: -1.0, not coerced to +1.0
        assertEquals(-1.0f, res.peakCrossCorrelation, 0.05f)
    }

    @Test
    fun testNonperiodicBroadbandSignal_delayed_noPhaseReported() {
        // Deterministic broadband noise delayed by 5 samples at 500 Hz (10 ms delay)
        val odr = 500f
        val sigA = makeBroadband(150, odr, seed = 999L, delaySamples = 0)
        val sigB = makeBroadband(150, odr, seed = 999L, delaySamples = 5)

        val res = MathEngine.compareSignals(sigA, sigB, odr)

        assertEquals(10.0f, res.optimalLagMs, 0.1f)
        assertEquals(1.0f, res.peakCrossCorrelation, 0.05f)
        // Non-periodic signal must NOT report an arbitrary phase shift
        assertNull(res.estimatedPhaseDeg)
    }

    @Test
    fun testConstantSignals_returnUnavailable() {
        val dtMs = 10f
        val constA = (0 until 100).map { ScalarPoint(it * dtMs, 5.0f) }
        val constB = (0 until 100).map { ScalarPoint(it * dtMs, 10.0f) }

        val res = MathEngine.compareSignals(constA, constB, 100f)

        assertEquals(0.0f, res.pearsonR, 1e-6f)
        assertEquals(0.0f, res.peakCrossCorrelation, 1e-6f)
        assertEquals(0.0f, res.optimalLagMs, 1e-6f)
        assertNull(res.estimatedPhaseDeg)
    }

    @Test
    fun testShortAndEmptyInput_handledSafely() {
        val emptyRes = MathEngine.compareSignals(emptyList(), emptyList(), 100f)
        assertTrue(emptyRes.alignedA.isEmpty())
        assertTrue(emptyRes.alignedB.isEmpty())
        assertEquals(0.0f, emptyRes.pearsonR, 1e-6f)
        assertEquals(0.0f, emptyRes.optimalLagMs, 1e-6f)
        assertNull(emptyRes.estimatedPhaseDeg)

        val shortA = (0 until 5).map { ScalarPoint(it * 10f, it.toFloat()) }
        val shortB = (0 until 5).map { ScalarPoint(it * 10f, (it * 2).toFloat()) }
        val shortRes = MathEngine.compareSignals(shortA, shortB, 100f)
        assertTrue(shortRes.alignedA.isEmpty())
    }

    @Test
    fun testNoisySignals_identifiesTrueLag() {
        val odr = 240f
        val rng = Random(777L)
        val baseA = makeSine(240, 5f, odr, delaySamples = 0)
        val baseB = makeSine(240, 5f, odr, delaySamples = 8) // ~33.33 ms delay

        // Add 10% noise to both signals
        val noisyA = baseA.map { ScalarPoint(it.timeMs, it.value + (rng.nextFloat() - 0.5f) * 0.2f) }
        val noisyB = baseB.map { ScalarPoint(it.timeMs, it.value + (rng.nextFloat() - 0.5f) * 0.2f) }

        val res = MathEngine.compareSignals(noisyA, noisyB, odr)

        assertEquals(8 * (1000f / 240f), res.optimalLagMs, 0.5f)
        assertTrue(res.peakCrossCorrelation > 0.85f)
    }
}

