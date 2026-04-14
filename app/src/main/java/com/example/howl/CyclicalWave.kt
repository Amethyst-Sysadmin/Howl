package com.example.howl

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.collections.zipWithNext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sign
import kotlin.random.Random

class CyclicalWave(val shape: WaveShape) {
    val name: String get() = shape.name
    val numPoints: Int get() = shape.points.count()
    init {
        require(shape.points.size >= 2) { "Shape must contain at least two points" }
        require(shape.points.all { it.time in 0.0..<1.0 }) { "All times must be in [0.0, 1.0)" }
        require(shape.points.zipWithNext().all { (a, b) -> a.time < b.time }) {
            "Points must be time-ordered"
        }
    }
    fun getPositionAndVelocity(simulationTime: Double): Pair<Double, Double> {
        val phase = simulationTime % 1.0
        val (previousPoint, nextPoint) = findSurroundingPoints(phase)
        val (position, velocity) = when (shape.interpolationType) {
            InterpolationType.HERMITE -> hermiteInterpolateWithVelocity(
                t = phase,
                t0 = previousPoint.time,
                p0 = previousPoint.position,
                m0 = previousPoint.slope!!,
                t1 = nextPoint.time,
                p1 = nextPoint.position,
                m1 = nextPoint.slope!!
            )
            InterpolationType.LINEAR -> linearInterpolateWithVelocity(
                t = phase,
                t0 = previousPoint.time,
                p0 = previousPoint.position,
                t1 = nextPoint.time,
                p1 = nextPoint.position
            )
        }
        return Pair(position.coerceIn(0.0, 1.0), velocity)
    }
    fun getPosition(simulationTime: Double): Double {
        val phase = simulationTime % 1.0
        val (previousPoint, nextPoint) = findSurroundingPoints(phase)
        val position = when (shape.interpolationType) {
            InterpolationType.HERMITE -> hermiteInterpolate(
                t = phase,
                t0 = previousPoint.time,
                p0 = previousPoint.position,
                m0 = previousPoint.slope!!,
                t1 = nextPoint.time,
                p1 = nextPoint.position,
                m1 = nextPoint.slope!!
            )
            InterpolationType.LINEAR -> linearInterpolate(
                t = phase,
                t0 = previousPoint.time,
                p0 = previousPoint.position,
                t1 = nextPoint.time,
                p1 = nextPoint.position
            )
        }
        return position.coerceIn(0.0, 1.0)
    }
    private fun findSurroundingPoints(
        phase: Double
    ): Pair<WavePoint, WavePoint> {
        val points = shape.points
        val index = points.indexOfLast { it.time <= phase }
        return when {
            index == -1 -> {
                // All points are after the phase, wrap to previous cycle's last point
                val previous = points.last().withTimeWrappedBackwards()
                val next = points.first()
                Pair(previous, next)
            }
            index == points.lastIndex -> {
                // Phase is after the last point, wrap to next cycle's first point
                val previous = points.last()
                val next = points.first().withTimeWrappedForwards()
                Pair(previous, next)
            }
            else -> {
                val previous = points[index]
                val next = points[index + 1]
                Pair(previous, next)
            }
        }
    }
    private fun WavePoint.withTimeWrappedForwards() = copy(time = time + 1.0)
    private fun WavePoint.withTimeWrappedBackwards() = copy(time = time - 1.0)
}

fun CyclicalWave.createRepeatedWave(repeats: Int, newName: String): CyclicalWave {
    require(repeats > 0) { "Repeats must be positive" }

    val newPoints = mutableListOf<WavePoint>()
    val timeScale = 1.0 / repeats

    for (i in 0 until repeats) {
        val timeOffset = i * timeScale
        for (point in this.shape.points) {
            val newTime = point.time * timeScale + timeOffset
            if (newTime < 1.0) {
                // Scale the slope by the time compression factor
                val newSlope = point.slope?.let { it * repeats }
                newPoints.add(WavePoint(newTime, point.position, newSlope))
            }
        }
    }

    // Ensure we have at least 2 points
    if (newPoints.size < 2) {
        // Fallback: add the first point again at the end if needed
        newPoints.add(WavePoint(1.0 - SMALL_AMOUNT, newPoints.first().position, newPoints.first().slope))
    }

    return CyclicalWave(
        WaveShape(
            name = newName,
            points = newPoints.sortedBy { it.time },
            interpolationType = this.shape.interpolationType
        )
    )
}

