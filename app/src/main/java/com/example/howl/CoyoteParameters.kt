package com.example.howl

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.howl.ui.theme.HowlTheme
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Switch
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

fun IntRange.toClosedFloatingPointRange(): ClosedFloatingPointRange<Float> {
    return this.start.toFloat()..this.endInclusive.toFloat()
}

class CoyoteParametersViewModel() : ViewModel() {
    val coyoteParametersState: StateFlow<DGCoyote.Parameters> = DataRepository.coyoteParametersState
    val miscOptionsState: StateFlow<DataRepository.MiscOptionsState> = DataRepository.miscOptionsState
    fun setCoyoteParametersState(newCoyoteParametersState: DGCoyote.Parameters) {
        DataRepository.setCoyoteParametersState(newCoyoteParametersState)
    }
    fun setMiscOptionsState(newMiscOptionsState: DataRepository.MiscOptionsState) {
        DataRepository.setMiscOptionsState(newMiscOptionsState)
    }
    fun setRemoteAccess(enabled: Boolean) {
        setMiscOptionsState(miscOptionsState.value.copy(remoteAccess = enabled))
        saveSettings()
        if (enabled) {
            RemoteControlServer.start()
        }
        else {
            RemoteControlServer.stop()
        }
    }
    fun syncParameters() {
        viewModelScope.launch {
            DataRepository.saveSettings()
        }
        DGCoyote.sendParameters(DataRepository.coyoteParametersState.value)
    }
    fun saveSettings() {
        viewModelScope.launch {
            DataRepository.saveSettings()
        }
    }
}

