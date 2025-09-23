package com.example.howl

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.howl.ui.theme.HowlTheme
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.DurationUnit
import kotlin.time.TimeSource.Monotonic.markNow
import java.util.Locale
import kotlin.math.pow
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.math.roundToInt

fun formatTime(position: Double): String {
    val minutes = (position / 60).toInt()
    val seconds = position % 60
    return String.format(Locale.US, "%02d:%04.1f", minutes, seconds)
}

data class Pulse (
    val ampA: Float = 0.0f,
    val ampB: Float = 0.0f,
    val freqA: Float = 0.0f,
    val freqB: Float = 0.0f
)

interface PulseSource {
    val displayName: String
    val duration: Double?
    val isFinite: Boolean
    val shouldLoop: Boolean
    val readyToPlay: Boolean
    val isRemote: Boolean
    val remoteLatency: Double
    fun getPulseAtTime(time: Double): Pulse
    fun updateState(currentTime: Double)
}

interface Output {
    val timerDelay: Double
    val pulseBatchSize: Int
    val sendSilenceWhenMuted: Boolean
    var allowedFrequencyRange: IntRange
    var defaultFrequencyRange: IntRange
    var ready: Boolean
    var latency: Double

    val pulseTime: Double
        get() = timerDelay / pulseBatchSize
    fun initialise()
    fun end()
    fun start()
    fun stop()
    fun sendPulses(channelAPower: Int,
                  channelBPower: Int,
                  minFrequency: Double,
                  maxFrequency: Double,
                  pulses: List<Pulse>
    )

    fun handleBluetoothEvent(event: BluetoothEvent)
}

object Player {
    private var contextRef: WeakReference<Context>? = null
    private var autoIncrementPowerCounterA: Int = 0
    private var autoIncrementPowerCounterB: Int = 0
    var output: Output = AudioOutput()
    private val noiseGenerator = NoiseGenerator()

