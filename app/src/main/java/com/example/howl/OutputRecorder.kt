package com.example.howl

class RecorderOutput : BaseOutput() {
    override val pulseBatchSize = 4
    override val sendSilenceWhenMuted = false
    override var allowedFrequencyRange = 1..100
    override var defaultFrequencyRange = 1..100
    override var ready = true
    private val recordBuffer: CircularBuffer<Pulse> = CircularBuffer(4800)

    override fun sendPulses(
        channelAPower: Int,
        channelBPower: Int,
        minFrequency: Double,
        maxFrequency: Double,
        pulses: List<Pulse>
    ) {
        // We use the recorder's pulses to feed our history chart and meters
        PulseHistory.addPulsesToHistory(pulses)
        val recordState = Player.recordState.value
        if (recordState.recordMode && !recordState.recording)
            return
        recordBuffer.addAll(pulses, overwrite = true)
        updateDuration()
    }

    fun updateDuration() {
        val duration = recordBuffer.size / HWL_PULSES_PER_SEC.toFloat()
        Player.setRecordState(Player.recordState.value.copy(duration = duration))
    }

    fun clear() {
        recordBuffer.clear()
        updateDuration()
    }

    fun resize(duration: Int, clear: Boolean = false) {
        recordBuffer.resize(duration * HWL_PULSES_PER_SEC, clear)
        updateDuration()
    }

    fun pulseCount(): Int {
        return recordBuffer.size
    }

    fun getPulses(): List<Pulse> {
        return recordBuffer.toList()
    }
}
