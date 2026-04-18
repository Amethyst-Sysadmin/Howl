package com.example.howl

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

abstract class Activity {
    val manager = ActivityManager()

    open fun initialise() {
        // Default empty implementation
    }
    open fun runSimulation(deltaSimulationTime: Double) {
        manager.update(deltaSimulationTime)
    }
    abstract fun getPulse(): Pulse

    open val permanentSettings: (@Composable () -> Unit)? = null
    open val temporarySettings: (@Composable () -> Unit)? = null
}

class LickActivity : Activity() {
    enum class LickType(val displayName: String) {
        UNIDIRECTIONAL("Unidirectional"),
        BIDIRECTIONAL("Bidirectional"),
    }
    enum class AmpType(val displayName: String) {
        CONSISTENT("Consistent"),
        LICK("Lick"),
        DIP("Dip"),
        RAMP("Ramp"),
        FLICKS("Flicks")
    }
    var waveManager: WaveManager = WaveManager()
    val lickPositionRange = 0.0 .. 1.0

    private val _lickStartPoint = MutableStateFlow(randomInRange(lickPositionRange))
    val lickStartPoint: StateFlow<Double> = _lickStartPoint.asStateFlow()
    private val _lickEndPoint = MutableStateFlow(randomInRange(lickPositionRange))
    val lickEndPoint: StateFlow<Double> = _lickEndPoint.asStateFlow()
    private val _lickType = MutableStateFlow(LickType.entries.random())
    val lickType: StateFlow<LickType> = _lickType.asStateFlow()
    private val _ampType = MutableStateFlow(AmpType.entries.random())
    val ampType: StateFlow<AmpType> = _ampType.asStateFlow()
    private val _manual = MutableStateFlow(false)
    val manual: StateFlow<Boolean> = _manual.asStateFlow()

    override fun initialise() {
        val bidirectional = CyclicalWave(
            WaveShape(
                name = "bidirectional",
                points = listOf(
                    WavePoint(0.0, 0.0),
                    WavePoint(0.5, 1.0),
                ),
                interpolationType = InterpolationType.HERMITE
            )
        )
        val unidirectional = CyclicalWave(
            WaveShape(
                name = "unidirectional",
                points = listOf(
                    WavePoint(0.0, 0.0),
                    WavePoint(1.0 - SMALL_AMOUNT, 1.0),
                ),
                interpolationType = InterpolationType.HERMITE
            )
        )
        val ampConsistent = CyclicalWave(
            WaveShape(
                name = "consistent",
                points = listOf(
                    WavePoint(0.0, 0.85),
                    WavePoint(0.5, 0.95),
                ),
                interpolationType = InterpolationType.HERMITE
            )
        )
        val ampLick = CyclicalWave(
            WaveShape(
                name = "lick",
                points = listOf(
                    WavePoint(0.0, 0.0),
                    WavePoint(0.05, 0.6),
                    WavePoint(0.5, 1.0),
                    WavePoint(0.95, 0.6),
                    WavePoint(1.0 - SMALL_AMOUNT, 0.0),
                ),
                interpolationType = InterpolationType.HERMITE
            )
        )
        val ampDip = CyclicalWave(
            WaveShape(
                name = "dip",
                points = listOf(
                    WavePoint(0.0, 0.9),
                    WavePoint(0.35, 0.8),
                    WavePoint(0.5, 0.6),
                    WavePoint(0.65, 0.8),
                ),
                interpolationType = InterpolationType.HERMITE
            )
        )
        val ampRamp = CyclicalWave(
            WaveShape(
                name = "ramp",
                points = listOf(
                    WavePoint(0.0, 0.4),
                    WavePoint(0.5, 0.7),
                    WavePoint(1.0 - SMALL_AMOUNT, 1.0),
                ),
                interpolationType = InterpolationType.HERMITE
            )
        )
        val ampFlicks = CyclicalWave(
            WaveShape(
                name = "flicks",
                points = listOf(
                    WavePoint(0.0, 0.0),
                    WavePoint(0.0833, 1.0),
                    WavePoint(0.25, 1.0),
                    WavePoint(0.3333, 0.0),
                    WavePoint(0.4166, 1.0),
                    WavePoint(0.5833, 1.0),
                    WavePoint(0.6666, 0.0),
                    WavePoint(0.75, 1.0),
                    WavePoint(0.9166, 1.0),
                ),
                interpolationType = InterpolationType.HERMITE
            )
        )
        waveManager.addWave(bidirectional)
        waveManager.addWave(unidirectional)
        waveManager.addWave(ampConsistent)
        waveManager.addWave(ampLick)
        waveManager.addWave(ampDip)
        waveManager.addWave(ampRamp)
        waveManager.addWave(ampFlicks)
        waveManager.setSpeedJitter(0.5)
        waveManager.setAmplitudeJitter(0.1)
        manager.register(waveManager)
        newLick()
    }

    fun newLick() {
        //Log.d("Activity", "New lick called")
        _lickType.value = LickType.entries.random()
        _ampType.value = AmpType.entries.random()
        _lickStartPoint.value = randomInRange(lickPositionRange)
        _lickEndPoint.value = randomInRange(lickPositionRange)

        val distance = abs(lickEndPoint.value - lickStartPoint.value)
        val maxSpeed = (-3.0 * distance) + 4.5
        val speed = Random.nextDouble(0.3, maxSpeed)
        val desiredTimeSeconds = Random.nextDouble(1.0, 5.0)
        val calculatedReps = (desiredTimeSeconds * speed).toInt().coerceAtLeast(1)

        waveManager.restart()
        waveManager.setSpeed(speed)
        waveManager.stopAfterIterations(calculatedReps) { lickComplete() }
    }

    private fun lickComplete() {
        newLick()
    }

    private fun setManual(manual: Boolean) {
        _manual.value = manual
        if(manual) {
            waveManager.restart()
        }
        else {
            lickComplete()
        }
    }

    override fun getPulse(): Pulse {
        val waveName = when (lickType.value) {
            LickType.UNIDIRECTIONAL -> "unidirectional"
            LickType.BIDIRECTIONAL -> "bidirectional"
        }
        val ampWaveName = when (ampType.value) {
            AmpType.CONSISTENT -> "consistent"
            AmpType.LICK -> "lick"
            AmpType.DIP -> "dip"
            AmpType.RAMP -> "ramp"
            AmpType.FLICKS -> "flicks"
        }
        val position = waveManager.getPosition(waveName)
        val power = waveManager.getPosition(ampWaveName)
        val lickPosition = (lickEndPoint.value - lickStartPoint.value) * position + lickStartPoint.value
        val amp = power * waveManager.currentAmplitude

        val freqA = lickPosition * 0.5 + 0.5
        val freqB = lickPosition * 0.52 + 0.48
        val (ampA, ampB) = calculatePositionalEffect(amp, lickPosition, 1.0)

        return Pulse(
            freqA = freqA.toFloat(),
            freqB = freqB.toFloat(),
            ampA = ampA.toFloat(),
            ampB = ampB.toFloat()
        )
    }

    override val temporarySettings: @Composable () -> Unit = {
        val lickStartPoint by lickStartPoint.collectAsStateWithLifecycle()
        val lickEndPoint by lickEndPoint.collectAsStateWithLifecycle()
        val lickType by lickType.collectAsStateWithLifecycle()
        val ampType by ampType.collectAsStateWithLifecycle()
        val manual by manual.collectAsStateWithLifecycle()

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                //horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                )
                {
                    Text(
                        text = "Manual control",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Switch(
                        checked = manual,
                        onCheckedChange = { enable ->
                            setManual(enable)
                        }
                    )
                }
                SliderWithLabel(
                    label = "Lick start point",
                    value = lickStartPoint.toFloat(),
                    onValueChange = {
                        _lickStartPoint.value = it.toDouble()
                    },
                    onValueChangeFinished = { },
                    valueRange = lickPositionRange.toFloatRange,
                    steps = calculateSliderSteps(lickPositionRange.toFloatRange, 0.01f),
                    valueDisplay = { String.format(Locale.US, "%03.2f", it) },
                    enabled = manual
                )
                SliderWithLabel(
                    label = "Lick end point",
                    value = lickEndPoint.toFloat(),
                    onValueChange = {
                        _lickEndPoint.value = it.toDouble()
                    },
                    onValueChangeFinished = { },
                    valueRange = lickPositionRange.toFloatRange,
                    steps = calculateSliderSteps(lickPositionRange.toFloatRange, 0.01f),
                    valueDisplay = { String.format(Locale.US, "%03.2f", it) },
                    enabled = manual
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Lick type", style = MaterialTheme.typography.labelLarge)
                    OptionPicker(
                        currentValue = lickType,
                        onValueChange = {
                            _lickType.value = it
                        },
                        options = LickType.entries,
                        getText = { it.displayName },
                        enabled = manual
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Amp type", style = MaterialTheme.typography.labelLarge)
                    OptionPicker(
                        currentValue = ampType,
                        onValueChange = {
                            _ampType.value = it
                        },
                        options = AmpType.entries,
                        getText = { it.displayName },
                        enabled = manual
                    )
                }
                NiceSmootherControl(
                    smoother = waveManager.baseSpeed,
                    targetLabel = "Average speed",
                    targetRange = 0.1f..5.0f,
                    rateRange = 0.1f..1.0f,
                    enabled = manual
                )
            }
        }
    }
}

