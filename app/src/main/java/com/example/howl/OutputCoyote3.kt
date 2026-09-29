package com.example.howl

import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

@Serializable
data class Coyote3Settings(
    val frequencyBalanceA: Int = 200,
    val frequencyBalanceB: Int = 200,
    val intensityBalanceA: Int = 0,
    val intensityBalanceB: Int = 0
)

data class Coyote3Parameters (
    val powerLimitA: Int = 70,
    val powerLimitB: Int = 70,
    val frequencyBalanceA: Int = 200,
    val frequencyBalanceB: Int = 200,
    val intensityBalanceA: Int = 0,
    val intensityBalanceB: Int = 0
)

@Composable
fun Coyote3SettingsContent(
    settings: Coyote3Settings,
    onSettingsChange: (Coyote3Settings) -> Unit,
    onSaveAndSync: () -> Unit,
    onResetSettings: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "These are parameters offered by the Coyote 3 hardware itself. Its frequency balance control works differently to Howl's built-in calibration (you can adjust either or both).",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        SliderWithLabel(
            label = "Frequency Balance A",
            value = settings.frequencyBalanceA.toFloat(),
            onValueChange = { onSettingsChange(settings.copy(frequencyBalanceA = it.roundToInt())) },
            onValueChangeFinished = onSaveAndSync,
            valueRange = Coyote3Output.FREQUENCY_BALANCE_RANGE.toClosedFloatingPointRange(),
            steps = Coyote3Output.FREQUENCY_BALANCE_RANGE.last - 1,
            valueDisplay = { it.roundToInt().toString() }
        )
        SliderWithLabel(
            label = "Frequency Balance B",
            value = settings.frequencyBalanceB.toFloat(),
            onValueChange = { onSettingsChange(settings.copy(frequencyBalanceB = it.roundToInt())) },
            onValueChangeFinished = onSaveAndSync,
            valueRange = Coyote3Output.FREQUENCY_BALANCE_RANGE.toClosedFloatingPointRange(),
            steps = Coyote3Output.FREQUENCY_BALANCE_RANGE.last - 1,
            valueDisplay = { it.roundToInt().toString() }
        )
        SliderWithLabel(
            label = "Intensity Balance A",
            value = settings.intensityBalanceA.toFloat(),
            onValueChange = { onSettingsChange(settings.copy(intensityBalanceA = it.roundToInt())) },
            onValueChangeFinished = onSaveAndSync,
            valueRange = Coyote3Output.INTENSITY_BALANCE_RANGE.toClosedFloatingPointRange(),
            steps = Coyote3Output.INTENSITY_BALANCE_RANGE.last - 1,
            valueDisplay = { it.roundToInt().toString() }
        )
        SliderWithLabel(
            label = "Intensity Balance B",
            value = settings.intensityBalanceB.toFloat(),
            onValueChange = { onSettingsChange(settings.copy(intensityBalanceB = it.roundToInt())) },
            onValueChangeFinished = onSaveAndSync,
            valueRange = Coyote3Output.INTENSITY_BALANCE_RANGE.toClosedFloatingPointRange(),
            steps = Coyote3Output.INTENSITY_BALANCE_RANGE.last - 1,
            valueDisplay = { it.roundToInt().toString() }
        )

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onResetSettings,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("Reset settings")
        }
    }
}

class Coyote3Output : BluetoothOutput("47L121000") {
    override val type = OutputType.COYOTE3
    override val pulseDivider = 1
    override val pulseBatchSize = 4
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

    companion object {
        const val TAG = "Coyote3Output"
        val FREQUENCY_BALANCE_RANGE: IntRange = 0..255
        val INTENSITY_BALANCE_RANGE: IntRange = 0..255
        val DEVICE_FREQUENCY_RANGE: IntRange = 5..240
        val DEVICE_AMPLITUDE_RANGE: IntRange = 0..100

        // Battery polling can fail if it's too close to other Bluetooth activity like sending pulses.
        // The unusual interval helps to avoid this happening multiple times in a row.
        private val BATTERY_POLL_INTERVAL = 60.02.seconds

        private val batteryServiceUUID: UUID = UUID.fromString("0000180A-0000-1000-8000-00805f9b34fb")
        private val mainServiceUUID: UUID = UUID.fromString("0000180C-0000-1000-8000-00805f9b34fb")
        private val writeCharacteristicUUID: UUID = UUID.fromString("0000150A-0000-1000-8000-00805f9b34fb")
        private val notifyCharacteristicUUID: UUID = UUID.fromString("0000150B-0000-1000-8000-00805f9b34fb")
        private val batteryCharacteristicUUID: UUID = UUID.fromString("00001500-0000-1000-8000-00805f9b34fb")
    }

