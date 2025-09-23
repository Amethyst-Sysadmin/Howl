package com.example.howl

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt

// Internal state representing the stage we're at in our initial setup process
enum class CoyoteSetupStage {
    Initial,
    RegisterForStatusUpdates,
    SyncParameters,
    Ready
}

data class Coyote3Parameters (
    val channelALimit: Int = 70,
    val channelBLimit: Int = 70,
    val channelAFrequencyBalance: Int = 200,
    val channelBFrequencyBalance: Int = 200,
    val channelAIntensityBalance: Int = 0,
    val channelBIntensityBalance: Int = 0
)

class Coyote3Output : Output {
    override val timerDelay = 0.1
    override val pulseBatchSize = 4
    override val sendSilenceWhenMuted = false
    override var allowedFrequencyRange = 1..200
    override var defaultFrequencyRange = 10..100
    override var ready = false
    override var latency = 0.0
    var setupStage: CoyoteSetupStage = CoyoteSetupStage.Initial
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var batteryPollJob: Job? = null
    private var previousChannelAPower = -1
    private var previousChannelBPower = -1

    companion object {
        const val TAG = "Coyote3Output"
        val POWER_RANGE: IntRange = 0..200
        val FREQUENCY_BALANCE_RANGE: IntRange = 0..255
        val INTENSITY_BALANCE_RANGE: IntRange = 0..255
        val DEVICE_FREQUENCY_RANGE: IntRange = 5..240
        val DEVICE_AMPLITUDE_RANGE: IntRange = 0..100
        //battery polling can fail if it's too close to other Bluetooth activity like sending pulses
        //the unusual interval helps to avoid this happening multiple times in a row
        const val BATTERY_POLL_INTERVAL_SECS = 60.02
        val batteryServiceUUID: UUID = UUID.fromString("0000180A-0000-1000-8000-00805f9b34fb")
        val mainServiceUUID: UUID = UUID.fromString("0000180C-0000-1000-8000-00805f9b34fb")
        val writeCharacteristicUUID: UUID = UUID.fromString("0000150A-0000-1000-8000-00805f9b34fb")
        val notifyCharacteristicUUID: UUID = UUID.fromString("0000150B-0000-1000-8000-00805f9b34fb")
        val batteryCharacteristicUUID: UUID = UUID.fromString("00001500-0000-1000-8000-00805f9b34fb")
    }

    override fun initialise() {}
    override fun end() {
        batteryPollJob?.cancel()
    }

    override fun handleBluetoothEvent(event: BluetoothEvent) {
        when (event.type) {
            BluetoothEventType.Connected -> {
                setupStage = CoyoteSetupStage.RegisterForStatusUpdates
                BluetoothHandler.subscribeToCharacteristic(mainServiceUUID, notifyCharacteristicUUID)
            }
            BluetoothEventType.Disconnected -> {
                ready = false
                setupStage = CoyoteSetupStage.Initial
                batteryPollJob?.cancel()
                batteryPollJob = null
            }
            BluetoothEventType.CharacteristicRead-> {
                // Battery level message
                if (event.serviceUuid == batteryServiceUUID && event.characteristicUuid == batteryCharacteristicUUID) {
                    val batteryLevel = event.data?.first()?.toInt() ?: return
                    HLog.d(TAG, "Fetched Coyote battery level: $batteryLevel%")
                    DataRepository.setCoyoteBatteryLevel(batteryLevel)
                }
            }
            BluetoothEventType.CharacteristicChanged -> {
                // Notify characteristic update
                if(event.serviceUuid == mainServiceUUID && event.characteristicUuid == notifyCharacteristicUUID) {
                    val data = event.data ?: return
                    when(data[0]) {
                        0xB1.toByte() -> {
                            val strengthByte = data[1]
                            val powerA = data[2].toInt()
                            val powerB = data[3].toInt()
                            //if strengthByte == 0x1F also update previous values
                            previousChannelAPower = powerA
                            previousChannelBPower = powerB
                            if (strengthByte == 0x00.toByte()) {
                                Log.v(TAG,"Received new power levels from device: A: $powerA B: $powerB")
                                DataRepository.setChannelAPower(powerA)
                                DataRepository.setChannelBPower(powerB)
                            }
                        }
                    }
                }
            }
            BluetoothEventType.CharacteristicWrite -> {
                if (event.serviceUuid == mainServiceUUID && event.characteristicUuid == writeCharacteristicUUID) {
                    if (setupStage == CoyoteSetupStage.SyncParameters) {
                        // assume the response is for our sync
                        if (event.success != true) {
                            BluetoothHandler.disconnect()
                            return
                        }
                        setupStage = CoyoteSetupStage.Ready
                        startBatteryPolling()
                        ready = true
                    }
                }
            }
            BluetoothEventType.DescriptorWrite -> {
                // Response to our subscription request
                if (event.serviceUuid == mainServiceUUID && event.characteristicUuid == notifyCharacteristicUUID) {
                    if (event.success != true) {
                        BluetoothHandler.disconnect()
                        return
                    }
                    setupStage = CoyoteSetupStage.SyncParameters
                    sendParameters(DataRepository.coyoteParametersState.value)
                }
            }
            BluetoothEventType.Error -> {
                HLog.d(TAG, "Bluetooth error received service:${event.serviceUuid} characteristic:${event.characteristicUuid} message:${event.errorMessage}")
            }
        }
    }