    fun initialise(context: Context) {
        contextRef = WeakReference(context)
    }
    fun switchOutput(outputType: OutputType) {
        stopPlayer()
        output.end()
        DataRepository.setChannelAPower(0)
        DataRepository.setChannelBPower(0)
        output = when (outputType) {
            OutputType.COYOTE3 -> Coyote3Output()
            OutputType.AUDIO -> AudioOutput()
        }
        output.initialise()

        val frequencyRangeSubset = output.defaultFrequencyRange.toProportionOf(output.allowedFrequencyRange)
        val minFreq = output.allowedFrequencyRange.lerp(frequencyRangeSubset.start.toDouble())
        val maxFreq = output.allowedFrequencyRange.lerp(frequencyRangeSubset.endInclusive.toDouble())
        DataRepository.setMainOptionsState(DataRepository.mainOptionsState.value.copy(
            frequencyRange = output.allowedFrequencyRange,
            frequencyRangeSelectedSubset = frequencyRangeSubset,
            minFrequency = minFreq,
            maxFrequency = maxFreq
        ))
        DataRepository.setOutputState(DataRepository.outputState.value.copy(outputType = outputType))
    }
    fun getNextTimes(time: Double): List<Double> {
        val syncFineTune = DataRepository.playerState.value.syncFineTune
        val playbackSpeed = DataRepository.playerAdvancedControlsState.value.playbackSpeed
        val isRemote = DataRepository.playerState.value.activePulseSource?.isRemote ?: false
        val latency = if (isRemote) DataRepository.playerState.value.activePulseSource?.remoteLatency ?: 0.0 else 0.0
        val outputLatency = output.latency

        // Convert real-world offsets to media time
        val adjustedSyncFineTune = syncFineTune * playbackSpeed
        val adjustedLatency = latency * playbackSpeed
        val adjustedPulseTime = output.pulseTime * playbackSpeed
        //val adjustedOutputLatency = outputLatency * playbackSpeed

        return List(output.pulseBatchSize) { index ->
            //(time + adjustedPulseTime * index.toDouble() + adjustedSyncFineTune + adjustedLatency + adjustedOutputLatency).coerceAtLeast(0.0)
            (time + adjustedPulseTime * index.toDouble() + adjustedSyncFineTune + adjustedLatency).coerceAtLeast(0.0)
        }
    }
    fun updatePlayerState(newPlayerState: DataRepository.PlayerState) {
        DataRepository.setPlayerState(newPlayerState)
    }
    fun updateAdvancedControlsState(newAdvancedControlsState: DataRepository.PlayerAdvancedControlsState) {
        DataRepository.setPlayerAdvancedControlsState(newAdvancedControlsState)
    }
    private fun applyNoise(time: Double, pulse: Pulse, ampNoiseAmount: Double, ampNoiseSpeed: Double, freqNoiseAmount: Double, freqNoiseSpeed: Double): Pulse {
        val noiseRotationSpeed = 0.05
        val noiseRadius = 0.3
        val (ampNoiseA, ampNoiseB) = noiseGenerator.getNoise(
            time = time * ampNoiseSpeed,
            rotation = time * noiseRotationSpeed,
            radius = noiseRadius,
            axis = 0,
            shiftResult = false
        )
        val (freqNoiseA, freqNoiseB) = noiseGenerator.getNoise(
            time = time * freqNoiseSpeed,
            rotation = time * noiseRotationSpeed,
            radius = noiseRadius,
            axis = 1,
            shiftResult = false
        )
        return Pulse(
            ampA = (pulse.ampA + ampNoiseA * ampNoiseAmount).toFloat().coerceIn(0.0f..1.0f),
            ampB = (pulse.ampB + ampNoiseB * ampNoiseAmount).toFloat().coerceIn(0.0f..1.0f),
            freqA = (pulse.freqA + freqNoiseA * freqNoiseAmount).toFloat().coerceIn(0.0f..1.0f),
            freqB = (pulse.freqB + freqNoiseB * freqNoiseAmount).toFloat().coerceIn(0.0f..1.0f),
        )
    }
    private fun applyDeveloperOptions(pulse: Pulse): Pulse {
        val devOpts = DataRepository.developerOptionsState.value
        return pulse.copy(
            ampA = (pulse.ampA.pow(devOpts.developerAmplitudeExponent) * devOpts.developerAmplitudeGain)
                .coerceIn(0f, 1f),
            ampB = (pulse.ampB.pow(devOpts.developerAmplitudeExponent) * devOpts.developerAmplitudeGain)
                .coerceIn(0f, 1f),
            freqA = (pulse.freqA.pow(devOpts.developerFrequencyExponent) * devOpts.developerFrequencyGain
                    + devOpts.developerFrequencyAdjustA)
                .coerceIn(0f, 1f),
            freqB = (pulse.freqB.pow(devOpts.developerFrequencyExponent) * devOpts.developerFrequencyGain
                    + devOpts.developerFrequencyAdjustB)
                .coerceIn(0f, 1f)
        )
    }
    fun applySpecialEffects(time: Double, pulse: Pulse, specialEffectsState: DataRepository.PlayerSpecialEffectsState): Pulse {
        var modifiedPulse = pulse.copy(
            freqA = if (specialEffectsState.frequencyInversionA) 1.0f - pulse.freqA else pulse.freqA,
            freqB = if (specialEffectsState.frequencyInversionB) 1.0f - pulse.freqB else pulse.freqB,
        )

        modifiedPulse = modifiedPulse.copy(
            freqA = calculateFeelAdjustment(
                frequency = modifiedPulse.freqA,
                exponent = specialEffectsState.frequencyFeel
            ),
            freqB = calculateFeelAdjustment(
                frequency = modifiedPulse.freqB,
                exponent = specialEffectsState.frequencyFeel
            )
        )

        if (showDeveloperOptions)
            modifiedPulse = applyDeveloperOptions(modifiedPulse)

        if (specialEffectsState.amplitudeNoiseAmount > 0.0 || specialEffectsState.frequencyNoiseAmount > 0.0) {
            modifiedPulse = applyNoise(
                time = time,
                pulse = modifiedPulse,
                ampNoiseAmount = specialEffectsState.amplitudeNoiseAmount.toDouble(),
                ampNoiseSpeed = specialEffectsState.amplitudeNoiseSpeed.toDouble(),
                freqNoiseAmount = specialEffectsState.frequencyNoiseAmount.toDouble(),
                freqNoiseSpeed = specialEffectsState.frequencyNoiseSpeed.toDouble()
            )
        }

        modifiedPulse = modifiedPulse.copy(
            ampA = (pulse.ampA * specialEffectsState.scaleAmplitudeA).coerceAtMost(1.0f),
            ampB = (pulse.ampB * specialEffectsState.scaleAmplitudeB).coerceAtMost(1.0f)
        )

        return modifiedPulse
    }
    fun applyPostProcessing(time: Double, pulse: Pulse): Pulse {
        val mainOptionsState = DataRepository.mainOptionsState.value
        val specialEffectsState = DataRepository.playerSpecialEffectsState.value
        val swapChannels = mainOptionsState.swapChannels

        val processedPulse = if (specialEffectsState.specialEffectsEnabled) {
            applySpecialEffects(time, pulse, specialEffectsState)
        } else {
            pulse
        }
        
        return if (swapChannels) {
            processedPulse.copy(
                ampA = processedPulse.ampB,
                ampB = processedPulse.ampA,
                freqA = processedPulse.freqB,
                freqB = processedPulse.freqA
            )
        } else {
            processedPulse
        }
    }
    fun getPulseAtTime(time: Double): Pulse {
        val activePulseSource = DataRepository.playerState.value.activePulseSource
        val pulse = activePulseSource?.getPulseAtTime(time) ?: Pulse()
        return applyPostProcessing(time, pulse)
    }
    fun stopPlayer() {
        updatePlayerState(DataRepository.playerState.value.copy(isPlaying = false))
        output.stop()
    }
    fun startPlayer(from: Double? = null) {
        val playerState = DataRepository.playerState.value
        val playFrom = from ?: playerState.currentPosition
        if(playerState.activePulseSource?.readyToPlay != true)
            return
        updatePlayerState(playerState.copy(isPlaying = true, startTime = markNow(), startPosition = playFrom))
        output.start()

        val context = contextRef?.get() ?: return
        val serviceIntent = Intent(context, PlayerService::class.java)
        context.startService(serviceIntent)
    }
    fun loadFile(uri: Uri, context: Context) {
        DataRepository.setPlayerPosition(0.0)
        try {
            val pulseSource = HWLPulseSource()
            pulseSource.open(uri, context)
            switchPulseSource(pulseSource)
            return
        }
        catch (_: BadFileException) { }
        try {
            val pulseSource = FunscriptPulseSource()
            pulseSource.open(uri, context)
            switchPulseSource(pulseSource)
            return
        }
        catch (_: BadFileException) { }
        switchPulseSource(null)
    }
    fun switchPulseSource(source: PulseSource?) {
        val playerState = DataRepository.playerState.value
        if (source == null || playerState.activePulseSource != source) {
            updatePlayerState(DataRepository.PlayerState(activePulseSource = source))
        }
    }
    fun getCurrentPosition(): Double {
        val playerState = DataRepository.playerState.value
        val playbackSpeed = DataRepository.playerAdvancedControlsState.value.playbackSpeed
        val elapsed = playerState.startTime?.elapsedNow()?.toDouble(DurationUnit.SECONDS) ?: 0.0
        return playerState.startPosition + elapsed * playbackSpeed
    }
    fun handlePowerAutoIncrement() {
        val mainOptionsState = DataRepository.mainOptionsState.value
        val miscOptionsState = DataRepository.miscOptionsState.value

        if (mainOptionsState.autoIncreasePower && !mainOptionsState.globalMute) {
            if (mainOptionsState.channelAPower > 0)
                autoIncrementPowerCounterA++
            if (mainOptionsState.channelBPower > 0)
                autoIncrementPowerCounterB++

            val autoIncrementDelayA =
                miscOptionsState.powerAutoIncrementDelayA
            val autoIncrementDelayB =
                miscOptionsState.powerAutoIncrementDelayB
            //Log.d("Player", "Auto increment calculation $autoIncrementPowerCounterA / $autoIncrementDelayA      $autoIncrementPowerCounterB / $autoIncrementDelayB")
            if (autoIncrementPowerCounterA >= autoIncrementDelayA * 10) {
                autoIncrementPowerCounterA = 0
                DataRepository.setChannelAPower(mainOptionsState.channelAPower + 1)
            }
            if (autoIncrementPowerCounterB >= autoIncrementDelayB * 10) {
                autoIncrementPowerCounterB = 0
                DataRepository.setChannelBPower(mainOptionsState.channelBPower + 1)
            }
        }
    }
    fun seek(position: Double? = null) {
        // May also be called with null position to resync the player, for example when changing
        // playback speed
        val playerState = DataRepository.playerState.value
        val finite = playerState.activePulseSource?.isFinite ?: false
        val pos = if (!finite || position == null) playerState.currentPosition else position
        updatePlayerState(
            playerState.copy(
                currentPosition = pos,
                startTime = markNow(),
                startPosition = pos
            )
        )
    }
    fun setCurrentPosition(position: Double) {
        DataRepository.setPlayerPosition(position)
    }
}