class PenetrationActivity : Activity() {
    var waveManager: WaveManager = WaveManager()
    val penetrationSpeedRange = 0.3..3.0
    val penetrationSpeedChangeRateRange = 0.05..0.3
    val penetrationFeelExponentRange = 0.5..1.0
    val penetrationFeelExponentChangeRateRange = 0.05..0.1
    private val _manual = MutableStateFlow(false)
    val manual: StateFlow<Boolean> = _manual.asStateFlow()

    val speedChange = Timer(
        durationProvider = { randomInRange(1.0..20.0) },
        repeating = true,
        onTrigger = { speedChange() }
    )

    val feelChange = Timer(
        durationProvider = { randomInRange(2.0..10.0) },
        repeating = true,
        onTrigger = { feelChange() }
    )

    private val penetrationFeelExponent = NiceSmoother(randomInRange(penetrationFeelExponentRange))

    override fun initialise() {
        val penetrationWave = CyclicalWave(
            WaveShape(
                name = "penetration",
                points = listOf(
                    WavePoint(0.0, 0.0, 0.0),
                    WavePoint(0.4, 0.95, 0.2),
                    WavePoint(0.5, 0.97, 0.0),
                    WavePoint(0.6, 0.95, -0.2),
                ),
                interpolationType = InterpolationType.HERMITE
            )
        )
        waveManager.addWave(penetrationWave)
        waveManager.setSpeedJitter(0.2)
        waveManager.setAmplitudeJitter(0.1)
        waveManager.setSpeed(0.5)
        speedChange()
        feelChange()
        manager.register(speedChange)
        manager.register(feelChange)
        manager.register(penetrationFeelExponent)
        manager.register(waveManager)
        speedChange.start()
        feelChange.start()
    }

    fun speedChange() {
        Log.d("Activity", "speedChange called")
        val rate = randomInRange(penetrationSpeedChangeRateRange)
        val newSpeed = randomInRange(penetrationSpeedRange)
        waveManager.setTargetSpeed(newSpeed, rate)
    }

    fun feelChange() {
        Log.d("Activity", "feelChange called")
        val rate = randomInRange(penetrationFeelExponentChangeRateRange)
        val newFeel = randomInRange(penetrationFeelExponentRange)
        penetrationFeelExponent.setTarget(newFeel, rate)
    }

    private fun setManual(manual: Boolean) {
        Log.d("Activity", "setManual called")
        _manual.value = manual
        if(manual) {
            speedChange.pause()
            feelChange.pause()
        }
        else {
            speedChange.resume()
            feelChange.resume()
        }
    }

    override fun getPulse(): Pulse {
        val (position, velocity) = waveManager.getPositionAndVelocity("penetration")
        val scaledVelocity = scaleVelocity(velocity, 0.1)

        var ampFactor = (waveManager.currentSpeed - penetrationSpeedRange.start) / (penetrationSpeedRange.endInclusive - penetrationSpeedRange.start)
        ampFactor = 0.8 + ampFactor * 0.2

        val freqBaseA = position * 0.7
        val freqBaseB = scaledVelocity * 0.5 + position * 0.4
        val freqA = calculateFeelAdjustment(freqBaseA.toFloat(), penetrationFeelExponent.value.toFloat())
        val freqB = calculateFeelAdjustment(freqBaseB.toFloat(), penetrationFeelExponent.value.toFloat())

        val ampA = position * ampFactor
        val ampB = (scaledVelocity * 0.6 + position * 0.4) * ampFactor

        return Pulse(
            freqA = freqA,
            freqB = freqB,
            ampA = ampA.toFloat(),
            ampB = ampB.toFloat()
        )
    }

    override val temporarySettings: @Composable () -> Unit = {
        val manual by manual.collectAsStateWithLifecycle()

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                )
                {
                    Text(
                        text = "Manual control",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Switch(
                        checked = manual,
                        onCheckedChange = { enable ->
                            setManual(enable)
                        }
                    )
                }
                NiceSmootherControl(
                    smoother = waveManager.baseSpeed,
                    targetLabel = "Target speed",
                    targetRange = penetrationSpeedRange.toFloatRange,
                    rateRange = penetrationSpeedChangeRateRange.toFloatRange,
                    enabled = manual
                )
                NiceSmootherControl(
                    smoother = penetrationFeelExponent,
                    targetLabel = "Target feel exponent",
                    targetRange = penetrationFeelExponentRange.toFloatRange,
                    rateRange = penetrationFeelExponentChangeRateRange.toFloatRange,
                    enabled = manual
                )
            }
        }
    }
}

class VibroActivity : Activity() {
    val vibeFrequencyRange = 0.0..1.0
    val vibePower = 0.9
    val vibePositionRange = 0.0 .. 1.0
    val vibeMoveSpeedRange = 0.08..0.2

    private val _frequency = MutableStateFlow(randomInRange(vibeFrequencyRange))
    val frequency: StateFlow<Double> = _frequency.asStateFlow()

    private val _manual = MutableStateFlow(false)
    val manual: StateFlow<Boolean> = _manual.asStateFlow()

    private val position = NiceSmoother(randomInRange(vibePositionRange))

    // Variables for pulsing feature
    private var pulseTracker = 0.0
    private var vibeActive = false

    val frequencyChange = Timer(
        durationProvider = { randomInRange(5.0..30.0) },
        repeating = true,
        onTrigger = { frequencyChange() }
    )
    val holdTimer = Timer(
        durationProvider = { randomInRange(1.0..3.0) },
        repeating = false,
        onTrigger = { newTarget() }
    )

    override fun initialise() {
        position.rate = randomInRange(vibeMoveSpeedRange)
        manager.register(frequencyChange)
        manager.register(holdTimer)
        manager.register(position)
        newTarget()
        frequencyChange.start()
    }

    private fun frequencyChange() {
        _frequency.value = randomInRange(vibeFrequencyRange)
    }

    private fun newTarget() {
        val newTarget = randomInRange(vibePositionRange)
        val vibeMoveSpeed = randomInRange(vibeMoveSpeedRange)
        position.rate = vibeMoveSpeed
        position.setTarget(newTarget, onReached = { targetPositionReached() })
    }

    private fun targetPositionReached() {
        if (Random.nextDouble() < Prefs.activityVibeHoldProbability.value) {
            holdTimer.reset()
        }
        else {
            newTarget()
        }
    }

    private fun setManual(manual: Boolean) {
        _manual.value = manual
        holdTimer.cancel()
        if(manual) {
            position.setTarget(position.value)
            frequencyChange.cancel()
        }
        else {
            targetPositionReached()
            frequencyChange.reset()
        }
    }

    override fun runSimulation(deltaSimulationTime: Double) {
        super.runSimulation(deltaSimulationTime)

        val pulseTime = Prefs.activityVibePulseTime.value
        val pulseDutyCycle = Prefs.activityVibePulseDutyCycle.value

        pulseTracker += deltaSimulationTime
        val pulsePhase = pulseTracker % pulseTime
        val offDuration = pulseTime * (1.0 - pulseDutyCycle)
        vibeActive = pulsePhase >= offDuration
    }

    override fun getPulse(): Pulse {
        val freqA = frequency.value
        val freqB = frequency.value
        var ampA = 0.0
        var ampB = 0.0

        if (vibeActive) {
            val (amplitudeA, amplitudeB) = calculatePositionalEffect(vibePower, position.value, 1.0)
            ampA = amplitudeA
            ampB = amplitudeB
        }

        return Pulse(
            freqA = freqA.toFloat(),
            freqB = freqB.toFloat(),
            ampA = ampA.toFloat(),
            ampB = ampB.toFloat()
        )
    }

