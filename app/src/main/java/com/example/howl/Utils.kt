package com.example.howl

import android.util.Log
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

data class WavePoint(
    val time: Double,
    val position: Double,
    val slope: Double
)

enum class InterpolationType {
    HERMITE, LINEAR
}

data class FrequencyConverterPoint(
    val position: Double,
    val frequency: Double
)

enum class FrequencyInterpolationType {
    SMOOTHSTEP, LINEAR
}


data class WaveShape(
    val name: String,
    val points: List<WavePoint>,
    val interpolationType: InterpolationType = InterpolationType.HERMITE
)

data class Quadruple<T1, T2, T3, T4>(
    val first: T1,
    val second: T2,
    val third: T3,
    val fourth: T4
)

fun Float.roughlyEqual(other: Float) : Boolean {
    return (abs(this-other) < 1e-6)
}

fun Double.roughlyEqual(other: Double) : Boolean {
    return (abs(this-other) < 1e-6)
}

fun Double.scaleBetween(a: Double, b: Double): Double {
    return (a + (b - a) * this).coerceIn(minOf(a, b), maxOf(a, b))
}

fun Double.scaleBetween(range: ClosedRange<Double>): Double {
    val (a, b) = range.start to range.endInclusive
    return (a + (b - a) * this).coerceIn(minOf(a, b), maxOf(a, b))
}

val ClosedRange<Double>.toFloatRange: ClosedFloatingPointRange<Float>
    get() = this.start.toFloat()..this.endInclusive.toFloat()

fun randomInRange(range: ClosedRange<Double>, bias: Double = 1.0): Double {
    /*
        Generates a random number in the specified range, optionally with biased probability
        where certain values are more likely.
        When bias > 1, lower values become more probable
        When bias = 1, results are uniform (no bias)
        When 0 < bias < 1, higher values become more probable
    */
    require(bias > 0) { "Bias must be positive" }
    val min = minOf(range.start, range.endInclusive)
    val max = maxOf(range.start, range.endInclusive)
    if (min == max) return min

    val u = Random.nextDouble(0.0, 1.0) // Uniform in [0, 1)
    val skewed = u.pow(bias) // Apply bias transformation
    return min + skewed * (max - min)
}

fun randomInRange(range: IntRange): Int {
    return range.random()
}

inline fun <reified T: Enum<T>> T.next(): T {
    val values = enumValues<T>()
    val nextOrdinal = (ordinal + 1) % values.size
    return values[nextOrdinal]
}

fun scaleVelocity(velocity: Double, sensitivity: Double): Double {
    val scaledVelocity = if (velocity != 0.0) {
        abs(velocity) / (abs(velocity) + sensitivity)
    } else 0.0
    return scaledVelocity
}

fun smoothstep(t: Double): Double {
    val clamped = t.coerceIn(0.0..1.0)
    return clamped * clamped * (3.0 - 2.0 * clamped)
}

fun linearInterpolate(
    t: Double,
    t0: Double,
    p0: Double,
    t1: Double,
    p1: Double
): Double {
    if (t0 >= t1) return p0
    val h = (t - t0) / (t1 - t0)
    return p0 + h * (p1 - p0)
}

fun linearInterpolateWithVelocity(
    t: Double,
    t0: Double,
    p0: Double,
    t1: Double,
    p1: Double
): Pair<Double, Double> {
    if (t0 >= t1) return Pair(p0, 0.0)
    val h = (t - t0) / (t1 - t0)
    val position = p0 + h * (p1 - p0)
    val velocity = (p1 - p0) / (t1 - t0)
    return Pair(position, velocity)
}

private fun getHermitePositionAndFactors(
    t: Double,
    t0: Double,
    p0: Double,
    m0: Double,
    t1: Double,
    p1: Double,
    m1: Double
): Quadruple<Double, Double, Double, Double> {
    if (t0 >= t1) {
        return Quadruple(p0, 0.0, 0.0, 0.0)
    }
    val h = (t - t0) / (t1 - t0)
    val hSq = h * h
    val hCu = hSq * h

    val position = p0 * (2 * hCu - 3 * hSq + 1) +
            m0 * (hCu - 2 * hSq + h) * (t1 - t0) +
            p1 * (-2 * hCu + 3 * hSq) +
            m1 * (hCu - hSq) * (t1 - t0)

    return Quadruple(position, h, hSq, hCu)
}

fun hermiteInterpolate(
    t: Double,
    t0: Double,
    p0: Double,
    m0: Double,
    t1: Double,
    p1: Double,
    m1: Double
): Double {
    return getHermitePositionAndFactors(t, t0, p0, m0, t1, p1, m1).first
}

