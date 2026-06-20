package com.example.howl

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.pow

class BadFileException (message: String) : Exception(message)

enum class FrequencyAlgorithmType(val displayName: String) {
    POSITION("Position"),
    VARIED("Varied"),
    BLEND("Blend"),
    FIXED("Fixed")
}

@Serializable
data class Action(val at: Double, val pos: Double)

@Serializable
data class Funscript(
    val actions: List<Action>,
)

fun Uri.getName(context: Context): String {
    val returnCursor = context.contentResolver.query(this, null, null, null, null)
    if (returnCursor == null)
        return ""
    val nameIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
    returnCursor.moveToFirst()
    val fileName = returnCursor.getString(nameIndex)
    returnCursor.close()
    return fileName
}

data class PositionVelocity(val position: Double, val velocity: Double)

class FunscriptPulseSource : PulseSource {
    private val _displayName = MutableStateFlow("Funscript")
    override val displayName = _displayName.asStateFlow()
    override var duration: Double? = null
    override val isFinite: Boolean = true
    override var shouldLoop: Boolean = false
    override var readyToPlay: Boolean = false
    override var isRemote: Boolean = false

    private val timePositionData = TreeMap<Double, PositionVelocity>()
    val jsonConfig = Json { ignoreUnknownKeys = true }

    private val noiseGenerator = NoiseGenerator()

    override fun updateState(currentTime: Double) {}

    private fun getClosestPoints(time: Double): Pair<Pair<Double, PositionVelocity>?, Pair<Double, PositionVelocity>?> {
        val floorEntry = timePositionData.floorEntry(time)
        val ceilingEntry = timePositionData.higherEntry(time)
        val before = floorEntry?.let { Pair(it.key, it.value) }
        val after = ceilingEntry?.let { Pair(it.key, it.value) }
        return Pair(before, after)
    }

    private fun calculateOverallAmplitude(velocity: Double, acceleration: Double, exponent: Double = 0.5): Double {
        // Howl's algorithm bases our overall amplitude (before the positional effect) on both the
        // velocity and the acceleration magnitudes of the stroker. This creates pleasing results
        // with V peaking in the middle of strokes, and A peaking at the top/bottom.
        // Factoring in acceleration also helps to translate common script embellishments like rapid
        // short up/down movements in a nice way.

        val threshold = 0.005 // Minimum below which we zero the output
        val ratio = 0.5 // Weights acceleration and velocity in our formula
        val maxSpeed = 5.0 // typical maximum speed of a "stroker" device (5 strokes per second)
        val maxMagnitude = 80.0 // maximum magnitude of acceleration for normalisation purposes

        val speed = abs(velocity)
        val magnitude = abs(acceleration)
        val normalizedSpeed = (speed / maxSpeed).coerceIn(0.0..1.0)
        val normalizedMagnitude = (magnitude / maxMagnitude).coerceIn(0.0..1.0)

        val rawAmp = normalizedSpeed * (1.0 - ratio) + normalizedMagnitude * ratio
        if (rawAmp < threshold)
            return 0.0
        val amplitude = rawAmp.pow(exponent).coerceIn(0.0..1.0)
        return amplitude
    }

    private fun calculateVariedFrequencies(time: Double, varySpeed: Double): Pair<Double, Double> {
        val variedFrequencyRotationSpeed = 0.05
        val variedFrequencyRadius = 0.4
        return noiseGenerator.getNoise(
            time = time * varySpeed,
            rotation = time * variedFrequencyRotationSpeed,
            radius = variedFrequencyRadius,
            axis = 1,
            shiftResult = true
        )
    }

    private fun getPositionAtTime(time: Double): Double {
        val (before, after) = getClosestPoints(time)
        return when {
            before == null && after == null -> 0.0
            before == null -> after!!.second.position
            after == null -> before.second.position
            else -> {
                val (t0, pv0) = before
                val (t1, pv1) = after
                if (t0 == t1 || pv0.position == pv1.position) {
                    pv0.position
                } else {
                    hermiteInterpolate(
                        time,
                        t0, pv0.position, pv0.velocity,
                        t1, pv1.position, pv1.velocity
                    ).coerceIn(0.0, 1.0)
                }
            }
        }
    }

    fun getPositionVelocityAccelerationAtTime(time: Double): Triple<Double, Double, Double> {
        val (before, after) = getClosestPoints(time)
        return when {
            before == null && after == null -> Triple(0.0, 0.0, 0.0)
            before == null -> Triple(after!!.second.position, 0.0, 0.0)
            after == null -> Triple(before.second.position, 0.0, 0.0)
            else -> {
                val (t0, pv0) = before
                val (t1, pv1) = after
                if (t0 == t1 || pv0.position == pv1.position) {
                    Triple(pv0.position, 0.0, 0.0)
                } else {
                    val (rawPos, rawVel, rawAcc) = hermiteInterpolateWithVelocityAndAcceleration(
                        time, t0, pv0.position, pv0.velocity, t1, pv1.position, pv1.velocity
                    )
                    Triple(rawPos.coerceIn(0.0, 1.0), rawVel, rawAcc)
                }
            }
        }
    }

