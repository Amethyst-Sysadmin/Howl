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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.howl.ui.theme.HowlTheme
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.OutlinedTextField
import java.util.Locale

fun IntRange.toClosedFloatingPointRange(): ClosedFloatingPointRange<Float> {
    return this.first.toFloat()..this.last.toFloat()
}

class SettingsViewModel() : ViewModel() {
    fun setRemoteAccess(enabled: Boolean) {
        Prefs.remoteAccess.value = enabled
        Prefs.remoteAccess.save()
        if (enabled) {
            RemoteControlServer.start()
        }
        else {
            RemoteControlServer.stop()
        }
    }
    fun setOutputType(outputType: OutputType) {
        BluetoothHandler.disconnect()
        Prefs.outputType.value = outputType
        Prefs.outputType.save()
        Player.switchOutput(outputType)
    }
    fun setAudioOutputMinFrequency(newFrequency: Int) {
        val currentMax = Prefs.outputAudioMaxFrequency.value
        val clampedMin = newFrequency.coerceAtMost(currentMax - 50).coerceAtLeast(10)
        Prefs.outputAudioMinFrequency.value = clampedMin
        audioOutputFrequencyRangeUpdated()
    }
    fun setAudioOutputMaxFrequency(newFrequency: Int) {
        val currentMin = Prefs.outputAudioMinFrequency.value
        val clampedMax = newFrequency.coerceAtLeast(currentMin + 50).coerceAtLeast(10)
        Prefs.outputAudioMaxFrequency.value = clampedMax
        audioOutputFrequencyRangeUpdated()
    }
    fun audioOutputFrequencyRangeUpdated() {
        val newRange = Prefs.outputAudioMinFrequency.value .. Prefs.outputAudioMaxFrequency.value
        Player.output.allowedFrequencyRange = newRange
        MainOptions.setFrequencyRange(newRange)
    }
    fun syncCoyoteParameters() {
        if (Player.output is Coyote3Output) {
            val output = Player.output as Coyote3Output
            if (output.ready)
                output.syncParameters()
        }
    }
}