class PlayerViewModel() : ViewModel() {
    val playerState: StateFlow<DataRepository.PlayerState> = DataRepository.playerState
    val advancedControlsState: StateFlow<DataRepository.PlayerAdvancedControlsState> =
        DataRepository.playerAdvancedControlsState
    val specialEffectsState: StateFlow<DataRepository.PlayerSpecialEffectsState> =
        DataRepository.playerSpecialEffectsState
    val developerOptionsState: StateFlow<DataRepository.DeveloperOptionsState> = DataRepository.developerOptionsState

    fun updatePlayerState(newPlayerState: DataRepository.PlayerState) {
        DataRepository.setPlayerState(newPlayerState)
    }

    fun updateAdvancedControlsState(newAdvancedControlsState: DataRepository.PlayerAdvancedControlsState) {
        DataRepository.setPlayerAdvancedControlsState(newAdvancedControlsState)
    }

    fun updateSpecialEffectsState(newSpecialEffectsState: DataRepository.PlayerSpecialEffectsState) {
        DataRepository.setPlayerSpecialEffectsState(newSpecialEffectsState)
    }

    fun updateDeveloperOptionsState(newDeveloperOptionsState: DataRepository.DeveloperOptionsState) {
        DataRepository.setDeveloperOptionsState(newDeveloperOptionsState)
    }

