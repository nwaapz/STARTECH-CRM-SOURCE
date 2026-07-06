package com.datacall.ccp.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PacketCodecTest {
    @Test
    fun encodeDecode_roundTrip() {
        val payload = ByteArray(100) { it.toByte() }
        val packet = DataPacket(packetId = 3, totalPackets = 10, payload = payload)
        val encoded = PacketCodec.encode(packet)
        val decoded = PacketCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(packet.packetId, decoded!!.packetId)
        assertEquals(packet.totalPackets, decoded.totalPackets)
        assertArrayEquals(packet.payload, decoded.payload)
    }

    @Test
    fun decode_rejectsBadCrc() {
        val packet = DataPacket(0, 1, byteArrayOf(1, 2, 3))
        val encoded = PacketCodec.encode(packet)
        encoded[encoded.size - 1] = 0x00
        assertNull(PacketCodec.decode(encoded))
    }

    @Test
    fun splitAndReassemble() {
        val data = ByteArray(300) { (it % 256).toByte() }
        val packets = PacketCodec.splitIntoPackets(data)
        assertEquals(3, packets.size)
        val map = packets.associateBy { it.packetId }
        val restored = PacketCodec.reassemble(map, data.size)
        assertNotNull(restored)
        assertArrayEquals(data, restored)
    }
}
