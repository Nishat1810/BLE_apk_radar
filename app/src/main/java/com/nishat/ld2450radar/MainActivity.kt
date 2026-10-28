package com.nishat.ld2450radar

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

private val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
private val RX_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
private val TX_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private val Bg = Color(0xFF07111B)
private val Panel = Color(0xFF101F2C)
private val Panel2 = Color(0xFF152938)
private val Neon = Color(0xFF56F0C1)
private val Cyan = Color(0xFF5AC8FA)
private val Muted = Color(0xFF8BA1B2)
private val Danger = Color(0xFFFF7D8D)

data class RadarTarget(
    val id: Int,
    val xMm: Int,
    val yMm: Int,
    val speedCms: Int,
    val resolutionMm: Int,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val distanceMm: Double get() = hypot(xMm.toDouble(), yMm.toDouble())
}

class Ld2450Parser {
    private val buffer = ArrayList<Byte>(256)
    var framesParsed: Long = 0
        private set

    fun feed(bytes: ByteArray): List<List<RadarTarget>> {
        bytes.forEach { buffer.add(it) }
        val output = mutableListOf<List<RadarTarget>>()

        while (buffer.size >= 4) {
            // Standard LD2450 target-report header AA FF 03 00.
            var header = -1
            for (i in 0..buffer.size - 4) {
                if (u(buffer[i]) == 0xAA && u(buffer[i + 1]) == 0xFF &&
                    u(buffer[i + 2]) == 0x03 && u(buffer[i + 3]) == 0x00) {
                    header = i
                    break
                }
            }

            if (header < 0) {
                // Keep the last 3 bytes in case the header is split across notifications.
                val keep = minOf(3, buffer.size)
                repeat(buffer.size - keep) { buffer.removeAt(0) }
                break
            }

            repeat(header) { buffer.removeAt(0) }
            if (buffer.size < 30) break

            if (u(buffer[28]) != 0x55 || u(buffer[29]) != 0xCC) {
                buffer.removeAt(0)
                continue
            }

            val frame = ByteArray(30) { buffer[it] }
            repeat(30) { buffer.removeAt(0) }
            val targets = mutableListOf<RadarTarget>()

            for (i in 0 until 3) {
                val offset = 4 + i * 8
                val rawX = u16(frame, offset)
                val rawY = u16(frame, offset + 2)
                val rawSpeed = u16(frame, offset + 4)
                val rawResolution = u16(frame, offset + 6)

                // X/Y are signed magnitude: bit 15 indicates negative.
                val x = signedMagnitude(rawX)
                val y = signedMagnitude(rawY)
                val speed = signedMagnitude(rawSpeed)
                val resolution = rawResolution and 0x7FFF

                // The all-zero record is the radar's empty target slot.
                if (rawX == 0 && rawY == 0 && rawSpeed == 0 && rawResolution == 0) continue

                targets.add(
                    RadarTarget(
                        id = i + 1,
                        xMm = x,
                        yMm = y,
                        speedCms = speed,
                        resolutionMm = resolution
                    )
                )
            }

            framesParsed++
            output.add(targets)
        }
        return output
    }

    private fun u(b: Byte) = b.toInt() and 0xFF
    private fun u16(a: ByteArray, offset: Int) = (a[offset].toInt() and 0xFF) or
            ((a[offset + 1].toInt() and 0xFF) shl 8)

    private fun signedMagnitude(raw: Int): Int {
        val magnitude = raw and 0x7FFF
        return if ((raw and 0x8000) != 0) -magnitude else magnitude
    }
}


/**
 * Rejects one-frame glitches. A target slot must appear in two consecutive
 * valid reports before being shown. This is a light temporal filter, not
 * proof that a reported target is a human.
 */
class TargetStabilizer {
    private data class State(
        var target: RadarTarget,
        var consecutiveHits: Int,
        var lastSeenAt: Long
    )
    private val states = mutableMapOf<Int, State>()

