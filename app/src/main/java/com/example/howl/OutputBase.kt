package com.example.howl

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max

/* Main loop runs at this fixed rate. All output timing derives from this. */
const val MAIN_LOOP_RATE_HZ = 40
const val MAIN_LOOP_INTERVAL_SECS = 1.0 / MAIN_LOOP_RATE_HZ  // 0.025s

@Serializable
data class Pulse (
    val ampA: Float = 0.0f,
    val ampB: Float = 0.0f,
    val freqA: Float = 0.0f,
    val freqB: Float = 0.0f
)

/**
 * A fully-resolved pulse ready for output delivery. Contains all [Pulse] fields
 * plus the device power levels and frequencies mapped into Hz for the app's
 * current frequency range.
 */
@Serializable
data class OutputPulse(
    val ampA: Float = 0.0f,
    val ampB: Float = 0.0f,
    val freqA: Float = 0.0f,
    val freqB: Float = 0.0f,
    val freqAHz: Float = 0.0f,
    val freqBHz: Float = 0.0f,
    val channelAPower: Int = 0,
    val channelBPower: Int = 0,
)

fun Pulse.blend(other: Pulse, proportion: Float): Pulse {
    val t = proportion.coerceIn(0.0f, 1.0f)
    return Pulse(
        ampA = ampA + t * (other.ampA - ampA),
        ampB = ampB + t * (other.ampB - ampB),
        freqA = freqA + t * (other.freqA - freqA),
        freqB = freqB + t * (other.freqB - freqB)
    )
}

fun OutputPulse.toPulse(): Pulse = Pulse(
    ampA = ampA,
    ampB = ampB,
    freqA = freqA,
    freqB = freqB,
)

@Serializable
data class Calibration(
    val amplitudeScaling: Float = 1.0f,
    val amplitudeBalance: Float = 0.5f,
    val frequencyBalanceA: Float = 0.5f,
    val frequencyBalanceB: Float = 0.5f,
)

@Serializable
data class Tweaks(
    val amplitudeFeelA: Float = 1.0f,
    val amplitudeFeelB: Float = 1.0f,
    val frequencyFeelA: Float = 1.0f,
    val frequencyFeelB: Float = 1.0f,
    val frequencyInvertA: Boolean = false,
    val frequencyInvertB: Boolean = false,
    val frequencyAdjustA: Float = 0.0f,
    val frequencyAdjustB: Float = 0.0f
)

@Serializable
data class OutputState(
    val type: String,
    val calibration: Calibration = Calibration(),
    val tweaks: Tweaks = Tweaks(),
    val freqSubsetStart: Float = 0.0f,
    val freqSubsetEnd: Float = 1.0f,
    val typeSpecificSettings: String = "{}",
    val rememberedAddress: String? = null
)

enum class OutputType(
    val displayName: String,
    val description: String,
    val warning: String?,
    private val factory: () -> Output
) {
    RECORDER(
        displayName = "Recorder",
        description = "Recorder output (not shown to users)",
        warning = null,
        factory = ::RecorderOutput
    ),
    COYOTE3(
        displayName = "Coyote 3",
        description = "A modern pulse based device from DG-LAB, capable of 40 updates per second.\n\nTIP: If your Coyote 3 does not want to connect, pull down both switches on it to activate pairing mode.",
        warning = null,
        factory = ::Coyote3Output
    ),
    COYOTE2(
        displayName = "Coyote 2",
        description = "A legacy pulse based device from DG-LAB, capable of 10 updates per second. Howl makes it feel a bit less sluggish by interleaving updates between channels.",
        warning = null,
        factory = ::Coyote2Output
    ),
    AUDIO_CONTINUOUS(
        displayName = "Audio (continuous)",
        description = "Produces continuous tones for devices with their own audio processing (e.g. units that respond to music).",
        warning = "WARNING: Do not use with directly driven devices such as DIY stereostim or the Tingler (many available frequency range choices are unsafe for these units).",
        factory = ::ContinuousOutput
    ),
    AUDIO_WAVELET(
        displayName = "Audio (wavelet)",
        description = "Produces wavelets for stereostim devices. The wavelet technique uses discrete bursts of pulses from a fast carrier wave. It aims to provide similar sensation to traditional audio techniques, while reducing the energy transmitted.",
        warning = null,
        factory = ::WaveletOutput
    ),
    AUDIO_MULTIPULSE(
        displayName = "Audio (multipulse)",
        description = "An experimental audio output technique for stereostim devices. It's inspired by how pulse based units work, but uses bursts of multiple pulses rather than individual ones.",
        warning = "WARNING: Multipulse output is at an early testing stage, so it's only for experienced stereostim users. Please test what settings on the 'Device' tab feel best, and submit feedback to the developer.",
        factory = ::MultiPulseOutput
    ),
    FOC(
        displayName = "FOC Stim",
        description = "Output over WiFi for FOC Stim (v4) devices. Configure its IP address in the device settings after adding.\n\nA four electrode configuration must be used. Electrode order is lowest (leftmost socket) to highest (rightmost socket).",
        warning = null,
        factory = ::FOCOutput
    );

    fun create(): Output = factory()
}

