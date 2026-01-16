package com.example.howl

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

interface PreferenceAdapter<T> {
    fun serialise(value: T): String
    fun deserialise(value: String): T
}

object IntAdapter : PreferenceAdapter<Int> {
    override fun serialise(value: Int) = value.toString()
    override fun deserialise(value: String) = value.toInt()
}

object FloatAdapter : PreferenceAdapter<Float> {
    override fun serialise(value: Float) = value.toString()
    override fun deserialise(value: String) = value.toFloat()
}

object DoubleAdapter : PreferenceAdapter<Double> {
    override fun serialise(value: Double) = value.toString()
    override fun deserialise(value: String) = value.toDouble()
}

object BooleanAdapter : PreferenceAdapter<Boolean> {
    override fun serialise(value: Boolean) = value.toString()
    override fun deserialise(value: String) = value.toBooleanStrict()
}

object StringAdapter : PreferenceAdapter<String> {
    override fun serialise(value: String) = value
    override fun deserialise(value: String) = value
}

class EnumAdapter<T : Enum<T>>(private val values: Array<T>) : PreferenceAdapter<T> {
    override fun serialise(value: T) = value.name
    override fun deserialise(value: String) =
        values.first { it.name == value }
}

class Preference<T>(
    val name: String,
    val default: T,
    private val adapter: PreferenceAdapter<T>
) {
    // Internal StateFlow holds the current value in memory
    private val _state = MutableStateFlow(default)

    // Public immutable flow for Compose observation
    val flow: StateFlow<T> = _state.asStateFlow()

    var value: T
        get() = _state.value
        set(newValue) {
            _state.value = newValue
        }

    //fun get(): T = _state.value

    fun serialise(): String = adapter.serialise(_state.value)

    fun save() {
        Prefs.save(this)
    }

    internal fun loadFromString(str: String?) {
        if (str != null) {
            try {
                _state.value = adapter.deserialise(str)
            } catch (e: Exception) {
                // If DB value is invalid, do nothing and keep the default
                Log.e("Preferences", "Exception loading preference: $name, value: $str", e)
            }
        }
    }
}

object Prefs {
    var database: HowlDatabase? = null

    // Registry to keep track of all defined prefs to allow for bulk loading/saving
    private val registry = mutableMapOf<String, Preference<*>>()

    // A dedicated scope for database operations to keep them off the main thread
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Called at application startup
    fun initialise(db: HowlDatabase) {
        database = db
    }

    fun loadAll(onComplete: suspend () -> Unit = {}) {
        scope.launch {
            val entities = database?.preferencesDao()?.getAll()
            entities?.forEach { entity ->
                Log.d("Preferences","Loading preference ${entity.name} -> ${entity.value}")
                registry[entity.name]?.loadFromString(entity.value)
            }
            onComplete()
        }
    }

    /*suspend fun loadAll() {
        // Switch to IO context for database operation
        withContext(Dispatchers.IO) {
            val entities = database?.preferencesDao()?.getAll()
            entities?.forEach { entity ->
                Log.d("Preferences","Loading preference ${entity.name} -> ${entity.value}")
                registry[entity.name]?.loadFromString(entity.value)
            }
        }
    }*/

    fun save(pref: Preference<*>) {
        Log.d("Preferences", "Saving preference ${pref.name} -> ${pref.serialise()}")
        scope.launch {
            database?.preferencesDao()?.insert(PreferenceEntity(pref.name, pref.serialise()))
        }
    }

    fun saveAll() {
        val entities = registry.values.map {
            PreferenceEntity(it.name, it.serialise())
        }
        scope.launch {
            database?.preferencesDao()?.insertAll(entities)
        }
    }

    fun <T> register(name: String, default: T, adapter: PreferenceAdapter<T>): Preference<T> {
        if (registry.containsKey(name)) {
            throw IllegalStateException("Preference with name '$name' already registered.")
        }
        val pref = Preference(name, default, adapter)
        registry[name] = pref
        return pref
    }

    // Player settings
    val playerRemoteLatency = register("player_remote_latency", 0.2f, FloatAdapter)
    val playerPlaybackSpeed = register("player_playback_speed", 1.0f, FloatAdapter)
    val playerShowSyncFineTune = register("player_show_sync_fine_tune", false, BooleanAdapter)

    // Power related
    val powerLimitA = register("power_limit_a", 70, IntAdapter)
    val powerLimitB = register("power_limit_b", 70, IntAdapter)
    val powerStepA = register("power_step_a", 1, IntAdapter)
    val powerStepB = register("power_step_b", 1, IntAdapter)
    val powerAutoIncrementDelayA = register("power_auto_inc_delay_a", 120, IntAdapter)
    val powerAutoIncrementDelayB = register("power_auto_inc_delay_b", 120, IntAdapter)

