package com.example.howl

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.collections.any

object OutputManager {
    const val TAG = "OutputManager"
    const val MAXIMUM_OUTPUTS = 7
    var audioEngine = AudioEngine()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _outputs = MutableStateFlow<List<BaseOutput>>(emptyList())
    val outputs: StateFlow<List<BaseOutput>> = _outputs.asStateFlow()
    val sampleRate: Int get() = audioEngine.sampleRate

    fun initialise() {
        audioEngine.initialise()

        // Automatically plays our audio engine if there is an audio output and the player is active.
        // Automatically stops it otherwise.
        scope.launch {
            combine(
                Player.playerState,
                outputs
            ) { playerState, currentOutputs ->
                // The engine should only play if the player is active AND we have an audio output
                playerState.isPlaying && currentOutputs.any { it is AudioOutput }
            }
            .distinctUntilChanged() // Crucial: Only trigger when the boolean result actually changes
            .collect { shouldPlay ->
                if (shouldPlay) {
                    Log.d(TAG, "Starting audio engine")
                    audioEngine.play()
                } else {
                    Log.d(TAG, "Stopping audio engine")
                    audioEngine.stop()
                }
            }
        }
    }

    fun saveState() {
        val states = _outputs.value.map { it.toState() }
        Prefs.outputStates.value = states
        Prefs.outputStates.save()
    }

    fun restoreOutputs(states: List<OutputState>) {
        val restored = states.mapNotNull { state ->
            val type = OutputType.entries.find { it.name == state.type } ?: return@mapNotNull null
            val newOutput = type.create() as BaseOutput
            newOutput.applyState(state)
            newOutput.initialise()

            if (newOutput is AudioBlockProvider) {
                audioEngine.setProvider(newOutput)
            }
            newOutput
        }
        _outputs.value = restored
    }

    fun addOutput(type: OutputType) {
        if (_outputs.value.size >= MAXIMUM_OUTPUTS) return

        val newOutput = type.create() as BaseOutput
        newOutput.initialise()
        _outputs.value += newOutput

        if (newOutput is AudioBlockProvider) {
            audioEngine.setProvider(newOutput)
        }
        saveState()
    }

    fun removeOutput(output: BaseOutput) {
        output.stop()

        if (output is ConnectableOutput) {
            output.disconnect()
        }

        if (output is AudioBlockProvider) {
            audioEngine.setProvider(null)
        }

        output.destroy()
        _outputs.value -= output
        saveState()
    }

    fun start() {
        // Signal to all our outputs that we are starting playback
        outputs.value.forEach { it.start() }
    }

    fun stop() {
        // Signal to all our outputs that we are stopping playback
        outputs.value.forEach { it.stop() }
    }

    fun powerLimitsUpdated() {
        // Called when global power limits are changed in the settings panel.
        // We need to sync them to any Coyote 3 outputs, as we also use the internal power limit
        // parameter on that for a second layer of safety.
        outputs.value.forEach { output ->
            if (output is Coyote3Output) {
                output.syncParameters()
            }
        }
    }
}

@Composable
fun OutputsPanel(
    onRequestPermissions: (Array<String>, (Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val outputs by OutputManager.outputs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showAddOutputDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // Header row with Title and Plus button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Outputs",
                    style = MaterialTheme.typography.titleLarge,
                )
                IconButton(onClick = { showAddOutputDialog = true }) {
                    Icon(painterResource(R.drawable.plus), contentDescription = "Add output")
                }
            }

            outputs.forEach { output ->
                OutputRow(
                    output = output,
                    onRemove = { OutputManager.removeOutput(output) },
                    onRequestPermissions = onRequestPermissions,
                    scope = scope
                )
            }

            if (outputs.isEmpty()) {
                Text(
                    text = "Add your device to get started.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }
        }
    }

    if (showAddOutputDialog) {
        AddOutputDialog(
            outputs = outputs,
            onAddOutput = { type -> OutputManager.addOutput(type) },
            onDismissRequest = { showAddOutputDialog = false }
        )
    }
}

