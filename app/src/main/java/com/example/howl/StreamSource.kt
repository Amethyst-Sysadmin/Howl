package com.example.howl

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object StreamSource : PulseSource {
    private val _displayName = MutableStateFlow("Streaming")
    override val displayName = _displayName.asStateFlow()
    private val _displayInfo = MutableStateFlow("")
    override val displayInfo = _displayInfo.asStateFlow()
    override var duration: Double? = null
    override val isFinite: Boolean = false
    override var shouldLoop: Boolean = false
    override var readyToPlay: Boolean = true
    override var isRemote: Boolean = false

    const val DEFAULT_PULSE_BUFFER_SIZE = 5

    private val _pulseBuffer: CircularBuffer<Pulse> = CircularBuffer(DEFAULT_PULSE_BUFFER_SIZE)
    private var _lastPulse = Pulse()

    override fun updateState(currentTime: Double) {
        // _pulseBuffer.clear()
    }

    override fun getPulseAtTime(time: Double): Pulse {
        val pulse = _pulseBuffer.removeFirstOrNull() ?: _lastPulse
        _lastPulse = pulse
        return pulse
    }

    fun newStream(
        bufferSize: Int = DEFAULT_PULSE_BUFFER_SIZE,
        title: String = "Streaming",
    ) {
        _displayName.value = title
        _pulseBuffer.resize(bufferSize, true)
    }

    fun addPulse(pulse: Pulse) {
        _pulseBuffer.add(pulse, overwrite = true)
    }
}