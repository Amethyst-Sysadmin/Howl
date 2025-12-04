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
import kotlin.math.sqrt

// Internal state representing the stage we're at in our initial setup process
enum class Coyote2SetupStage {
    Initial,
    RegisterForPowerUpdates,
    Ready
}

class Coyote2Output : Output {
    override val timerDelay = 0.1
    override val pulseBatchSize = 1
    override val sendSilenceWhenMuted = false
    override var allowedFrequencyRange = 1..200
    override var defaultFrequencyRange = 10..100
    override var ready = false
    override var latency = 0.0
    var setupStage: Coyote2SetupStage = Coyote2SetupStage.Initial
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var batteryPollJob: Job? = null
    private var previousChannelAPower = -1
    private var previousChannelBPower = -1

    companion object {
        const val TAG = "Coyote2Output"
        const val DELAY_BETWEEN_OPS = 10L // delay needed between Bluetooth operations in milliseconds
        val POWER_RANGE: IntRange = 0..200
        //battery polling can fail if it's too close to other Bluetooth activity like sending pulses
        //the unusual interval helps to avoid this happening multiple times in a row
        const val BATTERY_POLL_INTERVAL_SECS = 60.02
        val batteryServiceUUID: UUID = UUID.fromString("955A180A-0FE2-F5AA-A094-84B8D4F3E8AD")
        val mainServiceUUID: UUID = UUID.fromString("955A180B-0FE2-F5AA-A094-84B8D4F3E8AD")
        val batteryCharacteristicUUID: UUID = UUID.fromString("955A1500-0FE2-F5AA-A094-84B8D4F3E8AD")
        val powerCharacteristicUUID: UUID = UUID.fromString("955A1504-0FE2-F5AA-A094-84B8D4F3E8AD")
        val patternACharacteristicUUID: UUID = UUID.fromString("955A1506-0FE2-F5AA-A094-84B8D4F3E8AD")
        val patternBCharacteristicUUID: UUID = UUID.fromString("955A1505-0FE2-F5AA-A094-84B8D4F3E8AD")
    }

    override fun initialise() {}
    override fun end() {
        batteryPollJob?.cancel()
    }

