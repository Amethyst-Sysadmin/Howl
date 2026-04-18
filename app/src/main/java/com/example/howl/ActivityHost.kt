package com.example.howl

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.howl.ui.theme.HowlTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

enum class ActivityType(val displayName: String, val iconResId: Int) {
    LICKS("Infinite licks", R.drawable.grin_tongue) { override fun create() = LickActivity() },
    PENETRATION("Penetration", R.drawable.rocket) { override fun create() = PenetrationActivity() },
    VIBRATOR("Sliding vibrator", R.drawable.vibration) { override fun create() = VibroActivity() },
    MILKMASTER("Milkmaster 3000", R.drawable.cow) { override fun create() = MilkerActivity() },
    CHAOS("Chaos", R.drawable.chaos) { override fun create() = ChaosActivity() },
    HJ("Luxury HJ", R.drawable.hand) { override fun create() = LuxuryHJActivity() },
    OPPOSITES("Opposites", R.drawable.yin_yang) { override fun create() = OppositesActivity() },
    CALIBRATION1("Calibration 1", R.drawable.swapvert) { override fun create() =
        Calibration1Activity() },
    CALIBRATION2("Calibration 2", R.drawable.calibration) { override fun create() =
        Calibration2Activity() },
    BJ("BJ Megamix", R.drawable.lips) { override fun create() = BJActivity() },
    FASTSLOW("Fast/slow", R.drawable.speed) { override fun create() =
        FastSlowActivity() },
    SIMPLEX("Simplex", R.drawable.wave_triangle) { override fun create() = SimplexActivity() },
    RELENTLESS("Relentless", R.drawable.hammer) { override fun create() = RelentlessActivity() },
    OVERFLOWING("Overflowing", R.drawable.water_drop) { override fun create() =
        OverflowingActivity() },
    SUCCUBUS("Succubus", R.drawable.succubus) { override fun create() = SuccubusActivity() };

    abstract fun create(): Activity
}

object ActivityHost : PulseSource {
    val PROBABILITY_RANGE: ClosedFloatingPointRange<Float> = 0.0f..1.0f

    data class ActivityState(
        val type: ActivityType,
        val instance: Activity
    )

    private val _displayName = MutableStateFlow("Activity output")
    override val displayName = _displayName.asStateFlow()
    override var duration: Double? = null
    override val isFinite: Boolean = false
    override val shouldLoop: Boolean = false
    override var readyToPlay: Boolean = true
    override var isRemote: Boolean = false

    private var lastUpdateTime = -1.0
    private var lastSimulationTime = -1.0

    private val _currentActivity = MutableStateFlow(switchActivity(randomActivityType()))
    val currentActivity: StateFlow<ActivityState> = _currentActivity.asStateFlow()

    override fun updateState(currentTime: Double) {
        if (lastUpdateTime !in 0.0..currentTime)
            lastUpdateTime = currentTime

        val changeProbability = Prefs.activityChangeProbability.value
        val timeDelta = currentTime - lastUpdateTime

        val probability = (changeProbability * 3.0 * timeDelta) / 60.0
        if (Random.nextDouble() < probability) {
            randomActivity()
        }

        lastUpdateTime = currentTime
    }

    override fun getPulseAtTime(time: Double): Pulse {
        if (lastSimulationTime !in 0.0..time) {
            lastSimulationTime = time
        }

        //Log.d("ActivityHost", "Time: $time")
        val simulationTimeDelta = time - lastSimulationTime
        lastSimulationTime = time

        _currentActivity.value.instance.runSimulation(simulationTimeDelta)
        return _currentActivity.value.instance.getPulse()
    }

    private fun switchActivity(type: ActivityType): ActivityState {
        _displayName.value = "Activity (${type.displayName})"
        lastSimulationTime = -1.0
        lastUpdateTime = -1.0

        // Create and initialize the activity instance
        return ActivityState(type, type.create().apply { initialise() })
    }

    fun setCurrentActivity(type: ActivityType) {
        _currentActivity.value = switchActivity(type)
    }

