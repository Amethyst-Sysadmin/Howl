package com.example.howl

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.MaterialTheme

enum class PulseChartMode(val description: String) {
    Off("Off"),
    Combined("Combined"),
    Separate("Separate");

    fun next(): PulseChartMode {
        val entries = entries
        return entries[(ordinal + 1) % entries.size]
    }
}

enum class PulsePlotMode {
    Combined,
    AmplitudeOnly,
    FrequencyOnly
}

@Composable
fun PulsePlotter(
    modifier: Modifier = Modifier,
    mode: PulsePlotMode = PulsePlotMode.Combined
) {
    val pulses by DataRepository.pulseHistory.collectAsStateWithLifecycle()
    val backgroundColor = MaterialTheme.colorScheme.background

    Canvas(modifier = modifier.background(backgroundColor)) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // Define start and end colors for each channel
        val startColorA = Color(0xFFFF0000)
        val endColorA = Color(0xFFFFFF00)
        val startColorB = Color(0xFF0033FF)
        val endColorB = Color(0xFF00FF00)

        for ((index, pulse) in pulses.withIndex()) {
            // Calculate X position (oldest left, newest right)
            val x = index.toFloat() / (DataRepository.PULSE_HISTORY_SIZE - 1) * canvasWidth

            // Channel A
            drawPoint(
                mode = mode,
                amp = pulse.ampA,
                freq = pulse.freqA,
                x = x,
                canvasHeight = canvasHeight,
                startColor = startColorA,
                endColor = endColorA
            )

            // Channel B
            drawPoint(
                mode = mode,
                amp = pulse.ampB,
                freq = pulse.freqB,
                x = x,
                canvasHeight = canvasHeight,
                startColor = startColorB,
                endColor = endColorB
            )
        }
    }
}

private fun DrawScope.drawPoint(
    mode: PulsePlotMode,
    amp: Float,
    freq: Float,
    x: Float,
    canvasHeight: Float,
    startColor: Color,
    endColor: Color
) {
    val y = when (mode) {
        PulsePlotMode.Combined, PulsePlotMode.AmplitudeOnly -> (1f - amp) * canvasHeight
        PulsePlotMode.FrequencyOnly -> (1f - freq) * canvasHeight
    }
    val color = when (mode) {
        PulsePlotMode.Combined -> lerp(startColor, endColor, freq)
        PulsePlotMode.AmplitudeOnly -> startColor
        PulsePlotMode.FrequencyOnly -> startColor
    }

    drawCircle(
        color = color,
        radius = 4f,
        center = Offset(x, y)
    )
}