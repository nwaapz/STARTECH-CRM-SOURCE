package com.datacall.ccp.modem

data class FskConfig(
    val sampleRate: Int = 8000,
    val baudRate: Int = 1000,
    val freq0: Double = 1200.0,
    val freq1: Double = 2200.0,
    val amplitude: Double = 0.95,
    val silencePaddingMs: Int = 100
) {
    val samplesPerBit: Int get() = sampleRate / baudRate

    val silenceSamples: Int get() = (sampleRate * silencePaddingMs) / 1000

    companion object {
        fun forLoopback(baudRate: Int = 1000): FskConfig = FskConfig(baudRate = baudRate)

        fun forCall(): FskConfig = FskConfig(
            baudRate = 600,
            amplitude = 0.95,
            silencePaddingMs = 100
        )
    }
}
