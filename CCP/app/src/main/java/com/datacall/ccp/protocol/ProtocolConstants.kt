package com.datacall.ccp.protocol

object ProtocolConstants {
    const val HANDSHAKE_SENDER = "DATA_CALL_V1"
    const val HANDSHAKE_RECEIVER = "READY"
    const val DATA_TYPE_IMAGE = 103
    const val SYNC_BYTE_1: Byte = 0xAA.toByte()
    const val SYNC_BYTE_2: Byte = 0x55
    const val MAX_PAYLOAD_SIZE = 128
    const val HEADER_SIZE = 8
    const val CRC_SIZE = 4
    const val PACKET_OVERHEAD = HEADER_SIZE + CRC_SIZE
    const val FRAME_PREFIX = "FRAME:"
    const val NACK_PREFIX = "NACK:"
    const val DONE_MARKER = "DONE"
}
