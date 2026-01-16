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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.DurationUnit
import kotlin.time.TimeSource.Monotonic.markNow
import java.util.Locale
import java.lang.ref.WeakReference
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.time.TimeMark

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

data class PlayerState(
    val activePulseSource: PulseSource? = null,
    val startPosition: Double = 0.0,
    val isPlaying: Boolean = false,
    val startTime: TimeMark? = null,
    val syncFineTune: Float = 0.0f,
)

data class RecordState(
    val duration: Float = 0.0f,
    val recordMode: Boolean = false,
    val recording: Boolean = false,
)

interface PulseSource {
    val displayName: String
    val duration: Double?
    val isFinite: Boolean
    val shouldLoop: Boolean
    val readyToPlay: Boolean
    val isRemote: Boolean
    fun getPulseAtTime(time: Double): Pulse
    fun updateState(currentTime: Double)
}

object Player {
    private var contextRef: WeakReference<Context>? = null
    var output: Output = DummyOutput()
    var recorder: RecorderOutput = RecorderOutput()
    private val noiseGenerator = NoiseGenerator()

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _recordState = MutableStateFlow(RecordState())
    val recordState: StateFlow<RecordState> = _recordState.asStateFlow()

    // split out of playerState because it changes very frequently and most observers don't
    // actually need it (helps reduce UI recompositions)
    private val _playerPosition = MutableStateFlow(0.0)
    val playerPosition: StateFlow<Double> = _playerPosition.asStateFlow()

    fun initialise(context: Context) {
        contextRef = WeakReference(context)
    }
    fun switchOutput(outputType: OutputType) {
        Prefs.outputType.value = outputType
        stopPlayer()
        output.end()
        MainOptions.setChannelPower(0, 0)
        MainOptions.setChannelPower(1, 0)
        output = when (outputType) {
            OutputType.COYOTE3 -> Coyote3Output()
            OutputType.COYOTE2 -> Coyote2Output()
            OutputType.AUDIO_WAVELET -> AudioOutput()
            OutputType.AUDIO_CONTINUOUS -> ContinuousAudioOutput()
        }
        output.initialise()
        MainOptions.setFrequenciesToOutputDefaults(output)
    }
    fun setRecordState(newRecordState: RecordState) {
        _recordState.update { newRecordState }
    }
    fun setPlayerPosition(position: Double) {
        _playerPosition.update { position }
    }
    fun setSyncFineTune(offset: Float) {
        _playerState.update { it.copy(syncFineTune = offset) }
    }

