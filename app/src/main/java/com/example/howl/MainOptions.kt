package com.example.howl
import android.util.Log
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.flow.update

data class MainOptionsState (
    val channelAPower: Int = 0,
    val channelBPower: Int = 0,
    val globalMute: Boolean = false,
    val autoIncreasePower: Boolean = false,
    val swapChannels: Boolean = false,
    val frequencyRange: IntRange = 100..1000,
    val frequencyRangeSelectedSubset: ClosedFloatingPointRange<Float> = 0.0f..1.0f,
) {
    val minFrequency: Int
        get() {
            val rangeSpan = frequencyRange.last - frequencyRange.first
            return frequencyRange.first + (rangeSpan * frequencyRangeSelectedSubset.start).toInt()
        }

    val maxFrequency: Int
        get() {
            val rangeSpan = frequencyRange.last - frequencyRange.first
            return frequencyRange.first + (rangeSpan * frequencyRangeSelectedSubset.endInclusive).toInt()
        }
}

object MainOptions {
    private val _state = MutableStateFlow(MainOptionsState())
    val state: StateFlow<MainOptionsState> = _state.asStateFlow()
    private var autoIncrementPowerCounterA: Long = 0L
    private var autoIncrementPowerCounterB: Long = 0L

    fun setChannelPower(channel: Int, power: Int) {
        when (channel) {
            0 -> {
                val limit = Prefs.powerLimitA.value
                val newPower = power.coerceIn(0..limit)
                _state.update { it.copy(channelAPower = newPower) }
            }
            1 -> {
                val limit = Prefs.powerLimitB.value
                val newPower = power.coerceIn(0..limit)
                _state.update { it.copy(channelBPower = newPower) }
            }
            else -> {}
        }
    }

    fun incrementChannelPower(channel: Int, step: Int = 0) {
        val current = getChannelPower(channel)
        val stepSize = if (step == 0) getChannelPowerStep(channel) else step
        setChannelPower(channel, current + stepSize)
    }

    fun decrementChannelPower(channel: Int, step: Int = 0) {
        val current = getChannelPower(channel)
        val stepSize = if (step == 0) getChannelPowerStep(channel) else step
        setChannelPower(channel, current - stepSize)
    }

    fun getChannelPower(channel: Int): Int {
        return when (channel) {
            0 -> state.value.channelAPower
            1 -> state.value.channelBPower
            else -> 0
        }
    }

    fun getPowerLevels(): Pair<Int, Int> {
        return Pair(state.value.channelAPower, state.value.channelBPower)
    }

    fun getChannelPowerStep(channel: Int): Int {
        return when (channel) {
            0 -> Prefs.powerStepA.value
            1 -> Prefs.powerStepB.value
            else -> 1
        }
    }

    fun autoIncreasePower(elapsed: Double) {
        val options = state.value

        if (options.autoIncreasePower && !options.globalMute) {
            // Using milliseconds internally avoids an annoying issue where the channel updates
            // can desynchronise from each other over time due to floating point errors
            val elapsedMs = (elapsed * 1000).toLong()
            if (options.channelAPower > 0)
                autoIncrementPowerCounterA += elapsedMs
            if (options.channelBPower > 0)
                autoIncrementPowerCounterB += elapsedMs

            val autoIncrementDelayA = (Prefs.powerAutoIncrementDelayA.value * 1000).toLong()
            val autoIncrementDelayB = (Prefs.powerAutoIncrementDelayB.value * 1000).toLong()
            //Log.d("MainControls", "Auto increment calculation $autoIncrementPowerCounterA / $autoIncrementDelayA      $autoIncrementPowerCounterB / $autoIncrementDelayB")
            if (autoIncrementPowerCounterA >= autoIncrementDelayA) {
                autoIncrementPowerCounterA = 0L
                incrementChannelPower(0, 1)
            }
            if (autoIncrementPowerCounterB >= autoIncrementDelayB) {
                autoIncrementPowerCounterB = 0L
                incrementChannelPower(1, 1)
            }
        }
    }

    fun singleChannelMode(): Boolean {
        val aActive = state.value.channelAPower > 0
        val bActive = state.value.channelBPower > 0
        return aActive != bActive
    }

    fun setGlobalMute(muted: Boolean) {
        _state.update { it.copy(globalMute = muted)}
    }

    fun setAutoIncreasePower(autoIncrease: Boolean) {
        autoIncrementPowerCounterA = 0L
        autoIncrementPowerCounterB = 0L
        _state.update { it.copy(autoIncreasePower = autoIncrease)}
    }

