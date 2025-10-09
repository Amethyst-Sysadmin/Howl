package com.example.howl

import android.util.Log
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlin.Boolean
import kotlin.time.TimeMark
import kotlin.Float

const val howlVersion = "0.6"
const val showDeveloperOptions = false

enum class OutputType(val displayName: String) {
    COYOTE3("Coyote 3"),
    AUDIO_CONTINUOUS("Audio (continuous)"),
    AUDIO_WAVELET("Audio (wavelet)"),
}

object DataRepository {
    var database: HowlDatabase? = null

    const val chartUpdatesPerSecond = 50
    const val chartUpdateMillis = 1000L / chartUpdatesPerSecond

    private val _lastPulse = MutableStateFlow(Pulse())
    val lastPulse: StateFlow<Pulse> = _lastPulse.asStateFlow()

    const val PULSE_HISTORY_SIZE = 200
    private val _pulseHistoryBuffer: CircularBuffer<Pulse> = CircularBuffer(PULSE_HISTORY_SIZE)
    private val _pulseHistory = MutableStateFlow<List<Pulse>>(emptyList())
    val pulseHistory: StateFlow<List<Pulse>> = _pulseHistory.asStateFlow()
    @OptIn(FlowPreview::class)
    val throttledPulseHistory: Flow<List<Pulse>> = _pulseHistory.sample(chartUpdateMillis)

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    // split out of playerState because it changes very frequently and most observers don't
    // actually need it (helps reduce UI recompositions)
    private val _playerPosition = MutableStateFlow(0.0)
    val playerPosition: StateFlow<Double> = _playerPosition.asStateFlow()

    private val _playerAdvancedControlsState = MutableStateFlow(PlayerAdvancedControlsState())
    val playerAdvancedControlsState: StateFlow<PlayerAdvancedControlsState> = _playerAdvancedControlsState.asStateFlow()

    private val _playerSpecialEffectsState = MutableStateFlow(PlayerSpecialEffectsState())
    val playerSpecialEffectsState: StateFlow<PlayerSpecialEffectsState> = _playerSpecialEffectsState.asStateFlow()

    private val _mainOptionsState = MutableStateFlow(MainOptionsState())
    val mainOptionsState: StateFlow<MainOptionsState> = _mainOptionsState.asStateFlow()

    private val _miscOptionsState = MutableStateFlow(MiscOptionsState())
    val miscOptionsState: StateFlow<MiscOptionsState> = _miscOptionsState.asStateFlow()

    private val _outputState = MutableStateFlow(OutputState())
    val outputState: StateFlow<OutputState> = _outputState.asStateFlow()

    private val _developerOptionsState = MutableStateFlow(DeveloperOptionsState())
    val developerOptionsState: StateFlow<DeveloperOptionsState> = _developerOptionsState.asStateFlow()

    private val _generatorState = MutableStateFlow(GeneratorState())
    val generatorState: StateFlow<GeneratorState> = _generatorState.asStateFlow()

    private val _activityState = MutableStateFlow(ActivityState())
    val activityState: StateFlow<ActivityState> = _activityState.asStateFlow()

    private val _coyoteBatteryLevel = MutableStateFlow(0)
    val coyoteBatteryLevel: StateFlow<Int> = _coyoteBatteryLevel.asStateFlow()

    private val _coyoteConnectionStatus = MutableStateFlow(ConnectionStatus.Disconnected)
    val coyoteConnectionStatus: StateFlow<ConnectionStatus> = _coyoteConnectionStatus.asStateFlow()

    private val _coyoteParametersState = MutableStateFlow(Coyote3Parameters())
    val coyoteParametersState: StateFlow<Coyote3Parameters> = _coyoteParametersState.asStateFlow()

    @OptIn(FlowPreview::class)
    val throttledlastPulse: Flow<Pulse> = combine(
        _lastPulse,
        _playerState
    ) { pulse, playerState ->
        if (playerState.isPlaying) pulse else Pulse()
    }.sample(chartUpdateMillis)

    fun setCoyoteParametersState(newCoyoteParametersState: Coyote3Parameters) {
        _coyoteParametersState.update { newCoyoteParametersState }
    }

    fun setActivityState(newActivityState: ActivityState) {
        _activityState.update { newActivityState }
    }

