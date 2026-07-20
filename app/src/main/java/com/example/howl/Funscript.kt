package com.example.howl

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class BadFileException (message: String) : Exception(message)

enum class NormalisationType {
    // FULL_RANGE expands the axis to fill 0.0 to 1.0 (used for L0).
    // BALANCED expands as much as possible while preserving the same balance around the
    // central position (used for the other motion axes).
    FULL_RANGE,
    BALANCED,
    OFF
}

@Serializable
data class Action(val at: Double, val pos: Double)

@Serializable
data class FunscriptAxisData(
    val id: String,
    val actions: List<Action>
)

@Serializable
data class Funscript(
    val actions: List<Action>,
    val axes: List<FunscriptAxisData>? = null
)



data class PositionVelocity(val position: Double, val velocity: Double)

class FunscriptAxis(val id: String, private val timePositionData: TreeMap<Double, PositionVelocity>) {

    companion object {
        fun create(id: String, actions: List<Action>, normalisationType: NormalisationType = NormalisationType.OFF): FunscriptAxis {
            // Filter duplicate timestamps, sort chronologically, clamp all positions to valid range
            val uniqueActions = actions
                .distinctBy { it.at }
                .sortedBy { it.at }
                .map { it.copy(pos = it.pos.coerceIn(0.0, 100.0)) }

            if (uniqueActions.size < 2) {
                throw BadFileException("Funscript axis $id must have at least 2 actions")
            }

            val rawMin = uniqueActions.minOf { it.pos }
            val rawMax = uniqueActions.maxOf { it.pos }

            val minPos: Double
            val maxPos: Double

            when (normalisationType) {
                NormalisationType.FULL_RANGE -> {
                    minPos = rawMin
                    maxPos = rawMax
                }
                NormalisationType.BALANCED -> {
                    val distMin = 50.0 - rawMin
                    val distMax = rawMax - 50.0
                    val maxDist = maxOf(distMin, distMax)
                    minPos = 50.0 - maxDist
                    maxPos = 50.0 + maxDist
                }
                NormalisationType.OFF -> {
                    minPos = 0.0
                    maxPos = 100.0
                }
            }

            val range = maxPos - minPos

            if (normalisationType != NormalisationType.OFF && (rawMin == rawMax || range <= 0.0)) {
                throw BadFileException("Funscript axis $id must contain at least 2 different positions")
            }

            // Converts times to seconds
            fun timeAt(index: Int): Double = uniqueActions[index].at / 1000.0

            // Normalises funscript positions from 0.0 to 1.0, expanding limited range funscripts
            // if normalisationType is not OFF
            fun posAt(index: Int): Double {
                val rawPos = uniqueActions[index].pos
                return if (normalisationType != NormalisationType.OFF) {
                    (rawPos - minPos) / range
                } else {
                    rawPos / 100.0
                }
            }

            val lastIndex = uniqueActions.lastIndex
            val data = TreeMap<Double, PositionVelocity>()

            // Pre-calculate secant slopes (average velocity between points)
            val secants = DoubleArray(lastIndex)
            for (i in 0 until lastIndex) {
                val dt = timeAt(i + 1) - timeAt(i)
                if (dt == 0.0) {
                    secants[i] = 0.0
                } else {
                    secants[i] = (posAt(i + 1) - posAt(i)) / dt
                }
            }

            // Populate our TreeMap using Fritsch-Carlson (Monotone Cubic Interpolation)
            for (i in uniqueActions.indices) {
                val currentTime = timeAt(i)
                val currentPos = posAt(i)

                val velocity = when (i) {
                    0 -> secants[0]
                    lastIndex -> secants[lastIndex - 1]
                    else -> {
                        val mLeft = secants[i - 1]
                        val mRight = secants[i]

                        // If adjacent slopes have different signs, this is a local peak/valley.
                        // Set velocity to 0.0 to flatten the curve and prevent overshoot.
                        // (Also safely handles cases where mLeft or mRight is exactly 0.0)
                        if (mLeft * mRight <= 0.0) {
                            0.0
                        } else {
                            // Fritsch-Carlson Monotonicity Constraint for non-uniform spacing
                            val dtLeft = currentTime - timeAt(i - 1)
                            val dtRight = timeAt(i + 1) - currentTime
                            val common = dtLeft + dtRight

                            // Weighted Harmonic Mean: strictly prevents overshoot while maintaining C1 continuity.
                            3.0 * common / ((common + dtRight) / mLeft + (common + dtLeft) / mRight)
                        }
                    }
                }

                data[currentTime] = PositionVelocity(currentPos, velocity)
            }

            return FunscriptAxis(id, data)
        }

        fun createOrNull(id: String, actions: List<Action>, normalisationType: NormalisationType = NormalisationType.OFF): FunscriptAxis? {
            return try {
                create(id, actions, normalisationType)
            } catch (e: BadFileException) {
                null
            }
        }
    }