    // Funscript related
    val funscriptVolume = register("funscript_volume", 0.8f, FloatAdapter)
    val funscriptPositionalEffectStrength = register("funscript_positional_effect_strength", 1.0f, FloatAdapter)
    val funscriptFrequencyTimeOffset = register("funscript_freq_time_offset", -0.08f, FloatAdapter)
    val funscriptFrequencyVarySpeed = register("funscript_freq_vary_speed", 0.5f, FloatAdapter)
    val funscriptFrequencyBlendRatio = register("funscript_freq_blend_ratio", 0.5f, FloatAdapter)
    val funscriptFrequencyAlgorithm = register("funscript_freq_algorithm", FrequencyAlgorithmType.POSITION, EnumAdapter(FrequencyAlgorithmType.entries.toTypedArray()))
    val funscriptFrequencyFixedA = register("funscript_freq_fixed_a", 0.8f, FloatAdapter)
    val funscriptFrequencyFixedB = register("funscript_freq_fixed_b", 0.8f, FloatAdapter)

    // Player special effects
    val sfxEnabled = register("sfx_enabled", false, BooleanAdapter)
    val sfxFrequencyInvertA = register("sfx_freq_invert_a", false, BooleanAdapter)
    val sfxFrequencyInvertB = register("sfx_freq_invert_b", false, BooleanAdapter)
    val sfxAmplitudeScaleA = register("sfx_amp_scale_a", 1.0f, FloatAdapter)
    val sfxAmplitudeScaleB = register("sfx_amp_scale_b", 1.0f, FloatAdapter)
    val sfxFrequencyFeelA = register("sfx_freq_feel_a", 1.0f, FloatAdapter)
    val sfxFrequencyFeelB = register("sfx_freq_feel_b", 1.0f, FloatAdapter)
    val sfxAmplitudeFeelA = register("sfx_amp_feel_a", 1.0f, FloatAdapter)
    val sfxAmplitudeFeelB = register("sfx_amp_feel_b", 1.0f, FloatAdapter)
    val sfxAmplitudeNoiseAmount = register("sfx_amp_noise_amount", 0.0f, FloatAdapter)
    val sfxAmplitudeNoiseSpeed = register("sfx_amp_noise_speed", 5.0f, FloatAdapter)
    val sfxFrequencyNoiseAmount = register("sfx_freq_noise_amount", 0.0f, FloatAdapter)
    val sfxFrequencyNoiseSpeed = register("sfx_freq_noise_speed", 5.0f, FloatAdapter)
    val sfxFrequencyAdjustA = register("sfx_freq_adjust_a", 0.0f, FloatAdapter)
    val sfxFrequencyAdjustB = register("sfx_freq_adjust_b", 0.0f, FloatAdapter)

    // Generator related
    val generatorAutoChange = register("generator_auto_change", false, BooleanAdapter)
    val generatorSpeedChangeProbability = register("generator_speed_change_prob", 0.2f, FloatAdapter)
    val generatorAmplitudeChangeProbability = register("generator_amp_change_prob", 0.2f, FloatAdapter)
    val generatorFrequencyChangeProbability = register("generator_freq_change_prob", 0.2f, FloatAdapter)
    val generatorWaveChangeProbability = register("generator_wave_change_prob", 0.2f, FloatAdapter)

    // Activity related
    val activityChangeProbability = register("activity_change_prob", 0.0f, FloatAdapter)

    // Misc options
    val miscShowPowerMeter = register("misc_show_power_meter", true, BooleanAdapter)
    val miscShowDebugLog = register("misc_show_debug_log", false, BooleanAdapter)

    // Remote access
    val remoteAccess = register("remote_access", false, BooleanAdapter)
    val remoteAPIKey = register("remote_api_key", "changeme", StringAdapter)

    // Output options
    val outputType = register("output_type", OutputType.COYOTE3, EnumAdapter(OutputType.entries.toTypedArray()))
    // Coyote 3 output parameters
    val outputC3FrequencyBalanceA = register("output_c3_freq_balance_a", 200, IntAdapter)
    val outputC3FrequencyBalanceB = register("output_c3_freq_balance_b", 200, IntAdapter)
    val outputC3IntensityBalanceA = register("output_c3_intensity_balance_a", 0, IntAdapter)
    val outputC3IntensityBalanceB = register("output_c3_intensity_balance_b", 0, IntAdapter)
    // Audio output parameters
    val outputAudioWaveShape = register("output_audio_wave_shape", AudioWaveShape.SINE, EnumAdapter(AudioWaveShape.entries.toTypedArray()))
    val outputAudioCarrierShape = register("output_audio_carrier_shape", AudioWaveShape.SINE, EnumAdapter(AudioWaveShape.entries.toTypedArray()))
    val outputAudioMaxFrequency = register("output_audio_max_freq", 200, IntAdapter)
    val outputAudioMinFrequency = register("output_audio_min_freq", 50, IntAdapter)
    val outputAudioCarrierPhaseType = register("output_audio_carrier_phase", AudioPhaseType.OFFSET, EnumAdapter(AudioPhaseType.entries.toTypedArray()))
    val outputAudioCarrierFrequency = register("output_audio_carrier_freq", 1000, IntAdapter)
    val outputAudioWaveletWidth = register("output_audio_wavelet_width", 5, IntAdapter)
    val outputAudioWaveletFade = register("output_audio_wavelet_fade", 0.5f, FloatAdapter)
}

@Composable
fun <T> Preference<T>.collectAsState(): State<T> {
    return this.flow.collectAsState()
}

@Composable
fun <T> Preference<T>.collectAsStateWithLifecycle(): State<T> {
    return this.flow.collectAsStateWithLifecycle()
}