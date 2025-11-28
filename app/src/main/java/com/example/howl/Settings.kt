package com.example.howl

import android.widget.Toast
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
import androidx.compose.ui.res.stringResource
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
    val miscOptionsState: StateFlow<DataRepository.MiscOptionsState> =
        DataRepository.miscOptionsState
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
        } else {
            RemoteControlServer.stop()
        }
    }

    fun setOutputType(outputType: OutputType) {
        setOutputState(outputState.value.copy(outputType = outputType))
        saveSettings()
        BluetoothHandler.disconnect()
        Player.switchOutput(outputType)
    }

    fun setAudioWaveShape(shape: AudioWaveShape) {
        setOutputState(outputState.value.copy(audioWaveShape = shape))
        saveSettings()
    }

    fun setAudioCarrierShape(shape: AudioWaveShape) {
        setOutputState(outputState.value.copy(audioCarrierShape = shape))
        saveSettings()
    }

    fun setAudioCarrierPhaseType(type: AudioPhaseType) {
        setOutputState(outputState.value.copy(audioCarrierPhaseType = type))
        saveSettings()
    }

    fun setAudioOutputMinFrequency(newFrequency: Int) {
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
        val newRange =
            outputState.value.audioOutputMinFrequency..outputState.value.audioOutputMaxFrequency
        Player.output.allowedFrequencyRange = newRange
        DataRepository.setFrequencyRange(newRange)
    }

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
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier = modifier
            .padding(16.dp)
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = "Howl version $howlVersion", style = MaterialTheme.typography.labelLarge)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = context.getString(R.string.settings_view),
                style = MaterialTheme.typography.headlineSmall
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = context.getString(R.string.settings_language),
                style = MaterialTheme.typography.labelLarge
            )
            OptionPicker(
                currentValue = miscOptionsState.language,
                onValueChange = { language ->
                    viewModel.setMiscOptionsState(miscOptionsState.copy(language = language))
                    viewModel.saveSettings()
                    // 显示重启提示并延迟自动重启应用
                    Toast.makeText(
                        context,
                        context.getString(R.string.language_restarting),
                        Toast.LENGTH_SHORT
                    ).show()

                    // 使用Handler延迟重启应用
                    val handler = android.os.Handler(context.mainLooper)
                    handler.postDelayed({
                        val intent =
                            context.packageManager.getLaunchIntentForPackage(context.packageName)
                        intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        // 结束当前应用进程
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }, 1500) // 1.5秒延迟，让用户有时间看到提示
                },
                options = listOf("zh", "en"),
                getText = {
                    when (it) {
                        "en" -> context.getString(R.string.language_english)
                        "zh" -> context.getString(R.string.language_chinese)
                        else -> it
                    }
                }
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = context.getString(R.string.output_options),
                style = MaterialTheme.typography.headlineSmall
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = context.getString(R.string.output_type),
                style = MaterialTheme.typography.labelLarge
            )
            val outputTypeTexts = mapOf(
                    OutputType.COYOTE3 to R.string.settings_coyote_parameters,
                    OutputType.AUDIO_CONTINUOUS to R.string.settings_audio_continuous,
                    OutputType.AUDIO_WAVELET to R.string.settings_audio_wavelet
                )
                OptionPicker(
                    currentValue = outputState.outputType,
                    onValueChange = {
                        viewModel.setOutputType(it)
                    },
                    options = OutputType.entries,
                    getText = { outputType ->
                        outputTypeTexts[outputType]?.let { context.getString(it) } ?: outputType.displayName
                    }
                )
        }

        // 在Composable上下文中创建波形资源映射
        val audioWaveShapeResources = mapOf(
            AudioWaveShape.SINE to R.string.wave_shape_sine,
            AudioWaveShape.SQUARE to R.string.wave_shape_square,
            AudioWaveShape.TRIANGLE to R.string.wave_shape_triangle,
            AudioWaveShape.SAWTOOTH to R.string.wave_shape_sawtooth
            // 故意不包含不存在的TRAPEZOID资源
        )
        
        // 创建本地化的波形文本映射
        val audioWaveShapeLabels = audioWaveShapeResources.mapValues { stringResource(it.value) }
        
        if (outputState.outputType == OutputType.AUDIO_WAVELET) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = context.getString(R.string.audio_wavelet),
                    style = MaterialTheme.typography.headlineSmall
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = context.getString(R.string.carrier_wave_shape),
                    style = MaterialTheme.typography.labelLarge
                )
                OptionPicker(
                    currentValue = outputState.audioCarrierShape,
                    onValueChange = {
                        viewModel.setAudioCarrierShape(it)
                    },
                    options = AudioWaveShape.entries,
                getText = { shape -> audioWaveShapeLabels.getOrDefault(shape, shape.displayName) }
                )
            }
            SliderWithLabel(
                label = context.getString(R.string.settings_carrier_wave_frequency),
                value = outputState.audioCarrierFrequency.toFloat(),
                onValueChange = {
                    viewModel.setOutputState(
                        outputState.copy(
                            audioCarrierFrequency = it.roundToInt()
                        )
                    )
                },
                onValueChangeFinished = { viewModel.saveSettings() },
                valueRange = 600.0f..2000.0f,
                steps = 139,
                valueDisplay = { it.roundToInt().toString() }
            )
            val waveletWidthRange = 3..10
            SliderWithLabel(
                label = context.getString(R.string.settings_wavelet_width),
                value = outputState.audioWaveletWidth.toFloat(),
                onValueChange = { viewModel.setOutputState(outputState.copy(audioWaveletWidth = it.roundToInt())) },
                onValueChangeFinished = { viewModel.saveSettings() },
                valueRange = waveletWidthRange.start.toFloat()..waveletWidthRange.endInclusive.toFloat(),
                steps = (waveletWidthRange.endInclusive - waveletWidthRange.start) - 1,
                valueDisplay = { it.roundToInt().toString() }
            )
            SliderWithLabel(
                label = context.getString(R.string.settings_wavelet_fade),
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
                Text(
                    text = context.getString(R.string.settings_carrier_phase),
                    style = MaterialTheme.typography.labelLarge
                )
                val phaseTypeTexts = mapOf(
                    AudioPhaseType.SAME to R.string.phase_type_same,
                    AudioPhaseType.OFFSET to R.string.phase_type_offset,
                    AudioPhaseType.OPPOSITE to R.string.phase_type_opposite
                )
                OptionPicker(
                    currentValue = outputState.audioCarrierPhaseType,
                    onValueChange = {
                        viewModel.setAudioCarrierPhaseType(it)
                    },
                    options = AudioPhaseType.entries,
                    getText = { phaseType ->
                        phaseTypeTexts[phaseType]?.let { context.getString(it) } ?: phaseType.displayName
                    }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val dutyCycle =
                    ((1.0 / outputState.audioCarrierFrequency) * outputState.audioWaveletWidth) / 0.01
                val displayDutyCycle = (dutyCycle * 100.0).coerceIn(0.0..100.0).roundToInt()
                Text(
                    text = context.getString(
                        R.string.settings_estimated_duty_cycle,
                        displayDutyCycle
                    ),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        if (outputState.outputType == OutputType.AUDIO_CONTINUOUS) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = context.getString(R.string.settings_audio_continuous),
                    style = MaterialTheme.typography.headlineSmall
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = context.getString(R.string.settings_wave_shape),
                    style = MaterialTheme.typography.labelLarge
                )
                OptionPicker(
                    currentValue = outputState.audioWaveShape,
                    onValueChange = {
                        viewModel.setAudioWaveShape(it)
                    },
                    options = AudioWaveShape.entries,
                getText = { shape -> audioWaveShapeLabels.getOrDefault(shape, shape.displayName) }
                )
            }
            val FREQUENCY_SLIDER_RANGE = 10..1000
            SliderWithLabel(
                label = context.getString(R.string.settings_minimum_frequency),
                value = outputState.audioOutputMinFrequency.toFloat(),
                onValueChange = { viewModel.setAudioOutputMinFrequency(it.roundToInt()) },
                onValueChangeFinished = { viewModel.saveSettings() },
                valueRange = FREQUENCY_SLIDER_RANGE.toClosedFloatingPointRange(),
                steps = ((FREQUENCY_SLIDER_RANGE.endInclusive - FREQUENCY_SLIDER_RANGE.start) * 0.1).roundToInt() - 1,
                valueDisplay = { it.roundToInt().toString() }
            )
            SliderWithLabel(
                label = context.getString(R.string.settings_maximum_frequency),
                value = outputState.audioOutputMaxFrequency.toFloat(),
                onValueChange = { viewModel.setAudioOutputMaxFrequency(it.roundToInt()) },
                onValueChangeFinished = { viewModel.saveSettings() },
                valueRange = FREQUENCY_SLIDER_RANGE.toClosedFloatingPointRange(),
                steps = ((FREQUENCY_SLIDER_RANGE.endInclusive - FREQUENCY_SLIDER_RANGE.start) * 0.1).roundToInt() - 1,
                valueDisplay = { it.roundToInt().toString() }
            )
        }

        if (outputState.outputType == OutputType.COYOTE3) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = context.getString(R.string.settings_coyote_parameters),
                    style = MaterialTheme.typography.headlineSmall
                )
            }
            SliderWithLabel(
                label = context.getString(R.string.settings_channel_a_frequency_balance),
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
                label = context.getString(R.string.settings_channel_b_frequency_balance),
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
                label = context.getString(R.string.settings_channel_a_intensity_balance),
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
                label = context.getString(R.string.settings_channel_b_intensity_balance),
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = context.getString(R.string.settings_power_options),
                style = MaterialTheme.typography.headlineSmall
            )
        }
        SliderWithLabel(
            label = context.getString(R.string.settings_channel_a_power_limit),
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
            label = context.getString(R.string.settings_channel_b_power_limit),
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
            label = context.getString(R.string.settings_power_step_size_a),
            value = miscOptionsState.powerStepSizeA.toFloat(),
            onValueChange = { viewModel.setMiscOptionsState(miscOptionsState.copy(powerStepSizeA = it.roundToInt())) },
            onValueChangeFinished = { viewModel.saveSettings() },
            valueRange = powerStepRange.toClosedFloatingPointRange(),
            steps = powerStepRange.endInclusive - 1,
            valueDisplay = { it.roundToInt().toString() }
        )
        SliderWithLabel(
            label = context.getString(R.string.settings_power_step_size_b),
            value = miscOptionsState.powerStepSizeB.toFloat(),
            onValueChange = { viewModel.setMiscOptionsState(miscOptionsState.copy(powerStepSizeB = it.roundToInt())) },
            onValueChangeFinished = { viewModel.saveSettings() },
            valueRange = powerStepRange.toClosedFloatingPointRange(),
            steps = powerStepRange.endInclusive - 1,
            valueDisplay = { it.roundToInt().toString() }
        )
        val autoIncrementRange: IntRange = 5..300
        SliderWithLabel(
            label = context.getString(R.string.settings_power_auto_increase_delay_a),
            value = miscOptionsState.powerAutoIncrementDelayA.toFloat(),
            onValueChange = {
                viewModel.setMiscOptionsState(
                    miscOptionsState.copy(
                        powerAutoIncrementDelayA = it.roundToInt()
                    )
                )
            },
            onValueChangeFinished = { viewModel.saveSettings() },
            valueRange = autoIncrementRange.toClosedFloatingPointRange(),
            steps = ((autoIncrementRange.endInclusive - autoIncrementRange.start) * 0.2 - 1).roundToInt(),
            valueDisplay = { it.roundToInt().toString() }
        )
        SliderWithLabel(
            label = context.getString(R.string.settings_power_auto_increase_delay_b),
            value = miscOptionsState.powerAutoIncrementDelayB.toFloat(),
            onValueChange = {
                viewModel.setMiscOptionsState(
                    miscOptionsState.copy(
                        powerAutoIncrementDelayB = it.roundToInt()
                    )
                )
            },
            onValueChangeFinished = { viewModel.saveSettings() },
            valueRange = autoIncrementRange.toClosedFloatingPointRange(),
            steps = ((autoIncrementRange.endInclusive - autoIncrementRange.start) * 0.2 - 1).roundToInt(),
            valueDisplay = { it.roundToInt().toString() }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = context.getString(R.string.settings_misc_options),
                style = MaterialTheme.typography.headlineSmall
            )
        }
        SwitchWithLabel(
            label = context.getString(R.string.settings_allow_remote_access),
            checked = miscOptionsState.remoteAccess,
            onCheckedChange = {
                viewModel.setRemoteAccess(it)
            }
        )
        SwitchWithLabel(
            label = context.getString(R.string.settings_smoother_charts),
            checked = miscOptionsState.smootherCharts,
            onCheckedChange = {
                viewModel.setMiscOptionsState(miscOptionsState.copy(smootherCharts = it))
                viewModel.saveSettings()
            }
        )
        SwitchWithLabel(
            label = context.getString(R.string.settings_show_power_meter),
            checked = miscOptionsState.showPowerMeter,
            onCheckedChange = {
                viewModel.setMiscOptionsState(miscOptionsState.copy(showPowerMeter = it))
                viewModel.saveSettings()
            }
        )
        SwitchWithLabel(
            label = context.getString(R.string.settings_show_debug_log),
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
            Text(
                text = context.getString(R.string.settings_resource_note),
                style = MaterialTheme.typography.labelSmall
            )
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