    fun stopPlayer() {
        Player.stopPlayer()
    }

    fun startPlayer(from: Double? = null) {
        Player.startPlayer(from)
    }

    fun seek(position: Double? = null) {
        Player.seek(position)
    }

    fun loadFile(uri: Uri, context: Context) {
        Player.loadFile(uri, context)
    }

    fun updateFunscriptLatency(latency: Float) {
        updateAdvancedControlsState(advancedControlsState.value.copy(funscriptRemoteLatency = latency))
        if (playerState.value.activePulseSource is FunscriptPulseSource) {
            val funscriptPulseSource = playerState.value.activePulseSource as FunscriptPulseSource
            if (funscriptPulseSource.isRemote)
                funscriptPulseSource.remoteLatency = latency.toDouble()
        }
    }

    fun saveSettings() {
        viewModelScope.launch {
            DataRepository.saveSettings()
        }
    }
}

@Composable
fun AdvancedControlsPanel(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val advancedControlsState by viewModel.advancedControlsState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = "Player settings", style = MaterialTheme.typography.headlineSmall)
        }
        SwitchWithLabel(
            label = "Show sync fine tune",
            checked = advancedControlsState.showSyncFineTune,
            onCheckedChange = {
                viewModel.updateAdvancedControlsState(
                    advancedControlsState.copy(
                        showSyncFineTune = it
                    )
                )
                viewModel.saveSettings()
            }
        )
        SliderWithLabel(
            label = "Playback speed",
            value = advancedControlsState.playbackSpeed,
            onValueChange = {
                viewModel.updateAdvancedControlsState(advancedControlsState.copy(playbackSpeed = it))
                viewModel.seek()
            },
            onValueChangeFinished = { viewModel.saveSettings() },
            valueRange = 0.25f..4.0f,
            steps = 14,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) }
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = "Funscript settings", style = MaterialTheme.typography.headlineSmall)
        }
        SliderWithLabel(
            label = "Scaling (boosts slower movements)",
            value = advancedControlsState.funscriptVolume,
            onValueChange = {viewModel.updateAdvancedControlsState(advancedControlsState.copy(funscriptVolume = it))},
            onValueChangeFinished = { viewModel.saveSettings() },
            valueRange = 0f..1.0f,
            steps = 99,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) }
        )
        SliderWithLabel(
            label = "Positional effect strength",
            value = advancedControlsState.funscriptPositionalEffectStrength,
            onValueChange = {viewModel.updateAdvancedControlsState(advancedControlsState.copy(funscriptPositionalEffectStrength = it))},
            onValueChangeFinished = { viewModel.saveSettings() },
            valueRange = 0f..1.0f,
            steps = 99,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) }
        )
        SliderWithLabel(
            label = "Remote funscript latency (seconds)",
            value = advancedControlsState.funscriptRemoteLatency,
            onValueChange = { viewModel.updateFunscriptLatency(it) },
            onValueChangeFinished = { viewModel.saveSettings() },
            valueRange = 0.0f..1.0f,
            steps = 99,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Amplitude algorithm", style = MaterialTheme.typography.labelLarge)
            OptionPicker(
                currentValue = advancedControlsState.funscriptAmplitudeAlgorithm,
                onValueChange = {
                    viewModel.updateAdvancedControlsState(
                        advancedControlsState.copy(
                            funscriptAmplitudeAlgorithm = it
                        )
                    )
                    viewModel.saveSettings()
                },
                options = AmplitudeAlgorithmType.entries,
                getText = { it.displayName }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Frequency algorithm", style = MaterialTheme.typography.labelLarge)
            OptionPicker(
                currentValue = advancedControlsState.funscriptFrequencyAlgorithm,
                onValueChange = {
                    viewModel.updateAdvancedControlsState(
                        advancedControlsState.copy(
                            funscriptFrequencyAlgorithm = it
                        )
                    )
                    viewModel.saveSettings()
                },
                options = FrequencyAlgorithmType.entries,
                getText = { it.displayName }
            )
        }
        if (advancedControlsState.funscriptFrequencyAlgorithm == FrequencyAlgorithmType.POSITION) {
            SliderWithLabel(
                label = "A/B frequency time offset",
                value = advancedControlsState.funscriptFrequencyTimeOffset,
                onValueChange = {
                    viewModel.updateAdvancedControlsState(
                        advancedControlsState.copy(
                            funscriptFrequencyTimeOffset = it
                        )
                    )
                },
                onValueChangeFinished = { viewModel.saveSettings() },
                valueRange = 0f..1.0f,
                steps = 99,
                valueDisplay = { String.format(Locale.US, "%03.2f", it) }
            )
        }
        if (advancedControlsState.funscriptFrequencyAlgorithm != FrequencyAlgorithmType.POSITION) {
            SliderWithLabel(
                label = "Frequency vary speed",
                value = advancedControlsState.funscriptFrequencyVarySpeed,
                onValueChange = {
                    viewModel.updateAdvancedControlsState(
                        advancedControlsState.copy(
                            funscriptFrequencyVarySpeed = it
                        )
                    )
                },
                onValueChangeFinished = { viewModel.saveSettings() },
                valueRange = 0.1f..5.0f,
                steps = 48,
                valueDisplay = { String.format(Locale.US, "%03.2f", it) }
            )
        }
        if (advancedControlsState.funscriptFrequencyAlgorithm == FrequencyAlgorithmType.BLEND) {
            SliderWithLabel(
                label = "Blend ratio (Position -> Varied)",
                value = advancedControlsState.funscriptFrequencyBlendRatio,
                onValueChange = {
                    viewModel.updateAdvancedControlsState(
                        advancedControlsState.copy(
                            funscriptFrequencyBlendRatio = it
                        )
                    )
                },
                onValueChangeFinished = { viewModel.saveSettings() },
                valueRange = 0f..1.0f,
                steps = 99,
                valueDisplay = { String.format(Locale.US, "%03.2f", it) }
            )
        }
    }
}

