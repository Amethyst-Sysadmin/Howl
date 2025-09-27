package com.example.howl

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.util.Locale

fun IntRange.toClosedFloatingPointRange(): ClosedFloatingPointRange<Float> {
    return this.start.toFloat()..this.endInclusive.toFloat()
}

class SettingsViewModel() : ViewModel() {
    val coyoteParametersState: StateFlow<Coyote3Parameters> = DataRepository.coyoteParametersState
    val miscOptionsState: StateFlow<DataRepository.MiscOptionsState> = DataRepository.miscOptionsState
    val outputState: StateFlow<DataRepository.OutputState> = DataRepository.outputState

    fun setCoyoteParametersState(newCoyoteParametersState: Coyote3Parameters) {
        DataRepository.setCoyoteParametersState(newCoyoteParametersState)
    }
    fun setMiscOptionsState(newMiscOptionsState: DataRepository.MiscOptionsState) {
        DataRepository.setMiscOptionsState(newMiscOptionsState)
    }
    fun setOutputState(newOutputState: DataRepository.OutputState) {
        DataRepository.setOutputState(newOutputState)
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
    fun setOutputType(outputType: OutputType) {
        setOutputState(outputState.value.copy(outputType = outputType))
        saveSettings()
        BluetoothHandler.disconnect()
        Player.switchOutput(outputType)
    }
    fun setAudioCarrierType(type: CarrierWaveType) {
        setOutputState(outputState.value.copy(audioCarrierType = type))
        saveSettings()
    }
    fun setAudioCarrierPhaseType(type: CarrierPhaseType) {
        setOutputState(outputState.value.copy(audioCarrierPhaseType = type))
        saveSettings()
    }
    /*fun setAudioOutputMinFrequency(newFrequency: Int) {
        val currentMax = outputState.value.audioOutputMaxFrequency
        val clampedMin = newFrequency.coerceAtMost(currentMax - 50).coerceAtLeast(10)
        setOutputState(outputState.value.copy(audioOutputMinFrequency = clampedMin))
        audioOutputFrequencyRangeUpdated()
    }
    fun setAudioOutputMaxFrequency(newFrequency: Int) {
        val currentMin = outputState.value.audioOutputMinFrequency
        val clampedMax = newFrequency.coerceAtLeast(currentMin + 50).coerceAtLeast(10)
        setOutputState(outputState.value.copy(audioOutputMaxFrequency = clampedMax))
        audioOutputFrequencyRangeUpdated()
    }
    fun audioOutputFrequencyRangeUpdated() {
        val newRange = outputState.value.audioOutputMinFrequency .. outputState.value.audioOutputMaxFrequency
        Player.output.allowedFrequencyRange = newRange
        DataRepository.setFrequencyRange(newRange)
    }*/
    fun syncParameters() {
        viewModelScope.launch {
            DataRepository.saveSettings()
        }
        if (Player.output is Coyote3Output) {
            val output = Player.output as Coyote3Output
            if (output.ready)
                output.sendParameters(DataRepository.coyoteParametersState.value)
        }
    }
    fun saveSettings() {
        viewModelScope.launch {
            DataRepository.saveSettings()
        }
    }
}

@Composable
fun SettingsPanel(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val parametersState by viewModel.coyoteParametersState.collectAsStateWithLifecycle()
    val miscOptionsState by viewModel.miscOptionsState.collectAsStateWithLifecycle()
    val outputState by viewModel.outputState.collectAsStateWithLifecycle()

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
            Text(text = "Output options", style = MaterialTheme.typography.headlineSmall)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Output type", style = MaterialTheme.typography.labelLarge)
            OptionPicker(
                currentValue = outputState.outputType,
                onValueChange = {
                    viewModel.setOutputType(it)
                },
                options = OutputType.entries,
                getText = { it.displayName }
            )
        }