    fun getTimeAdjustment(): Double {
        if (recordState.value.recordMode)
            return 0.0

        val syncFineTune = playerState.value.syncFineTune
        val isRemote = playerState.value.activePulseSource?.isRemote ?: false
        val latency = if (isRemote) Prefs.playerRemoteLatency.value.toDouble() else 0.0

        return syncFineTune + latency
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
    fun applySpecialEffects(time: Double, pulse: Pulse): Pulse {

        var modifiedPulse = pulse.copy(
            freqA = if (Prefs.sfxFrequencyInvertA.value) 1.0f - pulse.freqA else pulse.freqA,
            freqB = if (Prefs.sfxFrequencyInvertB.value) 1.0f - pulse.freqB else pulse.freqB,
        )

        if (Prefs.sfxAmplitudeNoiseAmount.value > 0.0 || Prefs.sfxFrequencyNoiseAmount.value > 0.0) {
            modifiedPulse = applyNoise(
                time = time,
                pulse = modifiedPulse,
                ampNoiseAmount = Prefs.sfxAmplitudeNoiseAmount.value.toDouble(),
                ampNoiseSpeed = Prefs.sfxAmplitudeNoiseSpeed.value.toDouble(),
                freqNoiseAmount = Prefs.sfxFrequencyNoiseAmount.value.toDouble(),
                freqNoiseSpeed = Prefs.sfxFrequencyNoiseSpeed.value.toDouble()
            )
        }

        modifiedPulse = modifiedPulse.copy(
            freqA = calculateFeelAdjustment(
                value = modifiedPulse.freqA,
                feel = Prefs.sfxFrequencyFeelA.value
            ),
            freqB = calculateFeelAdjustment(
                value = modifiedPulse.freqB,
                feel = Prefs.sfxFrequencyFeelB.value
            ),
            ampA = calculateFeelAdjustment(
                value = modifiedPulse.ampA,
                feel = Prefs.sfxAmplitudeFeelA.value
            ),
            ampB = calculateFeelAdjustment(
                value = modifiedPulse.ampB,
                feel = Prefs.sfxAmplitudeFeelB.value
            )
        )

        modifiedPulse = modifiedPulse.copy(
            ampA = (modifiedPulse.ampA * Prefs.sfxAmplitudeScaleA.value).coerceAtMost(1.0f),
            ampB = (modifiedPulse.ampB * Prefs.sfxAmplitudeScaleB.value).coerceAtMost(1.0f),
            freqA = (modifiedPulse.freqA + Prefs.sfxFrequencyAdjustA.value).coerceIn(0.0f..1.0f),
            freqB = (modifiedPulse.freqB + Prefs.sfxFrequencyAdjustB.value).coerceIn(0.0f..1.0f)
        )

        return modifiedPulse
    }
    fun applyPostProcessing(time: Double, pulse: Pulse): Pulse {
        val mainOptionsState = MainOptions.state.value
        val swapChannels = mainOptionsState.swapChannels

        val inputPulse = if (swapChannels) {
            pulse.copy(
                ampA = pulse.ampB,
                ampB = pulse.ampA,
                freqA = pulse.freqB,
                freqB = pulse.freqA
            )
        } else {
            pulse
        }

        return if (Prefs.sfxEnabled.value) {
            applySpecialEffects(time, inputPulse)
        } else {
            inputPulse
        }
    }
    fun getPulseAtTime(time: Double): Pulse {
        val activePulseSource = playerState.value.activePulseSource
        val pulse = activePulseSource?.getPulseAtTime(time) ?: Pulse()
        return applyPostProcessing(time, pulse)
    }
    fun stopPlayer() {
        _playerState.update { it.copy(isPlaying = false) }
        output.stop()
    }
    fun startPlayer(from: Double? = null) {
        val playerState = playerState.value
        val currentPosition = playerPosition.value
        val playFrom = from ?: currentPosition
        if(playerState.activePulseSource?.readyToPlay != true)
            return
        _playerState.update { it.copy(
            isPlaying = true,
            startTime = markNow(),
            startPosition = playFrom
        ) }
        output.start()

        val context = contextRef?.get() ?: return
        val serviceIntent = Intent(context, PlayerService::class.java)
        context.startService(serviceIntent)
    }
    fun loadFile(uri: Uri, context: Context) {
        setPlayerPosition(0.0)
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
        val playerState = playerState.value
        if (source == null || playerState.activePulseSource != source) {
            _playerState.update { it.copy(
                activePulseSource = source,
                startPosition = 0.0,
                startTime = null,
                isPlaying = false,
                syncFineTune = 0.0f
            ) }
            setPlayerPosition(0.0)
        }
    }
    fun getCurrentPosition(): Double {
        val playerState = playerState.value
        val recordState = recordState.value
        if (recordState.recordMode) {
            val playerPosition = playerPosition.value
            // Round to nearest 40th of a second
            val roundedPosition = (playerPosition * 40).roundToInt() / 40.0
            return roundedPosition
        }
        else {
            val playbackSpeed = Prefs.playerPlaybackSpeed.value
            val elapsed = playerState.startTime?.elapsedNow()?.toDouble(DurationUnit.SECONDS) ?: 0.0
            return playerState.startPosition + elapsed * playbackSpeed
        }
    }
    fun seek(position: Double? = null) {
        // May also be called with null position to resync the player, for example when changing
        // playback speed
        val playerState = playerState.value
        val currentPosition = playerPosition.value

        val finite = playerState.activePulseSource?.isFinite ?: false
        val pos = if (!finite || position == null) currentPosition else position
        _playerState.update { it.copy(
            startTime = markNow(),
            startPosition = pos
        ) }
        setPlayerPosition(pos)
    }
}

class PlayerViewModel() : ViewModel() {
    val playerState: StateFlow<PlayerState> = Player.playerState
    val recordState: StateFlow<RecordState> = Player.recordState

