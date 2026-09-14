package com.example.electrophy_app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.Locale
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.github.mikephil.charting.utils.MPPointD
import kotlin.math.abs

// UUIDs
val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
val TX_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // Notify (ESP -> App)
val RX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // Write (App -> ESP)
val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

enum class ConnectionState { Disconnected, Scanning, Connecting, Connected }

enum class GraphMode(val label: String, val command: String) {
    LOW_G("Low-G Accel", "low_acc"),
    HIGH_G("High-G Accel", "high_acc"),
    BOTH_ACC("Both Accel", "both_acc"),
    GYRO("Gyro", "only_gyro"),
}

private const val MAX_CHART_POINTS = 1500
private const val SAMPLE_RATE_UPDATE_INTERVAL_MS = 2_000L
private const val PREFERRED_BLE_MTU = 517
private const val BLE_TAG = "ElectrophyBLE"

data class AxisPoint(val x: Float, val y: Float, val z: Float, val time: Float = 0f)

data class ChartData(
    val lowG: List<AxisPoint> = emptyList(),
    val highG: List<AxisPoint> = emptyList(),
    val gyro: List<AxisPoint> = emptyList(),
)

data class OdrOption(val label: String, val suffix: String)

data class RangeOption(val label: String, val suffix: String)

data class TimeWindowOption(val label: String, val seconds: Float)

val timeWindowOptions = listOf(
    TimeWindowOption("1 sec", 1f),
    TimeWindowOption("3 sec", 3f),
    TimeWindowOption("5 sec", 5f),
    TimeWindowOption("10 sec", 10f),
    TimeWindowOption("30 sec", 30f),
)

val lowGRangeOptions = listOf(
    RangeOption("±2 g", "2g"),
    RangeOption("±4 g", "4g"),
    RangeOption("±8 g", "8g"),
    RangeOption("±16 g", "16g"),
)

val highGRangeOptions = listOf(
    RangeOption("±32 g", "32g"),
    RangeOption("±64 g", "64g"),
    RangeOption("±128 g", "128g"),
    RangeOption("±256 g", "256g"),
    RangeOption("±320 g", "320g"),
)

val gyroRangeOptions = listOf(
    RangeOption("±250 dps", "250dps"),
    RangeOption("±500 dps", "500dps"),
    RangeOption("±1000 dps", "1000dps"),
    RangeOption("±2000 dps", "2000dps"),
    RangeOption("±4000 dps", "4000dps"),
)

val lowGOdrOptions = listOf(
    OdrOption("Power-down", "off"),
    OdrOption("1.875 Hz", "1hz875"),
    OdrOption("7.5 Hz", "7hz5"),
    OdrOption("15 Hz", "15hz"),
    OdrOption("30 Hz", "30hz"),
    OdrOption("60 Hz", "60hz"),
    OdrOption("120 Hz", "120hz"),
    OdrOption("240 Hz", "240hz"),
    OdrOption("480 Hz", "480hz"),
    OdrOption("960 Hz", "960hz"),
    OdrOption("1920 Hz", "1920hz"),
    OdrOption("3840 Hz", "3840hz"),
    OdrOption("7680 Hz", "7680hz"),
)

val highGOdrOptions = listOf(
    OdrOption("Power-down", "off"),
    OdrOption("480 Hz", "480hz"),
    OdrOption("960 Hz", "960hz"),
    OdrOption("1920 Hz", "1920hz"),
    OdrOption("3840 Hz", "3840hz"),
    OdrOption("7680 Hz", "7680hz"),
)

val gyroOdrOptions = listOf(
    OdrOption("Power-down", "off"),
    OdrOption("7.5 Hz", "7hz5"),
    OdrOption("15 Hz", "15hz"),
    OdrOption("30 Hz", "30hz"),
    OdrOption("60 Hz", "60hz"),
    OdrOption("120 Hz", "120hz"),
    OdrOption("240 Hz", "240hz"),
    OdrOption("480 Hz", "480hz"),
    OdrOption("960 Hz", "960hz"),
    OdrOption("1920 Hz", "1920hz"),
    OdrOption("3840 Hz", "3840hz"),
    OdrOption("7680 Hz", "7680hz"),
)

/**
 * MSB-first bit reader used to unpack the 12-bit packed sample stream.
 * `startBit` lets it skip the fixed 9-byte packet header.
 */
private class BitReader(private val data: ByteArray, startBit: Int = 0) {
    var bitPos = startBit
        private set

    fun readBits(n: Int): Int {
        if (bitPos + n > data.size * 8) return 0 // malformed packet guard
        var v = 0
        for (i in 0 until n) {
            val byte = data[bitPos shr 3].toInt() and 0xFF
            val bit = (byte ushr (7 - (bitPos and 7))) and 1
            v = (v shl 1) or bit
            bitPos++
        }
        return v
    }

    /** Reads 12 bits and sign-extends them to a signed Int. */
    fun readSigned12(): Int {
        val v = readBits(12)
        return if (v and 0x800 != 0) v - 0x1000 else v
    }
}

@SuppressLint("MissingPermission")
class BleViewModel : ViewModel() {
    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private var sessionStartTimeMs = System.currentTimeMillis()
    private var lastAssignedTimeMs = 0L
    private var lastTerminalUpdateTimeMs = 0L
    private var sampleRateWindowStartMs = SystemClock.elapsedRealtime()
    private var samplesInRateWindow = 0

    private fun resetSession() {
        sessionStartTimeMs = System.currentTimeMillis()
        lastAssignedTimeMs = 0L
        lastTerminalUpdateTimeMs = 0L
        sampleRateWindowStartMs = SystemClock.elapsedRealtime()
        samplesInRateWindow = 0
        _receivedSamplesPerSecond.value = 0
        resetFilterState()
    }

    private val _isFilterEnabled = MutableStateFlow(false)
    val isFilterEnabled: StateFlow<Boolean> = _isFilterEnabled

    private val _filterAlpha = MutableStateFlow(0.2f)
    val filterAlpha: StateFlow<Float> = _filterAlpha

    fun setFilterEnabled(enabled: Boolean) {
        _isFilterEnabled.value = enabled
        resetFilterState()
    }

    fun setFilterAlpha(alpha: Float) {
        _filterAlpha.value = alpha.coerceIn(0f, 1f)
    }

    private var prevLowGX = 0f; private var prevLowGY = 0f; private var prevLowGZ = 0f; private var hasPrevLowG = false
    private var prevHighGX = 0f; private var prevHighGY = 0f; private var prevHighGZ = 0f; private var hasPrevHighG = false
    private var prevGyroX = 0f; private var prevGyroY = 0f; private var prevGyroZ = 0f; private var hasPrevGyro = false

    private fun resetFilterState() {
        hasPrevLowG = false
        hasPrevHighG = false
        hasPrevGyro = false
    }