@Composable
fun AddOutputDialog(
    outputs: List<BaseOutput>,
    onAddOutput: (OutputType) -> Unit,
    onDismissRequest: () -> Unit
) {
    // Dynamic filtering logic
    val hasAudioOutput = outputs.any { it is AudioOutput }
    val maxOutputs = OutputManager.MAXIMUM_OUTPUTS
    val maxOutputsReached = outputs.size >= maxOutputs

    val availableTypes = OutputType.entries.filter { type ->
        if (maxOutputsReached) return@filter false // Hide all options if limit is reached
        when (type) {
            OutputType.RECORDER -> false // Never show recorder
            OutputType.AUDIO_CONTINUOUS, OutputType.AUDIO_WAVELET, OutputType.AUDIO_MULTIPULSE -> !hasAudioOutput // Only show if no audio output exists
            else -> true // Coyote 2, Coyote 3 always available
        }
    }

    var selectedType by remember { mutableStateOf<OutputType?>(null) }
    val effectiveSelectedType = selectedType?.takeIf { availableTypes.contains(it) } ?: availableTypes.firstOrNull()

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.Start // Default to left alignment
            ) {
                Text(
                    text = "Add output",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                )

                if (availableTypes.isNotEmpty() && effectiveSelectedType != null) {
                    Text(
                        text = "Choose the device you'd like to add.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    OptionPicker(
                        currentValue = effectiveSelectedType,
                        onValueChange = { selectedType = it },
                        options = availableTypes,
                        getText = { it.displayName },
                        size = OptionPickerSize.Large,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = effectiveSelectedType.description,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    effectiveSelectedType.warning?.let { warningText ->
                        Text(
                            text = warningText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismissRequest) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onAddOutput(effectiveSelectedType)
                                onDismissRequest()
                            }
                        ) {
                            Text("Add")
                        }
                    }
                } else {
                    Text(
                        text = "Thought you'd keep adding output devices to see what would happen eh?\n\nWell you found out. It's this.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    // Wrapped in a Row to maintain right-alignment for the button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(onClick = onDismissRequest) {
                            Text("Close")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OutputRow(
    output: BaseOutput,
    onRemove: () -> Unit,
    onRequestPermissions: (Array<String>, (Boolean) -> Unit) -> Unit,
    scope: CoroutineScope,
    modifier: Modifier = Modifier
) {
    var showSettings by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }

    val status = if (output is ConnectableOutput) {
        val s by output.connectionStatus.collectAsStateWithLifecycle()
        s
    } else null

    val battery = if (output is BluetoothOutput) {
        val b by output.batteryLevel.collectAsStateWithLifecycle()
        b
    } else null

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = output.type.displayName,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )

        if (output is ConnectableOutput && status != null) {
            val (iconRes, tint, contentDescription) = when (status) {
                ConnectionStatus.Disconnected -> Triple(
                    if (output is BluetoothOutput) R.drawable.bluetooth else R.drawable.wifi,
                    MaterialTheme.colorScheme.error, // Red
                    "Disconnected. Tap to connect."
                )
                ConnectionStatus.Scanning -> Triple(
                    if (output is BluetoothOutput) R.drawable.bluetooth_searching else R.drawable.wifi,
                    Color(0xFFFFC107), // Yellow
                    "Scanning..."
                )
                ConnectionStatus.Connecting -> Triple(
                    if (output is BluetoothOutput) R.drawable.bluetooth_connected else R.drawable.wifi,
                    Color(0xFFFFC107), // Yellow
                    "Connecting..."
                )
                ConnectionStatus.Connected -> Triple(
                    if (output is BluetoothOutput) R.drawable.bluetooth_connected else R.drawable.wifi,
                    Color(0xFF4CAF50), // Green
                    "Connected. Tap to disconnect."
                )
            }

            if (status == ConnectionStatus.Connected && output is BluetoothOutput) {
                Icon(
                    painter = painterResource(R.drawable.battery),
                    contentDescription = "Battery level",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "${battery ?: 0}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = {
                    when (status) {
                        ConnectionStatus.Disconnected -> {
                            if (output is BluetoothOutput) {
                                onRequestPermissions(BluetoothHandler.ALL_BLE_PERMISSIONS) { granted ->
                                    if (granted) {
                                        scope.launch {
                                            output.connect()
                                        }
                                    }
                                }
                            } else if (output is ConnectableOutput) {
                                scope.launch {
                                    output.connect()
                                }
                            }
                        }
                        ConnectionStatus.Connected -> {
                            output.disconnect()
                        }
                        else -> { /* Do nothing for Scanning/Connecting */ }
                    }
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = contentDescription,
                    tint = tint
                )
            }
        }

        IconButton(
            onClick = { showSettings = true },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(painterResource(R.drawable.settings), contentDescription = "Output settings")
        }

        IconButton(
            onClick = { showRemoveDialog = true },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(painterResource(R.drawable.bin), contentDescription = "Remove output")
        }
    }

    if (showSettings) {
        OutputSettingsPopup(
            output = output,
            onDismissRequest = { showSettings = false }
        )
    }

    if (showRemoveDialog) {
        ConfirmationDialog(
            message = "Remove the \"${output.type.displayName}\" output and its associated settings?",
            onConfirm = {
                showRemoveDialog = false
                onRemove()
            },
            onDismiss = { showRemoveDialog = false }
        )
    }
}

