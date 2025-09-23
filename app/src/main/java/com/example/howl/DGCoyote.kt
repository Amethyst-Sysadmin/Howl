package com.example.howl

/*import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import java.lang.ref.WeakReference
import java.util.UUID
import android.Manifest
import android.bluetooth.le.BluetoothLeScanner
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.os.Build
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Simplified connection status for the main app
enum class ConnectionStatus {
    Disconnected,
    Connecting,
    Connected
}

// Internal state representing the exact stage we're at the connection process
enum class ConnectionStage {
    Disconnected,
    ScanForDevice,
    ConnectToDevice,
    ServiceDiscovery,
    RegisterForStatusUpdates,
    SyncParameters,
    Connected
}

@Suppress("DEPRECATION")
object DGCoyote {
    private const val TAG = "BLE"
    val POWER_RANGE: IntRange = 0..200
    val FREQUENCY_RANGE: IntRange = 1..200
    val INTERNAL_FREQUENCY_RANGE: IntRange = 5..240
    val INTENSITY_RANGE: IntRange = 0..100
    val FREQUENCY_BALANCE_RANGE: IntRange = 0..255
    val INTENSITY_BALANCE_RANGE: IntRange = 0..255
    //battery polling can fail if it's too close to other Bluetooth activity like sending pulses
    //the unusual interval helps to avoid this happening multiple times in a row
    private const val BATTERY_POLL_INTERVAL_SECS = 60.02
    private val coyoteScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var batteryPollJob: Job? = null

    data class Parameters (
        val channelALimit: Int = 70,
        val channelBLimit: Int = 70,
        val channelAFrequencyBalance: Int = 200,
        val channelBFrequencyBalance: Int = 200,
        val channelAIntensityBalance: Int = 0,
        val channelBIntensityBalance: Int = 0
    )

    private var contextRef: WeakReference<Context>? = null
    private var bluetoothDevice: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null
    private var services: List<BluetoothGattService> = emptyList()
    private var currentConnectionStage: ConnectionStage = ConnectionStage.Disconnected
    private var previousChannelAStrength = 0
    private var previousChannelBStrength = 0
    private var initialParameters: Parameters = Parameters()
    private var onConnectionStatusUpdate: ((status: ConnectionStatus) -> Unit)? = null
    private var onBatteryLevelUpdate: ((batteryPercent: Int) -> Unit)? = null
    private var onPowerLevelUpdate: ((channel: Int, power: Int) -> Unit)? = null

    private const val DEVICE_NAME = "47L121000"
    private val batteryServiceUUID = UUID.fromString("0000180A-0000-1000-8000-00805f9b34fb")
    private val mainServiceUUID = UUID.fromString("0000180C-0000-1000-8000-00805f9b34fb")
    private val writeCharacteristicUUID = UUID.fromString("0000150A-0000-1000-8000-00805f9b34fb")
    private val notifyCharacteristicUUID = UUID.fromString("0000150B-0000-1000-8000-00805f9b34fb")
    private val batteryCharacteristicUUID = UUID.fromString("00001500-0000-1000-8000-00805f9b34fb")
    private val clientConfigDescriptor = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val PERMISSION_BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
    const val PERMISSION_BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
    //const val PERMISSION_BLUETOOTH_SCAN = Manifest.permission.BLUETOOTH_SCAN
    //const val PERMISSION_BLUETOOTH_CONNECT = Manifest.permission.BLUETOOTH_CONNECT

    val ALL_BLE_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )
    }
    else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    @RequiresPermission(PERMISSION_BLUETOOTH_CONNECT)
    @SuppressLint("MissingPermission")
    private fun runConnectionProcess() {
        HLog.d(TAG, "runConnectionProcess - state ${currentConnectionStage.name}")
        when(currentConnectionStage) {
            ConnectionStage.Disconnected -> {
                onConnectionStatusUpdate?.invoke(ConnectionStatus.Connecting)
                currentConnectionStage = ConnectionStage.ScanForDevice
                scanForDevice()
            }
            ConnectionStage.ScanForDevice -> {
                //generally not called
                //we already handle starting the scan above when leaving the disconnected state
            }
            ConnectionStage.ConnectToDevice -> {
                gatt = bluetoothDevice?.connectGatt(contextRef?.get(), false, callback, BluetoothDevice.TRANSPORT_LE)
            }
            ConnectionStage.ServiceDiscovery -> {
                gatt?.discoverServices()
            }
            ConnectionStage.RegisterForStatusUpdates -> {
                statusSubscribe()
            }
            ConnectionStage.SyncParameters -> {
                sendParameters(initialParameters)
            }
            ConnectionStage.Connected -> {
                startBatteryPolling()
                onConnectionStatusUpdate?.invoke(ConnectionStatus.Connected)
            }
        }
    }

    /*//useful for debugging
    private fun BluetoothGatt.printGattTable() {
        if (services.isEmpty()) {
            Log.i("printGattTable", "No service and characteristic available, call discoverServices() first?")
            return
        }
        services.forEach { service ->
            val characteristicsTable = service.characteristics.joinToString(
                separator = "\n|--",
                prefix = "|--"
            ) { it.uuid.toString() }
            Log.i("printGattTable", "\nService ${service.uuid}\nCharacteristics:\n$characteristicsTable"
            )
        }
    }*/

    private fun ByteArray.toHexString() = joinToString("") { String.format("%02X", it) }

    private fun frequencyToStupidCoyoteValue(frequency: Float): Int {
        //Convert a frequency in Hz to the weird 5-240 range the Coyote uses
        //Round as late as possible, since granularity is already extremely poor for higher frequencies
        val period = 1000.0 / frequency
        var calculated = 0.0
        calculated = when (period) {
            in 5.0..100.0 -> {
                period
            }
            in 100.0..600.0 -> {
                (period - 100) / 5.0 + 100
            }
            in 600.0..1000.0 -> {
                (period - 600) / 10.0 + 200
            }
            else -> {
                10.0
            }
        }
        return(calculated.roundToInt().coerceIn(INTERNAL_FREQUENCY_RANGE))
    }

    private fun pulseDataToByteArray(
        minFrequency: Float,
        maxFrequency: Float,
        pulseData: List<Pulse>
    ): ByteArray {
        val frequencyRange = maxFrequency - minFrequency
        //convert our internal frequencies (0 to 1) to a value in Hz and then the nearest Coyote value
        val channelAConvertedFrequencies = pulseData.map {
            frequencyToStupidCoyoteValue(minFrequency + frequencyRange * it.freqA).toByte() }.toByteArray()
        val channelBConvertedFrequencies = pulseData.map {
            frequencyToStupidCoyoteValue(minFrequency + frequencyRange * it.freqB).toByte() }.toByteArray()
        //convert our internal amplitudes (0 to 1) to the Coyote's range
        val channelAConvertedIntensities = pulseData.map { (it.ampA * 100).roundToInt().coerceIn(INTENSITY_RANGE).toByte() }.toByteArray()
        val channelBConvertedIntensities = pulseData.map { (it.ampB * 100).roundToInt().coerceIn(INTENSITY_RANGE).toByte() }.toByteArray()

        return channelAConvertedFrequencies + channelAConvertedIntensities + channelBConvertedFrequencies + channelBConvertedIntensities
    }

    @SuppressLint("MissingPermission")
    fun sendPulse(channelAStrength: Int,
                  channelBStrength: Int,
                  minFrequency: Float,
                  maxFrequency: Float,
                  pulseData: List<Pulse>
    ) {
        val expectedParameters = 4
        var strengthByte = 0x00.toByte()
        if(pulseData.size != expectedParameters) {
            HLog.d(TAG, "Incorrect number of parameters in call to sendPulse")
            return
        }
        if(channelAStrength != previousChannelAStrength || channelBStrength != previousChannelBStrength) {
            //signal that we will also be updating the channel strength
            //Log.d("DGCoyote", "Sending strength update ${channelAStrength} ${previousChannelAStrength} ${channelBStrength} ${previousChannelBStrength}")
            strengthByte = 0x1F.toByte()
        }

        val command = byteArrayOf(
            0xB0.toByte(), // command header
            strengthByte, // sequence number + strength interpretation method
            channelAStrength.toByte(), // channel A updated strength setting
            channelBStrength.toByte(), // channel B updated strength setting
        )

        val completeCommand = command + pulseDataToByteArray(minFrequency, maxFrequency, pulseData)
        //Log.v("DGCoyote", "Sending pulse command, data: ${completeCommand.toHexString()}")
        sendCommand(completeCommand)
    }

    @SuppressLint("MissingPermission")
    fun sendParameters(parameters: Parameters) {
        sendBFCommand(parameters)
    }

    @SuppressLint("MissingPermission")
    fun pollBatteryLevel() {
        HLog.d(TAG, "Polling battery level")
        val service = gatt?.getService(batteryServiceUUID)
        val characteristic = service?.getCharacteristic(batteryCharacteristicUUID)
        if (characteristic != null) {
            val success = gatt?.readCharacteristic(characteristic)
            //Log.v("bluetooth", "pollBatteryLevel read status: $success")
        }
    }

    private fun startBatteryPolling() {
        batteryPollJob?.cancel() // Cancel existing job if any
        batteryPollJob = coyoteScope.launch {
            // Initial poll immediately
            pollBatteryLevel()
            // Subsequent polls at intervals
            while (isActive) {
                delay((BATTERY_POLL_INTERVAL_SECS * 1000).toLong())
                pollBatteryLevel()
            }
        }
    }

    @RequiresPermission(PERMISSION_BLUETOOTH_CONNECT)
    private fun sendCommand(payload: ByteArray) {
        val writeService = gatt?.getService(mainServiceUUID)
        val writeCharacteristic = writeService?.getCharacteristic(writeCharacteristicUUID)
        if (writeCharacteristic != null) {
            writeCharacteristic.value = payload
            gatt?.writeCharacteristic(writeCharacteristic)
        }
    }

    @RequiresPermission(PERMISSION_BLUETOOTH_CONNECT)
    private fun sendBFCommand(parameters: Parameters) {
        val command = byteArrayOf(
            0xBF.toByte(),
            parameters.channelALimit.toByte(),
            parameters.channelBLimit.toByte(),
            parameters.channelAFrequencyBalance.toByte(),
            parameters.channelBFrequencyBalance.toByte(),
            parameters.channelAIntensityBalance.toByte(),
            parameters.channelBIntensityBalance.toByte()
        )
        //Log.v("DGCoyote", "Sending BF command, data: ${command.toHexString()}")
        sendCommand(command)
    }

    @RequiresPermission(PERMISSION_BLUETOOTH_CONNECT)
    private fun characteristicSubscribe(bgc: BluetoothGattCharacteristic) {
        gatt?.setCharacteristicNotification(bgc, true)
        val desc = bgc.getDescriptor(clientConfigDescriptor)
        desc?.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        gatt?.writeDescriptor(desc)
    }

    @RequiresPermission(PERMISSION_BLUETOOTH_CONNECT)
    private fun statusSubscribe() {
        val statusService = gatt?.getService(mainServiceUUID)
        val statusCharacteristic = statusService?.getCharacteristic(notifyCharacteristicUUID)
        if (statusCharacteristic != null) {
            characteristicSubscribe(statusCharacteristic)
        }
    }

    private val callback = object: BluetoothGattCallback() {
        @RequiresPermission(PERMISSION_BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            if(status != BluetoothGatt.GATT_SUCCESS) {
                HLog.d(TAG, "onConnectionStateChange error, will disconnect. Status:$status newState:$newState")
                disconnect()
                return
            }

            if (newState == BluetoothGatt.STATE_CONNECTED) {
                HLog.d(TAG, "Bluetooth device connected")
                currentConnectionStage = ConnectionStage.ServiceDiscovery
                runConnectionProcess()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                HLog.d(TAG, "Bluetooth device disconnected")
                disconnect()
            }
        }

        @RequiresPermission(PERMISSION_BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if(status != BluetoothGatt.GATT_SUCCESS) {
                HLog.d(TAG, "onServicesDiscovered error, will disconnect. Status:$status")
                disconnect()
                return
            }
            HLog.d(TAG, "Bluetooth services discovered")
            services = gatt.services
            //gatt.printGattTable()
            currentConnectionStage = ConnectionStage.RegisterForStatusUpdates
            runConnectionProcess()
        }

        @RequiresPermission(PERMISSION_BLUETOOTH_CONNECT)
        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (descriptor.characteristic.uuid == notifyCharacteristicUUID) {
                    //successfully registered for status updates
                    currentConnectionStage = ConnectionStage.SyncParameters
                    runConnectionProcess()
                }
                else {
                    HLog.d(
                        TAG,
                        "Descriptor ${descriptor.uuid} of characteristic ${descriptor.characteristic.uuid}: write success"
                    )
                }

            }
            else {
                HLog.d(TAG, "Descriptor ${descriptor.uuid} of characteristic ${descriptor.characteristic.uuid}: write fail (status=$status)")
            }
        }

        @RequiresPermission(PERMISSION_BLUETOOTH_CONNECT)
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (characteristic.uuid == writeCharacteristicUUID)
                {
                    /*val data = characteristic.value
                    if (data != null && data.size > 0) {
                        Log.v("DGCoyote", "Characteristic write successful ${characteristic.uuid} data: ${characteristic.value.toHexString()}")
                    }*/
                    if (currentConnectionStage == ConnectionStage.SyncParameters)
                    {
                        //assume the success message is for our sync
                        currentConnectionStage = ConnectionStage.Connected
                        runConnectionProcess()
                    }
                }
            }
            else {
                HLog.d(TAG, "Characteristic write failed for ${characteristic.uuid}, error: $status")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
            if (characteristic.uuid == batteryCharacteristicUUID) {
                //Log.v("DGCoyote", "Hex data for battery level ${characteristic.value.toHexString()}")
                val batteryLevel = characteristic.value.first().toInt()
                HLog.d(TAG, "Fetched Coyote battery level: $batteryLevel%")
                onBatteryLevelUpdate?.invoke(batteryLevel)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
            if (characteristic.uuid == notifyCharacteristicUUID)
            {
                if (characteristic.value.isEmpty())
                    return
                //Log.v("DGCoyote", "Notify characteristic update ${characteristic.value.toHexString()}")
                when(characteristic.value[0]) {
                    0xB1.toByte() -> {
                        val strengthByte = characteristic.value[1].toByte()
                        val powerA = characteristic.value[2].toInt()
                        val powerB = characteristic.value[3].toInt()
                        //if strengthByte == 0x1F also update previous values
                        previousChannelAStrength = powerA
                        previousChannelBStrength = powerB
                        if (strengthByte == 0x00.toByte()) {
                            /*Log.v(
                                "DGCoyote",
                                "Received new power levels from device: A: $powerA B: $powerB"
                            )*/
                            onPowerLevelUpdate?.invoke(0, powerA)
                            onPowerLevelUpdate?.invoke(1, powerB)
                        }
                    }
                }
            }
            else {
                HLog.d(TAG, "Unexpected Bluetooth characteristic change ${characteristic.uuid}")
                HLog.d(TAG, "Hex data for unexpected characteristic ${characteristic.value.toHexString()}")
            }
        }
    }

    fun initialise(context: Context,
                   onConnectionStatusUpdate: ((status: ConnectionStatus) -> Unit)?,
                   onBatteryLevelUpdate: ((batteryPercent: Int) -> Unit)?,
                   onPowerLevelUpdate: ((channel: Int, power: Int) -> Unit)?
    ) {
        this.contextRef = WeakReference(context)
        this.onConnectionStatusUpdate = onConnectionStatusUpdate
        this.onBatteryLevelUpdate = onBatteryLevelUpdate
        this.onPowerLevelUpdate = onPowerLevelUpdate
    }

    fun checkAndRequestPermissions(): Boolean {
        val context = contextRef?.get() ?: return false
        val permissionsToRequest = ALL_BLE_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            HLog.d(TAG, "Requesting Bluetooth permissions $permissionsToRequest")
            val activity = context as? MainActivity ?: return false
            ActivityCompat.requestPermissions(activity, permissionsToRequest.toTypedArray(), 1)
            return false
        }

        // All permissions are granted
        HLog.d(TAG, "Required permissions are already granted")
        return true
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(PERMISSION_BLUETOOTH_SCAN)
    fun scanForDevice() {
        val context = contextRef?.get() ?: return
        val timeoutHandler = Handler(Looper.getMainLooper())

        val bluetooth = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (bluetooth == null || bluetooth.adapter == null) {
            HLog.d(TAG, "Bluetooth is not supported by this device.")
            disconnect()
            return
        }

        if(!bluetooth.adapter.isEnabled) {
            HLog.d(TAG, "The Bluetooth adapter is currently disabled (do you have Bluetooth turned on?)")
            disconnect()
            return
        }

        val scanner: BluetoothLeScanner = bluetooth.adapter.bluetoothLeScanner

        // Create a ScanFilter to filter by device name
        val scanFilter = ScanFilter.Builder()
            .setDeviceName(DEVICE_NAME)
            .build()

        // Create a ScanSettings object
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        // Define the ScanCallback to handle scan results
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult?) {
                result?.let {
                    bluetoothDevice = it.device
                    HLog.d(TAG, "Found device: ${bluetoothDevice?.name} - ${bluetoothDevice?.address}")
                    timeoutHandler.removeCallbacksAndMessages(null)
                    scanner.stopScan(this)
                    currentConnectionStage = ConnectionStage.ConnectToDevice
                    runConnectionProcess()
                }
            }

            override fun onScanFailed(errorCode: Int) {
                HLog.d(TAG, "Scan failed with error code: $errorCode")
                disconnect()
            }
        }

        val timeout = object : Runnable {
            override fun run () {
                HLog.d(TAG, "Scan timed out, no device found")
                scanner.stopScan(scanCallback)
                disconnect()
            }
        }

        // Start the BLE scan
        HLog.d(TAG, "Starting BLE scan")
        scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)

        // Set a handler to stop the scan after 10 seconds
        timeoutHandler.postDelayed(timeout, 10000)
    }

    @RequiresPermission(PERMISSION_BLUETOOTH_CONNECT)
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        bluetoothDevice = null
        previousChannelAStrength = 0
        previousChannelBStrength = 0
        batteryPollJob?.cancel()
        batteryPollJob = null
        currentConnectionStage = ConnectionStage.Disconnected
        onConnectionStatusUpdate?.invoke(ConnectionStatus.Disconnected)
    }

    @SuppressLint("MissingPermission") //we explicitly check for permissions
    fun connect(parameters: Parameters) {
        if (currentConnectionStage != ConnectionStage.Disconnected)
            return
        if (!checkAndRequestPermissions()) return
        initialParameters = parameters.copy()
        runConnectionProcess()
    }
}
*/