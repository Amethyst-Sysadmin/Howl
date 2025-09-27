package com.example.howl

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

enum class PlaybackState {
    Stopped,
    Playing,
    Stopping
}

enum class CarrierWaveType(val displayName: String) {
    SINE("Sine"),
    SQUARE("Square"),
    TRIANGLE("Triangle"),
    TRAPEZOID("Trapezoid"),
    SAWTOOTH("Sawtooth")
}

enum class CarrierPhaseType(val displayName: String) {
    SAME("Same"), // Identical carrier phase on both channels
    OFFSET("Offset"), // Pi/2 offset
    OPPOSITE("Opposite"), // Pi offset
}

class AudioOutput : Output {
    override val timerDelay = 0.1
    override val pulseBatchSize = 10
    override val sendSilenceWhenMuted = true
    override var allowedFrequencyRange = 1..200
    override var defaultFrequencyRange = 10..100
    override var ready = true
    override var latency = 0.0

    private val sampleRate: Int = 48000
    private val desiredBufferSizeFrames = (sampleRate * timerDelay * 2.0).toInt()
    private var audioTrack: AudioTrack? = null
    private var bufferSizeFrames = 9600
    private var framesWritten: Long = 0
    private val blockSizeFrames = ((timerDelay / pulseBatchSize) * sampleRate).toInt()
    private val updateLock = Any()

    private val pulseQueue = CircularBuffer<Pulse>(capacity = pulseBatchSize + 5)
    private var lastPulse = Pulse()

    @Volatile
    private var playbackState: PlaybackState = PlaybackState.Stopped

    @Volatile
    private var stopFramesRemaining = 0

    @Volatile
    private var running = false
    private var producerThread: Thread? = null

    private var currentMinFreq = defaultFrequencyRange.first.toDouble()
    private var currentMaxFreq = defaultFrequencyRange.last.toDouble()
    private var currentPowerA = 0.0
    private var currentPowerB = 0.0

    // Phase accumulators for continuous waveform
    private var carrierPhase: Double = 0.0

    private enum class ChannelStage { REST, WAVELET }
    private data class ChannelState(
        var stage: ChannelStage = ChannelStage.REST,
        var stageCounter: Int = 0,               // samples elapsed in current stage
        var currentWaveletLength: Int = 0,
        var currentWaveletAmplitude: Double = 0.0,
        var currentWaveletPhaseOffset: Double = 0.0
    )

    private var channelA = ChannelState()
    private var channelB = ChannelState()

    override fun initialise() {
        bufferSizeFrames = initialiseAudioTrack()
        // I think the AudioTrack has a 3264 frame internal audio buffer (based on the steps the playback head moves in)
        latency = (bufferSizeFrames / sampleRate.toDouble()) + (3264 / sampleRate.toDouble())
        Log.d("AudioOutput", "Latency: $latency")
        Log.d("AudioOutput", "Block size (frames): $blockSizeFrames   Buffer size (frames): $bufferSizeFrames")
        running = true
        startProducerThread()
    }

    override fun end() {
        stop()
        val startTime = System.currentTimeMillis()
        // Wait until the buffer has played out and playback has actually stopped to avoid clicks.
        while (audioTrack?.playState != AudioTrack.PLAYSTATE_STOPPED) {
            Thread.sleep(10)
            if (System.currentTimeMillis() - startTime > 1000) {
                Log.w("AudioOutput", "Timed out waiting for AudioTrack to stop")
                break
            }
        }
        running = false
        producerThread?.join()
        producerThread = null
        audioTrack?.release()
        audioTrack = null
    }

    override fun handleBluetoothEvent(event: BluetoothEvent) {}

    override fun start() {
        if (playbackState == PlaybackState.Playing) return
        resetState()
        fillBufferWithSilence()
        audioTrack?.play()
        playbackState = PlaybackState.Playing
        //Log.d("AudioOutput", "After play, playState: ${audioTrack?.playState}")
    }

    override fun stop() {
        playbackState = PlaybackState.Stopping
        stopFramesRemaining = bufferSizeFrames
    }

    private fun triangleWave(phase: Double): Double {
        // Normalized to [-1,1]
        val norm = (phase / (2 * Math.PI)) % 1.0
        return if (norm < 0.5) (norm * 4 - 1) else (3 - norm * 4)
    }

    private fun trapezoidWave(phase: Double, duty: Double = 0.25): Double {
        // duty = flat portion length (0.0 = pure triangle, 0.5 = near square)
        val tri = triangleWave(phase)
        return when {
            tri > duty -> 1.0
            tri < -duty -> -1.0
            else -> tri / duty
        }
    }

