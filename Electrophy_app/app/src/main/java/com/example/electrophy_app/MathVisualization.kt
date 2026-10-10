package com.example.electrophy_app

import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import java.util.Locale
import kotlin.math.*

data class MathUiState(
    val sourceA: SignalSource = SignalSource.LOW_G_Z,
    val sourceB: SignalSource = SignalSource.LOW_G_X,
    // Integration options
    val integrationSecondOrder: Boolean = false,
    val removeDcMean: Boolean = true,
    // Derivative options
    val derivativeSecondOrder: Boolean = false,
    val derivativeStencilWidth: Int = 1,
    // FFT options
    val fftSize: Int = 256,
    val windowFunction: WindowFunction = WindowFunction.HANNING,
    val fftDbScale: Boolean = false,
    // Spectrogram options
    val spectrogramWinSize: Int = 128,
    val spectrogramPalette: SpectrogramPalette = SpectrogramPalette.VIRIDIS,
    // Statistics options
    val histogramBins: Int = 25,
    val showGaussianFit: Boolean = true,
    val showSigmaBands: Boolean = true,
    // Autocorrelation options
    val maxLagSamples: Int = 128,
    val normalizeAutocorr: Boolean = true,
    // Filter Lab options
    val filterLabType: FilterLabType = FilterLabType.LOW_PASS,
    val filterLabAlphaLow: Float = 0.15f,
    val filterLabAlphaHigh: Float = 0.05f,
    val filterLabWindow: Int = 12,
    val showResidualNoise: Boolean = false,
    // Comparator options
    val normalizeComparator: Boolean = true,
    val lissajousTrailLength: Int = 250,
    val fullLissajousView: Boolean = false
)

