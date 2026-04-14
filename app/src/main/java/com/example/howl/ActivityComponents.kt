package com.example.howl

interface ActivityComponent {
    fun update(deltaTime: Double) {}
    fun onRegister() {}
    fun onUnregister() {}
}

class ActivityManager {
    private val components = mutableListOf<ActivityComponent>()

    fun register(component: ActivityComponent) {
        components += component
        component.onRegister()
    }

    fun unregister(component: ActivityComponent) {
        components -= component
        component.onUnregister()
    }

    fun update(deltaTime: Double) {
        components.forEach { it.update(deltaTime) }
    }
}

class Timer (
    private val durationProvider: () -> Double,
    var repeating: Boolean = false,
    private val onTrigger: () -> Unit = {}
) : ActivityComponent {
    constructor(
        // helper for simple fixed duration timers
        duration: Double,
        repeating: Boolean = false,
        onTrigger: () -> Unit = {}
    ) : this({ duration }, repeating, onTrigger)

    enum class TimerState {
        INITIAL,
        RUNNING,
        FINISHED,
        PAUSED,
        CANCELLED
    }

    var duration: Double = 0.0
        private set

    var remainingTime: Double = 0.0
        private set

    var state = TimerState.INITIAL

    val elapsedTime: Double
        get() = (duration - remainingTime).coerceIn(0.0, duration)

    val progress: Double
        get() = if (duration <= 0.0) 0.0 else (elapsedTime / duration).coerceIn(0.0, 1.0)

    val isRunning: Boolean
        get() = state == TimerState.RUNNING

    val isFinished: Boolean
        get() = state == TimerState.FINISHED

    val isPaused: Boolean
        get() = state == TimerState.PAUSED

    val isCancelled: Boolean
        get() = state == TimerState.CANCELLED

    private fun nextDuration(): Double {
        val value = durationProvider()
        require(value > 0.0) { "Timer duration must be > 0.0" }
        return value
    }

    override fun update(deltaTime: Double) {
        if (state != TimerState.RUNNING) return

        remainingTime -= deltaTime

        while (remainingTime <= 0.0) {
            onTrigger()

            if (!repeating) {
                remainingTime = 0.0
                state = TimerState.FINISHED
                return
            }

            duration = nextDuration()
            remainingTime += duration
        }
    }

    fun start() {
        reset()
    }

    fun reset() {
        duration = nextDuration()
        remainingTime = duration
        state = TimerState.RUNNING
    }

    fun pause() {
        state = TimerState.PAUSED
    }

    fun resume() {
        state = TimerState.RUNNING
    }

    fun cancel() {
        state = TimerState.CANCELLED
        remainingTime = 0.0
    }
}