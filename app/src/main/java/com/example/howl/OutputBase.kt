package com.example.howl

const val OUTPUT_TIMER = 0.1

enum class OutputType(val displayName: String) {
    COYOTE3("Coyote 3"),
    COYOTE2("Coyote 2"),
    AUDIO_CONTINUOUS("Audio (continuous)"),
    AUDIO_WAVELET("Audio (wavelet)"),
}

interface Output {
    val pulseBatchSize: Int
    val sendSilenceWhenMuted: Boolean
    var allowedFrequencyRange: IntRange
    var defaultFrequencyRange: IntRange
    var ready: Boolean

    val pulseTime: Double
        get() = OUTPUT_TIMER / pulseBatchSize
    fun initialise()
    fun end()
    fun start()
    fun stop()
    fun sendPulses(channelAPower: Int,
                   channelBPower: Int,
                   minFrequency: Double,
                   maxFrequency: Double,
                   pulses: List<Pulse>
    )

    fun handleBluetoothEvent(event: BluetoothEvent)
    fun getNextTimes(time: Double, playbackSpeed: Double, offset: Double): List<Double>
}

abstract class BaseOutput : Output {
    override fun getNextTimes(
        time: Double,
        playbackSpeed: Double,
        offset: Double
    ): List<Double> {
        // Convert real-world offsets to media time
        val adjustedPulseTime = pulseTime * playbackSpeed
        val adjustedOffset = offset * playbackSpeed

        return List(pulseBatchSize) { index ->
            (time + adjustedPulseTime * index.toDouble() + adjustedOffset).coerceAtLeast(0.0)
        }
    }
    override fun initialise() {}
    override fun end() {}
    override fun handleBluetoothEvent(event: BluetoothEvent) {}
    override fun start() {}
    override fun stop() {}
}

class DummyOutput : BaseOutput() {
    override val pulseBatchSize = 1
    override val sendSilenceWhenMuted = false
    override var allowedFrequencyRange = 1..200
    override var defaultFrequencyRange = 10..100
    override var ready = false

    override fun sendPulses(
        channelAPower: Int,
        channelBPower: Int,
        minFrequency: Double,
        maxFrequency: Double,
        pulses: List<Pulse>
    ) {}
}