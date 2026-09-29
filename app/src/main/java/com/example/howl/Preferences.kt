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
import kotlinx.serialization.json.Json

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

object StringListAdapter : PreferenceAdapter<List<String>> {
    override fun serialise(value: List<String>): String {
        return Json.encodeToString(value)
    }

    override fun deserialise(value: String): List<String> {
        return Json.decodeFromString(value)
    }
}

class EnumAdapter<T : Enum<T>>(private val values: Array<T>) : PreferenceAdapter<T> {
    override fun serialise(value: T) = value.name
    override fun deserialise(value: String) =
        values.first { it.name == value }
}

class EnumListAdapter<T : Enum<T>>(private val values: Array<T>) : PreferenceAdapter<List<T>> {
    override fun serialise(value: List<T>): String {
        return Json.encodeToString(value.map { it.name })
    }

    override fun deserialise(value: String): List<T> {
        val names: List<String> = Json.decodeFromString(value)
        return names.map { name ->
            values.first { it.name == name }
        }
    }
}

object OutputStateListAdapter : PreferenceAdapter<List<OutputState>> {
    override fun serialise(value: List<OutputState>): String {
        return Json.encodeToString(value)
    }

    override fun deserialise(value: String): List<OutputState> {
        return try {
            Json.decodeFromString(value)
        } catch (e: Exception) {
            emptyList()
        }
    }
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

    fun serialise(): String = adapter.serialise(_state.value)

    fun save() {
        Prefs.save(this)
    }

    fun resetToDefault() {
        _state.value = default
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

    fun resetAll(exceptions: List<Preference<*>> = emptyList()) {
        val exceptionNames = exceptions.map { it.name }.toSet()
        val toReset = registry.values.filter { it.name !in exceptionNames }
        performReset(toReset)
    }

    fun resetByPrefix(prefix: String, exceptions: List<Preference<*>> = emptyList()) {
        val exceptionNames = exceptions.map { it.name }.toSet()
        val toReset = registry.values.filter {
            it.name.startsWith(prefix) && it.name !in exceptionNames
        }
        performReset(toReset)
    }

    private fun performReset(prefs: Collection<Preference<*>>) {
        if (prefs.isEmpty()) return

        // Reset in-memory values
        prefs.forEach { it.resetToDefault() }

        // Remove the preference rows from the database.
        // No need to write anything since an absent row implies the default.
        val names = prefs.map { it.name }
        Log.d("Preferences", "Resetting ${names.size} preference(s)")
        scope.launch {
            database?.preferencesDao()?.deleteByNames(names)
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

    // Global calibrations
    val calibrationPositionalEffectCurve = register("calibration_positional_effect_curve", 0.5f, FloatAdapter)

    // Funscript related
    val funscriptVolume = register("funscript_volume", 0.8f, FloatAdapter)
    val funscriptPositionalEffectStrength = register("funscript_positional_effect_strength", 1.0f, FloatAdapter)
    val funscriptDirectionalFreqShift = register("funscript_directional_freq_shift", 0.15f, FloatAdapter)
    val funscriptFreqEnergyProportion = register("funscript_freq_energy_proportion", 0.2f, FloatAdapter)
    val funscriptFlipDirectionalFreqShift = register("funscript_flip_directional_freq_shift", false, BooleanAdapter)
    val funscriptNormaliseAxes = register("funscript_normalise_axes", true, BooleanAdapter)
    val funscriptSmoothingSigma = register("funscript_smoothing_sigma", 0.2f, FloatAdapter)
    // 0.0 = Linear, 1.0 = Fritsch-Carlson, >1.0 = Exaggerated Curves
    val funscriptMotionProfile = register("funscript_motion_profile", 0.6f, FloatAdapter)

    // Generator related
    val generatorAutoChange = register("generator_auto_change", false, BooleanAdapter)
    val generatorSpeedChangeProbability = register("generator_speed_change_prob", 0.2f, FloatAdapter)
    val generatorAmplitudeChangeProbability = register("generator_amp_change_prob", 0.2f, FloatAdapter)
    val generatorFrequencyChangeProbability = register("generator_freq_change_prob", 0.2f, FloatAdapter)
    val generatorWaveChangeProbability = register("generator_wave_change_prob", 0.2f, FloatAdapter)

    // Activity related (global)
    val activityChangeProbability = register("activity_change_prob", 0.0f, FloatAdapter)
    val activityExcludedFromRandom = register("activity_excluded_from_random", listOf(ActivityType.CALIBRATE_POWER, ActivityType.CALIBRATE_FREQ,
        ActivityType.CALIBRATE_POSITION), EnumListAdapter(ActivityType.entries.toTypedArray()))

    // Activity related (individual activity options)
    val activityVibePulseDutyCycle = register("activity_vibe_pulse_duty_cycle", 1.0f, FloatAdapter)
    val activityVibePulseTime = register("activity_vibe_pulse_time", 0.3f, FloatAdapter)
    val activityVibeHoldProbability = register("activity_vibe_hold_probability", 0.25f, FloatAdapter)
    val activityChaosCycleTime = register("activity_chaos_cycle_time", 1.0f, FloatAdapter)
    val activityLuxuryHJBonusProbability = register("activity_luxury_hj_bonus_prob", 0.7f, FloatAdapter)
    val activityLuxuryHJAmplitudeJitter = register("activity_luxury_hj_amplitude_jitter", 0.15f, FloatAdapter)
    val activityLuxuryHJTimingJitter = register("activity_luxury_hj_timing_jitter", 0.15f, FloatAdapter)
    val activitySimplexPreset = register("activity_simplex_preset", SimplexPreset.PRO, EnumAdapter(SimplexPreset.entries.toTypedArray()))

    // Manual control
    val manualSmoothdampTime = register("manual_smoothdamp_time", 0.1f, FloatAdapter)
    val manualTouchpadCenterRate = register("manual_touchpad_center_rate", 0.6f, FloatAdapter)


    // Misc options
    val miscShowPowerMeter = register("misc_show_power_meter", true, BooleanAdapter)
    val miscShowFunscriptMeters = register("misc_show_funscript_meters", true, BooleanAdapter)
    val miscShowDebugLog = register("misc_show_debug_log", false, BooleanAdapter)
    val miscPulseChartStyle = register("misc_pulse_chart_style", PulseChartStyle.Point, EnumAdapter(
        PulseChartStyle.entries.toTypedArray()))

    // Remote access
    val remoteAccess = register("remote_access", false, BooleanAdapter)
    val remoteAPIKey = register("remote_api_key", "changeme", StringAdapter)

    // Output states (compound preference with various fields for each active output)
    val outputStates = register("output_states", emptyList<OutputState>(), OutputStateListAdapter)
}

@Composable
fun <T> Preference<T>.collectAsState(): State<T> {
    return this.flow.collectAsState()
}

@Composable
fun <T> Preference<T>.collectAsStateWithLifecycle(): State<T> {
    return this.flow.collectAsStateWithLifecycle()
}