    fun setSwapChannels(swap: Boolean) {
        _state.update { it.copy(swapChannels = swap)}
    }

    fun setFrequencyRange(range: IntRange, overrideSelectedSubset: Boolean = false) {
        _state.update { currentState ->
            if (overrideSelectedSubset) {
                currentState.copy(
                    frequencyRange = range,
                    frequencyRangeSelectedSubset = 0.0f..1.0f
                )
            } else {
                currentState.copy(
                    frequencyRange = range,
                )
            }
        }
    }

    fun setFrequencyRangeSelectedSubset(range: ClosedFloatingPointRange<Float>) {
        _state.update { it.copy(frequencyRangeSelectedSubset = range) }
    }

    fun setFrequenciesToOutputDefaults(output: Output) {
        val frequencyRangeSubset = output.defaultFrequencyRange.toProportionOf(output.allowedFrequencyRange)
        _state.update { it.copy(
            frequencyRange = output.allowedFrequencyRange,
            frequencyRangeSelectedSubset = frequencyRangeSubset,
        ) }
    }
}

class MainOptionsViewModel() : ViewModel() {
    private val _pulseChartMode = MutableStateFlow(PulseChartMode.Off)
    val pulseChartMode: StateFlow<PulseChartMode> = _pulseChartMode.asStateFlow()

    fun setChannelPower(channel: Int, power: Int) {
        MainOptions.setChannelPower(channel, power)
    }

    fun incrementChannelPower(channel: Int) {
        MainOptions.incrementChannelPower(channel)
    }

    fun decrementChannelPower(channel: Int) {
        MainOptions.decrementChannelPower(channel)
    }

    fun setGlobalMute(muted: Boolean) {
        MainOptions.setGlobalMute(muted)
    }

    fun setAutoIncreasePower(autoIncrease: Boolean) {
        MainOptions.setAutoIncreasePower(autoIncrease)
    }

    fun setSwapChannels(swap: Boolean) {
        MainOptions.setSwapChannels(swap)
    }

    fun setFrequencyRangeSelectedSubset(range: ClosedFloatingPointRange<Float>) {
        MainOptions.setFrequencyRangeSelectedSubset(range)
    }

    fun cyclePulseChart() {
        val newMode = _pulseChartMode.value.next()
        _pulseChartMode.update { newMode }
    }
}

