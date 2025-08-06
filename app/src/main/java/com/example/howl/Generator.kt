package com.example.howl

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.howl.ui.theme.HowlTheme
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

class GeneratorChannel(
    val waveManager: WaveManager = WaveManager(),
    val minFreq: SmoothedValue = SmoothedValue(0.0),
    val maxFreq: SmoothedValue = SmoothedValue(1.0),
    val minAmp: SmoothedValue = SmoothedValue(0.0),
    val maxAmp: SmoothedValue = SmoothedValue(1.0),
    var doingWaveChange: Boolean = false
) {
    fun getInfo(): GeneratorChannelInfo {
        return GeneratorChannelInfo(
            ampWaveName = waveManager.getWave("amp").name,
            freqWaveName = waveManager.getWave("freq").name,
            minFreq = minFreq.current,
            maxFreq = maxFreq.current,
            minAmp = minAmp.current,
            maxAmp = maxAmp.current,
            speed = waveManager.currentSpeed
        )
    }
    fun update(timeDelta: Double) {
        waveManager.update(timeDelta)
        minFreq.update(timeDelta)
        maxFreq.update(timeDelta)
        minAmp.update(timeDelta)
        maxAmp.update(timeDelta)
    }
    fun getAmpAndFreq(): Pair<Double, Double> {
        val baseAmp = waveManager.getPosition("amp")
        val baseFreq = waveManager.getPosition("freq")
        val amp = baseAmp.scaleBetween(minAmp.current, maxAmp.current)
        val freq = baseFreq.scaleBetween(minFreq.current, maxFreq.current)
        return Pair(amp, freq)
    }
    fun appropriateSpeedRange() : ClosedFloatingPointRange<Double> {
        val numPoints = waveManager.getWave("amp").numPoints
        if (numPoints <= 4) {
            return Generator.SPEED_RANGE
        }
        else {
            val min = Generator.SPEED_RANGE.start
            val max = Generator.SPEED_RANGE.endInclusive * 4.0 / numPoints
            return min .. max
        }
    }
    fun makeRandomChanges(timeDelta: Double) {
        val state = DataRepository.generatorState.value
        val speedChangeProbability = state.speedChangeProbability
        val ampChangeProbability = state.amplitudeChangeProbability
        val freqChangeProbability = state.frequencyChangeProbability
        val waveChangeProbability = state.waveChangeProbability

        val baseProbability = 10.0 //average changes per minute at 1.0 probability

        val speedChange = (speedChangeProbability * baseProbability * timeDelta) / 60.0
        if (Random.nextDouble() < speedChange) {
            val range = appropriateSpeedRange()
            waveManager.setTargetSpeed(randomInRange(range), randomInRange(Generator.SPEED_CHANGE_RATE_RANGE))
        }

        val minAmpChange = (ampChangeProbability * baseProbability * timeDelta) / 60.0
        if (Random.nextDouble() < minAmpChange) {
            minAmp.setTarget(randomInRange(Generator.MIN_AMP_RANGE), randomInRange(Generator.CHANGE_RATE_RANGE))
        }

        val maxAmpChange = (ampChangeProbability * baseProbability * timeDelta) / 60.0
        if (Random.nextDouble() < maxAmpChange) {
            maxAmp.setTarget(randomInRange(Generator.MAX_AMP_RANGE), randomInRange(Generator.CHANGE_RATE_RANGE))
        }

        val minFreqChange = (freqChangeProbability * baseProbability * timeDelta) / 60.0
        if (Random.nextDouble() < minFreqChange) {
            minFreq.setTarget(randomInRange(Generator.MIN_FREQ_RANGE), randomInRange(Generator.CHANGE_RATE_RANGE))
        }

        val maxFreqChange = (freqChangeProbability * baseProbability * timeDelta) / 60.0
        if (Random.nextDouble() < maxFreqChange) {
            maxFreq.setTarget(randomInRange(Generator.MAX_FREQ_RANGE), randomInRange(Generator.CHANGE_RATE_RANGE))
        }

        val waveChange = (waveChangeProbability * baseProbability * timeDelta) / 60.0
        if (Random.nextDouble() < waveChange && !doingWaveChange) {
            doingWaveChange = true
            waveManager.stopAtEndOfCycle {
                changeAmpWave()
                doingWaveChange = false
            }
        }

        val freqWaveChange = (waveChangeProbability * baseProbability * timeDelta) / 60.0
        if (Random.nextDouble() < freqWaveChange && !doingWaveChange) {
            doingWaveChange = true
            waveManager.stopAtEndOfCycle {
                changeFreqWave()
                doingWaveChange = false
            }
        }
    }
    fun changeAmpWave() {
        waveManager.addWave(CyclicalWave(generatorWaveShapes.random()),"amp")
        val range = appropriateSpeedRange()
        if (waveManager.getTargetSpeed() !in range) {
            waveManager.setSpeed(randomInRange(range))
        }
        waveManager.restart()
    }
    fun changeFreqWave() {
        waveManager.addWave(CyclicalWave(generatorWaveShapes.random()),"freq")
        waveManager.restart()
    }
    fun randomise() {
        minAmp.setImmediately(randomInRange(Generator.MIN_AMP_RANGE))
        maxAmp.setImmediately(randomInRange(Generator.MAX_AMP_RANGE))
        minFreq.setImmediately(randomInRange(Generator.MIN_FREQ_RANGE))
        maxFreq.setImmediately(randomInRange(Generator.MAX_FREQ_RANGE))

        waveManager.addWave(CyclicalWave(generatorWaveShapes.random()),"amp")
        waveManager.addWave(CyclicalWave(generatorWaveShapes.random()),"freq")
        waveManager.restart()
        val range = appropriateSpeedRange()
        waveManager.setSpeed(randomInRange(range))
        waveManager.setSpeedVariance(0.0)
        waveManager.setAmplitudeVariance(0.0)
        waveManager.setSpeedVarianceEaseIn(0.0)
        waveManager.setAmplitudeVarianceEaseIn(0.0)
        doingWaveChange = false
    }
    fun setSpeed(speed: Double) {
        waveManager.setSpeed(speed)
    }
    fun setAmpWave(waveShape: WaveShape) {
        waveManager.addWave(CyclicalWave(waveShape), "amp")
        waveManager.restart()
    }
    fun setFreqWave(waveShape: WaveShape) {
        waveManager.addWave(CyclicalWave(waveShape), "freq")
        waveManager.restart()
    }
    fun setMinAmp(amplitude: Double) {
        minAmp.setImmediately(amplitude)
    }
    fun setMaxAmp(amplitude: Double) {
        maxAmp.setImmediately(amplitude)
    }
    fun setMinFreq(frequency: Double) {
        minFreq.setImmediately(frequency)
    }
    fun setMaxFreq(frequency: Double) {
        maxFreq.setImmediately(frequency)
    }
}