    private val _settings = MutableStateFlow(Coyote3Settings())
    val settings: StateFlow<Coyote3Settings> = _settings.asStateFlow()

    fun updateSettings(newSettings: Coyote3Settings) {
        _settings.value = newSettings
    }

    override fun getSettingsJson(): String {
        return Json.encodeToString(settings.value)
    }

    override fun applySettings(json: String) {
        try {
            val parsedSettings = Json.decodeFromString<Coyote3Settings>(json)
            // Apply silently without triggering sync during state restoration
            _settings.value = parsedSettings
        } catch (e: Exception) {
            HLog.e(TAG, "Failed to parse Coyote 3 settings", e)
        }
    }

    override val settingsUI: (@Composable () -> Unit) = {
        val currentSettings by settings.collectAsStateWithLifecycle()
        Coyote3SettingsContent(
            settings = currentSettings,
            onSettingsChange = { newSettings ->
                updateSettings(newSettings)
            },
            onSaveAndSync = {
                OutputManager.saveState()
                syncParameters()
            },
            onResetSettings = {
                updateSettings(Coyote3Settings())
                OutputManager.saveState()
                syncParameters()
            }
        )
    }

    fun syncParameters() {
        scope.launch {
            if (handler.connectionState.value == ConnectionStatus.Connected) {
                syncParametersSuspend()
            }
        }
    }

    override fun initialise() {
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
        HLog.d(TAG, "Setting up Coyote 3.")

        // 1. Subscribe to notifications (Retries up to 3 times if it fails)
        val subResult = handler.enableNotifications(mainServiceUUID, notifyCharacteristicUUID, important = true)
        if (subResult.isFailure) {
            HLog.e(TAG, "Failed to subscribe to notifications", subResult.exceptionOrNull())
            handler.disconnect()
            return
        }

        // 2. Sync parameters (Retries up to 3 times if it fails)
        val syncResult = syncParametersSuspend()
        if (syncResult.isFailure) {
            HLog.e(TAG, "Failed to sync parameters", syncResult.exceptionOrNull())
            handler.disconnect()
            return
        }

        // 3. Start listening to continuous notifications via Flow
        startNotificationListener()

        // 4. Start battery polling
        startBatteryPolling()

        ready = true
        HLog.d(TAG, "Coyote 3 setup complete.")
    }

    private fun cleanup() {
        ready = false
        batteryPollJob?.cancel()
        batteryPollJob = null
        notificationJob?.cancel()
        notificationJob = null

        previousChannelAPower = -1
        previousChannelBPower = -1
    }

    private fun startNotificationListener() {
        notificationJob?.cancel()
        notificationJob = scope.launch {
            handler.notifications.collect { notification ->
                if (notification.serviceUuid == mainServiceUUID && notification.characteristicUuid == notifyCharacteristicUUID) {
                    handleDeviceUpdate(notification.data)
                }
            }
        }
    }

    private fun handleDeviceUpdate(data: ByteArray) {
        if (data.size < 4) return
        when (data[0]) {
            0xB1.toByte() -> {
                val strengthByte = data[1]
                // Use and 0xFF to treat bytes as unsigned (0-255) instead of signed (-128 to 127)
                val powerA = data[2].toInt() and 0xFF
                val powerB = data[3].toInt() and 0xFF

                previousChannelAPower = powerA
                previousChannelBPower = powerB

                if (strengthByte == 0x00.toByte()) {
                    Log.v(TAG, "Received new power levels from device: A: $powerA B: $powerB")
                    MainOptions.setChannelPower(0, powerA)
                    MainOptions.setChannelPower(1, powerB)
                }
            }
        }
    }

