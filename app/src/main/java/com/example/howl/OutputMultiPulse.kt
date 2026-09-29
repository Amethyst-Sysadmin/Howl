package com.example.howl

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

@Serializable
data class MultiPulseSettings(
    val fullVolume: Boolean = false,
    // Number of pulses per burst at low burst frequencies (@ 1Hz burst frequency)
    val lowFreqPulses: Int = 10,
    // Number of pulses per burst at high burst frequencies (@ 100Hz burst frequency)
    val highFreqPulses: Int = 3,
    // Pulse width in microseconds (excluding intra pulse delay).
    val pulseWidth: Int = 600,
    // Delay between the high and low segments of one single pulse, in microseconds
    val intraPulseDelay: Int = 50,
    // Delay between each pulse in a burst, in microseconds
    val interPulseDelay: Int = 200,
    // true if we should avoid firing pulses on both channels at the same time
    val preventInterference: Boolean = false
)

private data class BurstEstimate(
    val dutyCyclePercent: Int,
    val burstFits: Boolean,
)

private fun estimateBurst(
    burstFrequencyHz: Double,
    minPulses: Int,
    maxPulses: Int,
    pulseWidthUs: Int,
    intraPulseDelayUs: Int,
    interPulseDelayUs: Int,
): BurstEstimate {
    val minBurstGap = MultiPulseOutput.MIN_BURST_GAP_US
    val t = ((burstFrequencyHz - 1.0) / (100.0 - 1.0)).coerceIn(0.0, 1.0)
    val rawPulses = minPulses + (maxPulses - minPulses) * t
    val pulseCount = rawPulses.roundToInt().coerceIn(
        minOf(minPulses, maxPulses),
        maxOf(minPulses, maxPulses),
    )

    val periodUs = 1_000_000.0 / burstFrequencyHz
    val burstDurationUs = pulseCount * (2.0 * pulseWidthUs + intraPulseDelayUs) +
            (pulseCount - 1) * interPulseDelayUs
    val activeTimeUs = pulseCount * 2.0 * pulseWidthUs
    val dutyCycle = (activeTimeUs / periodUs).coerceIn(0.0, 1.0)
    val burstFits = (burstDurationUs + minBurstGap) <= periodUs

    return BurstEstimate(
        dutyCyclePercent = (dutyCycle * 100.0).roundToInt(),
        burstFits = burstFits,
    )
}

