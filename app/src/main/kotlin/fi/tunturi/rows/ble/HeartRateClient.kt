package fi.tunturi.rows.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import fi.tunturi.rows.hr.HeartRateMeasurement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private val HEART_RATE_SERVICE = sig16(0x180d)
private val HEART_RATE_MEASUREMENT = sig16(0x2a37)

sealed interface StrapState {
    data object Idle : StrapState
    data object Scanning : StrapState
    data class Connecting(val name: String) : StrapState
    data class Connected(val name: String) : StrapState
    data class Failed(val reason: String) : StrapState
}

data class StrapHit(val name: String, val address: String, val device: BluetoothDevice)

/**
 * A standard Bluetooth heart-rate strap (service 0x180D), alongside the rower.
 * Many consoles report no heart rate at all; a strap fills it in.
 *
 * Connects with autoConnect, so when the strap drops out (sweat, out of range,
 * taken off) the Bluetooth stack reconnects by itself whenever it is back.
 */
@SuppressLint("MissingPermission") // callers check RowerClient.hasPermission first
class HeartRateClient private constructor(private val context: Context) {

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("strap", Context.MODE_PRIVATE)
    private var gatt: BluetoothGatt? = null
    private var scanning = false

    private val _state = MutableStateFlow<StrapState>(StrapState.Idle)
    val state: StateFlow<StrapState> = _state.asStateFlow()
    private val _found = MutableStateFlow<List<StrapHit>>(emptyList())
    val found: StateFlow<List<StrapHit>> = _found.asStateFlow()
    private val _bpm = MutableStateFlow<Int?>(null)
    val bpm: StateFlow<Int?> = _bpm.asStateFlow()
    private var bpmAtMs = 0L

    /** The latest rate, or null when the strap has been silent for a few seconds. */
    fun freshBpm(): Int? = _bpm.value?.takeIf { System.currentTimeMillis() - bpmAtMs < STALE_MS }

    val hasSaved: Boolean get() = prefs.getString("address", null) != null

    fun scan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (scanning) return
        scanning = true
        _found.value = emptyList()
        _state.value = StrapState.Scanning
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(HEART_RATE_SERVICE)).build()
        scanner.startScan(listOf(filter), ScanSettings.Builder().build(), scanCallback)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopScan() }, SCAN_MS)
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        if (_state.value is StrapState.Scanning) _state.value = StrapState.Idle
    }

    fun connect(hit: StrapHit) {
        prefs.edit().putString("address", hit.address).putString("name", hit.name).apply()
        stopScan()
        open(hit.device, hit.name)
    }

    /** The strap used last time, without scanning. Used at app start. */
    fun connectLast() {
        val address = prefs.getString("address", null) ?: return
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        open(device, prefs.getString("name", null) ?: address)
    }

    /** Disconnects and stops reconnecting to this strap. */
    fun forget() {
        prefs.edit().clear().apply()
        close()
        _bpm.value = null
        _state.value = StrapState.Idle
    }

    private fun open(device: BluetoothDevice, name: String) {
        close()
        _state.value = StrapState.Connecting(name)
        gatt = device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) _state.value = StrapState.Failed("could not connect to $name")
    }

    private fun close() {
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val hit = StrapHit(device.name?.takeIf { it.isNotBlank() } ?: device.address, device.address, device)
            _found.update { list -> if (list.any { it.address == hit.address }) list else list + hit }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val name = prefs.getString("name", null) ?: g.device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                // Keep the gatt open: with autoConnect the stack brings it back.
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _bpm.value = null
                    if (gatt === g) _state.value = StrapState.Connecting(name)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val measurement = g.getService(HEART_RATE_SERVICE)?.getCharacteristic(HEART_RATE_MEASUREMENT)
            if (measurement == null) {
                _state.value = StrapState.Failed("${g.device.name ?: g.device.address} has no heart-rate service")
                return
            }
            g.setCharacteristicNotification(measurement, true)
            measurement.getDescriptor(CCCD)?.let {
                g.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
            _state.value = StrapState.Connected(prefs.getString("name", null) ?: g.device.address)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid != HEART_RATE_MEASUREMENT) return
            HeartRateMeasurement.parse(value)?.let {
                bpmAtMs = System.currentTimeMillis()
                _bpm.value = it
            }
        }
    }

    companion object {
        private const val SCAN_MS = 20_000L
        private const val STALE_MS = 5_000L
        private var instance: HeartRateClient? = null

        fun get(context: Context): HeartRateClient =
            instance ?: HeartRateClient(context.applicationContext).also { instance = it }
    }
}