    private fun squareWave(phase: Double): Double {
        // Audio waveform: -1 or +1
        return if ((phase % (2 * PI)) < PI) 1.0 else -1.0
    }

    private fun sawtoothWave(phase: Double): Double {
        // Normalized sawtooth in [-1,1]
        val norm = (phase / (2 * Math.PI)) % 1.0
        return 2.0 * norm - 1.0
    }

    private inline fun generateSample(
        ch: ChannelState,
        carrierType: CarrierWaveType,
        carrierPhase: Double,
        amplitude: Double,
        waveletLengthSamples: Int,
        restLengthSamples: Int,
        waveletFade: Double
    ): Short {
        ch.stageCounter++

        return when (ch.stage) {
            ChannelStage.WAVELET -> {
                //val phase = carrierPhase + ch.currentWaveletPhaseOffset
                val phase = carrierPhase
                val carrier  = when (carrierType) {
                    CarrierWaveType.SINE -> sin(phase)
                    CarrierWaveType.SQUARE -> squareWave(phase)
                    CarrierWaveType.TRIANGLE -> triangleWave(phase)
                    CarrierWaveType.TRAPEZOID -> trapezoidWave(phase)
                    CarrierWaveType.SAWTOOTH -> sawtoothWave(phase)
                }

                // Linear envelope
                val n = ch.stageCounter
                val fadeLength = (waveletLengthSamples * 0.5 * waveletFade).toInt()
                val sustainStart = fadeLength
                val sustainEnd = waveletLengthSamples - fadeLength

                val envelope = when {
                    fadeLength == 0 -> 1.0
                    n <= sustainStart -> n.toDouble() / fadeLength            // Linear fade-in
                    n >= sustainEnd -> (waveletLengthSamples - n).toDouble() / fadeLength  // Linear fade-out
                    else -> 1.0
                }.coerceIn(0.0, 1.0) // safety clamp

                if (ch.stageCounter >= ch.currentWaveletLength) {
                    ch.stage = ChannelStage.REST
                    ch.stageCounter = 0
                }
                (carrier * ch.currentWaveletAmplitude * envelope * Short.MAX_VALUE).toInt().toShort()
            }

            ChannelStage.REST -> {
                if (ch.stageCounter >= restLengthSamples && restLengthSamples != Int.MAX_VALUE) {
                    ch.stage = ChannelStage.WAVELET
                    ch.stageCounter = 0
                    ch.currentWaveletLength = waveletLengthSamples
                    ch.currentWaveletAmplitude = amplitude
                    ch.currentWaveletPhaseOffset = randomInRange(0.0..PI)
                }
                0
            }
        }
    }

    private fun generateStereoSamples(
        out: ShortArray,
        startPulse: Pulse,
        endPulse: Pulse
    ) {
        val carrierType = DataRepository.outputState.value.audioCarrierType
        val carrierFrequency = DataRepository.outputState.value.audioCarrierFrequency
        val carrierPhaseType = DataRepository.outputState.value.audioCarrierPhaseType
        val waveletWidth = DataRepository.outputState.value.audioWaveletWidth
        val waveletFade = DataRepository.outputState.value.audioWaveletFade.toDouble()
        val carrierPhaseInc = 2.0 * PI * carrierFrequency / sampleRate
        val carrierPhaseChannelOffset = when (carrierPhaseType) {
            CarrierPhaseType.SAME -> 0.0
            CarrierPhaseType.OFFSET -> PI/2.0
            CarrierPhaseType.OPPOSITE -> PI
        }
        val minimumRestSamples = 100

        val minFreq = currentMinFreq
        val freqRange = currentMaxFreq - currentMinFreq

        val frameCount = out.size / 2 // stereo = 2 samples per frame
        if (frameCount == 0) return

        val ampA = (lerp(startPulse.ampA, endPulse.ampA, 0.5f) * currentPowerA).coerceIn(0.0,1.0)
        val ampB = (lerp(startPulse.ampB, endPulse.ampB, 0.5f) * currentPowerB).coerceIn(0.0,1.0)
        val freqA = minFreq + freqRange * lerp(startPulse.freqA, endPulse.freqA, 0.5f)
        val freqB = minFreq + freqRange * lerp(startPulse.freqB, endPulse.freqB, 0.5f)
        val periodSamplesA = sampleRate / freqA
        val periodSamplesB = sampleRate / freqB
        val waveletLength =  if (carrierFrequency > 0.0)
            max(1, (waveletWidth * (sampleRate / carrierFrequency)).toInt())
        else 1
        val restLengthA =  max(minimumRestSamples, (periodSamplesA - waveletLength).roundToInt())
        val restLengthB =  max(minimumRestSamples, (periodSamplesB - waveletLength).roundToInt())

        var idx = 0
        for (frame in 0 until frameCount) {
            val sampleA = generateSample(channelA, carrierType, carrierPhase, ampA, waveletLength, restLengthA, waveletFade)
            val sampleB = generateSample(channelB, carrierType, carrierPhase + carrierPhaseChannelOffset, ampB, waveletLength, restLengthB, waveletFade)

            out[idx++] = sampleA
            out[idx++] = sampleB

            carrierPhase += carrierPhaseInc
        }
        carrierPhase %= 2.0 * Math.PI
    }