@Composable
fun SpecialEffectsPanel(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val specialEffectsState by viewModel.specialEffectsState.collectAsStateWithLifecycle()
    val developerOptionsState by viewModel.developerOptionsState.collectAsStateWithLifecycle()
    val enabled = specialEffectsState.specialEffectsEnabled

    val developerExponentRange: ClosedFloatingPointRange<Float> = 0.5f .. 2.0f
    val developerGainRange: ClosedFloatingPointRange<Float> = 0.5f .. 2.0f
    val developerFrequencyAdjustRange: ClosedFloatingPointRange<Float> = -1.0f .. 1.0f

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = "Special effects", style = MaterialTheme.typography.headlineSmall)
        }
        SwitchWithLabel(
            label = "Apply special effects",
            checked = specialEffectsState.specialEffectsEnabled,
            onCheckedChange = {
                viewModel.updateSpecialEffectsState(
                    specialEffectsState.copy(
                        specialEffectsEnabled = it
                    )
                )
                viewModel.saveSettings()
            }
        )
        SwitchWithLabel(
            label = "Invert channel A frequencies",
            checked = specialEffectsState.frequencyInversionA,
            onCheckedChange = {
                viewModel.updateSpecialEffectsState(
                    specialEffectsState.copy(
                        frequencyInversionA = it
                    )
                )
                viewModel.saveSettings()
            },
            enabled = enabled
        )
        SwitchWithLabel(
            label = "Invert channel B frequencies",
            checked = specialEffectsState.frequencyInversionB,
            onCheckedChange = {
                viewModel.updateSpecialEffectsState(
                    specialEffectsState.copy(
                        frequencyInversionB = it
                    )
                )
                viewModel.saveSettings()
            },
            enabled = enabled
        )
        SliderWithLabel(
            label = "Scale amplitude (channel A)",
            value = specialEffectsState.scaleAmplitudeA,
            onValueChange = {viewModel.updateSpecialEffectsState(specialEffectsState.copy(scaleAmplitudeA = it))},
            onValueChangeFinished = { viewModel.saveSettings() },
            valueRange = 0.0f..2.0f,
            steps = 39,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) },
            enabled = enabled
        )
        SliderWithLabel(
            label = "Scale amplitude (channel B)",
            value = specialEffectsState.scaleAmplitudeB,
            onValueChange = {viewModel.updateSpecialEffectsState(specialEffectsState.copy(scaleAmplitudeB = it))},
            onValueChangeFinished = { viewModel.saveSettings() },
            valueRange = 0.0f..2.0f,
            steps = 39,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) },
            enabled = enabled
        )
        SliderWithLabel(
            label = "Frequency feel adjustment",
            value = specialEffectsState.frequencyFeel,
            onValueChange = {viewModel.updateSpecialEffectsState(specialEffectsState.copy(frequencyFeel = it))},
            onValueChangeFinished = { viewModel.saveSettings() },
            valueRange = 0.5f..2.0f,
            steps = 149,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) },
            enabled = enabled
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SliderWithLabel(
            label = "Random amplitude noise (amount)",
            value = specialEffectsState.amplitudeNoiseAmount,
            onValueChange = {
                viewModel.updateSpecialEffectsState(
                    specialEffectsState.copy(
                        amplitudeNoiseAmount = it
                    )
                )
            },
            onValueChangeFinished = { viewModel.saveSettings() },
            valueRange = 0f..1.0f,
            steps = 99,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) },
            enabled = enabled
        )
        if (specialEffectsState.amplitudeNoiseAmount > 0f) {
            SliderWithLabel(
                label = "Random amplitude noise (speed)",
                value = specialEffectsState.amplitudeNoiseSpeed,
                onValueChange = {
                    viewModel.updateSpecialEffectsState(
                        specialEffectsState.copy(
                            amplitudeNoiseSpeed = it
                        )
                    )
                },
                onValueChangeFinished = { viewModel.saveSettings() },
                valueRange = 0.1f..20.0f,
                steps = 198,
                valueDisplay = { String.format(Locale.US, "%03.2f", it) },
                enabled = enabled
            )
        }
        SliderWithLabel(
            label = "Random frequency noise (amount)",
            value = specialEffectsState.frequencyNoiseAmount,
            onValueChange = {
                viewModel.updateSpecialEffectsState(
                    specialEffectsState.copy(
                        frequencyNoiseAmount = it
                    )
                )
            },
            onValueChangeFinished = { viewModel.saveSettings() },
            valueRange = 0f..1.0f,
            steps = 99,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) },
            enabled = enabled
        )
        if (specialEffectsState.frequencyNoiseAmount > 0f) {
            SliderWithLabel(
                label = "Random frequency noise (speed)",
                value = specialEffectsState.frequencyNoiseSpeed,
                onValueChange = {
                    viewModel.updateSpecialEffectsState(
                        specialEffectsState.copy(
                            frequencyNoiseSpeed = it
                        )
                    )
                },
                onValueChangeFinished = { viewModel.saveSettings() },
                valueRange = 0.1f..20.0f,
                steps = 198,
                valueDisplay = { String.format(Locale.US, "%03.2f", it) },
                enabled = enabled
            )
        }
        if (showDeveloperOptions) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(text = "Developer options", style = MaterialTheme.typography.headlineSmall)
            }
            SliderWithLabel(
                label = "Frequency exponent",
                value = developerOptionsState.developerFrequencyExponent,
                onValueChange = { viewModel.updateDeveloperOptionsState(developerOptionsState.copy(developerFrequencyExponent = it)) },
                onValueChangeFinished = { },
                valueRange = developerExponentRange,
                steps = ((developerExponentRange.endInclusive - developerExponentRange.start) * 10.0 - 1).roundToInt(),
                valueDisplay = { String.format(Locale.US, "%02.1f", it) },
                enabled = enabled
            )
            SliderWithLabel(
                label = "Frequency gain",
                value = developerOptionsState.developerFrequencyGain,
                onValueChange = { viewModel.updateDeveloperOptionsState(developerOptionsState.copy(developerFrequencyGain = it)) },
                onValueChangeFinished = { },
                valueRange = developerGainRange,
                steps = ((developerGainRange.endInclusive - developerGainRange.start) * 10.0 - 1).roundToInt(),
                valueDisplay = { String.format(Locale.US, "%02.1f", it) },
                enabled = enabled
            )
            SliderWithLabel(
                label = "Channel A frequency adjust",
                value = developerOptionsState.developerFrequencyAdjustA,
                onValueChange = { viewModel.updateDeveloperOptionsState(developerOptionsState.copy(developerFrequencyAdjustA = it)) },
                onValueChangeFinished = { },
                valueRange = developerFrequencyAdjustRange,
                steps = ((developerFrequencyAdjustRange.endInclusive - developerFrequencyAdjustRange.start) * 20.0 - 1).roundToInt(),
                valueDisplay = { String.format(Locale.US, "%03.2f", it) },
                enabled = enabled
            )
            SliderWithLabel(
                label = "Channel B frequency adjust",
                value = developerOptionsState.developerFrequencyAdjustB,
                onValueChange = { viewModel.updateDeveloperOptionsState(developerOptionsState.copy(developerFrequencyAdjustB = it)) },
                onValueChangeFinished = { },
                valueRange = developerFrequencyAdjustRange,
                steps = ((developerFrequencyAdjustRange.endInclusive - developerFrequencyAdjustRange.start) * 20.0 - 1).roundToInt(),
                valueDisplay = { String.format(Locale.US, "%03.2f", it) },
                enabled = enabled
            )
            SliderWithLabel(
                label = "Amplitude exponent",
                value = developerOptionsState.developerAmplitudeExponent,
                onValueChange = { viewModel.updateDeveloperOptionsState(developerOptionsState.copy(developerAmplitudeExponent = it)) },
                onValueChangeFinished = { },
                valueRange = developerExponentRange,
                steps = ((developerExponentRange.endInclusive - developerExponentRange.start) * 10.0 - 1).roundToInt(),
                valueDisplay = { String.format(Locale.US, "%02.1f", it) },
                enabled = enabled
            )
            SliderWithLabel(
                label = "Amplitude gain",
                value = developerOptionsState.developerAmplitudeGain,
                onValueChange = { viewModel.updateDeveloperOptionsState(developerOptionsState.copy(developerAmplitudeGain = it)) },
                onValueChangeFinished = { },
                valueRange = developerGainRange,
                steps = ((developerGainRange.endInclusive - developerGainRange.start) * 10.0 - 1).roundToInt(),
                valueDisplay = { String.format(Locale.US, "%02.1f", it) },
                enabled = enabled
            )
        }
    }
}

