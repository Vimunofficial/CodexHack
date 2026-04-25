package com.codexhackathon.echohome.ble

import android.annotation.SuppressLint
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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.codexhackathon.echohome.data.EchoConfig
import com.codexhackathon.echohome.data.LedZone
import org.json.JSONObject
import java.util.UUID

class EchoBleController(
    private val context: Context,
    private val onConnectionChanged: (connected: Boolean, scanning: Boolean, status: String) -> Unit,
    private val onStatusReceived: (leds: List<LedZone>) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var scanCallback: ScanCallback? = null

    private val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    @SuppressLint("MissingPermission")
    fun connect() {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null) {
            onConnectionChanged(false, false, "Bluetooth is not available on this phone")
            return
        }
        if (!adapter.isEnabled) {
            onConnectionChanged(false, false, "Turn on Bluetooth and try again")
            return
        }

        disconnect(closeStatus = false)

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onConnectionChanged(false, false, "Bluetooth scanner is unavailable")
            return
        }

        scanning = true
        onConnectionChanged(false, true, "Scanning for EchoHome")

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val advertisedName = result.scanRecord?.deviceName
                val deviceName = advertisedName ?: runCatching { result.device.name }.getOrNull().orEmpty()
                val hasService = result.scanRecord?.serviceUuids?.contains(ParcelUuid(EchoConfig.BleServiceUuid)) == true
                if (deviceName.startsWith(EchoConfig.BLE_DEVICE_PREFIX, ignoreCase = true) || hasService) {
                    stopScan()
                    connectGatt(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                scanning = false
                onConnectionChanged(false, false, "Bluetooth scan failed: $errorCode")
            }
        }

        scanCallback = callback
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        runCatching {
            scanner.startScan(null, settings, callback)
        }.onFailure {
            scanning = false
            onConnectionChanged(false, false, it.message ?: "Bluetooth scan could not start")
        }

        mainHandler.postDelayed({
            if (scanning) {
                stopScan()
                onConnectionChanged(false, false, "EchoHome Bluetooth device not found")
            }
        }, 12_000)
    }

    @SuppressLint("MissingPermission")
    fun disconnect(closeStatus: Boolean = true) {
        stopScan()
        commandCharacteristic = null
        statusCharacteristic = null
        runCatching {
            gatt?.disconnect()
            gatt?.close()
        }
        gatt = null
        if (closeStatus) {
            onConnectionChanged(false, false, "Bluetooth disconnected")
        }
    }

    @SuppressLint("MissingPermission")
    fun sendCommand(command: String): Boolean {
        val activeGatt = gatt ?: return false
        val characteristic = commandCharacteristic ?: return false
        val payload = command.toByteArray(Charsets.UTF_8)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activeGatt.writeCharacteristic(
                characteristic,
                payload,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == 0
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = payload
            @Suppress("DEPRECATION")
            activeGatt.writeCharacteristic(characteristic)
        }
    }

    @SuppressLint("MissingPermission")
    fun readStatus() {
        val activeGatt = gatt ?: return
        val characteristic = statusCharacteristic ?: return
        runCatching {
            activeGatt.readCharacteristic(characteristic)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val scanner = manager?.adapter?.bluetoothLeScanner
        val callback = scanCallback
        if (callback != null) {
            runCatching { scanner?.stopScan(callback) }
        }
        scanCallback = null
        scanning = false
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(device: BluetoothDevice) {
        onConnectionChanged(false, false, "Connecting to ${runCatching { device.name }.getOrNull() ?: EchoConfig.BLE_DEVICE_PREFIX}")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                this@EchoBleController.gatt = gatt
                onConnectionChanged(false, false, "Discovering Bluetooth services")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                commandCharacteristic = null
                statusCharacteristic = null
                this@EchoBleController.gatt = null
                runCatching { gatt.close() }
                onConnectionChanged(false, false, "Bluetooth disconnected")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onConnectionChanged(false, false, "Bluetooth service discovery failed")
                return
            }

            val service = gatt.getService(EchoConfig.BleServiceUuid)
            commandCharacteristic = service?.getCharacteristic(EchoConfig.BleCommandUuid)
            statusCharacteristic = service?.getCharacteristic(EchoConfig.BleStatusUuid)

            if (commandCharacteristic == null || statusCharacteristic == null) {
                onConnectionChanged(false, false, "EchoHome service was not found")
                disconnect(closeStatus = false)
                return
            }

            enableStatusNotifications(gatt, statusCharacteristic!!)
            gatt.readCharacteristic(statusCharacteristic)
            onConnectionChanged(true, false, "ESP32 online by Bluetooth")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == EchoConfig.BleStatusUuid) {
                parseStatus(String(value, Charsets.UTF_8))?.let(onStatusReceived)
            }
        }

        @Deprecated("Deprecated by Android API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == EchoConfig.BleStatusUuid) {
                @Suppress("DEPRECATION")
                parseStatus(String(characteristic.value ?: ByteArray(0), Charsets.UTF_8))?.let(onStatusReceived)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == EchoConfig.BleStatusUuid) {
                parseStatus(String(value, Charsets.UTF_8))?.let(onStatusReceived)
            }
        }

        @Deprecated("Deprecated by Android API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == EchoConfig.BleStatusUuid) {
                @Suppress("DEPRECATION")
                parseStatus(String(characteristic.value ?: ByteArray(0), Charsets.UTF_8))?.let(onStatusReceived)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableStatusNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(cccdUuid) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun parseStatus(payload: String): List<LedZone>? {
        return runCatching {
            val root = JSONObject(payload)
            val ledArray = root.optJSONArray("leds") ?: return null
            buildList {
                for (index in 0 until ledArray.length()) {
                    val item = ledArray.getJSONObject(index)
                    val ledId = item.optInt("id", index + 1)
                    add(
                        LedZone(
                            id = ledId,
                            name = EchoConfig.LedNames.getOrElse(ledId - 1) { "LED $ledId" },
                            pin = if (item.has("pin")) item.optInt("pin") else EchoConfig.LedPins.getOrNull(ledId - 1),
                            isOn = item.optBoolean("state", false)
                        )
                    )
                }
            }
        }.getOrNull()
    }
}
