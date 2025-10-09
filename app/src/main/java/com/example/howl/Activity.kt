package com.example.howl

import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random

sealed class Activity {
    val timerManager = TimerManager()

    open fun initialise() {
        // Default empty implementation
    }
    open fun runSimulation(deltaSimulationTime: Double) {
        timerManager.update(deltaSimulationTime)
    }
    abstract fun getPulse(): Pulse
}

class LickActivity : Activity() {
    enum class LickType {
        UNIDIRECTIONAL,
        BIDIRECTIONAL
    }

    var waveManager: WaveManager = WaveManager()
    var lickStartPoint = 0.0
    var lickEndPoint = 1.0
    var lickType = LickType.BIDIRECTIONAL


    override fun initialise() {
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
        waveManager.addWave(bidirectional)
        waveManager.addWave(unidirectional)
        waveManager.setAmplitudeVariance(0.5)
        waveManager.setSpeedVariance(0.5)
        newLick()
    }

    fun newLick() {
        lickType = LickType.entries.random()
        lickStartPoint = Random.nextDouble()
        lickEndPoint = Random.nextDouble()

        val distance = abs(lickEndPoint - lickStartPoint)
        val maxSpeed = (-3.0 * distance) + 4.5
        val speed = Random.nextDouble(0.3, maxSpeed)
        val desiredTimeSeconds = Random.nextDouble(1.0, 5.0)
        val calculatedReps = (desiredTimeSeconds * speed).toInt().coerceAtLeast(1)

        waveManager.restart()
        waveManager.setSpeed(speed)
        waveManager.stopAfterIterations(calculatedReps) { newLick() }
    }

    override fun runSimulation(deltaSimulationTime: Double) {
        super.runSimulation(deltaSimulationTime)
        waveManager.update(deltaSimulationTime)
    }

    override fun getPulse(): Pulse {
        val waveName = when (lickType) {
            LickType.UNIDIRECTIONAL -> "unidirectional"
            LickType.BIDIRECTIONAL -> "bidirectional"
        }
        val (position, velocity) = waveManager.getPositionAndVelocity(waveName)
        val lickPosition = (lickEndPoint - lickStartPoint) * position + lickStartPoint
        val scaledVelocity = scaleVelocity(velocity, 0.1)

        val freqA = lickPosition * 0.5 + 0.5
        val freqB = lickPosition * 0.52 + 0.48
        val (ampA, ampB) = calculatePositionalEffect(scaledVelocity, lickPosition, 1.0)

        return Pulse(
            freqA = freqA.toFloat(),
            freqB = freqB.toFloat(),
            ampA = ampA.toFloat(),
            ampB = ampB.toFloat()
        )
    }
}

class PenetrationActivity : Activity() {
    var waveManager: WaveManager = WaveManager()
    val penetrationSpeedChangeSecsRange = 1.0..20.0
    val penetrationSpeedRange = 0.3..3.0
    val penetrationSpeedChangeRateRange = 0.05..0.3
    val penetrationFeelExponentRange = 1.0..2.0
    val penetrationFeelExponentChangeRateRange = 0.05..0.1
    val penetrationFeelChangeSecsRange = 2.0..10.0
    //var penetrationFeelExponent = 1.0