@Composable
fun CoyoteParametersPanel(
    viewModel: CoyoteParametersViewModel,
    modifier: Modifier = Modifier
) {
    val parametersState by viewModel.coyoteParametersState.collectAsStateWithLifecycle()
    val miscOptionsState by viewModel.miscOptionsState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .padding(16.dp)
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = "Howl version $howlVersion", style = MaterialTheme.typography.labelLarge)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = "Coyote parameters", style = MaterialTheme.typography.headlineSmall)
        }

        SliderWithLabel(
            label = "Channel A Power Limit",
            value = parametersState.channelALimit.toFloat(),
            onValueChange = { viewModel.setCoyoteParametersState(parametersState.copy(channelALimit = it.roundToInt())) },
            onValueChangeFinished = { viewModel.syncParameters() },
            valueRange = DGCoyote.POWER_RANGE.toClosedFloatingPointRange(),
            steps = DGCoyote.POWER_RANGE.endInclusive - 1,
            valueDisplay = { it.roundToInt().toString() }
        )

        SliderWithLabel(
            label = "Channel B Power Limit",
            value = parametersState.channelBLimit.toFloat(),
            onValueChange = { viewModel.setCoyoteParametersState(parametersState.copy(channelBLimit = it.roundToInt())) },
            onValueChangeFinished = { viewModel.syncParameters() },
            valueRange = DGCoyote.POWER_RANGE.toClosedFloatingPointRange(),
            steps = DGCoyote.POWER_RANGE.endInclusive - 1,
            valueDisplay = { it.roundToInt().toString() }
        )

        SliderWithLabel(
            label = "Channel A Frequency Balance",
            value = parametersState.channelAFrequencyBalance.toFloat(),
            onValueChange = { viewModel.setCoyoteParametersState(parametersState.copy(channelAFrequencyBalance = it.roundToInt())) },
            onValueChangeFinished = { viewModel.syncParameters() },
            valueRange = DGCoyote.FREQUENCY_BALANCE_RANGE.toClosedFloatingPointRange(),
            steps = DGCoyote.FREQUENCY_BALANCE_RANGE.endInclusive - 1,
            valueDisplay = { it.roundToInt().toString() }
        )

        SliderWithLabel(
            label = "Channel B Frequency Balance",
            value = parametersState.channelBFrequencyBalance.toFloat(),
            onValueChange = { viewModel.setCoyoteParametersState(parametersState.copy(channelBFrequencyBalance = it.roundToInt())) },
            onValueChangeFinished = { viewModel.syncParameters() },
            valueRange = DGCoyote.FREQUENCY_BALANCE_RANGE.toClosedFloatingPointRange(),
            steps = DGCoyote.FREQUENCY_BALANCE_RANGE.endInclusive - 1,
            valueDisplay = { it.roundToInt().toString() }
        )

        SliderWithLabel(
            label = "Channel A Intensity Balance",
            value = parametersState.channelAIntensityBalance.toFloat(),
            onValueChange = { viewModel.setCoyoteParametersState(parametersState.copy(channelAIntensityBalance = it.roundToInt())) },
            onValueChangeFinished = { viewModel.syncParameters() },
            valueRange = DGCoyote.INTENSITY_BALANCE_RANGE.toClosedFloatingPointRange(),
            steps = DGCoyote.INTENSITY_BALANCE_RANGE.endInclusive - 1,
            valueDisplay = { it.roundToInt().toString() }
        )

        SliderWithLabel(
            label = "Channel B Intensity Balance",
            value = parametersState.channelBIntensityBalance.toFloat(),
            onValueChange = { viewModel.setCoyoteParametersState(parametersState.copy(channelBIntensityBalance = it.roundToInt())) },
            onValueChangeFinished = { viewModel.syncParameters() },
            valueRange = DGCoyote.INTENSITY_BALANCE_RANGE.toClosedFloatingPointRange(),
            steps = DGCoyote.INTENSITY_BALANCE_RANGE.endInclusive - 1,
            valueDisplay = { it.roundToInt().toString() }
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = "Power options", style = MaterialTheme.typography.headlineSmall)
        }
        val powerStepRange: IntRange = 1..10
        SliderWithLabel(
            label = "Power control step size A",
            value = miscOptionsState.powerStepSizeA.toFloat(),
            onValueChange = { viewModel.setMiscOptionsState(miscOptionsState.copy(powerStepSizeA = it.roundToInt())) },
            onValueChangeFinished = { viewModel.saveSettings() },
            valueRange = powerStepRange.toClosedFloatingPointRange(),
            steps = powerStepRange.endInclusive - 1,
            valueDisplay = { it.roundToInt().toString() }
        )
        SliderWithLabel(
            label = "Power control step size B",
            value = miscOptionsState.powerStepSizeB.toFloat(),
            onValueChange = { viewModel.setMiscOptionsState(miscOptionsState.copy(powerStepSizeB = it.roundToInt())) },
            onValueChangeFinished = { viewModel.saveSettings() },
            valueRange = powerStepRange.toClosedFloatingPointRange(),
            steps = powerStepRange.endInclusive - 1,
            valueDisplay = { it.roundToInt().toString() }
        )
        val autoIncrementRange: IntRange = 5..300
        SliderWithLabel(
            label = "Power auto increase delay A (seconds)",
            value = miscOptionsState.powerAutoIncrementDelayA.toFloat(),
            onValueChange = { viewModel.setMiscOptionsState(miscOptionsState.copy(powerAutoIncrementDelayA = it.roundToInt())) },
            onValueChangeFinished = { viewModel.saveSettings() },
            valueRange = autoIncrementRange.toClosedFloatingPointRange(),
            steps = ((autoIncrementRange.endInclusive - autoIncrementRange.start) * 0.2 - 1).roundToInt(),
            valueDisplay = { it.roundToInt().toString() }
        )
        SliderWithLabel(
            label = "Power auto increase delay B (seconds)",
            value = miscOptionsState.powerAutoIncrementDelayB.toFloat(),
            onValueChange = { viewModel.setMiscOptionsState(miscOptionsState.copy(powerAutoIncrementDelayB = it.roundToInt())) },
            onValueChangeFinished = { viewModel.saveSettings() },
            valueRange = autoIncrementRange.toClosedFloatingPointRange(),
            steps = ((autoIncrementRange.endInclusive - autoIncrementRange.start) * 0.2 - 1).roundToInt(),
            valueDisplay = { it.roundToInt().toString() }
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = "Misc options", style = MaterialTheme.typography.headlineSmall)
        }
        SwitchWithLabel(
            label = "Allow remote access (not secured)",
            checked = miscOptionsState.remoteAccess,
            onCheckedChange = {
                viewModel.setRemoteAccess(it)
            }
        )
        SwitchWithLabel(
            label = "Smoother charts & meters [*]",
            checked = miscOptionsState.smootherCharts,
            onCheckedChange = {
                viewModel.setMiscOptionsState(miscOptionsState.copy(smootherCharts = it))
                viewModel.saveSettings()
            }
        )
        SwitchWithLabel(
            label = "Show animated power meters [*]",
            checked = miscOptionsState.showPowerMeter,
            onCheckedChange = {
                viewModel.setMiscOptionsState(miscOptionsState.copy(showPowerMeter = it))
                viewModel.saveSettings()
            }
        )
        SwitchWithLabel(
            label = "Show debug log tab",
            checked = miscOptionsState.showDebugLog,
            onCheckedChange = {
                viewModel.setMiscOptionsState(miscOptionsState.copy(showDebugLog = it))
                viewModel.saveSettings()
            }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Options marked with [*] increase resource usage. Disable to improve performance on low-end devices and reduce energy use.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun SliderWithLabel(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueDisplay: (Float) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Column( modifier = modifier) {
        if (label != "") {
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),//.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = valueDisplay(value),
                modifier = Modifier.widthIn(45.dp)
            )
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = valueRange,
                steps = steps,
                enabled = enabled
            )
        }
    }
}

@Composable
fun SwitchWithLabel(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Preview
@Composable
fun CoyoteParametersPanelPreview() {
    HowlTheme {
        val viewModel: CoyoteParametersViewModel = viewModel()
        CoyoteParametersPanel(
            viewModel = viewModel,
            modifier = Modifier.fillMaxHeight()
        )
    }
}