@Composable
fun PlayerPanel(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val advancedControlsState by viewModel.advancedControlsState.collectAsStateWithLifecycle()
    val specialEffectsState by viewModel.specialEffectsState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showAdvancedSettings by remember { mutableStateOf(false) }
    var showSpecialEffects by remember { mutableStateOf(false) }
    val activeButtonColour = Color.Red


    // Temporary variables that we use to ensure the player position only gets
    // updated once the user finishes dragging the drag handle on the seek bar.
    // This prevents sending garbled output when the user drags during playback.
    var isDragging by remember { mutableStateOf(false) }
    var tempPosition by remember { mutableDoubleStateOf(playerState.currentPosition) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            uri?.let {
                viewModel.loadFile(uri, context)
            }
        }
    )

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // File Name Display
        val displayName = playerState.activePulseSource?.displayName ?: "Player"
        val duration = playerState.activePulseSource?.duration ?: 0.0
        Text(text = displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)

        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Position Display
            val pos = if (isDragging) tempPosition else playerState.currentPosition
            Text(text = formatTime(pos))
            Spacer(modifier = Modifier.width(4.dp))

            // Seek Bar
            Slider(
                value = pos.toFloat(),
                onValueChange = {
                    tempPosition = it.toDouble()
                    if (!isDragging) isDragging = true
                },
                valueRange = 0f..duration.toFloat(),
                onValueChangeFinished = {
                    isDragging = false
                    viewModel.seek(tempPosition)
                }
            )
            /*Slider(
                value = playerState.currentPosition.toFloat(),
                onValueChange = { viewModel.seek(it.toDouble()) },
                valueRange = 0f..duration.toFloat(),
                onValueChangeFinished = { }
            )*/
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Play/Pause Button
            Button(
                onClick = {
                    if (playerState.isPlaying)
                        viewModel.stopPlayer()
                    else
                        viewModel.startPlayer()
                }
            ) {
                if (playerState.isPlaying) {
                    Icon(
                        painter = painterResource(R.drawable.pause),
                        contentDescription = "Pause"
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.play),
                        contentDescription = "Play"
                    )
                }
            }
            // File Picker Button
            Button(
                onClick = {
                    viewModel.stopPlayer()
                    filePickerLauncher.launch(arrayOf("*/*"))
                }
            ) {
                Icon(
                    painter = painterResource(R.drawable.folder_open),
                    contentDescription = "Open file"
                )
            }
            // Special effects options button
            Button(
                onClick = {
                    showSpecialEffects = true
                },
                colors = ButtonDefaults.buttonColors(
                containerColor = if (specialEffectsState.specialEffectsEnabled) activeButtonColour else ButtonDefaults.buttonColors().containerColor
                )
            ) {
                Icon(
                    painter = painterResource(R.drawable.special_effects),
                    contentDescription = "Special effects"
                )
            }
            // Advanced options button
            Button(
                onClick = {
                    showAdvancedSettings = true
                }
            ) {
                Icon(
                    painter = painterResource(R.drawable.settings),
                    contentDescription = "Advanced settings"
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (advancedControlsState.showSyncFineTune) {
            SliderWithLabel(
                label = "Sync fine tune (seconds)",
                value = playerState.syncFineTune,
                onValueChange = { viewModel.updatePlayerState(playerState.copy(syncFineTune = it)) },
                onValueChangeFinished = { },
                valueRange = -0.5f..0.5f,
                steps = 99,
                valueDisplay = { String.format(Locale.US, "%03.2f", it) }
            )
        }

        if (showAdvancedSettings) {
            Dialog(
                onDismissRequest = { showAdvancedSettings = false }
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
                ) {
                    AdvancedControlsPanel(
                        viewModel = viewModel,
                    )
                }
            }
        }
        if (showSpecialEffects) {
            Dialog(
                onDismissRequest = { showSpecialEffects = false }
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
                ) {
                    SpecialEffectsPanel (
                        viewModel = viewModel,
                    )
                }
            }
        }
    }
}

@Preview
@Composable
fun PlayerPreview() {
    HowlTheme {
        val viewModel: PlayerViewModel = viewModel()
        PlayerPanel(
            viewModel = viewModel,
            modifier = Modifier.fillMaxHeight()
        )
    }
}