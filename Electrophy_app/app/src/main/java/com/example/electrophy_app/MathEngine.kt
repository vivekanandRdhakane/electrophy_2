package com.example.electrophy_app

import kotlin.math.*

enum class SignalSource(
    val label: String,
    val shortLabel: String,
    val unit: String,
    val requiredMode: GraphMode
) {
    LOW_G_X("Accel X", "aX", "mg", GraphMode.LOW_G),
    LOW_G_Y("Accel Y", "aY", "mg", GraphMode.LOW_G),
    LOW_G_Z("Accel Z", "aZ", "mg", GraphMode.LOW_G),
    LOW_G_MAG("Accel |Mag|", "|a|", "mg", GraphMode.LOW_G),
    HIGH_G_X("High-G X", "hgX", "g", GraphMode.HIGH_G),
    HIGH_G_Y("High-G Y", "hgY", "g", GraphMode.HIGH_G),
    HIGH_G_Z("High-G Z", "hgZ", "g", GraphMode.HIGH_G),
    HIGH_G_MAG("High-G |Mag|", "|hg|", "g", GraphMode.HIGH_G),
    GYRO_X("Gyro X", "gX", "mdps", GraphMode.GYRO),
    GYRO_Y("Gyro Y", "gY", "mdps", GraphMode.GYRO),
    GYRO_Z("Gyro Z", "gZ", "mdps", GraphMode.GYRO),
    GYRO_MAG("Gyro |Mag|", "|g|", "mdps", GraphMode.GYRO),
}

enum class MathOperation(val label: String, val icon: String) {
    INTEGRATION("Numerical Integration", "📈"),
    DERIVATIVE("Derivative (Jerk)", "📉"),
    FFT("FFT Spectrum", "🌈"),
    SPECTROGRAM("Spectrogram", "🎨"),
    STATISTICS("Statistics & Distribution", "📊"),
    AUTOCORRELATION("Autocorrelation", "🔁"),
    FILTER_LAB("Digital Filter Lab", "🎛️"),
    COMPARATOR("Signal Comparator", "⚡"),
}

enum class WindowFunction(val label: String) {
    HANNING("Hanning"),
    HAMMING("Hamming"),
    BLACKMAN("Blackman"),
    RECTANGULAR("Rectangular")
}

enum class FilterLabType(val label: String) {
    LOW_PASS("Low-Pass (EMA)"),
    HIGH_PASS("High-Pass"),
    BAND_PASS("Band-Pass"),
    MOVING_AVG("Moving Average"),
    MEDIAN("Median (De-spike)")
}

enum class SpectrogramPalette(val label: String) {
    VIRIDIS("Viridis"),
    MAGMA("Magma"),
    INFERNO("Inferno"),
    CYBERPUNK("Cyberpunk")
}

data class ScalarPoint(val timeMs: Float, val value: Float)

data class FftBin(val frequencyHz: Float, val magnitude: Float, val magnitudeDb: Float)

data class FftResult(
    val bins: List<FftBin>,
    val dominantFreqHz: Float,
    val dominantMag: Float,
    val secondFreqHz: Float,
    val secondMag: Float,
    val spectralCentroidHz: Float,
    val freqResolutionHz: Float
)

data class SpectrogramSlice(
    val timeMs: Float,
    val magnitudes: FloatArray, // normalized 0..1 or raw magnitudes for N/2 bins
    val peakFreqHz: Float
)

data class SignalStats(
    val count: Int,
    val mean: Float,
    val stdDev: Float,
    val variance: Float,
    val rms: Float,
    val min: Float,
    val max: Float,
    val peakToPeak: Float,
    val skewness: Float,
    val kurtosis: Float,
    val histogramCounts: IntArray,
    val binCenters: FloatArray,
    val binWidth: Float
)

data class AutocorrelationResult(
    val lagsMs: FloatArray,
    val correlations: FloatArray,
    val detectedPeriodSec: Float?,
    val detectedFreqHz: Float?,
    val periodicityConfidence: Float,
    val firstZeroCrossingMs: Float?
)

data class ComparatorResult(
    val alignedA: List<ScalarPoint>,
    val alignedB: List<ScalarPoint>,
    val pearsonR: Float,
    val optimalLagMs: Float,
    val peakCrossCorrelation: Float,
    val estimatedPhaseDeg: Float?
)