class GeneratorChannelInfo(
    val ampWaveName: String = "",
    val freqWaveName: String = "",
    val minFreq: Double = 0.0,
    val maxFreq: Double = 1.0,
    val minAmp: Double = 0.0,
    val maxAmp: Double = 1.0,
    val speed: Double = 1.0,
)

object Generator : PulseSource {
    val SPEED_RANGE = 0.1..2.0
    val SPEED_CHANGE_RATE_RANGE = 0.03..0.2
    val CHANGE_RATE_RANGE = 0.03..0.2
    val MIN_AMP_RANGE = 0.0..0.4
    val MAX_AMP_RANGE = 0.6..1.0
    val MIN_FREQ_RANGE = 0.0..1.0
    val MAX_FREQ_RANGE = 0.0..1.0
    val channelA: GeneratorChannel = GeneratorChannel()
    val channelB: GeneratorChannel = GeneratorChannel()

    override var displayName: String = "Generator output"
    override var duration: Double? = null
    override val isFinite: Boolean = false
    override val shouldLoop: Boolean = false
    override var readyToPlay: Boolean = false
    override var isRemote: Boolean = false
    override var remoteLatency: Double = 0.0

    private var lastSimulationTime = -1.0
    private var lastUpdateTime = -1.0
    private val timerManager = TimerManager()

    override fun updateState(currentTime: Double) {
        if (lastUpdateTime < 0 || lastUpdateTime > currentTime)
            lastUpdateTime = currentTime
        val state = DataRepository.generatorState.value
        val timeDelta = currentTime - lastUpdateTime
        lastUpdateTime = currentTime
        if (state.autoChange) {
            channelA.makeRandomChanges(timeDelta)
            channelB.makeRandomChanges(timeDelta)
        }
        updateInfo()
    }

    fun initialise() {
        if(!DataRepository.generatorState.value.initialised) {
            channelA.randomise()
            channelB.randomise()
            updateInfo()
        }

        updateGeneratorState(DataRepository.generatorState.value.copy(initialised = true))

        readyToPlay = true
    }