@Composable
fun OutputSettingsPanel(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val outputType by Prefs.outputType.collectAsStateWithLifecycle()
    val outputC3FrequencyBalanceA by Prefs.outputC3FrequencyBalanceA.collectAsStateWithLifecycle()
    val outputC3FrequencyBalanceB by Prefs.outputC3FrequencyBalanceB.collectAsStateWithLifecycle()
    val outputC3IntensityBalanceA by Prefs.outputC3IntensityBalanceA.collectAsStateWithLifecycle()
    val outputC3IntensityBalanceB by Prefs.outputC3IntensityBalanceB.collectAsStateWithLifecycle()
    val outputAudioWaveShape by Prefs.outputAudioWaveShape.collectAsStateWithLifecycle()
    val outputAudioCarrierShape by Prefs.outputAudioCarrierShape.collectAsStateWithLifecycle()
    val outputAudioMaxFrequency by Prefs.outputAudioMaxFrequency.collectAsStateWithLifecycle()
    val outputAudioMinFrequency by Prefs.outputAudioMinFrequency.collectAsStateWithLifecycle()
    val outputAudioCarrierPhaseType by Prefs.outputAudioCarrierPhaseType.collectAsStateWithLifecycle()
    val outputAudioCarrierFrequency by Prefs.outputAudioCarrierFrequency.collectAsStateWithLifecycle()
    val outputAudioWaveletWidth by Prefs.outputAudioWaveletWidth.collectAsStateWithLifecycle()
    val outputAudioWaveletFade by Prefs.outputAudioWaveletFade.collectAsStateWithLifecycle()

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
            currentValue = outputType,
            onValueChange = { viewModel.setOutputType(it) },
            options = OutputType.entries,
            getText = { it.displayName }
        )
    }

    if(outputType == OutputType.AUDIO_WAVELET) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = "Audio (wavelet)", style = MaterialTheme.typography.headlineSmall)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Carrier wave shape", style = MaterialTheme.typography.labelLarge)
            OptionPicker(
                currentValue = outputAudioCarrierShape,
                onValueChange = {
                    Prefs.outputAudioCarrierShape.value = it
                    Prefs.outputAudioCarrierShape.save()
                },
                options = AudioWaveShape.entries,
                getText = { it.displayName }
            )
        }
        SliderWithLabel(
            label = "Carrier wave frequency (Hz)",
            value = outputAudioCarrierFrequency.toFloat(),
            onValueChange = {
                Prefs.outputAudioCarrierFrequency.value = it.roundToInt()
            },
            onValueChangeFinished = { Prefs.outputAudioCarrierFrequency.save() },
            valueRange = 600.0f..2000.0f,
            steps = 139,
            valueDisplay = { it.roundToInt().toString() }
        )
        val waveletWidthRange = 3..10
        SliderWithLabel(
            label = "Wavelet width (in carrier wave cycles)",
            value = outputAudioWaveletWidth.toFloat(),
            onValueChange = { Prefs.outputAudioWaveletWidth.value = it.roundToInt() },
            onValueChangeFinished = { Prefs.outputAudioWaveletWidth.save() },
            valueRange = waveletWidthRange.first.toFloat()..waveletWidthRange.last.toFloat(),
            steps = (waveletWidthRange.last - waveletWidthRange.first) - 1,
            valueDisplay = { it.roundToInt().toString() }
        )
        SliderWithLabel(
            label = "Wavelet fade in/out proportion",
            value = outputAudioWaveletFade,
            onValueChange = { Prefs.outputAudioWaveletFade.value = it },
            onValueChangeFinished = { Prefs.outputAudioWaveletFade.save() },
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
                text = "Carrier phase on each channel",
                style = MaterialTheme.typography.labelLarge
            )
            OptionPicker(
                currentValue = outputAudioCarrierPhaseType,
                onValueChange = {
                    Prefs.outputAudioCarrierPhaseType.value = it
                    Prefs.outputAudioCarrierPhaseType.save()
                },
                options = AudioPhaseType.entries,
                getText = { it.displayName }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {

            val dutyCycle =
                ((1.0 / outputAudioCarrierFrequency) * outputAudioWaveletWidth) / 0.01
            val displayDutyCycle = (dutyCycle * 100.0).coerceIn(0.0..100.0).roundToInt()
            Text(
                text = "Estimated duty cycle at 100Hz: $displayDutyCycle%",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }

    if(outputType == OutputType.AUDIO_CONTINUOUS) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = "Audio (continuous)", style = MaterialTheme.typography.headlineSmall)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Wave shape", style = MaterialTheme.typography.labelLarge)
            OptionPicker(
                currentValue = outputAudioWaveShape,
                onValueChange = {
                    Prefs.outputAudioWaveShape.value = it
                    Prefs.outputAudioWaveShape.save()
                },
                options = AudioWaveShape.entries,
                getText = { it.displayName }
            )
        }
        val frequencySliderRange = 10..1000
        SliderWithLabel(
            label = "Minimum allowed frequency (Hz)",
            value = outputAudioMinFrequency.toFloat(),
            onValueChange = { viewModel.setAudioOutputMinFrequency(it.roundToInt()) },
            onValueChangeFinished = { Prefs.outputAudioMinFrequency.save() },
            valueRange = frequencySliderRange.toClosedFloatingPointRange(),
            steps = ((frequencySliderRange.last - frequencySliderRange.first) * 0.1).roundToInt() - 1,
            valueDisplay = { it.roundToInt().toString() }
        )
        SliderWithLabel(
            label = "Maximum allowed frequency (Hz)",
            value = outputAudioMaxFrequency.toFloat(),
            onValueChange = { viewModel.setAudioOutputMaxFrequency(it.roundToInt()) },
            onValueChangeFinished = { Prefs.outputAudioMaxFrequency.save() },
            valueRange = frequencySliderRange.toClosedFloatingPointRange(),
            steps = ((frequencySliderRange.last - frequencySliderRange.first) * 0.1).roundToInt() - 1,
            valueDisplay = { it.roundToInt().toString() }
        )
    }

    if(outputType == OutputType.COYOTE3) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = "Coyote 3 parameters", style = MaterialTheme.typography.headlineSmall)
        }
        SliderWithLabel(
            label = "Channel A Frequency Balance",
            value = outputC3FrequencyBalanceA.toFloat(),
            onValueChange = {
                Prefs.outputC3FrequencyBalanceA.value = it.roundToInt()
            },
            onValueChangeFinished = {
                Prefs.outputC3FrequencyBalanceA.save()
                viewModel.syncCoyoteParameters()
            },
            valueRange = Coyote3Output.FREQUENCY_BALANCE_RANGE.toClosedFloatingPointRange(),
            steps = Coyote3Output.FREQUENCY_BALANCE_RANGE.last - 1,
            valueDisplay = { it.roundToInt().toString() }
        )
        SliderWithLabel(
            label = "Channel B Frequency Balance",
            value = outputC3FrequencyBalanceB.toFloat(),
            onValueChange = {
                Prefs.outputC3FrequencyBalanceB.value = it.roundToInt()
            },
            onValueChangeFinished = {
                Prefs.outputC3FrequencyBalanceB.save()
                viewModel.syncCoyoteParameters()
            },
            valueRange = Coyote3Output.FREQUENCY_BALANCE_RANGE.toClosedFloatingPointRange(),
            steps = Coyote3Output.FREQUENCY_BALANCE_RANGE.last - 1,
            valueDisplay = { it.roundToInt().toString() }
        )
        SliderWithLabel(
            label = "Channel A Intensity Balance",
            value = outputC3IntensityBalanceA.toFloat(),
            onValueChange = {
                Prefs.outputC3IntensityBalanceA.value = it.roundToInt()
            },
            onValueChangeFinished = {
                Prefs.outputC3IntensityBalanceA.save()
                viewModel.syncCoyoteParameters()
            },
            valueRange = Coyote3Output.INTENSITY_BALANCE_RANGE.toClosedFloatingPointRange(),
            steps = Coyote3Output.INTENSITY_BALANCE_RANGE.last - 1,
            valueDisplay = { it.roundToInt().toString() }
        )
        SliderWithLabel(
            label = "Channel B Intensity Balance",
            value = outputC3IntensityBalanceB.toFloat(),
            onValueChange = {
                Prefs.outputC3IntensityBalanceB.value = it.roundToInt()
            },
            onValueChangeFinished = {
                Prefs.outputC3IntensityBalanceB.save()
                viewModel.syncCoyoteParameters()
            },
            valueRange = Coyote3Output.INTENSITY_BALANCE_RANGE.toClosedFloatingPointRange(),
            steps = Coyote3Output.INTENSITY_BALANCE_RANGE.last - 1,
            valueDisplay = { it.roundToInt().toString() }
        )
    }
}