interface Output {
    val type: OutputType
    /**
     * Pulse divider: only process 1 in every N pulses from the 40Hz loop.
     *   1 → 40Hz (every pulse)
     *   2 → 20Hz (every other pulse)
     *   4 → 10Hz (one in four)
     */
    val pulseDivider: Int
    /**
     * Batch size: accumulate this many (post-division) pulses before calling
     * [sendPulses]. Use 1 for immediate per-pulse delivery.
     *   1 → send each pulse as it arrives
     *   4 → collect 4 pulses, then send them together (e.g. Coyote 3)
     */
    val pulseBatchSize: Int

    val sendSilenceWhenMuted: Boolean
    var ready: Boolean

    var minFrequencyLimit: Int
    var maxFrequencyLimit: Int
    val minFrequency: Int get() {
        val subset = selectedFrequencySubset.value
        val rangeSpan = maxFrequencyLimit - minFrequencyLimit
        return minFrequencyLimit + (rangeSpan * subset.start).toInt()
    }
    val maxFrequency: Int get() {
        val subset = selectedFrequencySubset.value
        val rangeSpan = maxFrequencyLimit - minFrequencyLimit
        return minFrequencyLimit + (rangeSpan * subset.endInclusive).toInt()
    }
    val frequencyRange: Int get() { return (maxFrequency - minFrequency) }
    val frequencyLimitRange: Int get() { return (maxFrequencyLimit - minFrequencyLimit) }
    val selectedFrequencySubset: StateFlow<ClosedFloatingPointRange<Float>>
    fun setSelectedFrequencySubset(range: ClosedFloatingPointRange<Float>)

    val pulseTime: Double
        get() = MAIN_LOOP_INTERVAL_SECS * pulseDivider
    fun initialise()
    fun destroy()
    fun start()
    fun stop()
    fun sendPulses(pulses: List<OutputPulse>)

    fun toState(): OutputState
    fun applyState(state: OutputState)

    val settingsUI: (@Composable () -> Unit)?
        get() = null
}

interface ConnectableOutput {
    val connectionStatus: StateFlow<ConnectionStatus>
    suspend fun connect()
    fun disconnect()
}

abstract class BaseOutput : Output {
    override var minFrequencyLimit: Int = 10
    override var maxFrequencyLimit: Int = 100
    open val defaultFrequencySubset: ClosedFloatingPointRange<Float>
        get() = 0.0f..1.0f
    private val _selectedFrequencySubset = MutableStateFlow(defaultFrequencySubset)
    override val selectedFrequencySubset: StateFlow<ClosedFloatingPointRange<Float>> = _selectedFrequencySubset.asStateFlow()

    private val _calibration = MutableStateFlow(Calibration())
    val calibration: StateFlow<Calibration> = _calibration.asStateFlow()

    private val _tweaks = MutableStateFlow(Tweaks())
    val tweaks: StateFlow<Tweaks> = _tweaks.asStateFlow()

    fun updateCalibration(newCalibration: Calibration) {
        _calibration.value = newCalibration
    }

