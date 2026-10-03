package com.example.electrophy_app

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperimentScreen(
    mode: ExperimentMode,
    viewModel: BleViewModel,
    onBack: () -> Unit,
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val chartData by viewModel.chartData.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val timeWindowSec by viewModel.timeWindowSec.collectAsState()
    val selectedMode by viewModel.selectedMode.collectAsState()

    val lowGOdr by viewModel.lowGOdr.collectAsState()
    val highGOdr by viewModel.highGOdr.collectAsState()
    val gyroOdr by viewModel.gyroOdr.collectAsState()
    val lowGRange by viewModel.lowGRange.collectAsState()
    val highGRange by viewModel.highGRange.collectAsState()
    val gyroRange by viewModel.gyroRange.collectAsState()

    val isFilterEnabled by viewModel.isFilterEnabled.collectAsState()
    val filterAlpha by viewModel.filterAlpha.collectAsState()
    var isFilterExpanded by remember { mutableStateOf(false) }

    val isAutoTriggerEnabled by viewModel.isAutoTriggerEnabled.collectAsState()
    val autoTriggerAxis by viewModel.autoTriggerAxis.collectAsState()
    val autoTriggerCondition by viewModel.autoTriggerCondition.collectAsState()
    val autoTriggerThreshold by viewModel.autoTriggerThreshold.collectAsState()
    val autoTriggerDelayMs by viewModel.autoTriggerDelayMs.collectAsState()
    var isAutoTriggerExpanded by remember { mutableStateOf(false) }

    var isOdrExpanded by remember { mutableStateOf(false) }
    var instructionsExpanded by remember { mutableStateOf(false) }

    var showOdrInfoDialog by remember { mutableStateOf(false) }
    var showTimeWindowInfoDialog by remember { mutableStateOf(false) }
    var showFilterInfoDialog by remember { mutableStateOf(false) }
    var showAutoTriggerInfoDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current

    LaunchedEffect(mode.id) {
        viewModel.applyExperimentMode(mode)
    }

    val cutoffHz = viewModel.getCalculatedCutoffFrequency()
    val cutoffText = if (cutoffHz >= 1000f) {
        String.format(Locale.US, "%.2f kHz", cutoffHz / 1000f)
    } else {
        String.format(Locale.US, "%.2f Hz", cutoffHz)
    }

    val filterSummary = if (isFilterEnabled) {
        String.format(Locale.US, "Enabled (α = %.2f, fc = %s)", filterAlpha, cutoffText)
    } else {
        "Disabled"
    }

    val lowGOdrLabel = lowGOdrOptions.find { it.suffix == lowGOdr }?.label ?: lowGOdr
    val highGOdrLabel = highGOdrOptions.find { it.suffix == highGOdr }?.label ?: highGOdr
    val gyroOdrLabel = gyroOdrOptions.find { it.suffix == gyroOdr }?.label ?: gyroOdr

    val lowGRangeLabel = lowGRangeOptions.find { it.suffix == lowGRange }?.label ?: lowGRange
    val highGRangeLabel = highGRangeOptions.find { it.suffix == highGRange }?.label ?: highGRange
    val gyroRangeLabel = gyroRangeOptions.find { it.suffix == gyroRange }?.label ?: gyroRange

    val odrText = when (selectedMode) {
        GraphMode.LOW_G -> "Low-G: $lowGOdrLabel"
        GraphMode.HIGH_G -> "High-G: $highGOdrLabel"
        GraphMode.GYRO -> "Gyro: $gyroOdrLabel"
        GraphMode.BOTH_ACC -> "Low-G: $lowGOdrLabel, High-G: $highGOdrLabel"
    }

    val rangeText = when (selectedMode) {
        GraphMode.LOW_G -> "Low-G: $lowGRangeLabel"
        GraphMode.HIGH_G -> "High-G: $highGRangeLabel"
        GraphMode.GYRO -> "Gyro: $gyroRangeLabel"
        GraphMode.BOTH_ACC -> "Low-G: $lowGRangeLabel, High-G: $highGRangeLabel"
    }

    val odrRangeSummary = "ODR: $odrText | Range: $rangeText"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(mode.icon, modifier = Modifier.padding(end = 8.dp))
                        Text(mode.name)
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("← Back", color = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = {
                    val color = when (connectionState) {
                        ConnectionState.Connected -> Color.Green
                        ConnectionState.Scanning -> Color.Yellow
                        ConnectionState.Connecting -> Color.Yellow
                        ConnectionState.Disconnected -> Color.Red
                    }
                    SuggestionChip(
                        onClick = { },
                        label = { Text(connectionState.name) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = color.copy(alpha = 0.2f),
                            labelColor = color
                        ),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            )
        },
        bottomBar = {
            BottomAppBar(
                modifier = Modifier.height(60.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = { viewModel.togglePause() },
                        enabled = connectionState == ConnectionState.Connected,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPaused) Color(0xFFFFA000) else MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Text(if (isPaused) "Resume" else "Pause")
                    }

                    Button(onClick = { viewModel.clearLogs() }) {
                        Text("Clear")
                    }

                    Button(
                        onClick = { viewModel.toggleRecording(context) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) Color.Red else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(if (isRecording) "Stop & Export" else "Record")
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Instructions Card (Collapsible)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { instructionsExpanded = !instructionsExpanded }
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Instructions & Setup", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(if (instructionsExpanded) "▲" else "▼")
                    }
                    if (instructionsExpanded) {
                        Text(
                            text = mode.instructions,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                        )
                    }
                }
            }

            // Real-time Chart Area (Weighted so controls below fit nicely)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .background(Color(0xFF101418))
            ) {
                val activeData = when(selectedMode) {
                    GraphMode.LOW_G -> chartData.lowG
                    GraphMode.HIGH_G -> chartData.highG
                    GraphMode.GYRO -> chartData.gyro
                    GraphMode.BOTH_ACC -> chartData.lowG
                }
                
                val title = if (mode.chartTitle.isNotEmpty()) "${mode.chartTitle} (${mode.yAxisUnit})" else "${selectedMode.label} (${mode.yAxisUnit})"
                
                ExperimentChart(
                    title = title,
                    points = activeData,
                    timeWindowSec = timeWindowSec,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Scrollable Configurations and Analysis Area Below the Plot
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.1f)
                    .verticalScroll(rememberScrollState())
            ) {
                // Analysis Results Panel
                val points = when(selectedMode) {
                    GraphMode.LOW_G -> chartData.lowG
                    GraphMode.HIGH_G -> chartData.highG
                    GraphMode.GYRO -> chartData.gyro
                    GraphMode.BOTH_ACC -> chartData.lowG
                }

                if (mode.showPeakForce || mode.showImpulse || mode.showPeriodDetection || mode.showVelocityIntegration || mode.showFreeFallDetection) {
                    Card(
                        modifier = Modifier
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
                            Text("Real-Time Experiment Analysis", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            
                            if (mode.showPeakForce) {
                                val peaks = findPeakValues(points)
                                Text("Peak Force: X = ${String.format(Locale.US, "%.1f", peaks.first)} | Y = ${String.format(Locale.US, "%.1f", peaks.second)} | Z = ${String.format(Locale.US, "%.1f", peaks.third)} ${mode.yAxisUnit}", style = MaterialTheme.typography.bodySmall)
                            }
                            
                            if (mode.showImpulse) {
                                val odrStr = highGOdr ?: lowGOdr ?: "100"
                                val odrHz = odrStr.replace("hz", "").toFloatOrNull() ?: 100f
                                val impulse = calculateImpulse(points, odrHz)
                                Text("Impulse Z (∫F·dt): ${String.format(Locale.US, "%.2f", impulse)} ${mode.yAxisUnit}·s", style = MaterialTheme.typography.bodySmall)
                            }

                            if (mode.showPeriodDetection) {
                                val period = detectPeriod(points)
                                if (period != null) {
                                    Text("Detected Period T: ${String.format(Locale.US, "%.3f", period)} s (f = ${String.format(Locale.US, "%.2f", 1f/period)} Hz)", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    Text("Detected Period: Waiting for oscillation data...", style = MaterialTheme.typography.bodySmall)
                                }
                            }

                            if (mode.showVelocityIntegration) {
                                val odrStr = highGOdr ?: lowGOdr ?: "100"
                                val odrHz = odrStr.replace("hz", "").toFloatOrNull() ?: 100f
                                val vel = estimateVelocityChange(points, odrHz)
                                Text("Est. Velocity Change Δv (Z): ${String.format(Locale.US, "%.2f", vel)} units/s", style = MaterialTheme.typography.bodySmall)
                            }

                            if (mode.showFreeFallDetection) {
                                val ffDuration = detectFreeFallDuration(points)
                                if (ffDuration != null) {
                                    Text("Free Fall Detected! Duration: ${String.format(Locale.US, "%.0f", ffDuration)} ms", style = MaterialTheme.typography.bodySmall, color = Color(0xFF69F0AE))
                                } else {
                                    Text("Free Fall Status: In gravity / resting", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }

                // Time Window Section
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Time Window: ${timeWindowOptions.find { it.seconds == timeWindowSec }?.label ?: "${timeWindowSec}s"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            InfoIconButton(onClick = { showTimeWindowInfoDialog = true })
                        }

                        val currentIndex = timeWindowOptions.indexOfFirst { it.seconds == timeWindowSec }.let { if (it < 0) 2 else it }
                        Slider(
                            value = currentIndex.toFloat(),
                            onValueChange = { newValue ->
                                val index = newValue.toInt().coerceIn(0, timeWindowOptions.size - 1)
                                viewModel.setTimeWindow(timeWindowOptions[index].seconds)
                            },
                            valueRange = 0f..(timeWindowOptions.size - 1).toFloat(),
                            steps = timeWindowOptions.size - 2,
                            modifier = Modifier.height(30.dp)
                        )
                    }
                }

                // Sensor Sampling Rate and Range Section (Collapsible)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isOdrExpanded = !isOdrExpanded }
                                .padding(bottom = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Sensor Sampling Rate and Range",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (!isOdrExpanded) {
                                    Text(
                                        text = odrRangeSummary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = if (isOdrExpanded) "▲" else "▼",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                InfoIconButton(onClick = { showOdrInfoDialog = true })
                            }
                        }

                        if (isOdrExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Sampling Rate (ODR)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                when (selectedMode) {
                                    GraphMode.LOW_G -> {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(modifier = Modifier.weight(1f)) {
                                                CompactOdrDropdown(
                                                    title = "Low-G",
                                                    options = lowGOdrOptions,
                                                    selectedSuffix = lowGOdr,
                                                    enabled = true,
                                                    onSelected = { viewModel.setLowGOdr(it) }
                                                )
                                            }
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                    GraphMode.HIGH_G -> {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(modifier = Modifier.weight(1f)) {
                                                CompactOdrDropdown(
                                                    title = "High-G",
                                                    options = highGOdrOptions,
                                                    selectedSuffix = highGOdr,
                                                    enabled = true,
                                                    onSelected = { viewModel.setHighGOdr(it) }
                                                )
                                            }
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                    GraphMode.GYRO -> {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(modifier = Modifier.weight(1f)) {
                                                CompactOdrDropdown(
                                                    title = "Gyro",
                                                    options = gyroOdrOptions,
                                                    selectedSuffix = gyroOdr,
                                                    enabled = true,
                                                    onSelected = { viewModel.setGyroOdr(it) }
                                                )
                                            }
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                    GraphMode.BOTH_ACC -> {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(modifier = Modifier.weight(1f)) {
                                                CompactOdrDropdown(
                                                    title = "Low-G",
                                                    options = lowGOdrOptions,
                                                    selectedSuffix = lowGOdr,
                                                    enabled = true,
                                                    onSelected = { viewModel.setLowGOdr(it) }
                                                )
                                            }
                                            Box(modifier = Modifier.weight(1f)) {
                                                CompactOdrDropdown(
                                                    title = "High-G",
                                                    options = highGOdrOptions,
                                                    selectedSuffix = highGOdr,
                                                    enabled = true,
                                                    onSelected = { viewModel.setHighGOdr(it) }
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Full-Scale Range",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                when (selectedMode) {
                                    GraphMode.LOW_G -> {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(modifier = Modifier.weight(1f)) {
                                                CompactRangeDropdown(
                                                    title = "Low-G",
                                                    options = lowGRangeOptions,
                                                    selectedSuffix = lowGRange,
                                                    enabled = true,
                                                    onSelected = { viewModel.setLowGRange(it) }
                                                )
                                            }
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                    GraphMode.HIGH_G -> {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(modifier = Modifier.weight(1f)) {
                                                CompactRangeDropdown(
                                                    title = "High-G",
                                                    options = highGRangeOptions,
                                                    selectedSuffix = highGRange,
                                                    enabled = true,
                                                    onSelected = { viewModel.setHighGRange(it) }
                                                )
                                            }
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                    GraphMode.GYRO -> {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(modifier = Modifier.weight(1f)) {
                                                CompactRangeDropdown(
                                                    title = "Gyro",
                                                    options = gyroRangeOptions,
                                                    selectedSuffix = gyroRange,
                                                    enabled = true,
                                                    onSelected = { viewModel.setGyroRange(it) }
                                                )
                                            }
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                    GraphMode.BOTH_ACC -> {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(modifier = Modifier.weight(1f)) {
                                                CompactRangeDropdown(
                                                    title = "Low-G",
                                                    options = lowGRangeOptions,
                                                    selectedSuffix = lowGRange,
                                                    enabled = true,
                                                    onSelected = { viewModel.setLowGRange(it) }
                                                )
                                            }
                                            Box(modifier = Modifier.weight(1f)) {
                                                CompactRangeDropdown(
                                                    title = "High-G",
                                                    options = highGRangeOptions,
                                                    selectedSuffix = highGRange,
                                                    enabled = true,
                                                    onSelected = { viewModel.setHighGRange(it) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Digital Filter Section (Collapsible)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isFilterExpanded = !isFilterExpanded }
                                .padding(bottom = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Digital Low-Pass Filter (EMA)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = filterSummary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = if (isFilterExpanded) "▲" else "▼",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                InfoIconButton(onClick = { showFilterInfoDialog = true })
                            }
                        }

                        if (isFilterExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Enable Filter",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Switch(
                                        checked = isFilterEnabled,
                                        onCheckedChange = { viewModel.setFilterEnabled(it) }
                                    )
                                }

                                if (isFilterEnabled) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Smoothing Alpha (α)",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = String.format(Locale.US, "%.2f", filterAlpha),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Slider(
                                            value = filterAlpha,
                                            onValueChange = { viewModel.setFilterAlpha(it) },
                                            valueRange = 0.01f..1f,
                                            steps = 98
                                        )
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 2.dp, bottom = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Cut-off Frequency (fc):",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = cutoffText,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Text(
                                            text = "Lower α = smoother (higher lag). Higher α = more responsive (less smoothing).",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Auto-Trigger Section (Collapsible)
                if (selectedMode != GraphMode.BOTH_ACC) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isAutoTriggerExpanded = !isAutoTriggerExpanded }
                                    .padding(bottom = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Auto-Trigger / Spike Capture",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    val triggerSummary = if (isAutoTriggerEnabled) {
                                        String.format(Locale.US, "Enabled (%s %s-axis, th=%.1f)", autoTriggerCondition, autoTriggerAxis, autoTriggerThreshold)
                                    } else {
                                        "Disabled"
                                    }
                                    Text(
                                        text = triggerSummary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = if (isAutoTriggerExpanded) "▲" else "▼",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    InfoIconButton(onClick = { showAutoTriggerInfoDialog = true })
                                }
                            }

                            if (isAutoTriggerExpanded) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Enable Auto-Trigger",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Switch(
                                            checked = isAutoTriggerEnabled,
                                            onCheckedChange = { viewModel.setAutoTriggerEnabled(it) }
                                        )
                                    }

                                    if (isAutoTriggerEnabled) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Condition",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                listOf("Above", "Below", "Deviation").forEach { cond ->
                                                    FilterChip(
                                                        selected = autoTriggerCondition == cond,
                                                        onClick = { viewModel.setAutoTriggerCondition(cond) },
                                                        label = { Text(cond) }
                                                    )
                                                }
                                            }
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Trigger Axis",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                listOf("X", "Y", "Z").forEach { axis ->
                                                    FilterChip(
                                                        selected = autoTriggerAxis == axis,
                                                        onClick = { viewModel.setAutoTriggerAxis(axis) },
                                                        label = { Text(axis) }
                                                    )
                                                }
                                            }
                                        }

                                        var thresholdText by remember(autoTriggerThreshold) { mutableStateOf(autoTriggerThreshold.toString()) }
                                        OutlinedTextField(
                                            value = thresholdText,
                                            onValueChange = { newVal ->
                                                thresholdText = newVal
                                                newVal.toFloatOrNull()?.let { viewModel.setAutoTriggerThreshold(it) }
                                            },
                                            label = { Text("Threshold Value") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodySmall,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                        )

                                        var delayText by remember(autoTriggerDelayMs) { mutableStateOf(autoTriggerDelayMs.toString()) }
                                        OutlinedTextField(
                                            value = delayText,
                                            onValueChange = { newVal ->
                                                delayText = newVal
                                                newVal.toLongOrNull()?.let { viewModel.setAutoTriggerDelayMs(it) }
                                            },
                                            label = { Text("Post-Trigger Delay (ms)") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodySmall,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Info Dialogs
    if (showAutoTriggerInfoDialog) {
        InfoAlertDialog(
            title = "Auto-Trigger / Spike Capture",
            infoText = "• Auto-Trigger automatically pauses the running plot when sensor signals meet preset criteria:\n\n" +
                    "• Trigger Conditions:\n" +
                    "  - Above: Triggers when Value ≥ Threshold\n" +
                    "  - Below: Triggers when Value ≤ Threshold\n" +
                    "  - Deviation: Triggers when |Value - Baseline| ≥ Threshold (ignores DC offset / baseline)\n\n" +
                    "• Trigger Axis: Monitor X, Y, or Z axis.\n\n" +
                    "• Post-Trigger Delay (ms): Allows recording a short window of data after the spike occurs before pausing the stream.",
            onDismiss = { showAutoTriggerInfoDialog = false }
        )
    }

    if (showOdrInfoDialog) {
        InfoAlertDialog(
            title = "Sensor Sampling Rate and Range",
            infoText = "• Output Data Rate (ODR):\n" +
                    "  - Low-G Accel: 1.875 Hz to 7680 Hz\n" +
                    "  - High-G Accel: 480 Hz to 7680 Hz\n" +
                    "  - Gyroscope: 7.5 Hz to 7680 Hz\n\n" +
                    "• Full-Scale Range (FS):\n" +
                    "  - Low-G Range: ±2 g to ±16 g\n" +
                    "  - High-G Range: ±32 g to ±320 g\n" +
                    "  - Gyroscope Range: ±250 dps to ±4000 dps",
            onDismiss = { showOdrInfoDialog = false }
        )
    }

    if (showTimeWindowInfoDialog) {
        InfoAlertDialog(
            title = "Time Window",
            infoText = "• Time Window controls the horizontal time duration displayed on the live sensor chart (ranging from 1 sec to 30 sec).\n\n" +
                    "• Drag the slider to expand or compress the horizontal time scale in real-time.",
            onDismiss = { showTimeWindowInfoDialog = false }
        )
    }

    if (showFilterInfoDialog) {
        InfoAlertDialog(
            title = "Digital Low-Pass Filter (EMA)",
            infoText = "• Exponential Moving Average (EMA) Filter:\n" +
                    "  y[n] = α · current_value + (1 - α) · y[n-1]\n\n" +
                    "• Smoothing Alpha (α):\n" +
                    "  - Values range from 0.01 to 1.0.\n" +
                    "  - Lower α provides heavier smoothing (higher lag).\n" +
                    "  - Higher α provides faster response (less smoothing).",
            onDismiss = { showFilterInfoDialog = false }
        )
    }
}

@Composable
private fun ExperimentChart(
    title: String,
    points: List<AxisPoint>,
    timeWindowSec: Float,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
        
        AndroidView(
            factory = { ctx ->
                LineChart(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    description.isEnabled = false
                    setTouchEnabled(false)
                    isDragEnabled = false
                    setScaleEnabled(false)
                    setPinchZoom(false)
                    setBackgroundColor(android.graphics.Color.parseColor("#101418"))
                    
                    xAxis.apply {
                        textColor = android.graphics.Color.WHITE
                        position = XAxis.XAxisPosition.BOTTOM
                        setDrawGridLines(true)
                        gridColor = android.graphics.Color.parseColor("#333333")
                        valueFormatter = object : ValueFormatter() {
                            override fun getFormattedValue(value: Float): String {
                                return String.format(Locale.US, "%.1fs", value / 1000f)
                            }
                        }
                    }
                    axisLeft.apply {
                        textColor = android.graphics.Color.WHITE
                        setDrawGridLines(true)
                        gridColor = android.graphics.Color.parseColor("#333333")
                    }
                    axisRight.isEnabled = false
                    legend.isEnabled = true
                    legend.textColor = android.graphics.Color.WHITE
                }
            },
            modifier = Modifier.fillMaxSize().padding(bottom = 4.dp),
            update = { chart ->
                val maxTime = if (points.isNotEmpty()) points.last().time else 0f
                val windowMs = timeWindowSec * 1000f
                val filtered = if (points.isEmpty()) emptyList() else points.filter { it.time >= maxTime - windowMs }

                val entriesX = filtered.map { Entry(it.time, it.x) }
                val entriesY = filtered.map { Entry(it.time, it.y) }
                val entriesZ = filtered.map { Entry(it.time, it.z) }

                val dataSets = mutableListOf<LineDataSet>()
                dataSets.add(LineDataSet(entriesX, "X").apply {
                    color = android.graphics.Color.RED
                    setDrawCircles(false)
                    lineWidth = 2f
                    setDrawValues(false)
                })
                dataSets.add(LineDataSet(entriesY, "Y").apply {
                    color = android.graphics.Color.GREEN
                    setDrawCircles(false)
                    lineWidth = 2f
                    setDrawValues(false)
                })
                dataSets.add(LineDataSet(entriesZ, "Z").apply {
                    color = android.graphics.Color.BLUE
                    setDrawCircles(false)
                    lineWidth = 2f
                    setDrawValues(false)
                })

                chart.data = LineData(dataSets.map { it as ILineDataSet })
                chart.notifyDataSetChanged()
                chart.invalidate()
            }
        )
    }
}

// --- Analysis Helpers ---

private fun findPeakValues(points: List<AxisPoint>): Triple<Float, Float, Float> {
    var maxX = 0f
    var maxY = 0f
    var maxZ = 0f
    for (p in points) {
        if (abs(p.x) > abs(maxX)) maxX = p.x
        if (abs(p.y) > abs(maxY)) maxY = p.y
        if (abs(p.z) > abs(maxZ)) maxZ = p.z
    }
    return Triple(maxX, maxY, maxZ)
}

private fun calculateImpulse(points: List<AxisPoint>, odrHz: Float): Float {
    if (points.isEmpty() || odrHz <= 0f) return 0f
    val dt = 1f / odrHz
    val baseline = points.take(10).map { it.z }.average().toFloat().takeIf { !it.isNaN() } ?: 0f
    
    var integral = 0f
    for (p in points) {
        val force = p.z - baseline
        integral += force * dt
    }
    return integral
}

private fun detectPeriod(points: List<AxisPoint>): Float? {
    if (points.size < 10) return null
    val meanZ = points.map { it.z }.average().toFloat()
    
    val crossings = mutableListOf<Float>()
    for (i in 1 until points.size) {
        val p1 = points[i-1]
        val p2 = points[i]
        if ((p1.z - meanZ) < 0 && (p2.z - meanZ) >= 0) {
            val slope = (p2.z - p1.z) / (p2.time - p1.time)
            val tZero = p1.time + (meanZ - p1.z) / slope
            crossings.add(tZero)
        }
    }
    
    if (crossings.size < 2) return null
    
    val periods = mutableListOf<Float>()
    for (i in 1 until crossings.size) {
        periods.add((crossings[i] - crossings[i-1]) / 1000f)
    }
    
    return periods.average().toFloat().takeIf { !it.isNaN() }
}

private fun estimateVelocityChange(points: List<AxisPoint>, odrHz: Float): Float {
    if (points.isEmpty() || odrHz <= 0f) return 0f
    val dt = 1f / odrHz
    val baseline = points.take(10).map { it.z }.average().toFloat().takeIf { !it.isNaN() } ?: 0f
    
    var velocity = 0f
    for (p in points) {
        val accel = p.z - baseline
        velocity += accel * dt
    }
    return velocity
}

private fun detectFreeFallDuration(points: List<AxisPoint>): Float? {
    if (points.isEmpty()) return null
    val threshold = 200f
    var startFfTime: Float? = null
    var maxDuration = 0f
    
    for (p in points) {
        val mag = sqrt(p.x*p.x + p.y*p.y + p.z*p.z)
        if (mag < threshold) {
            if (startFfTime == null) {
                startFfTime = p.time
            } else {
                val duration = p.time - startFfTime
                if (duration > maxDuration) {
                    maxDuration = duration
                }
            }
        } else {
            startFfTime = null
        }
    }
    return if (maxDuration > 10f) maxDuration else null
}