package com.example.howl

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import java.lang.ref.WeakReference
import java.util.UUID
import android.Manifest
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanSettings
import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.le.ScanResult
import android.os.Build
import androidx.annotation.RequiresPermission

enum class ConnectionStatus {
    Disconnected,
    Connecting,
    Connected
}

enum class BluetoothEventType {
    Connected,
    Disconnected,
    CharacteristicChanged,
    CharacteristicRead,
    CharacteristicWrite,  // write result
    DescriptorWrite,      // descriptor write result (e.g. CCCD)
    Error                 // generic errors
}

// Bluetooth events that we pass on to our output
data class BluetoothEvent(
    val type: BluetoothEventType,
    val serviceUuid: UUID? = null,
    val characteristicUuid: UUID? = null,
    val descriptorUuid: UUID? = null,
    val data: ByteArray? = null,      // only for MessageReceived
    val success: Boolean? = null,     // for write/descriptor ops
    val errorMessage: String? = null  // for Error type or failed ops
)

data class SupportedDevice(
    val outputType: OutputType,
    val deviceName: String
)

object BluetoothHandler {
    private const val TAG = "BLE"
    private var contextRef: WeakReference<Context>? = null
    private var onConnectionStatusUpdate: ((status: ConnectionStatus) -> Unit)? = null
    private var bluetoothDevice: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null
    private val clientConfigDescriptor = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val PERMISSION_BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
    const val PERMISSION_BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
    private const val MAX_CONNECTION_RETRIES = 3
    private const val CONNECTION_RETRY_DELAY_MS = 500L
    private const val CONNECTION_SETTLE_DELAY_MS = 500L
    private var connectionRetryCount = 0
    private var connectionRetryHandler: Handler? = null

    val ALL_BLE_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private val supportedDevices = listOf(
        SupportedDevice(OutputType.COYOTE3, "47L121000"),
        SupportedDevice(OutputType.COYOTE2, "D-LAB ESTIM01")
    )

    fun initialise(context: Context,
                   onConnectionStatusUpdate: ((status: ConnectionStatus) -> Unit)?,
    ) {
        this.contextRef = WeakReference(context)
        this.onConnectionStatusUpdate = onConnectionStatusUpdate
    }

    @SuppressLint("MissingPermission")
    fun attemptConnection() {
        onConnectionStatusUpdate?.invoke(ConnectionStatus.Connecting)
        scanForDevices()
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun scanForDevices() {
        val context = contextRef?.get() ?: return
        val bluetooth = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetooth?.adapter ?: return
        val scanTimeoutDelay = 10000L // ms

        if (!adapter.isEnabled) {
            HLog.d(TAG, "Bluetooth is off.")
            disconnect()
            return
        }

        val scanner = adapter.bluetoothLeScanner
        // Use a permissive scan without name filters so we can log all found devices
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val timeoutHandler = Handler(Looper.getMainLooper())

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult?) {
                result?.let {
                    val deviceName = it.device?.name ?: "unnamed"
                    // Check against our known device names (software matching instead of hardware filter)
                    val foundDevice = supportedDevices.find { sd -> sd.deviceName == deviceName }
                    if (foundDevice != null) {
                        bluetoothDevice = it.device
                        HLog.d(TAG, "Found ${foundDevice.deviceName}, connecting...")
                        timeoutHandler.removeCallbacksAndMessages(null)
                        scanner?.stopScan(this)
                        // Small delay to let the BLE stack settle before connecting
                        Handler(Looper.getMainLooper()).postDelayed({
                            connectToDevice(foundDevice)
                        }, CONNECTION_SETTLE_DELAY_MS)
                    }
                }
            }
            override fun onScanFailed(errorCode: Int) {
                HLog.d(TAG, "Scan failed: $errorCode")
                disconnect()
            }
        }

        HLog.d(TAG, "Starting BLE scan...")
        scanner?.startScan(emptyList(), scanSettings, scanCallback)