private enum class OutputSettingsTab(val title: String) {
    CALIBRATION("Calibration"),
    TWEAKS("Tweaks"),
    OUTPUT_SETTINGS("Device")
}

@Composable
fun OutputSettingsPopup(
    output: BaseOutput,
    onDismissRequest: () -> Unit
) {
    val selectedSubset by output.selectedFrequencySubset.collectAsStateWithLifecycle()
    val calibration by output.calibration.collectAsStateWithLifecycle()
    val tweaks by output.tweaks.collectAsStateWithLifecycle()

    val hasSettingsUI = output.settingsUI != null
    val availableTabs = remember(hasSettingsUI) {
        buildList {
            add(OutputSettingsTab.CALIBRATION)
            add(OutputSettingsTab.TWEAKS)
            if (hasSettingsUI) {
                add(OutputSettingsTab.OUTPUT_SETTINGS)
            }
        }
    }

    var selectedTab by remember { mutableStateOf(availableTabs.first()) }

    // Ensure the selected tab is valid if the available tabs change dynamically
    LaunchedEffect(availableTabs) {
        if (selectedTab !in availableTabs) {
            selectedTab = availableTabs.first()
        }
    }

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = output.type.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 8.dp)
                )

                SecondaryScrollableTabRow(
                    selectedTabIndex = availableTabs.indexOf(selectedTab),
                    edgePadding = 0.dp,
                ) {
                    availableTabs.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            text = { Text(tab.title) }
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (selectedTab) {
                        OutputSettingsTab.CALIBRATION -> {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(text = "Frequency range", style = MaterialTheme.typography.titleMedium)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${output.minFrequency}Hz",
                                    modifier = Modifier.widthIn(40.dp),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                /*RangeSlider(
                                    modifier = Modifier.weight(1f),
                                    value = selectedSubset,
                                    steps = 0,
                                    onValueChange = { newRange ->
                                        // Enforce a minimum separation so handles don't overlap
                                        if (newRange.endInclusive - newRange.start >= 0.05f) {
                                            output.setSelectedFrequencySubset(newRange)
                                        }
                                    },
                                    valueRange = 0.0f..1.0f,
                                    onValueChangeFinished = { OutputManager.saveState() },
                                )*/
                                ConstrainedRangeSlider(
                                    modifier = Modifier.weight(1f),
                                    value = selectedSubset,
                                    valueRange = 0.0f..1.0f,
                                    minimumGap = 0.05f,
                                    onValueChange = { newRange ->
                                        output.setSelectedFrequencySubset(newRange)
                                    },
                                    onValueChangeFinished = {
                                        OutputManager.saveState()
                                    }
                                )
                                Text(
                                    text = "${output.maxFrequency}Hz",
                                    modifier = Modifier.widthIn(40.dp),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    output.setSelectedFrequencySubset(output.defaultFrequencySubset)
                                    OutputManager.saveState()
                                }
                            ) {
                                Text("Reset frequency range")
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(text = "Calibration", style = MaterialTheme.typography.titleMedium)
                            }
                            SliderWithLabel(
                                label = "Power balance",
                                value = calibration.amplitudeBalance,
                                onValueChange = { output.updateCalibration(calibration.copy(amplitudeBalance = it)) },
                                onValueChangeFinished = { OutputManager.saveState() },
                                valueRange = 0.0f..1.0f,
                                steps = 99,
                                valueDisplay = { String.format(Locale.US, "%03.2f", it) }
                            )
                            SliderWithLabel(
                                label = "Frequency balance A",
                                value = calibration.frequencyBalanceA,
                                onValueChange = { output.updateCalibration(calibration.copy(frequencyBalanceA = it)) },
                                onValueChangeFinished = { OutputManager.saveState() },
                                valueRange = 0.0f..1.0f,
                                steps = 99,
                                valueDisplay = { String.format(Locale.US, "%03.2f", it) }
                            )
                            SliderWithLabel(
                                label = "Frequency balance B",
                                value = calibration.frequencyBalanceB,
                                onValueChange = { output.updateCalibration(calibration.copy(frequencyBalanceB = it)) },
                                onValueChangeFinished = { OutputManager.saveState() },
                                valueRange = 0.0f..1.0f,
                                steps = 99,
                                valueDisplay = { String.format(Locale.US, "%03.2f", it) }
                            )
                            SliderWithLabel(
                                label = "Amplitude scaling",
                                value = calibration.amplitudeScaling,
                                onValueChange = { output.updateCalibration(calibration.copy(amplitudeScaling = it)) },
                                onValueChangeFinished = { OutputManager.saveState() },
                                valueRange = 0.0f..1.0f,
                                steps = 99,
                                valueDisplay = { String.format(Locale.US, "%03.2f", it) }
                            )
                            val calibrationPositionalEffectCurve = Prefs.calibrationPositionalEffectCurve.collectAsStateWithLifecycle()
                            SliderWithLabel(
                                label = "Positional effect curve (all outputs)",
                                value = calibrationPositionalEffectCurve.value,
                                onValueChange = { Prefs.calibrationPositionalEffectCurve.value = it },
                                onValueChangeFinished = { Prefs.calibrationPositionalEffectCurve.save() },
                                valueRange = 0.1f..1.0f,
                                steps = 89,
                                valueDisplay = { String.format(Locale.US, "%03.2f", it) }
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    output.updateCalibration(Calibration())
                                    OutputManager.saveState()
                                    Prefs.resetByPrefix("calibration_")
                                }
                            ) {
                                Text("Reset calibration")
                            }
                        }
                        OutputSettingsTab.TWEAKS -> {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Advanced settings for tinkerers.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(text = "Amplitude adjustments", style = MaterialTheme.typography.titleMedium)
                            }
                            SliderWithLabel(
                                label = "Amplitude feel A",
                                value = tweaks.amplitudeFeelA,
                                onValueChange = { output.updateTweaks(tweaks.copy(amplitudeFeelA = it)) },
                                onValueChangeFinished = { OutputManager.saveState() },
                                valueRange = 0.5f..2.0f,
                                steps = 149,
                                valueDisplay = { String.format(Locale.US, "%03.2f", it) }
                            )
                            SliderWithLabel(
                                label = "Amplitude feel B",
                                value = tweaks.amplitudeFeelB,
                                onValueChange = { output.updateTweaks(tweaks.copy(amplitudeFeelB = it)) },
                                onValueChangeFinished = { OutputManager.saveState() },
                                valueRange = 0.5f..2.0f,
                                steps = 149,
                                valueDisplay = { String.format(Locale.US, "%03.2f", it) }
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(text = "Frequency adjustments", style = MaterialTheme.typography.titleMedium)
                            }
                            SliderWithLabel(
                                label = "Frequency feel A",
                                value = tweaks.frequencyFeelA,
                                onValueChange = { output.updateTweaks(tweaks.copy(frequencyFeelA = it)) },
                                onValueChangeFinished = { OutputManager.saveState() },
                                valueRange = 0.5f..2.0f,
                                steps = 149,
                                valueDisplay = { String.format(Locale.US, "%03.2f", it) }
                            )
                            SliderWithLabel(
                                label = "Frequency feel B",
                                value = tweaks.frequencyFeelB,
                                onValueChange = { output.updateTweaks(tweaks.copy(frequencyFeelB = it)) },
                                onValueChangeFinished = { OutputManager.saveState() },
                                valueRange = 0.5f..2.0f,
                                steps = 149,
                                valueDisplay = { String.format(Locale.US, "%03.2f", it) }
                            )
                            SliderWithLabel(
                                label = "Flat frequency adjust A",
                                value = tweaks.frequencyAdjustA,
                                onValueChange = { output.updateTweaks(tweaks.copy(frequencyAdjustA = it)) },
                                onValueChangeFinished = { OutputManager.saveState() },
                                valueRange = -1.0f..1.0f,
                                steps = 199,
                                valueDisplay = { String.format(Locale.US, "%03.2f", it) }
                            )
                            SliderWithLabel(
                                label = "Flat frequency adjust B",
                                value = tweaks.frequencyAdjustB,
                                onValueChange = { output.updateTweaks(tweaks.copy(frequencyAdjustB = it)) },
                                onValueChangeFinished = { OutputManager.saveState() },
                                valueRange = -1.0f..1.0f,
                                steps = 199,
                                valueDisplay = { String.format(Locale.US, "%03.2f", it) }
                            )
                            SwitchWithLabel(
                                label = "Invert frequencies A",
                                checked = tweaks.frequencyInvertA,
                                onCheckedChange = {
                                    output.updateTweaks(tweaks.copy(frequencyInvertA = it))
                                    OutputManager.saveState()
                                }
                            )
                            SwitchWithLabel(
                                label = "Invert frequencies B",
                                checked = tweaks.frequencyInvertB,
                                onCheckedChange = {
                                    output.updateTweaks(tweaks.copy(frequencyInvertB = it))
                                    OutputManager.saveState()
                                }
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    output.updateTweaks(Tweaks())
                                    OutputManager.saveState()
                                }
                            ) {
                                Text("Reset tweaks")
                            }
                        }
                        // Device specific settings UI
                        OutputSettingsTab.OUTPUT_SETTINGS -> {
                            val settingsUI = output.settingsUI
                            if (settingsUI != null) {
                                settingsUI()
                            }
                        }
                    }
                }
            }
        }
    }
}