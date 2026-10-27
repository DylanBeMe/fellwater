package fi.tunturi.rows.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import fi.tunturi.rows.ftms.ControlResponse
import fi.tunturi.rows.ftms.FtmsControl
import fi.tunturi.rows.ftms.MachineFeatures
import fi.tunturi.rows.ftms.RowerData
import fi.tunturi.rows.ftms.RowerSample
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

data class ScanHit(
    val name: String,
    val address: String,
    val rssi: Int,
    val advertisesFtms: Boolean,
    val device: BluetoothDevice,
    val services: List<java.util.UUID> = emptyList(),
) {
    /**
     * Short 16-bit form for SIG services (0x1826), full UUID for vendor ones —
     * which is what identifies a proprietary console protocol.
     */
    val servicesLabel: String
        get() = if (services.isEmpty()) {
            "no services advertised"
        } else {
            services.joinToString(" ") { uuid ->
                val s = uuid.toString().lowercase()
                if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) {
                    "0x" + s.substring(4, 8)
                } else {
                    s
                }
            }
        }
}

sealed interface RowerState {
    data object Idle : RowerState
    data object Scanning : RowerState
    data class Connecting(val name: String) : RowerState
    data class Ready(val name: String, val features: MachineFeatures?, val controlAvailable: Boolean) : RowerState
    data class Failed(val reason: String) : RowerState
    /** The link dropped mid-session; retrying on its own with a growing delay. */
    data class Reconnecting(val name: String, val attempt: Int) : RowerState
}

internal fun sig16(short: Int): UUID =
    UUID.fromString(String.format("%04x-0000-1000-8000-00805f9b34fb", short))

internal val FTMS_SERVICE = sig16(0x1826)
internal val ROWER_DATA = sig16(0x2ad1)
internal val CONTROL_POINT = sig16(0x2ad9)
internal val MACHINE_STATUS = sig16(0x2ada)
internal val MACHINE_FEATURE = sig16(0x2acc)
internal val CCCD = sig16(0x2902)

/**
 * Distance model observed in Tunturi Routes: ~8.7 m per displayed stroke
 * (1020/117, 1100/126, 1190/133). An estimate, not a measurement.
 */
internal const val METRES_PER_CONSOLE_STROKE = 17.4

/** File the app appends every GATT send/receive line to, readable via ADB. */
internal const val RAW_LOG_FILE = "rawlog.txt"

/** Waits before each reconnect attempt after a dropped link: about five minutes in all. */
private val RECONNECT_DELAYS_MS = listOf(2_000L, 4_000L, 8_000L, 15_000L, 30_000L, 30_000L, 30_000L, 30_000L, 30_000L, 30_000L, 30_000L, 30_000L)

/** Distinguishes ISSC channels, which all share the 49535343 prefix. */
private fun shortId(uuid: UUID): String = uuid.toString().lowercase().split('-')[1]

/** ISCC/Microchip Transparent UART data pipe (what the iConsole console exposes). */
internal val ISSC_UART = UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616")

/** The only iConsole channel that answers (confirmed on hardware 2026-10-27). */
internal val ICONSOLE_CONTROL = UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3")

/** Replies arrive here (capture: notifications on ATT handle 0x0052). */
internal val ICONSOLE_NOTIFY = UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616")

/**
 * iConsole frame, as captured from their app against this console:
 *   F0 | cmd | 0x01 | payload... | sum(all previous) & 0xFF
 *
 * Byte 2 is always 0x01 — it is not a length. Replies use cmd + 0x10 and arrive
 * on the notify channel; requests go out as Write Command (no ATT ack).
 *
 * (Recovered from a btsnoop capture, not from the SDK: see docs/protocol/iconsole.md.)
 */
internal fun iconsoleFrame(command: Int, payload: ByteArray = byteArrayOf(0xE7.toByte())): ByteArray {
    val out = ByteArray(4 + payload.size)
    out[0] = 0xF0.toByte()
    out[1] = command.toByte()
    out[2] = 0x01
    payload.copyInto(out, 3)
    var sum = 0
    for (i in 0 until out.size - 1) sum += out[i].toInt() and 0xFF
    out[out.size - 1] = (sum and 0xFF).toByte()
    return out
}

