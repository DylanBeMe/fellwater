package fi.tunturi.rows.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import fi.tunturi.rows.ftms.FtmsControl
import java.util.UUID

/**
 * A stand-in FTMS rower, advertised from this phone. Lets the whole chain —
 * scan, connect, CCCD, notifications, parsing, control point — be exercised
 * without a real machine, and stays useful as a harness for the [RowerClient].
 *
 * Only implements the parts we consume: Rower Data (notify), Machine Feature
 * (read), Control Point (write + indicate), Machine Status (indicate).
 */
@SuppressLint("MissingPermission")
class FakeRower(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var server: BluetoothGattServer? = null
    private var advertising = false
    private var connectedDevice: BluetoothDevice? = null
    private var subscribed = false
    private var rowerData: BluetoothGattCharacteristic? = null
    private var controlPoint: BluetoothGattCharacteristic? = null
    private var machineStatus: BluetoothGattCharacteristic? = null

    private var elapsedSec = 0
    private var distanceM = 0
    private var strokeCount = 0
    private var targetPowerW = 180
    private var resistanceLevel = 3

    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    val running: Boolean get() = server != null

    /**
     * The phone's own adapter as a connectable device. A GATT client can open a
     * loopback connection to the GATT server running in the same app, which
     * exercises connect, CCCD writes, notifications and the control point without
     * a second radio.
     */
    fun selfHit(): ScanHit? {
        val address = adapter?.address ?: return null
        val device = adapter.getRemoteDevice(address) ?: return null
        return ScanHit(
            name = "Simulated rower",
            address = address,
            rssi = 0,
            advertisesFtms = true,
            device = device,
        )
    }

    fun start() {
        if (server != null) return
        // A denied BLUETOOTH_ADVERTISE must not take the app down with it.
        runCatching { startUnsafe() }
    }

    private fun startUnsafe() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val gattServer = manager.openGattServer(context, serverCallback) ?: return

        val service = BluetoothGattService(FTMS_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        fun characteristic(uuid: UUID, props: Int) = BluetoothGattCharacteristic(
            uuid, props, BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        val features = characteristic(
            MACHINE_FEATURE,
            BluetoothGattCharacteristic.PROPERTY_READ,
        ).apply {
            // machine: cadence, distance, pace, resistance, energy, HR, elapsed, power
            // target: resistance, power, cadence
            value = encodeFeatures()
        }

        val data = characteristic(
            ROWER_DATA,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        )
        val control = characteristic(
            CONTROL_POINT,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_INDICATE,
        )
        val status = characteristic(
            MACHINE_STATUS,
            BluetoothGattCharacteristic.PROPERTY_INDICATE,
        )

        service.addCharacteristic(features)
        service.addCharacteristic(data)
        service.addCharacteristic(control)
        service.addCharacteristic(status)
        if (!gattServer.addService(service)) {
            gattServer.close()
            return
        }

        server = gattServer
        rowerData = data
        controlPoint = control
        machineStatus = status
        advertise()
        handler.post(tick)
    }

    fun stop() {
        handler.removeCallbacks(tick)
        advertising = false
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { server?.close() }
        server = null
        connectedDevice = null
        subscribed = false
        elapsedSec = 0
        distanceM = 0
        strokeCount = 0
    }

    private fun advertise() {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(FTMS_SERVICE))
            .setIncludeDeviceName(true)
            .build()
        advertiser.startAdvertising(settings, data, advertiseCallback)
        advertising = true
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            advertising = false
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (server == null) return
            elapsedSec += 1
            // 24 spm at 4 m/s, matching the 125 s/500 m pace we report.
            distanceM += 4
            strokeCount += if (elapsedSec % 2 == 0) 1 else 0
            publish()
            handler.postDelayed(this, 1000)
        }
    }

    private fun publish() {
        val device = connectedDevice ?: return
        val data = rowerData ?: return
        if (!subscribed) return
        val value = encodeRowerData()
        val server = server ?: return
        data.value = value
        runCatching { server.notifyCharacteristicChanged(device, data, false, value) }
            .recoverCatching { server.notifyCharacteristicChanged(device, data, false) }
    }

    private fun encodeFeatures(): ByteArray {
        val machine = (1 shl 1) or (1 shl 2) or (1 shl 5) or (1 shl 7) or
            (1 shl 9) or (1 shl 10) or (1 shl 12) or (1 shl 14)
        val target = (1 shl 2) or (1 shl 3) or (1 shl 16)
        return u32(machine) + u32(target)
    }

    private fun encodeRowerData(): ByteArray {
        val paceSecPer500 = 125
        val flags = (1 shl 1) or (1 shl 2) or (1 shl 3) or (1 shl 5) or (1 shl 7) or (1 shl 11) or (1 shl 13)
        val out = mutableListOf<Byte>()
        fun add(v: Int) = out.add((v and 0xFF).toByte())
        fun add16(v: Int) {
            add(v and 0xFF); add((v shr 8) and 0xFF)
        }

        add16(flags)
        add(48) // stroke rate raw 48 = 24 spm
        add16(strokeCount)
        add(46) // average stroke rate raw 46 = 23 spm
        add(distanceM and 0xFF); add((distanceM shr 8) and 0xFF); add((distanceM shr 16) and 0xFF)
        add16(paceSecPer500)
        add16(targetPowerW)
        add16(resistanceLevel)
        add(142) // heart rate
        add16(elapsedSec)
        return out.toByteArray()
    }

    private fun u32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
    )

    private fun indicate(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val server = server ?: return
        characteristic.value = value
        runCatching { server.notifyCharacteristicChanged(device, characteristic, true, value) }
            .recoverCatching { server.notifyCharacteristicChanged(device, characteristic, true) }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            connectedDevice = if (newState == BluetoothProfile.STATE_CONNECTED) device else null
            if (newState != BluetoothProfile.STATE_CONNECTED) subscribed = false
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            val value = if (subscribed) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (descriptor.uuid == CCCD) {
                subscribed = value.any { it != 0.toByte() }
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            if (subscribed) publish()
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            server?.sendResponse(
                device, requestId, BluetoothGatt.GATT_SUCCESS, offset, characteristic.value,
            )
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            if (characteristic.uuid != CONTROL_POINT || value.isEmpty()) return

            val op = value[0].toInt() and 0xFF
            when (op) {
                FtmsControl.OP_TARGET_POWER -> {
                    if (value.size >= 3) {
                        targetPowerW = (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
                    }
                }
                FtmsControl.OP_TARGET_RESISTANCE -> {
                    val raw = if (value.size >= 3) {
                        (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
                    } else {
                        value[1].toInt() and 0xFF
                    }
                    resistanceLevel = raw / 100
                }
                else -> Unit
            }
            val control = controlPoint ?: return
            machineStatus?.let {
                indicate(device, it, byteArrayOf(0x03, 0x00)) // stopped-by-user style status, harmless
            }
            indicate(device, control, byteArrayOf(0x80.toByte(), op.toByte(), 0x01))
        }
    }
}