    private fun filterLowG(points: List<AxisPoint>): List<AxisPoint> {
        if (!_isFilterEnabled.value) return points
        val alpha = _filterAlpha.value
        return points.map { pt ->
            val fx = if (!hasPrevLowG) { prevLowGX = pt.x; prevLowGY = pt.y; prevLowGZ = pt.z; hasPrevLowG = true; pt.x } else { alpha * pt.x + (1f - alpha) * prevLowGX }.also { prevLowGX = it }
            val fy = (alpha * pt.y + (1f - alpha) * prevLowGY).also { prevLowGY = it }
            val fz = (alpha * pt.z + (1f - alpha) * prevLowGZ).also { prevLowGZ = it }
            AxisPoint(fx, fy, fz, pt.time)
        }
    }

    private fun filterHighG(points: List<AxisPoint>): List<AxisPoint> {
        if (!_isFilterEnabled.value) return points
        val alpha = _filterAlpha.value
        return points.map { pt ->
            val fx = if (!hasPrevHighG) { prevHighGX = pt.x; prevHighGY = pt.y; prevHighGZ = pt.z; hasPrevHighG = true; pt.x } else { alpha * pt.x + (1f - alpha) * prevHighGX }.also { prevHighGX = it }
            val fy = (alpha * pt.y + (1f - alpha) * prevHighGY).also { prevHighGY = it }
            val fz = (alpha * pt.z + (1f - alpha) * prevHighGZ).also { prevHighGZ = it }
            AxisPoint(fx, fy, fz, pt.time)
        }
    }

    private fun filterGyro(points: List<AxisPoint>): List<AxisPoint> {
        if (!_isFilterEnabled.value) return points
        val alpha = _filterAlpha.value
        return points.map { pt ->
            val fx = if (!hasPrevGyro) { prevGyroX = pt.x; prevGyroY = pt.y; prevGyroZ = pt.z; hasPrevGyro = true; pt.x } else { alpha * pt.x + (1f - alpha) * prevGyroX }.also { prevGyroX = it }
            val fy = (alpha * pt.y + (1f - alpha) * prevGyroY).also { prevGyroY = it }
            val fz = (alpha * pt.z + (1f - alpha) * prevGyroZ).also { prevGyroZ = it }
            AxisPoint(fx, fy, fz, pt.time)
        }
    }

    private fun parseOdrToHz(odrSuffix: String): Float {
        return when (odrSuffix) {
            "off" -> 0f
            "1hz875" -> 1.875f
            "7hz5" -> 7.5f
            "15hz" -> 15f
            "30hz" -> 30f
            "60hz" -> 60f
            "120hz" -> 120f
            "240hz" -> 240f
            "480hz" -> 480f
            "960hz" -> 960f
            "1920hz" -> 1920f
            "3840hz" -> 3840f
            "7680hz" -> 7680f
            else -> 15f
        }
    }

    private fun calculateCutoffFrequency(fs: Float, alpha: Float): Float {
        if (fs <= 0f || alpha >= 1f || alpha <= 0f) return 0f
        return (alpha * fs) / (2f * Math.PI.toFloat() * (1f - alpha))
    }

    fun getCalculatedCutoffFrequency(): Float {
        val fs = when (_selectedMode.value) {
            GraphMode.LOW_G -> parseOdrToHz(_lowGOdr.value)
            GraphMode.HIGH_G -> parseOdrToHz(_highGOdr.value)
            GraphMode.GYRO -> parseOdrToHz(_gyroOdr.value)
            GraphMode.BOTH_ACC -> {
                val f1 = parseOdrToHz(_lowGOdr.value)
                val f2 = parseOdrToHz(_highGOdr.value)
                if (f1 > 0f && f2 > 0f) minOf(f1, f2) else maxOf(f1, f2)
            }
        }
        return calculateCutoffFrequency(fs, _filterAlpha.value)
    }

    private val _logMessages = MutableStateFlow<List<String>>(emptyList())
    val logMessages: StateFlow<List<String>> = _logMessages

    private val _receivedSamplesPerSecond = MutableStateFlow(0)
    val receivedSamplesPerSecond: StateFlow<Int> = _receivedSamplesPerSecond

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _selectedMode = MutableStateFlow(GraphMode.LOW_G)
    val selectedMode: StateFlow<GraphMode> = _selectedMode

    private val _lowGOdr = MutableStateFlow("15hz")
    val lowGOdr: StateFlow<String> = _lowGOdr

    private val _highGOdr = MutableStateFlow("480hz")
    val highGOdr: StateFlow<String> = _highGOdr

    private val _gyroOdr = MutableStateFlow("15hz")
    val gyroOdr: StateFlow<String> = _gyroOdr

    private val _lowGRange = MutableStateFlow("2g")
    val lowGRange: StateFlow<String> = _lowGRange

    private val _highGRange = MutableStateFlow("320g")
    val highGRange: StateFlow<String> = _highGRange

    private val _gyroRange = MutableStateFlow("2000dps")
    val gyroRange: StateFlow<String> = _gyroRange

    private val _timeWindowSec = MutableStateFlow(5f)
    val timeWindowSec: StateFlow<Float> = _timeWindowSec

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused

    fun togglePause() {
        if (_connectionState.value != ConnectionState.Connected) return
        val nextState = !_isPaused.value
        _isPaused.value = nextState
        if (nextState) {
            sendCommand("pause")
        } else {
            sendCommand("resume")
        }
    }

    fun setTimeWindow(seconds: Float) {
        _timeWindowSec.value = seconds
    }

    fun setLowGRange(suffix: String) {
        if (_lowGRange.value == suffix) return
        _lowGRange.value = suffix
        if (_connectionState.value == ConnectionState.Connected) {
            sendCommand("range_low_g_$suffix")
        }
    }

    fun setHighGRange(suffix: String) {
        if (_highGRange.value == suffix) return
        _highGRange.value = suffix
        if (_connectionState.value == ConnectionState.Connected) {
            sendCommand("range_high_g_$suffix")
        }
    }

    fun setGyroRange(suffix: String) {
        if (_gyroRange.value == suffix) return
        _gyroRange.value = suffix
        if (_connectionState.value == ConnectionState.Connected) {
            sendCommand("range_gyro_$suffix")
        }
    }

    fun setLowGOdr(suffix: String) {
        if (_lowGOdr.value == suffix) return
        _lowGOdr.value = suffix
        if (_connectionState.value == ConnectionState.Connected) {
            sendCommand("odr_low_g_$suffix")
        }
    }

    fun setHighGOdr(suffix: String) {
        if (_highGOdr.value == suffix) return
        _highGOdr.value = suffix
        if (_connectionState.value == ConnectionState.Connected) {
            sendCommand("odr_high_g_$suffix")
        }
    }

