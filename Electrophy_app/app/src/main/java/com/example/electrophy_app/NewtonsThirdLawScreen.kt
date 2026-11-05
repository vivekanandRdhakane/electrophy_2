package com.example.electrophy_app

import android.os.SystemClock
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

private data class PlanarZero(val x: Float = 0f, val y: Float = 0f)

private fun zeroFrom(points: List<AxisPoint>): PlanarZero {
    val recent = points.takeLast(100)
    if (recent.isEmpty()) return PlanarZero()
    return PlanarZero(recent.map { it.x }.average().toFloat(), recent.map { it.y }.average().toFloat())
}

/** The sign comes from the shared collision axis, never from whichever component is larger. */
private fun signedPlanarG(point: AxisPoint, zero: PlanarZero, collisionAlongX: Boolean): Float {
    val x = point.x - zero.x
    val y = point.y - zero.y
    val magnitude = hypot(x, y)
    val along = if (collisionAlongX) x else y
    if (abs(along) < magnitude * 0.05f) return 0f
    return if (along >= 0f) magnitude else -magnitude
}

private fun impactPeakTime(points: List<AxisPoint>, zero: PlanarZero, collisionAlongX: Boolean,
                           timeWindowMs: Float): Float? {
    val latest = points.lastOrNull()?.time ?: return null
    return points.asReversed().asSequence()
        .takeWhile { it.time >= latest - timeWindowMs }
        .maxByOrNull { abs(signedPlanarG(it, zero, collisionAlongX)) }?.time
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
    val firstName by first.connectedDeviceName.collectAsState()
    val secondName by second.connectedDeviceName.collectAsState()
    val firstReceiveRate by first.receivedSamplesPerSecond.collectAsState()
    val secondReceiveRate by second.receivedSamplesPerSecond.collectAsState()
    val firstCapturedRate by first.lastActiveSamplesPerSecond.collectAsState()
    val secondCapturedRate by second.lastActiveSamplesPerSecond.collectAsState()
    val firstData by first.chartData.collectAsState()
    val secondData by second.chartData.collectAsState()
    val firstArrivalMs by first.lastSampleArrivalMs.collectAsState()
    val secondArrivalMs by second.lastSampleArrivalMs.collectAsState()
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
    var firstZero by remember { mutableStateOf(PlanarZero()) }
    var secondZero by remember { mutableStateOf(PlanarZero()) }
    var instructionsExpanded by remember { mutableStateOf(false) }
    var zeroed by remember { mutableStateOf(false) }
    var thresholdText by remember { mutableStateOf("5") }
    var calibrationExpanded by remember { mutableStateOf(false) }
    var samplingExpanded by remember { mutableStateOf(false) }
    var triggerExpanded by remember { mutableStateOf(false) }
    var capturedPeakTimes by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var cursorEnabled by remember { mutableStateOf(false) }

    var firstCrossedTime by remember { mutableLongStateOf(0L) }
    var secondCrossedTime by remember { mutableLongStateOf(0L) }
    var dualTriggerFired by remember { mutableStateOf(false) }

    LaunchedEffect(mode.id) {
        first.applyExperimentMode(mode)
        second.applyExperimentMode(mode)
    }
    DisposableEffect(first, second) {
        val COINCIDENCE_WINDOW_MS = 3000L

        fun checkBothCrossed() {
            if (dualTriggerFired || first.isPaused.value || second.isPaused.value) return
            val now = SystemClock.uptimeMillis()
            val firstValid = firstCrossedTime > 0L && (now - firstCrossedTime) <= COINCIDENCE_WINDOW_MS
            val secondValid = secondCrossedTime > 0L && (now - secondCrossedTime) <= COINCIDENCE_WINDOW_MS
            if (firstValid && secondValid) {
                dualTriggerFired = true
                val delay = maxOf(first.autoTriggerDelayMs.value, second.autoTriggerDelayMs.value)
                first.schedulePause(delay)
                second.schedulePause(delay)
            }
        }

        first.onThresholdCrossed = {
            if (!dualTriggerFired && !first.isPaused.value && !second.isPaused.value) {
                firstCrossedTime = SystemClock.uptimeMillis()
                checkBothCrossed()
            }
        }
        second.onThresholdCrossed = {
            if (!dualTriggerFired && !first.isPaused.value && !second.isPaused.value) {
                secondCrossedTime = SystemClock.uptimeMillis()
                checkBothCrossed()
            }
        }
        onDispose {
            first.onThresholdCrossed = null
            second.onThresholdCrossed = null
            first.onAutoTriggerPending = null
            second.onAutoTriggerPending = null
            first.onAutoTriggerCapture = null
            second.onAutoTriggerCapture = null
        }
    }

    val bothConnected = firstState == ConnectionState.Connected && secondState == ConnectionState.Connected
    val latestReceiveGapMs = if (firstArrivalMs != null && secondArrivalMs != null) {
        abs(firstArrivalMs!! - secondArrivalMs!!)
    } else null
    val windowMs = timeWindowSec * 1000f
    val firstShownRate = if (firstPaused) firstCapturedRate else firstReceiveRate
    val secondShownRate = if (secondPaused) secondCapturedRate else secondReceiveRate

    fun setTimeWindow(seconds: Float) {
        val delayMs = (seconds * 500f).roundToLong()
        first.setTimeWindow(seconds)
        second.setTimeWindow(seconds)
        first.setAutoTriggerDelayMs(delayMs)
        second.setAutoTriggerDelayMs(delayMs)
    }

    fun setAxis(alongX: Boolean) {
        collisionAlongX = alongX
        val axis = if (alongX) "X" else "Y"
        first.setAutoTriggerAxis(axis)
        second.setAutoTriggerAxis(axis)
    }

    LaunchedEffect(firstPaused, secondPaused) {
        if (!firstPaused || !secondPaused) {
            cursorEnabled = false
            dualTriggerFired = false
            firstCrossedTime = 0L
            secondCrossedTime = 0L
        }
    }

    LaunchedEffect(firstPaused, secondPaused, collisionAlongX, firstZero, secondZero,
        firstData.highG.isEmpty(), secondData.highG.isEmpty()) {
        capturedPeakTimes = if (firstPaused && secondPaused) {
            val firstPeak = impactPeakTime(first.chartData.value.highG, firstZero, collisionAlongX, 10000f)
            val secondPeak = impactPeakTime(second.chartData.value.highG, secondZero, collisionAlongX, 10000f)
            if (firstPeak != null && secondPeak != null) {
                setTimeWindow(0.010f)
                firstPeak to secondPeak
            } else null
        } else null
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Newton's Third Law") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("1: ${firstName ?: firstState.name}  ·  2: ${secondName ?: secondState.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (bothConnected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    if (timeWindowSec < 1f) {
                        setTimeWindow(3f)
                    }
                    if (firstPaused) first.togglePause()
                    if (secondPaused) second.togglePause()
                }, enabled = bothConnected && (firstPaused || secondPaused), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Resume") }
                TextButton(onClick = {
                    first.clearLogs()
                    second.clearLogs()
                }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Clear") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Collision direction", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 13.dp))
                FilterChip(collisionAlongX, onClick = { setAxis(true) }, label = { Text("X") })
                FilterChip(!collisionAlongX, onClick = { setAxis(false) }, label = { Text("Y") })
                if (firstPaused && secondPaused) {
                    Spacer(Modifier.weight(1f))
                    Text("Cursor", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 13.dp))
                    Switch(checked = cursorEnabled, onCheckedChange = { cursorEnabled = it })
                }
            }
            CollisionChart(
                firstPoints = if (firstState == ConnectionState.Connected) firstData.highG else emptyList(),
                secondPoints = if (secondState == ConnectionState.Connected) secondData.highG else emptyList(),
                firstZero = firstZero,
                secondZero = secondZero,
                collisionAlongX = collisionAlongX,
                timeWindowMs = windowMs,
                impactAnchors = if (firstPaused && secondPaused) capturedPeakTimes else null,
                cursorEnabled = cursorEnabled,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            Column(Modifier.fillMaxWidth().weight(0.9f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                NewtonSettingCard {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("Time Window: ${timeWindowOptions.minByOrNull { abs(it.seconds - timeWindowSec) }?.label ?: "10 ms"}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        val index = timeWindowOptions.indices.minByOrNull { abs(timeWindowOptions[it].seconds - timeWindowSec) } ?: 0
                        Slider(
                            value = index.toFloat(),
                            onValueChange = { setTimeWindow(timeWindowOptions[it.roundToLong().toInt().coerceIn(0, timeWindowOptions.lastIndex)].seconds) },
                            valueRange = 0f..timeWindowOptions.lastIndex.toFloat(),
                            steps = timeWindowOptions.size - 2,
                            modifier = Modifier.height(30.dp)
                        )
                    }
                }
                NewtonCollapsibleCard("Sensor Calibration (High-G Zero)", if (zeroed) "Both sensors zeroed" else "Tap to zero both sensors",
                    calibrationExpanded, { calibrationExpanded = !calibrationExpanded }) {
                        Text("Keep both strikers still, then zero both X/Y baselines.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            firstZero = zeroFrom(firstData.highG)
                            secondZero = zeroFrom(secondData.highG)
                            zeroed = true
                        }, enabled = bothConnected && firstData.highG.isNotEmpty() && secondData.highG.isNotEmpty()) {
                            Text("Zero both sensors")
                        }
                }
                NewtonCollapsibleCard("Sensor Sampling Rate and Range", "High-G · ${highGOdrOptions.find { it.suffix == highGOdr }?.label ?: highGOdr} · ${highGRangeOptions.find { it.suffix == highGRange }?.label ?: highGRange}",
                    samplingExpanded, { samplingExpanded = !samplingExpanded }) {
                        Text("High-G Accel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        CompactOdrDropdown("High-G", highGOdrOptions.filter { it.suffix != "off" }, highGOdr, true) {
                            first.setHighGOdr(it)
                            second.setHighGOdr(it)
                        }
                        CompactRangeDropdown("High-G", highGRangeOptions, highGRange, true) {
                            first.setHighGRange(it)
                            second.setHighGRange(it)
                        }
                }
                NewtonCollapsibleCard("Auto-Trigger / Spike Capture", if (triggerEnabled) "$triggerCondition · ${triggerThreshold.toInt()} g · ${triggerDelayMs} ms" else "Disabled",
                    triggerExpanded, { triggerExpanded = !triggerExpanded }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("Enable Auto-Trigger", style = MaterialTheme.typography.bodySmall)
                            Switch(checked = triggerEnabled, onCheckedChange = {
                                first.setAutoTriggerEnabled(it)
                                second.setAutoTriggerEnabled(it)
                            })
                        }
                        Text("Condition", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("Above", "Below", "Deviation").forEach { condition ->
                                FilterChip(triggerCondition == condition, onClick = {
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
                                    first.setAutoTriggerThreshold(it)
                                    second.setAutoTriggerThreshold(it)
                                }
                            },
                            label = { Text("Threshold (g)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Post-trigger delay: $triggerDelayMs ms (½ time window)", style = MaterialTheme.typography.bodySmall)
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text("Blue 1: ${if (firstState == ConnectionState.Connected && firstShownRate > 0) firstShownRate else "--"} samples/s   ·   Orange 2: ${if (secondState == ConnectionState.Connected && secondShownRate > 0) secondShownRate else "--"} samples/s${if (firstPaused && secondPaused) " (before pause)" else ""}",
                    style = MaterialTheme.typography.labelSmall)
                Text(
                    if (latestReceiveGapMs == null) "Latest packets: waiting for both devices"
                    else "Latest receive gap: ${String.format(Locale.US, "%.2f", latestReceiveGapMs / 1000.0)} s · ${if (firstPaused && secondPaused) "impact peaks aligned at 0" else "right edges aligned"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (latestReceiveGapMs != null && latestReceiveGapMs > 100L) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                )
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
        firstAnchor?.let { anchor -> firstPoints.first().time - anchor to firstPoints.last().time - anchor },
        secondAnchor?.let { anchor -> secondPoints.first().time - anchor to secondPoints.last().time - anchor }
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