    fun setPlayerState(newPlayerState: PlayerState) {
        _playerState.update { newPlayerState }
    }

    fun setPlayerAdvancedControlsState(newPlayerAdvancedControlsState: PlayerAdvancedControlsState) {
        _playerAdvancedControlsState.update { newPlayerAdvancedControlsState }
    }

    fun setPlayerSpecialEffectsState(newPlayerSpecialEffectsState: PlayerSpecialEffectsState) {
        _playerSpecialEffectsState.update { newPlayerSpecialEffectsState }
    }

    fun setMainOptionsState(newMainOptionsState: MainOptionsState) {
        _mainOptionsState.update { newMainOptionsState }
    }

    fun setMiscOptionsState(newMiscOptionsState: MiscOptionsState) {
        _miscOptionsState.update { newMiscOptionsState }
    }

    fun setOutputState(newOutputState: OutputState) {
        _outputState.update { newOutputState }
    }

    fun setDeveloperOptionsState(newDeveloperOptionsState: DeveloperOptionsState) {
        _developerOptionsState.update { newDeveloperOptionsState }
    }

    fun setCoyoteBatteryLevel(percent: Int) {
        _coyoteBatteryLevel.update { percent }
    }

    fun setCoyoteConnectionStatus(status: ConnectionStatus) {
        _coyoteConnectionStatus.update { status }
    }

    fun setChannelAPower(power: Int) {
        val newPower: Int = power.coerceIn(0..coyoteParametersState.value.channelALimit)
        _mainOptionsState.update { it.copy(channelAPower = newPower)}
    }

    fun setChannelBPower(power: Int) {
        val newPower: Int = power.coerceIn(0..coyoteParametersState.value.channelBLimit)
        _mainOptionsState.update { it.copy(channelBPower = newPower)}
    }

    fun setGlobalMute(muted: Boolean) {
        _mainOptionsState.update { it.copy(globalMute = muted)}
    }

    fun setAutoIncreasePower(autoIncrease: Boolean) {
        _mainOptionsState.update { it.copy(autoIncreasePower = autoIncrease)}
    }

    fun setPulseChartMode(mode: PulseChartMode) {
        _mainOptionsState.update { it.copy(pulseChartMode = mode)}
    }

    fun setSwapChannels(swap: Boolean) {
        _mainOptionsState.update { it.copy(swapChannels = swap)}
    }

    fun setFrequencyRange(range: IntRange, overrideSelectedSubset: Boolean = false) {
        _mainOptionsState.update { currentState ->
            if (overrideSelectedSubset) {
                currentState.copy(
                    frequencyRange = range,
                    minFrequency = range.start,
                    maxFrequency = range.endInclusive,
                    frequencyRangeSelectedSubset = 0.0f..1.0f
                )
            } else {
                val minFreq = range.lerp(currentState.frequencyRangeSelectedSubset.start.toDouble())
                val maxFreq = range.lerp(currentState.frequencyRangeSelectedSubset.endInclusive.toDouble())
                currentState.copy(
                    frequencyRange = range,
                    minFrequency = minFreq,
                    maxFrequency = maxFreq
                )
            }
        }
    }

    fun setFrequencyRangeSelectedSubset(range: ClosedFloatingPointRange<Float>) {
        _mainOptionsState.update { currentState ->
            val minFreq = currentState.frequencyRange.lerp(range.start.toDouble())
            val maxFreq = currentState.frequencyRange.lerp(range.endInclusive.toDouble())
            currentState.copy(
                frequencyRangeSelectedSubset = range,
                minFrequency = minFreq,
                maxFrequency = maxFreq
            )
        }
    }

    fun setPlayerPosition(position: Double) {
        _playerPosition.update { position }
    }

    fun setPlayerPulseSource(source: PulseSource?) {
        _playerState.update { it.copy(activePulseSource = source) }
    }

    fun setGeneratorState(newGeneratorState: GeneratorState) {
        _generatorState.update { newGeneratorState }
    }

    fun addPulsesToHistory(newPulses: List<Pulse>) {
        newPulses.forEach { _pulseHistoryBuffer.add(it, overwrite = true) }
        _pulseHistory.update { _pulseHistoryBuffer.toList() }
        _lastPulse.update { _pulseHistoryBuffer.last()!! }
    }