    fun updateRecordState(newRecordState: RecordState) {
        Player.setRecordState(newRecordState)
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

    fun clearRecording() {
        Player.recorder.clear()
    }

    fun resizeRecordingBuffer(duration: Int, clear: Boolean = false) {
        Player.recorder.resize(duration, clear)
    }

    fun setRecordingMode(enable: Boolean) {
        val recordBufferLengthPassive = 120
        val recordBufferLengthActive = 7200

        if(enable) {
            resizeRecordingBuffer(recordBufferLengthActive, true)
        }
        else {
            resizeRecordingBuffer(recordBufferLengthPassive, true)
            seek(null)
        }

        updateRecordState(recordState.value.copy(recordMode = enable, recording = false))
    }

    fun setRecording(enable: Boolean) {
        updateRecordState(recordState.value.copy(recording = enable))
    }

    fun saveRecording(uri: Uri, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pulses = Player.recorder.getPulses()

                if (pulses.isEmpty()) {
                    Log.e("Player", "No pulses to save")
                    return@launch
                }

                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    writeHWLFile(outputStream, pulses)
                    Log.i("Player", "Recording saved: ${pulses.size} pulses")
                } ?: Log.e("Player", "Failed to open output stream")

            } catch (e: Exception) {
                Log.e("Player", "Failed to save recording", e)
            }
        }
    }
}