    fun setGyroOdr(suffix: String) {
        if (_gyroOdr.value == suffix) return
        _gyroOdr.value = suffix
        if (_connectionState.value == ConnectionState.Connected) {
            sendCommand("odr_gyro_$suffix")
        }
    }

    private val _chartData = MutableStateFlow(ChartData())
    val chartData: StateFlow<ChartData> = _chartData

    private val xyzRegex = Regex(
        """X\s*=\s*(-?\d+(?:\.\d+)?)\s+Y\s*=\s*(-?\d+(?:\.\d+)?)\s+Z\s*=\s*(-?\d+(?:\.\d+)?)"""
    )

    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var scanner: BluetoothLeScanner? = null
    private var context: Context? = null

    private var logFile: File? = null
    private var pendingData = ""

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val deviceName = device.name ?: return
            if (deviceName == "ESP_IMU") {
                stopScan()
                connectToDevice(device)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = ConnectionState.Connecting
                bluetoothGatt = gatt
                // Request the link settings needed for high-rate binary IMU streaming.
                // Android or the peripheral can negotiate lower values when unsupported.
                requestFastLinkParams()
                gatt.setPreferredPhy(
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_OPTION_NO_PREFERRED
                )
                gatt.requestMtu(PREFERRED_BLE_MTU)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectionState.value = ConnectionState.Disconnected
                _isPaused.value = false
                bluetoothGatt?.close()
                bluetoothGatt = null
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(BLE_TAG, "MTU negotiated: $mtu bytes (status=$status)")
            gatt.discoverServices()
        }

        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            Log.i(BLE_TAG,
                "PHY negotiated: tx=$txPhy (2M=${txPhy == BluetoothDevice.PHY_LE_2M}) " +
                "rx=$rxPhy (2M=${rxPhy == BluetoothDevice.PHY_LE_2M}) status=$status")
        }

        override fun onPhyRead(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            Log.i(BLE_TAG,
                "PHY read: tx=$txPhy (2M=${txPhy == BluetoothDevice.PHY_LE_2M}) " +
                "rx=$rxPhy (2M=${rxPhy == BluetoothDevice.PHY_LE_2M}) status=$status")
        }


        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    rxCharacteristic = service.getCharacteristic(RX_CHAR_UUID)
                    val txChar = service.getCharacteristic(TX_CHAR_UUID)

                    if (txChar != null) {
                        gatt.setCharacteristicNotification(txChar, true)
                        val descriptor = txChar.getDescriptor(CCCD_UUID)
                        if (descriptor != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(descriptor)
                            }
                        }
                    }
                    _connectionState.value = ConnectionState.Connected
                    resetSession()
                    // The initial mode command can race the CCCD (notification
                    // enable) write on some phones, so send it now and once more
                    // shortly after the link settles.
                    applyCurrentMode()
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (_connectionState.value == ConnectionState.Connected) applyCurrentMode()
                    }, 400L)
                } else {
                    disconnect()
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == TX_CHAR_UUID) {
                handleIncomingData(value)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == TX_CHAR_UUID) {
                val v = characteristic.value ?: return
                handleIncomingData(v)
            }
        }
    }

    /**
     * Requests the fastest connection parameters the phone will grant.
     * Many Android devices ignore a priority request issued immediately on
     * connect, so it is retried once shortly afterwards when the link is stable.
     */
    private fun requestFastLinkParams() {
        val first = bluetoothGatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) == true
        Log.i(BLE_TAG, "requestConnectionPriority(HIGH) -> $first")
        Handler(Looper.getMainLooper()).postDelayed({
            val retry = bluetoothGatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) == true
            Log.i(BLE_TAG, "retry requestConnectionPriority(HIGH) -> $retry")
        }, 1500L)
    }

    private fun handleIncomingData(value: ByteArray) {
        if (value.size >= 9 && value[0] == 0xAA.toByte() && value[1] == 0x55.toByte()) {
            processBinaryPacket(value)
        } else {
            val data = String(value, Charsets.UTF_8)
            appendLog(data)
        }
    }

    private fun processBinaryPacket(bytes: ByteArray) {
        if (bytes.size < 9) return
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic0 = buffer.get()
        val magic1 = buffer.get()
        val mode = buffer.get().toInt() and 0xFF
        val sampleCount = buffer.get().toInt() and 0xFF
        val xlFs = buffer.get().toInt() and 0xFF
        val hgFs = buffer.get().toInt() and 0xFF
        val gyFs = buffer.get().toInt() and 0xFF
        val seq = buffer.get().toInt() and 0xFF
        val fmt = buffer.get().toInt() and 0xFF

        if (sampleCount == 0) return

        val packed12 = fmt == 1

        // Sensitivity factors (per 16-bit LSB)
        val xlSens = when (xlFs) {
            0 -> 0.061f  // ±2g
            1 -> 0.122f  // ±4g
            2 -> 0.244f  // ±8g
            3 -> 0.488f  // ±16g
            else -> 0.061f
        }
        val hgSens = when (hgFs) {
            0 -> 0.000976f // ±32g (in g)
            1 -> 0.001953f // ±64g
            2 -> 0.003906f // ±128g
            3 -> 0.007812f // ±256g
            4 -> 0.009765f // ±320g
            else -> 0.009765f
        }
        val gySens = when (gyFs) {
            1 -> 8.75f   // ±250dps (in mdps)
            2 -> 17.50f  // ±500dps
            3 -> 35.0f   // ±1000dps
            4 -> 70.0f   // ±2000dps
            5 -> 140.0f  // ±4000dps
            else -> 70.0f
        }

        // 12-bit packed values were produced by raw >> 4, so scale by sens * 16.
        val xlScale = if (packed12) xlSens * 16f else xlSens
        val hgScale = if (packed12) hgSens * 16f else hgSens
        val gyScale = if (packed12) gySens * 16f else gySens

        val lowGList = mutableListOf<AxisPoint>()
        val highGList = mutableListOf<AxisPoint>()
        val gyroList = mutableListOf<AxisPoint>()

        val rawNowMs = System.currentTimeMillis() - sessionStartTimeMs
        val nowMs = if (rawNowMs <= lastAssignedTimeMs) lastAssignedTimeMs + 20L else rawNowMs
        val dt = (nowMs - lastAssignedTimeMs).toFloat() / sampleCount.coerceAtLeast(1)
        val baseTime = lastAssignedTimeMs
        lastAssignedTimeMs = nowMs

        val reader = if (packed12) BitReader(bytes, startBit = 9 * 8) else null

        // Reads one axis in the current format and scales it.
        fun readAxis(scale: Float): Float =
            if (packed12) reader!!.readSigned12() * scale else buffer.short * scale

        for (i in 0 until sampleCount) {
            val t = (baseTime + dt * (i + 1))

            when (mode) {
                1 -> { // LOW_ACC
                    if (!packed12 && buffer.remaining() < 6) break
                    lowGList.add(AxisPoint(readAxis(xlScale), readAxis(xlScale), readAxis(xlScale), time = t))
                }
                2 -> { // HIGH_ACC
                    if (!packed12 && buffer.remaining() < 6) break
                    highGList.add(AxisPoint(readAxis(hgScale), readAxis(hgScale), readAxis(hgScale), time = t))
                }
                3 -> { // BOTH_ACC
                    if (!packed12 && buffer.remaining() < 12) break
                    val lgX = readAxis(xlScale); val lgY = readAxis(xlScale); val lgZ = readAxis(xlScale)
                    val hgX = readAxis(hgScale); val hgY = readAxis(hgScale); val hgZ = readAxis(hgScale)
                    lowGList.add(AxisPoint(lgX, lgY, lgZ, time = t))
                    highGList.add(AxisPoint(hgX, hgY, hgZ, time = t))
                }
                4 -> { // ONLY_GYRO
                    if (!packed12 && buffer.remaining() < 6) break
                    gyroList.add(AxisPoint(readAxis(gyScale), readAxis(gyScale), readAxis(gyScale), time = t))
                }
                0 -> { // ALL
                    if (!packed12 && buffer.remaining() < 18) break
                    val lgX = readAxis(xlScale); val lgY = readAxis(xlScale); val lgZ = readAxis(xlScale)
                    val hgX = readAxis(hgScale); val hgY = readAxis(hgScale); val hgZ = readAxis(hgScale)
                    val gyX = readAxis(gyScale); val gyY = readAxis(gyScale); val gyZ = readAxis(gyScale)
                    lowGList.add(AxisPoint(lgX, lgY, lgZ, time = t))
                    highGList.add(AxisPoint(hgX, hgY, hgZ, time = t))
                    gyroList.add(AxisPoint(gyX, gyY, gyZ, time = t))
                }
            }
        }

        val filteredLowG = filterLowG(lowGList)
        val filteredHighG = filterHighG(highGList)
        val filteredGyro = filterGyro(gyroList)

        appendBatchPoints(filteredLowG, filteredHighG, filteredGyro)
        recordReceivedSamples(maxOf(filteredLowG.size, filteredHighG.size, filteredGyro.size))

        // Human-readable terminal update (throttled to ~10 Hz to prevent UI thread lag)
        val now = System.currentTimeMillis()
        if (now - lastTerminalUpdateTimeMs >= 100L) {
            lastTerminalUpdateTimeMs = now
            val terminalLine = when (mode) {
                1 -> {
                    val pt = filteredLowG.lastOrNull()
                    if (pt != null) String.format(Locale.US, "[Low-G mg] X=%7.2f Y=%7.2f Z=%7.2f", pt.x, pt.y, pt.z) else null
                }
                2 -> {
                    val pt = filteredHighG.lastOrNull()
                    if (pt != null) String.format(Locale.US, "[High-G g] X=%6.2f Y=%6.2f Z=%6.2f", pt.x, pt.y, pt.z) else null
                }
                3 -> {
                    val lg = filteredLowG.lastOrNull()
                    val hg = filteredHighG.lastOrNull()
                    if (lg != null && hg != null) {
                        String.format(Locale.US, "[LG mg] X=%7.2f Y=%7.2f Z=%7.2f  [HG g] X=%6.2f Y=%6.2f Z=%6.2f", lg.x, lg.y, lg.z, hg.x, hg.y, hg.z)
                    } else null
                }
                4 -> {
                    val pt = filteredGyro.lastOrNull()
                    if (pt != null) String.format(Locale.US, "[mdps] X=%8.2f Y=%8.2f Z=%8.2f", pt.x, pt.y, pt.z) else null
                }
                0 -> {
                    val lg = filteredLowG.lastOrNull()
                    val hg = filteredHighG.lastOrNull()
                    val gy = filteredGyro.lastOrNull()
                    if (lg != null && hg != null && gy != null) {
                        String.format(Locale.US, "[LG mg] X=%7.2f Y=%7.2f Z=%7.2f  [HG g] X=%6.2f Y=%6.2f Z=%6.2f  [mdps] X=%8.2f Y=%8.2f Z=%8.2f",
                            lg.x, lg.y, lg.z, hg.x, hg.y, hg.z, gy.x, gy.y, gy.z)
                    } else null
                }
                else -> null
            }

            if (terminalLine != null) {
                _logMessages.update { current ->
                    val updated = current.toMutableList()
                    updated.add(terminalLine)
                    if (updated.size > 200) {
                        updated.subList(0, updated.size - 200).clear()
                    }
                    updated
                }
            }
        }

        if (_isRecording.value) {
            logFile?.let { file ->
                val sb = StringBuilder()
                val count = maxOf(filteredLowG.size, filteredHighG.size, filteredGyro.size)
                for (i in 0 until count) {
                    val lg = filteredLowG.getOrNull(i)
                    val hg = filteredHighG.getOrNull(i)
                    val gy = filteredGyro.getOrNull(i)
                    if (lg != null) sb.append(String.format(Locale.US, "[Low-G mg] X=%.2f Y=%.2f Z=%.2f ", lg.x, lg.y, lg.z))
                    if (hg != null) sb.append(String.format(Locale.US, "[High-G g] X=%.2f Y=%.2f Z=%.2f ", hg.x, hg.y, hg.z))
                    if (gy != null) sb.append(String.format(Locale.US, "[Gyro mdps] X=%.2f Y=%.2f Z=%.2f ", gy.x, gy.y, gy.z))
                    sb.append("\n")
                }
                file.appendText(sb.toString())
            }
        }
    }

    private fun appendBatchPoints(
        newLowG: List<AxisPoint> = emptyList(),
        newHighG: List<AxisPoint> = emptyList(),
        newGyro: List<AxisPoint> = emptyList()
    ) {
        if (newLowG.isEmpty() && newHighG.isEmpty() && newGyro.isEmpty()) return

        _chartData.update { current ->
            current.copy(
                lowG = if (newLowG.isNotEmpty()) (current.lowG + newLowG).takeLast(MAX_CHART_POINTS) else current.lowG,
                highG = if (newHighG.isNotEmpty()) (current.highG + newHighG).takeLast(MAX_CHART_POINTS) else current.highG,
                gyro = if (newGyro.isNotEmpty()) (current.gyro + newGyro).takeLast(MAX_CHART_POINTS) else current.gyro,
            )
        }
    }

    fun init(ctx: Context) {
        this.context = ctx.applicationContext
    }

    fun startScan() {
        val ctx = context ?: return
        val bluetoothManager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) return

        scanner = adapter.bluetoothLeScanner
        if (scanner == null) return

        _connectionState.value = ConnectionState.Scanning
        scanner?.startScan(scanCallback)

        Handler(Looper.getMainLooper()).postDelayed({
            if (_connectionState.value == ConnectionState.Scanning) {
                stopScan()
                _connectionState.value = ConnectionState.Disconnected
            }
        }, 10000)
    }

    private fun stopScan() {
        scanner?.stopScan(scanCallback)
    }

    private fun connectToDevice(device: BluetoothDevice) {
        val ctx = context ?: return
        _connectionState.value = ConnectionState.Connecting
        device.connectGatt(ctx, false, gattCallback)
    }

    fun disconnect() {
        stopScan()
        _isPaused.value = false
        bluetoothGatt?.disconnect()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun appendLog(data: String) {
        pendingData += data
        if (pendingData.contains("\n")) {
            val lines = pendingData.split("\n")
            pendingData = lines.last()
            val completeLines = lines.dropLast(1)

            val receivedSampleCount = completeLines.count { line -> processIncomingLine(line) }
            recordReceivedSamples(receivedSampleCount)

            _logMessages.update { current ->
                val updated = current.toMutableList()
                updated.addAll(completeLines)
                if (updated.size > 500) {
                    updated.subList(0, updated.size - 500).clear()
                }
                updated
            }

            if (_isRecording.value) {
                logFile?.let { file ->
                    val textToAppend = completeLines.joinToString("\n") + "\n"
                    file.appendText(textToAppend)
                }
            }
        }
    }

    fun selectMode(mode: GraphMode) {
        val changed = _selectedMode.value != mode
        _selectedMode.value = mode
        if (changed) clearLogs()
        // Always (re)send the mode command when connected — including when the
        // user re-taps the already-selected mode, to recover from a lost
        // connect-time command.
        applyCurrentMode()
    }

    private fun applyCurrentMode() {
        if (_connectionState.value == ConnectionState.Connected) {
            sendCommand(_selectedMode.value.command + "\r\n")
        }
    }

    private fun processIncomingLine(rawLine: String): Boolean {
        val line = rawLine.trim()
        if (line.isEmpty()) return false

        val points = parseXyzPoints(line)
        return when (_selectedMode.value) {
            GraphMode.LOW_G -> points.firstOrNull()?.let {
                appendPoints(lowG = it)
                true
            } ?: false
            GraphMode.HIGH_G -> points.firstOrNull()?.let {
                appendPoints(highG = it)
                true
            } ?: false
            GraphMode.GYRO -> points.firstOrNull()?.let {
                appendPoints(gyro = it)
                true
            } ?: false
            GraphMode.BOTH_ACC -> if (points.size >= 2) {
                appendPoints(lowG = points[0], highG = points[1])
                true
            } else {
                false
            }
        }
    }

    /** Updates the UI only once per two-second window, keeping the receive path lightweight. */
    private fun recordReceivedSamples(sampleCount: Int) {
        if (sampleCount <= 0) return

        samplesInRateWindow += sampleCount
        val nowMs = SystemClock.elapsedRealtime()
        val elapsedMs = nowMs - sampleRateWindowStartMs
        if (elapsedMs >= SAMPLE_RATE_UPDATE_INTERVAL_MS) {
            _receivedSamplesPerSecond.value =
                ((samplesInRateWindow * 1_000L) / elapsedMs).toInt()
            samplesInRateWindow = 0
            sampleRateWindowStartMs = nowMs
        }
    }

    private fun parseXyzPoints(line: String): List<AxisPoint> =
        xyzRegex.findAll(line).mapNotNull { match ->
            try {
                val x = match.groupValues[1].toFloat()
                val y = match.groupValues[2].toFloat()
                val z = match.groupValues[3].toFloat()
                // Ignore spurious all-zero startup or command echo packets
                if (x == 0f && y == 0f && z == 0f) return@mapNotNull null
                AxisPoint(x = x, y = y, z = z)
            } catch (e: NumberFormatException) {
                null
            }
        }.toList()

    private fun appendPoints(lowG: AxisPoint? = null, highG: AxisPoint? = null, gyro: AxisPoint? = null) {
        val rawTimeMs = System.currentTimeMillis() - sessionStartTimeMs
        val timeMs = if (rawTimeMs <= lastAssignedTimeMs) lastAssignedTimeMs + 20L else rawTimeMs
        lastAssignedTimeMs = timeMs
        val timeFloat = timeMs.toFloat()

        _chartData.update { current ->
            current.copy(
                lowG = if (lowG != null) (current.lowG + lowG.copy(time = timeFloat)).takeLast(MAX_CHART_POINTS) else current.lowG,
                highG = if (highG != null) (current.highG + highG.copy(time = timeFloat)).takeLast(MAX_CHART_POINTS) else current.highG,
                gyro = if (gyro != null) (current.gyro + gyro.copy(time = timeFloat)).takeLast(MAX_CHART_POINTS) else current.gyro,
            )
        }
    }

    fun clearLogs() {
        _logMessages.value = emptyList()
        _chartData.value = ChartData()
        resetSession()
        pendingData = ""
    }

    fun toggleRecording(ctx: Context) {
        if (_isRecording.value) {
            _isRecording.value = false
            shareFile(ctx)
        } else {
            logFile = File(ctx.cacheDir, "ESP_IMU_Log_${System.currentTimeMillis()}.txt")
            logFile?.writeText("")
            _isRecording.value = true
        }
    }

    private fun shareFile(ctx: Context) {
        val file = logFile ?: return
        if (!file.exists() || file.length() == 0L) return

        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Export Logs")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(chooser)
    }

    fun sendCommand(command: String) {
        val gatt = bluetoothGatt ?: return
        val char = rxCharacteristic ?: return
        val bytes = command.toByteArray(Charsets.UTF_8)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            char.value = bytes
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(char)
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BleAppScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleAppScreen(viewModel: BleViewModel = viewModel()) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.init(context)
    }

    val connectionState by viewModel.connectionState.collectAsState()
    val logMessages by viewModel.logMessages.collectAsState()
    val receivedSamplesPerSecond by viewModel.receivedSamplesPerSecond.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val selectedMode by viewModel.selectedMode.collectAsState()
    val chartData by viewModel.chartData.collectAsState()
    val lowGOdr by viewModel.lowGOdr.collectAsState()
    val highGOdr by viewModel.highGOdr.collectAsState()
    val gyroOdr by viewModel.gyroOdr.collectAsState()
    val lowGRange by viewModel.lowGRange.collectAsState()
    val highGRange by viewModel.highGRange.collectAsState()
    val gyroRange by viewModel.gyroRange.collectAsState()
    val timeWindowSec by viewModel.timeWindowSec.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val isConnected = (connectionState == ConnectionState.Connected)

    val isFilterEnabled by viewModel.isFilterEnabled.collectAsState()
    val filterAlpha by viewModel.filterAlpha.collectAsState()
    var isFilterExpanded by remember { mutableStateOf(false) }

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

    var showPlotInfoDialog by remember { mutableStateOf(false) }
    var showOdrInfoDialog by remember { mutableStateOf(false) }
    var showRangeInfoDialog by remember { mutableStateOf(false) }
    var isOdrExpanded by remember { mutableStateOf(false) }
    var isRangeExpanded by remember { mutableStateOf(false) }

    val lowGOdrLabel = lowGOdrOptions.find { it.suffix == lowGOdr }?.label ?: lowGOdr
    val highGOdrLabel = highGOdrOptions.find { it.suffix == highGOdr }?.label ?: highGOdr
    val gyroOdrLabel = gyroOdrOptions.find { it.suffix == gyroOdr }?.label ?: gyroOdr
    val windowLabel = timeWindowOptions.find { it.seconds == timeWindowSec }?.label ?: "${timeWindowSec}s"

    val odrSummary = when (selectedMode) {
        GraphMode.LOW_G -> "Low-G: $lowGOdrLabel | Window: $windowLabel"
        GraphMode.HIGH_G -> "High-G: $highGOdrLabel | Window: $windowLabel"
        GraphMode.GYRO -> "Gyro: $gyroOdrLabel | Window: $windowLabel"
        GraphMode.BOTH_ACC -> "Low-G: $lowGOdrLabel, High-G: $highGOdrLabel | Window: $windowLabel"
    }

    val lowGRangeLabel = lowGRangeOptions.find { it.suffix == lowGRange }?.label ?: lowGRange
    val highGRangeLabel = highGRangeOptions.find { it.suffix == highGRange }?.label ?: highGRange
    val gyroRangeLabel = gyroRangeOptions.find { it.suffix == gyroRange }?.label ?: gyroRange

    val rangeSummary = when (selectedMode) {
        GraphMode.LOW_G -> "Low-G: $lowGRangeLabel"
        GraphMode.HIGH_G -> "High-G: $highGRangeLabel"
        GraphMode.GYRO -> "Gyro: $gyroRangeLabel"
        GraphMode.BOTH_ACC -> "Low-G: $lowGRangeLabel, High-G: $highGRangeLabel"
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            if (connectionState == ConnectionState.Disconnected) {
                viewModel.startScan()
            }
        } else {
            Toast.makeText(context, "Permissions required for BLE", Toast.LENGTH_SHORT).show()
        }
    }

    fun checkAndRequestPermissions() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            if (connectionState == ConnectionState.Disconnected) {
                viewModel.startScan()
            }
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ESP_IMU Monitor") },
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
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Top Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = {
                    if (connectionState == ConnectionState.Disconnected) {
                        checkAndRequestPermissions()
                    } else {
                        viewModel.disconnect()
                    }
                }) {
                    Text(if (connectionState == ConnectionState.Disconnected) "Connect" else "Disconnect")
                }

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

            // Graph mode selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GraphMode.entries.forEach { mode ->
                        FilterChip(
                            selected = selectedMode == mode,
                            onClick = { viewModel.selectMode(mode) },
                            label = { Text(mode.label) },
                        )
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                InfoIconButton(onClick = { showPlotInfoDialog = true })
            }

            // Real-time chart
            Box(
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxWidth()
                    .padding(8.dp)
                    .background(Color(0xFF101418))
            ) {
                when (selectedMode) {
                    GraphMode.LOW_G -> SensorChart(
                        title = "Low-G Accelerometer (mg)",
                        points = chartData.lowG,
                        timeWindowSec = timeWindowSec,
                        isPaused = isPaused,
                        modifier = Modifier.fillMaxSize(),
                    )
                    GraphMode.HIGH_G -> SensorChart(
                        title = "High-G Accelerometer (g)",
                        points = chartData.highG,
                        timeWindowSec = timeWindowSec,
                        isPaused = isPaused,
                        modifier = Modifier.fillMaxSize(),
                    )
                    GraphMode.GYRO -> SensorChart(
                        title = "Gyroscope (mdps)",
                        points = chartData.gyro,
                        timeWindowSec = timeWindowSec,
                        isPaused = isPaused,
                        modifier = Modifier.fillMaxSize(),
                    )
                    GraphMode.BOTH_ACC -> Column(modifier = Modifier.fillMaxSize()) {
                        SensorChart(
                            title = "Low-G Accelerometer (mg)",
                            points = chartData.lowG,
                            timeWindowSec = timeWindowSec,
                            isPaused = isPaused,
                            modifier = Modifier.weight(1f),
                        )
                        SensorChart(
                            title = "High-G Accelerometer (g)",
                            points = chartData.highG,
                            timeWindowSec = timeWindowSec,
                            isPaused = isPaused,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // ODR & Time Window Section
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
                                text = "Sampling Rate (ODR) & Time Window",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (!isOdrExpanded) {
                                Text(
                                    text = odrSummary,
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
                                    Box(modifier = Modifier.weight(1f)) {
                                        CompactTimeWindowDropdown(
                                            title = "Window",
                                            options = timeWindowOptions,
                                            selectedSeconds = timeWindowSec,
                                            onSelected = { viewModel.setTimeWindow(it) }
                                        )
                                    }
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
                                    Box(modifier = Modifier.weight(1f)) {
                                        CompactTimeWindowDropdown(
                                            title = "Window",
                                            options = timeWindowOptions,
                                            selectedSeconds = timeWindowSec,
                                            onSelected = { viewModel.setTimeWindow(it) }
                                        )
                                    }
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
                                    Box(modifier = Modifier.weight(1f)) {
                                        CompactTimeWindowDropdown(
                                            title = "Window",
                                            options = timeWindowOptions,
                                            selectedSeconds = timeWindowSec,
                                            onSelected = { viewModel.setTimeWindow(it) }
                                        )
                                    }
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
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        CompactTimeWindowDropdown(
                                            title = "Window",
                                            options = timeWindowOptions,
                                            selectedSeconds = timeWindowSec,
                                            onSelected = { viewModel.setTimeWindow(it) }
                                        )
                                    }
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }

            // Full-Scale Range Section
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
                            .clickable { isRangeExpanded = !isRangeExpanded }
                            .padding(bottom = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Full-Scale Range",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (!isRangeExpanded) {
                                Text(
                                    text = rangeSummary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = if (isRangeExpanded) "▲" else "▼",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            InfoIconButton(onClick = { showRangeInfoDialog = true })
                        }
                    }

                    if (isRangeExpanded) {
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

            // Digital Filter Section
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
                        Text(
                            text = if (isFilterExpanded) "▲" else "▼",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
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

            // Info Dialogs
            if (showPlotInfoDialog) {
                InfoAlertDialog(
                    title = "Plot & Sensor Modes",
                    infoText = "• Graph Modes: Select between Low-G Accelerometer (mg), High-G Accelerometer (g), Both Accelerometers, or Gyroscope (mdps).\n\n" +
                            "• Channel Toggles: Tap X, Y, or Z in the chart header legend to show or hide individual axis channels.\n\n" +
                            "• Interactive Cursors:\n" +
                            "  - Single Tap: Places Cursor 1 (C1) to inspect real-time values.\n" +
                            "  - Dual Touch (when Paused): Places C1 and C2 to measure time delta (Δt), amplitude delta (Δv), and slope.",
                    onDismiss = { showPlotInfoDialog = false }
                )
            }

            if (showOdrInfoDialog) {
                InfoAlertDialog(
                    title = "Sampling Rate (ODR) & Window",
                    infoText = "• Output Data Rate (ODR):\n" +
                            "  - Low-G Accel: 1.875 Hz to 7680 Hz\n" +
                            "  - High-G Accel: 480 Hz to 7680 Hz\n" +
                            "  - Gyroscope: 7.5 Hz to 7680 Hz\n" +
                            "Higher ODR rates capture faster dynamics but transmit more sample data over BLE.\n\n" +
                            "• Display Time Window: Controls horizontal chart duration (1s to 30s).",
                    onDismiss = { showOdrInfoDialog = false }
                )
            }

            if (showRangeInfoDialog) {
                InfoAlertDialog(
                    title = "Full-Scale Range",
                    infoText = "• Full-Scale Range (FS) sets the measurement ceiling:\n" +
                            "  - Low-G Range: ±2 g, ±4 g, ±8 g, ±16 g\n" +
                            "  - High-G Range: ±32 g, ±64 g, ±128 g, ±256 g, ±320 g\n" +
                            "  - Gyroscope Range: ±250 dps, ±500 dps, ±1000 dps, ±2000 dps, ±4000 dps\n\n" +
                            "• Lower ranges give finer measurement resolution, while higher ranges prevent signal clipping under heavy vibration or rapid motion.",
                    onDismiss = { showRangeInfoDialog = false }
                )
            }

            // Collapsible Terminal Section
            var isTerminalVisible by remember { mutableStateOf(false) }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clickable { isTerminalVisible = !isTerminalVisible },
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isTerminalVisible) "Received Data Terminal ▼" else "Received Data Terminal ▲",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "$receivedSamplesPerSecond samples/s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${logMessages.size} lines",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (isTerminalVisible) {
                val listState = rememberLazyListState()
                LaunchedEffect(logMessages.size) {
                    if (logMessages.isNotEmpty()) {
                        listState.scrollToItem(logMessages.size - 1)
                    }
                }

                Box(
                    modifier = Modifier
                        .height(80.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .background(Color.Black)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp)
                    ) {
                        items(logMessages) { msg ->
                            Text(
                                text = msg,
                                color = Color.Green,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SensorChart(
    title: String,
    points: List<AxisPoint>,
    timeWindowSec: Float,
    isPaused: Boolean,
    modifier: Modifier = Modifier,
) {
    var cursor1 by remember(isPaused) { mutableStateOf<Entry?>(null) }
    var cursor2 by remember(isPaused) { mutableStateOf<Entry?>(null) }
    val chartRef = remember { mutableStateOf<LineChart?>(null) }

    var showX by remember { mutableStateOf(true) }
    var showY by remember { mutableStateOf(true) }
    var showZ by remember { mutableStateOf(true) }

    LaunchedEffect(points, isPaused, timeWindowSec) {
        if (!isPaused && cursor1 != null) {
            val maxTime = if (points.isNotEmpty()) points.last().time else 0f
            val windowMs = timeWindowSec * 1000f
            val minTime = maxTime - windowMs
            if (cursor1!!.x < minTime) {
                cursor1 = null
                cursor2 = null
            }
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClickableLegend(Color.Red, "X", showX) { showX = !showX }
                ClickableLegend(Color.Green, "Y", showY) { showY = !showY }
                ClickableLegend(Color.Blue, "Z", showZ) { showZ = !showZ }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(points, isPaused, timeWindowSec) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            val chart = chartRef.value

                            if (chart != null) {
                                if (isPaused) {
                                    val newlyPressed = event.changes.filter { it.pressed && !it.previousPressed }
                                    if (pressed.size >= 2) {
                                        val p1 = pressed[0].position
                                        val p2 = pressed[1].position
                                        val h1 = chart.getHighlightByTouchPoint(p1.x, p1.y)
                                        val h2 = chart.getHighlightByTouchPoint(p2.x, p2.y)
                                        if (h1 != null && h2 != null) {
                                            cursor1 = Entry(h1.x, h1.y)
                                            cursor2 = Entry(h2.x, h2.y)
                                        }
                                    } else if (newlyPressed.size == 1) {
                                        val pos = newlyPressed[0].position
                                        val h = chart.getHighlightByTouchPoint(pos.x, pos.y)
                                        if (h != null) {
                                            cursor1 = Entry(h.x, h.y)
                                            cursor2 = null
                                        }
                                    }
                                } else {
                                    if (pressed.size == 1) {
                                        val pos = pressed[0].position
                                        val offsetX = -70f
                                        val offsetY = -120f
                                        val valD = chart.getValuesByTouchPoint(pos.x + offsetX, pos.y + offsetY, YAxis.AxisDependency.LEFT)
                                        cursor1 = Entry(valD.x.toFloat(), valD.y.toFloat())
                                        cursor2 = null
                                    }
                                }
                            }
                        }
                    }
                }
        ) {
            AndroidView(
                factory = { ctx ->
                    LineChart(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        description.isEnabled = false
                        setTouchEnabled(true)
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
                        legend.isEnabled = false

                        chartRef.value = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { chart ->
                    chartRef.value = chart
                    val maxTime = if (points.isNotEmpty()) points.last().time else 0f
                    val windowMs = timeWindowSec * 1000f
                    val filtered = if (points.isEmpty()) emptyList() else points.filter { it.time >= maxTime - windowMs }

                    val entriesX = filtered.map { Entry(it.time, it.x) }
                    val entriesY = filtered.map { Entry(it.time, it.y) }
                    val entriesZ = filtered.map { Entry(it.time, it.z) }

                    val dataSets = mutableListOf<LineDataSet>()
                    if (showX) {
                        dataSets.add(LineDataSet(entriesX, "X").apply {
                            color = android.graphics.Color.RED
                            setDrawCircles(false)
                            lineWidth = 2f
                            setDrawValues(false)
                        })
                    }
                    if (showY) {
                        dataSets.add(LineDataSet(entriesY, "Y").apply {
                            color = android.graphics.Color.GREEN
                            setDrawCircles(false)
                            lineWidth = 2f
                            setDrawValues(false)
                        })
                    }
                    if (showZ) {
                        dataSets.add(LineDataSet(entriesZ, "Z").apply {
                            color = android.graphics.Color.BLUE
                            setDrawCircles(false)
                            lineWidth = 2f
                            setDrawValues(false)
                        })
                    }

                    val data = LineData(dataSets.map { it as ILineDataSet })
                    chart.data = data
                    chart.highlightValue(null)

                    chart.notifyDataSetChanged()
                    chart.invalidate()
                }
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                val chart = chartRef.value
                if (chart != null) {
                    if (cursor1 != null) {
                        val pixelD = chart.getPixelForValues(cursor1!!.x, cursor1!!.y, YAxis.AxisDependency.LEFT)
                        val px = pixelD.x.toFloat()
                        val py = pixelD.y.toFloat()

                        drawLine(
                            color = Color(0xFF00E5FF),
                            start = Offset(px, 0f),
                            end = Offset(px, size.height),
                            strokeWidth = 1.5f
                        )
                        drawLine(
                            color = Color(0xFF00E5FF),
                            start = Offset(0f, py),
                            end = Offset(size.width, py),
                            strokeWidth = 1.5f
                        )
                        drawCircle(
                            color = Color(0xFF00E5FF),
                            radius = 5f,
                            center = Offset(px, py)
                        )
                    }

                    if (cursor2 != null) {
                        val pixelD2 = chart.getPixelForValues(cursor2!!.x, cursor2!!.y, YAxis.AxisDependency.LEFT)
                        val px2 = pixelD2.x.toFloat()
                        val py2 = pixelD2.y.toFloat()

                        drawLine(
                            color = Color(0xFFFFAB40),
                            start = Offset(px2, 0f),
                            end = Offset(px2, size.height),
                            strokeWidth = 1.5f
                        )
                        drawLine(
                            color = Color(0xFFFFAB40),
                            start = Offset(0f, py2),
                            end = Offset(size.width, py2),
                            strokeWidth = 1.5f
                        )
                        drawCircle(
                            color = Color(0xFFFFAB40),
                            radius = 5f,
                            center = Offset(px2, py2)
                        )
                    }
                }
            }
        }

        // Cursor details in the space below X axis
        val detailsContent = remember(cursor1, cursor2, isPaused) {
            buildAnnotatedString {
                if (!isPaused) {
                    if (cursor1 != null) {
                        withStyle(SpanStyle(color = Color.LightGray)) { append("Cursor: ") }
                        withStyle(SpanStyle(color = Color(0xFF00E5FF))) { append(String.format(Locale.US, "t = %.2fs", cursor1!!.x / 1000f)) }
                        withStyle(SpanStyle(color = Color.Gray)) { append(" | ") }
                        withStyle(SpanStyle(color = Color(0xFF69F0AE))) { append(String.format(Locale.US, "Y = %.2f", cursor1!!.y)) }
                    } else {
                        withStyle(SpanStyle(color = Color.Gray)) { append("Tap plot to inspect point (Y Cursor active)") }
                    }
                } else {
                    if (cursor1 != null && cursor2 == null) {
                        withStyle(SpanStyle(color = Color(0xFF00E5FF))) { append("C1: ") }
                        withStyle(SpanStyle(color = Color.White)) { append(String.format(Locale.US, "t = %.2fs, v = %.2f", cursor1!!.x / 1000f, cursor1!!.y)) }
                        withStyle(SpanStyle(color = Color.Gray)) { append(" (Tap/Touch for C2)") }
                    } else if (cursor1 != null && cursor2 != null) {
                        val dt = (cursor2!!.x - cursor1!!.x) / 1000f
                        val dv = cursor2!!.y - cursor1!!.y
                        val slope = if (dt != 0f) dv / dt else 0f

                        withStyle(SpanStyle(color = Color(0xFF00E5FF))) { append("C1: ") }
                        append(String.format(Locale.US, "t=%.2fs (v=%.2f)", cursor1!!.x / 1000f, cursor1!!.y))
                        withStyle(SpanStyle(color = Color.Gray)) { append(" | ") }

                        withStyle(SpanStyle(color = Color(0xFFFFAB40))) { append("C2: ") }
                        append(String.format(Locale.US, "t=%.2fs (v=%.2f)", cursor2!!.x / 1000f, cursor2!!.y))
                        withStyle(SpanStyle(color = Color.Gray)) { append(" | ") }

                        withStyle(SpanStyle(color = Color(0xFF69F0AE))) { append(String.format(Locale.US, "Δt=%.2fs, Δv=%.2f", dt, dv)) }
                        withStyle(SpanStyle(color = Color.Gray)) { append(" | ") }

                        withStyle(SpanStyle(color = Color(0xFFFF4081))) { append(String.format(Locale.US, "Slope=%.2f/s", slope)) }
                    } else {
                        withStyle(SpanStyle(color = Color.Gray)) { append("Paused: Tap for C1 (or 2-finger touch for C1 & C2)") }
                    }
                }
            }
        }

        Text(
            text = detailsContent,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun ClickableLegend(color: Color, label: String, visible: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(if (visible) color else color.copy(alpha = 0.2f), CircleShape),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (visible) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
        )
    }
}

@Composable
private fun CompactOdrDropdown(
    title: String,
    options: List<OdrOption>,
    selectedSuffix: String,
    enabled: Boolean,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = options.find { it.suffix == selectedSuffix } ?: options.first()

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.width(55.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .wrapContentSize(Alignment.TopStart)
        ) {
            Surface(
                onClick = { if (enabled) expanded = !expanded },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp),
                shape = MaterialTheme.shapes.extraSmall,
                color = if (enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = selectedOption.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        maxLines = 1
                    )
                    Text(
                        text = "▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label, style = MaterialTheme.typography.bodySmall) },
                        onClick = {
                            onSelected(option.suffix)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactTimeWindowDropdown(
    title: String,
    options: List<TimeWindowOption>,
    selectedSeconds: Float,
    onSelected: (Float) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = options.find { it.seconds == selectedSeconds } ?: options[2]

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.width(55.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .wrapContentSize(Alignment.TopStart)
        ) {
            Surface(
                onClick = { expanded = !expanded },
                enabled = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp),
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = selectedOption.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = "▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label, style = MaterialTheme.typography.bodySmall) },
                        onClick = {
                            onSelected(option.seconds)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactRangeDropdown(
    title: String,
    options: List<RangeOption>,
    selectedSuffix: String,
    enabled: Boolean,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = options.find { it.suffix == selectedSuffix } ?: options.first()

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.width(55.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .wrapContentSize(Alignment.TopStart)
        ) {
            Surface(
                onClick = { if (enabled) expanded = !expanded },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp),
                shape = MaterialTheme.shapes.extraSmall,
                color = if (enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = selectedOption.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        maxLines = 1
                    )
                    Text(
                        text = "▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label, style = MaterialTheme.typography.bodySmall) },
                        onClick = {
                            onSelected(option.suffix)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoIconButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "i",
            style = TextStyle(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
private fun InfoAlertDialog(
    title: String,
    infoText: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "i",
                        style = TextStyle(
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Bold,
                            fontStyle = FontStyle.Italic,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        text = {
            Text(
                text = infoText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        }
    )
}

