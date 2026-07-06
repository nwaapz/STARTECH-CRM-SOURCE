package com.datacall.ccp.modem

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class FskDemodulator(private val config: FskConfig = FskConfig()) {
    private var phase = 0.0

    fun reset() {
        phase = 0.0
    }

    fun demodulateToBytes(samples: ShortArray): ByteArray {
        val bits = demodulateToBits(samples)
        val byteCount = bits.size / 8
        val bytes = ByteArray(byteCount)
        for (i in 0 until byteCount) {
            var value = 0
            for (b in 0 until 8) {
                if (bits[i * 8 + b]) value = value or (1 shl (7 - b))
            }
            bytes[i] = value.toByte()
        }
        return bytes
    }

    fun demodulateToText(samples: ShortArray): String {
        return demodulateToBytes(samples).toString(Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
    }

    fun demodulateToBits(samples: ShortArray): List<Boolean> {
        val samplesPerBit = config.samplesPerBit
        if (samples.isEmpty()) return emptyList()
        val bitCount = samples.size / samplesPerBit
        val bits = ArrayList<Boolean>(bitCount)
        var offset = 0
        repeat(bitCount) {
            val energy0 = goertzelEnergy(samples, offset, samplesPerBit, config.freq0)
            val energy1 = goertzelEnergy(samples, offset, samplesPerBit, config.freq1)
            bits.add(energy1 > energy0)
            offset += samplesPerBit
        }
        return bits
    }

    private fun goertzelEnergy(samples: ShortArray, offset: Int, length: Int, targetFreq: Double): Double {
        val omega = 2.0 * PI * targetFreq / config.sampleRate
        val cosine = cos(omega)
        val sine = sin(omega)
        val coeff = 2.0 * cosine
        var q0 = 0.0
        var q1 = 0.0
        var q2 = 0.0
        val end = minOf(offset + length, samples.size)
        for (i in offset until end) {
            val sample = samples[i].toDouble() / Short.MAX_VALUE
            q0 = coeff * q1 - q2 + sample
            q2 = q1
            q1 = q0
        }
        val real = q1 - q2 * cosine
        val imag = q2 * sine
        return real * real + imag * imag
    }
}