/**
 * Minimal FTMS rower client: scan for anything advertising, connect, enable the
 * three notify/indicate channels, read capabilities, then stream samples.
 *
 * GATT only allows one outstanding operation, so every descriptor write, read and
 * control-point write goes through [enqueue] and each callback advances the queue.
 */
@SuppressLint("MissingPermission")
class RowerClient(private val context: Context) {

    private val _devices = MutableStateFlow<List<ScanHit>>(emptyList())
    val devices: StateFlow<List<ScanHit>> = _devices.asStateFlow()

    private val _state = MutableStateFlow<RowerState>(RowerState.Idle)
    val state: StateFlow<RowerState> = _state.asStateFlow()

    private val _samples = MutableSharedFlow<RowerSample>(extraBufferCapacity = 128)
    val samples: SharedFlow<RowerSample> = _samples.asSharedFlow()

    /** Human-readable GATT table of the last connection — the RE instrument. */
    private val _inventory = MutableStateFlow<String?>(null)
    val inventory: StateFlow<String?> = _inventory.asStateFlow()

    /** Hex of every notification from a non-FTMS device: passive sniffing. */
    private val _rawLog = MutableStateFlow("")
    val rawLog: StateFlow<String> = _rawLog.asStateFlow()
    private val subscribedUuids = mutableSetOf<UUID>()

    private val _responses = MutableSharedFlow<ControlResponse>(extraBufferCapacity = 16)
    val responses: SharedFlow<ControlResponse> = _responses.asSharedFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val ops = ArrayDeque<() -> Unit>()

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private var gatt: BluetoothGatt? = null
    private var controlPoint: BluetoothGattCharacteristic? = null
    private var machineFeatures: MachineFeatures? = null
    private var scanning = false
    private var connectTimeout: Runnable? = null
    private val connectTimeoutMs = 15_000L
    // iConsole session accumulators (see decodePollReply)
    private var lastStrokeCounter = -1
    private var strokeTotal = 0
    private var distanceM = 0.0
    private var lastSampleMs = 0L
    private var lastMoveMs = 0L
    private var sessionStartMs = 0L
    private val prefs = context.getSharedPreferences("rower", Context.MODE_PRIVATE)
    private var polling: Runnable? = null
    // Auto-reconnect: a dropped link mid-row is retried instead of ending the session.
    private var target: ScanHit? = null
    private var userClosed = false
    private var everReady = false
    private var reconnectAttempt = 0
    private var reconnectTask: Runnable? = null

    val hasPermission: Boolean
        get() = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    val bluetoothOn: Boolean get() = hasPermission && adapter?.isEnabled == true

