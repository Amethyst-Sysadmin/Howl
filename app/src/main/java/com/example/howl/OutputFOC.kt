package com.example.howl

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import focstim_rpc.Constants.AxisType
import focstim_rpc.Constants.OutputMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds

data class FOC4ElectrodePowers(
    val e1: Double,
    val e2: Double,
    val e3: Double,
    val e4: Double
)

@Serializable
enum class ElectrodePowerMode(val displayName: String) {
    SIMPLE("Stereo emulation"),
    DYNAMIC("Dynamic"),
    POSITIONAL("Positional"),
}

@Serializable
enum class ElectrodeGrouping(val displayName: String) {
    AB_CD("Low pairs with Mid-low"),
    AC_BD("Low pairs with Mid-high"),
    AD_BC("Low pairs with High"),
}

// ── Settings ────────────────────────────────────────────────────────────────

@Serializable
data class FocSettings(
    val ipAddress: String = "192.168.0.70",
    val targetDutyCycle: Float = 0.25f,
    val maxAmplitudeAmps: Float = 0.10f,
    val powerMode: ElectrodePowerMode = ElectrodePowerMode.DYNAMIC,
    val dynamicPanStrength: Float = 0.5f,
    val positionalWidth: Float = 0.25f,
    val electrodeGrouping: ElectrodeGrouping = ElectrodeGrouping.AB_CD,
    val maxPulseWidth: Float = 15.0f,

    // Per-electrode calibration adjustments in dB
    val lowPowerDb: Float = 0.0f,
    val midLowPowerDb: Float = 0.0f,
    val midHighPowerDb: Float = 0.0f,
    val highPowerDb: Float = 0.0f,
)

