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
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

@Serializable
data class WaveletSettings(
    val fullVolume: Boolean = false,
    val carrierShape: AudioWaveShape = AudioWaveShape.SINE,
    val carrierFrequency: Int = 1000,
    val waveletWidth: Int = 5,
    val waveletFade: Float = 0.5f
)

@Composable
fun WaveletSettingsContent(
    settings: WaveletSettings,
    onSettingsChange: (WaveletSettings) -> Unit,
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
            Text(text = "Carrier wave shape", style = MaterialTheme.typography.labelLarge)
            OptionPicker(
                currentValue = settings.carrierShape,
                onValueChange = {
                    onSettingsChange(settings.copy(carrierShape = it))
                    onSave()
                },
                options = AudioWaveShape.entries,
                getText = { it.displayName }
            )
        }

        SliderWithLabel(
            label = "Carrier wave frequency (Hz)",
            value = settings.carrierFrequency.toFloat(),
            onValueChange = {
                onSettingsChange(settings.copy(carrierFrequency = it.roundToInt()))
            },
            onValueChangeFinished = onSave,
            valueRange = 600.0f..2000.0f,
            steps = 139,
            valueDisplay = { it.roundToInt().toString() }
        )

        val waveletWidthRange = 3..10
        SliderWithLabel(
            label = "Wavelet width (in carrier wave cycles)",
            value = settings.waveletWidth.toFloat(),
            onValueChange = { onSettingsChange(settings.copy(waveletWidth = it.roundToInt())) },
            onValueChangeFinished = onSave,
            valueRange = waveletWidthRange.first.toFloat()..waveletWidthRange.last.toFloat(),
            steps = (waveletWidthRange.last - waveletWidthRange.first) - 1,
            valueDisplay = { it.roundToInt().toString() }
        )

        SliderWithLabel(
            label = "Wavelet fade in/out proportion",
            value = settings.waveletFade,
            onValueChange = { onSettingsChange(settings.copy(waveletFade = it)) },
            onValueChangeFinished = onSave,
            valueRange = 0.0f..1.0f,
            steps = 99,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val dutyCycle = ((1.0 / settings.carrierFrequency) * settings.waveletWidth) / 0.01
            val displayDutyCycle = (dutyCycle * 100.0).coerceIn(0.0..100.0).roundToInt()
            Text(
                text = "Estimated duty cycle at 100Hz: $displayDutyCycle%",
                style = MaterialTheme.typography.labelLarge
            )
        }

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
 * Pulse-queue-driven wavelet output.
 *
 * Each pulse triggers a short burst ("wavelet") of a carrier waveform,
 * separated by rest periods whose length is derived from the pulse
 * frequency. Two independent channel state machines (A and B) produce
 * the stereo signal.
 *
 * All Android audio concerns are delegated to [AudioEngine].
 */
class WaveletOutput : AudioOutput(), AudioBlockProvider {

    companion object {
        private const val TAG = "WaveletOutput"
        private const val MINIMUM_REST_SAMPLES = 100
    }

    // ── BaseOutput configuration ───────────────────────────────

    override val type = OutputType.AUDIO_WAVELET
    override val pulseDivider = 1
    override val pulseBatchSize = 1
    override val sendSilenceWhenMuted = true
    override var minFrequencyLimit: Int = 1
    override var maxFrequencyLimit: Int = 200
    override var ready = true
    override val defaultFrequencySubset: ClosedFloatingPointRange<Float>
        get() = 0.05f..0.5f

    // ── Settings ───────────────────────────────────────────────
    private val _settings = MutableStateFlow(WaveletSettings())
    val settings: StateFlow<WaveletSettings> = _settings.asStateFlow()

    fun updateSettings(newSettings: WaveletSettings) {
        _settings.value = newSettings
    }

    override fun getSettingsJson(): String {
        return Json.encodeToString(settings.value)
    }

    override fun applySettings(json: String) {
        try {
            val settings = Json.decodeFromString<WaveletSettings>(json)
            updateSettings(settings)
        } catch (e: Exception) {
            HLog.e(TAG, "Failed to parse settings", e)
        }
    }

    override val settingsUI: (@Composable () -> Unit) = {
        val settings by settings.collectAsStateWithLifecycle()

        WaveletSettingsContent(
            settings = settings,
            onSettingsChange = { newSettings ->
                updateSettings(newSettings)
            },
            onSave = { OutputManager.saveState() },
            onResetSettings = {
                updateSettings(WaveletSettings())
                OutputManager.saveState()
            }
        )
    }

    // ── Pulse input state (written on control thread, read on audio thread) ──

    private val pulseQueue = CircularBuffer<OutputPulse>(capacity = pulseBatchSize + 2)
    private var lastPulse = OutputPulse()

    // ── Algorithmic state (audio thread only, reset in onReset) ──

    private enum class ChannelStage { REST, WAVELET }

