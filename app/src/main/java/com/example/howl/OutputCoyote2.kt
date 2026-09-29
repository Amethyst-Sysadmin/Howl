package com.example.howl

import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.seconds

class Coyote2Output : BluetoothOutput("D-LAB ESTIM01") {
    override val type = OutputType.COYOTE2
    override val pulseDivider = 2
    override val pulseBatchSize = 1
    override val sendSilenceWhenMuted = false
    override var minFrequencyLimit: Int = 1
    override var maxFrequencyLimit: Int = 200
    override val defaultFrequencySubset: ClosedFloatingPointRange<Float>
        get() = 0.05f..0.5f
    override var ready = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var setupJob: Job? = null
    private var batteryPollJob: Job? = null
    private var notificationJob: Job? = null

    private var previousChannelAPower = -1
    private var previousChannelBPower = -1
    private var sendChannelA = true  // alternates with each pulse

    companion object {
        const val TAG = "Coyote2Output"

        // Battery polling can fail if it's too close to other Bluetooth activity like sending pulses.
        // The unusual interval helps to avoid this happening multiple times in a row.
        val BATTERY_POLL_INTERVAL = 60.02.seconds

        private val batteryServiceUUID: UUID = UUID.fromString("955A180A-0FE2-F5AA-A094-84B8D4F3E8AD")
        private val mainServiceUUID: UUID = UUID.fromString("955A180B-0FE2-F5AA-A094-84B8D4F3E8AD")
        private val batteryCharacteristicUUID: UUID = UUID.fromString("955A1500-0FE2-F5AA-A094-84B8D4F3E8AD")
        private val powerCharacteristicUUID: UUID = UUID.fromString("955A1504-0FE2-F5AA-A094-84B8D4F3E8AD")
        private val patternACharacteristicUUID: UUID = UUID.fromString("955A1506-0FE2-F5AA-A094-84B8D4F3E8AD")
        private val patternBCharacteristicUUID: UUID = UUID.fromString("955A1505-0FE2-F5AA-A094-84B8D4F3E8AD")
    }

    override fun initialise() {
        // Observe connection state to automatically trigger setup/teardown
        setupJob = scope.launch {
            handler.connectionState.collect { state ->
                when (state) {
                    ConnectionStatus.Connected -> startSetup()
                    ConnectionStatus.Disconnected -> cleanup()
                    else -> { /* Ignore Scanning, Connecting, Disconnecting */ }
                }
            }
        }
    }

    override fun destroy() {
        setupJob?.cancel()
        cleanup()
        scope.cancel()
    }

    private suspend fun startSetup() {
        HLog.d(TAG, "Starting Coyote 2 setup...")

        // 1. Subscribe to power updates (Retries up to 3 times if it fails)
        val subResult = handler.enableNotifications(mainServiceUUID, powerCharacteristicUUID, important = true)
        if (subResult.isFailure) {
            HLog.e(TAG, "Failed to subscribe to power updates", subResult.exceptionOrNull())
            handler.disconnect()
            return
        }

        // 2. Start listening to continuous notifications via Flow
        startNotificationListener()

        // 3. Start battery polling
        startBatteryPolling()

        ready = true
        HLog.d(TAG, "Coyote 2 setup complete.")
    }

    private fun cleanup() {
        ready = false
        batteryPollJob?.cancel()
        batteryPollJob = null
        notificationJob?.cancel()
        notificationJob = null

        // Reset state variables
        previousChannelAPower = -1
        previousChannelBPower = -1
        sendChannelA = true
    }

    private fun startNotificationListener() {
        notificationJob?.cancel()
        notificationJob = scope.launch {
            handler.notifications.collect { notification ->
                if (notification.serviceUuid == mainServiceUUID && notification.characteristicUuid == powerCharacteristicUUID) {
                    handlePowerUpdate(notification.data)
                }
            }
        }
    }