    fun updateGeneratorState(newGeneratorState: DataRepository.GeneratorState) {
        DataRepository.setGeneratorState(newGeneratorState)
    }
    fun updateInfo() {
        updateGeneratorState(DataRepository.generatorState.value.copy(
            channelAInfo = channelA.getInfo(),
            channelBInfo = channelB.getInfo()
        ))
    }
    fun randomise() {
        channelA.randomise()
        channelB.randomise()
        updateInfo()
    }
    fun getChannel(channelNumber: Int): GeneratorChannel {
        return if(channelNumber==0) channelA else channelB
    }

    override fun getPulseAtTime(time: Double): Pulse {
        if (lastSimulationTime < 0 || lastSimulationTime > time) {
            lastSimulationTime = time
        }

        val simulationTimeDelta = time - lastSimulationTime
        lastSimulationTime = time
        timerManager.update(simulationTimeDelta)
        channelA.update(simulationTimeDelta)
        channelB.update(simulationTimeDelta)

        val (ampA, freqA) = channelA.getAmpAndFreq()
        val (ampB, freqB) = channelB.getAmpAndFreq()

        return Pulse(ampA.toFloat(), ampB.toFloat(), freqA.toFloat(), freqB.toFloat())
    }
}

class GeneratorViewModel() : ViewModel() {
    val generatorState: StateFlow<DataRepository.GeneratorState> = DataRepository.generatorState
    fun updateGeneratorState(newGeneratorState: DataRepository.GeneratorState) {
        DataRepository.setGeneratorState(newGeneratorState)
    }
    fun randomise() {
        Generator.randomise()
    }
    fun stopGenerator() {
        Player.stopPlayer()
    }
    fun startGenerator() {
        Player.switchPulseSource(Generator)
        Player.startPlayer()
    }
    fun setAutoChange(enabled: Boolean) {
        Generator.updateGeneratorState(DataRepository.generatorState.value.copy(autoChange = enabled))
    }
    fun setSpeedChangeProbability(probability: Double) {
        Generator.updateGeneratorState(DataRepository.generatorState.value.copy(speedChangeProbability = probability))
    }
    fun setAmplitudeChangeProbability(probability: Double) {
        Generator.updateGeneratorState(DataRepository.generatorState.value.copy(amplitudeChangeProbability = probability))
    }
    fun setFrequencyChangeProbability(probability: Double) {
        Generator.updateGeneratorState(DataRepository.generatorState.value.copy(frequencyChangeProbability = probability))
    }
    fun setWaveChangeProbability(probability: Double) {
        Generator.updateGeneratorState(DataRepository.generatorState.value.copy(waveChangeProbability = probability))
    }

    fun setSpeed(channel: Int, speed: Double) {
        Generator.getChannel(channel).setSpeed(speed)
        Generator.updateInfo()
    }
    fun setAmpWave(channel: Int, waveName: String) {
        val waveShape = generatorWaveShapes.find { it.name == waveName }
        waveShape?.let {
            Generator.getChannel(channel).setAmpWave(it)
            Generator.updateInfo()
        }
    }
    fun setFreqWave(channel: Int, waveName: String) {
        val waveShape = generatorWaveShapes.find { it.name == waveName }
        waveShape?.let {
            Generator.getChannel(channel).setFreqWave(it)
            Generator.updateInfo()
        }
    }
    fun setMinAmp(channel: Int, amplitude: Double) {
        Generator.getChannel(channel).setMinAmp(amplitude)
        Generator.updateInfo()
    }
    fun setMaxAmp(channel: Int, amplitude: Double) {
        Generator.getChannel(channel).setMaxAmp(amplitude)
        Generator.updateInfo()
    }
    fun setMinFreq(channel: Int, frequency: Double) {
        Generator.getChannel(channel).setMinFreq(frequency)
        Generator.updateInfo()
    }
    fun setMaxFreq(channel: Int, frequency: Double) {
        Generator.getChannel(channel).setMaxFreq(frequency)
        Generator.updateInfo()
    }

    fun saveSettings() {
        viewModelScope.launch {
            DataRepository.saveSettings()
        }
    }
}