@Composable
fun MainOptionsPanel(
    viewModel: MainOptionsViewModel,
    modifier: Modifier = Modifier
) {
    val mainOptionsState by MainOptions.state.collectAsStateWithLifecycle()
    val showPowerMeter by Prefs.miscShowPowerMeter.collectAsStateWithLifecycle()
    val pulseChartMode by viewModel.pulseChartMode.collectAsStateWithLifecycle()

    val minSeparation = 0.05
    val muted = mainOptionsState.globalMute
    val autoIncreasePower = mainOptionsState.autoIncreasePower
    val swapChannels = mainOptionsState.swapChannels
    val toolbarButtonHeight = 50.dp
    val activeButtonColour = MaterialTheme.colorScheme.tertiary

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left side: Channel A controls
            PowerLevelPanel(
                channelIndex = 0,
                channelLabel = "A",
                power = mainOptionsState.channelAPower,
                viewModel = viewModel
            )

            // Center: Power meters (grouped together)
            if (showPowerMeter) {
                Row(
                    horizontalArrangement = Arrangement.Center
                ) {
                    PowerLevelMeters()
                }
            } else {
                // Empty spacer when power meters are hidden to maintain layout
                Spacer(modifier = Modifier.width(12.dp + 8.dp + 12.dp)) // width of two meters + spacer
            }

            // Right side: Channel B controls
            PowerLevelPanel(
                channelIndex = 1,
                channelLabel = "B",
                power = mainOptionsState.channelBPower,
                viewModel = viewModel
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                modifier = Modifier.weight(1.0f)
                    .height(toolbarButtonHeight),
                contentPadding = PaddingValues(2.dp),
                onClick = {
                    viewModel.setGlobalMute(!muted)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (muted) activeButtonColour else ButtonDefaults.buttonColors().containerColor
                )
            ) {
                Icon(painter = painterResource(R.drawable.mute), contentDescription = "Mute output")
            //Text(if (muted) "Pulse output muted" else "Mute pulse output")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                modifier = Modifier.height(toolbarButtonHeight),
                contentPadding = PaddingValues(2.dp),
                //shape = RoundedCornerShape(8.dp),
                onClick = {
                    viewModel.setAutoIncreasePower(!autoIncreasePower)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (autoIncreasePower) activeButtonColour else ButtonDefaults.buttonColors().containerColor
                )
            ) {
                Icon(painter = painterResource(R.drawable.auto_increase), contentDescription = "Auto increase power")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                modifier = Modifier.height(toolbarButtonHeight),
                contentPadding = PaddingValues(2.dp),
                //shape = RoundedCornerShape(8.dp),
                onClick = {
                    viewModel.cyclePulseChart()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (pulseChartMode != PulseChartMode.Off) activeButtonColour else ButtonDefaults.buttonColors().containerColor
                )
            ) {
                Icon(painter = painterResource(R.drawable.chart), contentDescription = "Pulse chart")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                modifier = Modifier.height(toolbarButtonHeight),
                contentPadding = PaddingValues(2.dp),
                //shape = RoundedCornerShape(8.dp),
                onClick = {
                    viewModel.setSwapChannels(!swapChannels)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (swapChannels) activeButtonColour else ButtonDefaults.buttonColors().containerColor
                )
            ) {
                Icon(painter = painterResource(R.drawable.swap), contentDescription = "Swap channels")
            }

        }

        //Spacer(modifier = Modifier.height(4.dp))

        //Text(text = "Frequency range (Hz)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.CenterHorizontally))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "${mainOptionsState.minFrequency}Hz", modifier = modifier.widthIn(40.dp), style = MaterialTheme.typography.labelMedium)
            RangeSlider(
                modifier = Modifier.weight(1f),
                value = mainOptionsState.frequencyRangeSelectedSubset,
                steps = 0, // there's a crash due to an Android bug if we set the steps we actually want
                // https://issuetracker.google.com/issues/324934900
                // make it continuous and round in onValueChange instead as a workaround
                //onValueChange = { viewModel.setFrequencyRangeSelectedSubset(it) },
                onValueChange = { newRange ->
                    if (newRange.endInclusive - newRange.start >= minSeparation) {
                        viewModel.setFrequencyRangeSelectedSubset(newRange)
                    }
                },
                valueRange = 0.0f..1.0f,
                onValueChangeFinished = { },
            )
            Text(text = "${mainOptionsState.maxFrequency}Hz", modifier = modifier.widthIn(40.dp), style = MaterialTheme.typography.labelMedium)
        }
        when (pulseChartMode) {
            PulseChartMode.Combined -> {
                PulsePlotter(
                    modifier = Modifier
                        .height(200.dp)
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(3.dp),
                    mode = PulsePlotMode.Combined
                )
            }
            PulseChartMode.Separate -> {
                Row(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    PulsePlotter(
                        modifier = Modifier
                            .height(200.dp)
                            .weight(1.0f)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = MaterialTheme.shapes.small
                            )
                            .padding(3.dp),
                        mode = PulsePlotMode.AmplitudeOnly
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    PulsePlotter(
                        modifier = Modifier
                            .height(200.dp)
                            .weight(1.0f)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = MaterialTheme.shapes.small
                            )
                            .padding(3.dp),
                        mode = PulsePlotMode.FrequencyOnly
                    )
                }
            }
            PulseChartMode.Off -> {}
        }
    }
}

@Composable
fun PowerLevelPanel(
    channelIndex: Int,
    channelLabel: String,
    power: Int,
    viewModel: MainOptionsViewModel
) {
    Column {
        Text(
            text = "$power",
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Row {
            LongPressButton(
                onClick = { viewModel.decrementChannelPower(channelIndex) },
                onLongClick = { viewModel.setChannelPower(channelIndex, 0) },
                modifier = Modifier.size(68.dp)
            ) {
                Column {
                    Icon(
                        painter = painterResource(R.drawable.minus),
                        contentDescription = "Lower power",
                    )
                    Text(text = channelLabel, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            LongPressButton(
                onClick = { viewModel.incrementChannelPower(channelIndex) },
                onLongClick = {},
                modifier = Modifier.size(68.dp)
            ) {
                Column {
                    Icon(
                        painter = painterResource(R.drawable.plus),
                        contentDescription = "Increase power",
                    )
                    Text(text = channelLabel, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            }
        }
    }
}

@Preview
@Composable
fun MainOptionsPanelPreview() {
    HowlTheme {
        val viewModel: MainOptionsViewModel = viewModel()
        MainOptionsPanel(
            viewModel = viewModel,
            modifier = Modifier.fillMaxHeight()
        )
    }
}