    override fun getPulseAtTime(time: Double): Pulse {
        val frequencyAlgorithm = Prefs.funscriptFrequencyAlgorithm.value
        val funscriptPositionalEffectStrength = Prefs.funscriptPositionalEffectStrength.value.toDouble()
        val funscriptFrequencyTimeOffset = if (frequencyAlgorithm == FrequencyAlgorithmType.POSITION) Prefs.funscriptFrequencyTimeOffset.value.toDouble() else 0.0
        val frequencyVarySpeed = Prefs.funscriptFrequencyVarySpeed.value.toDouble()
        val frequencyBlendRatio = Prefs.funscriptFrequencyBlendRatio.value.toDouble()
        // higher "volume" boosts slow movements more, but reduces dynamic range
        val volume = Prefs.funscriptVolume.value.toDouble()
        val scalingExponent = (1.0 - volume).coerceAtLeast(0.001)

        val (position, velocity, acceleration) = getPositionVelocityAccelerationAtTime(time)
        val offsetPosition = getPositionAtTime(time + funscriptFrequencyTimeOffset)
        //Log.d("funscript", "T=$time P=$position V=$velocity A=$acceleration")

        val amplitude = calculateOverallAmplitude(velocity, acceleration, exponent = scalingExponent)

        val (amplitudeA, amplitudeB) = calculatePositionalEffect(amplitude, position, funscriptPositionalEffectStrength)

        var (freqA, freqB) = when (frequencyAlgorithm) {
            FrequencyAlgorithmType.POSITION -> Pair(offsetPosition, position)
            FrequencyAlgorithmType.VARIED -> calculateVariedFrequencies(time, frequencyVarySpeed)
            FrequencyAlgorithmType.BLEND -> {
                val (posA, posB) = Pair(offsetPosition, position)
                val (varA, varB) = calculateVariedFrequencies(time, frequencyVarySpeed)
                Pair(
                    posA * (1.0 - frequencyBlendRatio) + varA * frequencyBlendRatio,
                    posB * (1.0 - frequencyBlendRatio) + varB * frequencyBlendRatio
                )
            }
            FrequencyAlgorithmType.FIXED -> Pair(
                Prefs.funscriptFrequencyFixedA.value.toDouble(),
                Prefs.funscriptFrequencyFixedB.value.toDouble()
            )
        }

        return Pulse(
            freqA = freqA.toFloat(),
            freqB = freqB.toFloat(),
            ampA = amplitudeA.toFloat(),
            ampB = amplitudeB.toFloat()
        )
    }

    private fun processFunscript(content: String) {
        val funscript = jsonConfig.decodeFromString<Funscript>(content)

        // Filter duplicate timestamps, sort chronologically, clamp all positions to valid range
        val uniqueActions = funscript.actions
            .distinctBy { it.at }
            .sortedBy { it.at }
            .map { it.copy(pos = it.pos.coerceIn(0.0, 100.0)) }

        if (uniqueActions.size < 2) {
            throw BadFileException("Funscript must have at least 2 actions")
        }

        // Calculate bounds for normalisation
        val minPos = uniqueActions.minOf { it.pos }
        val maxPos = uniqueActions.maxOf { it.pos }
        val range = maxPos - minPos

        if (range <= 0.0) {
            throw BadFileException("Funscript must contain at least 2 different positions")
        }

        // Converts times to seconds
        fun timeAt(index: Int): Double = uniqueActions[index].at / 1000.0
        // Normalises funscript positions from 0.0 to 1.0 (expanding limited range funscripts)
        fun posAt(index: Int): Double = (uniqueActions[index].pos - minPos) / range

        val lastIndex = uniqueActions.lastIndex

        // Populate our TreeMap
        for (i in uniqueActions.indices) {
            val currentTime = timeAt(i)
            val currentPos = posAt(i)

            val velocity = when (i) {
                0 -> {
                    // Forward difference
                    (posAt(1) - currentPos) / (timeAt(1) - currentTime)
                }
                lastIndex -> {
                    // Backward difference
                    (currentPos - posAt(i - 1)) / (currentTime - timeAt(i - 1))
                }
                else -> {
                    // Central difference (Catmull-Rom)
                    (posAt(i + 1) - posAt(i - 1)) / (timeAt(i + 1) - timeAt(i - 1))
                }
            }

            timePositionData[currentTime] = PositionVelocity(currentPos, velocity)
        }
    }

    fun open(uri: Uri, context: Context): Double? {
        readyToPlay = false
        timePositionData.clear()

        val fileDescriptor: ParcelFileDescriptor? = context.contentResolver.openFileDescriptor(uri, "r")
        val fileSize: Long = fileDescriptor?.statSize ?: 0
        fileDescriptor?.close()

        if (fileSize > 20 * 1024 * 1024) {
            throw BadFileException("File is too large (>${fileSize / (1024 * 1024)}MB)")
        }

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val content = inputStream.bufferedReader().use { it.readText() }
            try {
                processFunscript(content)
            } catch (_: SerializationException) {
                throw BadFileException("Funscript decoding failed")
            }
        }

        _displayName.value = uri.getName(context)
        duration = timePositionData.lastKey()
        readyToPlay = true
        return duration
    }

    fun loadFromString(
        funscript: String,
        title: String,
        loop: Boolean = false
    ): Double? {
        readyToPlay = false
        timePositionData.clear()

        try {
            processFunscript(funscript)
        } catch (_: SerializationException) {
            throw BadFileException("Funscript decoding failed")
        }

        _displayName.value = title
        duration = timePositionData.lastKey()
        isRemote = true
        readyToPlay = true
        shouldLoop = loop
        return duration
    }
}