class JitterHandler {
    private var previousFactor: Double = 1.0
    private var currentFactor: Double = 1.0
    private var lastCycle: Int = -1

    var jitter: Double = 0.0
        private set

    var easeIn: Double = 0.0
        private set

    fun setJitter(jitter: Double) {
        this.jitter = jitter.coerceIn(0.0, 1.0)
    }

    fun setEaseIn(easeIn: Double) {
        this.easeIn = easeIn.coerceIn(0.0, 1.0)
    }

    fun update(time: Double) {
        val cycle = floor(time).toInt()

        if (cycle != lastCycle) {
            if (lastCycle == -1) {
                // First update cycle or restarted.
                // Initialize both factors to the same random value to ensure
                // consistent jitter application from the very start.
                val initialFactor = generateFactor()
                previousFactor = initialFactor
                currentFactor = initialFactor
            } else {
                // Standard cycle transition: advance factors.
                previousFactor = currentFactor
                currentFactor = generateFactor()
            }
            lastCycle = cycle
        }
    }

    private fun generateFactor(): Double {
        return if (jitter == 0.0) 1.0 else 1.0 + Random.nextDouble(-jitter, jitter)
    }

    fun reset() {
        lastCycle = -1
        previousFactor = 1.0
        currentFactor = 1.0
    }

    fun getInterpolatedFactor(time: Double): Double {
        val phase = time % 1.0
        val weight = if (easeIn == 0.0) 1.0 else (phase / easeIn).coerceIn(0.0, 1.0)
        val rawFactor = previousFactor + (currentFactor - previousFactor) * weight
        // Clamp to the expected range as there are times when the jitter setting can change
        // mid-cycle e.g. when it's controlled by the user
        return rawFactor.coerceIn(1.0 - jitter, 1.0 + jitter)
    }
}

class WaveManager : ActivityComponent {
    private val waves = mutableMapOf<String, CyclicalWave>()
    private var currentTime: Double = 0.0

    private var baseAmplitude: Double = 1.0
    val baseSpeed = NiceSmoother(1.0, 0.01..10.0)

    private var isStopped: Boolean = false
    private var stopTargetCycle: Double? = null
    private var stopCallback: (() -> Unit)? = null

    private val amplitudeJitterHandler = JitterHandler()
    private val speedJitterHandler = JitterHandler()

    val currentAmplitude: Double
        get() {
            // Reduce base amplitude to leave "space" for the desired amount of jitter
            val jitter = amplitudeJitterHandler.jitter
            val safeBase = if (jitter == 0.0) {
                baseAmplitude
            } else {
                baseAmplitude / (1.0 + jitter)
            }

            return (safeBase * amplitudeJitterHandler.getInterpolatedFactor(currentTime)).coerceIn(0.0..1.0)
        }

    val currentSpeed: Double
        get() {
            return baseSpeed.value * speedJitterHandler.getInterpolatedFactor(currentTime)
        }

    fun addWave(wave: CyclicalWave, name: String? = null) {
        val waveName = name ?: wave.name
        waves[waveName] = wave
    }

    fun removeWave(name: String) {
        waves.remove(name)
    }

    fun getWave(name: String): CyclicalWave {
        val wave = waves[name] ?: throw IllegalArgumentException("Wave '$name' not found")
        return wave
    }

    fun removeWave(wave: CyclicalWave) {
        waves.remove(wave.name)
    }

