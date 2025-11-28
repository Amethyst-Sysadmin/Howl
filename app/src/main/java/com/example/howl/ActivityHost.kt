package com.example.howl

import android.content.Context
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
import androidx.compose.runtime.LaunchedEffect
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

    fun getAvailableActivities(context: Context): List<ActivityInfo> {
        return listOf(
            ActivityInfo(context.getString(R.string.activity_infinite_licks), R.drawable.grin_tongue, true) { LickActivity() },
            ActivityInfo(context.getString(R.string.activity_penetration), R.drawable.rocket, true) { PenetrationActivity() },
            ActivityInfo(context.getString(R.string.activity_sliding_vibrator), R.drawable.vibration, true) { VibroActivity() },
            ActivityInfo(context.getString(R.string.activity_milkmaster), R.drawable.cow, true) { MilkerActivity() },
            ActivityInfo(context.getString(R.string.activity_chaos), R.drawable.chaos, true) { ChaosActivity() },
            ActivityInfo(context.getString(R.string.activity_luxury_hj), R.drawable.hand, true) { LuxuryHJActivity() },
            ActivityInfo(context.getString(R.string.activity_opposites), R.drawable.yin_yang, true) { OppositesActivity() },
            ActivityInfo(context.getString(R.string.activity_calibration_1), R.drawable.swapvert, false) { Calibration1Activity() },
            ActivityInfo(context.getString(R.string.activity_calibration_2), R.drawable.calibration, false) { Calibration2Activity() },
            ActivityInfo(context.getString(R.string.activity_bj_megamix), R.drawable.lips, true) { BJActivity() },
            ActivityInfo(context.getString(R.string.activity_fast_slow), R.drawable.speed, true) { FastSlowActivity() },
            ActivityInfo(context.getString(R.string.activity_additive), R.drawable.additive, true) { AdditiveActivity() },
            ActivityInfo(context.getString(R.string.activity_simplex), R.drawable.wave_triangle, true) { SimplexActivity() },
            ActivityInfo(context.getString(R.string.activity_simplex_pro), R.drawable.waveform, true) { SimplexProActivity() },
            ActivityInfo(context.getString(R.string.activity_simplex_turbo), R.drawable.waveform_path, true) { SimplexTurboActivity() },
            ActivityInfo(context.getString(R.string.activity_relentless), R.drawable.hammer, true) { RelentlessActivity() },
            ActivityInfo(context.getString(R.string.activity_random_shapes), R.drawable.shapes, true) { RandomShapesActivity() }
        )
    }
    
    private var currentActivityInfo: ActivityInfo? = null
    private var currentActivity: Activity? = null
    
    // 需要上下文参数的随机活动获取方法
    private fun getRandomActivities(context: Context): List<ActivityInfo> {
        return getAvailableActivities(context).filter { it.randomlySelect }
    }
    
    // 初始化时不再调用changeActivity，需要在有上下文后再初始化

    // 存储上下文的变量
    private var context: Context? = null
    
    // 设置上下文并初始化活动
    fun initializeWithContext(context: Context) {
        if (this.context == null) {
            this.context = context
            // 只有当没有当前活动时才初始化
            if (currentActivity == null) {
                changeActivity(context)
            }
        }
    }
    
    override fun updateState(currentTime: Double) {
        if (lastUpdateTime < 0 || lastUpdateTime > currentTime)
            lastUpdateTime = currentTime
        val state = DataRepository.activityState.value
        val timeDelta = currentTime - lastUpdateTime

        val probability = (state.activityChangeProbability * 3.0 * timeDelta) / 60.0
        if (Random.nextDouble() < probability && context != null) {
            changeActivity(context!!)
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

    fun changeActivity(context: Context) {
        val current = currentActivityInfo
        val randomActivities = getRandomActivities(context)
        
        val candidates = if (current != null) {
            randomActivities.filter { it != current }
        } else {
            randomActivities
        }

        val newInfo = candidates.randomOrNull() ?: randomActivities.randomOrNull()
        if (newInfo != null) {
            setCurrentActivity(newInfo)
        }
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
    val context = androidx.compose.ui.platform.LocalContext.current
    
    // 确保ActivityHost在组合时初始化上下文
    LaunchedEffect(Unit) {
        ActivityHost.initializeWithContext(context)
    }

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
                        contentDescription = context.getString(R.string.button_pause)
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.play),
                        contentDescription = context.getString(R.string.button_play)
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
            label = context.getString(R.string.activity_change_probability),
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
            items(ActivityHost.getAvailableActivities(context).sortedBy { it.displayName }) { info ->
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