    override val permanentSettings: @Composable () -> Unit = {
        val pulseTime by Prefs.activityVibePulseTime.collectAsStateWithLifecycle()
        val pulseDutyCycle by Prefs.activityVibePulseDutyCycle.collectAsStateWithLifecycle()
        val holdProbability by Prefs.activityVibeHoldProbability.collectAsStateWithLifecycle()

        SliderWithLabel(
            label = "Hold probability",
            value = holdProbability,
            onValueChange = { Prefs.activityVibeHoldProbability.value = it },
            onValueChangeFinished = { Prefs.activityVibeHoldProbability.save() },
            valueRange = 0.0f..1.0f,
            steps = 19,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) }
        )
        SliderWithLabel(
            label = "Pulse duty cycle",
            value = pulseDutyCycle,
            onValueChange = { Prefs.activityVibePulseDutyCycle.value = it },
            onValueChangeFinished = { Prefs.activityVibePulseDutyCycle.save() },
            valueRange = 0.1f..1.0f,
            steps = 17,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) }
        )
        SliderWithLabel(
            label = "Pulse time",
            value = pulseTime,
            onValueChange = { Prefs.activityVibePulseTime.value = it },
            onValueChangeFinished = { Prefs.activityVibePulseTime.save() },
            valueRange = 0.1f..1.0f,
            steps = 17,
            valueDisplay = { String.format(Locale.US, "%03.2f", it) }
        )
    }

    override val temporarySettings: @Composable () -> Unit = {
        val frequency by frequency.collectAsStateWithLifecycle()
        val manual by manual.collectAsStateWithLifecycle()

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                //horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                )
                {
                    Text(
                        text = "Manual control",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Switch(
                        checked = manual,
                        onCheckedChange = { enable ->
                            setManual(enable)
                        }
                    )
                }
                NiceSmootherControl(
                    smoother = position,
                    targetLabel = "Target position",
                    targetRange = 0.0f..1.0f,
                    rateRange = 0.05f..0.5f,
                    enabled = manual
                )
                SliderWithLabel(
                    label = "Frequency",
                    value = frequency.toFloat(),
                    onValueChange = {
                        _frequency.value = it.toDouble()
                    },
                    onValueChangeFinished = { },
                    valueRange = 0.0f..1.0f,
                    steps = 99,
                    valueDisplay = { String.format(Locale.US, "%03.2f", it) },
                    enabled = manual
                )
            }
        }
    }
}

class MilkerActivity : Activity() {
    val waveManager: WaveManager = WaveManager()
    val wompStartFreq = 0.0
    val wompEndFreq = 0.7
    val wompStartSpeed = 0.3
    val wompEndSpeed = 2.5
    val wompRateChangeSpeedRange = 0.1..0.15
    val buzzFreqARange = 0.0..0.3
    val buzzFreqBRange = 0.7..1.0
    val buzzSpeedRange = 0.4..0.8

    var currentStage = MilkerStage.Womp
    var buzzFreqAStart = 0.75
    var buzzFreqAEnd = 0.75
    var buzzFreqBStart = 0.75
    var buzzFreqBEnd = 0.75
    var reverseWomp = false

    enum class MilkerStage {
        Womp,
        Buzz,
    }

    val buzzTimer = Timer(
        durationProvider = { randomInRange(6.0..12.0) },
        repeating = false,
        onTrigger = { wompStart() }
    )

    override fun initialise() {
        val wompWave = CyclicalWave(
            WaveShape(
                name = "womp",
                points = listOf(
                    WavePoint(0.0, 1.0, 0.0),
                    WavePoint(1.0 - SMALL_AMOUNT, 0.0, 0.0),
                ),
                interpolationType = InterpolationType.HERMITE
            )
        )
        val buzzWave = CyclicalWave(
            WaveShape(
                name = "buzz",
                points = listOf(
                    WavePoint(0.0, 0.0, 0.0),
                    WavePoint(0.5, 1.0, 0.0),
                ),
                interpolationType = InterpolationType.HERMITE
            )
        )

        waveManager.addWave(wompWave)
        waveManager.addWave(buzzWave)
        manager.register(waveManager)
        manager.register(buzzTimer)
        wompStart()
    }

    fun wompStart() {
        currentStage = MilkerStage.Womp
        waveManager.restart()
        val speedChangeRate = randomInRange(wompRateChangeSpeedRange)
        reverseWomp = Random.nextBoolean()
        waveManager.setSpeed(wompStartSpeed)
        waveManager.setTargetSpeed(wompEndSpeed, speedChangeRate) {
            waveManager.stopAtEndOfCycle { buzzStart() }
        }
    }

    fun buzzStart() {
        currentStage = MilkerStage.Buzz
        buzzFreqAStart = randomInRange(buzzFreqARange)
        buzzFreqAEnd = randomInRange(buzzFreqARange)
        buzzFreqBStart = randomInRange(buzzFreqBRange)
        buzzFreqBEnd = randomInRange(buzzFreqBRange)
        val speed = randomInRange(buzzSpeedRange)
        waveManager.restart()
        waveManager.setSpeed(speed)
        buzzTimer.start()
    }

    override fun getPulse(): Pulse {
        var ampA = 0.0
        var ampB = 0.0
        var freqA = 0.0
        var freqB = 0.0

        when (currentStage) {
            MilkerStage.Womp -> {
                val position = waveManager.getPosition("womp")
                val adjustedPosition = if (reverseWomp) 1 - position else position
                val (posAmpA, posAmpB) = calculatePositionalEffect(0.9, adjustedPosition, 1.0)
                ampA = posAmpA
                ampB = posAmpB
                freqA = (adjustedPosition * (wompEndFreq - wompStartFreq)) + wompStartFreq
                freqB = (adjustedPosition * (wompEndFreq - wompStartFreq)) + wompStartFreq
            }
            MilkerStage.Buzz -> {
                val position = waveManager.getPosition("buzz")
                val phase = buzzTimer.progress
                val freqRangeA = buzzFreqAEnd - buzzFreqAStart
                val freqRangeB = buzzFreqBEnd - buzzFreqBStart

                freqA = phase * freqRangeA + buzzFreqAStart
                freqB = phase * freqRangeB + buzzFreqBStart
                ampA = 0.8 + 0.1 * position
                ampB = 0.8 + 0.1 * position
            }
        }

        return Pulse(
            freqA = freqA.toFloat(),
            freqB = freqB.toFloat(),
            ampA = ampA.toFloat(),
            ampB = ampB.toFloat()
        )
    }
}

class ChaosActivity : Activity() {
    val freqRange = 0.0..1.0
    val ampRange = 0.0..1.0
    var cycleTimeCounter = 0.0

    var ampA = 0.0
    var ampB = 0.0
    var freqA = 0.0
    var freqB = 0.0

    override fun initialise() {
        randomise()
    }

    fun randomise() {
        ampA = randomInRange(ampRange)
        ampB = randomInRange(ampRange)
        freqA = randomInRange(freqRange)
        freqB = randomInRange(freqRange)
    }

    override fun runSimulation(deltaSimulationTime: Double) {
        super.runSimulation(deltaSimulationTime)
        val cycleTime = Prefs.activityChaosCycleTime.value
        cycleTimeCounter += deltaSimulationTime
        if(cycleTimeCounter > cycleTime) {
            randomise()
            cycleTimeCounter -= cycleTime
        }
    }

    override fun getPulse(): Pulse {
        return Pulse(
            freqA = freqA.toFloat(),
            freqB = freqB.toFloat(),
            ampA = ampA.toFloat(),
            ampB = ampB.toFloat()
        )
    }