    fun startScan() {
        if (!hasPermission) {
            _state.value = RowerState.Failed("Bluetooth permission not granted")
            return
        }
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            _state.value = RowerState.Failed("This device has no Bluetooth LE")
            return
        }
        if (scanning) return
        scanning = true
        _devices.value = emptyList()
        _state.value = RowerState.Scanning
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        // Scans until stopped: a console at the edge of range advertises rarely, and a
        // 20 s window was missing it entirely.
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        if (_state.value is RowerState.Scanning) _state.value = RowerState.Idle
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val services = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
            val hit = ScanHit(
                name = device.name?.takeIf { it.isNotBlank() } ?: "unnamed (${device.address})",
                address = device.address,
                rssi = result.rssi,
                advertisesFtms = services.contains(FTMS_SERVICE),
                device = device,
                services = services,
            )
            _devices.update { current ->
                // Update in place. Appending on every advertisement would move the row
                // to the bottom ~1x/second, i.e. a list that jumps under the finger.
                val index = current.indexOfFirst { it.address == hit.address }
                if (index < 0) {
                    current + hit
                } else {
                    current.toMutableList().also { it[index] = hit }
                }
            }
        }
    }

    /** Reconnects to the last rower without scanning. Used at app start. */
    fun connectLast(): Boolean {
        if (!hasPermission) return false
        val address = prefs.getString("last_address", null) ?: return false
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return false
        connect(
            ScanHit(
                name = prefs.getString("last_name", null) ?: address,
                address = address,
                rssi = 0,
                advertisesFtms = false,
                device = device,
            )
        )
        return true
    }

    fun connect(hit: ScanHit) {
        prefs.edit()
            .putString("last_address", hit.address)
            .putString("last_name", hit.name)
            .apply()
        stopScan()
        cancelReconnect()
        target = hit
        userClosed = false
        everReady = false
        reconnectAttempt = 0
        open(hit)
    }

    private fun open(hit: ScanHit) {
        closeGatt()
        _state.value = if (reconnectAttempt > 0) {
            RowerState.Reconnecting(hit.name, reconnectAttempt)
        } else {
            RowerState.Connecting(hit.name)
        }
        // The stack can accept connectGatt and then never call back (rower off, or
        // out of range), so never leave the screen stuck on "Connecting".
        val timeout = Runnable {
            val current = _state.value
            if (current is RowerState.Connecting || current is RowerState.Reconnecting) {
                closeGatt()
                linkLost("no response from ${hit.name}")
            }
        }
        connectTimeout = timeout
        handler.postDelayed(timeout, connectTimeoutMs)
        // connectGatt returns null when the stack refuses the connection outright.
        val connection = hit.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (connection == null) {
            cancelConnectTimeout()
            _state.value = RowerState.Failed("could not start a connection")
        } else {
            gatt = connection
        }
    }

    private var poll = true

    /**
     * Cycles every known request through the live channel, labelling each attempt,
     * so one session shows which command (if any) produces anything but the ack.
     */
    private fun startPolling(control: BluetoothGattCharacteristic) {
        stopPolling()
        poll = true
        // Replies follow reply = request + 0x10 (A0->B0, A1->B1, A2->B2). The SDK
        // parses D0/D1/D2/D7/E2/E4/E7, so the sport-data requests must live in the
        // C range: C1 should answer D1, the 20-byte rower frame.
        val variants = (0xC0..0xCF).map { cmd ->
            "C%X ".format(cmd and 0xF) to iconsoleFrame(cmd)
        }
        var index = 0
        val runnable = object : Runnable {
            override fun run() {
                if (!poll || gatt == null) return
                val (label, frame) = variants[index % variants.size]
                index++
                enqueue {
                    appendRaw("sent", ByteArray(0), note = "=== try $label ===")
                    advance()
                }
                enqueue { write(control, frame, noResponse = true) }
                handler.postDelayed(this, 400)
            }
        }
        polling = runnable
        handler.postDelayed(runnable, 400)
    }

    private fun stopPolling() {
        poll = false
        polling?.let { handler.removeCallbacks(it) }
        polling = null
    }

    private fun cancelConnectTimeout() {
        connectTimeout?.let { handler.removeCallbacks(it) }
        connectTimeout = null
    }

    /** Writes a control point command; silently ignored if the machine has none. */
    fun send(command: ByteArray) {
        val characteristic = controlPoint ?: return
        enqueue { write(characteristic, command) }
    }

    fun requestControl() = send(FtmsControl.requestControl())
    fun startWorkout() = send(FtmsControl.startResume())
    fun stopWorkout() = send(FtmsControl.stop())
    fun targetPower(watts: Int) = send(FtmsControl.targetPower(watts))
    fun targetResistanceLevel(tenths: Int) = send(FtmsControl.targetResistanceLevel(tenths))
    fun targetCadence(halfSpm: Int) = send(FtmsControl.targetCadence(halfSpm))

    fun disconnect() {
        userClosed = true
        everReady = false
        cancelReconnect()
        stopScan()
        closeGatt()
        _state.value = RowerState.Idle
    }

    /**
     * Zeroes the row totals this client integrates itself (iConsole distance, strokes,
     * elapsed). Called when a row is finished or discarded, so the next row starts at
     * 0 m instead of carrying on from the last one.
     */
    fun resetSession() {
        strokeTotal = 0
        distanceM = 0.0
        sessionStartMs = 0L
        lastMoveMs = 0L
    }

    /**
     * The link is gone without the user asking. Once a session has been live, retry
     * with a growing delay (about five minutes in all) before giving up: Bluetooth
     * blips mid-row are common, and the totals above carry on across a reconnect.
     */
    private fun linkLost(reason: String) {
        val hit = target
        if (userClosed || !everReady || hit == null || reconnectAttempt >= RECONNECT_DELAYS_MS.size) {
            _state.value = RowerState.Failed(reason)
            return
        }
        val delay = RECONNECT_DELAYS_MS[reconnectAttempt]
        reconnectAttempt++
        _state.value = RowerState.Reconnecting(hit.name, reconnectAttempt)
        val task = Runnable { if (!userClosed) open(hit) }
        reconnectTask = task
        handler.postDelayed(task, delay)
    }

    private fun cancelReconnect() {
        reconnectTask?.let { handler.removeCallbacks(it) }
        reconnectTask = null
    }

    private fun markReady(state: RowerState.Ready) {
        everReady = true
        reconnectAttempt = 0
        _state.value = state
    }

    private fun closeGatt() {
        cancelConnectTimeout()
        stopPolling()
        ops.clear()
        controlPoint = null
        machineFeatures = null
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
    }

    private fun enqueue(op: () -> Unit) {
        ops.addLast(op)
        if (ops.size == 1) handler.post { runNext() }
    }

    private fun advance() {
        if (ops.isNotEmpty()) ops.removeFirst()
        if (ops.isNotEmpty()) handler.post { runNext() }
    }

    private fun runNext() {
        ops.firstOrNull()?.invoke()
    }

    private fun write(characteristic: BluetoothGattCharacteristic, value: ByteArray, noResponse: Boolean = false) {
        // Deprecated on 33+, still functional, and keeps one code path for API 31+.
        characteristic.writeType = if (noResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        characteristic.value = value
        val accepted = gatt?.writeCharacteristic(characteristic) == true
        val target = shortId(characteristic.uuid)
        val mode = if (noResponse) "cmd" else "req"
        appendRaw("send", value, note = if (accepted) "to $target $mode" else "to $target REJECTED")
        if (!accepted) advance()
    }

    private fun enableNotifications(characteristic: BluetoothGattCharacteristic, indicate: Boolean) {
        val g = gatt ?: return advance()
        if (!g.setCharacteristicNotification(characteristic, true)) {
            appendRaw("sent", ByteArray(0), note = "notify-enable FAILED ${shortId(characteristic.uuid)}")
            return advance()
        }
        val descriptor = characteristic.getDescriptor(CCCD)
        if (descriptor == null) {
            appendRaw("sent", ByteArray(0), note = "no CCCD on ${shortId(characteristic.uuid)}")
            return advance()
        }
        descriptor.value = if (indicate) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        if (!g.writeDescriptor(descriptor)) {
            appendRaw("sent", ByteArray(0), note = "CCCD write REJECTED ${shortId(characteristic.uuid)}")
            advance()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            // Ignore callbacks from a connection we already closed ourselves.
            if (gatt == null) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                closeGatt()
                linkLost("connect failed (status $status)")
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    cancelConnectTimeout()
                    if (!g.discoverServices()) {
                        _state.value = RowerState.Failed("service discovery refused")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    closeGatt()
                    linkLost("rower disconnected")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            _inventory.value = describeGatt(g)
            val service = g.getService(FTMS_SERVICE)
            if (service == null) {
                // Not FTMS: subscribe to every notify/indicate channel and log raw
                // frames, so the vendor protocol can be reverse engineered from traffic.
                _rawLog.value = ""
                runCatching { java.io.File(context.filesDir, RAW_LOG_FILE).writeText("") }
                subscribedUuids.clear()
                g.services.orEmpty().forEach { s ->
                    s.characteristics.orEmpty().forEach { c ->
                        val notify = c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                        val indicate = c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                        if ((notify || indicate) && c.getDescriptor(CCCD) != null) {
                            subscribedUuids += c.uuid
                            enqueue { enableNotifications(c, indicate = indicate) }
                        }
                    }
                }
                // Keep the connection: the GATT table and raw frames are the useful part.
                // Overwritten with Ready below when this is a known iConsole.
                _state.value = RowerState.Failed(
                    "connected, but no FTMS (0x1826) service — logging raw frames"
                )
                // Only one channel answers (verified): 49535343-aca3-... . Send the
                // rower handshake there, then poll continuously — this looks like a
                // poll/response device, so a one-shot burst would never stream data.
                val control = g.services.orEmpty()
                    .flatMap { it.characteristics.orEmpty() }
                    .firstOrNull { it.uuid == ICONSOLE_CONTROL }
                if (control != null) {
                    // Exactly the sequence their app sends, then a steady A2 poll.
                    // 0xB0 + 01 is the start-workout command; without it the console
                    // only answers status polls and never streams sport data.
                    listOf(
                        iconsoleFrame(0xA0, byteArrayOf(0x01)),
                        iconsoleFrame(0xA0),
                        iconsoleFrame(0xA1),
                        iconsoleFrame(0xA5, byteArrayOf(0xE7.toByte(), 0x02)),
                        iconsoleFrame(0xA2),
                        iconsoleFrame(0xB0, byteArrayOf(0x01)),
                    ).forEach { frame -> enqueue { write(control, frame, noResponse = true) } }
                    startPolling(control)
                    // This is the Tunturi console and it streams through the poll above,
                    // so it is a live session: Ready, which keeps the screen on and the
                    // foreground service running. It used to stay "Failed" here.
                    markReady(
                        RowerState.Ready(g.device.name ?: g.device.address, features = null, controlAvailable = false)
                    )
                }
                return
            }
            val data = service.getCharacteristic(ROWER_DATA)
            val control = service.getCharacteristic(CONTROL_POINT)
            val feature = service.getCharacteristic(MACHINE_FEATURE)
            controlPoint = control
            val name = g.device.name ?: g.device.address

            data?.let { enqueue { enableNotifications(it, indicate = false) } }
            control?.let { enqueue { enableNotifications(it, indicate = true) } }
            service.getCharacteristic(MACHINE_STATUS)?.let { enqueue { enableNotifications(it, indicate = true) } }
            feature?.let {
                enqueue {
                    if (!g.readCharacteristic(it)) advance()
                }
            }
            control?.let { enqueue { write(it, FtmsControl.requestControl()) } }
            enqueue {
                markReady(RowerState.Ready(name, features = machineFeatures, controlAvailable = control != null))
                advance()
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            onFeatureRead(c, value)
            advance()
        }

        // API 31-32 deliver the value on the characteristic instead of the callback.
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            onFeatureRead(c, c.value)
            advance()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            appendRaw("sent", ByteArray(0), note = "status=$status ${shortId(c.uuid)}")
            advance()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            appendRaw("sent", ByteArray(0), note = "cccd status=$status ${shortId(d.characteristic.uuid)}")
            advance()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            deliver(c.uuid, value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            deliver(c.uuid, c.value)
        }
    }

    private fun describeGatt(g: BluetoothGatt): String = buildString {
        appendLine("device: ${g.device.name ?: g.device.address} (${g.device.address})")
        g.services.orEmpty().forEach { service ->
            appendLine(service.uuid.toString().lowercase())
            service.characteristics.orEmpty().forEach { c ->
                val props = buildList {
                    if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
                    if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
                    if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("write-nr")
                    if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
                    if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
                }.joinToString("|")
                appendLine("  $props  ${c.uuid.toString().lowercase()}")
            }
        }
    }

    private fun onFeatureRead(characteristic: BluetoothGattCharacteristic, value: ByteArray?) {
        if (characteristic.uuid != MACHINE_FEATURE || value == null) return
        val features = MachineFeatures.parse(value) ?: return
        machineFeatures = features
        val current = _state.value
        if (current is RowerState.Ready) _state.value = current.copy(features = features)
    }

    private fun deliver(uuid: UUID, value: ByteArray?) {
        if (value == null) return
        when (uuid) {
            ROWER_DATA -> RowerData.parse(value)?.let { _samples.tryEmit(it) }
            CONTROL_POINT -> ControlResponse.parse(value)?.let { _responses.tryEmit(it) }
            else -> if (uuid in subscribedUuids) {
                appendRaw("recv", value, note = shortId(uuid))
                decodePollReply(value)
                decodeRowerFrame(value)
            }
        }
    }

    /**
     * The 20-byte sport-data frame: F0 D1 01 <15 bytes> <checksum>, big-endian
     * fields at the offsets recovered from the SDK's own parser (see docs/protocol/iconsole.md).
     * Logged as numbers so the field meanings can be confirmed against the console
     * display rather than assumed.
     */
    /**
     * 0xB2 poll reply: `F0 B2 01 E7 | 15 data bytes | 1 trailer`.
     *
     * Every field below was matched against Tunturi Routes' own on-screen display,
     * captured by screenshot at the same byte offset as the frame:
     *
     *  - idx 9   stroke rate, spm        (screen 32 -> byte 31-33)
     *  - idx 17  power, watts            (screen 17/35/93 W -> bytes 18/36/94)
     *  - idx 13  stroke counter          (screen 117/126/133 strokes -> 59/63/68)
     *  - elapsed not reliable: bytes 11-12 cycle in a way that does not track either
     *    the console's active timer or the app's wall clock, so it is not surfaced.
     *
     * Distance and pace are NOT sent: their app derives distance at ~8.7 m per
     * displayed stroke (1020/117, 1100/126, 1190/133 all agree). We integrate the
     * same way, from the console stroke counter, so distance is an estimate with the
     * same assumptions their app makes — not a measured value.
     */
    private fun decodePollReply(v: ByteArray) {
        if (v.size < 20 || v[0] != 0xF0.toByte() || v[1] != 0xB2.toByte()) return

        val now = System.currentTimeMillis()
        val spmRaw = v[9].toInt() and 0xFF
        val wattsRaw = v[17].toInt() and 0xFF
        val strokeCounter = v[13].toInt() and 0xFF

        // Stroke counter is 8-bit and wraps; integrate the positive deltas only.
        val delta = if (lastStrokeCounter < 0) 0 else (strokeCounter - lastStrokeCounter)
        lastStrokeCounter = strokeCounter
        if (spmRaw > 4 && sessionStartMs == 0L) sessionStartMs = now
        if (delta > 0) strokeTotal += delta * 2 // their app counts two per console tick
        if (delta > 0 && lastSampleMs > 0) {
            val dtSec = (now - lastSampleMs) / 1000.0
            if (dtSec > 0.05) distanceM += delta * METRES_PER_CONSOLE_STROKE
        }
        lastSampleMs = now

        val speed = if (delta > 0 && distanceM > 0) {
            // estimate from the last stroke interval; smoothed by the UI anyway
            val dt = (now - lastMoveMs) / 1000.0
            if (dt in 0.2..10.0) (delta * METRES_PER_CONSOLE_STROKE / dt) else null
        } else null
        if (delta > 0) lastMoveMs = now

        _samples.tryEmit(
            RowerSample(
                strokeRateSpm = if (spmRaw <= 1) null else spmRaw.toDouble(),
                strokeCount = if (strokeTotal <= 0) null else strokeTotal,
                averageStrokeRateSpm = null,
                totalDistanceM = distanceM.takeIf { it > 0 }?.toInt(),
                instantPaceSecPer500m = speed?.takeIf { it > 0.1 }?.let { 500.0 / it },
                averagePaceSecPer500m = null,
                instantPowerW = if (wattsRaw <= 1) null else wattsRaw,
                averagePowerW = null,
                resistanceLevel = null,
                totalEnergyKcal = null,
                energyPerHourKcal = null,
                energyPerMinKcal = null,
                heartRateBpm = null,
                metabolicEquivalent = null,
                elapsedSec = if (sessionStartMs == 0L) null
                             else ((now - sessionStartMs) / 1000).toInt(),
                remainingSec = null,
                moreData = false,
            )
        )
    }

    /**
     * Any sport-data reply in the D/E range. Replies follow reply = request + 0x10,
     * so a C-range request answers D*. The SDK parses D0/D1/D2 (5/20/5 bytes),
     * D7 (6), E2 (9), E4 (7), E7 (5).
     *
     * Logged as labelled bytes only — the field meanings are not yet confirmed on
     * hardware, so nothing here is fed into the UI or the workout record.
     */
    private fun decodeRowerFrame(v: ByteArray) {
        if (v.size < 4 || v[0] != 0xF0.toByte()) return
        val cmd = v[1].toInt() and 0xFF
        if (cmd !in 0xD0..0xEF) return
        val data = (3 until v.size).joinToString(" ") { "%02x".format(v[it]) }
        appendRaw("sent", ByteArray(0), note = "D-frame cmd=%02X len=%d  %s".format(cmd, v.size, data))
    }

    private fun appendRaw(tag: String, value: ByteArray, note: String = "") {
        if (value.isEmpty() && note.isEmpty()) return
        val hex = value.joinToString(" ") { "%02x".format(it) }
        val stamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT)
            .format(java.util.Date())
        val arrow = when (tag) {
            "send" -> "-> "
            "sent" -> "-- "
            else -> "<- "
        }
        val line = "$stamp $arrow$hex  $note"
        _rawLog.update { existing ->
            // Bounded: keep the most recent 200 lines.
            (existing.lines() + line).takeLast(200).joinToString("\n")
        }
        // Also on disk: the screen only shows what fits, and ADB can read this whole file.
        runCatching {
            java.io.File(context.filesDir, RAW_LOG_FILE).appendText(line + "\n")
        }
    }
}
