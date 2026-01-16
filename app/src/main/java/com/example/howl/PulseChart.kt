package com.example.howl

import android.util.Log
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

object PulseHistory {
    // Various state for our pulse history (used by charts) and last pulse (used by meters)
    const val PULSE_HISTORY_SIZE = 200
    private val _pulseHistoryBuffer: CircularBuffer<Pulse> = CircularBuffer(PULSE_HISTORY_SIZE)
    private val _pulseHistory = MutableStateFlow<List<Pulse>>(emptyList())
    val pulseHistory: StateFlow<List<Pulse>> = _pulseHistory.asStateFlow()
    private val _lastPulse = MutableStateFlow(Pulse())
    val lastPulse: StateFlow<Pulse> = _lastPulse.asStateFlow()

    // Hack to make the pulse meters show nothing when the player is stopped
    val lastPulseWithPlayerState: Flow<Pulse> = combine(
        _lastPulse,
        Player.playerState
    ) { pulse, playerState ->
        if (playerState.isPlaying) pulse else Pulse()
    }

    // All used by some slightly convoluted logic that smooths updates to our pulse history and last
    // pulse. We generate pulses for the recorder (which is also the basis for the charts) in
    // batches of 4 every 0.1 seconds. If we added them all at once the charts would update at 10Hz
    // which is not very smooth. So we instead put them in a temporary buffer and emit them at
    // evenly spaced times so that we can have 40Hz UI updates.
    private val _pendingPulses = ArrayDeque<Pulse>()
    private val _pendingPulsesMutex = Mutex()
    private val _pulseAddedSignal = Channel<Unit>(capacity = Channel.CONFLATED)
    private val _emitterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var _emitterJob: Job? = null

    private fun startPulseEmitter() {
        if (_emitterJob?.isActive == true) return

        Log.d("PulseHistory", "Starting pulse emitter")

        _emitterJob = _emitterScope.launch {
            while (isActive) {
                // take one pulse from queue (if any)
                val pulse: Pulse? = _pendingPulsesMutex.withLock {
                    if (_pendingPulses.isNotEmpty()) _pendingPulses.removeFirst() else null
                }

                if (pulse == null) {
                    // We've exhausted the pulse queue, so wait for a signal from addPulsesToHistory
                    // before we resume.
                    _pulseAddedSignal.receive()
                    continue
                }

                // emit a single pulse
                emitSinglePulse(pulse)

                // keep an even spacing between emitted pulses
                delay(HWL_PULSE_TIME.seconds)
            }
        }
    }

    private fun stopPulseEmitter() {
        _emitterJob?.cancel()
    }

    private fun emitSinglePulse(p: Pulse) {
        // Update our pulse history and lastPulse with the latest pulse.
        _pulseHistoryBuffer.add(p, overwrite = true)
        _pulseHistory.update { _pulseHistoryBuffer.toList() }
        _lastPulse.update { _pulseHistoryBuffer.last()!! }
    }

    fun addPulsesToHistory(newPulses: List<Pulse>) {
        // Ensure emitter is running
        startPulseEmitter()

        _emitterScope.launch {
            _pendingPulsesMutex.withLock {
                // Check if there are any pulses left from the previous batch. If there are, emit
                // them all now instead of waiting for the pulse emitter. This ensures that we never
                // fall behind.
                if (_pendingPulses.isNotEmpty()) {
                    val toFlushNow = mutableListOf<Pulse>()
                    toFlushNow.addAll(_pendingPulses)
                    //Log.d("DataRepository", "Flush remaining pulses from last batch $toFlushNow")
                    _pendingPulses.clear()
                    toFlushNow.forEach { emitSinglePulse(it) }
                }

                // Now add the new pulses to the queue to be emitted at smooth intervals.
                newPulses.forEach { _pendingPulses.addLast(it) }
            }

            // Wake the emitter if needed - it will sleep on this signal when it exhausts the queue
            _pulseAddedSignal.trySend(Unit)
        }
    }
}

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
    val pulses by PulseHistory.pulseHistory.collectAsStateWithLifecycle(initialValue = emptyList())
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
            val x = index.toFloat() / (PulseHistory.PULSE_HISTORY_SIZE - 1) * canvasWidth

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