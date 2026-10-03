package com.example.electrophy_app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperimentPickerScreen(
    onExperimentSelected: (ExperimentMode) -> Unit,
    onBackToRawData: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Physics Experiments") }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Select an experiment mode to auto-configure sensor settings",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            
            item {
                OutlinedCard(
                    onClick = onBackToRawData,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📊", fontSize = 24.sp, modifier = Modifier.padding(end = 16.dp))
                        Column {
                            Text(
                                "Raw Data Mode",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "Full manual control over sensor settings",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            val categoryOrder = listOf("Flight & Aerodynamics", "Kinematics", "Mechanics")
            val groupedModes = experimentModes.groupBy { it.category }
            val sortedCategories = groupedModes.keys.sortedBy { cat ->
                val idx = categoryOrder.indexOf(cat)
                if (idx != -1) idx else categoryOrder.size
            }

            sortedCategories.forEach { category ->
                val modes = groupedModes[category] ?: return@forEach
                item {
                    Text(
                        text = category,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                    )
                }
                items(modes) { mode ->
                    ExperimentModeCard(mode = mode, onClick = { onExperimentSelected(mode) })
                }
            }
            
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun ExperimentModeCard(
    mode: ExperimentMode,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = mode.icon,
                fontSize = 32.sp,
                modifier = Modifier.padding(end = 16.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mode.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = mode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                
                // Config Chips Row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    val streamLabel = when(mode.streamMode) {
                        GraphMode.LOW_G -> "Low-G"
                        GraphMode.HIGH_G -> "High-G"
                        GraphMode.BOTH_ACC -> "Both-G"
                        GraphMode.GYRO -> "Gyro"
                        GraphMode.ALL -> "Accel+Gyro"
                    }
                    SmallConfigChip(streamLabel)
                    
                    val odr = mode.highGOdr ?: mode.lowGOdr ?: mode.gyroOdr
                    if (odr != null) {
                        SmallConfigChip(odr.uppercase())
                    }
                    
                    val range = mode.highGRange ?: mode.lowGRange ?: mode.gyroRange
                    if (range != null) {
                        SmallConfigChip("±$range")
                    }
                    
                    if (mode.autoTriggerEnabled) {
                        SmallConfigChip("Auto-Trigger")
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallConfigChip(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