    override fun update(deltaTime: Double) {
        require(deltaTime >= 0.0) { "Time delta may not be negative"}
        if (isStopped)
            return

        baseSpeed.update(deltaTime)

        val playbackTime = deltaTime * currentSpeed

        if (stopTargetCycle != null && currentTime + playbackTime >= stopTargetCycle!!) {
            currentTime = stopTargetCycle!!
            isStopped = true
            stopCallback?.invoke()
        } else {
            currentTime += playbackTime
        }
        amplitudeJitterHandler.update(currentTime)
        speedJitterHandler.update(currentTime)
    }

    fun getPositionAndVelocity(name: String, applyAmplitude: Boolean = false, clampResult: Boolean = true, offset: Double = 0.0): Pair<Double, Double> {
        val wave = waves[name] ?: throw IllegalArgumentException("Wave '$name' not found")
        val (position, velocity) = wave.getPositionAndVelocity(currentTime + offset)
        val scalePosition = if (applyAmplitude) currentAmplitude else 1.0
        val scaleVelocity = if (applyAmplitude) currentAmplitude * currentSpeed else currentSpeed
        val scaledPosition = scalePosition * position
        val scaledVelocity = scaleVelocity * velocity
        val clampedPosition = if (clampResult) scaledPosition.coerceIn(0.0..1.0) else scaledPosition
        return Pair(clampedPosition, scaledVelocity)
    }

    fun getPosition(name: String, applyAmplitude: Boolean = false, clampResult: Boolean = true, offset: Double = 0.0): Double {
        val wave = waves[name] ?: throw IllegalArgumentException("Wave '$name' not found")
        val scale = if (applyAmplitude) currentAmplitude else 1.0
        val result = scale * wave.getPosition(currentTime + offset)
        return if (clampResult) result.coerceIn(0.0..1.0) else result
    }

    fun stopAtEndOfCycle(callback: () -> Unit) {
        stopTargetCycle = floor(currentTime + 1.0)
        stopCallback = callback
    }

    fun stopAfterIterations(iterations: Int, callback: () -> Unit) {
        stopTargetCycle = floor(currentTime + iterations)
        stopCallback = callback
    }

    fun restart() {
        stopTargetCycle = null
        isStopped = false
        currentTime = 0.0
        amplitudeJitterHandler.reset()
        speedJitterHandler.reset()
    }

    fun setSpeed(newSpeed: Double) {
        baseSpeed.setImmediately(newSpeed)
    }

    fun setAmplitude(newAmplitude: Double) {
        baseAmplitude = newAmplitude
    }

    fun setAmplitudeJitter(jitter: Double) {
        amplitudeJitterHandler.setJitter(jitter.coerceIn(0.0, 1.0))
    }

    fun setSpeedJitter(jitter: Double) {
        speedJitterHandler.setJitter(jitter.coerceIn(0.0, 1.0))
    }

    fun setAmplitudeJitterEaseIn(easeIn: Double) {
        amplitudeJitterHandler.setEaseIn(easeIn)
    }

    fun setSpeedJitterEaseIn(easeIn: Double) {
        speedJitterHandler.setEaseIn(easeIn)
    }

    fun setTargetSpeed(target: Double, rate: Double? = null, onReached: (() -> Unit)? = null) {
        baseSpeed.setTarget(target, rate = rate, onReached = onReached)
    }

    fun getTargetSpeed(): Double {
        return baseSpeed.target
    }
}

class NiceSmoother(initialValue: Double = 0.0, val range: ClosedFloatingPointRange<Double> = 0.0..1.0) : ActivityComponent {
    // Implements a value that moves towards a target at a specified rate.
    // Movement has an "S" curve profile with gentle start and end.
    // Gracefully handles the target changing during the transition.

    // Backing fields for the flows
    private val _valueFlow = MutableStateFlow(initialValue.coerceIn(range))
    private val _rateFlow = MutableStateFlow(1.0)
    private val _targetFlow = MutableStateFlow(initialValue.coerceIn(range))