    fun getDuration(): Double? {
        return if (timePositionData.isEmpty()) null else timePositionData.lastKey()
    }

    private fun getClosestPoints(time: Double): Pair<Pair<Double, PositionVelocity>?, Pair<Double, PositionVelocity>?> {
        val floorEntry = timePositionData.floorEntry(time)
        val ceilingEntry = timePositionData.higherEntry(time)
        val before = floorEntry?.let { Pair(it.key, it.value) }
        val after = ceilingEntry?.let { Pair(it.key, it.value) }
        return Pair(before, after)
    }

    fun getPositionAtTime(time: Double): Double {
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
}

class FunscriptPulseSource : PulseSource {
    private val _displayName = MutableStateFlow("Funscript")
    override val displayName = _displayName.asStateFlow()
    private val _displayInfo = MutableStateFlow("")
    override val displayInfo = _displayInfo.asStateFlow()
    override var duration: Double? = null
    override val isFinite: Boolean = true
    override var shouldLoop: Boolean = false
    override var readyToPlay: Boolean = false
    override var isRemote: Boolean = false

    private val axes = mutableMapOf<String, FunscriptAxis>()
    val jsonConfig = Json { ignoreUnknownKeys = true }

    companion object {
        // Approximate maximum speed of a stroker device (used for normalisation)
        private const val MAX_SPEED = 5.0
        // Arbitrarily chosen maximum acceleration magnitude (used for normalisation)
        private const val MAX_MAGNITUDE = 80.0
        // Funscript axes that our app is able to utilise
        private val SUPPORTED_AXES = setOf("L0", "L1", "L2", "R0", "R1", "R2")
        // Axes that should be balanced around the central position when normalising
        private val BALANCED_AXES = setOf("L1", "L2", "R0", "R1", "R2")
        // Axis groupings for the energy calculation
        // currently all handled the same anyway, but in theory that could change in future
        private val LINEAR_AXES = listOf("L0", "L1", "L2")
        private val ROTATION_AXES = listOf("R0", "R1", "R2")
        // Backward-looking smoothing window shape: the window extends this many
        // sigmas into the past, and energy is sampled this many times per sigma
        private const val WINDOW_WIDTH_SIGMAS = 3.0
        private const val SAMPLES_PER_SIGMA = 4
    }

    override fun updateState(currentTime: Double) {}
    private fun calculateBaseFrequencies(time: Double, position: Double, amplitude: Double): Pair<Double, Double> {
        /*
        Calculates our base frequencies from the common L0 (up/down) axis that all funscripts
        contain. Our invented algorithm for this primarily uses a blend of position and total energy (amplitude).
        Then adds in a directional shift so that upward and downward strokes have some subtle
        differences in sensation.
        */

        // Get L0 velocity
        val (_, l0Vel, _) = axes["L0"]?.getPositionVelocityAccelerationAtTime(time) ?: Triple(0.0, 0.0, 0.0)
        val normL0Vel = (l0Vel / MAX_SPEED).coerceIn(-1.0, 1.0)
        val absNormL0Vel = abs(normL0Vel)

        // Base frequency (blend of position and total energy/amplitude)
        val coreBase = (position * (1.0 - Prefs.funscriptFreqEnergyProportion.value)) +
                (amplitude * Prefs.funscriptFreqEnergyProportion.value)

        // Directional shift (also incorporates speed, so faster strokes have a larger shift).
        var directionalShift = normL0Vel * Prefs.funscriptDirectionalFreqShift.value
        // alternative cubic idea
        // var directionalShift = normL0Vel * absNormL0Vel * Prefs.funscriptDirectionalFreqShift.value

        // Flip directional shift based on preference
        if (Prefs.funscriptFlipDirectionalFreqShift.value) {
            directionalShift = -directionalShift
        }

        // Channel A (Base) & Channel B (Tip)
        val freqA = coreBase - directionalShift
        val freqB = coreBase + directionalShift

        return Pair(freqA.coerceIn(0.0, 1.0), freqB.coerceIn(0.0, 1.0))
    }

