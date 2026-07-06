package com.datacall.ccp.modem

import com.datacall.ccp.protocol.DataPacket
import com.datacall.ccp.protocol.PacketCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FskModemTest {
    private val config = FskConfig(baudRate = 1000)

    @Test
    fun textRoundTrip() {
        val modulator = FskModulator(config)
        val demodulator = FskDemodulator(config)
        val text = "DATA_CALL_V1"
        val samples = modulator.modulateText(text)
        val decoded = demodulator.demodulateToText(samples)
        assertTrue(decoded.contains("DATA_CALL"))
    }

    @Test
    fun packetRoundTrip() {
        val modulator = FskModulator(config)
        val demodulator = FskDemodulator(config)
        val packet = DataPacket(0, 1, byteArrayOf(10, 20, 30, 40))
        val encoded = PacketCodec.encode(packet)
        val samples = modulator.modulateBytes(encoded)
        val bytes = demodulator.demodulateToBytes(samples)
        var found = false
        for (i in 0 until bytes.size - 12) {
            val decoded = PacketCodec.decode(bytes.copyOfRange(i, bytes.size))
            if (decoded != null && decoded.packetId == 0) {
                found = true
                assertEquals(4, decoded.payload.size)
                break
            }
        }
        assertTrue(found)
    }
}
