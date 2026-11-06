package com.example.electrophy_app

import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToLong
import java.util.Locale

/** The sign comes from the shared collision axis, never from whichever component is larger. */
private fun signedPlanarG(point: AxisPoint, zero: PlanarZero, collisionAlongX: Boolean): Float {
    return collisionSignedPlanarG(point, zero, collisionAlongX)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewtonsThirdLawScreen(
    first: BleViewModel,
    second: BleViewModel,
    mode: ExperimentMode,
    onBack: () -> Unit
) {
    val firstState by first.connectionState.collectAsState()
    val secondState by second.connectionState.collectAsState()
    val firstConfiguring by first.isConfiguring.collectAsState()
    val secondConfiguring by second.isConfiguring.collectAsState()
    val firstConnectionError by first.connectionError.collectAsState()
    val secondConnectionError by second.connectionError.collectAsState()
    val firstName by first.connectedDeviceName.collectAsState()
    val secondName by second.connectedDeviceName.collectAsState()
    val firstData by first.chartData.collectAsState()
    val secondData by second.chartData.collectAsState()
    val timeWindowSec by first.timeWindowSec.collectAsState()
    val highGOdr by first.highGOdr.collectAsState()
    val highGRange by first.highGRange.collectAsState()
    val triggerEnabled by first.isAutoTriggerEnabled.collectAsState()
    val triggerCondition by first.autoTriggerCondition.collectAsState()
    val triggerThreshold by first.autoTriggerThreshold.collectAsState()
    val triggerDelayMs by first.autoTriggerDelayMs.collectAsState()
    val firstPaused by first.isPaused.collectAsState()
    val secondPaused by second.isPaused.collectAsState()
    var collisionAlongX by remember { mutableStateOf(true) }
    var firstManualZero by remember { mutableStateOf(PlanarZero()) }
    var secondManualZero by remember { mutableStateOf(PlanarZero()) }
    var hasManualZero by remember { mutableStateOf(false) }
    var instructionsExpanded by remember { mutableStateOf(false) }
    var thresholdText by remember { mutableStateOf("5") }
    var calibrationExpanded by remember { mutableStateOf(false) }
    var samplingExpanded by remember { mutableStateOf(false) }
    var triggerExpanded by remember { mutableStateOf(false) }
    val pair = remember(first, second) { first.pairedCollision }
    val run by pair.state.collectAsState()
    var showPrevious by remember { mutableStateOf(false) }
    var cursorEnabled by remember { mutableStateOf(false) }
    var autoStartPending by remember { mutableStateOf(true) }
    val frozen = run.completed?.takeIf { run.phase == PairPhase.COMPLETE || showPrevious }
    val firstZero = frozen?.first?.baseline ?: run.firstZero
    val secondZero = frozen?.second?.baseline ?: run.secondZero
    val capturedPeakTimes = frozen?.let { it.first.peakTime to it.second.peakTime }
    val postImpactMs = frozen?.postNs?.div(1_000_000) ?: triggerDelayMs
    val zeroed = hasManualZero
    val bothConnected = firstState == ConnectionState.Connected && secondState == ConnectionState.Connected
    val bothReady = bothConnected && !firstConfiguring && !secondConfiguring
    val running = run.phase in setOf(PairPhase.BASELINE, PairPhase.ARMED, PairPhase.CAPTURING)
    val windowMs = timeWindowSec * 1000f

    fun setTimeWindow(seconds: Float) {
        first.setTimeWindow(seconds)
        second.setTimeWindow(seconds)
        val delayMs = (seconds * 500f).roundToLong()
        first.setAutoTriggerDelayMs(delayMs)
        second.setAutoTriggerDelayMs(delayMs)
    }

    fun stopRun(reason: String) {
        pair.interrupt(reason)
        first.cancelPendingCaptureTrigger()
        second.cancelPendingCaptureTrigger()
        first.setCapturePaused(true)
        second.setCapturePaused(true)
    }

    fun startRun(forceFreshSegment: Boolean = true) {
        // Read current values as setup may have started since this composition.
        if (!bothReady || first.isConfiguring.value || second.isConfiguring.value) return
        autoStartPending = false
        // An interrupted run has left both sensors streaming. Restart the
        // capture from incoming samples without a pause/resume BLE round trip.
        val restartStreaming = forceFreshSegment && (first.isPaused.value || second.isPaused.value)
        val previousEpochs = if (restartStreaming) listOf(first.latestAcquisitionEpoch, second.latestAcquisitionEpoch)
            else listOf(null, null)
        pair.interrupt("Starting a fresh run.")
        first.cancelPendingCaptureTrigger()
        second.cancelPendingCaptureTrigger()
        showPrevious = false
        cursorEnabled = false
        if (timeWindowSec < 1f) setTimeWindow(3f)
        if (restartStreaming) {
            first.setCapturePaused(true)
            second.setCapturePaused(true)
        }
        first.clearLogs()
        second.clearLogs()
        first.setCapturePaused(false)
        second.setCapturePaused(false)
        val halfWindowNs = (first.timeWindowSec.value * 500_000_000.0).toLong()
        pair.start(PairSettings(collisionAlongX, first.autoTriggerCondition.value,
            first.autoTriggerThreshold.value, halfWindowNs, halfWindowNs,
            false, firstName ?: "Device 1", secondName ?: "Device 2",
            collectBaseline = false, firstZero = firstManualZero, secondZero = secondManualZero),
            SystemClock.elapsedRealtime(), previousEpochs)
    }

    fun setAxis(alongX: Boolean) {
        stopRun("Collision direction changed.")
        collisionAlongX = alongX
        val axis = if (alongX) "X" else "Y"
        first.setAutoTriggerAxis(axis)
        second.setAutoTriggerAxis(axis)
    }

    LaunchedEffect(mode.id) {
        first.applyExperimentMode(mode)
        second.applyExperimentMode(mode)
    }
    DisposableEffect(first, second, pair) {
        // Keep the original threshold -> delayed paired pause interaction.
        // The controller owns crossing identity/cancellation, without readiness gates.
        fun thresholdCrossed(device: Int) {
            if (pair.thresholdCrossed(device, SystemClock.elapsedRealtime())) {
                val delayMs = maxOf(first.autoTriggerDelayMs.value, second.autoTriggerDelayMs.value)
                first.schedulePause(delayMs)
                second.schedulePause(delayMs)
            }
        }
        first.onThresholdCrossed = { thresholdCrossed(0) }
        second.onThresholdCrossed = { thresholdCrossed(1) }
        first.onPairedStreamInterrupted = { pair.interrupt("Device 1: $it") }
        second.onPairedStreamInterrupted = { pair.interrupt("Device 2: $it") }
        first.onPairedSegmentChanged = { pair.streamChanged(0, "Device 1 changed its data segment.", SystemClock.elapsedRealtime()) }
        second.onPairedSegmentChanged = { pair.streamChanged(1, "Device 2 changed its data segment.", SystemClock.elapsedRealtime()) }
        first.onPairedHighGBatch = { pair.accept(0, it, SystemClock.elapsedRealtime()) }
        second.onPairedHighGBatch = { pair.accept(1, it, SystemClock.elapsedRealtime()) }
        onDispose {
            val active = pair.state.value.phase in setOf(PairPhase.BASELINE, PairPhase.ARMED, PairPhase.CAPTURING)
            pair.interrupt("Experiment closed.")
            first.cancelPendingCaptureTrigger()
            second.cancelPendingCaptureTrigger()
            first.onThresholdCrossed = null
            second.onThresholdCrossed = null
            first.onPairedStreamInterrupted = null
            second.onPairedStreamInterrupted = null
            first.onPairedSegmentChanged = null
            second.onPairedSegmentChanged = null
            first.onPairedHighGBatch = null
            second.onPairedHighGBatch = null
            if (active) { first.setCapturePaused(true); second.setCapturePaused(true) }
        }
    }
    LaunchedEffect(pair) {
        while (true) {
            kotlinx.coroutines.delay(250)
            pair.tick(SystemClock.elapsedRealtime())
        }
    }
    LaunchedEffect(firstPaused, secondPaused, run.runId) {
        if (firstPaused && secondPaused && pair.state.value.runId == run.runId) pair.pause()
    }
    LaunchedEffect(firstState, secondState) {
        if (!bothConnected) pair.interrupt("Both devices must stay connected.")
    }
    val firstHasTimedHighG = firstData.highG.lastOrNull()?.acquisitionTimeNs != null
    val secondHasTimedHighG = secondData.highG.lastOrNull()?.acquisitionTimeNs != null
    LaunchedEffect(bothReady, firstHasTimedHighG, secondHasTimedHighG, autoStartPending) {
        if (autoStartPending && bothReady && firstHasTimedHighG && secondHasTimedHighG &&
            pair.state.value.phase == PairPhase.IDLE) startRun(forceFreshSegment = false)
    }
    LaunchedEffect(run.phase, run.runId) {
        if (pair.state.value.runId != run.runId || pair.state.value.phase != run.phase) return@LaunchedEffect
        if (run.phase == PairPhase.INTERRUPTED) {
            Log.w("ElectrophyBLE", "Paired capture interrupted run=${run.runId}: ${run.message}")
        }
        if (run.phase == PairPhase.COMPLETE || run.phase == PairPhase.INTERRUPTED) {
            first.cancelPendingCaptureTrigger()
            second.cancelPendingCaptureTrigger()
            if (shouldPauseSensorsForPairPhase(run.phase)) {
                first.setCapturePaused(true)
                second.setCapturePaused(true)
            }
            cursorEnabled = false
            if (run.phase == PairPhase.COMPLETE) setTimeWindow(0.010f)
            if (run.phase == PairPhase.INTERRUPTED) showPrevious = false
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Newton's Third Law") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
        )
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val controlsMaxHeight = maxHeight * 0.45f
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                NewtonSettingCard {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { instructionsExpanded = !instructionsExpanded }, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Instructions & Setup", style = MaterialTheme.typography.titleSmall)
                            Text(if (instructionsExpanded) "▲" else "▼")
                        }
                        if (instructionsExpanded) {
                            Text("Two strikers · signed planar acceleration (g)", style = MaterialTheme.typography.titleSmall)
                            Text(mode.instructions, style = MaterialTheme.typography.bodySmall)
                            Text("Match the sensor X/Y directions on both strikers. The graph sign follows the selected collision axis.", style = MaterialTheme.typography.bodySmall)
                            if (run.completed != null && run.phase != PairPhase.COMPLETE) {
                                TextButton(onClick = { showPrevious = !showPrevious; cursorEnabled = false }) {
                                    Text(if (showPrevious) "Show live data" else "View previous completed capture")
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("1: ${frozen?.firstName ?: firstName ?: firstState.name}  ·  2: ${frozen?.secondName ?: secondName ?: secondState.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (bothConnected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        if (running) {
                            pair.pause()
                            first.cancelPendingCaptureTrigger()
                            second.cancelPendingCaptureTrigger()
                            first.setCapturePaused(true)
                            second.setCapturePaused(true)
                        } else startRun()
                    }, enabled = bothReady || running, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(if (running) "Pause" else "Resume")
                    }
                    TextButton(onClick = {
                        val wasRunning = running
                        autoStartPending = false
                        stopRun("Live history cleared.")
                        pair.clearLive()
                        showPrevious = false
                        first.clearLogs()
                        second.clearLogs()
                        cursorEnabled = false
                        if (wasRunning) startRun()
                    }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Clear") }
                }
                if (run.phase == PairPhase.INTERRUPTED) {
                    Text(run.message.replace("Start a new run.", "Tap Resume to continue."),
                        Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                }
                if (run.phase != PairPhase.INTERRUPTED) {
                    firstConnectionError?.let { Text("Device 1: $it", Modifier.padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                    secondConnectionError?.let { Text("Device 2: $it", Modifier.padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Collision direction", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 13.dp))
                    FilterChip(collisionAlongX, onClick = { setAxis(true) }, label = { Text("X") })
                    FilterChip(!collisionAlongX, onClick = { setAxis(false) }, label = { Text("Y") })
                    if (frozen != null) {
                        Spacer(Modifier.weight(1f))
                        Text("Cursor", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 13.dp))
                        Switch(checked = cursorEnabled, onCheckedChange = { cursorEnabled = it })
                    }
                }
                CollisionChart(
                    firstPoints = frozen?.first?.points ?: run.interruptedPoints?.first
                        ?: if (firstState == ConnectionState.Connected) firstData.highG else emptyList(),
                    secondPoints = frozen?.second?.points ?: run.interruptedPoints?.second
                        ?: if (secondState == ConnectionState.Connected) secondData.highG else emptyList(),
                    firstZero = firstZero,
                    secondZero = secondZero,
                    collisionAlongX = frozen?.alongX ?: collisionAlongX,
                    timeWindowMs = windowMs,
                    impactAnchors = capturedPeakTimes,
                    cursorEnabled = cursorEnabled,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
                Column(Modifier.fillMaxWidth().heightIn(max = controlsMaxHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    NewtonSettingCard {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)) {
                            Text("Time Window: ${timeWindowOptions.minByOrNull { abs(it.seconds - timeWindowSec) }?.label ?: "10 ms"}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            val index = timeWindowOptions.indices.minByOrNull { abs(timeWindowOptions[it].seconds - timeWindowSec) } ?: 0
                            Slider(
                                value = index.toFloat(),
                                onValueChange = {
                                    if (running) stopRun("Capture duration changed.")
                                    setTimeWindow(timeWindowOptions[it.roundToLong().toInt().coerceIn(0, timeWindowOptions.lastIndex)].seconds)
                                },
                                valueRange = 0f..timeWindowOptions.lastIndex.toFloat(),
                                steps = timeWindowOptions.size - 2,
                                modifier = Modifier.height(30.dp)
                            )
                        }
                    }
                    NewtonCollapsibleCard("Sensor Calibration (High-G Zero)", if (zeroed) "Both sensors zeroed" else "Tap to zero both sensors",
                        calibrationExpanded, { calibrationExpanded = !calibrationExpanded }) {
                            Text("Keep both strikers still to refresh both X/Y baselines.", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                fun zero(points: List<AxisPoint>): PlanarZero {
                                    val recent = points.takeLast(100)
                                    return PlanarZero(recent.map { it.x }.average().toFloat(),
                                        recent.map { it.y }.average().toFloat())
                                }
                                firstManualZero = zero(firstData.highG)
                                secondManualZero = zero(secondData.highG)
                                hasManualZero = true
                                startRun()
                            }, enabled = bothConnected && firstData.highG.isNotEmpty() && secondData.highG.isNotEmpty()) {
                                Text("Zero both sensors")
                            }
                    }
                    NewtonCollapsibleCard("Sensor Sampling Rate and Range", "High-G · ${highGOdrOptions.find { it.suffix == highGOdr }?.label ?: highGOdr} · ${highGRangeOptions.find { it.suffix == highGRange }?.label ?: highGRange}",
                        samplingExpanded, { samplingExpanded = !samplingExpanded }) {
                            Text("High-G Accel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            Text("Missing samples are counted in diagnostics while the live plot continues.",
                                style = MaterialTheme.typography.bodySmall)
                            CompactOdrDropdown("High-G", highGOdrOptions.filter { it.suffix != "off" }, highGOdr, true) {
                                // Re-selecting the current rate must not restart the
                                // firmware's timing segment and interrupt this run.
                                if (first.highGOdr.value != it) first.setHighGOdr(it)
                                if (second.highGOdr.value != it) second.setHighGOdr(it)
                            }
                            CompactRangeDropdown("High-G", highGRangeOptions, highGRange, true) {
                                if (first.highGRange.value != it) first.setHighGRange(it)
                                if (second.highGRange.value != it) second.setHighGRange(it)
                            }
                    }
                    NewtonCollapsibleCard("Auto-Trigger / Spike Capture", if (triggerEnabled) "$triggerCondition · ${triggerThreshold.toInt()} g · $postImpactMs ms post-impact" else "Disabled",
                        triggerExpanded, { triggerExpanded = !triggerExpanded }) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("Enable Auto-Trigger", style = MaterialTheme.typography.bodySmall)
                                Switch(checked = triggerEnabled, onCheckedChange = {
                                    stopRun("Trigger setting changed.")
                                    first.setAutoTriggerEnabled(it)
                                    second.setAutoTriggerEnabled(it)
                                })
                            }
                            Text("Condition", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf("Above", "Below", "Deviation").forEach { condition ->
                                    FilterChip(triggerCondition == condition, onClick = {
                                        stopRun("Trigger condition changed.")
                                        first.setAutoTriggerCondition(condition)
                                        second.setAutoTriggerCondition(condition)
                                    }, label = { Text(condition) })
                                }
                            }
                            OutlinedTextField(
                                value = thresholdText,
                                onValueChange = { entered ->
                                    thresholdText = entered
                                    entered.toFloatOrNull()?.takeIf { it >= 0f }?.let {
                                        if (running) stopRun("Trigger threshold changed.")
                                        first.setAutoTriggerThreshold(it)
                                        second.setAutoTriggerThreshold(it)
                                    }
                                },
                                label = { Text("Threshold magnitude (g)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(if (frozen != null) "Captured post-impact interval: $postImpactMs ms. Plot zoom preserves saved samples."
                                else "Post-impact interval: $postImpactMs ms (half the capture window).", style = MaterialTheme.typography.bodySmall)
                            Text("Above: +threshold · Below: −threshold · Deviation: either direction.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun NewtonSettingCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) { content() }
}

@Composable
private fun NewtonCollapsibleCard(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    NewtonSettingCard {
        Column(Modifier.fillMaxWidth().padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    if (!expanded) Text(summary, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(if (expanded) "▲" else "▼", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (expanded) content()
        }
    }
}

@Composable
private fun CollisionChart(
    firstPoints: List<AxisPoint>,
    secondPoints: List<AxisPoint>,
    firstZero: PlanarZero,
    secondZero: PlanarZero,
    collisionAlongX: Boolean,
    timeWindowMs: Float,
    impactAnchors: Pair<Float, Float>?,
    cursorEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    var cursor1 by remember(cursorEnabled) { mutableStateOf<Entry?>(null) }
    var cursor2 by remember(cursorEnabled) { mutableStateOf<Entry?>(null) }
    val chartRef = remember { mutableStateOf<LineChart?>(null) }
    val peaksAligned = impactAnchors != null
    // Peak labels: data-space entries of the max-|y| point for each curve when paused
    var peakEntry1 by remember { mutableStateOf<Entry?>(null) }
    var peakEntry2 by remember { mutableStateOf<Entry?>(null) }
    val peakPaint1 = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.CYAN
            textSize = 36f; isFakeBoldText = true; isAntiAlias = true
            setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
        }
    }
    val peakPaint2 = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(255, 165, 0)
            textSize = 36f; isFakeBoldText = true; isAntiAlias = true
            setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
        }
    }
    LaunchedEffect(peaksAligned) {
        if (!peaksAligned) { peakEntry1 = null; peakEntry2 = null }
    }
    val firstAnchor = impactAnchors?.first ?: firstPoints.lastOrNull()?.time
    val secondAnchor = impactAnchors?.second ?: secondPoints.lastOrNull()?.time
    // X-axis animated zoom: after trigger pause, zoom to ±5 ms around the peak
    val animatedWindowMs = remember { Animatable(timeWindowMs) }
    val effectiveTimeWindowMs = animatedWindowMs.value  // drives recomposition every animation frame
    val defaultEnd = if (peaksAligned) effectiveTimeWindowMs / 2f else 0f
    val bounds = listOfNotNull(
        collisionTimeBounds(firstPoints, firstAnchor),
        collisionTimeBounds(secondPoints, secondAnchor)
    )
    val earliestShared = bounds.maxOfOrNull { it.first } ?: 0f
    val latestShared = bounds.minOfOrNull { it.second } ?: 0f
    val sliderMinEnd = earliestShared + effectiveTimeWindowMs
    val sliderMaxEnd = maxOf(defaultEnd, latestShared)
    val canBrowse = peaksAligned && sliderMinEnd < sliderMaxEnd
    var selectedEnd by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(peaksAligned, timeWindowMs) {
        selectedEnd = null
    }
    LaunchedEffect(firstPoints.isEmpty(), secondPoints.isEmpty()) {
        if (firstPoints.isEmpty() && secondPoints.isEmpty()) selectedEnd = null
    }
    var initialSpikeZoomDone by remember { mutableStateOf(false) }
    LaunchedEffect(peaksAligned, timeWindowMs) {
        if (peaksAligned) {
            if (!initialSpikeZoomDone) {
                initialSpikeZoomDone = true
                animatedWindowMs.animateTo(timeWindowMs, animationSpec = tween(durationMillis = 1200))
            } else {
                animatedWindowMs.animateTo(timeWindowMs, animationSpec = tween(durationMillis = 250))
            }
        } else {
            initialSpikeZoomDone = false
            animatedWindowMs.snapTo(timeWindowMs)
        }
    }
    // Y-axis animation: wide default (±100g) when live, auto-fit after trigger pause
    var computedPeak by remember { mutableFloatStateOf(0f) }
    val animatedYMax = remember { Animatable(100f) }
    val currentYMax = animatedYMax.value  // reading here ties recomposition to animation frames
    LaunchedEffect(peaksAligned) {
        if (!peaksAligned) {
            computedPeak = 0f
            animatedYMax.animateTo(100f, animationSpec = tween(durationMillis = 400))
        }
    }
    LaunchedEffect(computedPeak) {
        if (peaksAligned && computedPeak > 1f) {
            animatedYMax.animateTo(computedPeak * 1.15f, animationSpec = tween(durationMillis = 700))
        }
    }
    val viewEnd = if (canBrowse) (selectedEnd ?: defaultEnd).coerceIn(sliderMinEnd, sliderMaxEnd) else defaultEnd
    val viewStart = viewEnd - effectiveTimeWindowMs

    Column(modifier = modifier) {
    Box(Modifier.fillMaxWidth().weight(1f).then(
        if (cursorEnabled) Modifier.pointerInput(viewStart, viewEnd) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    val chart = chartRef.value ?: continue
                    if (pressed.size >= 2) {
                        val first = chart.getHighlightByTouchPoint(pressed[0].position.x, pressed[0].position.y)
                        val second = chart.getHighlightByTouchPoint(pressed[1].position.x, pressed[1].position.y)
                        if (first != null && second != null) {
                            cursor1 = Entry(first.x, first.y)
                            cursor2 = Entry(second.x, second.y)
                        }
                        event.changes.forEach { it.consume() }
                    } else if (pressed.size == 1) {
                        val point = chart.getHighlightByTouchPoint(pressed[0].position.x, pressed[0].position.y)
                        if (point != null) {
                            cursor1 = Entry(point.x, point.y)
                            cursor2 = null
                        }
                        pressed[0].consume()
                    }
                }
            }
        } else Modifier
    )) {
    AndroidView(
        factory = { context ->
            LineChart(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                description.isEnabled = false
                setBackgroundColor(android.graphics.Color.rgb(16, 20, 24))
                xAxis.position = XAxis.XAxisPosition.BOTTOM
                xAxis.textColor = android.graphics.Color.WHITE
                axisLeft.textColor = android.graphics.Color.WHITE
                axisLeft.axisMinimum = -1f
                axisLeft.axisMaximum = 1f
                axisRight.isEnabled = false
                legend.textColor = android.graphics.Color.WHITE
                setTouchEnabled(true)
                setScaleEnabled(true)
                setPinchZoom(false)
                isDragEnabled = false
                chartRef.value = this
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { chart ->
            chartRef.value = chart
            chart.setTouchEnabled(!cursorEnabled)
            chart.setScaleEnabled(!cursorEnabled)
            chart.setPinchZoom(false)
            chart.isDoubleTapToZoomEnabled = !cursorEnabled
            fun entries(points: List<AxisPoint>, zero: PlanarZero, peakTime: Float?): List<Entry> {
                val anchor = peakTime ?: points.lastOrNull()?.time ?: return emptyList()
                val visible = points.filter { it.time >= anchor + viewStart && it.time <= anchor + viewEnd }
                val signed = visible.map {
                    AxisPoint(signedPlanarG(it, zero, collisionAlongX), 0f, 0f, it.time - anchor)
                }
                return ChartDecimator.decimate(signed, 1200).map { Entry(it.time, it.x) }
            }
            val firstEntries = entries(firstPoints, firstZero, impactAnchors?.first)
            val secondEntries = entries(secondPoints, secondZero, impactAnchors?.second)
            // Capture peak entries for label overlay (only when paused with aligned peaks)
            if (peaksAligned) {
                val p1 = firstEntries.maxByOrNull { abs(it.y) }
                val p2 = secondEntries.maxByOrNull { abs(it.y) }
                if (p1 != null && peakEntry1 != p1) peakEntry1 = p1
                if (p2 != null && peakEntry2 != p2) peakEntry2 = p2
            }
            val peak = (firstEntries + secondEntries).maxOfOrNull { abs(it.y) }?.coerceAtLeast(1f) ?: 1f
            // Feed back peak when paused so the animation LaunchedEffect can target it
            if (peaksAligned && computedPeak != peak) computedPeak = peak
            chart.axisLeft.axisMinimum = -currentYMax
            chart.axisLeft.axisMaximum = currentYMax
            if (peaksAligned) {
                chart.axisLeft.setLabelCount(11, true)
            } else {
                chart.axisLeft.setLabelCount(6, false)
            }
            chart.xAxis.axisMinimum = viewStart
            chart.xAxis.axisMaximum = viewEnd
            chart.xAxis.valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float) =
                    if (effectiveTimeWindowMs < 1000f) String.format(Locale.US, "%.0f ms", value)
                    else String.format(Locale.US, "%.2f s", value / 1000f)
            }
            chart.data = LineData(
                LineDataSet(firstEntries, "Device 1").apply {
                    color = android.graphics.Color.CYAN
                    setDrawCircles(false)
                    setDrawValues(false)
                    lineWidth = 2f
                },
                LineDataSet(secondEntries, "Device 2").apply {
                    color = android.graphics.Color.rgb(255, 165, 0)
                    setDrawCircles(false)
                    setDrawValues(false)
                    lineWidth = 2f
                }
            )
            chart.invalidate()
        }
    )
    Canvas(Modifier.fillMaxSize()) {
        val chart = chartRef.value ?: return@Canvas
        // Cursor crosshairs
        listOf(cursor1 to Color(0xFF00E5FF), cursor2 to Color(0xFFFFAB40)).forEach { (entry, color) ->
            if (entry != null && entry.x in viewStart..viewEnd) {
                val pixel = chart.getPixelForValues(entry.x, entry.y, YAxis.AxisDependency.LEFT)
                val x = pixel.x.toFloat()
                val y = pixel.y.toFloat()
                drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f)
                drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f)
                drawCircle(color, radius = 5f, center = Offset(x, y))
            }
        }
        // Peak value labels (shown when paused with aligned peaks)
        if (peaksAligned) {
            listOf(peakEntry1 to peakPaint1, peakEntry2 to peakPaint2).forEach { (entry, paint) ->
                if (entry != null) {
                    val pixel = chart.getPixelForValues(entry.x, entry.y, YAxis.AxisDependency.LEFT)
                    val px = pixel.x.toFloat()
                    val py = pixel.y.toFloat()
                    if (px in 0f..size.width && py in 0f..size.height) {
                        val label = String.format(java.util.Locale.US, "%.2f g", entry.y)
                        // Place label above peak if positive, below if negative
                        val labelY = if (entry.y >= 0f) (py - 14f).coerceAtLeast(paint.textSize) else py + paint.textSize + 4f
                        // Clamp label horizontally so it stays within chart
                        val labelX = (px + 8f).coerceAtMost(size.width - paint.measureText(label) - 4f)
                        // Dot at the peak point
                        drawCircle(Color(paint.color), radius = 6f, center = Offset(px, py))
                        drawContext.canvas.nativeCanvas.drawText(label, labelX, labelY, paint)
                    }
                }
            }
        }
    }
    }
    if (cursorEnabled) {
        Text(
            text = if (cursor1 != null && cursor2 != null) {
                val dt = cursor2!!.x - cursor1!!.x
                val dv = cursor2!!.y - cursor1!!.y
                "C1 ${String.format(Locale.US, "%.2f ms, %.2f g", cursor1!!.x, cursor1!!.y)}  ·  " +
                    "C2 ${String.format(Locale.US, "%.2f ms, %.2f g", cursor2!!.x, cursor2!!.y)}  ·  " +
                    "Δt ${String.format(Locale.US, "%.2f ms", dt)}, Δa ${String.format(Locale.US, "%.2f g", dv)}"
            } else if (cursor1 != null) {
                "C1 ${String.format(Locale.US, "%.2f ms, %.2f g", cursor1!!.x, cursor1!!.y)} · Touch with two fingers for C1 and C2"
            } else "Touch the paused plot with two fingers for C1 and C2",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
    if (peaksAligned) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("◀ Past", style = MaterialTheme.typography.labelSmall,
                color = if (canBrowse) Color(0xFF00E5FF) else Color.Gray)
            Slider(
                value = if (canBrowse) viewEnd else 0f,
                onValueChange = { selectedEnd = it },
                valueRange = if (canBrowse) sliderMinEnd..sliderMaxEnd else 0f..1f,
                enabled = canBrowse,
                modifier = Modifier.weight(1f).height(28.dp).padding(horizontal = 6.dp),
                colors = SliderDefaults.colors(thumbColor = Color(0xFF00E5FF),
                    activeTrackColor = Color(0xFF00E5FF), inactiveTrackColor = Color(0xFF444444))
            )
            Text("Recent ▶", style = MaterialTheme.typography.labelSmall,
                color = if (canBrowse) Color(0xFF00E5FF) else Color.Gray)
        }
    }
    }
}