object MathEngine {

    /**
     * Extracts a 1D scalar signal from the multi-channel ChartData based on the selected SignalSource.
     */
    fun extractSignal(chartData: ChartData, source: SignalSource): List<ScalarPoint> {
        val rawList = when (source) {
            SignalSource.LOW_G_X, SignalSource.LOW_G_Y, SignalSource.LOW_G_Z, SignalSource.LOW_G_MAG -> chartData.lowG
            SignalSource.HIGH_G_X, SignalSource.HIGH_G_Y, SignalSource.HIGH_G_Z, SignalSource.HIGH_G_MAG -> chartData.highG
            SignalSource.GYRO_X, SignalSource.GYRO_Y, SignalSource.GYRO_Z, SignalSource.GYRO_MAG -> chartData.gyro
        }
        return rawList.map { pt ->
            val v = when (source) {
                SignalSource.LOW_G_X, SignalSource.HIGH_G_X, SignalSource.GYRO_X -> pt.x
                SignalSource.LOW_G_Y, SignalSource.HIGH_G_Y, SignalSource.GYRO_Y -> pt.y
                SignalSource.LOW_G_Z, SignalSource.HIGH_G_Z, SignalSource.GYRO_Z -> pt.z
                SignalSource.LOW_G_MAG, SignalSource.HIGH_G_MAG, SignalSource.GYRO_MAG ->
                    sqrt(pt.x * pt.x + pt.y * pt.y + pt.z * pt.z)
            }
            ScalarPoint(pt.time, v)
        }
    }