    private fun calculateSpatialFrequencies(time: Double, position: Double, amplitude: Double): Pair<Double, Double> {
        // Adjusts our frequencies in various ways when we are using a multi-axis funscript
        // for a more dynamic experience.

        if (amplitude <= 0.0)
            return Pair(0.0, 0.0)

        // Calculate our base frequencies from L0 using a common algorithm that works for all
        // funscripts.
        var (freqA, freqB) = calculateBaseFrequencies(time, position, amplitude)

        // Tunable constants
        val pitchSensitivity = 0.4
        val rollSensitivity = 0.4
        val twistSensitivity = 0.5
        val impactSensitivity = 0.4

        // Twist (R0): Increases both frequencies based on the speed of the rotation, representing
        // an increase in sensation due to friction.
        axes["R0"]?.let { r0 ->
            val (_, twistVel, _) = r0.getPositionVelocityAccelerationAtTime(time)
            val frictionBuzz = (abs(twistVel) / MAX_SPEED).coerceIn(0.0, 1.0) * twistSensitivity
            freqA += frictionBuzz
            freqB += frictionBuzz
        }

        // Roll (R1): Lateral spread that is symmetrical around the center so that tilting left
        // or right feels the same (which makes sense given our central electrodes).
        // Affects B more than A.
        axes["R1"]?.let { r1 ->
            val rollPos = r1.getPositionAtTime(time)
            val lateralSpread = abs(rollPos - 0.5) * rollSensitivity
            freqA += lateralSpread * 0.2
            freqB -= lateralSpread * 0.8
        }

        // Pitch (R2): give different sensations when bending towards vs. away from the subject,
        // using a frequency spread.
        axes["R2"]?.let { r2 ->
            val pitchPos = r2.getPositionAtTime(time)
            val pitchOffset = (pitchPos - 0.5) * pitchSensitivity
            freqA += pitchOffset
            freqB -= pitchOffset
        }

        // The L1 and L2 axes use an "impact" calculation based on acceleration that reduces
        // both frequencies. There might be better ideas that could work here, mainly I just
        // chose it to be different to the rotational axes effects above.
        // Currently they are less commonly used in funscripts than the rotational axes anyway.
        var impactAccSq = 0.0
        axes["L1"]?.let {
            val (_, _, acc) = it.getPositionVelocityAccelerationAtTime(time)
            impactAccSq += (acc / MAX_MAGNITUDE).pow(2)
        }
        axes["L2"]?.let {
            val (_, _, acc) = it.getPositionVelocityAccelerationAtTime(time)
            impactAccSq += (acc / MAX_MAGNITUDE).pow(2)
        }
        if (impactAccSq > 0.0) {
            val impactDrop = sqrt(impactAccSq).coerceIn(0.0, 1.0) * impactSensitivity
            //Log.d("funscript", "Impact drop: $impactDrop")
            freqA -= impactDrop
            freqB -= impactDrop
        }

        return Pair(freqA.coerceIn(0.0, 1.0), freqB.coerceIn(0.0, 1.0))
    }

    // Instantaneous (unsmoothed) energy at a single point in time: the squared
    // magnitude of the 6D velocity vector across all present axes, with each axis
    // normalised by MAX_SPEED.
    private fun instantaneousEnergy(time: Double): Double {
        var energy = 0.0
        for (id in LINEAR_AXES) {
            axes[id]?.let { axis ->
                val (_, v, _) = axis.getPositionVelocityAccelerationAtTime(time)
                val normV = (abs(v) / MAX_SPEED).coerceIn(0.0..1.0)
                energy += normV * normV
            }
        }
        for (id in ROTATION_AXES) {
            axes[id]?.let { axis ->
                val (_, v, _) = axis.getPositionVelocityAccelerationAtTime(time)
                val normV = (abs(v) / MAX_SPEED).coerceIn(0.0..1.0)
                energy += normV * normV
            }
        }
        return energy
    }

    private fun calculateTotalAmplitude(time: Double): Double {
        val threshold = 0.005

        /*
        Backward-looking (causal) smoothing window.
        Prefs.funscriptSmoothingSigma represents the Gaussian sigma in seconds (default 0.2).
        Smaller = tracks the script tightly but may flutter on slow strokes.
        Larger = smoother and more consistent but more smeared in time.

        The window extends roughly WINDOW_WIDTH_SIGMAS * sigma into the past.
        */
        val sigma = Prefs.funscriptSmoothingSigma.value.toDouble().coerceAtLeast(0.01)
        val step = sigma / SAMPLES_PER_SIGMA
        val totalSteps = (WINDOW_WIDTH_SIGMAS * SAMPLES_PER_SIGMA).toInt()

        // Gaussian-weighted mean of the energy over the backward-looking window
        var weightedSum = 0.0
        var weightSum = 0.0
        for (i in -totalSteps..0) {
            val offset = i * step
            val weight = kotlin.math.exp(-(offset * offset) / (2.0 * sigma * sigma))
            weightedSum += weight * instantaneousEnergy(time + offset)
            weightSum += weight
        }
        val smoothedEnergy = if (weightSum > 0.0) weightedSum / weightSum else 0.0

        // Square root gives the RMS (Root Mean Square) velocity magnitude
        val rawAmp = sqrt(smoothedEnergy).coerceIn(0.0, 1.0)

        if (rawAmp < threshold) return 0.0
        return rawAmp
    }