        timeoutHandler.postDelayed({
            HLog.d(TAG, "Scan timeout, disconnecting.")
            scanner?.stopScan(scanCallback)
            disconnect()
        }, scanTimeoutDelay)
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(deviceInfo: SupportedDevice) {
        onConnectionStatusUpdate?.invoke(ConnectionStatus.Connecting)
        Player.switchOutput(deviceInfo.outputType)
        // Cancel any pending retry
        connectionRetryHandler?.removeCallbacksAndMessages(null)
        connectionRetryHandler = null
        // Close any existing GATT connection before creating a new one
        // but save the device reference since we need it below
        val device = bluetoothDevice
        closeGatt()
        bluetoothDevice = device
        connectionRetryCount = 0
        gatt = device?.connectGatt(contextRef?.get(), false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            HLog.d(TAG, "connectGatt returned null, scheduling retry...")
            scheduleRetry(deviceInfo)
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: Exception) {
            HLog.d(TAG, "Error closing GATT: ${e.message}")
        }
        gatt = null
        bluetoothDevice = null
    }

    private fun scheduleRetry(deviceInfo: SupportedDevice) {
        if (connectionRetryCount >= MAX_CONNECTION_RETRIES) {
            HLog.d(TAG, "Max connection retries reached")
            onConnectionStatusUpdate?.invoke(ConnectionStatus.Disconnected)
            Player.output.handleBluetoothEvent(BluetoothEvent(type = BluetoothEventType.Disconnected))
            return
        }
        connectionRetryCount++
        HLog.d(TAG, "Retrying connection (attempt ${connectionRetryCount}/$MAX_CONNECTION_RETRIES)...")
        onConnectionStatusUpdate?.invoke(ConnectionStatus.Connecting)
        connectionRetryHandler = Handler(Looper.getMainLooper())
        connectionRetryHandler?.postDelayed({
            // Re-scan for the device since the BluetoothDevice reference may be stale
            scanForDevices()
        }, CONNECTION_RETRY_DELAY_MS)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        connectionRetryHandler?.removeCallbacksAndMessages(null)
        connectionRetryHandler = null
        closeGatt()
        Player.output.handleBluetoothEvent(BluetoothEvent(type = BluetoothEventType.Disconnected))
        onConnectionStatusUpdate?.invoke(ConnectionStatus.Disconnected)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @RequiresPermission(PERMISSION_BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                HLog.d(TAG, "Connection state change error: status=$status newState=$newState")
                // Retry on transient connection errors rather than giving up immediately
                // Common retryable status codes: 62 (establishment failure), 133 (GATT_ERROR), 257 (timeout)
                if (connectionRetryCount < MAX_CONNECTION_RETRIES) {
                    val deviceName = bluetoothDevice?.name ?: "unknown"
                    val deviceInfo = supportedDevices.find { it.deviceName == deviceName }
                    if (deviceInfo != null) {
                        scheduleRetry(deviceInfo)
                        return
                    }
                }
                disconnect()
                return
            }
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                HLog.d(TAG, "Connected, discovering services...")
                connectionRetryCount = 0 // Reset retry counter on successful connection
                gatt.discoverServices()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                disconnect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                HLog.d(TAG, "Service discovery failed: status=$status")
                // Retry service discovery instead of immediately disconnecting
                if (connectionRetryCount < MAX_CONNECTION_RETRIES) {
                    val deviceName = bluetoothDevice?.name ?: "unknown"
                    val deviceInfo = supportedDevices.find { it.deviceName == deviceName }
                    if (deviceInfo != null) {
                        scheduleRetry(deviceInfo)
                        return
                    }
                }
                disconnect()
                return
            }
            HLog.d(TAG, "Services discovered.")
            onConnectionStatusUpdate?.invoke(ConnectionStatus.Connected)
            Player.output.handleBluetoothEvent(BluetoothEvent(type = BluetoothEventType.Connected))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val service = characteristic.service
            Player.output.handleBluetoothEvent(
                BluetoothEvent(
                    type = BluetoothEventType.CharacteristicChanged,
                    serviceUuid = service.uuid,
                    characteristicUuid = characteristic.uuid,
                    data = characteristic.value
                )
            )
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val service = characteristic.service
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Player.output.handleBluetoothEvent(
                    BluetoothEvent(
                        type = BluetoothEventType.CharacteristicRead,
                        serviceUuid = service.uuid,
                        characteristicUuid = characteristic.uuid,
                        data = characteristic.value
                    )
                )
            } else {
                Player.output.handleBluetoothEvent(
                    BluetoothEvent(
                        type = BluetoothEventType.Error,
                        serviceUuid = service.uuid,
                        characteristicUuid = characteristic.uuid,
                        errorMessage = "Characteristic read failed with status $status"
                    )
                )
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val service = characteristic.service
            Player.output.handleBluetoothEvent(
                BluetoothEvent(
                    type = BluetoothEventType.CharacteristicWrite,
                    serviceUuid = service.uuid,
                    characteristicUuid = characteristic.uuid,
                    success = (status == BluetoothGatt.GATT_SUCCESS),
                    errorMessage = if (status != BluetoothGatt.GATT_SUCCESS)
                        "Characteristic write failed with status $status" else null
                )
            )
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val characteristic = descriptor.characteristic
            val service = characteristic.service
            Player.output.handleBluetoothEvent(
                BluetoothEvent(
                    type = BluetoothEventType.DescriptorWrite,
                    serviceUuid = service.uuid,
                    characteristicUuid = characteristic.uuid,
                    descriptorUuid = descriptor.uuid,
                    success = (status == BluetoothGatt.GATT_SUCCESS),
                    errorMessage = if (status != BluetoothGatt.GATT_SUCCESS)
                        "Descriptor write failed with status $status" else null
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun getCharacteristic(
        serviceUuid: UUID,
        characteristicUuid: UUID
    ): BluetoothGattCharacteristic? {
        val service = gatt?.getService(serviceUuid)
        if (service == null) {
            Log.w(TAG, "Service $serviceUuid not found")
            return null
        }

        val characteristic = service.getCharacteristic(characteristicUuid)
        if (characteristic == null) {
            Log.w(TAG, "Characteristic $characteristicUuid not found in $serviceUuid")
            return null
        }

        return characteristic
    }

    @SuppressLint("MissingPermission")
    fun pollCharacteristic(serviceUuid: UUID, characteristicUuid: UUID): Boolean {
        val characteristic = getCharacteristic(serviceUuid, characteristicUuid) ?: return false

        if (gatt?.readCharacteristic(characteristic) != true) {
            Log.w(TAG, "Failed to initiate read for $characteristicUuid")
            return false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    fun subscribeToCharacteristic(serviceUuid: UUID, characteristicUuid: UUID): Boolean {
        val characteristic = getCharacteristic(serviceUuid, characteristicUuid) ?: return false

        if (gatt?.setCharacteristicNotification(characteristic, true) == true) {
            val cccDescriptor = characteristic.getDescriptor(clientConfigDescriptor)
            if (cccDescriptor == null) {
                Log.w(TAG, "CCCD descriptor not found for $characteristicUuid")
                return false
            }

            cccDescriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (gatt?.writeDescriptor(cccDescriptor) != true) {
                Log.w(TAG, "Failed to write CCCD for $characteristicUuid")
                return false
            }
            return true
        } else {
            Log.w(TAG, "setCharacteristicNotification failed for $characteristicUuid")
            return false
        }
    }

    @SuppressLint("MissingPermission")
    fun sendToDevice(serviceUuid: UUID, characteristicUuid: UUID, data: ByteArray): Boolean {
        val characteristic = getCharacteristic(serviceUuid, characteristicUuid) ?: return false

        characteristic.value = data
        if (gatt?.writeCharacteristic(characteristic) != true) {
            Log.w(TAG, "Failed to write characteristic $characteristicUuid")
            return false
        }
        return true
    }
}