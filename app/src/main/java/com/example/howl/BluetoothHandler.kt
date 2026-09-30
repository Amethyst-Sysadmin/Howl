package com.example.howl

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import java.util.UUID
import android.Manifest
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanSettings
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.os.Build
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlin.time.Duration.Companion.milliseconds

enum class ConnectionStatus {
    Disconnected,
    Scanning,
    Connecting,
    Connected,
}

@SuppressLint("MissingPermission") // Permissions are assumed to be handled by the caller/UI
class BluetoothHandler(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val deviceName: String,
    private val friendlyName: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val onAddressChanged: () -> Unit = {}
) {
    companion object {
        private const val TAG = "BLE"
        private val SCAN_TIMEOUT = 15000.milliseconds
        private val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val ALL_BLE_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null
    var rememberedAddress: String? = null

    // 1. Reactive Connection State
    private val _connectionState = MutableStateFlow(ConnectionStatus.Disconnected)
    val connectionState: StateFlow<ConnectionStatus> = _connectionState.asStateFlow()

    private val _batteryLevel = MutableStateFlow(0)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    // 2. Continuous Notifications Flow
    private val _notifications = MutableSharedFlow<BluetoothNotification>(extraBufferCapacity = 64)
    val notifications: SharedFlow<BluetoothNotification> = _notifications.asSharedFlow()

    // 3. Internal Event Routing for GATT Callbacks (Changed to Channel for guaranteed delivery)
    private var gattEvents = Channel<GattEvent>(Channel.BUFFERED)

    // 4. Sequential Operation Queue
    private val operationMutex = Mutex()

    fun setBatteryLevel(percent: Int) {
        _batteryLevel.value = percent
    }
    private fun updateState(state: ConnectionStatus) {
        _connectionState.value = state
    }

    suspend fun scanAndConnect(): Result<Unit> = operationMutex.withLock {
        var device: BluetoothDevice? = null

        try {
            // Attempt direct connection if we have a remembered address
            if (!rememberedAddress.isNullOrBlank()) {
                updateState(ConnectionStatus.Connecting)
                HLog.d(TAG, "Attempting direct connection to remembered address: $rememberedAddress")
                try {
                    val remoteDevice = bluetoothAdapter?.getRemoteDevice(rememberedAddress!!)
                    if (remoteDevice != null) {
                        executeWithRetry(important = true, maxAttempts = 2) {
                            try {
                                connectGattSuspend(remoteDevice)
                                discoverServicesSuspend()
                            } catch (e: Exception) {
                                closeGatt()
                                throw e
                            }
                        }
                        device = remoteDevice
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    HLog.w(TAG, "Direct connection failed, falling back to scan.")
                    device = null
                }
            }

            // Fall back to scanning if direct connection failed or wasn't attempted
            if (device == null) {
                updateState(ConnectionStatus.Scanning)

                val scannedDevice = withTimeoutOrNull(SCAN_TIMEOUT) {
                    scanForDevice()
                } ?: run {
                    val message = "Scan timed out, no $friendlyName found."
                    HLog.w(TAG, message)
                    throw BleException(message)
                }

                updateState(ConnectionStatus.Connecting)

                executeWithRetry(important = true, maxAttempts = 3) {
                    try {
                        connectGattSuspend(scannedDevice)
                        discoverServicesSuspend()
                    } catch (e: Exception) {
                        closeGatt()
                        throw e
                    }
                }
                device = scannedDevice
            }

            // Only replace the remembered address upon successful connection
            val connectedDevice = device ?: throw BleException("Device is null after connection attempts")
            if (rememberedAddress != connectedDevice.address) {
                rememberedAddress = connectedDevice.address
                onAddressChanged() // <-- Trigger save
            }

            rememberedAddress = connectedDevice.address

            updateState(ConnectionStatus.Connected)
            return@withLock Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            disconnect()
            return@withLock Result.failure(e)
        }
    }

    fun disconnect() {
        if (_connectionState.value == ConnectionStatus.Disconnected) return
        if (bluetoothGatt != null) {
            HLog.d(TAG, "$friendlyName disconnected.")
        }

        updateState(ConnectionStatus.Disconnected)

        try {
            bluetoothGatt?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }

        closeGatt()
    }

    suspend fun readCharacteristic(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        important: Boolean = true
    ): Result<ByteArray> = queueOperation(important) {
        val characteristic = getCharacteristic(serviceUuid, characteristicUuid)

        if (!bluetoothGatt!!.readCharacteristic(characteristic)) {
            throw BleException("Failed to initiate read operation.")
        }

        val event = awaitGattEvent<GattEvent.CharacteristicRead> {
            it is GattEvent.CharacteristicRead && it.uuid == characteristicUuid
        }

        if (event.status == BluetoothGatt.GATT_SUCCESS) event.value
        else throw BleException("Read failed with status: ${event.status}")
    }

    suspend fun writeCharacteristic(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        payload: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        important: Boolean = true
    ): Result<Unit> = queueOperation(important) {
        val characteristic = getCharacteristic(serviceUuid, characteristicUuid)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = bluetoothGatt!!.writeCharacteristic(characteristic, payload, writeType)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                throw BleException("Failed to initiate write operation (status: $status).")
            }
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = payload
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            if (!bluetoothGatt!!.writeCharacteristic(characteristic)) {
                throw BleException("Failed to initiate write operation.")
            }
        }

        val event = awaitGattEvent<GattEvent.CharacteristicWrite> {
            it is GattEvent.CharacteristicWrite && it.uuid == characteristicUuid
        }

        if (event.status == BluetoothGatt.GATT_SUCCESS) Unit
        else throw BleException("Write failed with status: ${event.status}")
    }

    suspend fun enableNotifications(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        important: Boolean = true
    ): Result<Unit> = queueOperation(important) {
        val characteristic = getCharacteristic(serviceUuid, characteristicUuid)

        if (!bluetoothGatt!!.setCharacteristicNotification(characteristic, true)) {
            throw BleException("Failed to set local characteristic notification.")
        }

        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
            ?: throw BleException("CCCD descriptor not found for $characteristicUuid")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = bluetoothGatt!!.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                throw BleException("Failed to initiate descriptor write (status: $status).")
            }
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            if (!bluetoothGatt!!.writeDescriptor(descriptor)) {
                throw BleException("Failed to initiate descriptor write.")
            }
        }

        awaitGattEvent<GattEvent.DescriptorWrite> {
            it is GattEvent.DescriptorWrite && it.uuid == CLIENT_CHARACTERISTIC_CONFIG
        }
        Unit
    }

    suspend fun disableNotifications(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        important: Boolean = true
    ): Result<Unit> = queueOperation(important) {
        val characteristic = getCharacteristic(serviceUuid, characteristicUuid)

        if (!bluetoothGatt!!.setCharacteristicNotification(characteristic, false)) {
            throw BleException("Failed to clear local characteristic notification.")
        }

        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
            ?: throw BleException("CCCD descriptor not found for $characteristicUuid")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = bluetoothGatt!!.writeDescriptor(descriptor, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                throw BleException("Failed to initiate descriptor write (status: $status).")
            }
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            if (!bluetoothGatt!!.writeDescriptor(descriptor)) {
                throw BleException("Failed to initiate descriptor write.")
            }
        }

        awaitGattEvent<GattEvent.DescriptorWrite> {
            it is GattEvent.DescriptorWrite && it.uuid == CLIENT_CHARACTERISTIC_CONFIG
        }
        Unit
    }

    // --- INTERNAL QUEUE & RETRY ENGINE ---

    private suspend fun <T> queueOperation(important: Boolean, block: suspend () -> T): Result<T> {
        // Optimization for real-time streaming: If the BLE stack is busy, drop the pulse
        // instead of queuing it. This prevents massive backlogs of outdated stimulation data.
        if (!important) {
            if (!operationMutex.tryLock()) {
                return Result.failure(BleException("BLE queue busy, skipping unimportant operation."))
            }
            try {
                if (_connectionState.value != ConnectionStatus.Connected || bluetoothGatt == null) {
                    return Result.failure(BleException("Device is not connected."))
                }
                return Result.success(block())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return Result.failure(e)
            } finally {
                operationMutex.unlock()
            }
        }

        return operationMutex.withLock {
            if (_connectionState.value != ConnectionStatus.Connected || bluetoothGatt == null) {
                return@withLock Result.failure(BleException("Device is not connected."))
            }

            try {
                Result.success(executeWithRetry(true, 3, block))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private suspend fun <T> executeWithRetry(
        important: Boolean,
        maxAttempts: Int,
        block: suspend () -> T
    ): T {
        var lastException: Exception? = null

        for (attempt in 1..maxAttempts) {
            try {
                return block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxAttempts && important) {
                    Log.d(TAG, "Retrying Bluetooth request (attempt ${attempt + 1}/$maxAttempts) due to: ${e.message}")
                    delay((500 * attempt).milliseconds)
                }
            }
        }
        throw BleException("Operation failed after $maxAttempts attempts. Last error: ${lastException?.message}")
    }

    /**
     * Safely awaits a specific GATT event while actively listening for disconnections.
     * Includes a strict timeout to prevent permanent deadlocks if the Android BLE stack hangs.
     */
    private suspend fun <T : GattEvent> awaitGattEvent(predicate: (GattEvent) -> Boolean): T {
        val event = withTimeoutOrNull(3000.milliseconds) {
            try {
                var nextEvent = gattEvents.receive()
                while (!predicate(nextEvent) && !(nextEvent is GattEvent.ConnectionStateChange && nextEvent.newState == BluetoothProfile.STATE_DISCONNECTED)) {
                    Log.w(TAG, "Ignoring unexpected GATT event while waiting: $nextEvent")
                    nextEvent = gattEvents.receive()
                }
                nextEvent
            } catch (e: ClosedReceiveChannelException) {
                throw BleException("GATT channel closed unexpectedly.")
            }
        } ?: run {
            val isConnected = _connectionState.value == ConnectionStatus.Connected
            if (isConnected) {
                HLog.e(TAG, "GATT event timed out. Forcing disconnect.")
                disconnect()
            } else {
                HLog.w(TAG, "Connection attempt timed out.")
                try {
                    bluetoothGatt?.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Error disconnecting GATT after timeout", e)
                }
                closeGatt()
            }
            throw BleException("GATT operation timed out.")
        }

        if (event is GattEvent.ConnectionStateChange && event.newState == BluetoothProfile.STATE_DISCONNECTED) {
            throw BleException("Device disconnected during operation.")
        }
        @Suppress("UNCHECKED_CAST")
        return event as T
    }

    // --- INTERNAL BLE SUSPEND WRAPPERS ---

    private suspend fun scanForDevice(): BluetoothDevice? = suspendCancellableCoroutine { cont ->
        HLog.d(TAG, "Starting Bluetooth scan for $friendlyName.")
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        val seenAddresses = mutableSetOf<String>()


        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && locationManager != null && !LocationManagerCompat.isLocationEnabled(locationManager)) {
            HLog.i(TAG, "Location services are disabled. Scans might fail on older Android versions.")
        }

        if (scanner == null || !bluetoothAdapter.isEnabled) {
            cont.resumeWith(Result.failure(BleException("Bluetooth is turned off.")))
            return@suspendCancellableCoroutine
        }

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val address = result.device.address
                val advertisedName = result.scanRecord?.deviceName ?: result.device.name

                if (seenAddresses.add(address)) {
                    HLog.d(TAG, "Scan saw BLE device $address ($advertisedName)")
                }

                if (advertisedName == deviceName) {
                    scanner.stopScan(this)
                    HLog.d(TAG, "Scan found $friendlyName device.")
                    if (cont.isActive) cont.resumeWith(Result.success(result.device))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                scanner.stopScan(this)
                if (cont.isActive) cont.resumeWith(Result.failure(BleException("Scan failed with code $errorCode")))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)

        cont.invokeOnCancellation {
            try { scanner.stopScan(scanCallback) } catch (e: Exception) {}
        }
    }

    private suspend fun connectGattSuspend(device: BluetoothDevice) {
        bluetoothGatt = if (Build.VERSION.SDK_INT >= 37) {
            // On API 37+ build the connection settings using the new Builder pattern
            val settings = BluetoothGattConnectionSettings.Builder()
                .setAutoConnectEnabled(false)
                .setTransport(BluetoothDevice.TRANSPORT_LE)
                .build()

            device.connectGatt(settings, context.mainExecutor, gattCallback)
        } else {
            // Fallback for older Android versions
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        if (bluetoothGatt == null) {
            throw BleException("Failed to initiate GATT connection.")
        }

        val event = awaitGattEvent<GattEvent.ConnectionStateChange> { it is GattEvent.ConnectionStateChange }
        if (event.newState != BluetoothProfile.STATE_CONNECTED) {
            throw BleException("Failed to connect. Status: ${event.status}")
        }
    }

    private suspend fun discoverServicesSuspend() {
        if (bluetoothGatt?.discoverServices() != true) {
            throw BleException("Failed to initiate service discovery.")
        }

        val event = awaitGattEvent<GattEvent.ServicesDiscovered> { it is GattEvent.ServicesDiscovered }
        if (event.status != BluetoothGatt.GATT_SUCCESS) {
            throw BleException("Service discovery failed. Status: ${event.status}")
        }
    }

    private fun getCharacteristic(serviceUuid: UUID, charUuid: UUID): BluetoothGattCharacteristic {
        val service = bluetoothGatt?.getService(serviceUuid)
            ?: throw BleException("Service $serviceUuid not found.")
        return service.getCharacteristic(charUuid)
            ?: throw BleException("Characteristic $charUuid not found.")
    }

    private fun closeGatt() {
        try {
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT", e)
        } finally {
            bluetoothGatt = null
            // Close and recreate the channel to prevent stale callbacks from bleeding into the next connection
            gattEvents.close()
            gattEvents = Channel(Channel.BUFFERED)
        }
    }

    // --- GATT CALLBACK ROUTER ---

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            gattEvents.trySend(GattEvent.ConnectionStateChange(status, newState))
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                updateState(ConnectionStatus.Disconnected)
                closeGatt()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            gattEvents.trySend(GattEvent.ServicesDiscovered(status))
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            gattEvents.trySend(GattEvent.CharacteristicRead(characteristic.uuid, status, characteristic.value ?: ByteArray(0)))
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            gattEvents.trySend(GattEvent.CharacteristicRead(characteristic.uuid, status, value))
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            gattEvents.trySend(GattEvent.CharacteristicWrite(characteristic.uuid, status))
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            gattEvents.trySend(GattEvent.DescriptorWrite(descriptor.uuid, status))
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: ByteArray(0)
            _notifications.tryEmit(BluetoothNotification(characteristic.service.uuid, characteristic.uuid, value))
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            _notifications.tryEmit(BluetoothNotification(characteristic.service.uuid, characteristic.uuid, value))
        }
    }

    // --- SUPPORTING MODELS ---

    private sealed class GattEvent {
        data class ConnectionStateChange(val status: Int, val newState: Int) : GattEvent()
        data class ServicesDiscovered(val status: Int) : GattEvent()
        data class CharacteristicRead(val uuid: UUID, val status: Int, val value: ByteArray) : GattEvent()
        data class CharacteristicWrite(val uuid: UUID, val status: Int) : GattEvent()
        data class DescriptorWrite(val uuid: UUID, val status: Int) : GattEvent()
    }
}

class BleException(message: String) : Exception(message)

data class BluetoothNotification(
    val serviceUuid: UUID,
    val characteristicUuid: UUID,
    val data: ByteArray
)