    fun initialise(db: HowlDatabase) {
        database = db
    }

    suspend fun saveSettings() {
        HLog.d("DataRepository", "Saving settings")
        val settings = SavedSettings(
            channelALimit = coyoteParametersState.value.channelALimit,
            channelBLimit = coyoteParametersState.value.channelBLimit,
            channelAIntensityBalance = coyoteParametersState.value.channelAIntensityBalance,
            channelBIntensityBalance = coyoteParametersState.value.channelBIntensityBalance,
            channelAFrequencyBalance = coyoteParametersState.value.channelAFrequencyBalance,
            channelBFrequencyBalance = coyoteParametersState.value.channelBFrequencyBalance,
            showSyncFineTune = playerAdvancedControlsState.value.showSyncFineTune,
            playbackSpeed = playerAdvancedControlsState.value.playbackSpeed,
            funscriptVolume = playerAdvancedControlsState.value.funscriptVolume,
            funscriptPositionalEffectStrength = playerAdvancedControlsState.value.funscriptPositionalEffectStrength,
            funscriptFrequencyTimeOffset = playerAdvancedControlsState.value.funscriptFrequencyTimeOffset,
            funscriptFrequencyVarySpeed = playerAdvancedControlsState.value.funscriptFrequencyVarySpeed,
            funscriptFrequencyBlendRatio = playerAdvancedControlsState.value.funscriptFrequencyBlendRatio,
            funscriptFrequencyAlgorithm = playerAdvancedControlsState.value.funscriptFrequencyAlgorithm,
            funscriptAmplitudeAlgorithm = playerAdvancedControlsState.value.funscriptAmplitudeAlgorithm,
            funscriptRemoteLatency = playerAdvancedControlsState.value.funscriptRemoteLatency,
            specialEffectsEnabled = playerSpecialEffectsState.value.specialEffectsEnabled,
            frequencyInversionA = playerSpecialEffectsState.value.frequencyInversionA,
            frequencyInversionB = playerSpecialEffectsState.value.frequencyInversionB,
            scaleAmplitudeA = playerSpecialEffectsState.value.scaleAmplitudeA,
            scaleAmplitudeB = playerSpecialEffectsState.value.scaleAmplitudeB,
            frequencyFeel = playerSpecialEffectsState.value.frequencyFeel,
            amplitudeNoiseSpeed = playerSpecialEffectsState.value.amplitudeNoiseSpeed,
            amplitudeNoiseAmount = playerSpecialEffectsState.value.amplitudeNoiseAmount,
            frequencyNoiseSpeed = playerSpecialEffectsState.value.frequencyNoiseSpeed,
            frequencyNoiseAmount = playerSpecialEffectsState.value.frequencyNoiseAmount,
            autoChange = generatorState.value.autoChange,
            speedChangeProbability = generatorState.value.speedChangeProbability,
            amplitudeChangeProbability = generatorState.value.amplitudeChangeProbability,
            frequencyChangeProbability = generatorState.value.frequencyChangeProbability,
            waveChangeProbability = generatorState.value.waveChangeProbability,
            activityChangeProbability = activityState.value.activityChangeProbability,
            remoteAccess = miscOptionsState.value.remoteAccess,
            showPowerMeter = miscOptionsState.value.showPowerMeter,
            smootherCharts = miscOptionsState.value.smootherCharts,
            showDebugLog = miscOptionsState.value.showDebugLog,
            powerStepSizeA = miscOptionsState.value.powerStepSizeA,
            powerStepSizeB = miscOptionsState.value.powerStepSizeB,
            powerAutoIncrementDelayA = miscOptionsState.value.powerAutoIncrementDelayA,
            powerAutoIncrementDelayB = miscOptionsState.value.powerAutoIncrementDelayB,
            outputType = outputState.value.outputType,
            audioOutputMinFrequency = outputState.value.audioOutputMinFrequency,
            audioOutputMaxFrequency = outputState.value.audioOutputMaxFrequency,
            audioWaveShape = outputState.value.audioWaveShape,
            audioCarrierShape = outputState.value.audioCarrierShape,
            audioCarrierPhaseType = outputState.value.audioCarrierPhaseType,
            audioCarrierFrequency = outputState.value.audioCarrierFrequency,
            audioWaveletWidth = outputState.value.audioWaveletWidth,
            audioWaveletFade = outputState.value.audioWaveletFade,
        )
        database?.savedSettingsDao()?.updateSettings(settings)
    }