    override val permanentSettings: @Composable () -> Unit = {
        val cycleTime by Prefs.activityChaosCycleTime.collectAsStateWithLifecycle()
        val cycleTimeRange = 0.1f..5.0f

        SliderWithLabel(
            label = "Cycle time",
            value = cycleTime,
            onValueChange = {
                Prefs.activityChaosCycleTime.value = it
            },
            onValueChangeFinished = {
                Prefs.activityChaosCycleTime.save()
            },
            valueRange = cycleTimeRange.start..cycleTimeRange.endInclusive,
            steps = calculateSliderSteps(cycleTimeRange, 0.1f),
            valueDisplay = { String.format(Locale.US, "%03.2f", it) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
    }
}

class LuxuryHJActivity : Activity() {
    val hjWaveManager: WaveManager = WaveManager()
    val bonusWaveManager: WaveManager = WaveManager()
    val hjSpeedRange = 0.3..3.0
    val hjSpeedChangeRateRange = 0.05..0.3
    val bonusSpeedRange = 1.5..5.0
    val startFreq = 0.15
    val endFreq = 0.65
    val bonusStartFreq = 0.8
    val bonusEndFreq = 1.0
    var bonusChannel = 0

    private val _manual = MutableStateFlow(false)
    val manual: StateFlow<Boolean> = _manual.asStateFlow()

    val speedChangeTimer = Timer(
        durationProvider = { randomInRange(1.0..20.0) },
        repeating = true,
        onTrigger = { speedChange() }
    )

    val bonusTimer = Timer(
        durationProvider = { randomInRange(10.0..25.0) },
        repeating = false,
        onTrigger = { }
    )


    override fun initialise() {
        val hjWave = CyclicalWave(
            WaveShape(
                name = "hj",
                points = listOf(
                    WavePoint(0.0, 0.0, 0.0),
                    WavePoint(0.5, 1.0, 0.0),
                ),
                interpolationType = InterpolationType.HERMITE
            )
        )
        val bonusWave = CyclicalWave(
            WaveShape(
                name = "bonus",
                points = listOf(
                    WavePoint(0.0, 0.6, 0.0),
                    WavePoint(0.5, 1.0, 0.0),
                ),
                interpolationType = InterpolationType.HERMITE
            )
        )
        hjWaveManager.addWave(hjWave)
        bonusWaveManager.addWave(bonusWave)

        updateJitter()
        hjWaveManager.setAmplitudeJitterEaseIn(1.0)
        bonusWaveManager.setSpeedJitter(0.5)
        bonusWaveManager.setAmplitudeJitter(0.075)
        bonusWaveManager.setSpeed(randomInRange(bonusSpeedRange))
        hjWaveManager.setSpeed(randomInRange(hjSpeedRange))
        hjWaveManager.baseSpeed.rate = randomInRange(hjSpeedChangeRateRange)
        manager.register(speedChangeTimer)
        manager.register(bonusTimer)
        manager.register(hjWaveManager)
        manager.register(bonusWaveManager)
        speedChangeTimer.start()
    }

    fun updateJitter() {
        val amplitudeJitter = Prefs.activityLuxuryHJAmplitudeJitter.value.toDouble()
        val timingJitter = Prefs.activityLuxuryHJTimingJitter.value.toDouble()

        hjWaveManager.setSpeedJitter(timingJitter)
        hjWaveManager.setAmplitudeJitter(amplitudeJitter)
    }

    fun speedChange() {
        val newSpeed = randomInRange(hjSpeedRange)
        val changeRate = randomInRange(hjSpeedChangeRateRange)
        hjWaveManager.setTargetSpeed(newSpeed, changeRate)
    }

    fun startBonus(channel: Int = Random.nextInt(2)) {
        bonusWaveManager.setSpeed(randomInRange(bonusSpeedRange))
        bonusChannel = channel
        bonusTimer.start()
    }

    private fun setManual(manual: Boolean) {
        _manual.value = manual
        if(manual) {
            speedChangeTimer.pause()
            bonusTimer.cancel()
        }
        else {
            speedChangeTimer.resume()
        }
    }

    override fun runSimulation(deltaSimulationTime: Double) {
        super.runSimulation(deltaSimulationTime)

        val bonusProbability = Prefs.activityLuxuryHJBonusProbability.value

        if (!bonusTimer.isRunning && !_manual.value && Random.nextDouble() < (bonusProbability * deltaSimulationTime) / 60.0)
            startBonus()
    }

    override fun getPulse(): Pulse {
        val position = hjWaveManager.getPosition("hj")
        val baseAmp = hjWaveManager.currentAmplitude
        //Log.d("Activity", "baseAmp=$baseAmp")
        var (ampA, ampB) = calculatePositionalEffect(baseAmp, position, 1.0)
        var freqA = ((position * (endFreq - startFreq)) + startFreq) * 0.98
        var freqB = (position * (endFreq - startFreq)) + startFreq

        if (bonusTimer.isRunning) {
            val bonusWeight = 0.7
            val ampBonus = bonusWaveManager.getPosition("bonus")
            val freqBonus = (ampBonus * (bonusEndFreq - bonusStartFreq)) + bonusStartFreq

            if(bonusChannel == 0) {
                ampA = ampBonus * bonusWeight + (ampA * (1.0 - bonusWeight))
                freqA = freqBonus * bonusWeight + (freqA * (1.0 - bonusWeight))
            }
            else if(bonusChannel == 1) {
                ampB = ampBonus * bonusWeight + (ampB * (1.0 - bonusWeight))
                freqB = freqBonus * bonusWeight + (freqB * (1.0 - bonusWeight))
            }
        }

        return Pulse(
            freqA = freqA.toFloat(),
            freqB = freqB.toFloat(),
            ampA = ampA.toFloat(),
            ampB = ampB.toFloat()
        )
    }

    override val permanentSettings: @Composable () -> Unit = {
        val bonusProbability by Prefs.activityLuxuryHJBonusProbability.collectAsStateWithLifecycle()
        val bonusProbabilityRange = 0.0f..3.0f
        val amplitudeJitter by Prefs.activityLuxuryHJAmplitudeJitter.collectAsStateWithLifecycle()
        val timingJitter by Prefs.activityLuxuryHJTimingJitter.collectAsStateWithLifecycle()
        val jitterRange = 0.0f..0.5f

        SliderWithLabel(
            label = "Bonus pattern probability",
            value = bonusProbability,
            onValueChange = { Prefs.activityLuxuryHJBonusProbability.value = it },
            onValueChangeFinished = { Prefs.activityLuxuryHJBonusProbability.save() },
            valueRange = bonusProbabilityRange,
            steps = calculateSliderSteps(bonusProbabilityRange, 0.1f),
            valueDisplay = { String.format(Locale.US, "%03.2f", it) }
        )
        SliderWithLabel(
            label = "Amplitude jitter",
            value = amplitudeJitter,
            onValueChange = {
                Prefs.activityLuxuryHJAmplitudeJitter.value = it
                updateJitter()
                            },
            onValueChangeFinished = { Prefs.activityLuxuryHJAmplitudeJitter.save() },
            valueRange = jitterRange,
            steps = calculateSliderSteps(jitterRange, 0.05f),
            valueDisplay = { String.format(Locale.US, "%03.2f", it) }
        )
        SliderWithLabel(
            label = "Timing jitter",
            value = timingJitter,
            onValueChange = {
                Prefs.activityLuxuryHJTimingJitter.value = it
                updateJitter()
            },
            onValueChangeFinished = { Prefs.activityLuxuryHJTimingJitter.save() },
            valueRange = jitterRange,
            steps = calculateSliderSteps(jitterRange, 0.05f),
            valueDisplay = { String.format(Locale.US, "%03.2f", it) }
        )
    }

    override val temporarySettings: @Composable () -> Unit = {
        val manual by manual.collectAsStateWithLifecycle()

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                )
                {
                    Text(
                        text = "Manual control",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Switch(
                        checked = manual,
                        onCheckedChange = { enable ->
                            setManual(enable)
                        }
                    )
                }
                NiceSmootherControl(
                    smoother = hjWaveManager.baseSpeed,
                    targetLabel = "Target stroke speed",
                    targetRange = hjSpeedRange.toFloatRange,
                    rateRange = hjSpeedChangeRateRange.toFloatRange,
                    enabled = manual
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { startBonus(0) },
                        modifier = Modifier.weight(1f),
                        enabled = manual
                    ) {
                        Text("Bonus A")
                    }
                    Button(
                        onClick = { startBonus(1) },
                        modifier = Modifier.weight(1f),
                        enabled = manual
                    ) {
                        Text("Bonus B")
                    }
                }
            }
        }
    }
}

class OppositesActivity : Activity() {
    val ampRange = 0.0..1.0
    val freqRange = 0.0..1.0
    val overallSpeedRange = 0.5..3.0
    val overallSpeedChangeRateRange = 0.2..0.5
    val baseChangeRateRange = 0.15..0.4

    private val ampA = NiceSmoother(randomInRange(ampRange))
    private val freqA = NiceSmoother(randomInRange(freqRange))
    private val overallSpeed = NiceSmoother(randomInRange(overallSpeedRange), overallSpeedRange)

    val speedChangeTimer = Timer(
        durationProvider = { randomInRange(10.0..20.0) },
        repeating = true,
        onTrigger = { speedChange() }
    )

