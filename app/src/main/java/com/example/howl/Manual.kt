package com.example.howl

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

object Manual : PulseSource {
    private val _displayName = MutableStateFlow("Manual control")
    override val displayName = _displayName.asStateFlow()
    private val _displayInfo = MutableStateFlow("")
    override val displayInfo = _displayInfo.asStateFlow()
    override var duration: Double? = null
    override val isFinite: Boolean = false
    override val shouldLoop: Boolean = false
    override var readyToPlay: Boolean = true
    override var isRemote: Boolean = false

    // Raw touchpad positions (targets)
    private val _leftPadPosition = MutableStateFlow(CartesianPosition(0f, 0f))
    private val _rightPadPosition = MutableStateFlow(CartesianPosition(0f, 0f))

    val leftPadPosition: StateFlow<CartesianPosition> = _leftPadPosition.asStateFlow()
    val rightPadPosition: StateFlow<CartesianPosition> = _rightPadPosition.asStateFlow()

    // Smoothed positions used for generation
    private var smoothedLeftPadPosition = CartesianPosition(0f, 0f)
    private var smoothedRightPadPosition = CartesianPosition(0f, 0f)

    // Velocity state required by SmoothDamp
    private var leftPadVelocity = CartesianPosition(0f, 0f)
    private var rightPadVelocity = CartesianPosition(0f, 0f)

    // Simulation timing state
    private var lastSimulationTime = -1.0

    override fun getPulseAtTime(time: Double): Pulse {
        // Initialize last time on first run or sequence reset
        if (lastSimulationTime !in 0.0..time) {
            lastSimulationTime = time
        }

        val deltaSimulationTime = (time - lastSimulationTime).toFloat()
        lastSimulationTime = time

        val smoothTime = Prefs.manualSmoothdampTime.value

        // Smooth Left Pad
        val leftTarget = _leftPadPosition.value
        val leftResult = smoothDamp(smoothedLeftPadPosition, leftTarget, leftPadVelocity, smoothTime, deltaSimulationTime)
        smoothedLeftPadPosition = leftResult.first
        leftPadVelocity = leftResult.second

        // Smooth Right Pad
        val rightTarget = _rightPadPosition.value
        val rightResult = smoothDamp(smoothedRightPadPosition, rightTarget, rightPadVelocity, smoothTime, deltaSimulationTime)
        smoothedRightPadPosition = rightResult.first
        rightPadVelocity = rightResult.second

        // Convert smoothed Cartesian coordinates back to Polar for audio generation
        val leftAngle = atan2(smoothedLeftPadPosition.y, smoothedLeftPadPosition.x)
        val rightAngle = atan2(smoothedRightPadPosition.y, smoothedRightPadPosition.x)

        val leftRadius = hypot(smoothedLeftPadPosition.x, smoothedLeftPadPosition.y)
        val rightRadius = hypot(smoothedRightPadPosition.x, smoothedRightPadPosition.y)

        val freqA = angleToFrequency(leftAngle)
        val freqB = angleToFrequency(rightAngle)

        return Pulse(
            freqA = freqA,
            freqB = freqB,
            ampA = leftRadius,
            ampB = rightRadius
        )
    }

    override fun updateState(currentTime: Double) {
    }

    fun updateLeftPadPosition(position: CartesianPosition) {
        _leftPadPosition.value = position
    }

    fun updateRightPadPosition(position: CartesianPosition) {
        _rightPadPosition.value = position
    }

    fun reset() {
        _leftPadPosition.value = CartesianPosition(0.0f, 0.0f)
        _rightPadPosition.value = CartesianPosition(0.0f, 0.0f)
        smoothedLeftPadPosition = CartesianPosition(0.0f,0.0f)
        smoothedRightPadPosition = CartesianPosition(0.0f,0.0f)
        leftPadVelocity = CartesianPosition(0.0f,0.0f)
        rightPadVelocity = CartesianPosition(0.0f,0.0f)
    }

    fun angleToFrequency(angleRadians: Float): Float {
        val pi = PI.toFloat()
        val twoPi = 2f * pi

        // Normalize angle to [0, 2π)
        val normalized = ((angleRadians % twoPi) + twoPi) % twoPi

        // Top is at angle -π/2, which normalizes to 3π/2
        val topAngle = 3f * pi / 2f

        // Absolute angular distance from top around the circle
        val distFromTop = abs(normalized - topAngle)

        // Take the shorter path (clockwise or counterclockwise)
        val shortestDist = min(distFromTop, twoPi - distFromTop)

        // Linearly map [0, π] → [1.0, 0.0]
        return 1f - shortestDist / pi
    }