fun hermiteInterpolateWithVelocity(
    t: Double,
    t0: Double,
    p0: Double,
    m0: Double,
    t1: Double,
    p1: Double,
    m1: Double
): Pair<Double, Double> {
    val (position, h, hSq, hCu) = getHermitePositionAndFactors(t, t0, p0, m0, t1, p1, m1)

    val dpdh = (6 * hSq - 6 * h) * p0 +
            (3 * hSq - 4 * h + 1) * m0 * (t1 - t0) +
            (-6 * hSq + 6 * h) * p1 +
            (3 * hSq - 2 * h) * m1 * (t1 - t0)
    val velocity = if (t1 != t0) dpdh / (t1 - t0) else 0.0

    return Pair(position, velocity)
}

fun calculatePositionalEffect(
    amplitude: Double,
    position: Double,
    positionalEffectStrength: Double
): Pair<Double, Double> {
    //calculate the "effective position" by interpolating between the original position and the neutral position based on the effect strength
    val effectivePosition = 0.5 * (1 - positionalEffectStrength) + position * positionalEffectStrength

    val amplitudeA = amplitude * sqrt(1 - effectivePosition)
    val amplitudeB = amplitude * sqrt(effectivePosition)
    return Pair(amplitudeA, amplitudeB)
}

fun calculatePositionalEffectLinear(
    amplitude: Double,
    position: Double,
    positionalEffectStrength: Double
): Pair<Double, Double> {
    //calculate the "effective position" by interpolating between the original position and the neutral position based on the effect strength
    val effectivePosition = 0.5 * (1 - positionalEffectStrength) + position * positionalEffectStrength

    val amplitudeA = amplitude * (1 - effectivePosition)
    val amplitudeB = amplitude * (effectivePosition)
    return Pair(amplitudeA, amplitudeB)
}

fun calculateEngulfEffect(
    amplitude: Double,
    position: Double,
    channelAEngulfPoint: Double,
    channelBEngulfPoint: Double,
): Pair<Double, Double> {
    fun calculateChannelAmplitude(position: Double, engulfPoint: Double): Double {
        val distance = abs(position - engulfPoint)
        val falloffFactor = 0.8
        return if (position <= engulfPoint) {
            if (engulfPoint == 0.0) 1.0 else sqrt(position / engulfPoint)
        } else {
            sqrt(1.0 - distance * falloffFactor)
        }
    }
    val ampA = calculateChannelAmplitude(position, channelAEngulfPoint) * amplitude
    val ampB = calculateChannelAmplitude(position, channelBEngulfPoint) * amplitude
    return Pair(ampA, ampB)
}

/*fun calculateFeelAdjustment(
    frequency: Double,
    feelExponent: Double,
): Double {
    return frequency.pow(feelExponent).coerceIn(0.0,1.0)
}*/

fun calculateFeelAdjustment(
    frequency: Float,
    exponent: Float,
): Float {
    return frequency.pow(1.0f / exponent).coerceIn(0.0f,1.0f)
}

class TimerManager {
    private val timers = mutableMapOf<String, Timer>()

    fun addTimer(key: String, duration: Double, callback: () -> Unit) {
        if (duration <= 0) {
            callback()
            return
        }
        timers[key] = Timer(duration, duration, callback)
    }

    fun cancelTimer(key: String) {
        timers.remove(key)
    }

    fun hasTimer(key: String): Boolean = timers.containsKey(key)

    fun getRemainingTime(key: String): Double? {
        return timers[key]?.remainingTime
    }

    fun getElapsedTime(key: String): Double? {
        val timer = timers[key] ?: return null
        return timer.initialDuration - timer.remainingTime
    }

    fun getProportionElapsed(key: String): Double? {
        val timer = timers[key] ?: return null
        return (timer.initialDuration - timer.remainingTime) / timer.initialDuration
    }

    fun update(delta: Double) {
        require(delta >= 0.0) { "Time delta may not be negative"}
        // Create a snapshot of keys to avoid concurrent modification
        val keysSnapshot = timers.keys.toList()

        keysSnapshot.forEach { key ->
            timers[key]?.let { timer ->
                timer.remainingTime -= delta
                if (timer.remainingTime <= 0) {
                    // Remove before callback to prevent interference
                    timers.remove(key)
                    timer.callback()
                }
            }
        }
    }

    private data class Timer(
        val initialDuration: Double,
        var remainingTime: Double,
        val callback: () -> Unit
    )
}

class SmoothedValue(initialValue: Double = 0.0) {
    //implements a value that is capable of smoothly transitioning towards a target over time
    private var start: Double = initialValue
    private var target: Double = initialValue
    private var elapsed: Double = 0.0
    private var duration: Double = 0.0
    private var onReached: (() -> Unit)? = null