@Composable
fun PowerSettingsPanel(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val powerLimitA by Prefs.powerLimitA.collectAsStateWithLifecycle()
    val powerLimitB by Prefs.powerLimitB.collectAsStateWithLifecycle()
    val powerStepA by Prefs.powerStepA.collectAsStateWithLifecycle()
    val powerStepB by Prefs.powerStepB.collectAsStateWithLifecycle()
    val powerAutoIncrementDelayA by Prefs.powerAutoIncrementDelayA.collectAsStateWithLifecycle()
    val powerAutoIncrementDelayB by Prefs.powerAutoIncrementDelayB.collectAsStateWithLifecycle()

    Row(
        modifier = Modifier.fillMaxWidth().padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(text = "Power options", style = MaterialTheme.typography.headlineSmall)
    }
    SliderWithLabel(
        label = "Channel A Power Limit",
        value = powerLimitA.toFloat(),
        onValueChange = {
            Prefs.powerLimitA.value = it.roundToInt()
        },
        onValueChangeFinished = {
            Prefs.powerLimitA.save()
            viewModel.syncCoyoteParameters()
        },
        valueRange = Coyote3Output.POWER_RANGE.toClosedFloatingPointRange(),
        steps = Coyote3Output.POWER_RANGE.last - 1,
        valueDisplay = { it.roundToInt().toString() }
    )
    SliderWithLabel(
        label = "Channel B Power Limit",
        value = powerLimitB.toFloat(),
        onValueChange = {
            Prefs.powerLimitB.value = it.roundToInt()
        },
        onValueChangeFinished = {
            Prefs.powerLimitB.save()
            viewModel.syncCoyoteParameters()
        },
        valueRange = Coyote3Output.POWER_RANGE.toClosedFloatingPointRange(),
        steps = Coyote3Output.POWER_RANGE.last - 1,
        valueDisplay = { it.roundToInt().toString() }
    )
    val powerStepRange: IntRange = 1..10
    SliderWithLabel(
        label = "Power control step size A",
        value = powerStepA.toFloat(),
        onValueChange = { Prefs.powerStepA.value = it.roundToInt() },
        onValueChangeFinished = { Prefs.powerStepA.save() },
        valueRange = powerStepRange.toClosedFloatingPointRange(),
        steps = powerStepRange.last - 1,
        valueDisplay = { it.roundToInt().toString() }
    )
    SliderWithLabel(
        label = "Power control step size B",
        value = powerStepB.toFloat(),
        onValueChange = { Prefs.powerStepB.value = it.roundToInt() },
        onValueChangeFinished = { Prefs.powerStepB.save() },
        valueRange = powerStepRange.toClosedFloatingPointRange(),
        steps = powerStepRange.last - 1,
        valueDisplay = { it.roundToInt().toString() }
    )
    val autoIncrementRange: IntRange = 5..300
    SliderWithLabel(
        label = "Power auto increase delay A (seconds)",
        value = powerAutoIncrementDelayA.toFloat(),
        onValueChange = { Prefs.powerAutoIncrementDelayA.value = it.roundToInt() },
        onValueChangeFinished = { Prefs.powerAutoIncrementDelayA.save() },
        valueRange = autoIncrementRange.toClosedFloatingPointRange(),
        steps = ((autoIncrementRange.last - autoIncrementRange.first) * 0.2 - 1).roundToInt(),
        valueDisplay = { it.roundToInt().toString() }
    )
    SliderWithLabel(
        label = "Power auto increase delay B (seconds)",
        value = powerAutoIncrementDelayB.toFloat(),
        onValueChange = { Prefs.powerAutoIncrementDelayB.value = it.roundToInt() },
        onValueChangeFinished = { Prefs.powerAutoIncrementDelayB.save() },
        valueRange = autoIncrementRange.toClosedFloatingPointRange(),
        steps = ((autoIncrementRange.last - autoIncrementRange.first) * 0.2 - 1).roundToInt(),
        valueDisplay = { it.roundToInt().toString() }
    )
}