@Composable
fun FocSettingsContent(
    settings: FocSettings,
    onSettingsChange: (FocSettings) -> Unit,
    onSave: () -> Unit,
    onResetCalibration: () -> Unit,
    onResetSettings: () -> Unit
) {
    Column {
        // Local state for the IP address field, allowing us to persist the value only if the
        // address the user types is valid.
        var ipAddressText by remember(settings.ipAddress) { mutableStateOf(settings.ipAddress) }
        val isIpValid = isValidIPv4(ipAddressText)

        OutlinedTextField(
            value = ipAddressText,
            onValueChange = { newValue ->
                // Update the local UI state immediately so the user sees their typing
                ipAddressText = newValue

                // Only update the stored settings if the IP looks valid
                if (isValidIPv4(newValue) && newValue != settings.ipAddress) {
                    onSettingsChange(settings.copy(ipAddress = newValue))
                    onSave()
                }
            },
            label = { Text("FOC IP address") },
            isError = !isIpValid,
            supportingText = {
                if (!isIpValid) {
                    Text("Invalid IPv4 address")
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (!isIpValid) {
                        // revert to the last valid IP if the user presses done while invalid
                        ipAddressText = settings.ipAddress
                    }
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .onFocusChanged { focusState ->
                    if (!focusState.isFocused && !isIpValid) {
                        // revert to the last valid IP if the user clicks away while invalid
                        ipAddressText = settings.ipAddress
                    }
                }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = "Electrode calibration", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            text = "Reduce any electrodes that feel too strong. In order from left to right (low = leftmost socket).",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        SliderWithLabel(
            label = "Low (dB)",
            value = settings.lowPowerDb,
            onValueChange = { onSettingsChange(settings.copy(lowPowerDb = it)) },
            onValueChangeFinished = onSave,
            valueRange = FOCOutput.ELECTRODE_DB_RANGE,
            steps = calculateSliderSteps(FOCOutput.ELECTRODE_DB_RANGE, 0.1f),
            valueDisplay = { String.format(Locale.US, "%.1f", it) }
        )
        SliderWithLabel(
            label = "Mid-low (dB)",
            value = settings.midLowPowerDb,
            onValueChange = { onSettingsChange(settings.copy(midLowPowerDb = it)) },
            onValueChangeFinished = onSave,
            valueRange = FOCOutput.ELECTRODE_DB_RANGE,
            steps = calculateSliderSteps(FOCOutput.ELECTRODE_DB_RANGE, 0.1f),
            valueDisplay = { String.format(Locale.US, "%.1f", it) }
        )
        SliderWithLabel(
            label = "Mid-high (dB)",
            value = settings.midHighPowerDb,
            onValueChange = { onSettingsChange(settings.copy(midHighPowerDb = it)) },
            onValueChangeFinished = onSave,
            valueRange = FOCOutput.ELECTRODE_DB_RANGE,
            steps = calculateSliderSteps(FOCOutput.ELECTRODE_DB_RANGE, 0.1f),
            valueDisplay = { String.format(Locale.US, "%.1f", it) }
        )
        SliderWithLabel(
            label = "High (dB)",
            value = settings.highPowerDb,
            onValueChange = { onSettingsChange(settings.copy(highPowerDb = it)) },
            onValueChangeFinished = onSave,
            valueRange = FOCOutput.ELECTRODE_DB_RANGE,
            steps = calculateSliderSteps(FOCOutput.ELECTRODE_DB_RANGE, 0.1f),
            valueDisplay = { String.format(Locale.US, "%.1f", it) }
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onResetCalibration,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("Reset calibration")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = "FOC parameters", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            text = "Electrode channel pairing",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 8.dp)
        )
        OptionPicker(
            currentValue = settings.electrodeGrouping,
            onValueChange = {
                onSettingsChange(settings.copy(electrodeGrouping = it))
                onSave()
            },
            options = ElectrodeGrouping.entries.toList(),
            getText = { it.displayName },
            size = OptionPickerSize.Standard,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        )
        Text(
            text = "Pattern conversion algorithm",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 8.dp)
        )
        OptionPicker(
            currentValue = settings.powerMode,
            onValueChange = {
                onSettingsChange(settings.copy(powerMode = it))
                onSave()
            },
            options = ElectrodePowerMode.entries.toList(),
            getText = { it.displayName },
            size = OptionPickerSize.Standard,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        )
        if (settings.powerMode == ElectrodePowerMode.DYNAMIC) {
            SliderWithLabel(
                label = "Dynamic algorithm panning strength",
                value = settings.dynamicPanStrength,
                onValueChange = { onSettingsChange(settings.copy(dynamicPanStrength = it)) },
                onValueChangeFinished = onSave,
                valueRange = 0.0f..1.0f,
                steps = 19,
                valueDisplay = { String.format(Locale.US, "%.2f", it) }
            )
        }
        if (settings.powerMode == ElectrodePowerMode.POSITIONAL) {
            SliderWithLabel(
                label = "Positional algorithm sensation width",
                value = settings.positionalWidth,
                onValueChange = { onSettingsChange(settings.copy(positionalWidth = it)) },
                onValueChangeFinished = onSave,
                valueRange = 0.0f..1.0f,
                steps = 19,
                valueDisplay = { String.format(Locale.US, "%.2f", it) }
            )
        }
        SliderWithLabel(
            label = "Maximum output current (amps)",
            value = settings.maxAmplitudeAmps,
            onValueChange = { onSettingsChange(settings.copy(maxAmplitudeAmps = it)) },
            onValueChangeFinished = onSave,
            valueRange = FOCOutput.MAX_AMPLITUDE_RANGE,
            steps = calculateSliderSteps(FOCOutput.MAX_AMPLITUDE_RANGE, 0.005f),
            valueDisplay = { String.format(Locale.US, "%.3f", it) }
        )
        SliderWithLabel(
            label = "Target duty cycle",
            value = settings.targetDutyCycle,
            onValueChange = { onSettingsChange(settings.copy(targetDutyCycle = it)) },
            onValueChangeFinished = onSave,
            valueRange = 0.10f..0.50f,
            steps = calculateSliderSteps(0.10f..0.50f, 0.01f),
            valueDisplay = { String.format(Locale.US, "%.2f", it) }
        )
        SliderWithLabel(
            label = "Maximum pulse width",
            value = settings.maxPulseWidth,
            onValueChange = { onSettingsChange(settings.copy(maxPulseWidth = it)) },
            onValueChangeFinished = onSave,
            valueRange = 3.0f..20.0f,
            steps = 16,
            valueDisplay = { String.format(Locale.US, "%.2f", it) }
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onResetSettings,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("Reset FOC parameters")
        }
    }
}

/**
 * Check if an IP address string looks like a valid IPv4 address.
 */
fun isValidIPv4(ip: String): Boolean {
    val parts = ip.split(".")
    if (parts.size != 4) return false
    return parts.all { part ->
        val num = part.toIntOrNull()
        num != null && num in 0..255 && part == num.toString()
    }
}

/**
 * Calculate the pulse width we will use at a given pulse frequency to achieve a target duty cycle.
 */
fun calculatePulseWidth(
    targetDutyCycle: Double,
    pulseRateHz: Double,
    carrierFrequencyHz: Int
): Float {
    if (pulseRateHz <= 1.0 || carrierFrequencyHz <= 1) return FOCOutput.PULSE_WIDTH_RANGE.start

    // dutyCycle = (pulseWidth / carrierFrequencyHz) * pulseRateHz
    // pulseWidth = (dutyCycle * carrierFrequencyHz) / pulseRateHz
    val rawPulseWidth = (targetDutyCycle * carrierFrequencyHz) / pulseRateHz
    return rawPulseWidth.toFloat().coerceIn(FOCOutput.PULSE_WIDTH_RANGE)
}

fun reversePositionalEffect(amplitudeA: Double, amplitudeB: Double): Pair<Double, Double> {
    // Does some stupid hacky stuff to reverse Howl's calculatePositionalEffect function and get back
    // the original combined amplitude and position we had in Funscript.kt. This is not a good approach,
    // we're only doing it because Howl can only pass stereo patterns between the input and output
    // layers and was not designed to support this use case.

    val curve = Prefs.calibrationPositionalEffectCurve.value.toDouble().coerceAtLeast(0.01)
    val positionalEffectStrength = Prefs.funscriptPositionalEffectStrength.value.toDouble()

    // Ensure non-negative inputs to prevent NaN when using .pow()
    val ampA = amplitudeA.coerceAtLeast(0.0)
    val ampB = amplitudeB.coerceAtLeast(0.0)

    // Handle edge cases where one or both channels are completely silent
    if (ampA == 0.0 && ampB == 0.0) {
        return Pair(0.0, 0.5) // Position is ambiguous; default to centre
    }
    if (ampB == 0.0) {
        return Pair(ampA.coerceIn(0.0, 1.0), 0.0) // Panned entirely to A
    }
    if (ampA == 0.0) {
        return Pair(ampB.coerceIn(0.0, 1.0), 1.0) // Panned entirely to B
    }

    // Reverse the curve to find the ratio between the uncurved bases
    val ratio = (ampA / ampB).pow(1.0 / curve)

    // Calculate the normalised effective position (the position AFTER applying strength interpolation)
    val effectivePosition = 1.0 / (1.0 + ratio)

    // Calculate the original bases
    val baseA = (1.0 - effectivePosition).coerceIn(0.0, 1.0)
    val baseB = effectivePosition.coerceIn(0.0, 1.0)

    // Recover the original amplitude
    // We use the larger base for numerical stability (prevents dividing by a number extremely close to 0)
    val amplitude = if (baseA > baseB) {
        ampA / baseA.pow(curve)
    } else {
        ampB / baseB.pow(curve)
    }

    // Reverse the positional effect strength to find the original position
    val originalPosition = if (positionalEffectStrength > 0.0) {
        val rawPosition = (effectivePosition - 0.5 * (1.0 - positionalEffectStrength)) / positionalEffectStrength
        rawPosition.coerceIn(0.0, 1.0)
    } else {
        0.5 // If strength is 0, position is technically ambiguous, default to centre
    }

    // Return the normalised values clamped strictly between 0.0 and 1.0
    return Pair(
        amplitude.coerceIn(0.0, 1.0),
        originalPosition
    )
}

// ── Output ──────────────────────────────────────────────────────────────────

class FOCOutput : BaseOutput(), ConnectableOutput {
    override val type = OutputType.FOC
    override val pulseDivider = 1
    override val pulseBatchSize = 1
    override val sendSilenceWhenMuted = true
    override var minFrequencyLimit: Int = 1
    override var maxFrequencyLimit: Int = 200
    override val defaultFrequencySubset: ClosedFloatingPointRange<Float>
        get() = 0.05f..0.5f
    override var ready = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectJob: Job? = null
    private var keepAliveJob: Job? = null
    private var streamJob: Job? = null

    // Latest-state handoff: a conflated channel keeps at most one pending item,
    // so stale pulses are overwritten when the network lags instead of queueing.
    // A single consumer serialises all streaming writes.
    private sealed interface StreamItem {
        data class Pulse(val pulse: OutputPulse, val settings: FocSettings) : StreamItem
        data object Prime : StreamItem
    }
    private var pulseChannel = Channel<StreamItem>(Channel.CONFLATED)
    @Volatile private var apiRef: FocApi? = null
    private val lastSent = ConcurrentHashMap<AxisType, Float>()
    private var lastForceSendMs = 0L

    @Volatile private var lastActivityMs = 0L

    private val _settings = MutableStateFlow(FocSettings())
    val settings: StateFlow<FocSettings> = _settings.asStateFlow()

    private val _focStatus = MutableStateFlow(ConnectionStatus.Disconnected)
    override val connectionStatus: StateFlow<ConnectionStatus> = _focStatus.asStateFlow()

    fun updateSettings(newSettings: FocSettings) {
        _settings.value = newSettings
    }

    override fun getSettingsJson(): String {
        return Json.encodeToString(settings.value)
    }

    override fun applySettings(json: String) {
        try {
            _settings.value = Json.decodeFromString<FocSettings>(json)
        } catch (e: Exception) {
            HLog.e(TAG, "Failed to parse FOC settings", e)
        }
    }

    fun resetSettings() {
        val current = _settings.value
        val defaults = FocSettings()
        // Reset general settings while preserving IP address and calibration values
        updateSettings(
            current.copy(
                targetDutyCycle = defaults.targetDutyCycle,
                powerMode = defaults.powerMode,
                maxAmplitudeAmps = defaults.maxAmplitudeAmps,
                dynamicPanStrength = defaults.dynamicPanStrength,
                positionalWidth = defaults.positionalWidth,
                electrodeGrouping = defaults.electrodeGrouping,
                maxPulseWidth = defaults.maxPulseWidth,
            )
        )
    }

    fun resetCalibration() {
        val current = _settings.value
        val defaults = FocSettings()
        // Reset only the 4 electrode calibration values
        updateSettings(
            current.copy(
                lowPowerDb = defaults.lowPowerDb,
                midLowPowerDb = defaults.midLowPowerDb,
                midHighPowerDb = defaults.midHighPowerDb,
                highPowerDb = defaults.highPowerDb,
            )
        )
    }

    override val settingsUI: (@Composable () -> Unit) = {
        val currentSettings by settings.collectAsStateWithLifecycle()
        FocSettingsContent(
            settings = currentSettings,
            onSettingsChange = { updateSettings(it) },
            onSave = { OutputManager.saveState() },
            onResetCalibration = {
                resetCalibration()
                OutputManager.saveState()
            },
            onResetSettings = {
                resetSettings()
                OutputManager.saveState()
            }
        )
    }

    override suspend fun connect() {
        if (_focStatus.value == ConnectionStatus.Connected || _focStatus.value == ConnectionStatus.Connecting) return
        _focStatus.value = ConnectionStatus.Connecting
        connectJob?.cancel()
        connectJob = scope.launch { runConnect() }
    }

    override fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        val api = apiRef
        apiRef = null
        ready = false
        keepAliveJob?.cancel()
        keepAliveJob = null
        streamJob?.cancel()
        streamJob = null
        // Wake any parked consumer and replace with a fresh channel for the
        // next connection (a closed channel cannot be reused).
        try { pulseChannel.close() } catch (_: Exception) { }
        pulseChannel = Channel(Channel.CONFLATED)
        lastSent.clear()

        scope.launch {
            if (api != null && !api.isClosed) {
                try {
                    api.requestSignalStop()
                } catch (e: Exception) {
                    HLog.v(TAG, "Signal stop: ${e.message}")
                }
                api.close()
            }
            _focStatus.value = ConnectionStatus.Disconnected
        }
    }

    override fun destroy() {
        disconnect()
        super.destroy()
        scope.cancel()
    }

    private suspend fun runConnect() {
        val ip = _settings.value.ipAddress.trim()
        if (ip.isEmpty()) {
            HLog.e(TAG, "No IP address configured")
            _focStatus.value = ConnectionStatus.Disconnected
            return
        }
        val api = FocApi(
            ip,
            FocApi.FOC_TCP_PORT,
            scope,
            onDisconnect = { failed, cause -> markDisconnected(failed, cause) },
            onDeviceStopped = { stopped ->
                HLog.e(TAG, "Device stopped the signal (comms lost)")
                markDisconnected(stopped, FocException("Device stopped the signal (comms lost)"))
            },
        )
        try {
            api.connect()

            val firmwareVersion = api.requestFirmwareVersion()
                .responseFirmwareVersion.stm32FirmwareVersion2
            val firmwareString =
                "${firmwareVersion.major}.${firmwareVersion.minor}.${firmwareVersion.revision} (${firmwareVersion.branch})"
            HLog.i(TAG, "Firmware version: $firmwareString")

            if (firmwareVersion.major > MAX_FIRMWARE_MAJOR) {
                throw FocException("FOC Stim firmware is too new, the maximum supported major version is $MAX_FIRMWARE_MAJOR")
            }

            val capabilities = api.requestCapabilitiesGet().responseCapabilitiesGet
            if (!capabilities.fourphase) {
                throw FocException("Device does not report four-phase support")
            }

            lastSent.clear()
            primeAxes(api)
            api.requestSignalStart(OutputMode.OUTPUT_FOURPHASE_INDIVIDUAL_ELECTRODES)

            apiRef = api
            val nowMs = SystemClock.elapsedRealtime()
            lastActivityMs = nowMs
            lastForceSendMs = nowMs
            ready = true
            _focStatus.value = ConnectionStatus.Connected

            startStreamConsumer(api)
            startKeepAlive()

            HLog.i(TAG, "FOC Stim connected at $ip, signal started")
        } catch (_: CancellationException) {
            api.close()
            _focStatus.value = ConnectionStatus.Disconnected
        } catch (e: Exception) {
            HLog.e(TAG, "Connect failed: ${e.message}")
            api.close()
            _focStatus.value = ConnectionStatus.Disconnected
        }
    }

    private fun startStreamConsumer(api: FocApi) {
        streamJob?.cancel()
        // Fresh channel per connection so stale items from a previous session
        // can never leak into the new one.
        try { pulseChannel.close() } catch (_: Exception) { }
        pulseChannel = Channel(Channel.CONFLATED)
        val channel = pulseChannel
        streamJob = scope.launch {
            for (item in channel) {
                if (!ready) continue
                try {
                    when (item) {
                        is StreamItem.Pulse -> streamPulse(api, item.pulse, item.settings)
                        StreamItem.Prime -> primeAxes(api)
                    }
                    lastActivityMs = SystemClock.elapsedRealtime()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    HLog.w(TAG, "Stream error: ${e.message}")
                    markDisconnected(api, e)
                    break
                }
            }
        }
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive) {
                delay(1000.milliseconds)
                val api = apiRef ?: break
                if (!ready) break

                // Auto-disconnect if the device hasn't sent us any data for 10 seconds
                if (SystemClock.elapsedRealtime() - api.lastReceivedMs >= 10_000L) {
                    HLog.w(TAG, "No response from device for 10 seconds, disconnecting")
                    markDisconnected(api, FocException("Device stopped responding"))
                    break
                }

                // Send a neutral axis set to keep the connection alive if we didn't
                // send output recently.
                if (SystemClock.elapsedRealtime() - lastActivityMs >= 1000) {
                    pulseChannel.trySend(StreamItem.Prime)
                }
            }
        }
    }

    private fun markDisconnected(failed: FocApi?, cause: Throwable?) {
        if (failed != null && apiRef !== failed) return
        if (!ready && apiRef == null) return

        ready = false
        keepAliveJob?.cancel()
        keepAliveJob = null
        streamJob?.cancel()
        streamJob = null
        apiRef?.close()
        apiRef = null
        lastSent.clear()
        _focStatus.value = ConnectionStatus.Disconnected
        HLog.w(TAG, "Disconnected: ${cause?.message}")
    }

    /**
     * Send a full, neutral axis set.
     */
    private suspend fun primeAxes(api: FocApi) {
        val s = _settings.value
        val axes = listOf(
            AxisType.AXIS_ELECTRODE_1_POWER to 1.0f,
            AxisType.AXIS_ELECTRODE_2_POWER to 1.0f,
            AxisType.AXIS_ELECTRODE_3_POWER to 1.0f,
            AxisType.AXIS_ELECTRODE_4_POWER to 1.0f,
            AxisType.AXIS_WAVEFORM_AMPLITUDE_AMPS to 0.0f,
            AxisType.AXIS_CARRIER_FREQUENCY_HZ to CARRIER_FREQUENCY_HZ.toFloat(),
            AxisType.AXIS_PULSE_FREQUENCY_HZ to max(minFrequency, 1).toFloat(),
            AxisType.AXIS_PULSE_WIDTH_IN_CYCLES to PULSE_WIDTH_RANGE.start,
            AxisType.AXIS_PULSE_RISE_TIME_CYCLES to RISE_TIME_CARRIER_CYCLES,
            AxisType.AXIS_PULSE_INTERVAL_RANDOM_PERCENT to PULSE_INTERVAL_RANDOMNESS,
            AxisType.AXIS_CALIBRATION_4_A to s.lowPowerDb,
            AxisType.AXIS_CALIBRATION_4_B to s.midLowPowerDb,
            AxisType.AXIS_CALIBRATION_4_C to s.midHighPowerDb,
            AxisType.AXIS_CALIBRATION_4_D to s.highPowerDb,
            AxisType.AXIS_CALIBRATION_4_REDUCTION_IN_CENTER to REDUCTION_IN_CENTER
        )
        val updates = mutableListOf<Triple<AxisType, Float, Int>>()
        for ((axis, value) in axes) {
            updates.add(Triple(axis, value, INTERPOLATION_INTERVAL_MS))
            lastSent[axis] = value
        }
        api.requestAxesMoveToBatch(updates)
    }

    // ── Streaming ───────────────────────────────────────────────────────

    override fun sendPulses(pulses: List<OutputPulse>) {
        val api = apiRef
        if (!ready || api == null || api.isClosed) return
        // Non-blocking latest-state handoff; a lagging consumer overwrites
        // stale pulses instead of queueing them.
        pulseChannel.trySend(StreamItem.Pulse(pulses.last(), _settings.value))
    }

    private suspend fun streamPulse(api: FocApi, pulse: OutputPulse, s: FocSettings) {
        val ampA = pulse.ampA.toDouble()
        val ampB = pulse.ampB.toDouble()

        val powers: FOC4ElectrodePowers
        var volume = 0.0

        when (s.powerMode) {
            ElectrodePowerMode.SIMPLE -> {
                val rawPowers = calculateSimpleElectrodePowers(ampA, ampB)
                powers = applyElectrodeGrouping(rawPowers, s.electrodeGrouping)
                volume = max(ampA, ampB).coerceIn(0.0..1.0)
            }
            ElectrodePowerMode.DYNAMIC -> {
                val rawPowers = calculateDynamicElectrodePowers(
                    ampA,
                    ampB,
                    pulse.freqA.toDouble(),
                    pulse.freqB.toDouble(),
                    s.dynamicPanStrength.toDouble()
                )
                powers = applyElectrodeGrouping(rawPowers, s.electrodeGrouping)
                volume = max(ampA, ampB).coerceIn(0.0..1.0)
            }
            ElectrodePowerMode.POSITIONAL -> {
                val (amp, position) = reversePositionalEffect(ampA, ampB)
                powers = calculatePositionalElectrodePowers(position, s.positionalWidth.toDouble())
                volume = amp
            }
        }
        // Log.d(TAG, "Electrode powers: $powers")

        // Power scaling adjustment to make the FOC's output feel more linear
        // and closer to other devices Howl supports
        volume = volume.pow(POWER_SCALING).coerceIn(0.0..1.0)

        //Log.d(TAG, "Volume: $volume")
        val volumeAmps = (volume * s.maxAmplitudeAmps).toFloat()

        // Since the FOC can only support a single pulse frequency, we use an average of the two
        // stereo frequencies from our pattern, weighted by amplitude.
        val totalAmp = pulse.ampA + pulse.ampB
        val pulseFreqHz = if (totalAmp > 1e-6f) {
            (pulse.freqAHz * pulse.ampA + pulse.freqBHz * pulse.ampB) / totalAmp
        } else {
            pulse.freqAHz
        }

        // Calculate pulse width to achieve the target duty cycle
        val currentPulseWidth = calculatePulseWidth(
            targetDutyCycle = s.targetDutyCycle.toDouble(),
            pulseRateHz = pulseFreqHz.toDouble(),
            carrierFrequencyHz = CARRIER_FREQUENCY_HZ
        ).coerceAtMost(s.maxPulseWidth)

        // Log.d(TAG, "Pulse width: $currentPulseWidth")

        val nowMs = SystemClock.elapsedRealtime()
        val forceAll = nowMs - lastForceSendMs >= FORCE_RESEND_INTERVAL_MS
        if (forceAll) lastForceSendMs = nowMs

        val updates = mutableListOf<Triple<AxisType, Float, Int>>()

        // Helper to add to batch if dirty
        fun addIfDirty(axis: AxisType, value: Float) {
            val previous = lastSent[axis]
            if (forceAll || previous == null || isAxisValueDirty(axis, previous, value)) {
                updates.add(Triple(axis, value, INTERPOLATION_INTERVAL_MS))
                lastSent[axis] = value // Safe to update early; exceptions tear down the connection anyway
            }
        }

        addIfDirty(AxisType.AXIS_ELECTRODE_1_POWER, powers.e1.toFloat())
        addIfDirty(AxisType.AXIS_ELECTRODE_2_POWER, powers.e2.toFloat())
        addIfDirty(AxisType.AXIS_ELECTRODE_3_POWER, powers.e3.toFloat())
        addIfDirty(AxisType.AXIS_ELECTRODE_4_POWER, powers.e4.toFloat())
        addIfDirty(AxisType.AXIS_WAVEFORM_AMPLITUDE_AMPS, volumeAmps)
        addIfDirty(AxisType.AXIS_CARRIER_FREQUENCY_HZ, CARRIER_FREQUENCY_HZ.toFloat())
        addIfDirty(AxisType.AXIS_PULSE_FREQUENCY_HZ, pulseFreqHz)
        addIfDirty(AxisType.AXIS_PULSE_WIDTH_IN_CYCLES, currentPulseWidth)
        addIfDirty(AxisType.AXIS_PULSE_RISE_TIME_CYCLES, RISE_TIME_CARRIER_CYCLES)
        addIfDirty(AxisType.AXIS_PULSE_INTERVAL_RANDOM_PERCENT, PULSE_INTERVAL_RANDOMNESS)
        addIfDirty(AxisType.AXIS_CALIBRATION_4_A, s.lowPowerDb)
        addIfDirty(AxisType.AXIS_CALIBRATION_4_B, s.midLowPowerDb)
        addIfDirty(AxisType.AXIS_CALIBRATION_4_C, s.midHighPowerDb)
        addIfDirty(AxisType.AXIS_CALIBRATION_4_D, s.highPowerDb)
        addIfDirty(AxisType.AXIS_CALIBRATION_4_REDUCTION_IN_CENTER, REDUCTION_IN_CENTER)

        if (updates.isNotEmpty()) {
            api.requestAxesMoveToBatch(updates)
        }
    }

    /**
     * Determines if an axis value has changed significantly enough to warrant a network update.
     */
    private fun isAxisValueDirty(axis: AxisType, previous: Float, current: Float): Boolean {
        val tolerance = when (axis) {
            AxisType.AXIS_WAVEFORM_AMPLITUDE_AMPS -> 0.001f
            AxisType.AXIS_CARRIER_FREQUENCY_HZ -> 1.0f
            AxisType.AXIS_PULSE_FREQUENCY_HZ -> 0.1f
            //AxisType.AXIS_PULSE_WIDTH_IN_CYCLES -> 0.5f
            else -> 0.01f // Default behaviour for most axes
        }
        return abs(previous - current) >= tolerance
    }

    private suspend fun sendAxis(api: FocApi, axis: AxisType, value: Float, force: Boolean) {
        val previous = lastSent[axis]
        if (!force && previous != null && !isAxisValueDirty(axis, previous, value)) return

        api.requestAxisMoveTo(axis, value, INTERPOLATION_INTERVAL_MS)
        lastSent[axis] = value
    }

    override fun stop() {
        // super.stop() calls clearPending() to reset pulse dividers and batch buffers
        super.stop()

        val api = apiRef
        if (ready && api != null && !api.isClosed) {
            // primeAxes explicitly sets AXIS_WAVEFORM_AMPLITUDE_AMPS to 0.0f
            // and resets the electrodes to 1.0f (a safe, neutral hardware state).
            pulseChannel.trySend(StreamItem.Prime)
        }
    }

    /**
     * "Stereo emulation" algorithm: AB represents channel A, CD represents channel B.
     * The channel with the highest amplitude gets both electrodes set to 1.0.
     * The other two electrodes are set to the ratio of the lower amplitude to the higher amplitude.
     */
    fun calculateSimpleElectrodePowers(ampA: Double, ampB: Double): FOC4ElectrodePowers {
        if (ampA >= ampB) {
            val ratio = if (ampA > 0.0) ampB / ampA else 0.0
            return applyFOCConstraints(1.0, 1.0, ratio, ratio)
        } else {
            val ratio = if (ampB > 0.0) ampA / ampB else 0.0
            return applyFOCConstraints(ratio, ratio, 1.0, 1.0)
        }
    }

    /**
     * "Dynamic" algorithm:
     * Uses a similar idea to the simple algorithm of the relative amplitudes determining
     * stereo balance. But adds additional spatial panning so that the balance between
     * A and B is affected by the channel A frequency, and the balance between C and D is
     * affected by the channel B frequency.
     *
     * This isn't technically accurate (as we're converting frequency effects to spatial effects),
     * but creates more interesting true four phase patterns that are better suited to the FOC.
     */
    fun calculateDynamicElectrodePowers(
        ampA: Double,
        ampB: Double,
        freqA: Double,
        freqB: Double,
        panStrength: Double = 1.0
    ): FOC4ElectrodePowers {
        // Fall back to a neutral state if both channels are effectively silent
        if (ampA < 1e-4 && ampB < 1e-4) {
            return FOC4ElectrodePowers(1.0, 1.0, 1.0, 1.0)
        }

        val strength = panStrength.coerceIn(0.0, 1.0)

        // Constant sum panning using squared trigonometric functions.
        val thetaA = freqA * PI / 2.0
        val thetaB = freqB * PI / 2.0

        val panA1 = cos(thetaA).pow(2.0)
        val panA2 = sin(thetaA).pow(2.0)
        val panB1 = cos(thetaB).pow(2.0)
        val panB2 = sin(thetaB).pow(2.0)

        // Mix between the fully panned weights and a neutral 0.5 centre balance based on panStrength
        val w1 = ampA * ((1.0 - strength) * 0.5 + strength * panA1)
        val w2 = ampA * ((1.0 - strength) * 0.5 + strength * panA2)
        val w3 = ampB * ((1.0 - strength) * 0.5 + strength * panB1)
        val w4 = ampB * ((1.0 - strength) * 0.5 + strength * panB2)

        return applyFOCConstraints(w1, w2, w3, w4)
    }

    /**
     * Uses our four electrode powers to represent a one dimensional positional effect.
     *
     * @param position The normalised position from 0.0 (bottom) to 1.0 (top).
     * @param width The desired sensation width from 0.0 (narrow, only adjacent electrodes)
     *              to 1.0 (wide, further away electrodes are impacted).
     */
    fun calculatePositionalElectrodePowers(position: Double, width: Double = 0.0): FOC4ElectrodePowers {
        val pos = position.coerceIn(0.0, 1.0)
        val p = pos * 3.0 // Scale to 0.0..3.0 to match the 4 electrode positions (0, 1, 2, 3)

        val w = width.coerceIn(0.0, 1.0)
        val spread = 1.0 + w * 3.0

        fun weight(dist: Double): Double {
            val scaledDist = dist / spread
            return if (scaledDist <= 1.0) cos(scaledDist * PI / 2.0).pow(2.0) else 0.0
        }

        val w1 = weight(p - 0.0)
        val w2 = weight(p - 1.0)
        val w3 = weight(p - 2.0)
        val w4 = weight(p - 3.0)

        return applyFOCConstraints(w1, w2, w3, w4)
    }

    /**
     * Utility function to apply the FOC hardware constraints to 4 raw weights.
     * Core logic is a Kotlin port of the FOC firmware's fourphase_constrain_coordinates()
     * so the values we send should be identical to what the device would choose.
     *
     * Constraints enforced: at least one electrode is exactly 1.0, and the largest
     * electrode never exceeds the sum of the other three.
     *
     * The weights are automatically normalised so the maximum value is exactly 1.0,
     * saving the caller from having to do it manually.
     */
    private fun applyFOCConstraints(w1: Double, w2: Double, w3: Double, w4: Double): FOC4ElectrodePowers {
        // First normalise the weights so that the maximum is 1.0
        // the firmware doesn't do this step, but it saves us some work elsewhere
        val rawMax = maxOf(w1, w2, w3, w4)
        val safeMax = if (rawMax < 1e-12) 1.0 else rawMax

        val a = (w1 / safeMax).coerceIn(0.0, 1.0)
        val b = (w2 / safeMax).coerceIn(0.0, 1.0)
        val c = (w3 / safeMax).coerceIn(0.0, 1.0)
        val d = (w4 / safeMax).coerceIn(0.0, 1.0)

        // sX > 0 means electrode X dominates and the other three must rise to
        // satisfy the constraint; sX == 0 always otherwise.
        val sA = minOf(-a + b + c + d, 0.0) / -3.0
        val sB = minOf( a - b + c + d, 0.0) / -3.0
        val sC = minOf( a + b - c + d, 0.0) / -3.0
        val sD = minOf( a + b + c - d, 0.0) / -3.0

        var x1 = a + sB + sC + sD
        var x2 = b + sA + sC + sD
        var x3 = c + sA + sB + sD
        var x4 = d + sA + sB + sC

        // Boost everything so the maximum is exactly 1.0, keeping the shape.
        // Priority order on ties (a, b, c, d) matches the firmware.
        // Not generally triggered as we normalise first, but logic preserved for completeness.
        val maxVal = max(max(x1, x2), max(x3, x4))
        if (maxVal < 1.0) {
            val toAdd = 1.0 - maxVal
            when (maxVal) {
                x1 -> {
                    x1 = 1.0 // fix rounding errors, as later code tests for equality with 1
                    x2 += toAdd
                    x3 += toAdd
                    x4 += toAdd
                }
                x2 -> {
                    x2 = 1.0
                    x1 += toAdd
                    x3 += toAdd
                    x4 += toAdd
                }
                x3 -> {
                    x3 = 1.0
                    x1 += toAdd
                    x2 += toAdd
                    x4 += toAdd
                }
                else -> {
                    x4 = 1.0
                    x1 += toAdd
                    x2 += toAdd
                    x3 += toAdd
                }
            }
        }

        return FOC4ElectrodePowers(x1, x2, x3, x4)
    }

    /**
     * Remaps the calculated channel powers (where e1/e2 are Channel A and e3/e4 are Channel B)
     * to the physical electrodes based on the selected electrode grouping.
     */
    private fun applyElectrodeGrouping(powers: FOC4ElectrodePowers, grouping: ElectrodeGrouping): FOC4ElectrodePowers {
        return when (grouping) {
            // Channel A = Low & Mid-low (e1, e2) | Channel B = Mid-high & High (e3, e4)
            ElectrodeGrouping.AB_CD -> powers
            // Channel A = Low & Mid-high (e1, e3) | Channel B = Mid-low & High (e2, e4)
            ElectrodeGrouping.AC_BD -> FOC4ElectrodePowers(powers.e1, powers.e3, powers.e2, powers.e4)
            // Channel A = Low & High (e1, e4) | Channel B = Mid-low & Mid-high (e2, e3)
            ElectrodeGrouping.AD_BC -> FOC4ElectrodePowers(powers.e1, powers.e3, powers.e4, powers.e2)
        }
    }

    companion object {
        const val TAG = "FOCOutput"

        // Maximum supported firmware version (major version changes are not backwards compatible)
        const val MAX_FIRMWARE_MAJOR = 2
        // Interpolation time, in line with our 40Hz pattern update rate
        const val INTERPOLATION_INTERVAL_MS = 25
        // Forced full resend interval
        const val FORCE_RESEND_INTERVAL_MS = 1000L
        const val POWER_SCALING = 0.5
        const val REDUCTION_IN_CENTER = 0.08f
        const val PULSE_INTERVAL_RANDOMNESS = 0.0f
        const val RISE_TIME_CARRIER_CYCLES = 5.0f
        const val CARRIER_FREQUENCY_HZ = 1300

        val CARRIER_RANGE: IntRange = 500..2000
        val RISE_TIME_RANGE: ClosedFloatingPointRange<Float> = 2.0f..10.0f
        val RANDOMNESS_RANGE: ClosedFloatingPointRange<Float> = 0.0f..0.3f
        val ELECTRODE_DB_RANGE: ClosedFloatingPointRange<Float> = -10.0f..0.0f
        val PULSE_WIDTH_RANGE: ClosedFloatingPointRange<Float> = 3.0f..20.0f
        val MAX_AMPLITUDE_RANGE: ClosedFloatingPointRange<Float> = 0.06f..0.12f
    }
}