    private val penetrationFeelExponent = SmoothedValue(randomInRange(penetrationFeelExponentRange))

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
        waveManager.setSpeedVariance(0.2)
        waveManager.setAmplitudeVariance(0.1)
        speedChange()
        feelChange()
        waveManager.setSpeed(0.15)
        waveManager.setTargetSpeed(0.5, 0.1)
    }

    fun speedChange() {
        val newSpeed = randomInRange(penetrationSpeedRange)
        val changeRate = randomInRange(penetrationSpeedChangeRateRange)
        val nextSpeedChangeSecs = randomInRange(penetrationSpeedChangeSecsRange)
        waveManager.setTargetSpeed(newSpeed, changeRate)
        timerManager.addTimer("speedChange", nextSpeedChangeSecs) {
            speedChange()
        }
    }

    fun feelChange() {
        val target = randomInRange(penetrationFeelExponentRange)
        val rate = randomInRange(penetrationFeelExponentChangeRateRange)
        penetrationFeelExponent.setTarget(target, rate)
        val nextFeelChangeSecs = randomInRange(penetrationFeelChangeSecsRange)
        timerManager.addTimer("feelChange", nextFeelChangeSecs) {
            feelChange()
        }
    }

    override fun runSimulation(deltaSimulationTime: Double) {
        super.runSimulation(deltaSimulationTime)
        penetrationFeelExponent.update(deltaSimulationTime)
        waveManager.update(deltaSimulationTime)
    }

    override fun getPulse(): Pulse {
        val (position, velocity) = waveManager.getPositionAndVelocity("penetration")
        val scaledVelocity = scaleVelocity(velocity, 0.1)

        var ampFactor = (waveManager.currentSpeed - penetrationSpeedRange.start) / (penetrationSpeedRange.endInclusive - penetrationSpeedRange.start)
        ampFactor = 0.8 + ampFactor * 0.2

        val freqBaseA = position * 0.7
        val freqBaseB = scaledVelocity * 0.5 + position * 0.4
        val freqA = freqBaseA.pow(penetrationFeelExponent.current).coerceIn(0.0,1.0)
        val freqB = freqBaseB.pow(penetrationFeelExponent.current).coerceIn(0.0,1.0)

        val ampA = position * ampFactor
        val ampB = (scaledVelocity * 0.6 + position * 0.4) * ampFactor

        return Pulse(
            freqA = freqA.toFloat(),
            freqB = freqB.toFloat(),
            ampA = ampA.toFloat(),
            ampB = ampB.toFloat()
        )
    }
}


class VibroActivity : Activity() {
    val vibeSpeedRange = 0.0..1.0
    val vibeMoveSpeedRange = 0.04..0.3
    val vibeHoldTimeRange = 0.0..3.0
    val vibeSpeedChangeTimeRange = 5.0..30.0
    val vibePower = 0.9
    val vibePositionRange = 0.0 .. 1.0
    val vibeHoldProbability = 0.5

    var vibeSpeed = 0.3
    var vibeTargetPosition = 0.5
    var vibeMoveSpeed = 0.1
    private val vibePosition = SmoothedValue(randomInRange(vibePositionRange))

    override fun initialise() {
        initializeWithRandomValues()
    }

    private fun initializeWithRandomValues() {
        vibeSpeed = randomInRange(vibeSpeedRange)
        vibePosition.setImmediately(randomInRange(vibePositionRange))
        newTarget()
        scheduleSpeedChangeTimer()
    }

    private fun newTarget() {
        vibeTargetPosition = randomInRange(vibePositionRange)
        vibeMoveSpeed = randomInRange(vibeMoveSpeedRange)
        vibePosition.setTarget(vibeTargetPosition, vibeMoveSpeed, onReached = { targetPositionReached() } )
    }

    private fun scheduleSpeedChangeTimer() {
        timerManager.addTimer("speedChange", randomInRange(vibeSpeedChangeTimeRange)) {
            vibeSpeed = randomInRange(vibeSpeedRange)
            scheduleSpeedChangeTimer() // Reschedule
        }
    }

    private fun scheduleHoldTimer() {
        timerManager.addTimer("hold", randomInRange(vibeHoldTimeRange)) {
            newTarget()
        }
    }

    private fun targetPositionReached() {
        if (Random.nextDouble() < vibeHoldProbability) {
            scheduleHoldTimer()
        }
        else {
            newTarget()
        }
    }

    override fun runSimulation(deltaSimulationTime: Double) {
        super.runSimulation(deltaSimulationTime)
        vibePosition.update(deltaSimulationTime)
    }

    override fun getPulse(): Pulse {
        val freqA = vibeSpeed
        val freqB = vibeSpeed
        val (ampA, ampB) = calculatePositionalEffect(vibePower, vibePosition.current, 1.0)

        return Pulse(
            freqA = freqA.toFloat(),
            freqB = freqB.toFloat(),
            ampA = ampA.toFloat(),
            ampB = ampB.toFloat()
        )
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
    val buzzDurationRange = 6.0..12.0

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
        waveManager.setSpeedVariance(0.0)
        waveManager.setAmplitudeVariance(0.0)
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
        timerManager.addTimer("buzzEnd", randomInRange(buzzDurationRange)) {
            wompStart()
        }
    }