    fun update(current: List<RadarTarget>): List<RadarTarget> {
        val now = System.currentTimeMillis()
        val presentIds = current.map { it.id }.toSet()
        // A missing slot breaks the consecutive-report streak.
        states.forEach { (id, state) ->
            if (id !in presentIds) state.consecutiveHits = 0
        }

        for (target in current) {
            val old = states[target.id]
            if (old != null && hypot(
                    (target.xMm - old.target.xMm).toDouble(),
                    (target.yMm - old.target.yMm).toDouble()
                ) < 1800.0 && now - old.lastSeenAt < 500L
            ) {
                old.target = target
                old.consecutiveHits += 1
                old.lastSeenAt = now
            } else {
                states[target.id] = State(target, 1, now)
            }
        }

        // Expire missing slots quickly so the display does not leave ghosts.
        states.entries.removeAll { (_, state) -> now - state.lastSeenAt > 450L }
        return states.values
            .filter { it.consecutiveHits >= 2 && it.target.id in presentIds }
            .map { it.target }
    }
}

class BleRadarClient(private val context: Context) {
    var onStatus: ((String) -> Unit)? = null
    var onTargets: ((List<RadarTarget>) -> Unit)? = null
    var onDeviceFound: ((BluetoothDevice, String, Int) -> Unit)? = null