    /**
     * Resolves the required GraphMode when one or two SignalSources are active.
     */
    fun resolveRequiredGraphMode(sourceA: SignalSource, sourceB: SignalSource? = null): GraphMode {
        if (sourceB == null || sourceA.requiredMode == sourceB.requiredMode) {
            return sourceA.requiredMode
        }
        val modes = setOf(sourceA.requiredMode, sourceB.requiredMode)
        return if (modes == setOf(GraphMode.LOW_G, GraphMode.HIGH_G)) {
            GraphMode.BOTH_ACC
        } else {
            GraphMode.ALL
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 1. NUMERICAL INTEGRATION (Trapezoidal Rule)
    // ─────────────────────────────────────────────────────────────

    fun integrate(
        points: List<ScalarPoint>,
        odrHz: Float,
        removeDc: Boolean = true,
        secondOrder: Boolean = false
    ): List<ScalarPoint> {
        if (points.size < 2) return points.map { ScalarPoint(it.timeMs, 0f) }
        val dt = 1f / odrHz.coerceAtLeast(1f)
        val mean = if (removeDc) points.map { it.value.toDouble() }.average().toFloat() else 0f

        val firstIntegral = ArrayList<ScalarPoint>(points.size)
        var acc = 0f
        firstIntegral.add(ScalarPoint(points[0].timeMs, 0f))
        for (i in 1 until points.size) {
            val v0 = points[i - 1].value - mean
            val v1 = points[i].value - mean
            acc += 0.5f * (v0 + v1) * dt
            firstIntegral.add(ScalarPoint(points[i].timeMs, acc))
        }

        if (!secondOrder) return firstIntegral

        // Optional 2nd-order integration (e.g. Accel -> Velocity -> Displacement)
        val meanVel = if (removeDc) firstIntegral.map { it.value.toDouble() }.average().toFloat() else 0f
        val secondIntegral = ArrayList<ScalarPoint>(points.size)
        var acc2 = 0f
        secondIntegral.add(ScalarPoint(points[0].timeMs, 0f))
        for (i in 1 until firstIntegral.size) {
            val v0 = firstIntegral[i - 1].value - meanVel
            val v1 = firstIntegral[i].value - meanVel
            acc2 += 0.5f * (v0 + v1) * dt
            secondIntegral.add(ScalarPoint(firstIntegral[i].timeMs, acc2))
        }
        return secondIntegral
    }

    // ─────────────────────────────────────────────────────────────
    // 2. NUMERICAL DERIVATIVE (Central Finite Difference + Smoothing)
    // ─────────────────────────────────────────────────────────────

    fun derivative(
        points: List<ScalarPoint>,
        odrHz: Float,
        stencilHalfWidth: Int = 1,
        secondOrder: Boolean = false
    ): List<ScalarPoint> {
        if (points.size < 3) return points.map { ScalarPoint(it.timeMs, 0f) }
        val dt = 1f / odrHz.coerceAtLeast(1f)
        val firstDeriv = computeSingleDerivative(points, dt, stencilHalfWidth.coerceIn(1, 5))
        return if (!secondOrder) {
            firstDeriv
        } else {
            computeSingleDerivative(firstDeriv, dt, stencilHalfWidth.coerceIn(1, 5))
        }
    }

    private fun computeSingleDerivative(
        points: List<ScalarPoint>,
        dt: Float,
        halfWidth: Int
    ): List<ScalarPoint> {
        val n = points.size
        val out = ArrayList<ScalarPoint>(n)
        for (i in 0 until n) {
            val k = minOf(halfWidth, i, n - 1 - i)
            val deriv = if (k >= 1) {
                (points[i + k].value - points[i - k].value) / (2f * k * dt)
            } else if (i < n - 1) {
                (points[i + 1].value - points[i].value) / dt
            } else if (i > 0) {
                (points[i].value - points[i - 1].value) / dt
            } else {
                0f
            }
            out.add(ScalarPoint(points[i].timeMs, deriv))
        }
        return out
    }

    // ─────────────────────────────────────────────────────────────
    // 3. FAST FOURIER TRANSFORM (Cooley-Tukey Radix-2 DIT)
    // ─────────────────────────────────────────────────────────────

    fun computeFft(
        points: List<ScalarPoint>,
        odrHz: Float,
        fftSize: Int = 256,
        windowFunction: WindowFunction = WindowFunction.HANNING,
        removeDc: Boolean = true
    ): FftResult {
        val n = nextPowerOfTwo(fftSize.coerceIn(32, 2048))
        val fs = odrHz.coerceAtLeast(1f)
        val freqRes = fs / n

        if (points.size < 8) {
            return FftResult(emptyList(), 0f, 0f, 0f, 0f, 0f, freqRes)
        }

        val recent = if (points.size >= n) points.takeLast(n) else points
        val mean = if (removeDc) recent.map { it.value.toDouble() }.average().toFloat() else 0f

        val real = FloatArray(n)
        val imag = FloatArray(n)
        val m = recent.size

        // Coherent gain compensation factor for window
        var windowSum = 0f
        for (i in 0 until m) {
            val w = windowWeight(i, m, windowFunction)
            windowSum += w
            real[i] = (recent[i].value - mean) * w
        }
        // Pad remaining with zeros if m < n
        val scale = if (windowSum > 0f) 2f / windowSum else 2f / n

        fftInPlace(real, imag)

        val halfN = n / 2
        val bins = ArrayList<FftBin>(halfN)
        var max1Mag = -1f
        var max1Idx = 0
        var weightedFreqSum = 0.0
        var magSum = 0.0

        for (k in 0 until halfN) {
            val re = real[k]
            val im = imag[k]
            val rawMag = sqrt(re * re + im * im) * (if (k == 0) scale * 0.5f else scale)
            val freq = k * freqRes
            val db = 20f * log10(rawMag.coerceAtLeast(1e-6f))
            bins.add(FftBin(freq, rawMag, db))

            if (k > 0) {
                weightedFreqSum += freq.toDouble() * rawMag.toDouble()
                magSum += rawMag.toDouble()
                if (rawMag > max1Mag) {
                    max1Mag = rawMag
                    max1Idx = k
                }
            }
        }

        // Parabolic interpolation around dominant peak for sub-bin accuracy
        val domFreq = if (max1Idx in 1 until (halfN - 1)) {
            val alpha = bins[max1Idx - 1].magnitude
            val beta = bins[max1Idx].magnitude
            val gamma = bins[max1Idx + 1].magnitude
            val denom = alpha - 2f * beta + gamma
            val p = if (abs(denom) > 1e-6f) 0.5f * (alpha - gamma) / denom else 0f
            (max1Idx + p.coerceIn(-0.5f, 0.5f)) * freqRes
        } else {
            max1Idx * freqRes
        }

        // Find 2nd distinct local peak (at least 2 bins away from dominant peak)
        var max2Mag = 0f
        var max2Idx = 0
        for (k in 1 until halfN) {
            if (abs(k - max1Idx) > 2 && bins[k].magnitude > max2Mag) {
                max2Mag = bins[k].magnitude
                max2Idx = k
            }
        }

        val centroid = if (magSum > 1e-6) (weightedFreqSum / magSum).toFloat() else 0f

        return FftResult(
            bins = bins,
            dominantFreqHz = domFreq.coerceAtLeast(0f),
            dominantMag = max1Mag.coerceAtLeast(0f),
            secondFreqHz = max2Idx * freqRes,
            secondMag = max2Mag,
            spectralCentroidHz = centroid,
            freqResolutionHz = freqRes
        )
    }

    fun windowWeight(i: Int, n: Int, wf: WindowFunction): Float {
        if (n <= 1) return 1f
        val ratio = i.toDouble() / (n - 1).toDouble()
        return when (wf) {
            WindowFunction.RECTANGULAR -> 1f
            WindowFunction.HANNING -> (0.5 * (1.0 - cos(2.0 * Math.PI * ratio))).toFloat()
            WindowFunction.HAMMING -> (0.54 - 0.46 * cos(2.0 * Math.PI * ratio)).toFloat()
            WindowFunction.BLACKMAN -> (0.42 - 0.5 * cos(2.0 * Math.PI * ratio) + 0.08 * cos(4.0 * Math.PI * ratio)).toFloat()
        }
    }

    fun nextPowerOfTwo(v: Int): Int {
        var p = 1
        while (p < v) p = p shl 1
        return p
    }

    /**
     * In-place radix-2 Cooley-Tukey FFT. Length of real and imag must be a power of 2.
     */
    fun fftInPlace(real: FloatArray, imag: FloatArray) {
        val n = real.size
        if (n <= 1) return

        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
            var m = n shr 1
            while (m >= 1 && j >= m) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        // Butterfly updates
        var len = 2
        while (len <= n) {
            val halfLen = len shr 1
            val angle = (-2.0 * Math.PI / len)
            val wLenR = cos(angle).toFloat()
            val wLenI = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var wR = 1f
                var wI = 0f
                for (k in 0 until halfLen) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[i + k + halfLen] * wR - imag[i + k + halfLen] * wI
                    val vI = real[i + k + halfLen] * wI + imag[i + k + halfLen] * wR
                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[i + k + halfLen] = uR - vR
                    imag[i + k + halfLen] = uI - vI
                    val nextWR = wR * wLenR - wI * wLenI
                    wI = wR * wLenI + wI * wLenR
                    wR = nextWR
                }
                i += len
            }
            len = len shl 1
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 4. SPECTROGRAM (Short-Time Fourier Transform Heatmap)
    // ─────────────────────────────────────────────────────────────

    fun computeSpectrogram(
        points: List<ScalarPoint>,
        odrHz: Float,
        windowSize: Int = 128,
        hopSize: Int = windowSize / 2,
        removeDc: Boolean = true
    ): List<SpectrogramSlice> {
        val n = nextPowerOfTwo(windowSize.coerceIn(32, 512))
        val hop = hopSize.coerceAtLeast(8)
        if (points.size < n) return emptyList()

        val fs = odrHz.coerceAtLeast(1f)
        val freqRes = fs / n
        val halfN = n / 2
        val slices = ArrayList<SpectrogramSlice>()

        var start = 0
        while (start + n <= points.size) {
            val sub = points.subList(start, start + n)
            val mean = if (removeDc) sub.map { it.value.toDouble() }.average().toFloat() else 0f

            val real = FloatArray(n)
            val imag = FloatArray(n)
            for (i in 0 until n) {
                val w = windowWeight(i, n, WindowFunction.HANNING)
                real[i] = (sub[i].value - mean) * w
            }
            fftInPlace(real, imag)

            val mags = FloatArray(halfN)
            var peakMag = -1f
            var peakBin = 0
            for (k in 0 until halfN) {
                val mag = sqrt(real[k] * real[k] + imag[k] * imag[k]) / (n * 0.5f)
                mags[k] = if (k == 0 && removeDc) 0f else mag
                if (k > 0 && mag > peakMag) {
                    peakMag = mag
                    peakBin = k
                }
            }
            slices.add(
                SpectrogramSlice(
                    timeMs = sub.last().timeMs,
                    magnitudes = mags,
                    peakFreqHz = peakBin * freqRes
                )
            )
            start += hop
        }
        return slices
    }

    // ─────────────────────────────────────────────────────────────
    // 5. REAL-TIME STATISTICS & DISTRIBUTION
    // ─────────────────────────────────────────────────────────────

    fun computeStatistics(
        points: List<ScalarPoint>,
        numBins: Int = 25
    ): SignalStats {
        val n = points.size
        if (n == 0) {
            return SignalStats(0, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, IntArray(0), FloatArray(0), 1f)
        }

        var sum = 0.0
        var sumSq = 0.0
        var minVal = Float.POSITIVE_INFINITY
        var maxVal = Float.NEGATIVE_INFINITY

        for (pt in points) {
            val v = pt.value
            sum += v
            sumSq += v.toDouble() * v.toDouble()
            if (v < minVal) minVal = v
            if (v > maxVal) maxVal = v
        }

        val mean = (sum / n).toFloat()
        val rms = sqrt(sumSq / n).toFloat()

        var m2 = 0.0
        var m3 = 0.0
        var m4 = 0.0
        for (pt in points) {
            val d = (pt.value - mean).toDouble()
            val d2 = d * d
            m2 += d2
            m3 += d2 * d
            m4 += d2 * d2
        }

        val variance = (m2 / n).toFloat()
        val stdDev = sqrt(variance.coerceAtLeast(0f))
        val skewness = if (stdDev > 1e-6f) ((m3 / n) / stdDev.toDouble().pow(3.0)).toFloat() else 0f
        val kurtosis = if (stdDev > 1e-6f) (((m4 / n) / stdDev.toDouble().pow(4.0)) - 3.0).toFloat() else 0f

        val bins = numBins.coerceIn(5, 80)
        val span = (maxVal - minVal).let { if (it < 1e-4f) 1f else it }
        val binWidth = span / bins
        val counts = IntArray(bins)
        val centers = FloatArray(bins) { idx -> minVal + (idx + 0.5f) * binWidth }

        for (pt in points) {
            val idx = (((pt.value - minVal) / span) * bins).toInt().coerceIn(0, bins - 1)
            counts[idx]++
        }

        return SignalStats(
            count = n,
            mean = mean,
            stdDev = stdDev,
            variance = variance,
            rms = rms,
            min = minVal,
            max = maxVal,
            peakToPeak = maxVal - minVal,
            skewness = skewness,
            kurtosis = kurtosis,
            histogramCounts = counts,
            binCenters = centers,
            binWidth = binWidth
        )
    }

    // ─────────────────────────────────────────────────────────────
    // 6. AUTOCORRELATION
    // ─────────────────────────────────────────────────────────────

    fun computeAutocorrelation(
        points: List<ScalarPoint>,
        odrHz: Float,
        maxLagSamples: Int = 128,
        normalize: Boolean = true
    ): AutocorrelationResult {
        val n = points.size
        val dtMs = 1000f / odrHz.coerceAtLeast(1f)
        if (n < 16) {
            return AutocorrelationResult(FloatArray(0), FloatArray(0), null, null, 0f, null)
        }

        val mean = points.map { it.value.toDouble() }.average().toFloat()
        val centered = FloatArray(n) { points[it].value - mean }

        val maxLag = minOf(maxLagSamples, n - 4).coerceAtLeast(4)
        val lagsMs = FloatArray(maxLag) { it * dtMs }
        val corr = FloatArray(maxLag)

        var r0 = 0.0
        for (i in 0 until n) {
            r0 += centered[i].toDouble() * centered[i].toDouble()
        }
        r0 /= n

        if (r0 < 1e-9) {
            return AutocorrelationResult(lagsMs, corr, null, null, 0f, null)
        }

        for (lag in 0 until maxLag) {
            var sum = 0.0
            val count = n - lag
            for (i in 0 until count) {
                sum += centered[i].toDouble() * centered[i + lag].toDouble()
            }
            val rawR = (sum / n)
            corr[lag] = if (normalize) (rawR / r0).toFloat().coerceIn(-1f, 1f) else rawR.toFloat()
        }

        // Detect first zero-crossing and subsequent main peak for period detection
        var firstZeroLag: Int? = null
        for (lag in 1 until maxLag) {
            if (corr[lag - 1] > 0f && corr[lag] <= 0f) {
                firstZeroLag = lag
                break
            }
        }

        val firstZeroMs = firstZeroLag?.let { lag ->
            val c1 = corr[lag - 1]
            val c2 = corr[lag]
            val frac = if (abs(c1 - c2) > 1e-6f) c1 / (c1 - c2) else 0f
            (lag - 1 + frac) * dtMs
        }

        var bestPeakLag: Int? = null
        var bestPeakVal = -1f
        if (firstZeroLag != null) {
            for (lag in (firstZeroLag + 1) until (maxLag - 1)) {
                if (corr[lag] > corr[lag - 1] && corr[lag] >= corr[lag + 1] && corr[lag] > 0.15f) {
                    if (corr[lag] > bestPeakVal) {
                        bestPeakVal = corr[lag]
                        bestPeakLag = lag
                    }
                }
            }
        }

        val detectedPeriodSec = bestPeakLag?.let { lag ->
            // Parabolic refinement around autocorrelation peak
            val alpha = corr[lag - 1]
            val beta = corr[lag]
            val gamma = corr[lag + 1]
            val denom = alpha - 2f * beta + gamma
            val p = if (abs(denom) > 1e-6f) 0.5f * (alpha - gamma) / denom else 0f
            ((lag + p.coerceIn(-0.5f, 0.5f)) * dtMs) / 1000f
        }
        val detectedFreqHz = detectedPeriodSec?.let { if (it > 1e-4f) 1f / it else null }

        return AutocorrelationResult(
            lagsMs = lagsMs,
            correlations = corr,
            detectedPeriodSec = detectedPeriodSec,
            detectedFreqHz = detectedFreqHz,
            periodicityConfidence = bestPeakVal.coerceAtLeast(0f),
            firstZeroCrossingMs = firstZeroMs
        )
    }

    // ─────────────────────────────────────────────────────────────
    // 7. DIGITAL FILTER LAB
    // ─────────────────────────────────────────────────────────────

    fun applyFilterLab(
        points: List<ScalarPoint>,
        filterType: FilterLabType,
        alphaLow: Float = 0.15f,
        alphaHigh: Float = 0.05f,
        windowSize: Int = 10
    ): List<ScalarPoint> {
        if (points.isEmpty()) return emptyList()
        return when (filterType) {
            FilterLabType.LOW_PASS -> applyLowPass(points, alphaLow)
            FilterLabType.HIGH_PASS -> applyHighPass(points, alphaHigh)
            FilterLabType.BAND_PASS -> {
                val hp = applyHighPass(points, alphaHigh)
                applyLowPass(hp, alphaLow)
            }
            FilterLabType.MOVING_AVG -> applyMovingAverage(points, windowSize)
            FilterLabType.MEDIAN -> applyMedianFilter(points, windowSize)
        }
    }

    private fun applyLowPass(points: List<ScalarPoint>, alpha: Float): List<ScalarPoint> {
        val a = alpha.coerceIn(0.005f, 1f)
        val out = ArrayList<ScalarPoint>(points.size)
        var prev = points[0].value
        for (pt in points) {
            prev = a * pt.value + (1f - a) * prev
            out.add(ScalarPoint(pt.timeMs, prev))
        }
        return out
    }

    private fun applyHighPass(points: List<ScalarPoint>, alpha: Float): List<ScalarPoint> {
        // High-pass = Signal - Low-pass baseline
        val lp = applyLowPass(points, alpha)
        return points.indices.map { i ->
            ScalarPoint(points[i].timeMs, points[i].value - lp[i].value)
        }
    }

    private fun applyMovingAverage(points: List<ScalarPoint>, windowSize: Int): List<ScalarPoint> {
        val w = windowSize.coerceIn(2, 200)
        val out = ArrayList<ScalarPoint>(points.size)
        var runningSum = 0.0
        for (i in points.indices) {
            runningSum += points[i].value
            if (i >= w) {
                runningSum -= points[i - w].value
            }
            val count = minOf(i + 1, w)
            out.add(ScalarPoint(points[i].timeMs, (runningSum / count).toFloat()))
        }
        return out
    }

    private fun applyMedianFilter(points: List<ScalarPoint>, windowSize: Int): List<ScalarPoint> {
        val w = windowSize.coerceIn(3, 51)
        val half = w / 2
        val out = ArrayList<ScalarPoint>(points.size)
        val scratch = FloatArray(w)
        for (i in points.indices) {
            val start = (i - half).coerceAtLeast(0)
            val end = (i + half).coerceAtMost(points.size - 1)
            val len = end - start + 1
            for (j in 0 until len) {
                scratch[j] = points[start + j].value
            }
            scratch.sort(0, len)
            val median = scratch[len / 2]
            out.add(ScalarPoint(points[i].timeMs, median))
        }
        return out
    }

    fun alphaToCutoffHz(odrHz: Float, alpha: Float): Float {
        if (odrHz <= 0f || alpha <= 0f || alpha >= 1f) return 0f
        return (alpha * odrHz) / (2f * Math.PI.toFloat() * (1f - alpha))
    }

    // ─────────────────────────────────────────────────────────────
    // 8. SIGNAL COMPARATOR (Cross-Correlation & Lissajous)
    // ─────────────────────────────────────────────────────────────

    fun compareSignals(
        rawA: List<ScalarPoint>,
        rawB: List<ScalarPoint>,
        odrHz: Float,
        normalize: Boolean = true
    ): ComparatorResult {
        val n = minOf(rawA.size, rawB.size)
        if (n < 8) {
            return ComparatorResult(emptyList(), emptyList(), 0f, 0f, 0f, null)
        }

        val tailA = rawA.takeLast(n)
        val tailB = rawB.takeLast(n)

        val meanA = tailA.map { it.value.toDouble() }.average().toFloat()
        val meanB = tailB.map { it.value.toDouble() }.average().toFloat()

        var varA = 0.0
        var varB = 0.0
        var cov = 0.0
        for (i in 0 until n) {
            val da = (tailA[i].value - meanA).toDouble()
            val db = (tailB[i].value - meanB).toDouble()
            varA += da * da
            varB += db * db
            cov += da * db
        }

        val stdA = sqrt(varA / n).toFloat().coerceAtLeast(1e-5f)
        val stdB = sqrt(varB / n).toFloat().coerceAtLeast(1e-5f)
        val denom = sqrt(varA * varB)
        val pearsonR = if (denom > 1e-9) (cov / denom).toFloat().coerceIn(-1f, 1f) else 0f

        val alignedA = if (normalize) {
            tailA.map { ScalarPoint(it.timeMs, (it.value - meanA) / stdA) }
        } else {
            tailA
        }

        val alignedB = if (normalize) {
            tailB.map { ScalarPoint(it.timeMs, (it.value - meanB) / stdB) }
        } else {
            tailB
        }

        // Cross-correlation over lags [-maxLag, +maxLag]
        val maxLag = minOf(n / 3, 64).coerceAtLeast(1)
        val dtMs = 1000f / odrHz.coerceAtLeast(1f)
        var bestLag = 0
        var bestCorr = -2f

        for (lag in -maxLag..maxLag) {
            var sum = 0.0
            var count = 0
            for (i in 0 until n) {
                val j = i + lag
                if (j in 0 until n) {
                    val da = (tailA[i].value - meanA) / stdA
                    val db = (tailB[j].value - meanB) / stdB
                    sum += da * db
                    count++
                }
            }
            if (count > 0) {
                val c = (sum / count).toFloat()
                if (abs(c) > abs(bestCorr)) {
                    bestCorr = c
                    bestLag = lag
                }
            }
        }

        val optimalLagMs = bestLag * dtMs

        // Estimate dominant period of A to express lag in degrees of phase shift
        val autoA = computeAutocorrelation(tailA, odrHz, maxLagSamples = minOf(128, n / 2))
        val phaseDeg = autoA.detectedPeriodSec?.let { periodSec ->
            val periodMs = periodSec * 1000f
            if (periodMs > 1f) {
                ((optimalLagMs / periodMs) * 360f) % 360f
            } else null
        }

        return ComparatorResult(
            alignedA = alignedA,
            alignedB = alignedB,
            pearsonR = pearsonR,
            optimalLagMs = optimalLagMs,
            peakCrossCorrelation = bestCorr.coerceIn(-1f, 1f),
            estimatedPhaseDeg = phaseDeg
        )
    }
}

