package com.example.howl

import kotlin.collections.zipWithNext
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

class CyclicalWave(private val shape: WaveShape) {
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
        return when (shape.interpolationType) {
            InterpolationType.HERMITE -> hermiteInterpolateWithVelocity(
                t = phase,
                t0 = previousPoint.time,
                p0 = previousPoint.position,
                m0 = previousPoint.slope,
                t1 = nextPoint.time,
                p1 = nextPoint.position,
                m1 = nextPoint.slope
            )
            InterpolationType.LINEAR -> linearInterpolateWithVelocity(
                t = phase,
                t0 = previousPoint.time,
                p0 = previousPoint.position,
                t1 = nextPoint.time,
                p1 = nextPoint.position
            )
        }
    }
    fun getPosition(simulationTime: Double): Double {
        val phase = simulationTime % 1.0
        val (previousPoint, nextPoint) = findSurroundingPoints(phase)
        return when (shape.interpolationType) {
            InterpolationType.HERMITE -> hermiteInterpolate(
                t = phase,
                t0 = previousPoint.time,
                p0 = previousPoint.position,
                m0 = previousPoint.slope,
                t1 = nextPoint.time,
                p1 = nextPoint.position,
                m1 = nextPoint.slope
            )
            InterpolationType.LINEAR -> linearInterpolate(
                t = phase,
                t0 = previousPoint.time,
                p0 = previousPoint.position,
                t1 = nextPoint.time,
                p1 = nextPoint.position
            )
        }
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

class VarianceHandler(
    initialFactor: Double = 1.0,
    private val varianceFunction: (Double) -> Double
) {
    private var previousFactor: Double = initialFactor
    private var currentFactor: Double = initialFactor
    private var variance: Double = 0.0
    var easeIn: Double = 0.0
        private set

    fun setVariance(newVariance: Double) {
        variance = newVariance
    }

    fun setEaseIn(easeIn: Double) {
        this.easeIn = easeIn.coerceIn(0.0..1.0)
    }

    fun applyNewCycle() {
        previousFactor = currentFactor
        currentFactor = if (variance == 0.0) 1.0 else varianceFunction(variance)
    }

    fun getInterpolatedFactor(phase: Double): Double {
        val weight = if (easeIn == 0.0) 1.0 else (phase / easeIn).coerceIn(0.0..1.0)
        return previousFactor + (currentFactor - previousFactor) * weight
    }
}

class WaveManager {
    private val waves = mutableMapOf<String, CyclicalWave>()
    private var currentTime: Double = 0.0

    private var baseAmplitude: Double = 1.0
    private val baseSpeed = SmoothedValue(1.0)
    private var lastCycle: Int = 0

    private var isStopped: Boolean = false
    private var stopTargetCycle: Double? = null
    private var stopCallback: (() -> Unit)? = null

    private val amplitudeVarianceHandler = VarianceHandler(1.0) { variance ->
        1.0 - Random.nextDouble(0.0, variance)
    }

    private val speedVarianceHandler = VarianceHandler(1.0) { variance ->
        1.0 + Random.nextDouble(-variance, variance)
    }

    val currentAmplitude: Double
        get() {
            val phase = currentTime % 1.0
            return baseAmplitude * amplitudeVarianceHandler.getInterpolatedFactor(phase)
        }

    val currentSpeed: Double
        get() {
            val phase = currentTime % 1.0
            return baseSpeed.current * speedVarianceHandler.getInterpolatedFactor(phase)
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

    fun update(delta: Double) {
        require(delta >= 0.0) { "Time delta may not be negative"}
        if (isStopped)
            return

        baseSpeed.update(delta)

        val deltaTime = delta * currentSpeed
        val newTime = currentTime + deltaTime
        val newCycle = floor(newTime).toInt()
        if (newCycle != lastCycle)
            applyCycleVariance(newCycle)

        if (stopTargetCycle != null && currentTime + deltaTime >= stopTargetCycle!!) {
            currentTime = stopTargetCycle!!.toDouble()
            isStopped = true
            stopCallback?.invoke()
        } else {
            currentTime += deltaTime
        }
    }

    private fun applyCycleVariance(newCycle: Int) {
        lastCycle = newCycle
        amplitudeVarianceHandler.applyNewCycle()
        speedVarianceHandler.applyNewCycle()
    }

    fun getPositionAndVelocity(name: String, applyAmplitudeVariance: Boolean = true): Pair<Double, Double> {
        val wave = waves[name] ?: throw IllegalArgumentException("Wave '$name' not found")
        val (position, velocity) = wave.getPositionAndVelocity(currentTime)
        val amplitude = if (applyAmplitudeVariance) currentAmplitude else baseAmplitude
        val scaledPosition = amplitude * position
        val scaledVelocity = amplitude * currentSpeed * velocity
        return Pair(scaledPosition, scaledVelocity)
    }

    fun getPosition(name: String, applyAmplitudeVariance: Boolean = true): Double {
        val wave = waves[name] ?: throw IllegalArgumentException("Wave '$name' not found")
        val amplitude = if (applyAmplitudeVariance) currentAmplitude else baseAmplitude
        return amplitude * wave.getPosition(currentTime)
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
    }

    fun setSpeed(newSpeed: Double) {
        baseSpeed.setImmediately(newSpeed)
    }

    fun setAmplitude(newAmplitude: Double) {
        baseAmplitude = newAmplitude
    }

    fun setAmplitudeVariance(variance: Double) {
        amplitudeVarianceHandler.setVariance(variance.coerceIn(0.0..1.0))
    }

    fun setSpeedVariance(variance: Double) {
        speedVarianceHandler.setVariance(variance.coerceIn(0.0..1.0))
    }

    fun setAmplitudeVarianceEaseIn(easeIn: Double) {
        amplitudeVarianceHandler.setEaseIn(easeIn)
    }

    fun setSpeedVarianceEaseIn(easeIn: Double) {
        speedVarianceHandler.setEaseIn(easeIn)
    }

    fun setTargetSpeed(target: Double, rate: Double, onReached: (() -> Unit)? = null) {
        baseSpeed.setTarget(target, rate = rate, onReached = onReached)
    }

    fun getTargetSpeed(): Double {
        return baseSpeed.getTarget()
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