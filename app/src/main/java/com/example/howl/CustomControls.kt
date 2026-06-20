package com.example.howl

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

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
    modifier: Modifier = Modifier,
    rateRange: ClosedFloatingPointRange<Float> = 0.1f..1.0f,
    targetLabel: String = "Target",
    rateLabel: String = "Rate",
    targetSteps: Int = 0,
    rateSteps: Int = 0,
    targetValueDisplay: (Double) -> String = { "%.2f".format(it) },
    rateValueDisplay: (Double) -> String = { "%.2f".format(it) },
    adjustableRate: Boolean = true,
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
                    modifier = Modifier.widthIn(min = 45.dp)
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

        if (adjustableRate) {
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
                        modifier = Modifier.widthIn(min = 45.dp)
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

@Composable
fun DualCircularTouchpadSurface(
    leftPadPosition: CartesianPosition,
    rightPadPosition: CartesianPosition,
    onLeftPadPositionChange: (CartesianPosition) -> Unit,
    onRightPadPositionChange: (CartesianPosition) -> Unit,
    returnRate: Float = 0f // Normalized units per second. 0f disables return animation.
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        CircularTouchpad(
            modifier = Modifier
                .weight(1f)
                .aspectRatio(1f)
                .sizeIn(maxWidth = 300.dp, maxHeight = 300.dp),
            position = leftPadPosition,
            onPositionChange = onLeftPadPositionChange,
            returnRate = returnRate
        )
        CircularTouchpad(
            modifier = Modifier
                .weight(1f)
                .aspectRatio(1f)
                .sizeIn(maxWidth = 300.dp, maxHeight = 300.dp),
            position = rightPadPosition,
            onPositionChange = onRightPadPositionChange,
            returnRate = returnRate
        )
    }
}

@Composable
fun CircularTouchpad(
    position: CartesianPosition,
    onPositionChange: (CartesianPosition) -> Unit,
    modifier: Modifier = Modifier,
    returnRate: Float = 0f
) {
    // Track whether the user's finger is currently pressing the touchpad
    val isTouched = remember { mutableStateOf(false) }

    // Extract MaterialTheme colors here while we are still in a @Composable context
    val touchpadBackgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val touchpadBorderColor = MaterialTheme.colorScheme.outline
    val indicatorColor = MaterialTheme.colorScheme.primary

    // Define constants for styling
    val borderWidth = 4.dp
    val indicatorRadius = 8.dp

    // Calculate the pixel inset needed to keep the indicator inside the border.
    // Inset = Border Width + Indicator Radius.
    val insetPx = with(LocalDensity.current) { (borderWidth + indicatorRadius).toPx() }

    DisposableEffect(Unit) {
        onDispose {
            onPositionChange(CartesianPosition(0f, 0f))
        }
    }

    // LaunchedEffect handles centering when the finger is lifted
    LaunchedEffect(isTouched.value, returnRate) {
        if (!isTouched.value && returnRate > 0f) {
            val startX = position.x
            val startY = position.y
            val distance = hypot(startX, startY)

            if (distance > 0f) {
                // Calculate total duration in milliseconds based on distance and return rate
                val durationMillis = (distance / returnRate * 1000f)
                var accumulatedTime = 0f

                // Wait for the first frame to establish a baseline time
                var lastFrameTime = withFrameMillis { it }

                // Smoothly interpolate position frame-by-frame
                while (isActive) {
                    val currentFrameTime = withFrameMillis { it }
                    val deltaTime = currentFrameTime - lastFrameTime
                    lastFrameTime = currentFrameTime
                    accumulatedTime += deltaTime

                    val progress = (accumulatedTime / durationMillis).coerceIn(0f, 1f)

                    // Move progressively closer to (0,0)
                    val currentX = startX * (1f - progress)
                    val currentY = startY * (1f - progress)

                    onPositionChange(CartesianPosition(currentX, currentY))

                    // Break the loop once we've fully returned to the center
                    if (progress >= 1f) {
                        break
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .systemGestureExclusion()
            .clip(CircleShape)
            .background(touchpadBackgroundColor)
            .border(
                width = borderWidth,
                color = touchpadBorderColor,
                shape = CircleShape
            )
            .pointerInput(insetPx) {
                awaitEachGesture {
                    val down = awaitFirstDown()

                    val center = Offset(size.width / 2f, size.height / 2f)

                    // The maximum radius for the touch input (full size)
                    val maxInputRadius = size.width / 2f

                    // The effective radius for clamping and drawing (reduced by inset)
                    // This ensures the dot stops before crossing the border
                    val innerRadius = (maxInputRadius - insetPx).coerceAtLeast(0f)

                    // Helper function to handle coordinates constrained to a circle
                    fun getCartesian(offset: Offset, isDragging: Boolean): CartesianPosition? {
                        val dx = offset.x - center.x
                        val dy = offset.y - center.y
                        val distance = hypot(dx, dy)

                        // Allow touch initiation anywhere in the box (including the border area),
                        // but ignore touches strictly outside the circle.
                        if (!isDragging && distance > maxInputRadius) return null

                        // Coerce the position to the inner radius so the dot stays visually inside the border
                        val clampedDistance = distance.coerceAtMost(innerRadius)
                        val scale = if (distance == 0f) 0f else clampedDistance / distance

                        // Normalize against the inner radius so output is -1..1 relative to the drawable area
                        val normalizedX = ((dx * scale) / innerRadius).coerceIn(-1f, 1f)
                        val normalizedY = ((dy * scale) / innerRadius).coerceIn(-1f, 1f)

                        return CartesianPosition(normalizedX, normalizedY)
                    }

                    // Process the initial touch (isDragging = false)
                    val initialCartesian = getCartesian(down.position, isDragging = false)

                    // Only consume and start tracking if the touch was actually inside the circle
                    if (initialCartesian != null) {
                        isTouched.value = true // Halt any ongoing return animation
                        onPositionChange(initialCartesian)
                        down.consume()

                        try {
                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }

                                if (change != null && change.pressed) {
                                    val dragCartesian = getCartesian(change.position, isDragging = true)
                                    if (dragCartesian != null) {
                                        onPositionChange(dragCartesian)
                                    }
                                    change.consume()
                                }
                            } while (event.changes.any { it.pressed })
                        } finally {
                            // When the finger is lifted (or gesture cancelled), trigger the return animation
                            isTouched.value = false
                        }
                    }
                }
            }
    ) {
        // Re-calculate pixel coordinates to draw the visual indicator
        Canvas(modifier = Modifier.matchParentSize()) {
            val centerOffset = Offset(size.width / 2f, size.height / 2f)

            // Use the same inner radius calculation used for input clamping
            val drawRadius = (size.width / 2f - insetPx).coerceAtLeast(0f)

            val indicatorX = centerOffset.x + (position.x * drawRadius)
            val indicatorY = centerOffset.y + (position.y * drawRadius)

            drawCircle(
                color = indicatorColor,
                radius = indicatorRadius.toPx(),
                center = Offset(indicatorX, indicatorY)
            )
        }
    }
}