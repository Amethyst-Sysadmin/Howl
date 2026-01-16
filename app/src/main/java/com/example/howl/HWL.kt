package com.example.howl

import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val HWL_HEADER = "YEAHBOI!"
const val HWL_HEADER_SIZE = 8 // header size in bytes
const val HWL_PULSE_SIZE = 16 // pulse size in bytes
const val HWL_PULSES_PER_SEC = 40
const val HWL_PULSE_TIME = 1.0/HWL_PULSES_PER_SEC

fun writeHWLFile(outputStream: OutputStream, pulses: List<Pulse>) {
    // Write header (8 bytes)
    outputStream.write(HWL_HEADER.toByteArray(Charsets.US_ASCII))

    // Preallocate one pulse buffer (4 floats = 16 bytes)
    val buffer = ByteBuffer.allocate(HWL_PULSE_SIZE).order(ByteOrder.LITTLE_ENDIAN)

    for (pulse in pulses) {
        buffer.clear()

        buffer.putFloat(pulse.ampA)
        buffer.putFloat(pulse.ampB)
        buffer.putFloat(pulse.freqA)
        buffer.putFloat(pulse.freqB)

        outputStream.write(buffer.array())
    }

    outputStream.flush()
}

fun readHWLFile(input: InputStream): List<Pulse> {
    val pulses = mutableListOf<Pulse>()

    // Read and validate header
    val headerBytes = ByteArray(HWL_HEADER_SIZE)
    if (input.read(headerBytes) != HWL_HEADER_SIZE ||
        String(headerBytes, Charsets.US_ASCII) != HWL_HEADER
    ) {
        throw BadFileException("Invalid HWL file: header mismatch.")
    }

    val buffer = ByteArray(HWL_PULSE_SIZE)
    val byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)

    // Read pulse data
    while (true) {
        val read = input.read(buffer)

        if (read == -1) break
        if (read != HWL_PULSE_SIZE) {
            throw BadFileException("Invalid HWL file: incomplete pulse data.")
        }

        byteBuffer.rewind()

        pulses += Pulse(
            ampA = byteBuffer.float,
            ampB = byteBuffer.float,
            freqA = byteBuffer.float,
            freqB = byteBuffer.float
        )
    }

    return pulses
}

class HWLPulseSource : PulseSource {
    override var displayName: String = "HWL"
    override var duration: Double? = null
    override val isFinite: Boolean = true
    override val shouldLoop: Boolean = true
    override var readyToPlay: Boolean = false
    override var isRemote: Boolean = false
    var pulseData: MutableList<Pulse> = mutableListOf<Pulse>()

    override fun updateState(currentTime: Double) {}

    override fun getPulseAtTime(time: Double): Pulse {
        if (pulseData.isEmpty()) {
            return Pulse()
        }

        val totalDuration = pulseData.size * HWL_PULSE_TIME

        if (time <= 0.0) {
            return pulseData.first()
        }
        if (time >= totalDuration) {
            return pulseData.last()
        }

        val idx = time / HWL_PULSE_TIME
        val index0 = idx.toInt()

        if (index0 == pulseData.size - 1) {
            return pulseData.last()
        }

        val index1 = index0 + 1
        val t0 = index0 * HWL_PULSE_TIME
        val t1 = (index0 + 1) * HWL_PULSE_TIME

        val pulse0 = pulseData[index0]
        val pulse1 = pulseData[index1]

        val ampA = linearInterpolate(time, t0, pulse0.ampA.toDouble(), t1, pulse1.ampA.toDouble()).toFloat()
        val ampB = linearInterpolate(time, t0, pulse0.ampB.toDouble(), t1, pulse1.ampB.toDouble()).toFloat()
        val freqA = linearInterpolate(time, t0, pulse0.freqA.toDouble(), t1, pulse1.freqA.toDouble()).toFloat()
        val freqB = linearInterpolate(time, t0, pulse0.freqB.toDouble(), t1, pulse1.freqB.toDouble()).toFloat()

        return Pulse(ampA, ampB, freqA, freqB)
    }

    private fun loadPulses(
        pulses: List<Pulse>,
        name: String,
        isRemoteSource: Boolean
    ): Double {
        readyToPlay = false

        pulseData.clear()
        pulseData.addAll(pulses)

        displayName = name
        duration = pulseData.size * HWL_PULSE_TIME
        isRemote = isRemoteSource

        readyToPlay = true
        return duration ?: 0.0
    }

    fun open(uri: Uri, context: Context): Double {
        val pulses = context.contentResolver
            .openInputStream(uri)
            ?.use(::readHWLFile)
            ?: throw BadFileException("Could not open HWL file.")

        return loadPulses(
            pulses = pulses,
            name = uri.getName(context),
            isRemoteSource = false
        )
    }

    fun loadFromBytes(
        data: ByteArray,
        title: String = "Remote HWL",
    ): Double {
        val pulses = data.inputStream().use(::readHWLFile)

        return loadPulses(
            pulses = pulses,
            name = title,
            isRemoteSource = true
        )
    }
}