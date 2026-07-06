package com.datacall.ccp.modem

import kotlin.math.PI
import kotlin.math.sin

class FskModulator(private val config: FskConfig = FskConfig()) {
    fun modulateBytes(data: ByteArray): ShortArray {
        val bits = mutableListOf<Boolean>()
        for (byte in data) {
            for (bit in 7 downTo 0) {
                bits.add(((byte.toInt() shr bit) and 1) == 1)
            }
        }
        return modulateBits(bits)
    }

    fun modulateText(text: String): ShortArray = modulateBytes(text.toByteArray(Charsets.UTF_8))

    fun modulateBits(bits: List<Boolean>): ShortArray {
        val samplesPerBit = config.samplesPerBit
        val output = ShortArray(bits.size * samplesPerBit)
        var phase = 0.0
        var offset = 0
        for (bit in bits) {
            val freq = if (bit) config.freq1 else config.freq0
            val phaseIncrement = 2.0 * PI * freq / config.sampleRate
            repeat(samplesPerBit) {
                val sample = sin(phase) * config.amplitude
                output[offset++] = (sample * Short.MAX_VALUE).toInt().toShort()
                phase += phaseIncrement
            }
        }
        return output
    }
}