    override fun start() {}
    override fun stop() {}

    override fun sendPulses(
        channelAPower: Int,
        channelBPower: Int,
        minFrequency: Double,
        maxFrequency: Double,
        pulses: List<Pulse>
    ) {
        if (pulses.size != pulseBatchSize) {
            HLog.d(TAG, "Incorrect number of pulses received by sendPulses")
            return
        }
        if (channelAPower !in POWER_RANGE || channelBPower !in POWER_RANGE) {
            HLog.d(TAG, "!! Invalid power value passed to sendPulses !!")
            return
        }
        var strengthByte = 0x00.toByte()
        if(channelAPower != previousChannelAPower || channelBPower != previousChannelBPower) {
            strengthByte = 0x1F.toByte()
        }

        val command = byteArrayOf(
            0xB0.toByte(), // command header
            strengthByte, // sequence number + strength interpretation method
            channelAPower.toByte(),
            channelBPower.toByte(),
        ) + pulsesToByteArray(minFrequency, maxFrequency, pulses)

        BluetoothHandler.sendToDevice(mainServiceUUID, writeCharacteristicUUID, command)
    }

    fun sendParameters(parameters: Coyote3Parameters) {
        val command = byteArrayOf(
            0xBF.toByte(),
            parameters.channelALimit.toByte(),
            parameters.channelBLimit.toByte(),
            parameters.channelAFrequencyBalance.toByte(),
            parameters.channelBFrequencyBalance.toByte(),
            parameters.channelAIntensityBalance.toByte(),
            parameters.channelBIntensityBalance.toByte()
        )
        Log.v(TAG, "Sending BF command, data: ${command.toHexString()}")
        BluetoothHandler.sendToDevice(mainServiceUUID, writeCharacteristicUUID, command)
    }

    private fun pollBatteryLevel() {
        HLog.d(TAG, "Polling battery level")
        BluetoothHandler.pollCharacteristic(batteryServiceUUID, batteryCharacteristicUUID)
    }

    private fun startBatteryPolling() {
        batteryPollJob?.cancel() // Cancel existing job if any
        batteryPollJob = scope.launch {
            pollBatteryLevel() // Do initial poll immediately
            while (isActive) {
                delay((BATTERY_POLL_INTERVAL_SECS * 1000).toLong())
                pollBatteryLevel()
            }
        }
    }

    private fun frequencyHzToCoyote(frequency: Double): Int {
        //Convert a frequency in Hz to the weird 5-240 range the Coyote uses
        //Round as late as possible, since granularity is already extremely poor for higher frequencies
        val period = 1000.0 / frequency
        val coyoteFreq = when (period) {
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
        return(coyoteFreq.roundToInt().coerceIn(DEVICE_FREQUENCY_RANGE))
    }

    private fun pulsesToByteArray(
        minFrequency: Double,
        maxFrequency: Double,
        pulses: List<Pulse>
    ): ByteArray {
        val frequencyRange = maxFrequency - minFrequency
        //convert our internal frequencies (0 to 1) to a value in Hz and then the nearest Coyote value
        val channelAConvertedFrequencies = pulses.map {
            frequencyHzToCoyote(minFrequency + frequencyRange * it.freqA).toByte() }.toByteArray()
        val channelBConvertedFrequencies = pulses.map {
            frequencyHzToCoyote(minFrequency + frequencyRange * it.freqB).toByte() }.toByteArray()
        //convert our internal amplitudes (0 to 1) to the Coyote's range
        val channelAConvertedIntensities = pulses.map { (it.ampA * 100).roundToInt().coerceIn(DEVICE_AMPLITUDE_RANGE).toByte() }.toByteArray()
        val channelBConvertedIntensities = pulses.map { (it.ampB * 100).roundToInt().coerceIn(DEVICE_AMPLITUDE_RANGE).toByte() }.toByteArray()

        return channelAConvertedFrequencies + channelAConvertedIntensities + channelBConvertedFrequencies + channelBConvertedIntensities
    }
}
