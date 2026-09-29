package com.example.howl

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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

        // Initialise slopes using the Fritsch-Carlson method
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

data class Quadruple<T1, T2, T3, T4>(
    val first: T1,
    val second: T2,
    val third: T3,
    val fourth: T4
)

data class PolarPosition(
    val radius: Float, // Normalized from 0.0 (centre) to 1.0 (edge)
    val angleRadians: Float // Direction from 0.0 to 2*PI radians
)

data class CartesianPosition(
    val x: Float, // Normalized from -1.0 (left edge) to 1.0 (right edge)
    val y: Float  // Normalized from -1.0 (top edge) to 1.0 (bottom edge)
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

fun ByteArray.toHexString() = joinToString("") { String.format("%02X", it) }

fun ClosedRange<Double>.lerp(fraction: Double): Double = lerp(start, endInclusive, fraction)
fun ClosedRange<Float>.lerp(fraction: Float): Float = lerp(start, endInclusive, fraction)
fun ClosedRange<Int>.lerp(fraction: Double): Int = lerp(start, endInclusive, fraction)
fun ClosedRange<Long>.lerp(fraction: Double): Long = lerp(start, endInclusive, fraction)

fun IntRange.toProportionOf(parent: IntRange): ClosedFloatingPointRange<Float> {
    val parentSize = (parent.last - parent.first).toFloat()
    return ((this.first - parent.first) / parentSize)..((this.last - parent.first) / parentSize)
}

fun IntRange.toClosedFloatingPointRange(): ClosedFloatingPointRange<Float> {
    return this.first.toFloat()..this.last.toFloat()
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

fun calculateSliderSteps(range: ClosedFloatingPointRange<Float>, increment: Float): Int {
    return ((range.endInclusive - range.start) / increment).roundToInt() - 1
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
    m1: Double,
    shape: Double = 1.0
): Double {
    val (hermitePos, h, _, _) = getHermitePositionAndFactors(t, t0, p0, m0, t1, p1, m1)
    if (shape == 1.0) return hermitePos

    // Linear interpolation component
    val linearPos = p0 + (p1 - p0) * h

    // Blend: linear + shape * (hermite - linear)
    return linearPos + shape * (hermitePos - linearPos)
}

fun hermiteInterpolateWithVelocity(
    t: Double,
    t0: Double,
    p0: Double,
    m0: Double,
    t1: Double,
    p1: Double,
    m1: Double,
    shape: Double = 1.0
): Pair<Double, Double> {
    val (hermitePos, h, hSq, hCu) = getHermitePositionAndFactors(t, t0, p0, m0, t1, p1, m1)
    val dt = t1 - t0

    val dpdh = (6 * hSq - 6 * h) * p0 +
            (3 * hSq - 4 * h + 1) * m0 * dt +
            (-6 * hSq + 6 * h) * p1 +
            (3 * hSq - 2 * h) * m1 * dt
    val hermiteVel = if (dt != 0.0) dpdh / dt else 0.0

    if (shape == 1.0) {
        return Pair(hermitePos, hermiteVel)
    }

    val linearPos = p0 + (p1 - p0) * h
    val linearVel = if (dt != 0.0) (p1 - p0) / dt else 0.0

    val finalPos = linearPos + shape * (hermitePos - linearPos)
    val finalVel = linearVel + shape * (hermiteVel - linearVel)

    return Pair(finalPos, finalVel)
}

fun hermiteInterpolateWithVelocityAndAcceleration(
    t: Double,
    t0: Double,
    p0: Double,
    m0: Double,
    t1: Double,
    p1: Double,
    m1: Double,
    shape: Double = 1.0
): Triple<Double, Double, Double> {

    val (hermitePos, h, hSq, _) = getHermitePositionAndFactors(t, t0, p0, m0, t1, p1, m1)

    val dt = (t1 - t0)
    if (dt == 0.0) return Triple(hermitePos, 0.0, 0.0)

    // -------- Hermite Velocity --------
    val dpdh =
        (6 * hSq - 6 * h) * p0 +
                (3 * hSq - 4 * h + 1) * m0 * dt +
                (-6 * hSq + 6 * h) * p1 +
                (3 * hSq - 2 * h) * m1 * dt

    val hermiteVel = dpdh / dt

    // -------- Hermite Acceleration --------
    val d2pdh2 =
        (12 * h - 6) * p0 +
                (6 * h - 4) * m0 * dt +
                (-12 * h + 6) * p1 +
                (6 * h - 2) * m1 * dt

    val hermiteAcc = d2pdh2 / (dt * dt)

    if (shape == 1.0) {
        return Triple(hermitePos, hermiteVel, hermiteAcc)
    }

    val linearPos = p0 + (p1 - p0) * h
    val linearVel = (p1 - p0) / dt
    val linearAcc = 0.0

    val finalPos = linearPos + shape * (hermitePos - linearPos)
    val finalVel = linearVel + shape * (hermiteVel - linearVel)
    val finalAcc = linearAcc + shape * (hermiteAcc - linearAcc)

    return Triple(finalPos, finalVel, finalAcc)
}

fun calculatePositionalEffect(
    amplitude: Double,
    position: Double,
    positionalEffectStrength: Double
): Pair<Double, Double> {
    // Curve to tune the positional effect to provide equal sensations at the centre and extremes.
    // 1.0 = linear panning. 0.5 = constant power panning (default).
    val curve = Prefs.calibrationPositionalEffectCurve.value.toDouble().coerceAtLeast(0.01)

    //calculate the "effective position" by interpolating between the original position and the neutral position based on the effect strength
    val effectivePosition = 0.5 * (1 - positionalEffectStrength) + position * positionalEffectStrength

    val baseA = (1.0 - effectivePosition).coerceIn(0.0, 1.0)
    val baseB = effectivePosition.coerceIn(0.0, 1.0)

    // Apply the perceptual power curve
    val amplitudeA = amplitude * baseA.pow(curve)
    val amplitudeB = amplitude * baseB.pow(curve)

    return Pair(amplitudeA, amplitudeB)
}

fun calculateFeelAdjustment(
    value: Float,
    feel: Float,
): Float {
    // we use the inverse as it makes the sliders feel more logical
    // e.g. increasing frequency feel uses more higher frequencies
    return value.pow(1.0f / feel).coerceIn(0.0f,1.0f)
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

fun Uri.getName(context: Context): String {
    // Does the ridiculous process required just to get a local filename on Android.
    return try {
        context.contentResolver.query(this, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                cursor.getString(nameIndex) ?: ""
            } else {
                ""
            }
        } ?: ""
    } catch (e: Exception) {
        HLog.w("URI", "Could not resolve file name for $this: ${e.message}")
        ""
    }
}

/**
 * General thread safe circular buffer class that can be used to store history or as a FIFO queue.
 * Uses a lightweight optimistic locking mechanism that assumes concurrent access will be rare.
 */
class CircularBuffer<T>(capacity: Int) {
    init {
        require(capacity > 0) { "Capacity must be positive" }
    }

    private val lock = Any()

    // Private internal mutable state variables
    private var _capacity = capacity
    private var buffer: Array<T?> = arrayOfNulls(_capacity)
    private var start = 0
    private var _size = 0

    // Thread-safe public properties
    val capacity: Int
        get() = synchronized(lock) { _capacity }

    val size: Int
        get() = synchronized(lock) { _size }

    val isEmpty: Boolean
        get() = synchronized(lock) { _size == 0 }

    val isFull: Boolean
        get() = synchronized(lock) { _size == _capacity }

    fun firstOrNull(): T? = synchronized(lock) {
        if (_size == 0) null else buffer[start]
    }

    fun lastOrNull(): T? = synchronized(lock) {
        if (_size == 0) null else buffer[(start + _size - 1) % _capacity]
    }

    fun clear() = synchronized(lock) {
        start = 0
        _size = 0
        buffer.fill(null) // Clear references to help garbage collection
    }

    // Internal helper assumes the lock is already held
    private fun addInternal(element: T, overwrite: Boolean) {
        if (_size == _capacity) {
            if (!overwrite) {
                throw IllegalStateException("Cannot perform add operation since buffer is full and overwrite=false")
            }
            buffer[start] = element
            start = (start + 1) % _capacity
        } else {
            buffer[(start + _size) % _capacity] = element
            _size++
        }
    }

    fun add(element: T, overwrite: Boolean = false) = synchronized(lock) {
        addInternal(element, overwrite)
    }

    // non-atomic, can partially write when overwrite = false
    fun addAll(elements: Collection<T>, overwrite: Boolean = false) = synchronized(lock) {
        for (element in elements) {
            addInternal(element, overwrite)
        }
    }

    fun removeFirstOrNull(): T? = synchronized(lock) {
        if (_size == 0) return null
        val element = buffer[start]
        buffer[start] = null
        start = (start + 1) % _capacity
        _size--
        element
    }

    fun removeLastOrNull(): T? = synchronized(lock) {
        if (_size == 0) return null
        val index = (start + _size - 1) % _capacity
        val element = buffer[index]
        buffer[index] = null
        _size--
        element
    }

    fun resize(newCapacity: Int, clear: Boolean = false): Unit = synchronized(lock) {
        require(newCapacity > 0) { "New capacity must be positive" }
        if (newCapacity == _capacity && !clear) return

        val oldElements = if (clear) emptyList() else toListInternal().takeLast(newCapacity)

        _capacity = newCapacity
        buffer = arrayOfNulls(newCapacity)
        start = 0
        _size = 0

        for (e in oldElements) {
            addInternal(e, false) // Re-add elements atomically without needing overwrite logic
        }
    }

    // Internal helper assumes the lock is already held
    @Suppress("UNCHECKED_CAST")
    private fun toListInternal(): List<T> = List(_size) { i -> buffer[(start + i) % _capacity] as T }

    fun toList(): List<T> = synchronized(lock) {
        toListInternal()
    }

    @Suppress("UNCHECKED_CAST")
    fun lastN(n: Int): List<T> = synchronized(lock) {
        val count = n.coerceIn(0, _size)
        val offset = _size - count
        List(count) { i -> buffer[(start + offset + i) % _capacity] as T }
    }

    override fun toString(): String = synchronized(lock) {
        toListInternal().toString()
    }
}