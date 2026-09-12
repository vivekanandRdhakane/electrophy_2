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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
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
import java.util.UUID
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.patrykandpatrick.vico.core.cartesian.AutoScrollCondition
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer

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

private const val MAX_CHART_POINTS = 50

data class AxisPoint(val x: Float, val y: Float, val z: Float)

data class ChartData(
    val lowG: List<AxisPoint> = emptyList(),
    val highG: List<AxisPoint> = emptyList(),
    val gyro: List<AxisPoint> = emptyList(),
)

@SuppressLint("MissingPermission")
class BleViewModel : ViewModel() {
    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _logMessages = MutableStateFlow<List<String>>(emptyList())
    val logMessages: StateFlow<List<String>> = _logMessages

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _selectedMode = MutableStateFlow(GraphMode.LOW_G)
    val selectedMode: StateFlow<GraphMode> = _selectedMode

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
                gatt.requestMtu(256)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectionState.value = ConnectionState.Disconnected
                bluetoothGatt?.close()
                bluetoothGatt = null
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // MTU requested, now discover services
            gatt.discoverServices()
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
                    sendCommand(_selectedMode.value.command + "\r\n")
                } else {
                    disconnect()
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == TX_CHAR_UUID) {
                val data = String(value, Charsets.UTF_8)
                appendLog(data)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == TX_CHAR_UUID) {
                val data = String(characteristic.value, Charsets.UTF_8)
                appendLog(data)
            }
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
        
        // Timeout scan after 10s
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
        bluetoothGatt?.disconnect()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun appendLog(data: String) {
        pendingData += data
        if (pendingData.contains("\n")) {
            val lines = pendingData.split("\n")
            pendingData = lines.last()
            val completeLines = lines.dropLast(1)

            completeLines.forEach { line -> processIncomingLine(line) }

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
        if (_selectedMode.value == mode) return
        _selectedMode.value = mode
        _chartData.value = ChartData()
        sendCommand(mode.command + "\r\n")
    }

    private fun processIncomingLine(rawLine: String) {
        val line = rawLine.trim()
        if (line.isEmpty()) return

        val points = parseXyzPoints(line)
        when (_selectedMode.value) {
            GraphMode.LOW_G -> if (points.isNotEmpty()) appendPoints(lowG = points.first())
            GraphMode.HIGH_G -> if (points.isNotEmpty()) appendPoints(highG = points.first())
            GraphMode.GYRO -> if (points.isNotEmpty()) appendPoints(gyro = points.first())
            GraphMode.BOTH_ACC -> if (points.size >= 2) appendPoints(lowG = points[0], highG = points[1])
        }
    }

    private fun parseXyzPoints(line: String): List<AxisPoint> =
        xyzRegex.findAll(line).mapNotNull { match ->
            try {
                AxisPoint(
                    x = match.groupValues[1].toFloat(),
                    y = match.groupValues[2].toFloat(),
                    z = match.groupValues[3].toFloat(),
                )
            } catch (e: NumberFormatException) {
                null
            }
        }.toList()

    private fun appendPoints(lowG: AxisPoint? = null, highG: AxisPoint? = null, gyro: AxisPoint? = null) {
        _chartData.update { current ->
            current.copy(
                lowG = if (lowG != null) (current.lowG + lowG).takeLast(MAX_CHART_POINTS) else current.lowG,
                highG = if (highG != null) (current.highG + highG).takeLast(MAX_CHART_POINTS) else current.highG,
                gyro = if (gyro != null) (current.gyro + gyro).takeLast(MAX_CHART_POINTS) else current.gyro,
            )
        }
    }

    fun clearLogs() {
        _logMessages.value = emptyList()
        pendingData = ""
    }

    fun toggleRecording(ctx: Context) {
        if (_isRecording.value) {
            _isRecording.value = false
            shareFile(ctx)
        } else {
            logFile = File(ctx.cacheDir, "ESP_IMU_Log_${System.currentTimeMillis()}.txt")
            logFile?.writeText("") // create/clear
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
    
    // Initialize viewModel context
    LaunchedEffect(Unit) {
        viewModel.init(context)
    }

    val connectionState by viewModel.connectionState.collectAsState()
    val logMessages by viewModel.logMessages.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val selectedMode by viewModel.selectedMode.collectAsState()
    val chartData by viewModel.chartData.collectAsState()

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
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
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
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
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

            // Real-time chart
            Box(
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxWidth()
                    .padding(8.dp)
                    .background(Color(0xFF101418))
            ) {
                ProvideVicoTheme(rememberM3VicoTheme()) {
                    when (selectedMode) {
                        GraphMode.LOW_G -> SensorChart(
                            title = "Low-G Accelerometer (mg)",
                            points = chartData.lowG,
                            modifier = Modifier.fillMaxSize(),
                        )
                        GraphMode.HIGH_G -> SensorChart(
                            title = "High-G Accelerometer (g)",
                            points = chartData.highG,
                            modifier = Modifier.fillMaxSize(),
                        )
                        GraphMode.GYRO -> SensorChart(
                            title = "Gyroscope (mdps)",
                            points = chartData.gyro,
                            modifier = Modifier.fillMaxSize(),
                        )
                        GraphMode.BOTH_ACC -> Column(modifier = Modifier.fillMaxSize()) {
                            SensorChart(
                                title = "Low-G Accelerometer (mg)",
                                points = chartData.lowG,
                                modifier = Modifier.weight(1f),
                            )
                            SensorChart(
                                title = "High-G Accelerometer (g)",
                                points = chartData.highG,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            // Console
            val listState = rememberLazyListState()
            LaunchedEffect(logMessages.size) {
                if (logMessages.isNotEmpty()) {
                    listState.animateScrollToItem(logMessages.size - 1)
                }
            }

            Box(
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxWidth()
                    .padding(8.dp)
                    .background(Color.Black)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                ) {
                    items(logMessages) { msg ->
                        Text(
                            text = msg,
                            color = Color.Green,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
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
    modifier: Modifier = Modifier,
) {
    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(points) {
        if (points.isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction {
            lineSeries {
                series(y = points.map { it.x })
                series(y = points.map { it.y })
                series(y = points.map { it.z })
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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LegendDot(Color.Red, "X")
                LegendDot(Color.Green, "Y")
                LegendDot(Color.Blue, "Z")
            }
        }

        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(
                    LineCartesianLayer.LineProvider.series(
                        LineCartesianLayer.rememberLine(
                            fill = LineCartesianLayer.LineFill.single(fill(Color.Red)),
                        ),
                        LineCartesianLayer.rememberLine(
                            fill = LineCartesianLayer.LineFill.single(fill(Color.Green)),
                        ),
                        LineCartesianLayer.rememberLine(
                            fill = LineCartesianLayer.LineFill.single(fill(Color.Blue)),
                        ),
                    ),
                ),
                startAxis = VerticalAxis.rememberStart(),
                bottomAxis = HorizontalAxis.rememberBottom(),
            ),
            modelProducer = modelProducer,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            scrollState = rememberVicoScrollState(
                autoScrollCondition = AutoScrollCondition.OnModelGrowth,
            ),
        )
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