    private val parser = Ld2450Parser()
    private var gatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var scanning = false

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    @SuppressLint("MissingPermission")
    fun scan() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            onStatus?.invoke("Bluetooth is off")
            return
        }
        if (scanning) return
        scanning = true
        onStatus?.invoke("Scanning for LD2450 bridge…")
        try {
            a.bluetoothLeScanner.startScan(scanCallback)
        } catch (e: SecurityException) {
            scanning = false
            onStatus?.invoke("Bluetooth permission required")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        scanning = false
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan()
        onStatus?.invoke("Connecting to ${device.name ?: device.address}…")
        try {
            gatt?.close()
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            onStatus?.invoke("Connection failed: ${e.message ?: "unknown error"}")
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        txCharacteristic = null
        onStatus?.invoke("Disconnected")
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(g: BluetoothGatt) {
        val service = g.getService(SERVICE_UUID)
        if (service == null) {
            onStatus?.invoke("UART service not found. Check bridge firmware.")
            return
        }
        txCharacteristic = service.getCharacteristic(TX_UUID)
        val tx = txCharacteristic
        if (tx == null) {
            onStatus?.invoke("TX characteristic not found")
            return
        }
        g.setCharacteristicNotification(tx, true)
        val cccd = tx.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            onStatus?.invoke("BLE notification descriptor missing")
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
        onStatus?.invoke("Connected · enabling radar stream…")
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: "BLE device"
            onDeviceFound?.invoke(result.device, name, result.rssi)
            if (name == "LD2450_BLE_Bridge") {
                stopScan()
                connect(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            onStatus?.invoke("BLE scan failed ($errorCode)")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                onStatus?.invoke("GATT connection error ($status)")
                try { g.close() } catch (_: Exception) {}
                return
            }
            if (newState == android.bluetooth.BluetoothGatt.STATE_CONNECTED) {
                onStatus?.invoke("Connected · discovering services…")
                g.discoverServices()
            } else if (newState == android.bluetooth.BluetoothGatt.STATE_DISCONNECTED) {
                onStatus?.invoke("Disconnected")
                txCharacteristic = null
                try { g.close() } catch (_: Exception) {}
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) enableNotifications(g)
            else onStatus?.invoke("Service discovery failed")
        }

        @Deprecated("Deprecated by Android API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == TX_UUID) {
                characteristic.value?.let { handleIncoming(it.copyOf()) }
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == TX_UUID) handleIncoming(value.copyOf())
        }
    }

    private fun handleIncoming(bytes: ByteArray) {
        parser.feed(bytes).lastOrNull()?.let { onTargets?.invoke(it) }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RadarApp(this) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RadarApp(activity: ComponentActivity) {
    val client = remember { BleRadarClient(activity) }
    val stabilizer = remember { TargetStabilizer() }
    var status by remember { mutableStateOf("Ready to connect") }
    var targets by remember { mutableStateOf(emptyList<RadarTarget>()) }
    var discovered by remember { mutableStateOf(emptyList<Triple<BluetoothDevice, String, Int>>()) }
    var minDistance by remember { mutableFloatStateOf(0f) }
    var maxDistance by remember { mutableFloatStateOf(6f) }
    var showSettings by remember { mutableStateOf(false) }
    var framesSeen by remember { mutableLongStateOf(0L) }

    val permissions = remember {
        if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) client.scan()
        else status = "Bluetooth permission denied"
    }

    DisposableEffect(client) {
        client.onStatus = { status = it }
        client.onTargets = { list ->
            targets = stabilizer.update(list)
            framesSeen++
        }
        client.onDeviceFound = { device, name, rssi ->
            if (discovered.none { it.first.address == device.address }) {
                discovered = discovered + Triple(device, name, rssi)
            }
        }
        onDispose {
            client.disconnect()
            client.onStatus = null
            client.onTargets = null
            client.onDeviceFound = null
        }
    }

   val filtered = targets.filter {
    val d = it.distanceMm / 1000.0
    d >= minDistance && d <= maxDistance
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg,
            surface = Panel,
            primary = Neon,
            secondary = Cyan,
            onBackground = Color(0xFFEAF4FA),
            onSurface = Color(0xFFEAF4FA)
        )
    ) {
        Scaffold(containerColor = Bg) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(46.dp).background(Neon.copy(alpha = .12f), RoundedCornerShape(15.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Radar, null, tint = Neon, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("PULSE / LD2450", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("REAL-TIME SPATIAL TRACKING", color = Muted, fontSize = 10.sp, letterSpacing = 1.6.sp)
                        }
                        IconButton(onClick = { showSettings = !showSettings }) {
                            Icon(Icons.Default.Settings, "Filters", tint = Muted)
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(15.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(9.dp).background(if (status.contains("Connected")) Neon else Muted, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(status, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("BLE UART · no Wi-Fi required", color = Muted, fontSize = 11.sp)
                        }
                        Button(
                            onClick = {
                                discovered = emptyList()
                                if (status.contains("Connected")) client.disconnect()
                                else {
                                    if (permissions.all { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
                                        client.scan()
                                    } else permissionLauncher.launch(permissions)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Neon, contentColor = Bg),
                            contentPadding = PaddingValues(horizontal = 15.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Bluetooth, null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(if (status.contains("Connected")) "Disconnect" else "Scan & connect", fontSize = 12.sp)
                        }
                    }
                }
                if (discovered.isNotEmpty() && !status.contains("Connected")) {
                    item {
                        Text("NEARBY DEVICES", color = Muted, fontSize = 11.sp, letterSpacing = 1.4.sp)
                    }
                    items(discovered) { entry ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Panel),
                            onClick = { client.connect(entry.first) }
                        ) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Bluetooth, null, tint = Cyan)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(entry.second, fontWeight = FontWeight.Medium)
                                    Text(entry.first.address, color = Muted, fontSize = 11.sp)
                                }
                                Text("${entry.third} dBm", color = Muted, fontSize = 11.sp)
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard("TRACKS", filtered.size.toString(), "active slots", Modifier.weight(1f))
                        StatCard("FRAMES", framesSeen.toString(), "valid reports", Modifier.weight(1f))
                        StatCard("RANGE", String.format("%.1f m", maxDistance), "filter limit", Modifier.weight(1f))
                    }
                }
                item {
                    Text("LIVE RADAR", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
                    RadarCanvas(filtered, maxDistance, Modifier.fillMaxWidth().height(320.dp))
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("TARGET TRACKS", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
                        Text("${filtered.size} / 3", color = Neon, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(22.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Sensors, null, tint = Muted, modifier = Modifier.size(30.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("No targets in filter range", color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text("Connect the bridge and move into the radar field.", color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                } else {
                    items(filtered, key = { it.id }) { target -> TargetCard(target) }
                }
                if (showSettings) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("SOFTWARE FILTERS", color = Neon, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Text("Minimum range · ${"%.1f".format(minDistance)} m", color = Color.White, fontSize = 13.sp)
                            Slider(
                                value = minDistance,
                                onValueChange = { minDistance = it.coerceAtMost(maxDistance - .2f) },
                                valueRange = 0f..5.8f,
                                colors = SliderDefaults.colors(thumbColor = Neon, activeTrackColor = Neon)
                            )
                            Text("Maximum range · ${"%.1f".format(maxDistance)} m", color = Color.White, fontSize = 13.sp)
                            Slider(
                                value = maxDistance,
                                onValueChange = { maxDistance = it.coerceAtLeast(minDistance + .2f) },
                                valueRange = .2f..6f,
                                colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan)
                            )
                            Text(
                                "These filters hide tracks outside your chosen range. They cannot determine with certainty whether a track is a person.",
                                color = Muted, fontSize = 11.sp
                            )
                        }
                    }
                }
                item {
                    Text(
                        "Coordinates and speed are reported by the radar. Verify orientation and units in your test environment.",
                        color = Muted, fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, hint: String, modifier: Modifier = Modifier) {
    Column(
        modifier.background(Panel, RoundedCornerShape(16.dp)).padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, color = Muted, fontSize = 9.sp, letterSpacing = 1.2.sp)
        Text(value, color = Neon, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(hint, color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun RadarCanvas(targets: List<RadarTarget>, maxDistance: Float, modifier: Modifier = Modifier) {
    Box(modifier.background(Panel, RoundedCornerShape(20.dp)).padding(10.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height * .88f
            val radius = minOf(size.width * .44f, size.height * .78f)
            val rings = 4
            for (i in 1..rings) {
                val r = radius * i / rings
                drawArc(
                    color = Neon.copy(alpha = if (i == rings) .35f else .16f),
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            drawLine(Neon.copy(alpha = .20f), Offset(cx - radius, cy), Offset(cx + radius, cy), 1.dp.toPx())
            drawLine(Neon.copy(alpha = .12f), Offset(cx, cy), Offset(cx, cy - radius), 1.dp.toPx())
            for (deg in listOf(30f, 60f, 120f, 150f)) {
                val rad = Math.toRadians(deg.toDouble())
                drawLine(
                    Neon.copy(alpha = .10f),
                    Offset(cx, cy),
                    Offset(cx + (radius * kotlin.math.cos(rad)).toFloat(), cy - (radius * kotlin.math.sin(rad)).toFloat()),
                    1.dp.toPx()
                )
            }
            targets.forEach { target ->
                val range = maxDistance.coerceAtLeast(.2f)
                val px = cx + (target.xMm / 1000f / range) * radius
                val py = cy - (target.yMm / 1000f / range) * radius
                if (px in 0f..size.width && py in 0f..size.height) {
                    drawCircle(Cyan.copy(alpha = .18f), 13.dp.toPx(), Offset(px, py))
                    drawCircle(Neon, 5.dp.toPx(), Offset(px, py))
                    drawCircle(Color.White.copy(alpha = .9f), 2.dp.toPx(), Offset(px, py))
                }
            }
        }
        Text("6m", modifier.align(Alignment.TopCenter).padding(top = 4.dp), color = Muted, fontSize = 10.sp)
        Text("LEFT", modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 4.dp), color = Muted, fontSize = 9.sp)
        Text("RIGHT", modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 4.dp), color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun TargetCard(target: RadarTarget) {
    Row(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp))
            .border(1.dp, Neon.copy(alpha = .12f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(42.dp).background(Neon.copy(alpha = .12f), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("T${target.id}", color = Neon, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Target ${target.id}", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text("X ${target.xMm} mm   ·   Y ${target.yMm} mm", color = Muted, fontSize = 11.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${target.speedCms} cm/s", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("${"%.2f".format(target.distanceMm / 1000.0)} m away", color = Muted, fontSize = 10.sp)
        }
    }
}
