package com.example.howl

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color
import com.example.howl.ui.theme.HowlTheme
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

object ActivityHost : PulseSource {
    val PROBABILITY_RANGE: ClosedFloatingPointRange<Float> = 0.0f..1.0f

    data class ActivityInfo(
        val displayName: String,
        val iconResId: Int,
        val randomlySelect: Boolean,
        val factory: () -> Activity
    )

    override var displayName: String = "Activity output"
    override var duration: Double? = null
    override val isFinite: Boolean = false
    override val shouldLoop: Boolean = false
    override var readyToPlay: Boolean = true
    override var isRemote: Boolean = false
    override var remoteLatency: Double = 0.0

    private val timerManager = TimerManager()

    private var lastUpdateTime = -1.0
    private var lastSimulationTime = -1.0

    val availableActivities: List<ActivityInfo> = listOf(
        ActivityInfo("Infinite licks", R.drawable.grin_tongue, true) { LickActivity() },
        ActivityInfo("Penetration", R.drawable.rocket, true) { PenetrationActivity() },
        ActivityInfo("Sliding vibrator", R.drawable.vibration, true) { VibroActivity() },
        ActivityInfo("Milkmaster 3000", R.drawable.cow, true) { MilkerActivity() },
        ActivityInfo("Chaos", R.drawable.chaos, true) { ChaosActivity() },
        ActivityInfo("Luxury HJ", R.drawable.hand, true) { LuxuryHJActivity() },
        ActivityInfo("Opposites", R.drawable.yin_yang, true) { OppositesActivity() },
        ActivityInfo("Calibration 1", R.drawable.swapvert, false) { Calibration1Activity() },
        ActivityInfo("Calibration 2", R.drawable.calibration, false) { Calibration2Activity() },
        ActivityInfo("BJ megamix", R.drawable.lips, true) { BJActivity() },
        ActivityInfo("Fast/slow", R.drawable.speed, true) { FastSlowActivity() },
        ActivityInfo("Additive", R.drawable.additive, true) { AdditiveActivity() },
        ActivityInfo("Simplex", R.drawable.wave_triangle, true) { SimplexActivity() },
        ActivityInfo("Simplex Pro", R.drawable.waveform, true) { SimplexProActivity() },
        ActivityInfo("Simplex Turbo", R.drawable.waveform_path, true) { SimplexTurboActivity() }
    )
    private val randomActivities = availableActivities.filter { it.randomlySelect }
    private var currentActivityInfo: ActivityInfo? = null
    private var currentActivity: Activity? = null

    init {
        changeActivity()
    }

    override fun updateState(currentTime: Double) {
        if (lastUpdateTime < 0 || lastUpdateTime > currentTime)
            lastUpdateTime = currentTime
        val state = DataRepository.activityState.value
        val timeDelta = currentTime - lastUpdateTime

        val probability = (state.activityChangeProbability * 3.0 * timeDelta) / 60.0
        if (Random.nextDouble() < probability) {
            changeActivity()
        }

        lastUpdateTime = currentTime
    }

    override fun getPulseAtTime(time: Double): Pulse {
        if (lastSimulationTime < 0 || lastSimulationTime > time) {
            lastSimulationTime = time
        }

        val simulationTimeDelta = time - lastSimulationTime
        lastSimulationTime = time
        timerManager.update(simulationTimeDelta)

        currentActivity?.runSimulation(simulationTimeDelta)
        return currentActivity?.getPulse() ?: Pulse()
    }

    fun setCurrentActivity(newActivityInfo: ActivityInfo) {
        currentActivityInfo = newActivityInfo
        currentActivity = newActivityInfo.factory().apply { initialise() }
        lastSimulationTime = -1.0
        lastUpdateTime = -1.0
        DataRepository.setActivityState(DataRepository.activityState.value.copy(currentActivityDisplayName = newActivityInfo.displayName))
    }

    fun changeActivity() {
        val current = currentActivityInfo
        val candidates = if (current != null) {
            randomActivities.filter { it != current }
        } else {
            randomActivities
        }

        val newInfo = candidates.randomOrNull() ?: randomActivities.random()
        setCurrentActivity(newInfo)
    }
}

class ActivityHostViewModel() : ViewModel() {
    fun updateActivityState(newActivityState: DataRepository.ActivityState) {
        DataRepository.setActivityState(newActivityState)
    }
    fun setActivityChangeProbability(probability: Float) {
        updateActivityState(DataRepository.activityState.value.copy(activityChangeProbability = probability))
    }
    fun setCurrentActivity(activityInfo: ActivityHost.ActivityInfo) {
        ActivityHost.setCurrentActivity(activityInfo)
    }
    fun stop() {
        Player.stopPlayer()
    }
    fun start() {
        Player.switchPulseSource(ActivityHost)
        Player.startPlayer()
    }
    fun saveSettings() {
        viewModelScope.launch {
            DataRepository.saveSettings()
        }
    }
}

@Composable
fun ActivityHostPanel(
    viewModel: ActivityHostViewModel,
    modifier: Modifier = Modifier
) {
    val activityState by DataRepository.activityState.collectAsStateWithLifecycle()
    val playerState by DataRepository.playerState.collectAsStateWithLifecycle()
    val isPlaying = playerState.isPlaying && playerState.activePulseSource == ActivityHost

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Play/Pause Button
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
        }
        /*Text(
            text = "Current Action: ${activityState.currentActivityDisplayName}",
            modifier = Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodyLarge
        )*/
        SliderWithLabel(
            label = "Random activity change probability",
            value = activityState.activityChangeProbability.toFloat(),
            onValueChange = { viewModel.setActivityChangeProbability(it) },
            onValueChangeFinished = { viewModel.saveSettings() },
            valueRange = ActivityHost.PROBABILITY_RANGE,
            steps = ((ActivityHost.PROBABILITY_RANGE.endInclusive - ActivityHost.PROBABILITY_RANGE.start) * 100.0 - 1).roundToInt(),
            valueDisplay = { String.format(Locale.US, "%03.2f", it) }
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth().height(440.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(ActivityHost.availableActivities.sortedBy { it.displayName }) { info ->
                val isCurrent = info.displayName == activityState.currentActivityDisplayName
                Button(
                    onClick = {
                        viewModel.setCurrentActivity(info)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isCurrent) Color.Red else ButtonDefaults.buttonColors().containerColor
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        painter = painterResource(info.iconResId),
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = info.displayName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis

                    )
                }
            }
        }
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