        if(outputState.outputType == OutputType.AUDIO) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(text = "Audio output", style = MaterialTheme.typography.headlineSmall)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Carrier wave shape", style = MaterialTheme.typography.labelLarge)
                OptionPicker(
                    currentValue = outputState.audioCarrierType,
                    onValueChange = {
                        viewModel.setAudioCarrierType(it)
                    },
                    options = CarrierWaveType.entries,
                    getText = { it.displayName }
                )
            }
            SliderWithLabel(
                label = "Carrier wave frequency (Hz)",
                value = outputState.audioCarrierFrequency.toFloat(),
                onValueChange = { viewModel.setOutputState(outputState.copy(audioCarrierFrequency = it.roundToInt())) },
                onValueChangeFinished = { viewModel.saveSettings() },
                valueRange = 600.0f..2000.0f,
                steps = 139,
                valueDisplay = { it.roundToInt().toString() }
            )
            /*SwitchWithLabel(
                label = "Allow higher carrier frequencies",
                checked = outputState.audioAllowHighFrequencyCarrier,
                onCheckedChange = {
                    viewModel.setOutputState(outputState.copy(audioAllowHighFrequencyCarrier = it))
                    viewModel.saveSettings()
                }
            )*/
            /*Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Envelope shape", style = MaterialTheme.typography.labelLarge)
                OptionPicker(
                    currentValue = outputState.audioEnvelopeType,
                    onValueChange = {
                        viewModel.setAudioEnvelopeType(it)
                    },
                    options = EnvelopeType.entries,
                    getText = { it.displayName }
                )
            }*/
            val waveletWidthRange = 3..10
            SliderWithLabel(
                label = "Wavelet width (in carrier wave cycles)",
                value = outputState.audioWaveletWidth.toFloat(),
                onValueChange = { viewModel.setOutputState(outputState.copy(audioWaveletWidth = it.roundToInt())) },
                onValueChangeFinished = { viewModel.saveSettings() },
                valueRange = waveletWidthRange.start.toFloat()..waveletWidthRange.endInclusive.toFloat(),
                steps = (waveletWidthRange.endInclusive - waveletWidthRange.start) - 1,
                valueDisplay = { it.roundToInt().toString() }
            )
            SliderWithLabel(
                label = "Wavelet fade in/out proportion",
                value = outputState.audioWaveletFade,
                onValueChange = { viewModel.setOutputState(outputState.copy(audioWaveletFade = it)) },
                onValueChangeFinished = { viewModel.saveSettings() },
                valueRange = 0.0f..1.0f,
                steps = 99,
                valueDisplay = { String.format(Locale.US, "%03.2f", it) }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Carrier phase on each channel", style = MaterialTheme.typography.labelLarge)
                OptionPicker(
                    currentValue = outputState.audioCarrierPhaseType,
                    onValueChange = {
                        viewModel.setAudioCarrierPhaseType(it)
                    },
                    options = CarrierPhaseType.entries,
                    getText = { it.displayName }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val dutyCycle = ((1.0 / outputState.audioCarrierFrequency) * outputState.audioWaveletWidth) / 0.01
                val displayDutyCycle = (dutyCycle * 100.0).coerceIn(0.0..100.0).roundToInt()
                Text(text = "Estimated duty cycle at 100Hz: $displayDutyCycle%", style = MaterialTheme.typography.labelLarge)
            }
            /*val FREQUENCY_SLIDER_RANGE = 50..4000
            SliderWithLabel(
                label = "Minimum frequency (Hz)",
                value = outputState.audioOutputMinFrequency.toFloat(),
                onValueChange = { viewModel.setAudioOutputMinFrequency(it.roundToInt()) },
                onValueChangeFinished = { viewModel.saveSettings() },
                valueRange = FREQUENCY_SLIDER_RANGE.toClosedFloatingPointRange(),
                steps = ((FREQUENCY_SLIDER_RANGE.endInclusive - FREQUENCY_SLIDER_RANGE.start) * 0.02).roundToInt() - 1,
                valueDisplay = { it.roundToInt().toString() }
            )
            SliderWithLabel(
                label = "Maximum frequency (Hz)",
                value = outputState.audioOutputMaxFrequency.toFloat(),
                onValueChange = { viewModel.setAudioOutputMaxFrequency(it.roundToInt()) },
                onValueChangeFinished = { viewModel.saveSettings() },
                valueRange = FREQUENCY_SLIDER_RANGE.toClosedFloatingPointRange(),
                steps = ((FREQUENCY_SLIDER_RANGE.endInclusive - FREQUENCY_SLIDER_RANGE.start) * 0.02).roundToInt() - 1,
                valueDisplay = { it.roundToInt().toString() }
            )*/
        }

        if(outputState.outputType == OutputType.COYOTE3) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(text = "Coyote parameters", style = MaterialTheme.typography.headlineSmall)
            }
            SliderWithLabel(
                label = "Channel A Frequency Balance",
                value = parametersState.channelAFrequencyBalance.toFloat(),
                onValueChange = {
                    viewModel.setCoyoteParametersState(
                        parametersState.copy(
                            channelAFrequencyBalance = it.roundToInt()
                        )
                    )
                },
                onValueChangeFinished = { viewModel.syncParameters() },
                valueRange = Coyote3Output.FREQUENCY_BALANCE_RANGE.toClosedFloatingPointRange(),
                steps = Coyote3Output.FREQUENCY_BALANCE_RANGE.endInclusive - 1,
                valueDisplay = { it.roundToInt().toString() }
            )
            SliderWithLabel(
                label = "Channel B Frequency Balance",
                value = parametersState.channelBFrequencyBalance.toFloat(),
                onValueChange = {
                    viewModel.setCoyoteParametersState(
                        parametersState.copy(
                            channelBFrequencyBalance = it.roundToInt()
                        )
                    )
                },
                onValueChangeFinished = { viewModel.syncParameters() },
                valueRange = Coyote3Output.FREQUENCY_BALANCE_RANGE.toClosedFloatingPointRange(),
                steps = Coyote3Output.FREQUENCY_BALANCE_RANGE.endInclusive - 1,
                valueDisplay = { it.roundToInt().toString() }
            )
            SliderWithLabel(
                label = "Channel A Intensity Balance",
                value = parametersState.channelAIntensityBalance.toFloat(),
                onValueChange = {
                    viewModel.setCoyoteParametersState(
                        parametersState.copy(
                            channelAIntensityBalance = it.roundToInt()
                        )
                    )
                },
                onValueChangeFinished = { viewModel.syncParameters() },
                valueRange = Coyote3Output.INTENSITY_BALANCE_RANGE.toClosedFloatingPointRange(),
                steps = Coyote3Output.INTENSITY_BALANCE_RANGE.endInclusive - 1,
                valueDisplay = { it.roundToInt().toString() }
            )
            SliderWithLabel(
                label = "Channel B Intensity Balance",
                value = parametersState.channelBIntensityBalance.toFloat(),
                onValueChange = {
                    viewModel.setCoyoteParametersState(
                        parametersState.copy(
                            channelBIntensityBalance = it.roundToInt()
                        )
                    )
                },
                onValueChangeFinished = { viewModel.syncParameters() },
                valueRange = Coyote3Output.INTENSITY_BALANCE_RANGE.toClosedFloatingPointRange(),
                steps = Coyote3Output.INTENSITY_BALANCE_RANGE.endInclusive - 1,
                valueDisplay = { it.roundToInt().toString() }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = "Power options", style = MaterialTheme.typography.headlineSmall)
        }
        SliderWithLabel(
            label = "Channel A Power Limit",
            value = parametersState.channelALimit.toFloat(),
            onValueChange = {
                viewModel.setCoyoteParametersState(
                    parametersState.copy(
                        channelALimit = it.roundToInt()
                    )
                )
            },
            onValueChangeFinished = { viewModel.syncParameters() },
            valueRange = Coyote3Output.POWER_RANGE.toClosedFloatingPointRange(),
            steps = Coyote3Output.POWER_RANGE.endInclusive - 1,
            valueDisplay = { it.roundToInt().toString() }
        )
        SliderWithLabel(
            label = "Channel B Power Limit",
            value = parametersState.channelBLimit.toFloat(),
            onValueChange = {
                viewModel.setCoyoteParametersState(
                    parametersState.copy(
                        channelBLimit = it.roundToInt()
                    )
                )
            },
            onValueChangeFinished = { viewModel.syncParameters() },
            valueRange = Coyote3Output.POWER_RANGE.toClosedFloatingPointRange(),
            steps = Coyote3Output.POWER_RANGE.endInclusive - 1,
            valueDisplay = { it.roundToInt().toString() }
        )
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



@Preview
@Composable
fun SettingsPanelPreview() {
    HowlTheme {
        val viewModel: SettingsViewModel = viewModel()
        SettingsPanel(
            viewModel = viewModel,
            modifier = Modifier.fillMaxHeight()
        )
    }
}