    override fun runSimulation(deltaSimulationTime: Double) {
        super.runSimulation(deltaSimulationTime)
        waveManager.update(deltaSimulationTime)
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
                val phase = timerManager.getProportionElapsed("buzzEnd") ?: 1.0
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
    val randomisePulseTimeSecsRange = 10.0..30.0
    val pulseTimeRange = 0.025..0.25
    var pulseTime = 0.025
    var pulseTimeCounter = 0.0
    var ampA = 0.0
    var ampB = 0.0
    var freqA = 0.0
    var freqB = 0.0

    override fun initialise() {
        randomise()
        randomisePulseTime()
    }

    fun randomise() {
        ampA = randomInRange(ampRange)
        ampB = randomInRange(ampRange)
        freqA = randomInRange(freqRange)
        freqB = randomInRange(freqRange)
    }

    fun randomisePulseTime() {
        pulseTime = randomInRange(pulseTimeRange)
        val nextChangeSecs = randomInRange(randomisePulseTimeSecsRange)
        timerManager.addTimer("randomisePulseTime", nextChangeSecs) {
            randomisePulseTime()
        }
    }

    override fun runSimulation(deltaSimulationTime: Double) {
        super.runSimulation(deltaSimulationTime)
        pulseTimeCounter += deltaSimulationTime
        if(pulseTimeCounter > pulseTime) {
            randomise()
            pulseTimeCounter -= pulseTime
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
}

class LuxuryHJActivity : Activity() {
    val hjWaveManager: WaveManager = WaveManager()
    val bonusWaveManager: WaveManager = WaveManager()
    val hjSpeedChangeSecsRange = 1.0..20.0
    val hjSpeedRange = 0.3..3.0
    val hjSpeedChangeRateRange = 0.05..0.3
    val bonusTimeRange = 10.0..25.0
    val bonusSpeedRange = 1.5..5.0
    val startFreq = 0.15
    val endFreq = 0.65
    val bonusStartFreq = 0.8
    val bonusEndFreq = 1.0

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

        hjWaveManager.setSpeedVariance(0.2)
        hjWaveManager.setAmplitudeVariance(0.2)
        hjWaveManager.setAmplitudeVarianceEaseIn(1.0)
        bonusWaveManager.setSpeedVariance(0.5)
        bonusWaveManager.setAmplitudeVariance(0.15)
        bonusWaveManager.setSpeed(1.0)
        hjWaveManager.setSpeed(0.5)
        speedChange()
    }

    fun speedChange() {
        val newSpeed = randomInRange(hjSpeedRange)
        val changeRate = randomInRange(hjSpeedChangeRateRange)
        val nextSpeedChangeSecs = randomInRange(hjSpeedChangeSecsRange)
        hjWaveManager.setTargetSpeed(newSpeed, changeRate)
        timerManager.addTimer("speedChange", nextSpeedChangeSecs) {
            speedChange()
        }
    }

    override fun runSimulation(deltaSimulationTime: Double) {
        super.runSimulation(deltaSimulationTime)
        hjWaveManager.update(deltaSimulationTime)
        bonusWaveManager.update(deltaSimulationTime)
        val bonusAProbability = (0.7 * deltaSimulationTime) / 60.0
        val bonusBProbability = (0.7 * deltaSimulationTime) / 60.0
        if (Random.nextDouble() < bonusAProbability && !timerManager.hasTimer("bonusB")) {
            bonusWaveManager.setSpeed(randomInRange(bonusSpeedRange))
            timerManager.addTimer("bonusA", randomInRange(bonusTimeRange)) {}
        }
        if (Random.nextDouble() < bonusBProbability && !timerManager.hasTimer("bonusA")) {
            bonusWaveManager.setSpeed(randomInRange(bonusSpeedRange))
            timerManager.addTimer("bonusB", randomInRange(bonusTimeRange)) {}
        }
    }

    override fun getPulse(): Pulse {
        var freqA = 0.0
        var freqB = 0.0

        val position = hjWaveManager.getPosition("hj", applyAmplitudeVariance = false)
        val baseAmp = hjWaveManager.currentAmplitude
        var (ampA, ampB) = calculatePositionalEffect(baseAmp, position, 1.0)
        freqA = ((position * (endFreq - startFreq)) + startFreq) * 0.98
        freqB = (position * (endFreq - startFreq)) + startFreq

        val ampBonus = bonusWaveManager.getPosition("bonus")
        val freqBonus = (ampBonus * (bonusEndFreq - bonusStartFreq)) + bonusStartFreq

        val bonusWeight = 0.7

        if(timerManager.hasTimer("bonusA")) {
            ampA = ampBonus * bonusWeight + (ampA * (1.0 - bonusWeight))
            freqA = freqBonus * bonusWeight + (freqA * (1.0 - bonusWeight))
        }
        if(timerManager.hasTimer("bonusB")) {
            ampB = ampBonus * bonusWeight + (ampB * (1.0 - bonusWeight))
            freqB = freqBonus * bonusWeight + (freqB * (1.0 - bonusWeight))
        }

        return Pulse(
            freqA = freqA.toFloat(),
            freqB = freqB.toFloat(),
            ampA = ampA.toFloat(),
            ampB = ampB.toFloat()
        )
    }
}

class OppositesActivity : Activity() {
    val ampRange = 0.0..1.0
    val freqRange = 0.0..1.0
    val overallSpeedRange = 0.5..3.0
    val overallSpeedChangeRateRange = 0.2..0.5
    val overallSpeedChangeSecsRange = 10.0..20.0
    val baseChangeRateRange = 0.15..0.4

    private val ampA = SmoothedValue(randomInRange(ampRange))
    private val freqA = SmoothedValue(randomInRange(freqRange))
    private val overallSpeed = SmoothedValue(randomInRange(overallSpeedRange))

    override fun initialise() {
        newRandomAmplitudeTarget(ampA)
        newRandomFrequencyTarget(freqA)
        speedChange()
    }

    private fun getChangeRate() : Double {
        val range = baseChangeRateRange.start * overallSpeed.current .. baseChangeRateRange.endInclusive * overallSpeed.current
        return randomInRange(range)
    }

    private fun speedChange() {
        val newSpeed = randomInRange(overallSpeedRange)
        val changeRate = randomInRange(overallSpeedChangeRateRange)
        val nextSpeedChangeSecs = randomInRange(overallSpeedChangeSecsRange)
        overallSpeed.setTarget(newSpeed, changeRate)
        timerManager.addTimer("speedChange", nextSpeedChangeSecs) {
            speedChange()
        }
    }

    private fun newRandomFrequencyTarget(freq: SmoothedValue) {
        val targetFreq = randomInRange(freqRange)
        val changeRate = getChangeRate()
        freq.setTarget(
            target = targetFreq,
            rate = changeRate,
            onReached = { newRandomFrequencyTarget(freq) }
        )
    }

    private fun newRandomAmplitudeTarget(amp: SmoothedValue) {
        val targetAmp = randomInRange(ampRange)
        val changeRate = getChangeRate()
        amp.setTarget(
            target = targetAmp,
            rate = changeRate,
            onReached = { newRandomAmplitudeTarget(amp) }
        )
    }

    override fun runSimulation(deltaSimulationTime: Double) {
        super.runSimulation(deltaSimulationTime)

        ampA.update(deltaSimulationTime)
        freqA.update(deltaSimulationTime)
        overallSpeed.update(deltaSimulationTime)
    }

    override fun getPulse(): Pulse {
        val ampB = 1.0 - ampA.current
        val freqB = 1.0 - freqA.current
        return Pulse(
            freqA = freqA.current.toFloat(),
            freqB = freqB.toFloat(),
            ampA = ampA.current.toFloat(),
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
        calibrationWaveManager.setSpeedVariance(0.0)
        calibrationWaveManager.setAmplitudeVariance(0.0)
    }

    override fun runSimulation(deltaSimulationTime: Double) {
        super.runSimulation(deltaSimulationTime)
        calibrationWaveManager.update(deltaSimulationTime)
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
    val phaseChangeSecs = 16.0
    var currentPhase: Phase = Phase.CHANNEL_A

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
        calibrationWaveManager.setSpeedVariance(0.0)
        calibrationWaveManager.setAmplitudeVariance(0.0)
        timerManager.addTimer("nextPhase", phaseChangeSecs) {
            nextPhase()
        }
    }

    fun nextPhase() {
        currentPhase = currentPhase.next()
        timerManager.addTimer("nextPhase", phaseChangeSecs) {
            nextPhase()
        }
    }

    override fun runSimulation(deltaSimulationTime: Double) {
        super.runSimulation(deltaSimulationTime)
        calibrationWaveManager.update(deltaSimulationTime)
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
    val primaryDurationRange = 20.0..60.0
    val secondaryDurationRange = 6.0..20.0
    val deepthroatFrequencyConverter = FrequencyConverter(
        points = listOf(
            FrequencyConverterPoint(0.0, 1.0),
            FrequencyConverterPoint(0.7, 0.0),
            FrequencyConverterPoint(1.0, 0.3),
        ),
        interpolationType = FrequencyInterpolationType.SMOOTHSTEP
    )
    val suckFrequencyConverterA = FrequencyConverter(
        points = listOf(
            FrequencyConverterPoint(0.0, 0.7),
            FrequencyConverterPoint(1.0, 0.3),
        ),
        interpolationType = FrequencyInterpolationType.SMOOTHSTEP
    )
    val suckFrequencyConverterB = FrequencyConverter(
        points = listOf(
            FrequencyConverterPoint(0.0, 0.9),
            FrequencyConverterPoint(1.0, 0.3),
        ),
        interpolationType = FrequencyInterpolationType.SMOOTHSTEP
    )
    val lickFrequencyRange = 0.8..1.0
    val BJSpeedChangeSecsRange = 1.0..20.0
    val BJSpeedRange = 0.2..1.2
    val BJSpeedChangeRateRange = 0.03..0.2
    val fullLickSpeedRange = 0.3..1.0
    val tipLickSpeedRange = 0.5..3.0

    enum class BJStage {
        FullLick,
        TipLick,
        Suck,
        Deepthroat,
    }
    var currentStage = BJStage.entries.random()

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
        waveManager.addWave(positionWave)
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
        waveManager.addWave(bidirectional)
        waveManager.addWave(unidirectional)
        nextStage()
    }

    fun speedChange() {
        val newSpeed = randomInRange(BJSpeedRange)
        val changeRate = randomInRange(BJSpeedChangeRateRange)
        waveManager.setTargetSpeed(newSpeed, changeRate)
        val nextSpeedChangeSecs = randomInRange(BJSpeedChangeSecsRange)
        timerManager.addTimer("speedChange", nextSpeedChangeSecs) {
            speedChange()
        }
    }

    fun nextStage() {
        var stageDuration = 1.0

        val previousStage = currentStage
        while(previousStage == currentStage)
            currentStage = BJStage.entries.random()

        stageDuration = when(currentStage) {
            BJStage.FullLick -> randomInRange(secondaryDurationRange)
            BJStage.TipLick -> randomInRange(secondaryDurationRange)
            BJStage.Suck -> randomInRange(primaryDurationRange)
            BJStage.Deepthroat -> randomInRange(primaryDurationRange)
        }

        waveManager.restart()
        when(currentStage) {
            BJStage.FullLick -> {
                waveManager.setSpeedVariance(0.4)
                waveManager.setAmplitudeVariance(0.3)
                waveManager.setAmplitudeVarianceEaseIn(0.0)
                waveManager.setSpeed(randomInRange(fullLickSpeedRange))
                timerManager.cancelTimer("speedChange")
            }
            BJStage.TipLick -> {
                waveManager.setSpeedVariance(0.4)
                waveManager.setAmplitudeVariance(0.3)
                waveManager.setAmplitudeVarianceEaseIn(0.0)
                waveManager.setSpeed(randomInRange(tipLickSpeedRange))
                timerManager.cancelTimer("speedChange")
            }
            BJStage.Suck -> {
                waveManager.setSpeedVariance(0.2)
                waveManager.setAmplitudeVariance(0.2)
                waveManager.setAmplitudeVarianceEaseIn(0.0)
                waveManager.setSpeed(randomInRange(BJSpeedRange))
                speedChange()
            }
            BJStage.Deepthroat -> {
                waveManager.setSpeedVariance(0.2)
                waveManager.setAmplitudeVariance(0.2)
                waveManager.setAmplitudeVarianceEaseIn(0.0)
                waveManager.setSpeed(randomInRange(BJSpeedRange))
                speedChange()
            }
        }

        timerManager.addTimer("nextStage", stageDuration) {
            waveManager.stopAtEndOfCycle { nextStage() }
        }
    }

    override fun runSimulation(deltaSimulationTime: Double) {
        super.runSimulation(deltaSimulationTime)
        waveManager.update(deltaSimulationTime)
    }

    override fun getPulse(): Pulse {
        var ampA = 0.0
        var ampB = 0.0
        var freqA = 0.0
        var freqB = 0.0

        when(currentStage) {
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
                val position = waveManager.getPosition("position", applyAmplitudeVariance = false)
                val baseAmp = waveManager.currentAmplitude
                val amplitudes = calculateEngulfEffect(baseAmp, position, 0.7, 0.4)
                ampA = amplitudes.first
                ampB = amplitudes.second

                freqA = suckFrequencyConverterA.getFrequency(position)
                freqB = suckFrequencyConverterB.getFrequency(position)
            }
            BJStage.Deepthroat -> {
                val position = waveManager.getPosition("position", applyAmplitudeVariance = false)
                val baseAmp = waveManager.currentAmplitude
                val amplitudes = calculateEngulfEffect(baseAmp, position, 0.8, 0.3)
                ampA = amplitudes.first
                ampB = amplitudes.second

                freqA = position
                freqB = deepthroatFrequencyConverter.getFrequency(position)
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

    override fun runSimulation(deltaSimulationTime: Double) {
        super.runSimulation(deltaSimulationTime)
        waveManager.update(deltaSimulationTime)
        waveManager2.update(deltaSimulationTime)
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

class AdditiveActivity : Activity() {
    val waveManager1: WaveManager = WaveManager()
    val waveManager2: WaveManager = WaveManager()
    val waveManagers = listOf(waveManager1, waveManager2)

    val speedChangeSecsRange = 10.0..30.0
    val proportionChangeSecsRange = 10.0..30.0
    val shapeChangeSecsRange = 10.0..60.0
    val speedRange = 0.08..2.0
    val speedBias = 2.5
    val speedChangeRateRange = 0.03..0.2
    val proportionRange = 0.0..1.0
    val proportionChangeRate = 0.05
    val wavePowerRange = 0.7..1.0
    val waveShapeChangeProbability = 0.3
    val proportionChangeProbability = 0.3
    val speedChangeProbability = 0.3
    val ampProportionA = SmoothedValue(randomInRange(proportionRange))
    val ampProportionB = SmoothedValue(randomInRange(proportionRange))
    val freqProportionA = SmoothedValue(randomInRange(proportionRange))
    val freqProportionB = SmoothedValue(randomInRange(proportionRange))

    override fun initialise() {
        waveManagers.forEach { wm ->
            wm.addWave(randomWave(), name="amp")
            wm.addWave(randomWave(), name="freq")
            wm.setSpeed(randomInRange(speedRange, speedBias))
        }
        shapeChange()
        speedChange()
        proportionChange()
    }

    fun randomWave(): CyclicalWave {
        val point = randomInRange(0.01..0.99)
        val power = randomInRange(wavePowerRange)
        val wave = CyclicalWave(
            WaveShape(
                name = "random",
                points = listOf(
                    WavePoint(0.0, 0.0, 0.0),
                    WavePoint(point, power, 0.0),
                ),
                interpolationType = InterpolationType.HERMITE
            )
        )
        return wave
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
        val nextProportionChangeSecs = randomInRange(proportionChangeSecsRange)
        timerManager.addTimer("proportionChange", nextProportionChangeSecs) {
            proportionChange()
        }
    }

    fun shapeChange() {
        //Log.d("Activity", "Shape change called")
        waveManagers.forEach { wm ->
            if(Random.nextDouble() < waveShapeChangeProbability) {
                wm.addWave(randomWave(), name="amp")
            }
            if(Random.nextDouble() < waveShapeChangeProbability) {
                wm.addWave(randomWave(), name="freq")
            }
        }
        val nextShapeChangeSecs = randomInRange(shapeChangeSecsRange)
        timerManager.addTimer("shapeChange", nextShapeChangeSecs) {
            shapeChange()
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
        val nextSpeedChangeSecs = randomInRange(speedChangeSecsRange)
        timerManager.addTimer("speedChange", nextSpeedChangeSecs) {
            speedChange()
        }
    }

    override fun runSimulation(deltaSimulationTime: Double) {
        super.runSimulation(deltaSimulationTime)
        waveManagers.forEach { wm ->
            wm.update(deltaSimulationTime)
        }
        ampProportionA.update(deltaSimulationTime)
        ampProportionB.update(deltaSimulationTime)
        freqProportionA.update(deltaSimulationTime)
        freqProportionB.update(deltaSimulationTime)
    }

    override fun getPulse(): Pulse {
        val ampPos1 = waveManager1.getPosition("amp")
        val ampPos2 = waveManager2.getPosition("amp")
        val freqPos1 = waveManager1.getPosition("freq")
        val freqPos2 = waveManager2.getPosition("freq")

        val ampA = (ampPos1 * ampProportionA.current) + (ampPos2 * (1.0 - ampProportionA.current))
        val ampB = (ampPos1 * ampProportionB.current) + (ampPos2 * (1.0 - ampProportionB.current))
        val freqA = (freqPos1 * freqProportionA.current) + (freqPos2 * (1.0 - freqProportionA.current))
        val freqB = (freqPos1 * freqProportionB.current) + (freqPos2 * (1.0 - freqProportionB.current))

        return Pulse(
            freqA = freqA.toFloat(),
            freqB = freqB.toFloat(),
            ampA = ampA.toFloat(),
            ampB = ampB.toFloat()
        )
    }
}

abstract class BaseSimplexActivity : Activity() {
    protected val noiseGenerator = NoiseGenerator()
    protected var elapsedTime = 0.0
    protected var phaseTime = 0.0
    protected var phaseRotation = 0.0

    open val ampTimeSpeedRange: ClosedRange<Double> = 0.2..4.0
    open val ampRotationSpeedRange: ClosedRange<Double> = 0.0..PI * 0.2
    open val changeRateRange: ClosedRange<Double> = 0.1..0.5
    open val ampTimeChangeSecsRange: ClosedRange<Double> = 10.0..50.0
    open val ampRotationSpeedChangeSecsRange: ClosedRange<Double> = 10.0..50.0

    open val ampRadius: Double = 0.3
    open val freqRadius: Double = 0.2
    open val freqTimeSpeed: Double = 0.2
    open val freqRotationSpeed: Double = 0.1

    protected lateinit var ampTimeSpeed: SmoothedValue
    protected lateinit var ampRotationSpeed: SmoothedValue

    override fun initialise() {
        super.initialise()
        ampTimeSpeed = SmoothedValue(randomInRange(ampTimeSpeedRange))
        ampRotationSpeed = SmoothedValue(randomInRange(ampRotationSpeedRange))
        ampTimeSpeedChange()
        ampRotationSpeedChange()
    }

    protected fun ampTimeSpeedChange() {
        ampTimeSpeed.setTarget(randomInRange(ampTimeSpeedRange), randomInRange(changeRateRange))
        //Log.d("Activity", "ampTimeSpeedChange ${ampTimeSpeed.getTarget()}")
        val nextAmpTimeSpeedChangeSecs = randomInRange(ampTimeChangeSecsRange)
        timerManager.addTimer("ampTimeSpeedChange", nextAmpTimeSpeedChangeSecs) {
            ampTimeSpeedChange()
        }
    }

    protected fun ampRotationSpeedChange() {
        ampRotationSpeed.setTarget(randomInRange(ampRotationSpeedRange), randomInRange(changeRateRange))
        //Log.d("Activity", "ampRotationSpeedChange ${ampRotationSpeed.getTarget()}")
        val nextAmpRotationSpeedChangeSecs = randomInRange(ampRotationSpeedChangeSecsRange)
        timerManager.addTimer("ampRotationSpeedChange", nextAmpRotationSpeedChangeSecs) {
            ampRotationSpeedChange()
        }
    }

    override fun runSimulation(deltaSimulationTime: Double) {
        super.runSimulation(deltaSimulationTime)
        ampTimeSpeed.update(deltaSimulationTime)
        ampRotationSpeed.update(deltaSimulationTime)
        elapsedTime += deltaSimulationTime

        phaseTime += ampTimeSpeed.current * deltaSimulationTime
        phaseRotation += ampRotationSpeed.current * deltaSimulationTime
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
}

class SimplexActivity : BaseSimplexActivity() {
    // All parameters use default values from BaseSimplexActivity
}

class SimplexProActivity : BaseSimplexActivity() {
    override val ampTimeSpeedRange = 0.2..0.8
    override val ampRotationSpeedRange = PI * 0.5..PI * 4
    override val changeRateRange = 0.2..0.5
    override val ampRadius = 0.4
    override val freqRadius = 0.3
}

class SimplexTurboActivity : BaseSimplexActivity() {
    override val ampTimeSpeedRange = 0.1..0.5
    override val ampRotationSpeedRange = PI * 3..PI * 6
    override val changeRateRange = 0.2..0.5
    override val ampRadius = 0.6
    override val freqRadius = 0.3
}

class RelentlessActivity : Activity() {
    val waveManager: WaveManager = WaveManager()
    val iterationSecsRange = 1.0..30.0
    val speedRange = 0.2..0.4
    val speedBias = 2.5
    val frequencyRange = 0.0..1.0
    val wavePowerRange = 0.8..0.95
    val repeatOptions = listOf(1, 2, 3, 4)
    var currentSpeed = randomInRange(speedRange, speedBias)
    var currentFrequencyA = randomInRange(frequencyRange)
    var currentFrequencyB = randomInRange(frequencyRange)
    var swapChannels = Random.nextBoolean()

    override fun initialise() {
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
        val nextIterationSecs = randomInRange(iterationSecsRange)
        timerManager.addTimer("nextIteration", nextIterationSecs) {
            waveManager.stopAtEndOfCycle { nextIteration() }
        }
    }

    override fun runSimulation(deltaSimulationTime: Double) {
        super.runSimulation(deltaSimulationTime)
        waveManager.update(deltaSimulationTime)
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

class RandomShapesActivity : Activity() {
    val waveManager: WaveManager = WaveManager()
    val iterationSecsRange = 10.0..40.0
    val speedRange = 0.2..0.8
    val speedBias = 1.8
    val frequencyRange = 0.0..1.0
    var currentSpeed = randomInRange(speedRange, speedBias)
    var currentFrequencyA = randomInRange(frequencyRange)
    var currentFrequencyB = randomInRange(frequencyRange)
    var swapChannels = Random.nextBoolean()
    val wavePointsRange = 2..5

    override fun initialise() {
        nextIteration()
    }

    fun createRandomWaveShape(numPoints: Int): WaveShape {
        val points = mutableListOf<WavePoint>()
        val times = mutableListOf<Double>()
        val maxPower = 0.95
        val powerLowerBound = 0.8
        val minTimeSpacing = 0.05

        times.add(0.0)
        times.add(1.0)

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

        points.add(WavePoint(0.0,0.0))

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

        val waveAPoints = randomInRange(wavePointsRange)
        val waveBPoints = randomInRange(wavePointsRange)

        val waveA = CyclicalWave(createRandomWaveShape(waveAPoints))
        val waveB = CyclicalWave(createRandomWaveShape(waveBPoints))

        waveManager.addWave(waveA, name = "waveA")
        waveManager.addWave(waveB, name = "waveB")

        waveManager.setSpeed(currentSpeed)
        waveManager.restart()
        val nextIterationSecs = randomInRange(iterationSecsRange)
        timerManager.addTimer("nextIteration", nextIterationSecs) {
            waveManager.stopAtEndOfCycle { nextIteration() }
        }
    }

    override fun runSimulation(deltaSimulationTime: Double) {
        super.runSimulation(deltaSimulationTime)
        waveManager.update(deltaSimulationTime)
    }

    override fun getPulse(): Pulse {
        val ampA = waveManager.getPosition("waveA")
        val ampB = waveManager.getPosition("waveB")

        return Pulse(
            freqA = currentFrequencyA.toFloat(),
            freqB = currentFrequencyB.toFloat(),
            ampA = ampA.toFloat(),
            ampB = ampB.toFloat()
        )
    }
}