    override fun sendPulses(pulses: List<OutputPulse>) {
        val last = pulses.last()
        var strengthByte = 0x00.toByte()
        if (last.channelAPower != previousChannelAPower || last.channelBPower != previousChannelBPower) {
            strengthByte = 0x1F.toByte()
        }

        val command = byteArrayOf(
            0xB0.toByte(), // command header
            strengthByte, // sequence number + strength interpretation method
            last.channelAPower.toByte(),
            last.channelBPower.toByte(),
        ) + pulsesToByteArray(pulses)

        // Fire-and-forget for high-frequency pulse streaming.
        // important = false ensures we don't stall the audio engine with exponential backoff retries.
        scope.launch {
            val result = handler.writeCharacteristic(
                serviceUuid = mainServiceUUID,
                characteristicUuid = writeCharacteristicUUID,
                payload = command,
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                important = false
            )

            // Log queue busy events or any other failures without stalling the audio engine
            result.onFailure { e ->
                Log.w(TAG, "Pulse send failed: ${e.message}")
            }
        }
    }

    suspend fun syncParametersSuspend(): Result<Unit> {
        val currentSettings = _settings.value

        val params = Coyote3Parameters(
            powerLimitA = Prefs.powerLimitA.value,
            powerLimitB = Prefs.powerLimitB.value,
            frequencyBalanceA = currentSettings.frequencyBalanceA,
            frequencyBalanceB = currentSettings.frequencyBalanceB,
            intensityBalanceA = currentSettings.intensityBalanceA,
            intensityBalanceB = currentSettings.intensityBalanceB
        )
        val command = byteArrayOf(
            0xBF.toByte(),
            params.powerLimitA.toByte(),
            params.powerLimitB.toByte(),
            params.frequencyBalanceA.toByte(),
            params.frequencyBalanceB.toByte(),
            params.intensityBalanceA.toByte(),
            params.intensityBalanceB.toByte()
        )
        Log.v(TAG, "Sending BF command, data: ${command.toHexString()}")

        return handler.writeCharacteristic(
            serviceUuid = mainServiceUUID,
            characteristicUuid = writeCharacteristicUUID,
            payload = command,
            important = true // Retry setup commands
        )
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
                    important = first
                )

                result.onSuccess { data ->
                    if (data.isNotEmpty()) {
                        val batteryLevel = data[0].toInt() and 0xFF
                        HLog.d(TAG, "Fetched Coyote battery level: $batteryLevel%")
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

    private fun frequencyHzToCoyote(frequency: Double): Int {
        // Convert a frequency in Hz to the weird 5-240 range the Coyote uses
        // Round as late as possible, since granularity is already extremely poor for higher frequencies
        val coyoteFreq = when (val period = 1000.0 / frequency) {
            in 5.0..100.0 -> period
            in 100.0..600.0 -> (period - 100) / 5.0 + 100
            in 600.0..1000.0 -> (period - 600) / 10.0 + 200
            else -> 10.0
        }
        return coyoteFreq.roundToInt().coerceIn(DEVICE_FREQUENCY_RANGE)
    }

    private fun pulsesToByteArray(pulses: List<OutputPulse>): ByteArray {
        // Convert our frequencies to the Coyote's nearest available value
        val channelAConvertedFrequencies = pulses.map {
            frequencyHzToCoyote(it.freqAHz.toDouble()).toByte()
        }.toByteArray()
        val channelBConvertedFrequencies = pulses.map {
            frequencyHzToCoyote(it.freqBHz.toDouble()).toByte()
        }.toByteArray()

        // Convert our internal amplitudes (0 to 1) to the Coyote's range
        val channelAConvertedIntensities = pulses.map {
            (it.ampA * 100).roundToInt().coerceIn(DEVICE_AMPLITUDE_RANGE).toByte()
        }.toByteArray()
        val channelBConvertedIntensities = pulses.map {
            (it.ampB * 100).roundToInt().coerceIn(DEVICE_AMPLITUDE_RANGE).toByte()
        }.toByteArray()

        return channelAConvertedFrequencies + channelAConvertedIntensities + channelBConvertedFrequencies + channelBConvertedIntensities
    }
}