@Composable
fun GeneratorPanel(
    viewModel: GeneratorViewModel,
    frequencyRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier
) {
    val generatorState by viewModel.generatorState.collectAsStateWithLifecycle()
    val playerState by DataRepository.playerState.collectAsStateWithLifecycle()
    val isPlaying = playerState.isPlaying && playerState.activePulseSource == Generator
    var showChannelASettings by remember { mutableStateOf(false) }
    var showChannelBSettings by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            GeneratorParametersInfo("Channel A", generatorState.channelAInfo, frequencyRange, onClick = { showChannelASettings = true }, modifier=Modifier.weight(1f))
            GeneratorParametersInfo("Channel B", generatorState.channelBInfo, frequencyRange, onClick = { showChannelBSettings = true }, modifier=Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(
                onClick = {
                    if (isPlaying)
                        viewModel.stopGenerator()
                    else
                        viewModel.startGenerator()
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
            Button(
                onClick = { viewModel.randomise() }
            ) {
                Icon(
                    painter = painterResource(R.drawable.casino),
                    contentDescription = "Randomise"
                )
                Text(text = "Random", modifier = Modifier.padding(start = 8.dp))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Automatically change parameters", style = MaterialTheme.typography.labelLarge)
            Switch(
                checked = generatorState.autoChange,
                onCheckedChange = {
                    viewModel.setAutoChange(it)
                    viewModel.saveSettings()
                }
            )
        }
        if (generatorState.autoChange) {
            SliderWithLabel(
                label = "Speed change probability",
                value = generatorState.speedChangeProbability.toFloat(),
                onValueChange = {viewModel.setSpeedChangeProbability(probability = it.toDouble())},
                onValueChangeFinished = { viewModel.saveSettings() },
                valueRange = 0f..1.0f,
                steps = 99,
                valueDisplay = { String.format(Locale.US, "%03.2f", it) }
            )
            SliderWithLabel(
                label = "Amplitude change probability",
                value = generatorState.amplitudeChangeProbability.toFloat(),
                onValueChange = {viewModel.setAmplitudeChangeProbability(probability = it.toDouble())},
                onValueChangeFinished = { viewModel.saveSettings() },
                valueRange = 0f..1.0f,
                steps = 99,
                valueDisplay = { String.format(Locale.US, "%03.2f", it) }
            )
            SliderWithLabel(
                label = "Frequency change probability",
                value = generatorState.frequencyChangeProbability.toFloat(),
                onValueChange = {viewModel.setFrequencyChangeProbability(probability = it.toDouble())},
                onValueChangeFinished = { viewModel.saveSettings() },
                valueRange = 0f..1.0f,
                steps = 99,
                valueDisplay = { String.format(Locale.US, "%03.2f", it) }
            )
            SliderWithLabel(
                label = "Shape change probability",
                value = generatorState.waveChangeProbability.toFloat(),
                onValueChange = {viewModel.setWaveChangeProbability(probability = it.toDouble())},
                onValueChangeFinished = { viewModel.saveSettings() },
                valueRange = 0f..1.0f,
                steps = 99,
                valueDisplay = { String.format(Locale.US, "%03.2f", it) }
            )
        }
        if (showChannelASettings) {
            Dialog(
                onDismissRequest = { showChannelASettings = false }
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
                ) {
                    GeneratorParametersSettings(
                        viewModel = viewModel,
                        channel = 0,
                        generatorChannelInfo = generatorState.channelAInfo,
                        title = "Channel A Settings"
                    )
                }
            }
        }
        if (showChannelBSettings) {
            Dialog(
                onDismissRequest = { showChannelBSettings = false }
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
                ) {
                    GeneratorParametersSettings(
                        viewModel = viewModel,
                        channel = 1,
                        generatorChannelInfo = generatorState.channelBInfo,
                        title = "Channel B Settings"
                    )
                }
            }
        }
    }
}