    suspend fun loadSettings(){
        HLog.d("DataRepository", "Loading settings")
        var settings = database?.savedSettingsDao()?.getSettings()
        if (settings==null)
            settings = SavedSettings()
        setCoyoteParametersState(Coyote3Parameters(
            channelALimit = settings.channelALimit,
            channelBLimit = settings.channelBLimit,
            channelAIntensityBalance = settings.channelAIntensityBalance,
            channelBIntensityBalance = settings.channelBIntensityBalance,
            channelAFrequencyBalance = settings.channelAFrequencyBalance,
            channelBFrequencyBalance = settings.channelBFrequencyBalance
        ))
        setPlayerAdvancedControlsState(PlayerAdvancedControlsState(
            showSyncFineTune = settings.showSyncFineTune,
            playbackSpeed = settings.playbackSpeed,
            funscriptVolume = settings.funscriptVolume,
            funscriptPositionalEffectStrength = settings.funscriptPositionalEffectStrength,
            funscriptFrequencyTimeOffset = settings.funscriptFrequencyTimeOffset,
            funscriptFrequencyVarySpeed = settings.funscriptFrequencyVarySpeed,
            funscriptFrequencyBlendRatio = settings.funscriptFrequencyBlendRatio,
            funscriptFrequencyAlgorithm = settings.funscriptFrequencyAlgorithm,
            funscriptAmplitudeAlgorithm = settings.funscriptAmplitudeAlgorithm,
            funscriptRemoteLatency = settings.funscriptRemoteLatency
        ))
        setPlayerSpecialEffectsState(PlayerSpecialEffectsState(
            specialEffectsEnabled = settings.specialEffectsEnabled,
            frequencyInversionA = settings.frequencyInversionA,
            frequencyInversionB = settings.frequencyInversionB,
            scaleAmplitudeA = settings.scaleAmplitudeA,
            scaleAmplitudeB = settings.scaleAmplitudeB,
            frequencyFeel = settings.frequencyFeel,
            amplitudeNoiseSpeed = settings.amplitudeNoiseSpeed,
            amplitudeNoiseAmount = settings.amplitudeNoiseAmount,
            frequencyNoiseSpeed = settings.frequencyNoiseSpeed,
            frequencyNoiseAmount = settings.frequencyNoiseAmount
        ))
        setGeneratorState(generatorState.value.copy(
            autoChange = settings.autoChange,
            speedChangeProbability = settings.speedChangeProbability,
            amplitudeChangeProbability = settings.amplitudeChangeProbability,
            frequencyChangeProbability = settings.frequencyChangeProbability,
            waveChangeProbability = settings.waveChangeProbability,
        ))
        setMiscOptionsState(MiscOptionsState(
            remoteAccess = settings.remoteAccess,
            showPowerMeter = settings.showPowerMeter,
            smootherCharts = settings.smootherCharts,
            showDebugLog = settings.showDebugLog,
            powerStepSizeA = settings.powerStepSizeA,
            powerStepSizeB = settings.powerStepSizeB,
            powerAutoIncrementDelayA = settings.powerAutoIncrementDelayA,
            powerAutoIncrementDelayB = settings.powerAutoIncrementDelayB
        ))
        setActivityState(activityState.value.copy(
            activityChangeProbability = settings.activityChangeProbability
        ))
        setOutputState(outputState.value.copy(
            outputType = settings.outputType,
            audioOutputMinFrequency = settings.audioOutputMinFrequency,
            audioOutputMaxFrequency = settings.audioOutputMaxFrequency,
            audioWaveShape = settings.audioWaveShape,
            audioCarrierShape = settings.audioCarrierShape,
            audioCarrierPhaseType = settings.audioCarrierPhaseType,
            audioCarrierFrequency = settings.audioCarrierFrequency,
            audioWaveletWidth = settings.audioWaveletWidth,
            audioWaveletFade = settings.audioWaveletFade,
        ))
    }

