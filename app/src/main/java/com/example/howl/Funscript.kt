package com.example.howl

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class BadFileException(message: String) : Exception(message)

enum class FrequencyAlgorithmType(val displayName: String) {
    POSITION("Position"),
    VARIED("Varied"),
    BLEND("Blend"),
}

enum class AmplitudeAlgorithmType(val displayName: String) {
    DEFAULT("Default"),
    PENETRATIVE("Penetrative"),
}

/**
 * 增加自定义Action.at序列化工具，at设置成double类型，小数部分舍弃，避免faptap等脚本中会莫名出现 .5 的问题
 * 例如: {"at":430397.5,"pos":80}
 *
 * Add a custom Action.at serialization tool, setting the 'at' attribute as a double type and
 * truncating the decimal part to avoid the issue of unexpected .5 values in scripts like faptap.
 * sample: : {"at":430397.5,"pos":80}
 */
object AtTransformer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("At", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeInt(value)
    }


    override fun deserialize(decoder: Decoder): Int {
        return when (val input = decoder) {
            is JsonDecoder -> {
                val element = input.decodeJsonElement()
                when {
                    element is JsonPrimitive && element.isString ->
                        element.content.toIntOrNull() ?: 0

                    element is JsonPrimitive -> element.double.toInt()
                    else -> 0
                }
            }

            else -> decoder.decodeInt()
        }
    }
}