    override fun handleBluetoothEvent(event: BluetoothEvent) {
        when (event.type) {
            BluetoothEventType.Connected -> {
                HLog.d(TAG, "Received connected event")
                setupStage = Coyote2SetupStage.RegisterForPowerUpdates
                BluetoothHandler.subscribeToCharacteristic(mainServiceUUID, powerCharacteristicUUID)
            }
            BluetoothEventType.Disconnected -> {
                HLog.d(TAG, "Received disconnected event")
                ready = false
                setupStage = Coyote2SetupStage.Initial
                batteryPollJob?.cancel()
                batteryPollJob = null
            }
            BluetoothEventType.CharacteristicRead-> {
                // Battery level message
                if (event.serviceUuid == batteryServiceUUID && event.characteristicUuid == batteryCharacteristicUUID) {
                    val batteryLevel = event.data?.first()?.toInt() ?: return
                    HLog.d(TAG, "Fetched Coyote 2 battery level: $batteryLevel%")
                    DataRepository.setCoyoteBatteryLevel(batteryLevel)
                }
            }
            BluetoothEventType.CharacteristicChanged -> {
                // Power characteristic update
                if(event.serviceUuid == mainServiceUUID && event.characteristicUuid == powerCharacteristicUUID) {
                    val data = event.data ?: return
                    handlePowerUpdate(data)
                }
            }
            BluetoothEventType.CharacteristicWrite -> {}
            BluetoothEventType.DescriptorWrite -> {
                // Response to our subscription request
                if (event.serviceUuid == mainServiceUUID && event.characteristicUuid == powerCharacteristicUUID) {
                    if (event.success != true) {
                        BluetoothHandler.disconnect()
                        return
                    }
                    HLog.d(TAG, "Successfully subscribed for power events")
                    setupStage = Coyote2SetupStage.Ready
                    startBatteryPolling()
                    ready = true
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

        val powerChanged = channelAPower != previousChannelAPower || channelBPower != previousChannelBPower
        val frequencyRange = maxFrequency - minFrequency
        val pulse = pulses[0]
        val (xA, yA) = frequencyHzToXY(minFrequency + frequencyRange * pulse.freqA)
        val (xB, yB) = frequencyHzToXY(minFrequency + frequencyRange * pulse.freqB)
        val zA = amplitudeToZ(pulse.ampA)
        val zB = amplitudeToZ(pulse.ampB)
        val waveformA = packWaveformData(xA, yA, zA)
        val waveformB = packWaveformData(xB, yB, zB)

        //Log.d(TAG, "Waveform parameters A($xA,$yA,$zA) B($xB,$yB,$zB)")

        scope.launch {
            if (powerChanged) {
                sendPowerLevels(channelAPower, channelBPower)
                delay(DELAY_BETWEEN_OPS)
            }
            sendWaveform(0, waveformA)
            delay(DELAY_BETWEEN_OPS)
            sendWaveform(1, waveformB)
        }
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

    private fun packWaveformData(x: Int, y: Int, z: Int): ByteArray {
        // Clamp values to their valid ranges
        val xClamped = x.coerceIn(0, 31)    // 5 bits → 0..31
        val yClamped = y.coerceIn(0, 1023)  // 10 bits → 0..1023
        val zClamped = z.coerceIn(0, 31)    // 5 bits → 0..31

        // Pack the fields: Z in bits 19..15, Y in bits 14..5, X in bits 4..0
        val packed = (zClamped shl 15) or (yClamped shl 5) or xClamped

        // Convert to little-endian 3 byte array
        return byteArrayOf(
            (packed and 0xFF).toByte(),         // LSB
            ((packed shr 8) and 0xFF).toByte(), // Middle
            ((packed shr 16) and 0xFF).toByte() // MSB
        )
    }

    private fun sendWaveform(channel: Int, waveform: ByteArray) {
        val characteristic = if (channel == 0) patternACharacteristicUUID else patternBCharacteristicUUID
        BluetoothHandler.sendToDevice(mainServiceUUID, characteristic, waveform)
    }

    private fun sendPowerLevels(channelAPower: Int, channelBPower: Int) {
        /*  The Coyote 2's power on each channel has a range of 0-2047
            We instead use the app power level * 7 (for a max of 1400 at power level 200) as this
            makes the power steps consistent with the DG app and with how the hardware switches on
            the device work, which use steps of 7. */
        val devicePowerA = (channelAPower * 7).coerceIn(0, 2047)
        val devicePowerB = (channelBPower * 7).coerceIn(0, 2047)
        //HLog.d(TAG, "Sending new power levels $channelAPower $channelBPower [$devicePowerA $devicePowerB]")

        // Pack the fields: A in bits 21..11, B in bits 10..0
        val packed = (devicePowerA shl 11) or devicePowerB

        // Convert to little-endian 3 byte array
        val command = byteArrayOf(
            (packed and 0xFF).toByte(),          // LSB
            ((packed shr 8) and 0xFF).toByte(),  // Middle
            ((packed shr 16) and 0xFF).toByte()  // MSB
        )

        BluetoothHandler.sendToDevice(mainServiceUUID, powerCharacteristicUUID, command)
    }

    private fun handlePowerUpdate(data: ByteArray) {
        if (data.size != 3) return

        //val commandHex = data.toHexString()

        // Reconstruct the packed 21-bit integer from little-endian bytes
        val packed = (data[0].toInt() and 0xFF) or
                ((data[1].toInt() and 0xFF) shl 8) or
                ((data[2].toInt() and 0xFF) shl 16)

        // Extract device power levels
        val devicePowerA = (packed shr 11) and 0x7FF // upper 11 bits
        val devicePowerB = packed and 0x7FF          // lower 11 bits

        val channelAPower = devicePowerA / 7
        val channelBPower = devicePowerB / 7

        //HLog.d(TAG, "Received power level update $channelAPower $channelBPower [$devicePowerA $devicePowerB]")
        //HLog.d(TAG, "Power level update hex: $commandHex")

        previousChannelAPower = channelAPower
        previousChannelBPower = channelBPower

        if (DataRepository.mainOptionsState.value.channelAPower != channelAPower || DataRepository.mainOptionsState.value.channelBPower != channelBPower) {
            HLog.d(TAG,"Power level update from device A: $channelAPower B: $channelBPower")
            DataRepository.setChannelAPower(channelAPower)
            DataRepository.setChannelBPower(channelBPower)
        }
    }

    private fun frequencyHzToXY(frequencyHz: Double): Pair<Int, Int> {
        /* Converts a frequency in Hz to X and Y parameters suitable for the Coyote 2
            X is how many milliseconds in a row to send pulses (0-31) [feels like a single pulse]
            Y is the gap in milliseconds between each batch of X pulses (0-1023)
            The effective pulse frequency in Hz is 1000 / (X + Y)
            e.g. X=1, Y=9 would be 100Hz

            The examples in the DG Labs docs suggest we should use higher X at lower frequencies.
            How to actually distribute the time between X and Y seems to involve some black magic.
            This method is loosely based on their formula (modified to work in Hz for our purposes)
        */

        //The rounding method makes a big difference as it determines whether 55-100Hz use 2 pulses
        //or 1. Testers preferred the roundToInt (2 pulse) version.
        //val x = (sqrt(1.0/frequencyHz) * 15.0).toInt().coerceIn(1..31)
        val x = (sqrt(1.0/frequencyHz) * 15.0).roundToInt().coerceIn(1..31)
        val y = ((1000.0/frequencyHz).roundToInt() - x).coerceIn(0..1023)
        return Pair(x, y)
    }

    private fun amplitudeToZ(amplitude: Float): Int {
        // Converts our internal amplitudes (0.0-1.0) to the Coyote 2's Z parameter
        // The Z parameter controls pulse width, with the actual width being Z * 5 microseconds
        // Range of Z is 0-31, but the docs say >20 is more likely to cause a stinging sensation
        // which testers agreed with
        val maxZ = 20
        val z = (maxZ * amplitude).roundToInt().coerceIn(0..maxZ)
        return z
    }
}