    override fun sendPulses(
        channelAPower: Int,
        channelBPower: Int,
        minFrequency: Double,
        maxFrequency: Double,
        pulses: List<Pulse>
    ) {
        synchronized(updateLock) {
            currentMinFreq = minFrequency
            currentMaxFreq = maxFrequency
            currentPowerA = (channelAPower / 200.0).coerceIn(0.0..1.0)
            currentPowerB = (channelBPower / 200.0).coerceIn(0.0..1.0)
            pulseQueue.addAll(pulses, overwrite = true)
        }
    }

    private fun resetState() {
        synchronized(updateLock) {
            carrierPhase = 0.0
            channelA = ChannelState()
            channelB = ChannelState()
            lastPulse = Pulse()
            pulseQueue.clear()
        }
        audioTrack?.flush()
    }

    private fun fillBufferWithSilence() {
        // Prime the buffer with silence before starting playback
        // Android initially makes us fill the whole buffer, otherwise
        // playback will never start (contradicts the documentation)
        val silence = ShortArray(blockSizeFrames * 2)
        var writeResult: Int
        do {
            writeResult = audioTrack?.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING) ?: 0
            if (writeResult > 0) {
                framesWritten += writeResult / 2 // Update framesWritten (stereo frames)
            }
        } while (writeResult == silence.size)
    }

    private fun initialiseAudioTrack(): Int {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val nativeSampleRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
        Log.d("AudioOutput","Native sample rate: $nativeSampleRate")

        val bufferSizeInBytes = maxOf(desiredBufferSizeFrames * 2, minBufferSize)
        Log.d("AudioOutput","bufferSizeInBytes: $bufferSizeInBytes   minBufferSize: $minBufferSize")

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSizeInBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            //.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
            Log.d("AudioOutput", "AudioTrack initialized")
        } else {
            Log.e("AudioOutput", "AudioTrack not initialized: ${audioTrack?.state}")
        }
        return bufferSizeInBytes / 2
    }

    private fun startProducerThread() {
        if (producerThread != null) {
            Log.e("AudioOutput", "startProducerThread called but producerThread already set.")
            return
        }

        producerThread = thread(name = "AudioThread", priority = Thread.MAX_PRIORITY) {
            val buffer = ShortArray(blockSizeFrames * 2) // stereo: L, R
            while (running) {
                if (playbackState == PlaybackState.Stopped) {
                    Thread.sleep(50)
                    continue
                }
                val track = audioTrack ?: break

                val (startPulse, endPulse) = synchronized(updateLock) {
                    val start = lastPulse
                    val end = if (playbackState == PlaybackState.Stopping) Pulse() else pulseQueue.removeFirstOrNull() ?: lastPulse
                    val queueSize = pulseQueue.size
                    //Log.d("AudioOutput", "Pulse queue size: $queueSize")
                    lastPulse = end
                    start to end
                }

                //Log.d("AudioOutput", "Generating audio")
                generateStereoSamples(buffer, startPulse = startPulse, endPulse = endPulse)
                val writtenFrames = track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING) / 2
                if (writtenFrames > 0) {
                    framesWritten += writtenFrames
                }
                if (playbackState == PlaybackState.Stopping) {
                    stopFramesRemaining -= writtenFrames

                    if (stopFramesRemaining <= 0) {
                        try {
                            audioTrack?.stop()
                        } catch (e: IllegalStateException) {
                            Log.d("AudioOutput", "AudioTrack stop failed: ${e.message}")
                        }
                        playbackState = PlaybackState.Stopped
                        framesWritten = 0
                    }
                }
            }
        }
    }
}