    private fun smoothDamp(
        current: CartesianPosition,
        target: CartesianPosition,
        currentVelocity: CartesianPosition,
        smoothTime: Float,
        deltaTime: Float
    ): Pair<CartesianPosition, CartesianPosition> {
        val safeSmoothTime = maxOf(0.0001f, smoothTime)
        val omega = 2f / safeSmoothTime

        val x = omega * deltaTime
        val exp = 1f / (1f + x + 0.48f * x * x + 0.235f * x * x * x)

        val changeX = current.x - target.x
        val changeY = current.y - target.y

        val tempX = (currentVelocity.x + omega * changeX) * deltaTime
        val tempY = (currentVelocity.y + omega * changeY) * deltaTime

        var newVelX = (currentVelocity.x - omega * tempX) * exp
        var newVelY = (currentVelocity.y - omega * tempY) * exp

        var outputX = target.x + (changeX + tempX) * exp
        var outputY = target.y + (changeY + tempY) * exp

        // Prevent overshooting
        val origMinusCurrentX = target.x - current.x
        val origMinusCurrentY = target.y - current.y
        val outMinusOrigX = outputX - target.x
        val outMinusOrigY = outputY - target.y

        if (origMinusCurrentX * outMinusOrigX + origMinusCurrentY * outMinusOrigY > 0f) {
            outputX = target.x
            outputY = target.y

            newVelX = 0f
            newVelY = 0f
        }

        return Pair(
            CartesianPosition(outputX, outputY),
            CartesianPosition(newVelX, newVelY)
        )
    }
}

class ManualViewModel : ViewModel() {
    val leftPadPosition = Manual.leftPadPosition
    val rightPadPosition = Manual.rightPadPosition

    fun updateLeftPadPosition(position: CartesianPosition) {
        Manual.updateLeftPadPosition(position)
    }

    fun updateRightPadPosition(position: CartesianPosition) {
        Manual.updateRightPadPosition(position)
    }

    fun stop() {
        Player.stopPlayer()
    }
    fun start() {
        Manual.reset()
        Player.switchPulseSource(Manual)
        Player.startPlayer()
    }
}

@Composable
fun ManualSettingsPanel(
    viewModel: ManualViewModel,
    modifier: Modifier = Modifier
) {
    val smoothTime by Prefs.manualSmoothdampTime.collectAsStateWithLifecycle()
    val centerRate by Prefs.manualTouchpadCenterRate.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        onDispose {
            viewModel.updateLeftPadPosition(CartesianPosition(0f, 0f))
            viewModel.updateRightPadPosition(CartesianPosition(0f, 0f))
        }
    }


    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = "Manual settings", style = MaterialTheme.typography.headlineSmall)
        }
        SliderWithLabel(
            label = "Touchpad center rate",
            value = centerRate,
            onValueChange = { Prefs.manualTouchpadCenterRate.value = it },
            onValueChangeFinished = { Prefs.manualTouchpadCenterRate.save() },
            valueRange = 0.0f..4.0f,
            steps = 79,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) }
        )

        SliderWithLabel(
            label = "Smoothing",
            value = smoothTime,
            onValueChange = { Prefs.manualSmoothdampTime.value = it },
            onValueChangeFinished = { Prefs.manualSmoothdampTime.save() },
            valueRange = 0.1f..2.0f,
            steps = 189,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) }
        )
    }
}

@Composable
fun ManualPanel(
    viewModel: ManualViewModel,
    modifier: Modifier = Modifier
) {
    val leftPosition by viewModel.leftPadPosition.collectAsStateWithLifecycle()
    val rightPosition by viewModel.rightPadPosition.collectAsStateWithLifecycle()
    val centerRate by Prefs.manualTouchpadCenterRate.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    val playerState by Player.playerState.collectAsStateWithLifecycle()
    val isPlaying = playerState.isPlaying && playerState.activePulseSource == Manual

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(
                space = 8.dp,
                alignment = Alignment.CenterHorizontally
            )
        ) {
            Button(
                onClick = {
                    if (isPlaying)
                        viewModel.stop()
                    else
                        viewModel.start()
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
                onClick = { showSettings = true },
            ) {
                Icon(
                    painter = painterResource(R.drawable.settings),
                    contentDescription = "Manual settings"
                )
            }
        }
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (isPlaying) {
                DualCircularTouchpadSurface(
                    leftPadPosition = leftPosition,
                    rightPadPosition = rightPosition,
                    onLeftPadPositionChange = viewModel::updateLeftPadPosition,
                    onRightPadPositionChange = viewModel::updateRightPadPosition,
                    returnRate = centerRate
                )
            } else {
                Text(
                    text = "Press play to begin manual control.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    if (showSettings) {
        Dialog(
            onDismissRequest = { showSettings = false }
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
            ) {
                ManualSettingsPanel(
                    viewModel = viewModel,
                )
            }
        }
    }
}