package com.example.howl

import android.content.Context
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HWLPulseSource : PulseSource {
    override var displayName: String = "HWL"
    override var duration: Double? = null
    override val isFinite: Boolean = true
    override val shouldLoop: Boolean = true
    override var readyToPlay: Boolean = false
    override var isRemote: Boolean = false
    override var remoteLatency: Double = 0.0
    val HWLPulseTime = 0.025
    var pulseData: MutableList<Pulse> = mutableListOf<Pulse>()

    override fun updateState(currentTime: Double) {}

    override fun getPulseAtTime(time: Double): Pulse {
        if (pulseData.isEmpty()) {
            return Pulse()
        }

        val totalDuration = pulseData.size * HWLPulseTime

        if (time <= 0.0) {
            return pulseData.first()
        }
        if (time >= totalDuration) {
            return pulseData.last()
        }

        val idx = time / HWLPulseTime
        val index0 = idx.toInt()

        if (index0 == pulseData.size - 1) {
            return pulseData.last()
        }

        val index1 = index0 + 1
        val t0 = index0 * HWLPulseTime
        val t1 = (index0 + 1) * HWLPulseTime

        val pulse0 = pulseData[index0]
        val pulse1 = pulseData[index1]

        val ampA = linearInterpolate(time, t0, pulse0.ampA.toDouble(), t1, pulse1.ampA.toDouble()).toFloat()
        val ampB = linearInterpolate(time, t0, pulse0.ampB.toDouble(), t1, pulse1.ampB.toDouble()).toFloat()
        val freqA = linearInterpolate(time, t0, pulse0.freqA.toDouble(), t1, pulse1.freqA.toDouble()).toFloat()
        val freqB = linearInterpolate(time, t0, pulse0.freqB.toDouble(), t1, pulse1.freqB.toDouble()).toFloat()

        return Pulse(ampA, ampB, freqA, freqB)
    }

    fun open(uri: Uri, context: Context): Double? {
        readyToPlay = false
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            // Check the HWL file has the correct header
            val header = ByteArray(8)
            val bytesRead = inputStream.read(header)
            if (bytesRead != 8 || String(header) != "YEAHBOI!") {
                throw BadFileException("Invalid HWL file: expected header not found.")
            }

            val bytes = inputStream.readBytes()
            val buffer = ByteBuffer.wrap(bytes)
            buffer.order(ByteOrder.LITTLE_ENDIAN)

            while (buffer.hasRemaining()) {
                if (buffer.remaining() < 16) {
                    throw BadFileException("Invalid HWL file: incomplete Pulse data.")
                }
                val ampA = buffer.float
                val ampB = buffer.float
                val freqA = buffer.float
                val freqB = buffer.float
                pulseData.add(Pulse(ampA, ampB, freqA, freqB))
            }
        }

        displayName = uri.getName(context)
        duration = pulseData.size * HWLPulseTime
        readyToPlay = true
        return duration
    }
}