@Composable
fun SettingsPanel(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val miscShowPowerMeter by Prefs.miscShowPowerMeter.collectAsStateWithLifecycle()
    val miscShowDebugLog by Prefs.miscShowDebugLog.collectAsStateWithLifecycle()
    val remoteAccess by Prefs.remoteAccess.collectAsStateWithLifecycle()
    val remoteAPIKey by Prefs.remoteAPIKey.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .padding(16.dp)
            .fillMaxWidth(),
    ) {
        OutputSettingsPanel(viewModel, modifier)
        PowerSettingsPanel(viewModel, modifier)

        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = "Remote access options", style = MaterialTheme.typography.headlineSmall)
        }
        SwitchWithLabel(
            label = "Allow remote access",
            checked = remoteAccess,
            onCheckedChange = {
                viewModel.setRemoteAccess(it)
            }
        )
        val bearerRegex = Regex("[^A-Za-z0-9._~+/=-]")
        OutlinedTextField(
            value = remoteAPIKey,
            onValueChange = { input ->
                val filtered = input.replace(bearerRegex, "")
                Prefs.remoteAPIKey.value = filtered
                Prefs.remoteAPIKey.save()
            },
            label = { Text("Remote access key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = "Misc options", style = MaterialTheme.typography.headlineSmall)
        }
        SwitchWithLabel(
            label = "Show animated power meters",
            checked = miscShowPowerMeter,
            onCheckedChange = {
                Prefs.miscShowPowerMeter.value = it
                Prefs.miscShowPowerMeter.save()
            }
        )
        SwitchWithLabel(
            label = "Show debug log tab",
            checked = miscShowDebugLog,
            onCheckedChange = {
                Prefs.miscShowDebugLog.value = it
                Prefs.miscShowDebugLog.save()
            }
        )
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