    data class OutputState(
        val outputType: OutputType = OutputType.AUDIO_WAVELET,
        val audioOutputMaxFrequency: Int = 400,
        val audioOutputMinFrequency: Int = 50,
        val audioWaveShape: AudioWaveShape = AudioWaveShape.SINE,
        val audioCarrierShape: AudioWaveShape = AudioWaveShape.SINE,
        val audioCarrierPhaseType: AudioPhaseType = AudioPhaseType.OFFSET,
        val audioCarrierFrequency: Int = 1000,
        val audioWaveletWidth: Int = 5,
        val audioWaveletFade: Float = 0.5f,
    )

    data class ActivityState(
        val currentActivityDisplayName: String = "",
        val activityChangeProbability: Float = 0.0f,
    )

    data class GeneratorState(
        val initialised: Boolean = false,
        val channelAInfo: GeneratorChannelInfo = GeneratorChannelInfo(),
        val channelBInfo: GeneratorChannelInfo = GeneratorChannelInfo(),
        val autoChange: Boolean = true,
        val speedChangeProbability: Double = 0.2,
        val amplitudeChangeProbability: Double = 0.2,
        val frequencyChangeProbability: Double = 0.2,
        val waveChangeProbability: Double = 0.2,
    )

    data class PlayerState(
        val activePulseSource: PulseSource? = null,
        val startPosition: Double = 0.0,
        val isPlaying: Boolean = false,
        val startTime: TimeMark? = null,
        val syncFineTune: Float = 0.0f,
    )

    data class PlayerSpecialEffectsState (
        val specialEffectsEnabled: Boolean = false,
        val frequencyInversionA: Boolean = false,
        val frequencyInversionB: Boolean = false,
        val scaleAmplitudeA: Float = 1.0f,
        val scaleAmplitudeB: Float = 1.0f,
        var frequencyFeel: Float = 1.0f,
        val amplitudeNoiseSpeed: Float = 5.0f,
        val amplitudeNoiseAmount: Float = 0.0f,
        val frequencyNoiseSpeed: Float = 5.0f,
        val frequencyNoiseAmount: Float = 0.0f,
    )

    data class PlayerAdvancedControlsState (
        val showSyncFineTune: Boolean = false,
        val playbackSpeed: Float = 1.0f,
        var funscriptVolume: Float = 0.75f,
        val funscriptPositionalEffectStrength: Float = 1.0f,
        var funscriptFrequencyTimeOffset: Float = 0.1f,
        var funscriptFrequencyVarySpeed: Float = 0.5f,
        var funscriptFrequencyBlendRatio: Float = 0.5f,
        val funscriptAmplitudeAlgorithm: AmplitudeAlgorithmType = AmplitudeAlgorithmType.DEFAULT,
        val funscriptFrequencyAlgorithm: FrequencyAlgorithmType = FrequencyAlgorithmType.BLEND,
        val funscriptRemoteLatency: Float = 0.18f,
    )

    data class MainOptionsState (
        val channelAPower: Int = 0,
        val channelBPower: Int = 0,
        val globalMute: Boolean = false,
        val autoIncreasePower: Boolean = false,
        val swapChannels: Boolean = false,
        val frequencyRange: IntRange = 100..1000,
        val frequencyRangeSelectedSubset: ClosedFloatingPointRange<Float> = 0.0f..1.0f,
        val minFrequency: Int = 100,
        val maxFrequency: Int = 1000,
        val pulseChartMode: PulseChartMode = PulseChartMode.Off
    )

    data class MiscOptionsState (
        val remoteAccess: Boolean = false,
        val showPowerMeter: Boolean = true,
        val smootherCharts: Boolean = true,
        val showDebugLog: Boolean = false,
        val powerStepSizeA: Int = 1,
        val powerStepSizeB: Int = 1,
        val powerAutoIncrementDelayA: Int = 120,
        val powerAutoIncrementDelayB: Int = 120,
    )

    data class DeveloperOptionsState (
        val developerFrequencyExponent: Float = 1.0f,
        val developerFrequencyGain: Float = 1.0f,
        val developerFrequencyAdjustA: Float = 0.0f,
        val developerFrequencyAdjustB: Float = 0.0f,
        val developerAmplitudeExponent: Float = 1.0f,
        val developerAmplitudeGain: Float = 1.0f,
    )
}