    fun updateTweaks(newTweaks: Tweaks) {
        _tweaks.value = newTweaks
    }
    private var dividerCounter = 0
    private val batchBuffer by lazy { CircularBuffer<OutputPulse>(pulseBatchSize) }
    override fun initialise() {}
    override fun destroy() {}
    override fun start() { clearPending() }
    override fun stop() { clearPending() }
    override fun setSelectedFrequencySubset(range: ClosedFloatingPointRange<Float>) {
        _selectedFrequencySubset.value = range
    }
    /**
     * Entry point called once per 40Hz loop iteration.
     * Handles division and batching transparently.
     */
    fun receivePulse(pulse: Pulse, powerA: Int, powerB: Int) {
        val cal = _calibration.value
        val tw = _tweaks.value

        // Apply tweaks before calibration
        var tweakedPulse = pulse.copy(
            freqA = if (tw.frequencyInvertA) 1.0f - pulse.freqA else pulse.freqA,
            freqB = if (tw.frequencyInvertB) 1.0f - pulse.freqB else pulse.freqB,
        )

        tweakedPulse = tweakedPulse.copy(
            freqA = calculateFeelAdjustment(tweakedPulse.freqA, tw.frequencyFeelA),
            freqB = calculateFeelAdjustment(tweakedPulse.freqB, tw.frequencyFeelB),
            ampA = calculateFeelAdjustment(tweakedPulse.ampA, tw.amplitudeFeelA),
            ampB = calculateFeelAdjustment(tweakedPulse.ampB, tw.amplitudeFeelB)
        )

        tweakedPulse = tweakedPulse.copy(
            freqA = (tweakedPulse.freqA + tw.frequencyAdjustA).coerceIn(0.0f..1.0f),
            freqB = (tweakedPulse.freqB + tw.frequencyAdjustB).coerceIn(0.0f..1.0f)
        )

        // Apply calibrations
        val amplitudeScaling = cal.amplitudeScaling
        val amplitudeBalance = cal.amplitudeBalance
        val freqBalanceA = cal.frequencyBalanceA
        val freqBalanceB = cal.frequencyBalanceB

        // Amplitude balance: scales down the channel opposite to the balance direction
        val ampAScale = 1.0f - max(0f, amplitudeBalance - 0.5f) * 2.0f
        val ampBScale = 1.0f - max(0f, 0.5f - amplitudeBalance) * 2.0f

        // Frequency balance: attenuates specific frequency ranges based on balance deviation
        val freqScaleA = calculateFrequencyScale(freqBalanceA, tweakedPulse.freqA)
        val freqScaleB = calculateFrequencyScale(freqBalanceB, tweakedPulse.freqB)

        // Generate our final output pulse, ensuring fields are clamped to appropriate ranges
        // to guard against calculation errors anywhere in the generation or effects chain.
        val outputPulse = OutputPulse(
            ampA = (tweakedPulse.ampA * ampAScale * freqScaleA * amplitudeScaling).coerceIn(0f, 1f),
            ampB = (tweakedPulse.ampB * ampBScale * freqScaleB * amplitudeScaling).coerceIn(0f, 1f),
            freqA = (tweakedPulse.freqA).coerceIn(0f, 1f),
            freqB = (tweakedPulse.freqB).coerceIn(0f, 1f),
            freqAHz = (minFrequency + tweakedPulse.freqA * frequencyRange).coerceIn(minFrequencyLimit.toFloat(), maxFrequencyLimit.toFloat()),
            freqBHz = (minFrequency + tweakedPulse.freqB * frequencyRange).coerceIn(minFrequencyLimit.toFloat(), maxFrequencyLimit.toFloat()),
            channelAPower = powerA,
            channelBPower = powerB
        )

        // --- Pulse division ---
        dividerCounter++
        if (dividerCounter < pulseDivider) return
        dividerCounter = 0

        // --- Batch accumulation ---
        if (pulseBatchSize <= 1) {
            // Immediate delivery — no buffering needed
            sendPulses(listOf(outputPulse))
        } else {
            batchBuffer.add(outputPulse)
            if (batchBuffer.isFull) {
                sendPulses(batchBuffer.toList())
                batchBuffer.clear()
            }
        }
    }

    private fun calculateFrequencyScale(freqBalance: Float, freq: Float): Float {
        val reduction = 2.0f * abs(freqBalance - 0.5f)
        val target = if (freqBalance < 0.5f) freq else 1.0f - freq
        return 1.0f - reduction * target
    }

    /** Reset internal counters (call on start/stop). */
    fun clearPending() {
        dividerCounter = 0
        if (pulseBatchSize > 1) batchBuffer.clear()
    }

    override fun toState(): OutputState {
        val subset = selectedFrequencySubset.value
        return OutputState(
            type = type.name,
            calibration = calibration.value,
            tweaks = tweaks.value,
            freqSubsetStart = subset.start,
            freqSubsetEnd = subset.endInclusive,
            typeSpecificSettings = getSettingsJson(),
            rememberedAddress = getRememberedAddress()
        )
    }

    override fun applyState(state: OutputState) {
        updateCalibration(state.calibration)
        updateTweaks(state.tweaks)
        setSelectedFrequencySubset(state.freqSubsetStart..state.freqSubsetEnd)
        applySettings(state.typeSpecificSettings)
        setRememberedAddress(state.rememberedAddress)
    }

    protected open fun getRememberedAddress(): String? = null
    protected open fun setRememberedAddress(address: String?) {}

    protected open fun getSettingsJson(): String = "{}"
    protected open fun applySettings(json: String) {}
}

abstract class AudioOutput : BaseOutput()

abstract class BluetoothOutput(
    val deviceName: String
) : BaseOutput(), ConnectableOutput {
    val handler: BluetoothHandler by lazy {
        val context = HowlApp.context.applicationContext
        BluetoothHandler(
            context = context,
            bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager,
            deviceName = deviceName,
            friendlyName = type.displayName,
            onAddressChanged = { OutputManager.saveState() }
        )
    }

    override fun getRememberedAddress(): String? = handler.rememberedAddress
    override fun setRememberedAddress(address: String?) {
        handler.rememberedAddress = address
    }

    override val connectionStatus: StateFlow<ConnectionStatus>
        get() = handler.connectionState

    override suspend fun connect() {
        handler.scanAndConnect()
    }

    override fun disconnect() {
        handler.disconnect()
    }

    val batteryLevel: StateFlow<Int>
        get() = handler.batteryLevel
}