    override fun getPulseAtTime(time: Double): Pulse {
        val mainAxis = axes["L0"] ?: return Pulse() // Fallback if somehow missing

        val funscriptPositionalEffectStrength = Prefs.funscriptPositionalEffectStrength.value.toDouble()
        // higher "volume" boosts slow movements more, but reduces dynamic range
        val volume = Prefs.funscriptVolume.value.toDouble()
        val scalingExponent = (1.0 - volume).coerceAtLeast(0.001)

        // Calculate total amplitude using the new 3D Vector pipeline
        val rawAmplitude = calculateTotalAmplitude(time)
        val amplitude =  rawAmplitude.pow(scalingExponent).coerceIn(0.0..1.0)

        val position = mainAxis.getPositionAtTime(time)

        val (amplitudeA, amplitudeB) = calculatePositionalEffect(amplitude, position, funscriptPositionalEffectStrength)

        val (freqA, freqB) = calculateSpatialFrequencies(time, position, rawAmplitude)

        return Pulse(
            freqA = freqA.toFloat(),
            freqB = freqB.toFloat(),
            ampA = amplitudeA.toFloat(),
            ampB = amplitudeB.toFloat()
        )
    }

    private fun processFunscript(content: String) {
        val funscript = jsonConfig.decodeFromString<Funscript>(content)
        val normalise = Prefs.funscriptNormaliseAxes.value

        // Main axis L0 must be valid, so we let it throw if it's invalid.
        val l0NormType = if (normalise) NormalisationType.FULL_RANGE else NormalisationType.OFF
        val mainAxis = try {
            FunscriptAxis.create("L0", funscript.actions, l0NormType)
        } catch (e: BadFileException) {
            throw e
        }

        axes["L0"] = mainAxis

        // Process additional axes if they exist
        funscript.axes?.forEach { axisData ->
            // Avoid overwriting the main axis if "L0" somehow appears in the axes array
            if (axisData.id == "L0") {
                HLog.d("Funscript", "Warning: Funscript incorrectly contains an L0 axis in the 'axes' array (not loaded).")
                return@forEach
            }

            if (!SUPPORTED_AXES.contains(axisData.id)) {
                HLog.d("Funscript", "Skipped loading unsupported axis: ${axisData.id}")
                return@forEach
            }

            val normType = if (normalise && axisData.id in BALANCED_AXES) {
                NormalisationType.BALANCED
            } else {
                NormalisationType.OFF
            }

            val axis = FunscriptAxis.createOrNull(axisData.id, axisData.actions, normType)
            if (axis != null) {
                axes[axisData.id] = axis
            }
        }

        // Update display info with axis count and IDs
        val axisIds = axes.keys.sorted()
        _displayInfo.value = "${axes.size} ${if (axes.size == 1) "axis" else "axes"} [${axisIds.joinToString(", ")}]"

        HLog.i("Funscript", "Processed ${axes.size} ${if (axes.size == 1) "axis" else "axes"}.")
    }

    private fun clear() {
        // Clears any previously loaded funscript
        readyToPlay = false
        axes.clear()
        _displayName.value = ""
        _displayInfo.value = ""
    }

    fun open(uri: Uri, context: Context): Double? {
        clear()

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
        duration = axes.values.maxOfOrNull { it.getDuration() ?: 0.0 }
        readyToPlay = true
        return duration
    }

    fun loadFromString(
        funscript: String,
        title: String,
        loop: Boolean = false
    ): Double? {
        clear()

        try {
            processFunscript(funscript)
        } catch (_: SerializationException) {
            throw BadFileException("Funscript decoding failed")
        }

        _displayName.value = title
        duration = axes.values.maxOfOrNull { it.getDuration() ?: 0.0 }
        isRemote = true
        readyToPlay = true
        shouldLoop = loop
        return duration
    }
}