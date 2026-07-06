package com.datacall.ccp.protocol

data class DataPacket(
    val packetId: Int,
    val totalPackets: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DataPacket) return false
        return packetId == other.packetId &&
            totalPackets == other.totalPackets &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = packetId
        result = 31 * result + totalPackets
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

object PacketCodec {
    fun splitIntoPackets(data: ByteArray): List<DataPacket> {
        val chunkSize = ProtocolConstants.MAX_PAYLOAD_SIZE
        val total = (data.size + chunkSize - 1) / chunkSize
        return (0 until total).map { index ->
            val start = index * chunkSize
            val end = minOf(start + chunkSize, data.size)
            DataPacket(
                packetId = index,
                totalPackets = total,
                payload = data.copyOfRange(start, end)
            )
        }
    }

    fun encode(packet: DataPacket): ByteArray {
        val payloadLen = packet.payload.size
        val buffer = ByteArray(ProtocolConstants.HEADER_SIZE + payloadLen + ProtocolConstants.CRC_SIZE)
        buffer[0] = ProtocolConstants.SYNC_BYTE_1
        buffer[1] = ProtocolConstants.SYNC_BYTE_2
        writeShort(buffer, 2, packet.packetId)
        writeShort(buffer, 4, packet.totalPackets)
        writeShort(buffer, 6, payloadLen)
        packet.payload.copyInto(buffer, destinationOffset = 8)
        val crc = Crc32.compute(buffer, 0, 8 + payloadLen)
        Crc32.writeInt(crc).copyInto(buffer, destinationOffset = 8 + payloadLen)
        return buffer
    }

    fun decode(buffer: ByteArray): DataPacket? {
        if (buffer.size < ProtocolConstants.PACKET_OVERHEAD) return null
        if (buffer[0] != ProtocolConstants.SYNC_BYTE_1 || buffer[1] != ProtocolConstants.SYNC_BYTE_2) return null
        val packetId = readShort(buffer, 2)
        val totalPackets = readShort(buffer, 4)
        val payloadLen = readShort(buffer, 6)
        if (payloadLen < 0 || payloadLen > ProtocolConstants.MAX_PAYLOAD_SIZE) return null
        val expectedSize = ProtocolConstants.HEADER_SIZE + payloadLen + ProtocolConstants.CRC_SIZE
        if (buffer.size < expectedSize) return null
        val crcOffset = 8 + payloadLen
        val expectedCrc = Crc32.readInt(buffer, crcOffset)
        val actualCrc = Crc32.compute(buffer, 0, crcOffset)
        if (expectedCrc != actualCrc) return null
        val payload = buffer.copyOfRange(8, 8 + payloadLen)
        return DataPacket(packetId, totalPackets, payload)
    }

    fun reassemble(packets: Map<Int, DataPacket>, totalSize: Int): ByteArray? {
        if (packets.isEmpty()) return null
        val totalPackets = packets.values.first().totalPackets
        if (packets.size != totalPackets) return null
        for (i in 0 until totalPackets) {
            if (!packets.containsKey(i)) return null
        }
        val ordered = (0 until totalPackets).map { packets.getValue(it).payload }
        val combined = ordered.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        return if (combined.size >= totalSize) combined.copyOf(totalSize) else combined
    }

    private fun writeShort(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }

    private fun readShort(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
    }
}
