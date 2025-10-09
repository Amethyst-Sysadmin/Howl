package com.example.howl
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.howl.ui.theme.HowlTheme
import kotlinx.coroutines.flow.StateFlow

class MainOptionsViewModel() : ViewModel() {
    val mainOptionsState: StateFlow<DataRepository.MainOptionsState> = DataRepository.mainOptionsState
    val miscOptionsState: StateFlow<DataRepository.MiscOptionsState> = DataRepository.miscOptionsState

    fun updateMainOptionsState(newMainOptionsState: DataRepository.MainOptionsState) {
        DataRepository.setMainOptionsState(newMainOptionsState)
    }

    fun setChannelAPower(power: Int) {
        DataRepository.setChannelAPower(power)
    }

    fun setChannelBPower(power: Int) {
        DataRepository.setChannelBPower(power)
    }

    fun setGlobalMute(muted: Boolean) {
        DataRepository.setGlobalMute(muted)
    }

    fun setAutoIncreasePower(autoIncrease: Boolean) {
        DataRepository.setAutoIncreasePower(autoIncrease)
    }

    fun setSwapChannels(swap: Boolean) {
        DataRepository.setSwapChannels(swap)
    }

    fun setFrequencyRangeSelectedSubset(range: ClosedFloatingPointRange<Float>) {
        DataRepository.setFrequencyRangeSelectedSubset(range)
    }

    fun cyclePulseChart() {
        val newMode = mainOptionsState.value.pulseChartMode.next()
        DataRepository.setPulseChartMode(newMode)
        //if (newMode == PulseChartMode.Off)
        //    DataRepository.clearPulseHistory()
    }
}

@Composable
fun MainOptionsPanel(
    viewModel: MainOptionsViewModel,
    modifier: Modifier = Modifier
) {
    val mainOptionsState by viewModel.mainOptionsState.collectAsStateWithLifecycle()
    val miscOptionsState by viewModel.miscOptionsState.collectAsStateWithLifecycle()
    val minSeparation = 5f
    val muted = mainOptionsState.globalMute
    val autoIncreasePower = mainOptionsState.autoIncreasePower
    val swapChannels = mainOptionsState.swapChannels
    val toolbarButtonHeight = 50.dp
    val activeButtonColour = Color.Red

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
                channel = "A",
                power = mainOptionsState.channelAPower,
                onPowerChange = viewModel::setChannelAPower,
                stepSize = miscOptionsState.powerStepSizeA
            )

            // Center: Power meters (grouped together)
            if (miscOptionsState.showPowerMeter) {
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
                channel = "B",
                power = mainOptionsState.channelBPower,
                onPowerChange = viewModel::setChannelBPower,
                stepSize = miscOptionsState.powerStepSizeB
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
                    containerColor = if (mainOptionsState.pulseChartMode != PulseChartMode.Off) activeButtonColour else ButtonDefaults.buttonColors().containerColor
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
                    if (newRange.endInclusive - newRange.start >= 0.05) {
                        viewModel.setFrequencyRangeSelectedSubset(newRange)
                    }
                },
                valueRange = 0.0f..1.0f,
                onValueChangeFinished = { },
            )
            Text(text = "${mainOptionsState.maxFrequency}Hz", modifier = modifier.widthIn(40.dp), style = MaterialTheme.typography.labelMedium)
        }
        when (mainOptionsState.pulseChartMode) {
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
    channel: String,
    power: Int,
    onPowerChange: (Int) -> Unit,
    stepSize: Int
) {
    Column {
        Text(
            text = "$power",
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Row {
            LongPressButton(
                onClick = { onPowerChange(maxOf(0, power - stepSize)) },
                onLongClick = { onPowerChange(0) },
                modifier = Modifier.size(68.dp)
            ) {
                Column {
                    Icon(
                        painter = painterResource(R.drawable.minus),
                        contentDescription = "Lower power",
                    )
                    Text(text = channel, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            LongPressButton(
                onClick = { onPowerChange(power + stepSize) },
                onLongClick = {},
                modifier = Modifier.size(68.dp)
            ) {
                Column {
                    Icon(
                        painter = painterResource(R.drawable.plus),
                        contentDescription = "Increase power",
                    )
                    Text(text = channel, modifier = Modifier.align(Alignment.CenterHorizontally))
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