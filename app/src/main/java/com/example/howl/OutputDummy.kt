package com.example.howl

class DummyOutput : Output {
    override val timerDelay = 0.1
    override val pulseBatchSize = 1
    override val sendSilenceWhenMuted = false
    override var allowedFrequencyRange = 1..200
    override var defaultFrequencyRange = 10..100
    override var ready = false
    override var latency = 0.0

    override fun initialise() {}
    override fun end() {}
    override fun handleBluetoothEvent(event: BluetoothEvent) {}
    override fun start() {}
    override fun stop() {}
    override fun sendPulses(
        channelAPower: Int,
        channelBPower: Int,
        minFrequency: Double,
        maxFrequency: Double,
        pulses: List<Pulse>
    ) {}
}