@Composable
fun MathControlCard(
    operation: MathOperation,
    state: MathUiState,
    odrHz: Float,
    onStateChange: (MathUiState) -> Unit,
    onResetData: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 1. Primary Input Signal Selector
            val primaryLabel = if (operation == MathOperation.COMPARATOR) "Signal A (X-Axis):" else "Input Signal Source:"
            Text(
                text = primaryLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            SignalSourceChipRow(
                selected = state.sourceA,
                onSelect = { onStateChange(state.copy(sourceA = it)) }
            )

            // 2. Secondary Input Signal Selector (only for Signal Comparator)
            if (operation == MathOperation.COMPARATOR) {
                Text(
                    text = "Signal B (Y-Axis):",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFAB40),
                    fontWeight = FontWeight.Bold
                )
                SignalSourceChipRow(
                    selected = state.sourceB,
                    accentColor = Color(0xFFFFAB40),
                    onSelect = { onStateChange(state.copy(sourceB = it)) }
                )
            }

            // 3. Operation-Specific Parameter Bar
            when (operation) {
                MathOperation.INTEGRATION -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MathMiniChip(
                            label = "1st Order (∫x dt)",
                            selected = !state.integrationSecondOrder,
                            onClick = { onStateChange(state.copy(integrationSecondOrder = false)) }
                        )
                        MathMiniChip(
                            label = "2nd Order (∬x dt²)",
                            selected = state.integrationSecondOrder,
                            onClick = { onStateChange(state.copy(integrationSecondOrder = true)) }
                        )
                        MathMiniChip(
                            label = if (state.removeDcMean) "DC Mean Removed ✓" else "Raw (With DC Drift)",
                            selected = state.removeDcMean,
                            onClick = { onStateChange(state.copy(removeDcMean = !state.removeDcMean)) }
                        )
                        OutlinedButton(
                            onClick = onResetData,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("Zero Integral", fontSize = 11.sp)
                        }
                    }
                }

                MathOperation.DERIVATIVE -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MathMiniChip(
                            label = "1st: dx/dt (Jerk)",
                            selected = !state.derivativeSecondOrder,
                            onClick = { onStateChange(state.copy(derivativeSecondOrder = false)) }
                        )
                        MathMiniChip(
                            label = "2nd: d²x/dt² (Snap)",
                            selected = state.derivativeSecondOrder,
                            onClick = { onStateChange(state.copy(derivativeSecondOrder = true)) }
                        )
                        Text("Stencil:", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                        listOf(1 to "1-step", 2 to "3-step", 4 to "5-step").forEach { (w, lbl) ->
                            MathMiniChip(
                                label = lbl,
                                selected = state.derivativeStencilWidth == w,
                                onClick = { onStateChange(state.copy(derivativeStencilWidth = w)) }
                            )
                        }
                    }
                }

                MathOperation.FFT -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("N:", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                            listOf(128, 256, 512, 1024).forEach { sz ->
                                MathMiniChip(
                                    label = "$sz",
                                    selected = state.fftSize == sz,
                                    onClick = { onStateChange(state.copy(fftSize = sz)) }
                                )
                            }
                            MathMiniChip(
                                label = if (state.fftDbScale) "Scale: dB" else "Scale: Linear",
                                selected = state.fftDbScale,
                                onClick = { onStateChange(state.copy(fftDbScale = !state.fftDbScale)) }
                            )
                            MathMiniChip(
                                label = if (state.removeDcMean) "No DC" else "Keep DC",
                                selected = state.removeDcMean,
                                onClick = { onStateChange(state.copy(removeDcMean = !state.removeDcMean)) }
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Window:", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                            WindowFunction.values().forEach { wf ->
                                MathMiniChip(
                                    label = wf.label,
                                    selected = state.windowFunction == wf,
                                    onClick = { onStateChange(state.copy(windowFunction = wf)) }
                                )
                            }
                        }
                    }
                }

                MathOperation.SPECTROGRAM -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Palette:", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                        SpectrogramPalette.values().forEach { pal ->
                            MathMiniChip(
                                label = pal.label,
                                selected = state.spectrogramPalette == pal,
                                onClick = { onStateChange(state.copy(spectrogramPalette = pal)) }
                            )
                        }
                        Text("STFT:", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                        listOf(64, 128, 256).forEach { sz ->
                            MathMiniChip(
                                label = "$sz",
                                selected = state.spectrogramWinSize == sz,
                                onClick = { onStateChange(state.copy(spectrogramWinSize = sz)) }
                            )
                        }
                    }
                }

                MathOperation.STATISTICS -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Bins:", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                        listOf(15, 25, 40).forEach { b ->
                            MathMiniChip(
                                label = "$b",
                                selected = state.histogramBins == b,
                                onClick = { onStateChange(state.copy(histogramBins = b)) }
                            )
                        }
                        MathMiniChip(
                            label = "Gaussian Curve",
                            selected = state.showGaussianFit,
                            onClick = { onStateChange(state.copy(showGaussianFit = !state.showGaussianFit)) }
                        )
                        MathMiniChip(
                            label = "±2σ Bands",
                            selected = state.showSigmaBands,
                            onClick = { onStateChange(state.copy(showSigmaBands = !state.showSigmaBands)) }
                        )
                    }
                }

                MathOperation.AUTOCORRELATION -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Max Lag:", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                        listOf(64, 128, 256).forEach { lag ->
                            MathMiniChip(
                                label = "$lag pts",
                                selected = state.maxLagSamples == lag,
                                onClick = { onStateChange(state.copy(maxLagSamples = lag)) }
                            )
                        }
                        MathMiniChip(
                            label = if (state.normalizeAutocorr) "Normalized [-1, 1]" else "Raw Covariance",
                            selected = state.normalizeAutocorr,
                            onClick = { onStateChange(state.copy(normalizeAutocorr = !state.normalizeAutocorr)) }
                        )
                    }
                }

                MathOperation.FILTER_LAB -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterLabType.values().forEach { ft ->
                                MathMiniChip(
                                    label = ft.label,
                                    selected = state.filterLabType == ft,
                                    onClick = { onStateChange(state.copy(filterLabType = ft)) }
                                )
                            }
                            MathMiniChip(
                                label = "Show Noise Residual",
                                selected = state.showResidualNoise,
                                onClick = { onStateChange(state.copy(showResidualNoise = !state.showResidualNoise)) }
                            )
                        }

                        when (state.filterLabType) {
                            FilterLabType.LOW_PASS -> {
                                val fc = MathEngine.alphaToCutoffHz(odrHz, state.filterLabAlphaLow)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = String.format(Locale.US, "α=%.2f (fc=%.1f Hz)", state.filterLabAlphaLow, fc),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF00E5FF),
                                        modifier = Modifier.width(120.dp)
                                    )
                                    Slider(
                                        value = state.filterLabAlphaLow,
                                        onValueChange = { onStateChange(state.copy(filterLabAlphaLow = it)) },
                                        valueRange = 0.01f..0.95f,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(26.dp)
                                    )
                                }
                            }
                            FilterLabType.HIGH_PASS -> {
                                val fc = MathEngine.alphaToCutoffHz(odrHz, state.filterLabAlphaHigh)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = String.format(Locale.US, "α=%.2f (fc=%.1f Hz)", state.filterLabAlphaHigh, fc),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF00E5FF),
                                        modifier = Modifier.width(120.dp)
                                    )
                                    Slider(
                                        value = state.filterLabAlphaHigh,
                                        onValueChange = { onStateChange(state.copy(filterLabAlphaHigh = it)) },
                                        valueRange = 0.01f..0.90f,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(26.dp)
                                    )
                                }
                            }
                            FilterLabType.BAND_PASS -> {
                                val fcLow = MathEngine.alphaToCutoffHz(odrHz, state.filterLabAlphaHigh)
                                val fcHigh = MathEngine.alphaToCutoffHz(odrHz, state.filterLabAlphaLow)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = String.format(Locale.US, "HP α=%.2f (%.1fHz)", state.filterLabAlphaHigh, fcLow),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFFFAB40),
                                        modifier = Modifier.width(115.dp)
                                    )
                                    Slider(
                                        value = state.filterLabAlphaHigh,
                                        onValueChange = { onStateChange(state.copy(filterLabAlphaHigh = it)) },
                                        valueRange = 0.01f..0.80f,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(24.dp)
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = String.format(Locale.US, "LP α=%.2f (%.1fHz)", state.filterLabAlphaLow, fcHigh),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF00E5FF),
                                        modifier = Modifier.width(115.dp)
                                    )
                                    Slider(
                                        value = state.filterLabAlphaLow,
                                        onValueChange = { onStateChange(state.copy(filterLabAlphaLow = it)) },
                                        valueRange = 0.02f..0.95f,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(24.dp)
                                    )
                                }
                            }
                            FilterLabType.MOVING_AVG, FilterLabType.MEDIAN -> {
                                val maxWin = if (state.filterLabType == FilterLabType.MEDIAN) 31f else 80f
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Window: ${state.filterLabWindow} pts",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF00E5FF),
                                        modifier = Modifier.width(110.dp)
                                    )
                                    Slider(
                                        value = state.filterLabWindow.toFloat().coerceAtMost(maxWin),
                                        onValueChange = { onStateChange(state.copy(filterLabWindow = it.toInt().coerceAtLeast(3))) },
                                        valueRange = 3f..maxWin,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(26.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                MathOperation.COMPARATOR -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MathMiniChip(
                            label = if (state.normalizeComparator) "Z-Score Normalized" else "Raw Units",
                            selected = state.normalizeComparator,
                            onClick = { onStateChange(state.copy(normalizeComparator = !state.normalizeComparator)) }
                        )
                        MathMiniChip(
                            label = if (state.fullLissajousView) "Full XY Lissajous" else "Split View (Time + XY)",
                            selected = state.fullLissajousView,
                            onClick = { onStateChange(state.copy(fullLissajousView = !state.fullLissajousView)) }
                        )
                        Text("Trail:", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                        listOf(100, 250, 500).forEach { len ->
                            MathMiniChip(
                                label = "$len",
                                selected = state.lissajousTrailLength == len,
                                onClick = { onStateChange(state.copy(lissajousTrailLength = len)) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalSourceChipRow(
    selected: SignalSource,
    accentColor: Color = Color(0xFF00E5FF),
    onSelect: (SignalSource) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SignalSource.values().forEach { src ->
            val isSelected = src == selected
            Surface(
                color = if (isSelected) accentColor.copy(alpha = 0.22f) else Color(0xFF1A222D),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    color = if (isSelected) accentColor else Color(0xFF3A4656)
                ),
                modifier = Modifier.clickable { onSelect(src) }
            ) {
                Text(
                    text = src.label,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) accentColor else Color.LightGray,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun MathMiniChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val activeColor = Color(0xFF69F0AE)
    Surface(
        color = if (selected) activeColor.copy(alpha = 0.18f) else Color(0xFF161C24),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, if (selected) activeColor else Color(0xFF333D4B)),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) activeColor else Color.LightGray,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MAIN PLOT WORKSPACE FOR ALL 8 MATHEMATICS MODES
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun MathWorkspacePlotArea(
    operation: MathOperation,
    state: MathUiState,
    chartData: ChartData,
    odrHz: Float,
    timeWindowSec: Float,
    modifier: Modifier = Modifier
) {
    val rawA = remember(chartData, state.sourceA) {
        MathEngine.extractSignal(chartData, state.sourceA)
    }
    val visibleA = remember(rawA, timeWindowSec) {
        filterRecentWindow(rawA, timeWindowSec)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0E17))
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        when (operation) {
            MathOperation.INTEGRATION -> {
                val integrated = remember(visibleA, odrHz, state.removeDcMean, state.integrationSecondOrder) {
                    MathEngine.integrate(
                        points = visibleA,
                        odrHz = odrHz,
                        removeDc = state.removeDcMean,
                        secondOrder = state.integrationSecondOrder
                    )
                }
                val outUnit = if (state.integrationSecondOrder) "${state.sourceA.unit}·s²" else "${state.sourceA.unit}·s"
                val outTitle = if (state.integrationSecondOrder) "∬ Double Integral ($outUnit)" else "∫ Integrated Signal ($outUnit)"

                ScalarLineChartBox(
                    title = "Input: ${state.sourceA.label} (${state.sourceA.unit})",
                    series = visibleA,
                    lineColor = android.graphics.Color.parseColor("#00E5FF"),
                    timeWindowSec = timeWindowSec,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
                ScalarLineChartBox(
                    title = outTitle,
                    series = integrated,
                    lineColor = android.graphics.Color.parseColor("#69F0AE"),
                    fillAlpha = 45,
                    timeWindowSec = timeWindowSec,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }

            MathOperation.DERIVATIVE -> {
                val deriv = remember(visibleA, odrHz, state.derivativeStencilWidth, state.derivativeSecondOrder) {
                    MathEngine.derivative(
                        points = visibleA,
                        odrHz = odrHz,
                        stencilHalfWidth = state.derivativeStencilWidth,
                        secondOrder = state.derivativeSecondOrder
                    )
                }
                val outUnit = if (state.derivativeSecondOrder) "${state.sourceA.unit}/s²" else "${state.sourceA.unit}/s"
                val outTitle = if (state.derivativeSecondOrder) "d²x/dt² Snap ($outUnit)" else "dx/dt Jerk ($outUnit)"

                ScalarLineChartBox(
                    title = "Input: ${state.sourceA.label} (${state.sourceA.unit})",
                    series = visibleA,
                    lineColor = android.graphics.Color.parseColor("#00E5FF"),
                    timeWindowSec = timeWindowSec,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
                ScalarLineChartBox(
                    title = outTitle,
                    series = deriv,
                    lineColor = android.graphics.Color.parseColor("#FF4081"),
                    timeWindowSec = timeWindowSec,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }

            MathOperation.FFT -> {
                val fftResult = remember(rawA, odrHz, state.fftSize, state.windowFunction, state.removeDcMean) {
                    MathEngine.computeFft(
                        points = rawA,
                        odrHz = odrHz,
                        fftSize = state.fftSize,
                        windowFunction = state.windowFunction,
                        removeDc = state.removeDcMean
                    )
                }
                ScalarLineChartBox(
                    title = "Time Domain (${state.sourceA.label}, N=${state.fftSize})",
                    series = rawA.takeLast(state.fftSize),
                    lineColor = android.graphics.Color.parseColor("#00E5FF"),
                    timeWindowSec = timeWindowSec,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.75f)
                )
                FrequencySpectrumChart(
                    fftResult = fftResult,
                    unit = state.sourceA.unit,
                    dbScale = state.fftDbScale,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.25f)
                )
            }

            MathOperation.SPECTROGRAM -> {
                val slices = remember(rawA, odrHz, state.spectrogramWinSize, state.removeDcMean) {
                    MathEngine.computeSpectrogram(
                        points = rawA,
                        odrHz = odrHz,
                        windowSize = state.spectrogramWinSize,
                        hopSize = (state.spectrogramWinSize / 4).coerceAtLeast(8),
                        removeDc = state.removeDcMean
                    )
                }
                ScalarLineChartBox(
                    title = "Input Waveform: ${state.sourceA.label} (${state.sourceA.unit})",
                    series = visibleA,
                    lineColor = android.graphics.Color.parseColor("#00E5FF"),
                    timeWindowSec = timeWindowSec,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.65f)
                )
                SpectrogramCanvasView(
                    slices = slices,
                    nyquistHz = odrHz / 2f,
                    palette = state.spectrogramPalette,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.35f)
                )
            }

            MathOperation.STATISTICS -> {
                val stats = remember(visibleA, state.histogramBins) {
                    MathEngine.computeStatistics(visibleA, state.histogramBins)
                }
                ScalarLineChartBox(
                    title = "Signal + Confidence Bands (μ = ${String.format(Locale.US, "%.1f", stats.mean)}, σ = ${String.format(Locale.US, "%.1f", stats.stdDev)})",
                    series = visibleA,
                    lineColor = android.graphics.Color.parseColor("#00E5FF"),
                    meanLine = if (state.showSigmaBands && visibleA.isNotEmpty()) stats.mean else null,
                    upperBand = if (state.showSigmaBands && visibleA.isNotEmpty()) stats.mean + 2f * stats.stdDev else null,
                    lowerBand = if (state.showSigmaBands && visibleA.isNotEmpty()) stats.mean - 2f * stats.stdDev else null,
                    timeWindowSec = timeWindowSec,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.95f)
                )
                HistogramDistributionCanvas(
                    stats = stats,
                    unit = state.sourceA.unit,
                    showGaussian = state.showGaussianFit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.05f)
                )
            }

            MathOperation.AUTOCORRELATION -> {
                val acResult = remember(visibleA, odrHz, state.maxLagSamples, state.normalizeAutocorr) {
                    MathEngine.computeAutocorrelation(
                        points = visibleA,
                        odrHz = odrHz,
                        maxLagSamples = state.maxLagSamples,
                        normalize = state.normalizeAutocorr
                    )
                }
                ScalarLineChartBox(
                    title = "Input: ${state.sourceA.label} (${state.sourceA.unit})",
                    series = visibleA,
                    lineColor = android.graphics.Color.parseColor("#00E5FF"),
                    timeWindowSec = timeWindowSec,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.9f)
                )
                AutocorrelationChart(
                    result = acResult,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.1f)
                )
            }

            MathOperation.FILTER_LAB -> {
                val filtered = remember(
                    visibleA,
                    state.filterLabType,
                    state.filterLabAlphaLow,
                    state.filterLabAlphaHigh,
                    state.filterLabWindow
                ) {
                    MathEngine.applyFilterLab(
                        points = visibleA,
                        filterType = state.filterLabType,
                        alphaLow = state.filterLabAlphaLow,
                        alphaHigh = state.filterLabAlphaHigh,
                        windowSize = state.filterLabWindow
                    )
                }
                val residual = remember(visibleA, filtered, state.showResidualNoise) {
                    if (!state.showResidualNoise || visibleA.size != filtered.size) emptyList()
                    else visibleA.indices.map { i ->
                        ScalarPoint(visibleA[i].timeMs, visibleA[i].value - filtered[i].value)
                    }
                }
                FilterOverlayChartBox(
                    title = "Raw vs ${state.filterLabType.label} (${state.sourceA.unit})",
                    rawSeries = visibleA,
                    filteredSeries = filtered,
                    residualSeries = residual,
                    timeWindowSec = timeWindowSec,
                    modifier = Modifier.fillMaxSize()
                )
            }

            MathOperation.COMPARATOR -> {
                val rawB = remember(chartData, state.sourceB) {
                    MathEngine.extractSignal(chartData, state.sourceB)
                }
                val visibleB = remember(rawB, timeWindowSec) {
                    filterRecentWindow(rawB, timeWindowSec)
                }
                val comp = remember(visibleA, visibleB, odrHz, state.normalizeComparator) {
                    MathEngine.compareSignals(
                        rawA = visibleA,
                        rawB = visibleB,
                        odrHz = odrHz,
                        normalize = state.normalizeComparator
                    )
                }
                if (!state.fullLissajousView) {
                    DualOverlayTimeChartBox(
                        title = "${state.sourceA.shortLabel} (Cyan) vs ${state.sourceB.shortLabel} (Orange)",
                        seriesA = comp.alignedA,
                        seriesB = comp.alignedB,
                        labelA = state.sourceA.shortLabel,
                        labelB = state.sourceB.shortLabel,
                        timeWindowSec = timeWindowSec,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.85f)
                    )
                }
                LissajousPhaseCanvas(
                    seriesA = comp.alignedA.takeLast(state.lissajousTrailLength),
                    seriesB = comp.alignedB.takeLast(state.lissajousTrailLength),
                    labelA = "${state.sourceA.label} (${if (state.normalizeComparator) "σ" else state.sourceA.unit})",
                    labelB = "${state.sourceB.label} (${if (state.normalizeComparator) "σ" else state.sourceB.unit})",
                    pearsonR = comp.pearsonR,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(if (state.fullLissajousView) 2f else 1.15f)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// REAL-TIME NUMERICAL ANALYSIS SUMMARY CARD FOR ALL 8 OPERATIONS
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun MathAnalysisResultCard(
    operation: MathOperation,
    state: MathUiState,
    chartData: ChartData,
    odrHz: Float,
    timeWindowSec: Float,
    modifier: Modifier = Modifier
) {
    val rawA = remember(chartData, state.sourceA) {
        MathEngine.extractSignal(chartData, state.sourceA)
    }
    val visibleA = remember(rawA, timeWindowSec) {
        filterRecentWindow(rawA, timeWindowSec)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "${operation.icon} ${operation.label} — Live Telemetry",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            when (operation) {
                MathOperation.INTEGRATION -> {
                    val integrated = remember(visibleA, odrHz, state.removeDcMean, state.integrationSecondOrder) {
                        MathEngine.integrate(visibleA, odrHz, state.removeDcMean, state.integrationSecondOrder)
                    }
                    val currentVal = integrated.lastOrNull()?.value ?: 0f
                    val peakPos = integrated.maxOfOrNull { it.value } ?: 0f
                    val peakNeg = integrated.minOfOrNull { it.value } ?: 0f
                    val unit = if (state.integrationSecondOrder) "${state.sourceA.unit}·s²" else "${state.sourceA.unit}·s"

                    Text(
                        text = String.format(Locale.US, "Current Integral: %.3f %s  |  dt = %.2f ms", currentVal, unit, 1000f / odrHz.coerceAtLeast(1f)),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF69F0AE)
                    )
                    Text(
                        text = String.format(Locale.US, "Max Positive: +%.3f %s  |  Max Negative: %.3f %s", peakPos, unit, peakNeg, unit),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                MathOperation.DERIVATIVE -> {
                    val deriv = remember(visibleA, odrHz, state.derivativeStencilWidth, state.derivativeSecondOrder) {
                        MathEngine.derivative(visibleA, odrHz, state.derivativeStencilWidth, state.derivativeSecondOrder)
                    }
                    val currentVal = deriv.lastOrNull()?.value ?: 0f
                    val maxPeak = deriv.maxOfOrNull { abs(it.value) } ?: 0f
                    val rms = if (deriv.isNotEmpty()) sqrt(deriv.map { it.value.toDouble().pow(2.0) }.average()).toFloat() else 0f
                    val unit = if (state.derivativeSecondOrder) "${state.sourceA.unit}/s²" else "${state.sourceA.unit}/s"

                    Text(
                        text = String.format(Locale.US, "Peak |Derivative|: %.1f %s  |  RMS: %.1f %s", maxPeak, unit, rms, unit),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF4081)
                    )
                    Text(
                        text = String.format(Locale.US, "Instantaneous Rate: %.1f %s (ODR = %.0f Hz)", currentVal, unit, odrHz),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                MathOperation.FFT -> {
                    val fft = remember(rawA, odrHz, state.fftSize, state.windowFunction, state.removeDcMean) {
                        MathEngine.computeFft(rawA, odrHz, state.fftSize, state.windowFunction, state.removeDcMean)
                    }
                    val bpm = fft.dominantFreqHz * 60f
                    Text(
                        text = String.format(
                            Locale.US,
                            "Dominant Peak f₁: %.2f Hz (%.0f BPM/RPM) — Mag: %.2f %s",
                            fft.dominantFreqHz,
                            bpm,
                            fft.dominantMag,
                            state.sourceA.unit
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF69F0AE)
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "2nd Peak f₂: %.2f Hz (%.2f)  |  Centroid: %.1f Hz  |  Δf = %.2f Hz",
                            fft.secondFreqHz,
                            fft.secondMag,
                            fft.spectralCentroidHz,
                            fft.freqResolutionHz
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                MathOperation.SPECTROGRAM -> {
                    val slices = remember(rawA, odrHz, state.spectrogramWinSize, state.removeDcMean) {
                        MathEngine.computeSpectrogram(rawA, odrHz, state.spectrogramWinSize, (state.spectrogramWinSize / 4).coerceAtLeast(8), state.removeDcMean)
                    }
                    val latestSlice = slices.lastOrNull()
                    val peakFreq = latestSlice?.peakFreqHz ?: 0f
                    Text(
                        text = String.format(Locale.US, "Instantaneous Peak Freq: %.1f Hz  |  Nyquist Limit: %.1f Hz", peakFreq, odrHz / 2f),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF00E5FF)
                    )
                    Text(
                        text = "Time-Frequency slices rendered: ${slices.size}  |  Freq bins: ${state.spectrogramWinSize / 2}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                MathOperation.STATISTICS -> {
                    val st = remember(visibleA, state.histogramBins) {
                        MathEngine.computeStatistics(visibleA, state.histogramBins)
                    }
                    Text(
                        text = String.format(
                            Locale.US,
                            "Mean (μ): %.2f  |  Std Dev (σ): %.2f  |  RMS: %.2f %s",
                            st.mean, st.stdDev, st.rms, state.sourceA.unit
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF00E5FF)
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "Min: %.1f  |  Max: %.1f  |  P-P: %.1f  |  Var (σ²): %.1f",
                            st.min, st.max, st.peakToPeak, st.variance
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "Skewness: %.2f  |  Excess Kurtosis: %.2f  (N = %d samples)",
                            st.skewness, st.kurtosis, st.count
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray
                    )
                }

                MathOperation.AUTOCORRELATION -> {
                    val ac = remember(visibleA, odrHz, state.maxLagSamples, state.normalizeAutocorr) {
                        MathEngine.computeAutocorrelation(visibleA, odrHz, state.maxLagSamples, state.normalizeAutocorr)
                    }
                    if (ac.detectedPeriodSec != null && ac.detectedFreqHz != null) {
                        Text(
                            text = String.format(
                                Locale.US,
                                "Fundamental Period T = %.3f s  (f = %.2f Hz)  |  Strength R(T) = %.2f",
                                ac.detectedPeriodSec,
                                ac.detectedFreqHz,
                                ac.periodicityConfidence
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF69F0AE)
                        )
                    } else {
                        Text(
                            text = "Periodicity: No strong periodic oscillation detected in current window",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFFAB40)
                        )
                    }
                    val zcText = ac.firstZeroCrossingMs?.let { String.format(Locale.US, "%.1f ms", it) } ?: "N/A"
                    Text(
                        text = "First Zero-Crossing Lag (Decorrelation Time): $zcText",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                MathOperation.FILTER_LAB -> {
                    val filtered = remember(visibleA, state.filterLabType, state.filterLabAlphaLow, state.filterLabAlphaHigh, state.filterLabWindow) {
                        MathEngine.applyFilterLab(visibleA, state.filterLabType, state.filterLabAlphaLow, state.filterLabAlphaHigh, state.filterLabWindow)
                    }
                    val rawStats = MathEngine.computeStatistics(visibleA, 10)
                    val filtStats = MathEngine.computeStatistics(filtered, 10)
                    val attenuationDb = if (rawStats.stdDev > 1e-5f && filtStats.stdDev > 1e-5f) {
                        20f * log10(filtStats.stdDev / rawStats.stdDev)
                    } else 0f

                    Text(
                        text = String.format(
                            Locale.US,
                            "Raw σ: %.2f %s  →  Filtered σ: %.2f %s  (Δ = %+.1f dB)",
                            rawStats.stdDev, state.sourceA.unit, filtStats.stdDev, state.sourceA.unit, attenuationDb
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF69F0AE)
                    )
                }

                MathOperation.COMPARATOR -> {
                    val rawB = remember(chartData, state.sourceB) {
                        MathEngine.extractSignal(chartData, state.sourceB)
                    }
                    val visibleB = remember(rawB, timeWindowSec) {
                        filterRecentWindow(rawB, timeWindowSec)
                    }
                    val comp = remember(visibleA, visibleB, odrHz, state.normalizeComparator) {
                        MathEngine.compareSignals(visibleA, visibleB, odrHz, state.normalizeComparator)
                    }
                    Text(
                        text = String.format(
                            Locale.US,
                            "Pearson Correlation r = %+.3f  |  Peak Cross-Corr = %+.3f",
                            comp.pearsonR,
                            comp.peakCrossCorrelation
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF00E5FF)
                    )
                    val phaseStr = comp.estimatedPhaseDeg?.let { String.format(Locale.US, " (Δφ ≈ %.1f°)", it) } ?: ""
                    Text(
                        text = String.format(
                            Locale.US,
                            "Optimal Time Lag Δτ: %+.1f ms%s",
                            comp.optimalLagMs,
                            phaseStr
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CHART & CANVAS COMPOSABLES
// ─────────────────────────────────────────────────────────────────────────────

private fun filterRecentWindow(points: List<ScalarPoint>, timeWindowSec: Float): List<ScalarPoint> {
    if (points.isEmpty()) return emptyList()
    val maxT = points.last().timeMs
    val minT = (maxT - timeWindowSec * 1000f).coerceAtLeast(0f)
    return points.filter { it.timeMs >= minT }
}

@Composable
private fun ScalarLineChartBox(
    title: String,
    series: List<ScalarPoint>,
    lineColor: Int,
    fillAlpha: Int = 0,
    meanLine: Float? = null,
    upperBand: Float? = null,
    lowerBand: Float? = null,
    timeWindowSec: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF101418), RoundedCornerShape(6.dp))
            .padding(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            val latest = series.lastOrNull()?.value
            if (latest != null) {
                Text(
                    text = String.format(Locale.US, "Now: %.2f", latest),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(lineColor)
                )
            }
        }

        AndroidView(
            factory = { ctx ->
                LineChart(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    description.isEnabled = false
                    setTouchEnabled(true)
                    isDragEnabled = true
                    setScaleEnabled(false)
                    setPinchZoom(false)
                    setBackgroundColor(android.graphics.Color.parseColor("#101418"))
                    xAxis.apply {
                        textColor = android.graphics.Color.LTGRAY
                        position = XAxis.XAxisPosition.BOTTOM
                        setDrawGridLines(true)
                        gridColor = android.graphics.Color.parseColor("#252B33")
                        textSize = 9f
                        valueFormatter = object : ValueFormatter() {
                            override fun getFormattedValue(value: Float): String {
                                return String.format(Locale.US, "%.1fs", value / 1000f)
                            }
                        }
                    }
                    axisLeft.apply {
                        textColor = android.graphics.Color.LTGRAY
                        setDrawGridLines(true)
                        gridColor = android.graphics.Color.parseColor("#252B33")
                        textSize = 9f
                    }
                    axisRight.isEnabled = false
                    legend.isEnabled = false
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            update = { chart ->
                val entries = series.map { Entry(it.timeMs, it.value) }
                val ds = LineDataSet(entries, title).apply {
                    color = lineColor
                    setDrawCircles(false)
                    lineWidth = 2f
                    setDrawValues(false)
                    if (fillAlpha > 0) {
                        setDrawFilled(true)
                        fillColor = lineColor
                        this.fillAlpha = fillAlpha
                    }
                }
                chart.axisLeft.removeAllLimitLines()
                if (meanLine != null) {
                    chart.axisLeft.addLimitLine(LimitLine(meanLine, "μ").apply {
                        this.lineColor = android.graphics.Color.YELLOW
                        lineWidth = 1.2f
                        textColor = android.graphics.Color.YELLOW
                        textSize = 8f
                    })
                }
                if (upperBand != null) {
                    chart.axisLeft.addLimitLine(LimitLine(upperBand, "+2σ").apply {
                        this.lineColor = android.graphics.Color.parseColor("#FF80AB")
                        lineWidth = 1f
                        enableDashedLine(10f, 8f, 0f)
                        textColor = android.graphics.Color.parseColor("#FF80AB")
                        textSize = 8f
                    })
                }
                if (lowerBand != null) {
                    chart.axisLeft.addLimitLine(LimitLine(lowerBand, "-2σ").apply {
                        this.lineColor = android.graphics.Color.parseColor("#FF80AB")
                        lineWidth = 1f
                        enableDashedLine(10f, 8f, 0f)
                        textColor = android.graphics.Color.parseColor("#FF80AB")
                        textSize = 8f
                    })
                }

                chart.data = LineData(ds)
                chart.notifyDataSetChanged()
                chart.invalidate()
            }
        )
    }
}

@Composable
private fun FrequencySpectrumChart(
    fftResult: FftResult,
    unit: String,
    dbScale: Boolean,
    modifier: Modifier = Modifier
) {
    var inspectedBin by remember { mutableStateOf<FftBin?>(null) }

    Column(
        modifier = modifier
            .background(Color(0xFF101418), RoundedCornerShape(6.dp))
            .padding(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "FFT Frequency Spectrum (${if (dbScale) "dB" else unit})",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF69F0AE)
            )
            val badgeText = if (inspectedBin != null) {
                val b = inspectedBin!!
                String.format(Locale.US, "Cursor: %.1f Hz (%.2f)", b.frequencyHz, if (dbScale) b.magnitudeDb else b.magnitude)
            } else {
                String.format(Locale.US, "Peak: %.1f Hz", fftResult.dominantFreqHz)
            }
            Text(
                text = badgeText,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFFFAB40)
            )
        }

        AndroidView(
            factory = { ctx ->
                LineChart(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    description.isEnabled = false
                    setTouchEnabled(true)
                    isDragEnabled = true
                    setScaleEnabled(true)
                    setPinchZoom(true)
                    setBackgroundColor(android.graphics.Color.parseColor("#101418"))
                    xAxis.apply {
                        textColor = android.graphics.Color.LTGRAY
                        position = XAxis.XAxisPosition.BOTTOM
                        setDrawGridLines(true)
                        gridColor = android.graphics.Color.parseColor("#252B33")
                        textSize = 9f
                        valueFormatter = object : ValueFormatter() {
                            override fun getFormattedValue(value: Float): String {
                                return String.format(Locale.US, "%.0f Hz", value)
                            }
                        }
                    }
                    axisLeft.apply {
                        textColor = android.graphics.Color.LTGRAY
                        setDrawGridLines(true)
                        gridColor = android.graphics.Color.parseColor("#252B33")
                        textSize = 9f
                    }
                    axisRight.isEnabled = false
                    legend.isEnabled = false
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            update = { chart ->
                val entries = fftResult.bins.map { bin ->
                    Entry(bin.frequencyHz, if (dbScale) bin.magnitudeDb else bin.magnitude)
                }
                val ds = LineDataSet(entries, "FFT").apply {
                    color = android.graphics.Color.parseColor("#69F0AE")
                    setDrawCircles(false)
                    lineWidth = 2f
                    setDrawValues(false)
                    setDrawFilled(true)
                    fillColor = android.graphics.Color.parseColor("#69F0AE")
                    fillAlpha = 65
                }
                chart.xAxis.removeAllLimitLines()
                if (fftResult.dominantFreqHz > 0f) {
                    chart.xAxis.addLimitLine(LimitLine(fftResult.dominantFreqHz, String.format(Locale.US, "%.1fHz", fftResult.dominantFreqHz)).apply {
                        lineColor = android.graphics.Color.parseColor("#FFAB40")
                        lineWidth = 1.5f
                        enableDashedLine(10f, 6f, 0f)
                        textColor = android.graphics.Color.parseColor("#FFAB40")
                        textSize = 9f
                    })
                }
                chart.data = LineData(ds)
                chart.notifyDataSetChanged()
                chart.invalidate()
            }
        )
    }
}

@Composable
private fun SpectrogramCanvasView(
    slices: List<SpectrogramSlice>,
    nyquistHz: Float,
    palette: SpectrogramPalette,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF101418), RoundedCornerShape(6.dp))
            .padding(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "2D Time-Frequency Spectrogram (0 – ${String.format(Locale.US, "%.0f", nyquistHz)} Hz)",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Text(
                text = palette.label,
                fontSize = 10.sp,
                color = Color(0xFF00E5FF)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val leftPad = 42.dp.toPx()
                val rightPad = 18.dp.toPx()
                val bottomPad = 14.dp.toPx()
                val plotW = (size.width - leftPad - rightPad).coerceAtLeast(10f)
                val plotH = (size.height - bottomPad).coerceAtLeast(10f)

                drawRect(
                    color = Color(0xFF070A0F),
                    topLeft = Offset(leftPad, 0f),
                    size = Size(plotW, plotH)
                )

                if (slices.isNotEmpty()) {
                    val maxCols = minOf(slices.size, 120)
                    val visibleSlices = slices.takeLast(maxCols)
                    val numBins = visibleSlices.first().magnitudes.size.coerceAtLeast(1)

                    var globalMax = 1e-4f
                    for (sl in visibleSlices) {
                        for (m in sl.magnitudes) {
                            if (m > globalMax) globalMax = m
                        }
                    }

                    val colW = plotW / maxCols
                    val rowH = plotH / numBins

                    for (c in visibleSlices.indices) {
                        val sl = visibleSlices[c]
                        val x = leftPad + c * colW
                        for (b in 0 until numBins) {
                            // Low frequency at bottom, Nyquist at top
                            val y = plotH - (b + 1) * rowH
                            val norm = (sl.magnitudes[b] / globalMax).coerceIn(0f, 1f)
                            // Mild gamma curve to bring out mid-level harmonics
                            val intensity = norm.pow(0.65f)
                            drawRect(
                                color = mapSpectrogramColor(intensity, palette),
                                topLeft = Offset(x, y.coerceAtLeast(0f)),
                                size = Size(colW + 1f, rowH + 1f)
                            )
                        }
                    }
                }

                // Frequency Y-axis labels
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.LTGRAY
                    textSize = 9.sp.toPx()
                    isAntiAlias = true
                }
                val ticks = 4
                for (t in 0..ticks) {
                    val frac = t.toFloat() / ticks
                    val freq = frac * nyquistHz
                    val y = plotH * (1f - frac)
                    drawLine(
                        color = Color(0x44FFFFFF),
                        start = Offset(leftPad, y),
                        end = Offset(leftPad + plotW, y),
                        strokeWidth = 1f
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        String.format(Locale.US, "%.0fHz", freq),
                        2f,
                        (y + 4.dp.toPx()).coerceIn(10f, plotH),
                        paint
                    )
                }

                // Colorbar legend on the right
                val barX = leftPad + plotW + 4.dp.toPx()
                val barW = 10.dp.toPx()
                val steps = 32
                val stepH = plotH / steps
                for (s in 0 until steps) {
                    val v = 1f - (s.toFloat() / (steps - 1))
                    drawRect(
                        color = mapSpectrogramColor(v, palette),
                        topLeft = Offset(barX, s * stepH),
                        size = Size(barW, stepH + 1f)
                    )
                }
            }
        }
    }
}

private fun mapSpectrogramColor(t: Float, palette: SpectrogramPalette): Color {
    val v = t.coerceIn(0f, 1f)
    return when (palette) {
        SpectrogramPalette.VIRIDIS -> {
            when {
                v < 0.33f -> lerpColor(Color(0xFF1F0C48), Color(0xFF21908C), v / 0.33f)
                v < 0.66f -> lerpColor(Color(0xFF21908C), Color(0xFF5DC863), (v - 0.33f) / 0.33f)
                else -> lerpColor(Color(0xFF5DC863), Color(0xFFFDE725), (v - 0.66f) / 0.34f)
            }
        }
        SpectrogramPalette.MAGMA -> {
            when {
                v < 0.33f -> lerpColor(Color(0xFF0B0726), Color(0xFF721F81), v / 0.33f)
                v < 0.66f -> lerpColor(Color(0xFF721F81), Color(0xFFF1605D), (v - 0.33f) / 0.33f)
                else -> lerpColor(Color(0xFFF1605D), Color(0xFFFEFFB2), (v - 0.66f) / 0.34f)
            }
        }
        SpectrogramPalette.INFERNO -> {
            when {
                v < 0.33f -> lerpColor(Color(0xFF08051D), Color(0xFF8C2369), v / 0.33f)
                v < 0.66f -> lerpColor(Color(0xFF8C2369), Color(0xFFED6925), (v - 0.33f) / 0.33f)
                else -> lerpColor(Color(0xFFED6925), Color(0xFFFCFFA4), (v - 0.66f) / 0.34f)
            }
        }
        SpectrogramPalette.CYBERPUNK -> {
            when {
                v < 0.33f -> lerpColor(Color(0xFF070B19), Color(0xFF0055FF), v / 0.33f)
                v < 0.66f -> lerpColor(Color(0xFF0055FF), Color(0xFF00E5FF), (v - 0.33f) / 0.33f)
                else -> lerpColor(Color(0xFF00E5FF), Color(0xFFFF007F), (v - 0.66f) / 0.34f)
            }
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val f = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * f,
        green = a.green + (b.green - a.green) * f,
        blue = a.blue + (b.blue - a.blue) * f,
        alpha = 1f
    )
}

@Composable
private fun HistogramDistributionCanvas(
    stats: SignalStats,
    unit: String,
    showGaussian: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF101418), RoundedCornerShape(6.dp))
            .padding(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Amplitude Distribution Histogram ($unit)",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            if (showGaussian) {
                Text(
                    text = "Yellow = Normal N(μ, σ²)",
                    fontSize = 10.sp,
                    color = Color(0xFFFFD740)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val leftPad = 28.dp.toPx()
            val bottomPad = 18.dp.toPx()
            val plotW = (size.width - leftPad - 8.dp.toPx()).coerceAtLeast(10f)
            val plotH = (size.height - bottomPad - 4.dp.toPx()).coerceAtLeast(10f)

            val counts = stats.histogramCounts
            if (counts.isEmpty() || stats.count == 0) return@Canvas

            val maxCount = (counts.maxOrNull() ?: 1).coerceAtLeast(1)
            val nBins = counts.size
            val barW = plotW / nBins

            // Draw histogram bars
            for (i in 0 until nBins) {
                val frac = counts[i].toFloat() / maxCount
                val barH = frac * plotH
                val x = leftPad + i * barW
                val y = plotH - barH
                drawRect(
                    color = Color(0xFF00E5FF).copy(alpha = 0.65f),
                    topLeft = Offset(x + 1f, y),
                    size = Size((barW - 2f).coerceAtLeast(1f), barH)
                )
            }

            // Draw theoretical Gaussian bell curve if enabled and stdDev > 0
            if (showGaussian && stats.stdDev > 1e-4f && stats.binCenters.size >= 2) {
                val minX = stats.min
                val maxX = stats.max
                val span = (maxX - minX).coerceAtLeast(1e-4f)
                val peakPdf = (1.0 / (stats.stdDev * sqrt(2.0 * Math.PI))).toFloat()
                val peakExpectedCount = peakPdf * stats.binWidth * stats.count
                val scaleY = plotH / maxOf(maxCount.toFloat(), peakExpectedCount)

                val path = Path()
                val steps = 80
                for (s in 0..steps) {
                    val fracX = s.toFloat() / steps
                    val xVal = minX + fracX * span
                    val z = (xVal - stats.mean) / stats.stdDev
                    val pdf = (1.0 / (stats.stdDev * sqrt(2.0 * Math.PI)) * exp(-0.5 * z * z)).toFloat()
                    val expectedCount = pdf * stats.binWidth * stats.count
                    val px = leftPad + fracX * plotW
                    val py = (plotH - expectedCount * scaleY).coerceIn(0f, plotH)
                    if (s == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(
                    path = path,
                    color = Color(0xFFFFD740),
                    style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Labels for min, mean, max on bottom axis
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.LTGRAY
                textSize = 9.sp.toPx()
                isAntiAlias = true
            }
            drawContext.canvas.nativeCanvas.drawText(
                String.format(Locale.US, "%.1f", stats.min),
                leftPad,
                plotH + 14.dp.toPx(),
                paint
            )
            drawContext.canvas.nativeCanvas.drawText(
                String.format(Locale.US, "μ=%.1f", stats.mean),
                leftPad + plotW * 0.45f,
                plotH + 14.dp.toPx(),
                paint
            )
            drawContext.canvas.nativeCanvas.drawText(
                String.format(Locale.US, "%.1f", stats.max),
                (leftPad + plotW - 34.dp.toPx()).coerceAtLeast(leftPad),
                plotH + 14.dp.toPx(),
                paint
            )
        }
    }
}

@Composable
private fun AutocorrelationChart(
    result: AutocorrelationResult,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF101418), RoundedCornerShape(6.dp))
            .padding(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Autocorrelation R(τ) vs Lag τ (ms)",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF69F0AE)
            )
            val periodLabel = result.detectedPeriodSec?.let {
                String.format(Locale.US, "Period T = %.3fs (%.1f Hz)", it, 1f / it)
            } ?: "Searching for period..."
            Text(
                text = periodLabel,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFFFAB40)
            )
        }

        AndroidView(
            factory = { ctx ->
                LineChart(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    description.isEnabled = false
                    setTouchEnabled(true)
                    isDragEnabled = true
                    setScaleEnabled(false)
                    setPinchZoom(false)
                    setBackgroundColor(android.graphics.Color.parseColor("#101418"))
                    xAxis.apply {
                        textColor = android.graphics.Color.LTGRAY
                        position = XAxis.XAxisPosition.BOTTOM
                        setDrawGridLines(true)
                        gridColor = android.graphics.Color.parseColor("#252B33")
                        textSize = 9f
                        valueFormatter = object : ValueFormatter() {
                            override fun getFormattedValue(value: Float): String {
                                return String.format(Locale.US, "%.0fms", value)
                            }
                        }
                    }
                    axisLeft.apply {
                        textColor = android.graphics.Color.LTGRAY
                        setDrawGridLines(true)
                        gridColor = android.graphics.Color.parseColor("#252B33")
                        textSize = 9f
                    }
                    axisRight.isEnabled = false
                    legend.isEnabled = false
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            update = { chart ->
                val entries = result.lagsMs.indices.map { i ->
                    Entry(result.lagsMs[i], result.correlations[i])
                }
                val ds = LineDataSet(entries, "R(τ)").apply {
                    color = android.graphics.Color.parseColor("#69F0AE")
                    setDrawCircles(false)
                    lineWidth = 2f
                    setDrawValues(false)
                }
                chart.axisLeft.removeAllLimitLines()
                chart.axisLeft.addLimitLine(LimitLine(0f, "").apply {
                    lineColor = android.graphics.Color.GRAY
                    lineWidth = 1f
                })
                chart.xAxis.removeAllLimitLines()
                result.detectedPeriodSec?.let { pSec ->
                    val lagMs = pSec * 1000f
                    chart.xAxis.addLimitLine(LimitLine(lagMs, String.format(Locale.US, "T=%.0fms", lagMs)).apply {
                        lineColor = android.graphics.Color.parseColor("#FFAB40")
                        lineWidth = 1.5f
                        enableDashedLine(10f, 6f, 0f)
                        textColor = android.graphics.Color.parseColor("#FFAB40")
                        textSize = 9f
                    })
                }
                chart.data = LineData(ds)
                chart.notifyDataSetChanged()
                chart.invalidate()
            }
        )
    }
}

@Composable
private fun FilterOverlayChartBox(
    title: String,
    rawSeries: List<ScalarPoint>,
    filteredSeries: List<ScalarPoint>,
    residualSeries: List<ScalarPoint>,
    timeWindowSec: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF101418), RoundedCornerShape(6.dp))
            .padding(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Raw", fontSize = 10.sp, color = Color(0xFFFF5252))
                Text("Filtered", fontSize = 10.sp, color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                if (residualSeries.isNotEmpty()) {
                    Text("Noise", fontSize = 10.sp, color = Color(0xFFFFD740))
                }
            }
        }

        AndroidView(
            factory = { ctx ->
                LineChart(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    description.isEnabled = false
                    setTouchEnabled(true)
                    isDragEnabled = true
                    setScaleEnabled(true)
                    setPinchZoom(true)
                    setBackgroundColor(android.graphics.Color.parseColor("#101418"))
                    xAxis.apply {
                        textColor = android.graphics.Color.LTGRAY
                        position = XAxis.XAxisPosition.BOTTOM
                        setDrawGridLines(true)
                        gridColor = android.graphics.Color.parseColor("#252B33")
                        textSize = 9f
                        valueFormatter = object : ValueFormatter() {
                            override fun getFormattedValue(value: Float): String {
                                return String.format(Locale.US, "%.1fs", value / 1000f)
                            }
                        }
                    }
                    axisLeft.apply {
                        textColor = android.graphics.Color.LTGRAY
                        setDrawGridLines(true)
                        gridColor = android.graphics.Color.parseColor("#252B33")
                        textSize = 9f
                    }
                    axisRight.isEnabled = false
                    legend.isEnabled = false
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            update = { chart ->
                val rawEntries = rawSeries.map { Entry(it.timeMs, it.value) }
                val filtEntries = filteredSeries.map { Entry(it.timeMs, it.value) }

                val sets = ArrayList<ILineDataSet>()
                sets.add(LineDataSet(rawEntries, "Raw").apply {
                    color = android.graphics.Color.argb(130, 255, 82, 82)
                    setDrawCircles(false)
                    lineWidth = 1.4f
                    setDrawValues(false)
                })
                sets.add(LineDataSet(filtEntries, "Filtered").apply {
                    color = android.graphics.Color.parseColor("#00E5FF")
                    setDrawCircles(false)
                    lineWidth = 2.6f
                    setDrawValues(false)
                })
                if (residualSeries.isNotEmpty()) {
                    val resEntries = residualSeries.map { Entry(it.timeMs, it.value) }
                    sets.add(LineDataSet(resEntries, "Noise").apply {
                        color = android.graphics.Color.argb(180, 255, 215, 64)
                        setDrawCircles(false)
                        lineWidth = 1.2f
                        setDrawValues(false)
                    })
                }
                chart.data = LineData(sets)
                chart.notifyDataSetChanged()
                chart.invalidate()
            }
        )
    }
}

@Composable
private fun DualOverlayTimeChartBox(
    title: String,
    seriesA: List<ScalarPoint>,
    seriesB: List<ScalarPoint>,
    labelA: String,
    labelB: String,
    timeWindowSec: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF101418), RoundedCornerShape(6.dp))
            .padding(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(labelA, fontSize = 10.sp, color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                Text(labelB, fontSize = 10.sp, color = Color(0xFFFFAB40), fontWeight = FontWeight.Bold)
            }
        }

        AndroidView(
            factory = { ctx ->
                LineChart(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    description.isEnabled = false
                    setTouchEnabled(false)
                    setBackgroundColor(android.graphics.Color.parseColor("#101418"))
                    xAxis.apply {
                        textColor = android.graphics.Color.LTGRAY
                        position = XAxis.XAxisPosition.BOTTOM
                        setDrawGridLines(true)
                        gridColor = android.graphics.Color.parseColor("#252B33")
                        textSize = 9f
                        valueFormatter = object : ValueFormatter() {
                            override fun getFormattedValue(value: Float): String {
                                return String.format(Locale.US, "%.1fs", value / 1000f)
                            }
                        }
                    }
                    axisLeft.apply {
                        textColor = android.graphics.Color.LTGRAY
                        setDrawGridLines(true)
                        gridColor = android.graphics.Color.parseColor("#252B33")
                        textSize = 9f
                    }
                    axisRight.isEnabled = false
                    legend.isEnabled = false
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            update = { chart ->
                val entriesA = seriesA.map { Entry(it.timeMs, it.value) }
                val entriesB = seriesB.map { Entry(it.timeMs, it.value) }
                val sets = listOf<ILineDataSet>(
                    LineDataSet(entriesA, labelA).apply {
                        color = android.graphics.Color.parseColor("#00E5FF")
                        setDrawCircles(false)
                        lineWidth = 2f
                        setDrawValues(false)
                    },
                    LineDataSet(entriesB, labelB).apply {
                        color = android.graphics.Color.parseColor("#FFAB40")
                        setDrawCircles(false)
                        lineWidth = 2f
                        setDrawValues(false)
                    }
                )
                chart.data = LineData(sets)
                chart.notifyDataSetChanged()
                chart.invalidate()
            }
        )
    }
}

@Composable
private fun LissajousPhaseCanvas(
    seriesA: List<ScalarPoint>,
    seriesB: List<ScalarPoint>,
    labelA: String,
    labelB: String,
    pearsonR: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF101418), RoundedCornerShape(6.dp))
            .padding(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Lissajous XY Phase Portrait",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF69F0AE)
            )
            Text(
                text = String.format(Locale.US, "r = %+.3f", pearsonR),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF00E5FF)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val n = minOf(seriesA.size, seriesB.size)
                val side = minOf(size.width, size.height) * 0.88f
                val left = (size.width - side) / 2f
                val top = (size.height - side) / 2f
                val cx = left + side / 2f
                val cy = top + side / 2f

                // Background square & grid
                drawRect(
                    color = Color(0xFF080B10),
                    topLeft = Offset(left, top),
                    size = Size(side, side)
                )
                drawRect(
                    color = Color(0xFF283240),
                    topLeft = Offset(left, top),
                    size = Size(side, side),
                    style = Stroke(width = 1.dp.toPx())
                )
                // Crosshairs
                drawLine(
                    color = Color(0xFF283240),
                    start = Offset(left, cy),
                    end = Offset(left + side, cy),
                    strokeWidth = 1f
                )
                drawLine(
                    color = Color(0xFF283240),
                    start = Offset(cx, top),
                    end = Offset(cx, top + side),
                    strokeWidth = 1f
                )

                if (n >= 2) {
                    var maxAbs = 0.5f
                    for (i in 0 until n) {
                        maxAbs = maxOf(maxAbs, abs(seriesA[i].value), abs(seriesB[i].value))
                    }
                    val scale = (side * 0.44f) / maxAbs

                    for (i in 1 until n) {
                        val x0 = cx + seriesA[i - 1].value * scale
                        val y0 = cy - seriesB[i - 1].value * scale
                        val x1 = cx + seriesA[i].value * scale
                        val y1 = cy - seriesB[i].value * scale
                        val ageFrac = i.toFloat() / n
                        drawLine(
                            color = Color(0xFF00E5FF).copy(alpha = (ageFrac * ageFrac * 0.9f).coerceIn(0.06f, 0.95f)),
                            start = Offset(x0, y0),
                            end = Offset(x1, y1),
                            strokeWidth = (1.2f + ageFrac * 2.2f).dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }

                    // Head point
                    val hx = cx + seriesA[n - 1].value * scale
                    val hy = cy - seriesB[n - 1].value * scale
                    drawCircle(
                        color = Color(0xFF69F0AE),
                        radius = 4.5.dp.toPx(),
                        center = Offset(hx, hy)
                    )
                }
            }
        }
    }
}