    override fun initialise() {
        newRandomAmplitudeTarget(ampA)
        newRandomFrequencyTarget(freqA)
        speedChange()
        speedChangeTimer.start()
        manager.register(ampA)
        manager.register(freqA)
        manager.register(overallSpeed)
    }

    private fun speedChange() {
        val newSpeed = randomInRange(overallSpeedRange)
        val changeRate = randomInRange(overallSpeedChangeRateRange)
        overallSpeed.setTarget(newSpeed, changeRate)
    }

    private fun newRandomFrequencyTarget(freq: NiceSmoother) {
        val targetFreq = randomInRange(freqRange)
        val changeRate = randomInRange(baseChangeRateRange) * overallSpeed.value
        freq.setTarget(
            target = targetFreq,
            rate = changeRate,
            onReached = { newRandomFrequencyTarget(freq) }
        )
    }

    private fun newRandomAmplitudeTarget(amp: NiceSmoother) {
        val targetAmp = randomInRange(ampRange)
        val changeRate = randomInRange(baseChangeRateRange) * overallSpeed.value
        amp.setTarget(
            target = targetAmp,
            rate = changeRate,
            onReached = { newRandomAmplitudeTarget(amp) }
        )
    }

    override fun getPulse(): Pulse {
        val ampB = 1.0 - ampA.value
        val freqB = 1.0 - freqA.value
        return Pulse(
            freqA = freqA.value.toFloat(),
            freqB = freqB.toFloat(),
            ampA = ampA.value.toFloat(),
            ampB = ampB.toFloat()
        )
    }
}

class Calibration1Activity : Activity() {
    val calibrationWaveManager: WaveManager = WaveManager()
    val waveSpeed = 0.25
    val waveFrequency = 0.5
    val wavePower = 0.9

    override fun initialise() {
        val calibrationWave = CyclicalWave(
            WaveShape(
                name = "calibration",
                points = listOf(
                    WavePoint(0.0, 0.0, 0.0),
                    WavePoint(0.5, 1.0, 0.0),
                ),
                interpolationType = InterpolationType.HERMITE
            )
        )
        calibrationWaveManager.addWave(calibrationWave)
        calibrationWaveManager.setSpeed(waveSpeed)
        manager.register(calibrationWaveManager)
    }

    override fun getPulse(): Pulse {
        val position = calibrationWaveManager.getPosition("calibration")
        val (ampA, ampB) = calculatePositionalEffect(wavePower, position, 1.0)

        return Pulse(
            freqA = waveFrequency.toFloat(),
            freqB = waveFrequency.toFloat(),
            ampA = ampA.toFloat(),
            ampB = ampB.toFloat()
        )
    }
}

class Calibration2Activity : Activity() {
    val calibrationWaveManager: WaveManager = WaveManager()
    val waveSpeed = 0.25
    val wavePower = 0.9
    var currentPhase: Phase = Phase.CHANNEL_A

    val phaseTimer = Timer(
        duration = 16.0,
        repeating = true,
        onTrigger = { nextPhase() }
    )

    enum class Phase {
        CHANNEL_A,
        CHANNEL_B,
        BOTH,
    }

    override fun initialise() {
        val calibrationWave = CyclicalWave(
            WaveShape(
                name = "calibration",
                points = listOf(
                    WavePoint(0.0, 0.0, 0.0),
                    WavePoint(0.5, 1.0, 0.0),
                ),
                interpolationType = InterpolationType.HERMITE
            )
        )
        calibrationWaveManager.addWave(calibrationWave)
        calibrationWaveManager.setSpeed(waveSpeed)
        manager.register(phaseTimer)
        manager.register(calibrationWaveManager)
    }

    fun nextPhase() {
        currentPhase = currentPhase.next()
    }

    override fun getPulse(): Pulse {
        val frequency = calibrationWaveManager.getPosition("calibration")
        val ampA = if (currentPhase == Phase.CHANNEL_B) 0.0 else wavePower
        val ampB = if (currentPhase == Phase.CHANNEL_A) 0.0 else wavePower

        return Pulse(
            freqA = frequency.toFloat(),
            freqB = frequency.toFloat(),
            ampA = ampA.toFloat(),
            ampB = ampB.toFloat()
        )
    }
}

class BJActivity : Activity() {
    val waveManager: WaveManager = WaveManager()

    val lickFrequencyRange = 0.8..1.0
    val BJSpeedRange = 0.2..1.2
    val BJSpeedChangeRateRange = 0.03..0.2
    val fullLickSpeedRange = 0.3..1.0
    val tipLickSpeedRange = 0.5..3.0

    enum class BJStage(val displayName: String) {
        FullLick("Full licks"),
        TipLick("Tip licks"),
        Suck("Suck"),
        Deepthroat("Deepthroat"),
    }

    val deepthroatFrequencyConverter = CyclicalWave(
        WaveShape(
            name = "deepthroatFrequencyConverter",
            points = listOf(
                WavePoint(0.0, 1.0),
                WavePoint(0.7, 0.0),
                WavePoint(1.0 - SMALL_AMOUNT, 0.3),
            ),
            interpolationType = InterpolationType.HERMITE
        )
    )

    private val _manual = MutableStateFlow(false)
    val manual: StateFlow<Boolean> = _manual.asStateFlow()

    private val _currentStage = MutableStateFlow(BJStage.entries.random())
    val currentStage: StateFlow<BJStage> = _currentStage.asStateFlow()

    val primaryStageTimer = Timer(
        durationProvider = { randomInRange(20.0..60.0) },
        repeating = false,
        onTrigger = { waveManager.stopAtEndOfCycle { nextStage() } }
    )
    val secondaryStageTimer = Timer(
        durationProvider = { randomInRange(6.0..20.0) },
        repeating = false,
        onTrigger = { waveManager.stopAtEndOfCycle { nextStage() } }
    )
    val speedChangeTimer = Timer(
        durationProvider = { randomInRange(1.0..20.0) },
        repeating = true,
        onTrigger = { speedChange() }
    )

    override fun initialise() {
        val positionWave = CyclicalWave(
            WaveShape(
                name = "position",
                points = listOf(
                    WavePoint(0.0, 0.0, 0.0),
                    WavePoint(0.35, 1.0, 0.0),
                ),
                interpolationType = InterpolationType.HERMITE
            )
        )
        val bidirectional = CyclicalWave(
            WaveShape(
                name = "bidirectional",
                points = listOf(
                    WavePoint(0.0, 0.0, 0.0),
                    WavePoint(0.5, 1.0, 0.0),
                ),
                interpolationType = InterpolationType.HERMITE
            )
        )
        val unidirectional = CyclicalWave(
            WaveShape(
                name = "unidirectional",
                points = listOf(
                    WavePoint(0.0, 0.0, 0.0),
                    WavePoint(1.0 - SMALL_AMOUNT, 1.0, 0.0),
                ),
                interpolationType = InterpolationType.HERMITE
            )
        )
        waveManager.addWave(positionWave)
        waveManager.addWave(bidirectional)
        waveManager.addWave(unidirectional)
        manager.register(waveManager)
        manager.register(speedChangeTimer)
        manager.register(primaryStageTimer)
        manager.register(secondaryStageTimer)
        speedChangeTimer.start()
        setStage(_currentStage.value)
    }

    fun speedChange() {
        if (_currentStage.value == BJStage.FullLick || _currentStage.value == BJStage.TipLick)
            return
        val newSpeed = randomInRange(BJSpeedRange)
        val changeRate = randomInRange(BJSpeedChangeRateRange)
        waveManager.setTargetSpeed(newSpeed, changeRate)
    }

    fun setStage(stage: BJStage, manual: Boolean = false) {
        _currentStage.value = stage
        waveManager.restart()
        when(_currentStage.value) {
            BJStage.FullLick -> {
                waveManager.setSpeedJitter(0.4)
                waveManager.setAmplitudeJitter(0.15)
                if(!manual)
                    waveManager.setSpeed(randomInRange(fullLickSpeedRange))
            }
            BJStage.TipLick -> {
                waveManager.setSpeedJitter(0.4)
                waveManager.setAmplitudeJitter(0.15)
                if(!manual)
                    waveManager.setSpeed(randomInRange(tipLickSpeedRange))
            }
            BJStage.Suck -> {
                waveManager.setSpeedJitter(0.2)
                waveManager.setAmplitudeJitter(0.1)
                if(!manual)
                    waveManager.setSpeed(randomInRange(BJSpeedRange))
            }
            BJStage.Deepthroat -> {
                waveManager.setSpeedJitter(0.2)
                waveManager.setAmplitudeJitter(0.1)
                if(!manual)
                    waveManager.setSpeed(randomInRange(BJSpeedRange))
            }
        }

        if (!manual) {
            waveManager.baseSpeed.rate = randomInRange(BJSpeedChangeRateRange)

            when(_currentStage.value) {
                BJStage.Deepthroat, BJStage.Suck -> primaryStageTimer.reset()
                BJStage.FullLick, BJStage.TipLick -> secondaryStageTimer.reset()
            }
        }
    }

