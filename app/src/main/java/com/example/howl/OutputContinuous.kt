package com.example.howl

import androidx.compose.foundation.layout.Arrangement
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

@Serializable
data class ContinuousSettings(
    val fullVolume: Boolean = false,
    val minFrequencyLimit: Int = 500,
    val maxFrequencyLimit: Int = 1000,
    val waveShape: AudioWaveShape = AudioWaveShape.SINE
)

@Composable
fun ContinuousSettingsContent(
    settings: ContinuousSettings,
    onSettingsChange: (ContinuousSettings) -> Unit,
    onSave: () -> Unit,
    onResetSettings: () -> Unit
) {
    Column {
        SwitchWithLabel(
            label = "Always full volume (ignore power)",
            checked = settings.fullVolume,
            onCheckedChange = {
                onSettingsChange(settings.copy(fullVolume = it))
                onSave()
            }
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Wave shape", style = MaterialTheme.typography.labelLarge)
            OptionPicker(
                currentValue = settings.waveShape,
                onValueChange = {
                    onSettingsChange(settings.copy(waveShape = it))
                    onSave()
                },
                options = AudioWaveShape.entries,
                getText = { it.displayName }
            )
        }
        val frequencySliderRange = 50..4000
        SliderWithLabel(
            label = "Minimum allowed frequency (Hz)",
            value = settings.minFrequencyLimit.toFloat(),
            onValueChange = { rawMin ->
                // Use roundToInt() to prevent float truncation issues (e.g. 2999.9 -> 3000)
                // Force minimum to be at least 50Hz less than the current maximum
                val newMin = rawMin.roundToInt().coerceAtMost(settings.maxFrequencyLimit - 50)
                onSettingsChange(settings.copy(minFrequencyLimit = newMin))
            },
            onValueChangeFinished = onSave,
            valueRange = frequencySliderRange.toClosedFloatingPointRange(),
            steps = calculateSliderSteps(frequencySliderRange.toClosedFloatingPointRange(), 50.0f),
            valueDisplay = { it.roundToInt().toString() }
        )
        SliderWithLabel(
            label = "Maximum allowed frequency (Hz)",
            value = settings.maxFrequencyLimit.toFloat(),
            onValueChange = { rawMax ->
                // Use roundToInt() to prevent float truncation issues
                // Force maximum to be at least 50Hz greater than the current minimum
                val newMax = rawMax.roundToInt().coerceAtLeast(settings.minFrequencyLimit + 50)
                onSettingsChange(settings.copy(maxFrequencyLimit = newMax))
            },
            onValueChangeFinished = onSave,
            valueRange = frequencySliderRange.toClosedFloatingPointRange(),
            steps = calculateSliderSteps(frequencySliderRange.toClosedFloatingPointRange(), 50.0f),
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

/**
 * Continuous waveform output.
 *
 * Generates an unbroken stereo tone whose frequency and amplitude
 * are interpolated smoothly between successive pulses. No wavelet
 * envelope, no rest periods — just two running phase accumulators.
 *
 * All Android audio concerns are delegated to [AudioEngine].
 */
class ContinuousOutput : AudioOutput(), AudioBlockProvider {

    // ── BaseOutput configuration ───────────────────────────────
    override val type = OutputType.AUDIO_CONTINUOUS
    override val pulseDivider = 1
    override val pulseBatchSize = 1
    override val sendSilenceWhenMuted = true
    override var minFrequencyLimit: Int = 500
    override var maxFrequencyLimit: Int = 1000
    override var ready = true

    // ── Settings ───────────────────────────────────────────────
    private val _settings = MutableStateFlow(ContinuousSettings())
    val settings: StateFlow<ContinuousSettings> = _settings.asStateFlow()

    fun updateSettings(newSettings: ContinuousSettings) {
        _settings.value = newSettings
        minFrequencyLimit = newSettings.minFrequencyLimit
        maxFrequencyLimit = newSettings.maxFrequencyLimit
    }

    override fun getSettingsJson(): String {
        return Json.encodeToString(settings.value)
    }

    override fun applySettings(json: String) {
        try {
            val settings = Json.decodeFromString<ContinuousSettings>(json)
            updateSettings(settings)
        } catch (e: Exception) {
            HLog.e("ContinuousOutput", "Failed to parse settings", e)
        }
    }

    override val settingsUI: (@Composable () -> Unit) = {
        val settings by settings.collectAsStateWithLifecycle()

        ContinuousSettingsContent(
            settings = settings,
            onSettingsChange = { newSettings ->
                updateSettings(newSettings)
            },
            onSave = { OutputManager.saveState() },
            onResetSettings = {
                updateSettings(ContinuousSettings())
                OutputManager.saveState()
            }
        )
    }

    // ── Pulse input state (written on control thread, read on audio thread) ──

    private val pulseQueue = CircularBuffer<OutputPulse>(capacity = pulseBatchSize + 2)
    private var lastPulse = OutputPulse()

    // ── Algorithmic state (audio thread only, reset in onReset) ──

    private var phaseA: Double = 0.0
    private var phaseB: Double = 0.0

    // ── BaseOutput lifecycle → delegate to engine ──────────────

    override fun start() {
        reset()
    }

    // ── Pulse input (control thread) ───────────────────────────

    override fun sendPulses(pulses: List<OutputPulse>) {
        pulseQueue.addAll(pulses, overwrite = true)
    }

    // ── AudioBlockProvider (audio thread) ──────────────────────

    private fun reset() {
        //phaseA = 0.0
        //phaseB = 0.0
        //lastPulse = OutputPulse()
        pulseQueue.clear()
    }

    override fun onFillBlock(buffer: FloatArray, frameCount: Int) {
        if (frameCount == 0) return

        //val bufferSize = pulseQueue.size
        //Log.d("continuous", "Buffer size: $bufferSize")

        // Pull the next pulse from the queue; fall back to the
        // previous value if the queue is empty (jitter absorption).
        val startPulse: OutputPulse = lastPulse
        val endPulse: OutputPulse = pulseQueue.removeFirstOrNull() ?: lastPulse
        lastPulse = endPulse

        val waveShape = _settings.value.waveShape
        val fullVolume = _settings.value.fullVolume

        val sr = OutputManager.sampleRate
        val twoPiOverSampleRate = 2.0 * PI / sr
        val invFrameCount = if (frameCount > 1) 1f / (frameCount - 1) else 0f

        val scaledPowerA = if (fullVolume) 1.0 else endPulse.channelAPower / 200.0
        val scaledPowerB = if (fullVolume) 1.0 else endPulse.channelBPower / 200.0
        val powerA = scaledPowerA.coerceIn(0.0, 1.0)
        val powerB = scaledPowerB.coerceIn(0.0, 1.0)

        var idx = 0
        for (frame in 0 until frameCount) {
            val t = frame * invFrameCount

            val ampA = lerp(startPulse.ampA, endPulse.ampA, t) * powerA
            val ampB = lerp(startPulse.ampB, endPulse.ampB, t) * powerB

            val freqAHz = lerp(startPulse.freqAHz, endPulse.freqAHz, t).toDouble()
            val freqBHz = lerp(startPulse.freqBHz, endPulse.freqBHz, t).toDouble()

            val sampleA = (waveShape.sample(phaseA) * ampA).toFloat()
            val sampleB = (waveShape.sample(phaseB) * ampB).toFloat()

            buffer[idx++] = sampleA
            buffer[idx++] = sampleB

            phaseA += twoPiOverSampleRate * freqAHz
            phaseB += twoPiOverSampleRate * freqBHz
        }

        phaseA %= 2.0 * PI
        phaseB %= 2.0 * PI
    }
}

enum class AudioWaveShape(val displayName: String) {
    SINE("Sine"),
    SQUARE("Square"),
    TRIANGLE("Triangle"),
    TRAPEZOID("Trapezoid");

    /**
     * Generates a sample for the given phase (in radians).
     * Returns a value in [-1, 1].
     */
    fun sample(phase: Double): Double = when (this) {
        SINE      -> sin(phase)
        SQUARE    -> squareWave(phase)
        TRIANGLE  -> triangleWave(phase)
        TRAPEZOID -> trapezoidWave(phase)
    }
}

private const val ONE_OVER_TWO_PI = 1.0 / (2.0 * PI)

/**
 * True mathematical modulo for phase wrapping.
 * Always returns a value in [0.0, 1.0) regardless of the sign of the input phase.
 */
private fun wrapPhase(phase: Double): Double {
    val norm = phase * ONE_OVER_TWO_PI
    return norm - floor(norm)
}

/**
 * Stateless waveform helpers shared by all output types.
 * All functions expect phase in radians and return a value in [-1, 1].
 */

private fun squareWave(phase: Double): Double {
    val t = wrapPhase(phase)
    return if (t < 0.5) 1.0 else -1.0
}

private fun triangleWave(phase: Double): Double {
    val t = wrapPhase(phase)
    // Branchless implementation: maps [0, 1) to [-1, 1, -1]
    return 1.0 - 4.0 * abs(t - 0.5)
}

private fun trapezoidWave(phase: Double, duty: Double = 0.25): Double {
    val tri = triangleWave(phase)
    return when {
        tri > duty  ->  1.0
        tri < -duty -> -1.0
        else        ->  tri / duty
    }
}