@Composable
fun AdvancedControlsPanel(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val playerShowSyncFineTune by Prefs.playerShowSyncFineTune.collectAsStateWithLifecycle()
    val playerPlaybackSpeed by Prefs.playerPlaybackSpeed.collectAsStateWithLifecycle()
    val playerRemoteLatency by Prefs.playerRemoteLatency.collectAsStateWithLifecycle()

    val funscriptVolume by Prefs.funscriptVolume.collectAsStateWithLifecycle()
    val funscriptPositionalEffectStrength by Prefs.funscriptPositionalEffectStrength.collectAsStateWithLifecycle()
    val funscriptFrequencyTimeOffset by Prefs.funscriptFrequencyTimeOffset.collectAsStateWithLifecycle()
    val funscriptFrequencyVarySpeed by Prefs.funscriptFrequencyVarySpeed.collectAsStateWithLifecycle()
    val funscriptFrequencyBlendRatio by Prefs.funscriptFrequencyBlendRatio.collectAsStateWithLifecycle()
    val funscriptFrequencyAlgorithm by Prefs.funscriptFrequencyAlgorithm.collectAsStateWithLifecycle()
    val funscriptFrequencyFixedA by Prefs.funscriptFrequencyFixedA.collectAsStateWithLifecycle()
    val funscriptFrequencyFixedB by Prefs.funscriptFrequencyFixedB.collectAsStateWithLifecycle()

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
        SliderWithLabel(
            label = "Playback speed",
            value = playerPlaybackSpeed,
            onValueChange = {
                Prefs.playerPlaybackSpeed.value = it
                viewModel.seek()
            },
            onValueChangeFinished = { Prefs.playerPlaybackSpeed.save() },
            valueRange = 0.25f..4.0f,
            steps = 14,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) }
        )
        SliderWithLabel(
            label = "Remote latency (seconds)",
            value = playerRemoteLatency,
            onValueChange = { Prefs.playerRemoteLatency.value = it },
            onValueChangeFinished = { Prefs.playerRemoteLatency.save() },
            valueRange = 0.0f..1.0f,
            steps = 99,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) }
        )
        SwitchWithLabel(
            label = "Show sync fine tune",
            checked = playerShowSyncFineTune,
            onCheckedChange = {
                Prefs.playerShowSyncFineTune.value = it
                Prefs.playerShowSyncFineTune.save()
            }
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = "Funscript settings", style = MaterialTheme.typography.headlineSmall)
        }
        SliderWithLabel(
            label = "Scaling coefficient",
            value = funscriptVolume,
            onValueChange = { Prefs.funscriptVolume.value = it },
            onValueChangeFinished = { Prefs.funscriptVolume.save() },
            valueRange = 0.5f..1.0f,
            steps = 49,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) }
        )
        SliderWithLabel(
            label = "Positional effect strength",
            value = funscriptPositionalEffectStrength,
            onValueChange = { Prefs.funscriptPositionalEffectStrength.value = it },
            onValueChangeFinished = { Prefs.funscriptPositionalEffectStrength.save() },
            valueRange = 0f..1.0f,
            steps = 99,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Frequency algorithm", style = MaterialTheme.typography.labelLarge)
            OptionPicker(
                currentValue = funscriptFrequencyAlgorithm,
                onValueChange = {
                    Prefs.funscriptFrequencyAlgorithm.value = it
                    Prefs.funscriptFrequencyAlgorithm.save()
                },
                options = FrequencyAlgorithmType.entries,
                getText = { it.displayName }
            )
        }
        if (funscriptFrequencyAlgorithm == FrequencyAlgorithmType.FIXED) {
            SliderWithLabel(
                label = "Channel A fixed frequency",
                value = funscriptFrequencyFixedA,
                onValueChange = { Prefs.funscriptFrequencyFixedA.value = it },
                onValueChangeFinished = { Prefs.funscriptFrequencyFixedA.save() },
                valueRange = 0f..1.0f,
                steps = 99,
                valueDisplay = { String.format(Locale.US, "%03.2f", it) }
            )
            SliderWithLabel(
                label = "Channel B fixed frequency",
                value = funscriptFrequencyFixedB,
                onValueChange = { Prefs.funscriptFrequencyFixedB.value = it },
                onValueChangeFinished = { Prefs.funscriptFrequencyFixedB.save() },
                valueRange = 0f..1.0f,
                steps = 99,
                valueDisplay = { String.format(Locale.US, "%03.2f", it) }
            )
        }
        if (funscriptFrequencyAlgorithm == FrequencyAlgorithmType.POSITION) {
            SliderWithLabel(
                label = "A/B frequency time offset",
                value = funscriptFrequencyTimeOffset,
                onValueChange = { Prefs.funscriptFrequencyTimeOffset.value = it },
                onValueChangeFinished = { Prefs.funscriptFrequencyTimeOffset.save() },
                valueRange = -0.3f..0.3f,
                steps = 59,
                valueDisplay = { String.format(Locale.US, "%03.2f", it) }
            )
        }
        if (funscriptFrequencyAlgorithm == FrequencyAlgorithmType.BLEND || funscriptFrequencyAlgorithm == FrequencyAlgorithmType.VARIED) {
            SliderWithLabel(
                label = "Frequency vary speed",
                value = funscriptFrequencyVarySpeed,
                onValueChange = { Prefs.funscriptFrequencyVarySpeed.value = it },
                onValueChangeFinished = { Prefs.funscriptFrequencyVarySpeed.save() },
                valueRange = 0.1f..5.0f,
                steps = 48,
                valueDisplay = { String.format(Locale.US, "%03.2f", it) }
            )
        }
        if (funscriptFrequencyAlgorithm == FrequencyAlgorithmType.BLEND) {
            SliderWithLabel(
                label = "Blend ratio (Position -> Varied)",
                value = funscriptFrequencyBlendRatio,
                onValueChange = { Prefs.funscriptFrequencyBlendRatio.value = it },
                onValueChangeFinished = { Prefs.funscriptFrequencyBlendRatio.save() },
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
    val sfxEnabled by Prefs.sfxEnabled.collectAsStateWithLifecycle()
    val sfxFrequencyInvertA by Prefs.sfxFrequencyInvertA.collectAsStateWithLifecycle()
    val sfxFrequencyInvertB by Prefs.sfxFrequencyInvertB.collectAsStateWithLifecycle()
    val sfxAmplitudeScaleA by Prefs.sfxAmplitudeScaleA.collectAsStateWithLifecycle()
    val sfxAmplitudeScaleB by Prefs.sfxAmplitudeScaleB.collectAsStateWithLifecycle()
    val sfxFrequencyFeelA by Prefs.sfxFrequencyFeelA.collectAsStateWithLifecycle()
    val sfxFrequencyFeelB by Prefs.sfxFrequencyFeelB.collectAsStateWithLifecycle()
    val sfxAmplitudeFeelA by Prefs.sfxAmplitudeFeelA.collectAsStateWithLifecycle()
    val sfxAmplitudeFeelB by Prefs.sfxAmplitudeFeelB.collectAsStateWithLifecycle()
    val sfxAmplitudeNoiseAmount by Prefs.sfxAmplitudeNoiseAmount.collectAsStateWithLifecycle()
    val sfxAmplitudeNoiseSpeed by Prefs.sfxAmplitudeNoiseSpeed.collectAsStateWithLifecycle()
    val sfxFrequencyNoiseAmount by Prefs.sfxFrequencyNoiseAmount.collectAsStateWithLifecycle()
    val sfxFrequencyNoiseSpeed by Prefs.sfxFrequencyNoiseSpeed.collectAsStateWithLifecycle()
    val sfxFrequencyAdjustA by Prefs.sfxFrequencyAdjustA.collectAsStateWithLifecycle()
    val sfxFrequencyAdjustB by Prefs.sfxFrequencyAdjustB.collectAsStateWithLifecycle()

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
            checked = sfxEnabled,
            onCheckedChange = {
                Prefs.sfxEnabled.value = it
                Prefs.sfxEnabled.save()
            }
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = "Amplitude adjustments", style = MaterialTheme.typography.headlineSmall)
        }
        SliderWithLabel(
            label = "Amplitude feel (channel A)",
            value = sfxAmplitudeFeelA,
            onValueChange = { Prefs.sfxAmplitudeFeelA.value = it },
            onValueChangeFinished = { Prefs.sfxAmplitudeFeelA.save() },
            valueRange = 0.5f..2.0f,
            steps = 149,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) },
            enabled = sfxEnabled
        )
        SliderWithLabel(
            label = "Amplitude feel (channel B)",
            value = sfxAmplitudeFeelB,
            onValueChange = { Prefs.sfxAmplitudeFeelB.value = it },
            onValueChangeFinished = { Prefs.sfxAmplitudeFeelB.save() },
            valueRange = 0.5f..2.0f,
            steps = 149,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) },
            enabled = sfxEnabled
        )
        SliderWithLabel(
            label = "Scale amplitude (channel A)",
            value = sfxAmplitudeScaleA,
            onValueChange = { Prefs.sfxAmplitudeScaleA.value = it },
            onValueChangeFinished = { Prefs.sfxAmplitudeScaleA.save() },
            valueRange = 0.0f..2.0f,
            steps = 39,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) },
            enabled = sfxEnabled
        )
        SliderWithLabel(
            label = "Scale amplitude (channel B)",
            value = sfxAmplitudeScaleB,
            onValueChange = { Prefs.sfxAmplitudeScaleB.value = it },
            onValueChangeFinished = { Prefs.sfxAmplitudeScaleB.save() },
            valueRange = 0.0f..2.0f,
            steps = 39,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) },
            enabled = sfxEnabled
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = "Frequency adjustments", style = MaterialTheme.typography.headlineSmall)
        }
        SliderWithLabel(
            label = "Frequency feel (channel A)",
            value = sfxFrequencyFeelA,
            onValueChange = { Prefs.sfxFrequencyFeelA.value = it },
            onValueChangeFinished = { Prefs.sfxFrequencyFeelA.save() },
            valueRange = 0.5f..2.0f,
            steps = 149,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) },
            enabled = sfxEnabled
        )
        SliderWithLabel(
            label = "Frequency feel (channel B)",
            value = sfxFrequencyFeelB,
            onValueChange = { Prefs.sfxFrequencyFeelB.value = it },
            onValueChangeFinished = { Prefs.sfxFrequencyFeelB.save() },
            valueRange = 0.5f..2.0f,
            steps = 149,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) },
            enabled = sfxEnabled
        )
        SwitchWithLabel(
            label = "Invert channel A frequencies",
            checked = sfxFrequencyInvertA,
            onCheckedChange = {
                Prefs.sfxFrequencyInvertA.value = it
                Prefs.sfxFrequencyInvertA.save()
            },
            enabled = sfxEnabled
        )
        SwitchWithLabel(
            label = "Invert channel B frequencies",
            checked = sfxFrequencyInvertB,
            onCheckedChange = {
                Prefs.sfxFrequencyInvertB.value = it
                Prefs.sfxFrequencyInvertB.save()
            },
            enabled = sfxEnabled
        )
        SliderWithLabel(
            label = "Frequency adjust (channel A)",
            value = sfxFrequencyAdjustA,
            onValueChange = { Prefs.sfxFrequencyAdjustA.value = it },
            onValueChangeFinished = { Prefs.sfxFrequencyAdjustA.save() },
            valueRange = -1.0f..1.0f,
            steps = 199,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) },
            enabled = sfxEnabled
        )
        SliderWithLabel(
            label = "Frequency adjust (channel B)",
            value = sfxFrequencyAdjustB,
            onValueChange = { Prefs.sfxFrequencyAdjustB.value = it },
            onValueChangeFinished = { Prefs.sfxFrequencyAdjustB.save() },
            valueRange = -1.0f..1.0f,
            steps = 199,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) },
            enabled = sfxEnabled
        )


        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = "Random noise", style = MaterialTheme.typography.headlineSmall)
        }
        SliderWithLabel(
            label = "Random amplitude noise (amount)",
            value = sfxAmplitudeNoiseAmount,
            onValueChange = { Prefs.sfxAmplitudeNoiseAmount.value = it },
            onValueChangeFinished = { Prefs.sfxAmplitudeNoiseAmount.save() },
            valueRange = 0f..1.0f,
            steps = 99,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) },
            enabled = sfxEnabled
        )
        if (sfxAmplitudeNoiseAmount > 0f) {
            SliderWithLabel(
                label = "Random amplitude noise (speed)",
                value = sfxAmplitudeNoiseSpeed,
                onValueChange = { Prefs.sfxAmplitudeNoiseSpeed.value = it },
                onValueChangeFinished = { Prefs.sfxAmplitudeNoiseSpeed.save() },
                valueRange = 0.1f..20.0f,
                steps = 198,
                valueDisplay = { String.format(Locale.US, "%03.2f", it) },
                enabled = sfxEnabled
            )
        }
        SliderWithLabel(
            label = "Random frequency noise (amount)",
            value = sfxFrequencyNoiseAmount,
            onValueChange = { Prefs.sfxFrequencyNoiseAmount.value = it },
            onValueChangeFinished = { Prefs.sfxFrequencyNoiseAmount.save() },
            valueRange = 0f..1.0f,
            steps = 99,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) },
            enabled = sfxEnabled
        )
        if (sfxFrequencyNoiseAmount > 0f) {
            SliderWithLabel(
                label = "Random frequency noise (speed)",
                value = sfxFrequencyNoiseSpeed,
                onValueChange = { Prefs.sfxFrequencyNoiseSpeed.value = it },
                onValueChangeFinished = { Prefs.sfxFrequencyNoiseSpeed.save() },
                valueRange = 0.1f..20.0f,
                steps = 198,
                valueDisplay = { String.format(Locale.US, "%03.2f", it) },
                enabled = sfxEnabled
            )
        }
    }
}