    private data class ChannelState(
        var stage: ChannelStage = ChannelStage.REST,
        var stageCounter: Int = 0,
        // Locked for the duration of the current wavelet burst:
        var waveletLength: Int = 0,
        var waveletAmplitude: Float = 0.0f,
        var waveletFade: Double = 0.0,
        var channelPhase: Double = 0.0
    )

    private var channelA = ChannelState()
    private var channelB = ChannelState()

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
        channelA = ChannelState()
        channelB = ChannelState()
        lastPulse = OutputPulse()
        pulseQueue.clear()
    }

    override fun onFillBlock(buffer: FloatArray, frameCount: Int) {
        if (frameCount == 0) return

        val pulse = pulseQueue.removeFirstOrNull()?.also { lastPulse = it } ?: lastPulse

        val currentSettings = _settings.value

        // Carrier oscillator parameters (always latest).
        val carrierShape = currentSettings.carrierShape
        val carrierFrequency = currentSettings.carrierFrequency

        val sr = OutputManager.sampleRate
        val carrierPhaseInc = 2.0 * PI * carrierFrequency / sr

        // Wavelet parameters derived from the pulse (used at burst onset).
        val waveletWidth = currentSettings.waveletWidth
        val waveletFade = currentSettings.waveletFade.toDouble()
        val fullVolume = currentSettings.fullVolume

        val scaledPowerA = if (fullVolume) 1.0f else pulse.channelAPower / 200.0f
        val scaledPowerB = if (fullVolume) 1.0f else pulse.channelBPower / 200.0f

        val powerA = scaledPowerA.coerceIn(0.0f, 1.0f)
        val powerB = scaledPowerB.coerceIn(0.0f, 1.0f)
        val ampA = (pulse.ampA * powerA).coerceIn(0.0f, 1.0f)
        val ampB = (pulse.ampB * powerB).coerceIn(0.0f, 1.0f)

        val waveletLength = if (carrierFrequency > 0.0)
            max(1, (waveletWidth * (sr / carrierFrequency)))
        else 1

        // Rest lengths always reflect the latest pulse (not locked).
        val freqA = pulse.freqAHz.toDouble()
        val freqB = pulse.freqBHz.toDouble()
        val restLengthA = max(MINIMUM_REST_SAMPLES, ((sr / freqA) - waveletLength).roundToInt())
        val restLengthB = max(MINIMUM_REST_SAMPLES, ((sr / freqB) - waveletLength).roundToInt())

        // Generate interleaved stereo samples.
        var idx = 0
        for (frame in 0 until frameCount) {
            buffer[idx++] = generateSample(
                channelA, carrierShape, carrierPhaseInc,
                ampA, waveletLength, restLengthA, waveletFade
            )
            buffer[idx++] = generateSample(
                channelB, carrierShape, carrierPhaseInc,
                ampB, waveletLength, restLengthB, waveletFade
            )
        }
    }

    // ── Per-sample wavelet generation ──────────────────────────

    private fun generateSample(
        ch: ChannelState,
        carrierShape: AudioWaveShape,
        carrierPhaseInc: Double,
        amplitude: Float,
        waveletLengthSamples: Int,
        restLengthSamples: Int,
        waveletFade: Double
    ): Float {
        ch.stageCounter++

        return when (ch.stage) {
            ChannelStage.WAVELET -> {
                val carrier = carrierShape.sample(ch.channelPhase)
                ch.channelPhase += carrierPhaseInc

                // Symmetrical trigonometric envelope (raised cosine / Hann window).
                val n = ch.stageCounter
                val fadeLength = (ch.waveletLength * 0.5 * ch.waveletFade).toInt()
                val sustainEnd = ch.waveletLength - fadeLength

                val envelope = when {
                    fadeLength == 0 -> 1.0
                    n <= fadeLength -> 0.5 * (1.0 - cos(PI * n / fadeLength))
                    n >= sustainEnd -> 0.5 * (1.0 - cos(PI * (ch.waveletLength - n) / fadeLength))
                    else -> 1.0
                }

                if (n >= ch.waveletLength) {
                    ch.stage = ChannelStage.REST
                    ch.stageCounter = 0
                }

                (carrier * ch.waveletAmplitude * envelope).toFloat()
            }

            ChannelStage.REST -> {
                // Rest length is always live — never locked.
                if (ch.stageCounter >= restLengthSamples) {
                    ch.stage = ChannelStage.WAVELET
                    ch.stageCounter = 0
                    // Lock wavelet parameters for this burst.
                    ch.waveletLength = waveletLengthSamples
                    ch.waveletAmplitude = amplitude
                    ch.waveletFade = waveletFade
                    // Start each burst at a random phase to reduce charge imbalances over time.
                    ch.channelPhase = Random.nextDouble(0.0, 2.0 * PI)
                }
                0.0f
            }
        }
    }
}