    fun randomActivityType(avoid: ActivityType? = null): ActivityType {
        val excluded = Prefs.activityExcludedFromRandom.value
        val candidates = ActivityType.entries.filter {
            it !in excluded && it != avoid
        }
        // Fallback: If everything is excluded, pick anything EXCEPT the 'avoid' type if possible
        return candidates.randomOrNull()
            ?: ActivityType.entries.filter { it != avoid }.randomOrNull()
            ?: ActivityType.entries.random()
    }

    fun randomActivity() {
        val nextType = randomActivityType(avoid = _currentActivity.value.type)
        setCurrentActivity(nextType)
    }
}

class ActivityHostViewModel : ViewModel() {
    fun toggleExclusion(type: ActivityType) {
        val currentExcluded = Prefs.activityExcludedFromRandom.value.toMutableList()

        if (type in currentExcluded) {
            currentExcluded.remove(type)
        } else {
            currentExcluded.add(type)
        }

        Prefs.activityExcludedFromRandom.value = currentExcluded
        Prefs.activityExcludedFromRandom.save()
    }
    fun setCurrentActivity(type: ActivityType) {
        ActivityHost.setCurrentActivity(type)
    }
    fun stop() {
        Player.stopPlayer()
    }
    fun start() {
        Player.switchPulseSource(ActivityHost)
        Player.startPlayer()
    }
}

@Composable
fun ActivityHostPanel(
    viewModel: ActivityHostViewModel,
    modifier: Modifier = Modifier
) {
    val currentState by ActivityHost.currentActivity.collectAsStateWithLifecycle()
    val currentType = currentState.type
    val currentInstance = currentState.instance
    val activityChangeProbability by Prefs.activityChangeProbability.collectAsStateWithLifecycle()
    val excludedActivities by Prefs.activityExcludedFromRandom.collectAsStateWithLifecycle()
    val playerState by Player.playerState.collectAsStateWithLifecycle()
    val isPlaying = playerState.isPlaying && playerState.activePulseSource == ActivityHost
    val excludedColor = MaterialTheme.colorScheme.error

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // First Surface: Controls and Probability
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Top Row: Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 1. OptionPicker
                    OptionPicker(
                        currentValue = currentType,
                        onValueChange = { viewModel.setCurrentActivity(it) },
                        options = ActivityType.entries,
                        getText = { it.displayName },
                        getIcon = { it.iconResId },
                        textColor = {
                            if (it in excludedActivities) excludedColor else Color.Unspecified
                        },
                        size = OptionPickerSize.Large,
                        modifier = Modifier.weight(1f)
                    )

                    // 2. Play/Pause Button
                    Button(
                        onClick = {
                            if (isPlaying)
                                viewModel.stop()
                            else
                                viewModel.start()
                        }
                    ) {
                        if (isPlaying) {
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

                    // 3. Restart Button
                    Button(
                        onClick = {
                            viewModel.setCurrentActivity(currentType)
                        }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.replay),
                            contentDescription = "Restart"
                        )
                    }
                }

                SliderWithLabel(
                    label = "Random activity change probability",
                    value = activityChangeProbability,
                    onValueChange = { Prefs.activityChangeProbability.value = it },
                    onValueChangeFinished = { Prefs.activityChangeProbability.save() },
                    valueRange = ActivityHost.PROBABILITY_RANGE,
                    steps = ((ActivityHost.PROBABILITY_RANGE.endInclusive - ActivityHost.PROBABILITY_RANGE.start) * 100.0 - 1).roundToInt(),
                    valueDisplay = { String.format(Locale.US, "%03.2f", it) }
                )
            }
        }

        // Second Surface: Activity Options
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                //horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title
                Text(
                    text = "${currentType.displayName} settings",
                    style = MaterialTheme.typography.titleLarge
                )

                // Random select availability slider
                val isExcluded = currentType in excludedActivities
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Available for random select")
                    Switch(
                        checked = !isExcluded,
                        onCheckedChange = { viewModel.toggleExclusion(currentType) }
                    )
                }
                currentInstance.permanentSettings?.let { it() }
            }
        }
        currentInstance.temporarySettings?.let { it() }
    }
}

@Preview
@Composable
fun ActivityHostPreview() {
    HowlTheme {
        val viewModel: ActivityHostViewModel = viewModel()
        ActivityHostPanel(
            viewModel = viewModel,
            modifier = Modifier.fillMaxHeight()
        )
    }
}