@Composable
fun MultiPulseSettingsContent(
    settings: MultiPulseSettings,
    onSettingsChange: (MultiPulseSettings) -> Unit,
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
        val pulseCountRange = 1..15
        SliderWithLabel(
            label = "Number of pulses per burst (at 1Hz)",
            value = settings.lowFreqPulses.toFloat(),
            onValueChange = { onSettingsChange(settings.copy(lowFreqPulses = it.roundToInt())) },
            onValueChangeFinished = onSave,
            valueRange = pulseCountRange.toClosedFloatingPointRange(),
            steps = (pulseCountRange.last - pulseCountRange.first) - 1,
            valueDisplay = { it.roundToInt().toString() }
        )
        SliderWithLabel(
            label = "Number of pulses per burst (at 100Hz)",
            value = settings.highFreqPulses.toFloat(),
            onValueChange = { onSettingsChange(settings.copy(highFreqPulses = it.roundToInt())) },
            onValueChangeFinished = onSave,
            valueRange = pulseCountRange.toClosedFloatingPointRange(),
            steps = (pulseCountRange.last - pulseCountRange.first) - 1,
            valueDisplay = { it.roundToInt().toString() }
        )
        val pulseWidthRange = 250..1000
        SliderWithLabel(
            label = "Pulse width (microseconds)",
            value = settings.pulseWidth.toFloat(),
            onValueChange = { onSettingsChange(settings.copy(pulseWidth = it.roundToInt())) },
            onValueChangeFinished = onSave,
            valueRange = pulseWidthRange.toClosedFloatingPointRange(),
            steps = 74,
            valueDisplay = { it.roundToInt().toString() }
        )
        val interPulseDelayRange = 0..1000
        SliderWithLabel(
            label = "Delay between each pulse in burst (microseconds)",
            value = settings.interPulseDelay.toFloat(),
            onValueChange = { onSettingsChange(settings.copy(interPulseDelay = it.roundToInt())) },
            onValueChangeFinished = onSave,
            valueRange = interPulseDelayRange.toClosedFloatingPointRange(),
            steps = 99,
            valueDisplay = { it.roundToInt().toString() }
        )
        val intraPulseDelayRange = 0..100
        SliderWithLabel(
            label = "Intra pulse delay (microseconds)",
            value = settings.intraPulseDelay.toFloat(),
            onValueChange = { onSettingsChange(settings.copy(intraPulseDelay = it.roundToInt())) },
            onValueChangeFinished = onSave,
            valueRange = intraPulseDelayRange.toClosedFloatingPointRange(),
            steps = 9,
            valueDisplay = { it.roundToInt().toString() }
        )
        SwitchWithLabel(
            label = "Prevent interference",
            checked = settings.preventInterference,
            onCheckedChange = {
                onSettingsChange(settings.copy(preventInterference = it))
                onSave()
            }
        )

        Spacer(modifier = Modifier.height(8.dp))
        listOf(10, 20, 50, 100).forEach { freqHz ->
            val estimate = estimateBurst(
                burstFrequencyHz = freqHz.toDouble(),
                minPulses = settings.lowFreqPulses,
                maxPulses = settings.highFreqPulses,
                pulseWidthUs = settings.pulseWidth,
                intraPulseDelayUs = settings.intraPulseDelay,
                interPulseDelayUs = settings.interPulseDelay,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Estimated duty cycle @ $freqHz Hz: ${estimate.dutyCyclePercent}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (estimate.burstFits) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
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
 * Output that generates bursts of symmetric square-wave pulses.
 *
 * Each burst contains N charge-balanced pulses (equal positive and negative area).
 * Burst timing is derived from the pattern's normalised frequency mapped through
 * the app's current frequency range. Pulse count interpolates between the
 * user-configured low-frequency and high-frequency pulse counts, so either
 * may be greater than the other.
 *
 * All Android audio concerns are delegated to [AudioEngine].
 */
class MultiPulseOutput : AudioOutput(), AudioBlockProvider {
    override val type = OutputType.AUDIO_MULTIPULSE
    override val pulseDivider = 1
    override val pulseBatchSize = 1
    override val sendSilenceWhenMuted = true
    override var minFrequencyLimit: Int = 1
    override var maxFrequencyLimit: Int = 100
    override var ready = true
    override val defaultFrequencySubset: ClosedFloatingPointRange<Float>
        get() = 0.1f..1.0f

    companion object {
        const val TAG = "MultiPulseOutput"

        /** Minimum silence between the end of one burst and the start of the next (μs). */
        const val MIN_BURST_GAP_US = 2000

        /** Minimum silence between a burst ending on one channel and starting on the other
         *  channel (μs). Only enforced when prevent interference is true.*/
        const val INTERFERENCE_GUARD_GAP_US = 500

        /** Reference frequencies for pulse-count interpolation (Hz). */
        private const val FREQ_FOR_LOW_FREQ_PULSES = 1.0
        private const val FREQ_FOR_HIGH_FREQ_PULSES = 100.0
    }

    // ─── Latest pattern state (written by main thread, read by audio thread) ─

    private val pulseQueue = CircularBuffer<OutputPulse>(capacity = pulseBatchSize + 2)
    private var lastPulse = OutputPulse()

    // ─── Per-channel burst state machine ─────────────────────────────────────

    private enum class BurstPhase {
        IDLE,
        FIRST_HALF,     // First half of pulse (polarity-dependent sign)
        INTRA_DELAY,    // Silence between the two halves of one pulse
        SECOND_HALF,    // Second half of pulse (opposite sign → charge balanced)
        INTER_DELAY     // Silence between consecutive pulses in a burst
    }

    private class ChannelState {
        var phase: BurstPhase = BurstPhase.IDLE
        var phaseCounter: Int = 0           // Samples elapsed in current phase
        var pulsesRemaining: Int = 0        // Pulses still to emit in this burst

        // Timing counters
        var samplesSinceBurstStart: Int = 0 // Always incrementing (all phases)
        var samplesSinceBurstEnd: Int = 0   // Incrementing only while IDLE

        // Parameters latched at burst start (immutable for duration of burst)
        var polarity: Int = 1               // +1 = high→low, -1 = low→high
        var amplitude: Double = 0.0         // Pre-scaled 0..1
        var pulseWidthSamples: Int = 0
        var intraDelaySamples: Int = 0
        var interDelaySamples: Int = 0

        fun reset() {
            phase = BurstPhase.IDLE
            phaseCounter = 0
            pulsesRemaining = 0
            samplesSinceBurstStart = 0
            samplesSinceBurstEnd = 0
            polarity = 1
            amplitude = 0.0
        }
    }

    private val chA = ChannelState()
    private val chB = ChannelState()

    // ─── Cross-channel interference prevention (shared state) ────────────────

    /** True while either channel is mid-burst. */
    private var anyBurstActive: Boolean = false

    /** Samples elapsed since the last burst ended on *either* channel. */
    private var samplesSinceAnyBurstEnd: Int = 0

    /** Which channel started a burst most recently (0 = A, 1 = B, -1 = neither). */
    private var lastFiredChannel: Int = -1

    private val _settings = MutableStateFlow(MultiPulseSettings())
    val settings: StateFlow<MultiPulseSettings> = _settings.asStateFlow()

    fun updateSettings(newSettings: MultiPulseSettings) {
        _settings.value = newSettings
    }

    override fun getSettingsJson(): String {
        return Json.encodeToString(settings.value)
    }

    override fun applySettings(json: String) {
        try {
            val settings = Json.decodeFromString<MultiPulseSettings>(json)
            _settings.value = settings
        } catch (e: Exception) {
            HLog.e(TAG, "Failed to parse settings", e)
        }
    }

    override val settingsUI: (@Composable () -> Unit) = {
        val settings by settings.collectAsStateWithLifecycle()

        MultiPulseSettingsContent(
            settings = settings,
            onSettingsChange = { newSettings ->
                updateSettings(newSettings)
            },
            onSave = { OutputManager.saveState() },
            onResetSettings = {
                updateSettings(MultiPulseSettings())
                OutputManager.saveState()
            }
        )
    }

    // ─── Lifecycle → delegate to engine ──────────────────────────────────────

    override fun start() {
        reset()
    }

    // ─── Pulse delivery (main thread → audio thread) ─────────────────────────

    override fun sendPulses(pulses: List<OutputPulse>) {
        pulseQueue.addAll(pulses, overwrite = true)
    }

    // ─── AudioBlockProvider (audio thread) ───────────────────────────────────

    private fun reset() {
        pulseQueue.clear()
        lastPulse = OutputPulse()
        chA.reset()
        chB.reset()
        anyBurstActive = false
        samplesSinceAnyBurstEnd = 0
        lastFiredChannel = -1
    }

    /**
     * Fill [buffer] with interleaved stereo samples (L, R, L, R, …).
     * Called from the audio producer thread with [frameCount] frames.
     */
    override fun onFillBlock(buffer: FloatArray, frameCount: Int) {
        if (frameCount == 0) return

        val pulse = pulseQueue.removeFirstOrNull()?.also { lastPulse = it } ?: lastPulse

        //Log.d(TAG, "> $pulse")

        val sr = OutputManager.sampleRate
        val fullVolume = _settings.value.fullVolume
        val burstFreqA = pulse.freqAHz.toDouble()
        val burstFreqB = pulse.freqBHz.toDouble()
        val scaledPowerA = if (fullVolume) 1.0 else pulse.channelAPower / 200.0
        val scaledPowerB = if (fullVolume) 1.0 else pulse.channelBPower / 200.0
        val powerA = scaledPowerA.coerceIn(0.0, 1.0)
        val powerB = scaledPowerB.coerceIn(0.0, 1.0)

        val periodA = if (burstFreqA > 0.0) (sr / burstFreqA).roundToInt() else Int.MAX_VALUE
        val periodB = if (burstFreqB > 0.0) (sr / burstFreqB).roundToInt() else Int.MAX_VALUE

        val minGapSamples = (MIN_BURST_GAP_US.toLong() * sr / 1_000_000L).toInt()
        val interChannelGapSamples =
            (INTERFERENCE_GUARD_GAP_US.toLong() * sr / 1_000_000L).toInt()

        val ampA = (pulse.ampA.toDouble() * powerA).coerceIn(0.0, 1.0)
        val ampB = (pulse.ampB.toDouble() * powerB).coerceIn(0.0, 1.0)

        val preventInterference = _settings.value.preventInterference

        var idx = 0
        for (frame in 0 until frameCount) {

            // Advance the shared inter-burst gap counter
            if (!anyBurstActive) {
                samplesSinceAnyBurstEnd++
            }

            if (preventInterference && lastFiredChannel == 0) {
                val sampleB = advanceChannel(chB, periodB, minGapSamples, ampB, burstFreqB,
                    interChannelGapSamples, preventInterference, channelId = 1)
                val sampleA = advanceChannel(chA, periodA, minGapSamples, ampA, burstFreqA,
                    interChannelGapSamples, preventInterference, channelId = 0)
                buffer[idx++] = sampleA
                buffer[idx++] = sampleB
            } else {
                val sampleA = advanceChannel(chA, periodA, minGapSamples, ampA, burstFreqA,
                    interChannelGapSamples, preventInterference, channelId = 0)
                val sampleB = advanceChannel(chB, periodB, minGapSamples, ampB, burstFreqB,
                    interChannelGapSamples, preventInterference, channelId = 1)
                buffer[idx++] = sampleA
                buffer[idx++] = sampleB
            }
        }
    }

    // ─── Core state machine ──────────────────────────────────────────────────

    /**
     * Advance one channel by a single sample and return the output value.
     * No allocations, no division in steady state.
     */
    private inline fun advanceChannel(
        ch: ChannelState,
        burstPeriodSamples: Int,
        minGapSamples: Int,
        amplitude: Double,
        burstFreqHz: Double,
        interChannelGapSamples: Int,
        preventInterference: Boolean,
        channelId: Int
    ): Float {
        // Period counter runs in every phase so that burst duration is
        // included in the onset-to-onset interval.
        ch.samplesSinceBurstStart++

        return when (ch.phase) {
            BurstPhase.IDLE -> {
                ch.samplesSinceBurstEnd++

                val periodMet = ch.samplesSinceBurstStart >= burstPeriodSamples
                val ownGapMet = ch.samplesSinceBurstEnd >= minGapSamples
                val crossChannelOk = !preventInterference ||
                        (!anyBurstActive && samplesSinceAnyBurstEnd >= interChannelGapSamples)

                if (periodMet && ownGapMet && crossChannelOk) {
                    startBurst(ch, amplitude, burstFreqHz, channelId)
                    anyBurstActive = true
                    ch.phase = BurstPhase.FIRST_HALF
                    ch.phaseCounter = 1
                    (ch.polarity * ch.amplitude).toFloat()
                } else {
                    0f
                }
            }

            BurstPhase.FIRST_HALF -> {
                ch.phaseCounter++
                if (ch.phaseCounter > ch.pulseWidthSamples) {
                    ch.phase = BurstPhase.INTRA_DELAY
                    ch.phaseCounter = 1
                    0f
                } else {
                    (ch.polarity * ch.amplitude).toFloat()
                }
            }

            BurstPhase.INTRA_DELAY -> {
                ch.phaseCounter++
                if (ch.phaseCounter > ch.intraDelaySamples) {
                    ch.phase = BurstPhase.SECOND_HALF
                    ch.phaseCounter = 1
                    (-ch.polarity * ch.amplitude).toFloat()
                } else {
                    0f
                }
            }

            BurstPhase.SECOND_HALF -> {
                ch.phaseCounter++
                if (ch.phaseCounter > ch.pulseWidthSamples) {
                    ch.pulsesRemaining--
                    if (ch.pulsesRemaining > 0) {
                        ch.phase = BurstPhase.INTER_DELAY
                        ch.phaseCounter = 1
                    } else {
                        ch.phase = BurstPhase.IDLE
                        ch.phaseCounter = 0
                        ch.samplesSinceBurstEnd = 0
                        anyBurstActive = false
                        samplesSinceAnyBurstEnd = 0
                    }
                    0f
                } else {
                    (-ch.polarity * ch.amplitude).toFloat()
                }
            }

            BurstPhase.INTER_DELAY -> {
                ch.phaseCounter++
                if (ch.phaseCounter > ch.interDelaySamples) {
                    ch.phase = BurstPhase.FIRST_HALF
                    ch.phaseCounter = 1
                    (ch.polarity * ch.amplitude).toFloat()
                } else {
                    0f
                }
            }
        }
    }

    /**
     * Latch all burst parameters. Called once at burst start — the burst then
     * runs entirely from these cached values, immune to concurrent updates.
     */
    private fun startBurst(
        ch: ChannelState,
        amplitude: Double,
        burstFreqHz: Double,
        channelId: Int
    ) {
        val sr = OutputManager.sampleRate
        val currentSettings = _settings.value

        val lowFreqPulses = currentSettings.lowFreqPulses
        val highFreqPulses = currentSettings.highFreqPulses

        val t = ((burstFreqHz - FREQ_FOR_LOW_FREQ_PULSES) /
                (FREQ_FOR_HIGH_FREQ_PULSES - FREQ_FOR_LOW_FREQ_PULSES))
            .coerceIn(0.0, 1.0)

        val rawCount = lowFreqPulses + (highFreqPulses - lowFreqPulses) * t

        val floor = rawCount.toInt()
        val frac = rawCount - floor
        val lo = minOf(lowFreqPulses, highFreqPulses)
        val hi = maxOf(lowFreqPulses, highFreqPulses)
        val pulseCount = (if (Random.nextDouble() < frac) floor + 1 else floor)
            .coerceIn(lo, hi)

        val pulseWidthUs = currentSettings.pulseWidth
        val intraDelayUs = currentSettings.intraPulseDelay
        val interDelayUs = currentSettings.interPulseDelay

        val pulseWidthSamples = max(1, (pulseWidthUs.toLong() * sr / 1_000_000L).toInt())
        val intraDelaySamples = (intraDelayUs.toLong() * sr / 1_000_000L).toInt()
        val interDelaySamples = (interDelayUs.toLong() * sr / 1_000_000L).toInt()

        val polarity = if (Random.nextBoolean()) 1 else -1

        ch.pulsesRemaining = pulseCount
        ch.polarity = polarity
        ch.amplitude = amplitude
        ch.pulseWidthSamples = pulseWidthSamples
        ch.intraDelaySamples = intraDelaySamples
        ch.interDelaySamples = interDelaySamples
        ch.samplesSinceBurstStart = 0

        lastFiredChannel = channelId
    }
}