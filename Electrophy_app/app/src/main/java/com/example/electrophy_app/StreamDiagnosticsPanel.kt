package com.example.electrophy_app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun StreamDiagnosticsPanel(frame: FirmwareDiagnostics?, rates: FirmwareRates?, rx: ReceiveMetrics) {
    Column(Modifier.fillMaxWidth().heightIn(max = 170.dp).verticalScroll(rememberScrollState())
        .padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text("Received ${rx.samplesPerSecond} samples/s · ${rx.packetsPerSecond} packets/s",
            style = MaterialTheme.typography.labelMedium)
        if (frame == null) {
            Text("Waiting for firmware diagnostics (requires updated firmware).",
                style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Acquired ${rates?.acquired ?: "—"}/s · Submitted ${rates?.submitted ?: "—"}/s",
                style = MaterialTheme.typography.bodySmall)
            Text("Pending ${frame.pendingSamples} samples · Queue ${frame.queueDepth}/16 (peak ${frame.queuePeak})",
                style = MaterialTheme.typography.bodySmall)
            Text("Since boot: overflow ${frame.overflow} · discarded ${frame.discarded} · retries ${frame.retries}",
                style = MaterialTheme.typography.bodySmall)
            Text("DRDY coalesced ${frame.coalesced} · timeouts ${frame.timeouts} · read errors ${frame.readErrors}",
                style = MaterialTheme.typography.bodySmall)
            fun phy(v: Int) = when (v) { 1 -> "1M"; 2 -> "2M"; 3 -> "Coded"; else -> "?" }
            val rssi = if (frame.rssi == 127) "?" else "${frame.rssi} dBm"
            val interval = String.format(Locale.US, "%.2f", frame.intervalUnits * 1.25)
            Text("RSSI $rssi · PHY ${phy(frame.txPhy)}/${phy(frame.rxPhy)} · MTU ${frame.mtu}",
                style = MaterialTheme.typography.bodySmall)
            Text("Interval $interval ms · TX data length ${frame.txOctets.takeIf { it > 0 } ?: "?"} B",
                style = MaterialTheme.typography.bodySmall)
            Text("Submitted ${rates?.bytes ?: "—"} B/s · Free buffers ${frame.freeMbufs} · Send errors ${frame.notifyErrors}",
                style = MaterialTheme.typography.bodySmall)
        }
        Text("RX sequence gaps ${rx.sequenceGaps} · invalid frames ${rx.invalidPackets}",
            style = MaterialTheme.typography.bodySmall)
        Text("Submitted means accepted by the BLE stack, not confirmed on the phone. Rates use separate windows.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