    fun nextStage() {
        val nextStage = BJStage.entries.filter { it != currentStage.value }.random()
        setStage(nextStage, manual = false)
    }

    private fun setManual(manual: Boolean) {
        _manual.value = manual
        if(manual) {
            speedChangeTimer.pause()
            primaryStageTimer.cancel()
            secondaryStageTimer.cancel()
        }
        else {
            speedChangeTimer.resume()
            nextStage()
        }
    }

    override fun getPulse(): Pulse {
        var ampA = 0.0
        var ampB = 0.0
        var freqA = 0.0
        var freqB = 0.0

        when(_currentStage.value) {
            BJStage.FullLick -> {
                val (position, velocity) = waveManager.getPositionAndVelocity("unidirectional")
                val scaledVelocity = scaleVelocity(velocity, 0.1)
                val amplitudes = calculatePositionalEffect(scaledVelocity, position, 1.0)
                ampA = amplitudes.first
                ampB = amplitudes.second

                freqA = position.scaleBetween(lickFrequencyRange) - 0.1
                freqB = position.scaleBetween(lickFrequencyRange)
            }
            BJStage.TipLick -> {
                val (position, velocity) = waveManager.getPositionAndVelocity("bidirectional")
                val lickPosition = position.scaleBetween(0.6..1.0)
                val scaledVelocity = scaleVelocity(velocity, 0.1)
                val amplitudes = calculatePositionalEffect(scaledVelocity, lickPosition, 1.0)
                ampA = amplitudes.first
                ampB = amplitudes.second

                freqA = position.scaleBetween(lickFrequencyRange) - 0.1
                freqB = position.scaleBetween(lickFrequencyRange)
            }
            BJStage.Suck -> {
                val position = waveManager.getPosition("position")
                val baseAmp = waveManager.currentAmplitude
                val amplitudes = calculateEngulfEffect(baseAmp, position, 0.7, 0.4)
                ampA = amplitudes.first
                ampB = amplitudes.second

                freqA = lerp(0.7, 0.3, smoothstep(position))
                freqB = lerp(0.9, 0.3, smoothstep(position))
            }
            BJStage.Deepthroat -> {
                val position = waveManager.getPosition("position")
                val baseAmp = waveManager.currentAmplitude
                val amplitudes = calculateEngulfEffect(baseAmp, position, 0.8, 0.3)
                ampA = amplitudes.first
                ampB = amplitudes.second

                freqA = position
                freqB = deepthroatFrequencyConverter.getPosition(position)
            }
        }

        return Pulse(
            freqA = freqA.toFloat(),
            freqB = freqB.toFloat(),
            ampA = ampA.toFloat(),
            ampB = ampB.toFloat()
        )
    }

    override val temporarySettings: @Composable () -> Unit = {
        val currentStage by currentStage.collectAsStateWithLifecycle()
        val manual by manual.collectAsStateWithLifecycle()

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                //horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                )
                {
                    Text(
                        text = "Manual control",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Switch(
                        checked = manual,
                        onCheckedChange = { enable ->
                            setManual(enable)
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Stage", style = MaterialTheme.typography.labelLarge)
                    OptionPicker(
                        currentValue = currentStage,
                        onValueChange = {
                            setStage(it, manual = true)
                        },
                        options = BJStage.entries,
                        getText = { it.displayName },
                        enabled = manual
                    )
                }
                NiceSmootherControl(
                    smoother = waveManager.baseSpeed,
                    targetLabel = "Target speed",
                    targetRange = 0.1f..3.0f,
                    rateRange = 0.03f..0.3f,
                    enabled = manual
                )
            }
        }
    }
}

class FastSlowActivity : Activity() {
    val waveManager: WaveManager = WaveManager()
    val waveManager2: WaveManager = WaveManager()

    var accelerating = false
    var switch = Random.nextBoolean()
    var freqSwitch = Random.nextBoolean()
    val switchProbability = 0.1
    val waveShapeChangeProbability = 0.2
    val minSpeed = 0.15
    val maxSpeed = 5.0
    val speedChangeRateRange =  0.1..0.3
    val frequencyRange = 0.0..1.0

    val possibleWaves: List<CyclicalWave> = listOf(
        CyclicalWave(
            WaveShape(
                name = "sawtooth",
                points = listOf(
                    WavePoint(0.0, 0.0, 0.0),
                    WavePoint(1.0 - SMALL_AMOUNT, 0.9, 0.0),
                ),
                interpolationType = InterpolationType.LINEAR
            )
        ),
        CyclicalWave(
            WaveShape(
                name = "reverseSawtooth",
                points = listOf(
                    WavePoint(0.0, 0.9, 0.0),
                    WavePoint(1.0 - SMALL_AMOUNT, 0.0, 0.0),
                ),
                interpolationType = InterpolationType.LINEAR
            )
        ),
        CyclicalWave(
            WaveShape(
                name = "hermiteSawtooth",
                points = listOf(
                    WavePoint(0.0, 0.0, 0.0),
                    WavePoint(1.0 - SMALL_AMOUNT, 0.9, 0.0),
                ),
                interpolationType = InterpolationType.HERMITE
            )
        ),
        CyclicalWave(
            WaveShape(
                name = "hermiteReverseSawtooth",
                points = listOf(
                    WavePoint(0.0, 0.9, 0.0),
                    WavePoint(1.0 - SMALL_AMOUNT, 0.0, 0.0),
                ),
                interpolationType = InterpolationType.HERMITE
            )
        ),
    )
    var currentWaveTypeA = possibleWaves.first()
    var currentWaveTypeB = possibleWaves.first()

    override fun initialise() {
        possibleWaves.forEach {
            waveManager.addWave(it)
            waveManager2.addWave(it)
        }
        manager.register(waveManager)
        manager.register(waveManager2)
        nextIteration()
    }

    fun nextIteration() {
        accelerating = !accelerating
        val startSpeed = if (accelerating) minSpeed else maxSpeed
        val targetSpeed = if (accelerating) maxSpeed else minSpeed
        val speedChangeRate = randomInRange(speedChangeRateRange)
        waveManager.setSpeed(startSpeed)
        waveManager.setTargetSpeed(targetSpeed, speedChangeRate, { nextIteration() } )
        waveManager2.setSpeed(targetSpeed)
        waveManager2.setTargetSpeed(startSpeed, speedChangeRate)

        if (Random.nextDouble() < waveShapeChangeProbability)
            currentWaveTypeA = possibleWaves.random()
        if (Random.nextDouble() < waveShapeChangeProbability)
            currentWaveTypeB = possibleWaves.random()
        if (Random.nextDouble() < switchProbability)
            switch = !switch
        if (Random.nextDouble() < switchProbability)
            freqSwitch = !freqSwitch
    }

    override fun getPulse(): Pulse {
        val phase = (waveManager.currentSpeed - minSpeed) / (maxSpeed - minSpeed)
        val invPhase = 1.0 - phase
        var ampA = waveManager2.getPosition(currentWaveTypeA.name)
        var ampB = waveManager.getPosition(currentWaveTypeB.name)

        var freqA = invPhase.scaleBetween(frequencyRange)
        var freqB = phase.scaleBetween(frequencyRange)

        if(switch)
            ampA = ampB.also { ampB = ampA }
        if(freqSwitch)
            freqA = freqB.also { freqB = freqA }

        return Pulse(
            freqA = freqA.toFloat(),
            freqB = freqB.toFloat(),
            ampA = ampA.toFloat(),
            ampB = ampB.toFloat()
        )
    }
}

enum class SimplexPreset(val displayName: String) {
    STANDARD("Standard"),
    PRO("Pro"),
    TURBO("Turbo")
}

class SimplexActivity : Activity() {
    val noiseGenerator = NoiseGenerator()
    var elapsedTime = 0.0
    var phaseTime = 0.0
    var phaseRotation = 0.0

    var ampTimeSpeedRange: ClosedRange<Double> = 0.2..4.0
    var ampRotationSpeedRange: ClosedRange<Double> = 0.0..PI * 0.2
    var changeRateRange: ClosedRange<Double> = 0.1..0.5

