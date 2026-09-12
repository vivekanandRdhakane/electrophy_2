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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

// UUIDs
val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
val TX_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // Notify (ESP -> App)
val RX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // Write (App -> ESP)
val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

enum class ConnectionState { Disconnected, Scanning, Connecting, Connected }

@SuppressLint("MissingPermission")
class BleViewModel : ViewModel() {
    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _logMessages = MutableStateFlow<List<String>>(emptyList())
    val logMessages: StateFlow<List<String>> = _logMessages

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

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

            // Middle Section: Console
            val listState = rememberLazyListState()
            LaunchedEffect(logMessages.size) {
                if (logMessages.isNotEmpty()) {
                    listState.animateScrollToItem(logMessages.size - 1)
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
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

            // Bottom Bar: Commands
            Text("Commands", modifier = Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelMedium)
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val commands = listOf(
                    "Low-G" to "low_acc\r\n",
                    "High-G" to "high_acc\r\n",
                    "Both Acc" to "both_acc\r\n",
                    "Gyro" to "only_gyro\r\n",
                    "All" to "all\r\n"
                )

                commands.forEach { (label, cmd) ->
                    Button(
                        onClick = { viewModel.sendCommand(cmd) },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
                        enabled = connectionState == ConnectionState.Connected
                    ) {
                        Text(label, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}