@Composable
fun PlayerPositionDisplay(
    duration: Double,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentPosition by Player.playerPosition.collectAsStateWithLifecycle()

    // Temporary variables that we use to ensure the player position only gets
    // updated once the user finishes dragging the drag handle on the seek bar.
    // This prevents sending garbled output when the user drags during playback.
    var isDragging by remember { mutableStateOf(false) }
    var tempPosition by remember { mutableDoubleStateOf(currentPosition) }
    val pos = if (isDragging) tempPosition else currentPosition

    Row(
        modifier = modifier
            .fillMaxWidth(),
            //.padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Seek Bar
        Slider(
            modifier = Modifier.weight(1f),
            value = pos.toFloat(),
            onValueChange = {
                tempPosition = it.toDouble()
                if (!isDragging) isDragging = true
            },
            valueRange = 0f..duration.toFloat(),
            onValueChangeFinished = {
                isDragging = false
                onSeek(tempPosition)
            }
        )
        // Position Display
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = formatTime(pos),
            maxLines = 1,
            style = MaterialTheme.typography.headlineSmall
        )
    }
}

@Composable
fun RecordPanel(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val recordState by viewModel.recordState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val duration = recordState.duration
    val recordMode = recordState.recordMode
    val recording = recordState.recording

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
        onResult = { uri: Uri? ->
            uri?.let { viewModel.saveRecording(it, context) }
        }
    )

    val backgroundColor =
        if (recordMode) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = backgroundColor,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 2.dp
    ) {
        Column(
            //modifier = Modifier.padding(12.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Section label and switch row
            Row (
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Recorder",
                    style = MaterialTheme.typography.titleLarge,
                )
                // Record mode toggle
                Switch(
                    checked = recordMode,
                    onCheckedChange = { enable ->
                        viewModel.setRecordingMode(enable)
                    }
                )
            }
            // Controls row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Start/stop recording button
                if (recordMode) {
                    Button(
                        onClick = {
                            viewModel.setRecording(!recording)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (recording)
                                MaterialTheme.colorScheme.tertiary
                            else
                                MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.record),
                            contentDescription = "Toggle recording"
                        )
                    }
                }
                // Save button
                Button(
                    enabled = duration > 0,
                    onClick = {
                        Player.stopPlayer()
                        val formatter = DateTimeFormatter.ofPattern(
                            "yyyy-MM-dd--HH-mm-ss",
                            Locale.US
                        )
                        val now = LocalDateTime.now()
                        val defaultFilename = "${now.format(formatter)}.hwl"
                        saveLauncher.launch(defaultFilename)
                    }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.save),
                        contentDescription = "Save recording"
                    )
                }
                if (recordMode) {
                    // Clear button
                    Button(
                        onClick = {
                            viewModel.clearRecording()
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.bin),
                            contentDescription = "Clear recording"
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = formatTime(duration.toDouble()),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.headlineSmall
                )
            }
        }
    }
}