    // Exposed read-only StateFlows for UI observation
    val valueFlow: StateFlow<Double> = _valueFlow.asStateFlow()
    val rateFlow: StateFlow<Double> = _rateFlow.asStateFlow()
    val targetFlow: StateFlow<Double> = _targetFlow.asStateFlow()

    var value: Double
        get() = _valueFlow.value
        private set(v) {
            _valueFlow.value = v.coerceIn(range)
        }

    var velocity: Double = 0.0
        private set

    // Units per second, higher values transition faster.
    var rate: Double
        get() = _rateFlow.value
        set(value) {
            val safeRate = max(0.0001, value)
            _rateFlow.value = safeRate

            if (isTransitioning)
                setTarget(target, onReached = onReached)
        }

    var target: Double
        get() = _targetFlow.value
        private set(value) {
            _targetFlow.value = value.coerceIn(range)
        }

    private var startValue: Double = initialValue
    private var startVelocity: Double = 0.0

    private var duration: Double = 0.0
    private var elapsed: Double = 0.0
    private var isTransitioning = false

    private var onReached: (() -> Unit)? = null

    override fun update(deltaTime: Double) {
        require(deltaTime >= 0.0)

        if (!isTransitioning) return

        elapsed += deltaTime

        if (elapsed >= duration) {
            finishTransition()
            return
        }

        val (pos, vel) = hermiteInterpolateWithVelocity(
            t = elapsed,
            t0 = 0.0,
            p0 = startValue,
            m0 = startVelocity,
            t1 = duration,
            p1 = target,
            m1 = 0.0
        )

        value = pos
        velocity = vel
    }

    fun setTarget(
        target: Double,
        rate: Double? = null,
        onReached: (() -> Unit)? = null
    ) {
        this.onReached = onReached
        if (rate != null)
            this._rateFlow.value = rate.coerceAtLeast(0.0001)

        // Capture current motion state
        startValue = value
        startVelocity = velocity
        this.target = target
        elapsed = 0.0

        val distance = abs(target - startValue)
        val direction = sign(target - startValue)

        val wrongDirectionPenalty =
            if (startVelocity * direction < 0.0)
                abs(startVelocity) / this.rate
            else
                0.0

        // additional fixed time component so that very short movements aren't too fast
        val smoothTime = 0.2 / this.rate

        duration = (distance / this.rate) + wrongDirectionPenalty + smoothTime

        isTransitioning = true
    }

    fun setImmediately(value: Double) {
        this.value = value
        velocity = 0.0
        target = value
        isTransitioning = false
        onReached = null
    }

    private fun finishTransition() {
        value = target
        velocity = 0.0
        isTransitioning = false
        val callback = onReached
        onReached = null
        callback?.invoke()
    }
}

class NoiseGenerator {
    private val noise = OpenSimplexNoise(System.currentTimeMillis())

    fun getNoise(time: Double, rotation: Double, radius: Double, axis: Int, shiftResult: Boolean): Pair<Double, Double> {
        val circleX = radius * cos(rotation)
        val circleY = radius * sin(rotation)

        val (x0, y0, z0) = when (axis) {
            0 -> Triple(time, circleX, circleY)
            1 -> Triple(circleX, time, circleY)
            2 -> Triple(circleX, circleY, time)
            else -> throw IllegalArgumentException("Axis must be 0, 1, or 2")
        }

        val (x1, y1, z1) = when (axis) {
            0 -> Triple(time, -circleX, -circleY)
            1 -> Triple(-circleX, time, -circleY)
            2 -> Triple(-circleX, -circleY, time)
            else -> throw IllegalArgumentException("Axis must be 0, 1, or 2")
        }

        val noiseA = noise.random3D(x0, y0, z0)
        val noiseB = noise.random3D(x1, y1, z1)

        return if (shiftResult) {
            Pair((noiseA + 1) / 2, (noiseB + 1) / 2)
        } else {
            Pair(noiseA, noiseB)
        }
    }
}