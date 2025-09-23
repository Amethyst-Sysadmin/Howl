package com.example.howl

import android.util.Log
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.random.Random

data class WavePoint(
    val time: Double,
    val position: Double,
    val slope: Double? = null
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


class WaveShape(
    val name: String,
    points: List<WavePoint>,
    val interpolationType: InterpolationType = InterpolationType.HERMITE
) {
    val points: List<WavePoint>

    init {
        require(points.all { it.time in 0.0..<1.0 }) { "All times must be in [0.0, 1.0)" }
        val sortedPoints = points.sortedBy { it.time }.distinctBy { it.time }
        require(sortedPoints.size >= 2) { "Shape must contain at least two unique points" }


        this.points = when (interpolationType) {
            InterpolationType.HERMITE -> {
                // For hermite, use provided slopes if available, otherwise compute monotone slopes
                if (sortedPoints.all { it.slope != null }) {
                    sortedPoints // All slopes provided, use as-is
                } else {
                    // Some slopes are null, compute monotone slopes and fill them in
                    val slopes = computeMonotoneSlopes(sortedPoints)
                    sortedPoints.mapIndexed { i, pt ->
                        pt.copy(slope = pt.slope ?: slopes[i])
                    }
                }
            }
            InterpolationType.LINEAR -> {
                // Slopes are not used for linear interpolation
                sortedPoints
            }
        }
    }

    private fun computeMonotoneSlopes(points: List<WavePoint>): List<Double> {
        val n = points.size
        val slopes = MutableList(n) { 0.0 }

        // Calculate secants between points, including the cyclical wrap-around
        val d = DoubleArray(n)
        for (i in 0 until n) {
            val nextIndex = if (i == n - 1) 0 else i + 1
            val h = if (i == n - 1)
                (1.0 + points[nextIndex].time) - points[i].time  // Wrap around
            else
                points[nextIndex].time - points[i].time

            val deltaPos = if (i == n - 1)
                points[nextIndex].position - points[i].position
            else
                points[nextIndex].position - points[i].position

            d[i] = deltaPos / h
        }

        // Initialize slopes using the Fritsch-Carlson method
        for (i in 0 until n) {
            val prevIndex = if (i == 0) n - 1 else i - 1
            val nextIndex = if (i == n - 1) 0 else i + 1

            if (d[prevIndex] * d[i] <= 0.0) {
                slopes[i] = 0.0
            } else {
                slopes[i] = (d[prevIndex] + d[i]) / 2.0
            }
        }

        // Fritsch–Carlson adjustment to ensure monotonicity
        for (i in 0 until n) {
            val nextIndex = if (i == n - 1) 0 else i + 1

            if (d[i] == 0.0) {
                slopes[i] = 0.0
                slopes[nextIndex] = 0.0
            } else {
                val a = slopes[i] / d[i]
                val b = slopes[nextIndex] / d[i]
                val h = hypot(a, b)
                if (h > 9.0) {
                    val t = 3.0 / h
                    slopes[i] = t * a * d[i]
                    slopes[nextIndex] = t * b * d[i]
                }
            }
        }

        return slopes
    }
}

/*data class WaveShape(
    val name: String,
    val points: List<WavePoint>,
    val interpolationType: InterpolationType = InterpolationType.HERMITE
)*/

data class Quadruple<T1, T2, T3, T4>(
    val first: T1,
    val second: T2,
    val third: T3,
    val fourth: T4
)

fun lerp(start: Double, end: Double, fraction: Double): Double {
    return start + (end - start) * fraction
}

fun lerp(start: Float, end: Float, fraction: Float): Float {
    return start + (end - start) * fraction
}

fun lerp(start: Int, end: Int, fraction: Double): Int {
    return (start + (end - start) * fraction).roundToInt()
}

fun lerp(start: Long, end: Long, fraction: Double): Long {
    return (start + (end - start) * fraction).roundToLong()
}

fun ClosedRange<Double>.lerp(fraction: Double): Double = lerp(start, endInclusive, fraction)
fun ClosedRange<Float>.lerp(fraction: Float): Float = lerp(start, endInclusive, fraction)
fun ClosedRange<Int>.lerp(fraction: Double): Int = lerp(start, endInclusive, fraction)
fun ClosedRange<Long>.lerp(fraction: Double): Long = lerp(start, endInclusive, fraction)

fun IntRange.toProportionOf(parent: IntRange): ClosedFloatingPointRange<Float> {
    val parentSize = (parent.last - parent.first).toFloat()
    return ((this.first - parent.first) / parentSize)..((this.last - parent.first) / parentSize)
}

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

fun Float.scaleBetween(range: ClosedRange<Double>): Double {
    val (a, b) = range.start to range.endInclusive
    return (a + (b - a) * this).coerceIn(minOf(a, b), maxOf(a, b))
}

val ClosedRange<Double>.toFloatRange: ClosedFloatingPointRange<Float>
    get() = this.start.toFloat()..this.endInclusive.toFloat()

// Helper extension function to clamp an IntRange to another range while enforcing minimum separation
fun IntRange.clamp(bounds: IntRange, minSep: Int): IntRange {
    require(minSep >= 0) { "minSep must be non-negative" }

    // First, clamp start and end within bounds
    var start = start.coerceIn(bounds.first, bounds.last)
    var end = endInclusive.coerceIn(bounds.first, bounds.last)

    // Ensure start <= end
    if (start > end) start = end

    // Check if we already satisfy minSep
    if (end - start >= minSep) {
        return start..end
    }

    // Try to expand minimally while staying in bounds
    val needed = minSep - (end - start)

    // Option 1: expand end to the right
    val expandRight = (end + needed).coerceAtMost(bounds.last)
    if (expandRight - start >= minSep) {
        return start..expandRight
    }

    // Option 2: expand start to the left
    val expandLeft = (start - needed).coerceAtLeast(bounds.first)
    if (end - expandLeft >= minSep) {
        return expandLeft..end
    }

    // If neither works, fallback to full bounds
    return bounds
}

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
    private val buffer: Array<T?> = arrayOfNulls(capacity)

    private var start = 0
    var size = 0
        private set

    init {
        require(capacity > 0) { "Capacity must be positive" }
    }

    val isEmpty get() = size == 0
    val isFull get() = size == capacity
    fun first(): T? = if (isEmpty) null else buffer[start]
    fun last(): T? = if (isEmpty) null else buffer[(start + size - 1) % capacity]

    fun clear() {
        start = 0
        size = 0
        buffer.fill(null) // Optional: Clear references to help garbage collection
    }

    fun add(element: T, overwrite: Boolean = false) {
        if (isFull) {
            if (!overwrite) {
                throw IllegalStateException("Cannot perform add operation since buffer is full and overwrite=false")
            }
            buffer[start] = element
            start = (start + 1) % capacity
        } else {
            buffer[(start + size) % capacity] = element
            size++
        }
    }

    fun addAll(elements: Collection<T>, overwrite: Boolean = false) {
        elements.forEach { add(it, overwrite) }
    }

    fun removeFirstOrNull(): T? {
        if (isEmpty) return null
        val element = buffer[start]
        buffer[start] = null
        start = (start + 1) % capacity
        size--
        return element
    }

    fun removeLastOrNull(): T? {
        if (isEmpty) return null
        val index = (start + size - 1) % capacity
        val element = buffer[index]
        buffer[index] = null
        size--
        return element
    }

    fun toList(): List<T> = List(size) { i -> buffer[(start + i) % capacity]!! }

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
                return buffer[(start + index++) % capacity]!!
            }
        }
    }
}