@Composable
fun PlayerPanel(
    viewModel: PlayerViewModel,
    onAdvancedSettingsClick: () -> Unit,
    onSpecialEffectsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val playerShowSyncFineTune by Prefs.playerShowSyncFineTune.collectAsStateWithLifecycle()
    val sfxEnabled by Prefs.sfxEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val activeButtonColour = MaterialTheme.colorScheme.tertiary
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            uri?.let {
                viewModel.loadFile(uri, context)
            }
        }
    )

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // File Name Display
            val displayName = playerState.activePulseSource?.displayName ?: "Player"
            Text(
                text = displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 10.dp)
            )

            // Position display and seek bar
            PlayerPositionDisplay(
                duration = playerState.activePulseSource?.duration ?: 0.0,
                onSeek = { viewModel.seek(it) }
            )

            // Control buttons row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
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

                Spacer(modifier = Modifier.weight(1f))

                // Special effects options button
                Button(
                    onClick = onSpecialEffectsClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (sfxEnabled) activeButtonColour else ButtonDefaults.buttonColors().containerColor
                    )
                ) {
                    Icon(
                        painter = painterResource(R.drawable.special_effects),
                        contentDescription = "Special effects"
                    )
                }

                // Advanced options button
                Button(
                    onClick = onAdvancedSettingsClick
                ) {
                    Icon(
                        painter = painterResource(R.drawable.settings),
                        contentDescription = "Advanced settings"
                    )
                }
            }
            // Sync fine tune (if enabled)
            if (playerShowSyncFineTune) {
                SliderWithLabel(
                    label = "Sync fine tune (seconds)",
                    value = playerState.syncFineTune,
                    onValueChange = { Player.setSyncFineTune(it) },
                    onValueChangeFinished = { },
                    valueRange = -0.5f..0.5f,
                    steps = 99,
                    valueDisplay = { String.format(Locale.US, "%03.2f", it) }
                )
            }
        }
    }
}

@Composable
fun CombinedPanel(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    var showAdvancedSettings by remember { mutableStateOf(false) }
    var showSpecialEffects by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Main player panel
        PlayerPanel(
            viewModel = viewModel,
            onAdvancedSettingsClick = { showAdvancedSettings = true },
            onSpecialEffectsClick = { showSpecialEffects = true },
            modifier = Modifier.fillMaxWidth()
        )

        // Record panel
        RecordPanel(viewModel = viewModel)
    }

    // Dialogs
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
                SpecialEffectsPanel(
                    viewModel = viewModel,
                )
            }
        }
    }
}

@Preview
@Composable
fun PlayerPreview() {
    HowlTheme {
        val viewModel: PlayerViewModel = viewModel()
        CombinedPanel(
            viewModel = viewModel,
            modifier = Modifier.fillMaxHeight()
        )
    }
}