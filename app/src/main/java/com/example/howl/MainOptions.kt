package com.example.howl
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.howl.ui.theme.HowlTheme
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.lerp

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

    fun setFrequencyRange(range: ClosedFloatingPointRange<Float>) {
        DataRepository.setFrequencyRange(range)
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
    val playerState by DataRepository.playerState.collectAsStateWithLifecycle()
    val lastPulse by DataRepository.lastPulse.collectAsStateWithLifecycle()
    val powerBarStartColor = Color(0xFFFF0000)
    val powerBarEndColor = Color(0xFFFFFF00)
    val minSeparation = 5f
    val muted = mainOptionsState.globalMute
    val autoIncreasePower = mainOptionsState.autoIncreasePower
    val swapChannels = mainOptionsState.swapChannels
    val playing = playerState.isPlaying
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
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row() {
                PowerLevelPanel(
                    channel = "A",
                    power = mainOptionsState.channelAPower,
                    onPowerChange = viewModel::setChannelAPower,
                    stepSize = miscOptionsState.powerStepSizeA
                )
                if(miscOptionsState.showPowerMeter) {
                    Spacer(modifier = Modifier.width(8.dp))
                    PowerLevelMeter(
                        barColor = lerp(
                            powerBarStartColor,
                            powerBarEndColor,
                            lastPulse.freqA
                        ), powerLevel = if (playing) lastPulse.ampA else 0.0f
                    )
                }
            }
            Row() {
                if(miscOptionsState.showPowerMeter) {
                    PowerLevelMeter(
                        barColor = lerp(
                            powerBarStartColor,
                            powerBarEndColor,
                            lastPulse.freqB
                        ), powerLevel = if (playing) lastPulse.ampB else 0.0f
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                PowerLevelPanel(
                    channel = "B",
                    power = mainOptionsState.channelBPower,
                    onPowerChange = viewModel::setChannelBPower,
                    stepSize = miscOptionsState.powerStepSizeB
                )
            }
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
            Text(text = "${mainOptionsState.frequencyRange.start.roundToInt()}Hz", modifier = modifier.widthIn(40.dp), style = MaterialTheme.typography.labelMedium)
            RangeSlider(
                modifier = Modifier.weight(1f),
                value = mainOptionsState.frequencyRange,
                steps = 0, //there's a crash due to an Android bug if we set the steps we actually want
                //steps = (DGCoyote.FREQUENCY_RANGE.endInclusive - DGCoyote.FREQUENCY_RANGE.start) - 1,
                //make it continuous and round to integer values in onValueChange instead as a workaround
                onValueChange = { newRange ->
                    val newStart = newRange.start
                    val newEnd = newRange.endInclusive
                    if (newEnd - newStart >= minSeparation) {
                        viewModel.setFrequencyRange(
                            newRange.start.roundToInt()
                                .toFloat()..newRange.endInclusive.roundToInt().toFloat()
                        )
                    }
                },
                valueRange = DGCoyote.FREQUENCY_RANGE.toClosedFloatingPointRange(),
                onValueChangeFinished = { },
            )
            Text(text = "${mainOptionsState.frequencyRange.endInclusive.roundToInt()}Hz", modifier = modifier.widthIn(40.dp), style = MaterialTheme.typography.labelMedium)
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

/*@Composable
fun PowerLevelMeter(
    powerLevel: Float,
    barColor: Color,
) {
    val clampedLevel = powerLevel.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .width(5.dp)
            .fillMaxHeight(),
        contentAlignment = Alignment.BottomCenter
    ) {
        if (clampedLevel > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(clampedLevel)
                    .background(barColor)
            )
        }
    }
}*/

@Composable
fun PowerLevelMeter(
    powerLevel: Float,
    barColor: Color,
) {
    val clampedLevel = powerLevel.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .width(12.dp)
            .fillMaxHeight()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = MaterialTheme.shapes.extraSmall
            )
            .padding(1.dp)
            .drawBehind {
                if (clampedLevel > 0f) {
                    val barHeight = size.height * clampedLevel
                    drawRect(
                        color = barColor,
                        topLeft = Offset(0f, size.height - barHeight),
                        size = Size(size.width, barHeight)
                    )
                }
            }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LongPressButton(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    content: @Composable RowScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                indication = ripple(),
                interactionSource = remember { MutableInteractionSource() }
            ),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        /*color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,*/
        shape = shape,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
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