@Serializable
data class Action(
    @Serializable(with = AtTransformer::class)
    val at: Int,
    val pos: Int
)

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
    override var displayName: String = "Funscript"
    override var duration: Double? = null
    override val isFinite: Boolean = true
    override val shouldLoop: Boolean = false
    override var readyToPlay: Boolean = false
    override var isRemote: Boolean = false
    override var remoteLatency: Double = 0.0

    private data class ScaledAction(val time: Double, val pos: Double)

    private val timePositionData = TreeMap<Double, PositionVelocity>()

    private val noiseGenerator = NoiseGenerator()

    private var previousTime: Double = -1.0
    private var previousAmplitude: Double = 0.0

    override fun updateState(currentTime: Double) {}

    private fun getClosestPoints(time: Double): Pair<Pair<Double, PositionVelocity>?, Pair<Double, PositionVelocity>?> {
        val floorEntry = timePositionData.floorEntry(time)
        val ceilingEntry = timePositionData.higherEntry(time)
        val before = floorEntry?.let { Pair(it.key, it.value) }
        val after = ceilingEntry?.let { Pair(it.key, it.value) }
        return Pair(before, after)
    }

    private fun scaleFunscriptVelocity(velocity: Double, exponent: Double = 0.5): Double {
        val movementThreshold = 0.033 // Minimum that counts as movement (1 stroke over 30 seconds)
        val maxSpeed = 5.0 // typical maximum speed of a "stroker" device (5 strokes per second)
        val speed = abs(velocity)

        if (speed < movementThreshold) {
            return 0.0
        }

        val normalizedSpeed = (speed / maxSpeed).coerceIn(0.0..1.0)
        return normalizedSpeed.pow(exponent).coerceIn(0.0..1.0)
    }

    private fun calculatePenetrativeEffect(
        time: Double,
        amplitude: Double,
        position: Double
    ): Pair<Double, Double> {
        val penEffectPower = 0.5
        val penEffectPoint = 0.5 // position below which the effect applies at 100%
        val penEffectTimeSpeed = 6.0
        val penEffectRotationSpeed = 0.05
        val penEffectRadius = 0.2

        if (position >= 1.0) return 0.0 to 0.0

        // Calculate easing factor based on position
        val factor = when {
            position < penEffectPoint -> 1.0
            else -> {
                val t = (position - penEffectPoint) / (1.0 - penEffectPoint)
                (1 - t) * (1 - t)
            }
        }

        val (_, noiseB) = noiseGenerator.getNoise(
            time = time * penEffectTimeSpeed,
            rotation = time * penEffectRotationSpeed,
            radius = penEffectRadius,
            axis = 2,
            shiftResult = true
        )

        val effectAmplitude = noiseB * factor * amplitude * penEffectPower
        return 0.0 to effectAmplitude
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

    private fun limitAmplitudeDrop(
        time: Double,
        amplitude: Double,
        maxAmplitudeDropPerSecond: Double
    ): Double {
        // Limit how much our total amplitude is allowed to reduce by per second.
        // This results in a more pleasing dip at the top and bottom of each interpolated stroker
        // motion rather than immediately going right down to zero. Chained motions feel smoother.
        var newAmplitude = amplitude

        if (previousTime >= 0) {
            val deltaTime = time - previousTime
            if (deltaTime > 0 && deltaTime < 1.0) {
                val maxAllowedDrop = maxAmplitudeDropPerSecond * deltaTime
                if (amplitude < previousAmplitude) {
                    newAmplitude = max(amplitude, previousAmplitude - maxAllowedDrop)
                }
            }
        }

        previousTime = time
        previousAmplitude = newAmplitude
        return newAmplitude
    }

    fun getPositionAndVelocityAtTime(time: Double): Pair<Double, Double> {
        val (before, after) = getClosestPoints(time)
        return when {
            before == null && after == null -> Pair(0.0, 0.0)
            before == null -> Pair(after!!.second.position, 0.0)
            after == null -> Pair(before.second.position, 0.0)
            else -> {
                val (t0, pv0) = before
                val (t1, pv1) = after
                if (t0 == t1 || pv0.position == pv1.position) {
                    Pair(pv0.position, 0.0)
                } else {
                    val (rawPos, rawVel) = hermiteInterpolateWithVelocity(
                        time, t0, pv0.position, pv0.velocity, t1, pv1.position, pv1.velocity
                    )
                    Pair(rawPos.coerceIn(0.0, 1.0), rawVel)
                }
            }
        }
    }

    override fun getPulseAtTime(time: Double): Pulse {
        val advancedControlState = DataRepository.playerAdvancedControlsState.value
        val frequencyAlgorithm = advancedControlState.funscriptFrequencyAlgorithm
        val amplitudeAlgorithm = advancedControlState.funscriptAmplitudeAlgorithm
        val funscriptPositionalEffectStrength =
            advancedControlState.funscriptPositionalEffectStrength
        val funscriptFrequencyTimeOffset =
            if (frequencyAlgorithm == FrequencyAlgorithmType.POSITION) advancedControlState.funscriptFrequencyTimeOffset.toDouble() else 0.0
        val frequencyVarySpeed = advancedControlState.funscriptFrequencyVarySpeed.toDouble()
        val frequencyBlendRatio = advancedControlState.funscriptFrequencyBlendRatio.toDouble()
        val scalingExponent = (1.0 - advancedControlState.funscriptVolume).coerceAtLeast(0.001)

        val (position, velocity) = getPositionAndVelocityAtTime(time)
        val (offsetPosition, _) = getPositionAndVelocityAtTime(time - funscriptFrequencyTimeOffset)

        //Log.d("funscript", "T=$time V=$velocity")

        var amplitude = scaleFunscriptVelocity(velocity, exponent = scalingExponent)
        amplitude = limitAmplitudeDrop(time, amplitude, maxAmplitudeDropPerSecond = 5.0)

        var (amplitudeA, amplitudeB) = when (amplitudeAlgorithm) {
            AmplitudeAlgorithmType.DEFAULT -> calculatePositionalEffect(
                amplitude,
                position,
                funscriptPositionalEffectStrength.toDouble()
            )

            AmplitudeAlgorithmType.PENETRATIVE -> {
                val (posAmpA, posAmpB) = calculatePositionalEffect(
                    amplitude,
                    position,
                    funscriptPositionalEffectStrength.toDouble()
                )
                val (penAmpA, penAmpB) = calculatePenetrativeEffect(time, amplitude, position)
                Pair(min(posAmpA + penAmpA, 1.0), min(posAmpB + penAmpB, 1.0))
            }
        }

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
        }

        return Pulse(
            freqA = freqA.toFloat(),
            freqB = freqB.toFloat(),
            ampA = amplitudeA.toFloat(),
            ampB = amplitudeB.toFloat()
        )
    }

    private fun processFunscript(content: String) {
        val jsonConfig = Json { ignoreUnknownKeys = true }

        val funscript = jsonConfig.decodeFromString<Funscript>(content)
        val positions = funscript.actions.map { it.pos }
        val minPos = positions.minOrNull() ?: 0
        val maxPos = positions.maxOrNull() ?: 100
        val (scaleFactor, offset) = if (minPos != maxPos) {
            val range = (maxPos - minPos).toDouble()
            Pair(1.0 / range, -minPos.toDouble())
        } else {
            Pair(1.0 / 100.0, 0.0)
        }

        val scaledActions = funscript.actions.map { action ->
            val time = action.at / 1000.0
            val scaledPos = ((action.pos.toDouble() + offset) * scaleFactor).coerceIn(0.0, 1.0)
            ScaledAction(time, scaledPos)
        }

        val positionVelocities = scaledActions.mapIndexed { i, current ->
            val prev = if (i > 0) scaledActions[i - 1] else null
            val next = if (i < scaledActions.size - 1) scaledActions[i + 1] else null

            val velocity = when {
                prev == null && next == null -> 0.0
                prev == null -> (next!!.pos - current.pos) / (next.time - current.time)
                next == null -> (current.pos - prev.pos) / (current.time - prev.time)
                else -> {
                    val slopePrev = (current.pos - prev.pos) / (current.time - prev.time)
                    val slopeNext = (next.pos - current.pos) / (next.time - current.time)
                    ((slopePrev + slopeNext) * 0.5)
                }
            }
            PositionVelocity(current.pos, velocity)
        }

        scaledActions.forEachIndexed { index, action ->
            timePositionData[action.time] = positionVelocities[index]
        }
    }

    fun open(uri: Uri, context: Context): Double? {
        readyToPlay = false
        timePositionData.clear()

        val fileDescriptor: ParcelFileDescriptor? =
            context.contentResolver.openFileDescriptor(uri, "r")
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

        displayName = uri.getName(context)
        duration = timePositionData.lastKey()
        readyToPlay = true
        return duration
    }

    fun loadFromString(funscript: String, title: String): Double? {
        readyToPlay = false
        timePositionData.clear()

        try {
            processFunscript(funscript)
        } catch (_: SerializationException) {
            throw BadFileException("Funscript decoding failed")
        }

        displayName = title
        duration = timePositionData.lastKey()
        isRemote = true
        remoteLatency =
            DataRepository.playerAdvancedControlsState.value.funscriptRemoteLatency.toDouble()
        readyToPlay = true
        return duration
    }
}