    override fun sendPulses(pulses: List<OutputPulse>) {
        // To improve perceived responsiveness on the C2 we receive 20 pulses per second even though
        // it is only capable of 10. We then interleave them, taking the channel A and channel B
        // values from alternating pulses.
        val pulse = pulses.last()

        // We launch a coroutine to handle the BLE writes.
        // The BluetoothHandler's internal Mutex guarantees that the power write (if needed)
        // will finish before the waveform write begins.
        val powerChanged = pulse.channelAPower != previousChannelAPower || pulse.channelBPower != previousChannelBPower
        val isChannelA = sendChannelA
        sendChannelA = !sendChannelA

        scope.launch {
            if (powerChanged) {
                sendPowerLevels(pulse.channelAPower, pulse.channelBPower)
            }

            if (isChannelA) {
                // Send channel A data from this pulse
                val (x, y) = frequencyHzToXY(pulse.freqAHz.toDouble())
                val z = amplitudeToZ(pulse.ampA)
                val waveform = packWaveformData(x, y, z)
                sendWaveform(0, waveform)
            } else {
                // Send channel B data from this pulse
                val (x, y) = frequencyHzToXY(pulse.freqBHz.toDouble())
                val z = amplitudeToZ(pulse.ampB)
                val waveform = packWaveformData(x, y, z)
                sendWaveform(1, waveform)
            }
        }
    }

    private suspend fun sendWaveform(channel: Int, waveform: ByteArray) {
        val characteristic = if (channel == 0) patternACharacteristicUUID else patternBCharacteristicUUID
        handler.writeCharacteristic(
            serviceUuid = mainServiceUUID,
            characteristicUuid = characteristic,
            payload = waveform,
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            important = false // Fail fast on pulse streams to avoid stalling the queue
        )
    }

    private suspend fun sendPowerLevels(channelAPower: Int, channelBPower: Int) {
        /*  The Coyote 2's power on each channel has a range of 0-2047
            We instead use the app power level * 7 (for a max of 1400 at power level 200) as this
            makes the power steps consistent with the DG app and with how the hardware switches on
            the device work, which use steps of 7. */
        val devicePowerA = (channelAPower * 7).coerceIn(0, 2047)
        val devicePowerB = (channelBPower * 7).coerceIn(0, 2047)

        // Pack the fields: A in bits 21..11, B in bits 10..0
        val packed = (devicePowerA shl 11) or devicePowerB

        // Convert to little-endian 3 byte array
        val command = byteArrayOf(
            (packed and 0xFF).toByte(),          // LSB
            ((packed shr 8) and 0xFF).toByte(),  // Middle
            ((packed shr 16) and 0xFF).toByte()  // MSB
        )

        handler.writeCharacteristic(
            serviceUuid = mainServiceUUID,
            characteristicUuid = powerCharacteristicUUID,
            payload = command,
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            important = false
        )
    }

    private fun handlePowerUpdate(data: ByteArray) {
        if (data.size != 3) return

        // Reconstruct the packed 21-bit integer from little-endian bytes
        val packed = (data[0].toInt() and 0xFF) or
                ((data[1].toInt() and 0xFF) shl 8) or
                ((data[2].toInt() and 0xFF) shl 16)

        // Extract device power levels
        val devicePowerA = (packed shr 11) and 0x7FF // upper 11 bits
        val devicePowerB = packed and 0x7FF          // lower 11 bits

        val channelAPower = devicePowerA / 7
        val channelBPower = devicePowerB / 7

        previousChannelAPower = channelAPower
        previousChannelBPower = channelBPower

        if (MainOptions.state.value.channelAPower != channelAPower || MainOptions.state.value.channelBPower != channelBPower) {
            HLog.d(TAG,"Power level update from device A: $channelAPower B: $channelBPower")
            MainOptions.setChannelPower(0, channelAPower)
            MainOptions.setChannelPower(1, channelBPower)
        }
    }

    private fun startBatteryPolling() {
        batteryPollJob?.cancel()
        batteryPollJob = scope.launch {
            var first = true
            while (isActive) {
                HLog.d(TAG, "Polling battery level")
                val result = handler.readCharacteristic(
                    serviceUuid = batteryServiceUUID,
                    characteristicUuid = batteryCharacteristicUUID,
                    important = first // Don't stall queue with retries if a background poll fails
                )

                result.onSuccess { data ->
                    if (data.isNotEmpty()) {
                        val batteryLevel = data[0].toInt() and 0xFF
                        HLog.d(TAG, "Fetched Coyote 2 battery level: $batteryLevel%")
                        handler.setBatteryLevel(batteryLevel)
                    }
                }.onFailure { e ->
                    HLog.w(TAG, "Battery poll failed: ${e.message}")
                }

                first = false
                delay(BATTERY_POLL_INTERVAL)
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