@Composable
fun GeneratorParametersSettings(
    viewModel: GeneratorViewModel,
    channel: Int,
    generatorChannelInfo: GeneratorChannelInfo,
    title: String,
    modifier: Modifier = Modifier
) {
    Column (modifier=modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)){
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = title, style = MaterialTheme.typography.headlineSmall)
        }
        SliderWithLabel(
            label = "Speed (repetitions/sec)",
            value = generatorChannelInfo.speed.toFloat(),
            onValueChange = { viewModel.setSpeed(channel = channel, speed = it.toDouble()) },
            onValueChangeFinished = { },
            valueRange = Generator.SPEED_RANGE.toFloatRange,
            steps = ((Generator.SPEED_RANGE.endInclusive - Generator.SPEED_RANGE.start) * 10.0 - 1).roundToInt(),
            valueDisplay = { String.format(Locale.US, "%02.1f", it) }
        )
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Wave shape")
            OptionPicker(
                currentValue = generatorChannelInfo.ampWaveName,
                onValueChange = { viewModel.setAmpWave(channel, it) },
                options = generatorWaveShapes.map { it.name },
                getText = { it }
            )
        }
        SliderWithLabel(
            label = "Start power",
            value = generatorChannelInfo.minAmp.toFloat(),
            onValueChange = { viewModel.setMinAmp(channel = channel, amplitude = it.toDouble()) },
            onValueChangeFinished = { },
            valueRange = 0.0f .. 1.0f,
            steps = 99,
            valueDisplay = { String.format(Locale.US, "%.0f%%", it * 100.0) }
        )
        SliderWithLabel(
            label = "End power",
            value = generatorChannelInfo.maxAmp.toFloat(),
            onValueChange = { viewModel.setMaxAmp(channel = channel, amplitude = it.toDouble()) },
            onValueChangeFinished = { },
            valueRange = 0.0f .. 1.0f,
            steps = 99,
            valueDisplay = { String.format(Locale.US, "%.0f%%", it * 100.0) }
        )
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Freq shape")
            OptionPicker(
                currentValue = generatorChannelInfo.freqWaveName,
                onValueChange = { viewModel.setFreqWave(channel, it) },
                options = generatorWaveShapes.map { it.name },
                getText = { it }
            )
        }
        SliderWithLabel(
            label = "Start frequency",
            value = generatorChannelInfo.minFreq.toFloat(),
            onValueChange = { viewModel.setMinFreq(channel = channel, frequency = it.toDouble()) },
            onValueChangeFinished = { },
            valueRange = 0.0f .. 1.0f,
            steps = 99,
            valueDisplay = { String.format(Locale.US, "%.0f%%", it * 100.0) }
        )
        SliderWithLabel(
            label = "End frequency",
            value = generatorChannelInfo.maxFreq.toFloat(),
            onValueChange = { viewModel.setMaxFreq(channel = channel, frequency = it.toDouble()) },
            onValueChangeFinished = { },
            valueRange = 0.0f .. 1.0f,
            steps = 99,
            valueDisplay = { String.format(Locale.US, "%.0f%%", it * 100.0) }
        )
    }
}

@Composable
fun GeneratorParametersInfo(
    title: String,
    generatorChannelInfo: GeneratorChannelInfo,
    frequencyRange: ClosedFloatingPointRange<Float>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Card(modifier = Modifier.fillMaxWidth().fillMaxHeight().clickable(onClick = onClick)) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 0.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            GeneratorParametersInfoRow(
                category = "Shape",
                value = generatorChannelInfo.ampWaveName
            )
            GeneratorParametersInfoRow(
                category = "Speed",
                value = String.format(Locale.US, "%.2f", generatorChannelInfo.speed)
            )
            val ampLow = String.format(Locale.US, "%.1f", generatorChannelInfo.minAmp * 100.0)
            val ampHigh = String.format(Locale.US, "%.1f", generatorChannelInfo.maxAmp * 100.0)
            val ampText = if (generatorChannelInfo.ampWaveName == "Constant") "$ampHigh%" else "$ampLow% - $ampHigh%"
            GeneratorParametersInfoRow(
                category = "Power",
                value = ampText
            )
            GeneratorParametersInfoRow(
                category = "Freq shape",
                value = generatorChannelInfo.freqWaveName
            )
            val freqLow =
                frequencyRange.start + generatorChannelInfo.minFreq * (frequencyRange.endInclusive - frequencyRange.start)
            val freqHigh =
                frequencyRange.start + generatorChannelInfo.maxFreq * (frequencyRange.endInclusive - frequencyRange.start)
            val freqText =
                if (generatorChannelInfo.freqWaveName == "Constant")
                    String.format(Locale.US, "%.1fHz", freqHigh)
                else
                    String.format(Locale.US, "%.1fHz - %.1fHz", freqLow, freqHigh)
            GeneratorParametersInfoRow(
                category = "Freq",
                value = freqText
            )
        }
    }
}

@Composable
fun GeneratorParametersInfoRow(
    category: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = category,
            style = MaterialTheme.typography.bodySmall)
        Text(text = value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun <T> OptionPicker(
    currentValue: T,
    onValueChange: (T) -> Unit,
    options: List<T>,
    getText: (T) -> String,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val sortedOptions = remember(options, getText) {
        options.sortedBy { getText(it) }
    }

    Box(modifier = modifier.wrapContentSize(Alignment.TopStart)) {
        OutlinedButton(onClick = { expanded = true }) {
            Text(text = getText(currentValue))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            sortedOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(getText(option)) },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Preview
@Composable
fun GeneratorPreview() {
    HowlTheme {
        val viewModel: GeneratorViewModel = viewModel()
        GeneratorPanel(
            viewModel = viewModel,
            frequencyRange = 10f..100f,
            modifier = Modifier.fillMaxHeight()
        )
    }
}