    var ampRadius: Double = 0.3
    var freqRadius: Double = 0.2
    val freqTimeSpeed: Double = 0.2
    val freqRotationSpeed: Double = 0.1

    var ampTimeSpeed = NiceSmoother(randomInRange(ampTimeSpeedRange), 0.0..PI * 10.0)
    var ampRotationSpeed = NiceSmoother(randomInRange(ampRotationSpeedRange), 0.0..PI * 10.0)

    val ampTimeSpeedChangeTimer = Timer(
        durationProvider = { randomInRange(10.0..50.0) },
        repeating = true,
        onTrigger = { ampTimeSpeedChange() }
    )

    val ampRotationSpeedChangeTimer = Timer(
        durationProvider = { randomInRange(10.0..50.0) },
        repeating = true,
        onTrigger = { ampRotationSpeedChange() }
    )

    override fun initialise() {
        super.initialise()
        presetChanged(Prefs.activitySimplexPreset.value)
        manager.register(ampTimeSpeed)
        manager.register(ampRotationSpeed)
        manager.register(ampTimeSpeedChangeTimer)
        manager.register(ampRotationSpeedChangeTimer)
        ampTimeSpeedChangeTimer.start()
        ampRotationSpeedChangeTimer.start()
    }

    private fun presetChanged(preset: SimplexPreset) {
        when (preset) {
            SimplexPreset.STANDARD -> {
                ampTimeSpeedRange = 0.2..4.0
                ampRotationSpeedRange = 0.0..PI * 0.2
                changeRateRange = 0.1..0.5
                ampRadius = 0.3
                freqRadius = 0.2
            }
            SimplexPreset.PRO -> {
                ampTimeSpeedRange = 0.2..0.8
                ampRotationSpeedRange = PI * 0.5..PI * 4.0
                changeRateRange = 0.2..0.5
                ampRadius = 0.4
                freqRadius = 0.3
            }
            SimplexPreset.TURBO -> {
                ampTimeSpeedRange = 0.1..0.5
                ampRotationSpeedRange = PI * 3..PI * 6.0
                changeRateRange = 0.2..0.5
                ampRadius = 0.6
                freqRadius = 0.3
            }
        }
        ampTimeSpeed.setImmediately(randomInRange(ampTimeSpeedRange))
        ampRotationSpeed.setImmediately(randomInRange(ampRotationSpeedRange))
    }

    private fun ampTimeSpeedChange() {
        ampTimeSpeed.setTarget(randomInRange(ampTimeSpeedRange), randomInRange(changeRateRange))
    }

    private fun ampRotationSpeedChange() {
        ampRotationSpeed.setTarget(randomInRange(ampRotationSpeedRange), randomInRange(changeRateRange))
    }

    override fun runSimulation(deltaSimulationTime: Double) {
        super.runSimulation(deltaSimulationTime)
        elapsedTime += deltaSimulationTime
        phaseTime += ampTimeSpeed.value * deltaSimulationTime
        phaseRotation += ampRotationSpeed.value * deltaSimulationTime
    }

    override fun getPulse(): Pulse {
        val (ampA, ampB) = noiseGenerator.getNoise(
            time = phaseTime,
            rotation = phaseRotation,
            radius = ampRadius,
            axis = 2,
            shiftResult = true
        )

        val (freqA, freqB) = noiseGenerator.getNoise(
            time = elapsedTime * freqTimeSpeed,
            rotation = elapsedTime * freqRotationSpeed,
            radius = freqRadius,
            axis = 1,
            shiftResult = true
        )

        return Pulse(
            freqA = freqA.toFloat(),
            freqB = freqB.toFloat(),
            ampA = ampA.toFloat(),
            ampB = ampB.toFloat()
        )
    }

    override val permanentSettings: @Composable () -> Unit = {
        val preset by Prefs.activitySimplexPreset.collectAsStateWithLifecycle()

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Preset", style = MaterialTheme.typography.labelLarge)
            OptionPicker(
                currentValue = preset,
                onValueChange = {
                    Prefs.activitySimplexPreset.value = it
                    Prefs.activitySimplexPreset.save()
                    presetChanged(it)
                },
                options = SimplexPreset.entries,
                getText = { it.displayName }
            )
        }
    }
}

class RelentlessActivity : Activity() {
    val waveManager: WaveManager = WaveManager()
    val speedRange = 0.2..0.4
    val speedBias = 2.5
    val frequencyRange = 0.0..1.0
    val wavePowerRange = 0.8..0.95
    val repeatOptions = listOf(1, 2, 3, 4)
    var currentSpeed = randomInRange(speedRange, speedBias)
    var currentFrequencyA = randomInRange(frequencyRange)
    var currentFrequencyB = randomInRange(frequencyRange)
    var swapChannels = Random.nextBoolean()

    val iterationTimer = Timer(
        durationProvider = { randomInRange(1.0..30.0) },
        repeating = true,
        onTrigger = { waveManager.stopAtEndOfCycle { nextIteration() } }
    )

    override fun initialise() {
        manager.register(waveManager)
        manager.register(iterationTimer)
        iterationTimer.start()
        nextIteration()
    }

    fun createRandomWaveShape(): WaveShape {
        val hasHold = Random.nextDouble() < 0.3 // chance of having a hold period
        val splitAttack = Random.nextDouble() < 0.5 // chance to split attack into two segments
        val splitDecay = Random.nextDouble() < 0.5 // chance to split decay into two segments
        val points = mutableListOf<WavePoint>()
        val peak = randomInRange(wavePowerRange)

        // Calculate phase weights
        val attackWeight = randomInRange(0.5..3.0)
        val decayWeight = randomInRange(0.5..3.0)
        val holdWeight = if (hasHold) randomInRange(0.25..1.5) else 0.0
        val totalWeight = attackWeight + decayWeight + holdWeight
        val attackProportion = attackWeight / totalWeight
        val decayProportion = decayWeight / totalWeight
        val holdProportion = holdWeight / totalWeight

        var currentX = 0.0
        // Start point
        points.add(WavePoint(currentX, 0.0))
        // Attack phase
        if (splitAttack) {
            val splitRatio = randomInRange(0.3..0.7)
            val midX = currentX + attackProportion * splitRatio
            val midY = peak * randomInRange(0.2..0.8)
            points.add(WavePoint(midX, midY))
        }
        currentX += attackProportion
        points.add(WavePoint(currentX, peak))
        // Hold phase (if any)
        if (hasHold) {
            currentX += holdProportion
            points.add(WavePoint(currentX, peak))
        }
        // Decay phase
        if (splitDecay) {
            val splitRatio = randomInRange(0.3..0.7)
            val midX = currentX + decayProportion  * splitRatio
            val midY = peak * randomInRange(0.2..0.8)
            points.add(WavePoint(midX, midY))
        }
        // We do not need to explicitly specify the final section of the decay since our wave shapes
        // are assumed to be cyclical and will automatically interpolate back to the starting point

        return WaveShape(
            name = "randomWave",
            points = points,
            interpolationType = InterpolationType.HERMITE
        )
    }

    fun nextIteration() {
        currentSpeed = randomInRange(speedRange, speedBias)
        currentFrequencyA = randomInRange(frequencyRange)
        currentFrequencyB = randomInRange(frequencyRange)
        swapChannels = Random.nextBoolean()

        val longWave = CyclicalWave(createRandomWaveShape())
        val repeats = repeatOptions.random()
        val shortWave = longWave.createRepeatedWave(repeats, "shortWave")

        waveManager.addWave(longWave, name = "longWave")
        waveManager.addWave(shortWave, name = "shortWave")

        waveManager.setSpeed(currentSpeed)
        waveManager.restart()
    }

    override fun getPulse(): Pulse {
        val longAmp = waveManager.getPosition("longWave")
        val shortAmp = waveManager.getPosition("shortWave")

        val (ampA, ampB) = if (swapChannels) {
            Pair(longAmp, shortAmp)
        } else {
            Pair(shortAmp, longAmp)
        }

        return Pulse(
            freqA = currentFrequencyA.toFloat(),
            freqB = currentFrequencyB.toFloat(),
            ampA = ampA.toFloat(),
            ampB = ampB.toFloat()
        )
    }
}

class OverflowingActivity : Activity() {
    val waveManager: WaveManager = WaveManager()
    val speedRange = 0.05..0.1
    val frequencyRange = 0.6..1.0
    val frequencyChangeRateRange = 0.02..0.05
    val longWavePowerRange = 0.85..0.99
    val shortWavePowerRange = 0.8..0.99
    var currentSpeed = randomInRange(speedRange)
    val freqA = NiceSmoother(randomInRange(frequencyRange))
    val freqB = NiceSmoother(randomInRange(frequencyRange))