    val current: Double
        get() = if (!isTransitioning) target else calculateInterpolatedValue()

    val isTransitioning: Boolean
        get() = elapsed < duration && duration > 0.0

    fun setTarget(
        target: Double,
        rate: Double? = null,
        duration: Double? = null,
        onReached: (() -> Unit)? = null
    ) {
        require((rate == null) xor (duration == null)) {
            "Must specify exactly one of rate or duration"
        }

        start = current
        this.target = target
        val difference = abs(target - start)

        this.duration = when {
            rate != null -> if (rate > 0.0) difference / rate else 0.0
            duration != null -> if (difference > 0.0) duration else 0.0
            else -> 0.0 // Impossible due to require check
        }

        elapsed = 0.0
        this.onReached = onReached

        if (!isTransitioning) {
            handleTransitionComplete()
        }
    }

    fun getTarget(): Double {
        return target
    }

    fun setImmediately(value: Double) {
        start = value
        target = value
        elapsed = 0.0
        duration = 0.0
        onReached = null
    }

    fun update(delta: Double) {
        require(delta >= 0.0) { "Time delta may not be negative"}
        if (!isTransitioning) return

        elapsed += delta
        if (!isTransitioning) {
            handleTransitionComplete()
        }
    }

    private fun calculateInterpolatedValue(): Double {
        val t = (elapsed / duration).coerceIn(0.0..1.0)
        return start + (target - start) * smoothstep(t)
    }

    private fun handleTransitionComplete() {
        val callback = onReached
        onReached = null
        callback?.invoke()
    }
}

class FrequencyConverter(
    points: List<FrequencyConverterPoint>,
    private val interpolationType: FrequencyInterpolationType
) {
    private val sortedPoints: List<FrequencyConverterPoint>

    init {
        require(points.isNotEmpty()) { "At least one point is required" }
        sortedPoints = points.sortedBy { it.position }
    }

    fun getFrequency(position: Double): Double {
        if (sortedPoints.size == 1) {
            return sortedPoints.first().frequency
        }

        if (position <= sortedPoints.first().position) {
            return sortedPoints.first().frequency
        }

        if (position >= sortedPoints.last().position) {
            return sortedPoints.last().frequency
        }

        for (i in 0 until sortedPoints.size - 1) {
            val current = sortedPoints[i]
            val next = sortedPoints[i + 1]
            if (position in current.position..next.position) {
                return interpolate(current, next, position)
            }
        }

        throw IllegalStateException("Failed to find interval for position $position during frequency conversion")
    }

    private fun interpolate(
        lower: FrequencyConverterPoint,
        upper: FrequencyConverterPoint,
        position: Double
    ): Double {
        return when (interpolationType) {
            FrequencyInterpolationType.LINEAR -> linearInterpolate(
                t = position,
                t0 = lower.position,
                p0 = lower.frequency,
                t1 = upper.position,
                p1 = upper.frequency
            )
            FrequencyInterpolationType.SMOOTHSTEP -> {
                val h = (position - lower.position) / (upper.position - lower.position)
                val smoothH = smoothstep(h)
                lower.frequency + smoothH * (upper.frequency - lower.frequency)
            }
        }
    }
}

class CircularBuffer<T>(val capacity: Int): Iterable<T> {
    private val buffer: Array<Any?>
    private var start = 0
    private var size = 0

    init {
        require(capacity > 0) { "Capacity must be positive" }
        buffer = arrayOfNulls(capacity)
    }

    fun first(): T {
        if (size == 0) throw NoSuchElementException("Buffer is empty")
        @Suppress("UNCHECKED_CAST")
        return buffer[start] as T
    }

    fun last(): T {
        if (size == 0) throw NoSuchElementException("Buffer is empty")
        @Suppress("UNCHECKED_CAST")
        return buffer[(start + size - 1) % capacity] as T
    }

    fun add(element: T) {
        if (size < capacity) {
            buffer[(start + size) % capacity] = element
            size++
        } else {
            buffer[start] = element
            start = (start + 1) % capacity
        }
    }

    fun toList(): List<T> {
        return List(size) { i ->
            @Suppress("UNCHECKED_CAST")
            buffer[(start + i) % capacity] as T
        }
    }

    override fun toString(): String {
        return toList().toString()
    }

    override fun iterator(): Iterator<T> {
        return object : Iterator<T> {
            private var index = 0

            override fun hasNext(): Boolean {
                return index < size
            }

            override fun next(): T {
                if (!hasNext()) throw NoSuchElementException()
                @Suppress("UNCHECKED_CAST")
                return buffer[(start + index++) % capacity] as T
            }
        }
    }
}