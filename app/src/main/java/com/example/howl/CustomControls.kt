package com.example.howl

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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

@Composable
fun NiceSmootherControl(
    smoother: NiceSmoother,
    targetRange: ClosedFloatingPointRange<Float>,
    rateRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    targetLabel: String = "Target",
    rateLabel: String = "Rate",
    targetSteps: Int = 0,
    rateSteps: Int = 0,
    targetValueDisplay: (Double) -> String = { "%.2f".format(it) },
    rateValueDisplay: (Double) -> String = { "%.2f".format(it) },
    enabled: Boolean = true
) {
    val target by smoother.targetFlow.collectAsState()
    val rate by smoother.rateFlow.collectAsState()

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = targetLabel,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = targetValueDisplay(target),
                    modifier = Modifier.widthIn(min = 40.dp)
                )
                Slider(
                    value = target.toFloat(),
                    onValueChange = { newTarget ->
                        smoother.setTarget(newTarget.toDouble())
                    },
                    valueRange = targetRange,
                    steps = targetSteps,
                    modifier = Modifier.weight(1f),
                    enabled = enabled
                )
            }
        }

        Column {
            Text(
                text = rateLabel,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = rateValueDisplay(rate),
                    modifier = Modifier.widthIn(min = 40.dp)
                )
                Slider(
                    value = rate.toFloat(),
                    onValueChange = { newRate ->
                        smoother.rate = newRate.toDouble()
                    },
                    valueRange = rateRange,
                    steps = rateSteps,
                    modifier = Modifier.width(80.dp),
                    enabled = enabled
                )
            }
        }
    }
}

enum class OptionPickerSize {
    Standard,
    Large
}

@Composable
fun <T> OptionPicker(
    currentValue: T,
    onValueChange: (T) -> Unit,
    options: List<T>,
    getText: (T) -> String,
    modifier: Modifier = Modifier,
    getIcon: (T) -> Int? = { null },
    textColor: (T) -> Color = { Color.Unspecified },
    size: OptionPickerSize = OptionPickerSize.Standard,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    val sortedOptions = remember(options, getText) {
        options.sortedBy { getText(it) }
    }

    val textStyle = when (size) {
        OptionPickerSize.Standard -> MaterialTheme.typography.labelLarge
        OptionPickerSize.Large -> MaterialTheme.typography.titleMedium // Bigger text
    }

    val iconSize = when (size) {
        OptionPickerSize.Standard -> ButtonDefaults.IconSize
        OptionPickerSize.Large -> 24.dp // Bigger icon
    }

    val contentPadding = when (size) {
        OptionPickerSize.Standard -> ButtonDefaults.ContentPadding
        OptionPickerSize.Large -> PaddingValues(horizontal = 24.dp, vertical = 12.dp) // More padding for larger touch target
    }

    Box(modifier = modifier.wrapContentSize(Alignment.TopStart)) {
        OutlinedButton(
            onClick = { expanded = true },
            contentPadding = contentPadding,
            enabled = enabled
        ) {
            getIcon(currentValue)?.let { iconRes ->
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(iconSize)
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            }

            Text(
                text = getText(currentValue),
                color = if (enabled) textColor(currentValue) else Color.Unspecified,
                style = textStyle
            )
        }

        // DropdownMenu remains standard size
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            sortedOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(getText(option), color = textColor(option)) },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    },
                    leadingIcon = getIcon(option)?.let { res ->
                        {
                            Icon(
                                painter = painterResource(id = res),
                                contentDescription = null
                            )
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun PowerLevelMeters(
) {
    val lastPulse by PulseHistory.lastPulseWithPlayerState.collectAsStateWithLifecycle(initialValue = Pulse())

    Row {
        PowerLevelMeter(
            powerLevelProvider = { lastPulse.ampA },
            frequencyProvider = { lastPulse.freqA },
        )
        Spacer(modifier = Modifier.width(8.dp))
        PowerLevelMeter(
            powerLevelProvider = { lastPulse.ampB },
            frequencyProvider = { lastPulse.freqB },
        )
    }
}

@Composable
fun PowerLevelMeter(
    powerLevelProvider: () -> Float,
    frequencyProvider: () -> Float,
) {
    val powerBarStartColor = Color(0xFFFF0000)
    val powerBarEndColor = Color(0xFFFFFF00)
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
                val powerLevel = powerLevelProvider().coerceIn(0f, 1f)
                if (powerLevel  > 0f) {
                    val barHeight = size.height * powerLevel
                    val frequency = frequencyProvider()
                    val barColor = lerp(
                        powerBarStartColor,
                        powerBarEndColor,
                        frequency
                    )
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