    override fun initialise() {
        updateFrequencyTarget(freqA)
        updateFrequencyTarget(freqB)
        manager.register(waveManager)
        manager.register(freqA)
        manager.register(freqB)
        nextIteration()
    }

    fun createLongWaveShape(): WaveShape {
        val power = randomInRange(longWavePowerRange)
        val lowPower = power * 0.85
        val highPowerPortion = randomInRange(0.8..0.9)
        val numLaps = randomInRange(5..20)
        val lapIncrement = highPowerPortion / (numLaps * 2.0)
        val points = mutableListOf<WavePoint>()
        var currentTime = 0.0

        points.add(WavePoint(currentTime,0.0))
        currentTime = (1.0 - highPowerPortion) / 2.0
        points.add(WavePoint(currentTime,lowPower))

        repeat(numLaps) {
            currentTime += lapIncrement
            points.add(WavePoint(currentTime,power))
            currentTime += lapIncrement
            points.add(WavePoint(currentTime,lowPower))
        }

        return WaveShape(
            name = "longWave",
            points = points,
            interpolationType = InterpolationType.HERMITE
        )
    }

    private fun updateFrequencyTarget(freq: NiceSmoother) {
        val targetFreq = randomInRange(frequencyRange)
        val changeRate = randomInRange(frequencyChangeRateRange)
        freq.setTarget(
            target = targetFreq,
            rate = changeRate,
            onReached = { updateFrequencyTarget(freq) }
        )
    }

    fun nextIteration() {
        currentSpeed = randomInRange(speedRange)
        val powerShort = randomInRange(shortWavePowerRange)

        val longWave = CyclicalWave(createLongWaveShape())

        val shortWaveShape = CyclicalWave(
            WaveShape(
                name = "shortWaveShape",
                points = listOf(
                    WavePoint(0.0, 0.0),
                    WavePoint(0.5, powerShort),
                ),
                interpolationType = InterpolationType.HERMITE
            )
        )

        val repeats = randomInRange(4..10)
        val shortWave = shortWaveShape.createRepeatedWave(repeats, "shortWave")

        waveManager.addWave(longWave, name = "longWave")
        waveManager.addWave(shortWave, name = "shortWave")

        waveManager.setSpeed(currentSpeed)
        waveManager.restart()
        waveManager.stopAtEndOfCycle { nextIteration() }
    }

    override fun getPulse(): Pulse {
        val longAmp = waveManager.getPosition("longWave")
        var shortAmp = waveManager.getPosition("shortWave")
        if (shortAmp > longAmp)
            shortAmp = longAmp

        return Pulse(
            freqA = freqA.value.toFloat(),
            freqB = freqB.value.toFloat(),
            ampA = shortAmp.toFloat(),
            ampB = longAmp.toFloat()
        )
    }
}

class SuccubusActivity : Activity() {
    val waveManager1: WaveManager = WaveManager()
    val waveManager2: WaveManager = WaveManager()
    val waveManagers = listOf(waveManager1, waveManager2)

    val speedRange = 0.06..2.0
    val speedBias = 3.5
    val speedChangeRateRange = 0.03..0.2
    val proportionRange = 0.0..1.0
    val proportionChangeRate = 0.05
    val waveShapeChangeProbability = 0.3
    val proportionChangeProbability = 0.3
    val speedChangeProbability = 0.3
    val ampProportionA = NiceSmoother(randomInRange(proportionRange))
    val ampProportionB = NiceSmoother(randomInRange(proportionRange))
    val freqProportionA = NiceSmoother(randomInRange(proportionRange))
    val freqProportionB = NiceSmoother(randomInRange(proportionRange))
    val wavePointsRange = 2..6

    val shapeChange = Timer(
        durationProvider = { randomInRange(10.0..40.0) },
        repeating = true,
        onTrigger = { shapeChange() }
    )
    val speedChange = Timer(
        durationProvider = { randomInRange(10.0..30.0) },
        repeating = true,
        onTrigger = { speedChange() }
    )
    val proportionChange = Timer(
        durationProvider = { randomInRange(10.0..30.0) },
        repeating = true,
        onTrigger = { proportionChange() }
    )

    override fun initialise() {
        waveManagers.forEach { wm ->
            wm.addWave(randomWave(randomInRange(wavePointsRange)), name="amp")
            wm.addWave(randomWave(randomInRange(wavePointsRange)), name="freq")
            wm.setSpeed(randomInRange(speedRange, speedBias))
            manager.register(wm)
        }
        manager.register(shapeChange)
        manager.register(speedChange)
        manager.register(proportionChange)
        manager.register(ampProportionA)
        manager.register(ampProportionB)
        manager.register(freqProportionA)
        manager.register(freqProportionB)

        shapeChange.start()
        speedChange.start()
        proportionChange.start()
    }

    fun randomWave(numPoints: Int): CyclicalWave {
        val points = mutableListOf<WavePoint>()
        val times = mutableListOf<Double>()
        val maxPower = 0.95
        val powerLowerBound = 0.8
        val minTimeSpacing = 0.05

        repeat(numPoints) {
            var t: Double
            do {
                t = randomInRange(0.0..TMAX)
            } while (times.any { abs(it - t) < minTimeSpacing })

            times.add(t)
            val pos = randomInRange(0.0..maxPower)
            points.add(WavePoint(t, pos))
        }

        val allBelowThreshold = points.all { it.position < powerLowerBound }
        if (allBelowThreshold) {
            // Select a random point and set it to a position in powerLowerBound..maxPower
            val randomIndex = (0 until numPoints).random()
            val newPosition = randomInRange(powerLowerBound..maxPower)
            points[randomIndex] = WavePoint(points[randomIndex].time, newPosition)
        }

        return CyclicalWave(
            WaveShape(
                name = "randomWave",
                points = points,
                interpolationType = InterpolationType.HERMITE
            )
        )
    }

    fun proportionChange() {
        //Log.d("Activity", "Proportion change called")
        if(Random.nextDouble() < proportionChangeProbability)
            ampProportionA.setTarget(randomInRange(proportionRange), proportionChangeRate)
        if(Random.nextDouble() < proportionChangeProbability)
            ampProportionB.setTarget(randomInRange(proportionRange), proportionChangeRate)
        if(Random.nextDouble() < proportionChangeProbability)
            freqProportionA.setTarget(randomInRange(proportionRange), proportionChangeRate)
        if(Random.nextDouble() < proportionChangeProbability)
            freqProportionB.setTarget(randomInRange(proportionRange), proportionChangeRate)
    }

    fun shapeChange() {
        //Log.d("Activity", "Shape change called")
        waveManagers.forEach { wm ->
            if(Random.nextDouble() < waveShapeChangeProbability) {
                wm.addWave(randomWave(randomInRange(wavePointsRange)), name="amp")
            }
            if(Random.nextDouble() < waveShapeChangeProbability) {
                wm.addWave(randomWave(randomInRange(wavePointsRange)), name="freq")
            }
        }
    }

    fun speedChange() {
        //Log.d("Activity", "Speed change called")
        waveManagers.forEach { wm ->
            if(Random.nextDouble() < speedChangeProbability) {
                val newSpeed = randomInRange(speedRange, speedBias)
                val changeRate = randomInRange(speedChangeRateRange)
                wm.setTargetSpeed(newSpeed, changeRate)
            }
        }
    }

    override fun getPulse(): Pulse {
        val ampPos1 = waveManager1.getPosition("amp")
        val ampPos2 = waveManager2.getPosition("amp")
        val freqPos1 = waveManager1.getPosition("freq")
        val freqPos2 = waveManager2.getPosition("freq")

        val ampA = (ampPos1 * ampProportionA.value) + (ampPos2 * (1.0 - ampProportionA.value))
        val ampB = (ampPos1 * ampProportionB.value) + (ampPos2 * (1.0 - ampProportionB.value))
        val freqA = (freqPos1 * freqProportionA.value) + (freqPos2 * (1.0 - freqProportionA.value))
        val freqB = (freqPos1 * freqProportionB.value) + (freqPos2 * (1.0 - freqProportionB.value))

        return Pulse(
            freqA = freqA.toFloat(),
            freqB = freqB.toFloat(),
            ampA = ampA.toFloat(),
            ampB = ampB.toFloat()
        )
    }
}