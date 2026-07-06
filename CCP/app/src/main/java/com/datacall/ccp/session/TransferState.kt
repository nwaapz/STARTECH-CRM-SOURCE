package com.datacall.ccp.session

data class TransferMetrics(
    val durationMs: Long = 0,
    val bytesTransferred: Int = 0,
    val packetsSent: Int = 0,
    val packetsReceived: Int = 0,
    val packetsRetried: Int = 0,
    val crcFailures: Int = 0,
    val effectiveBitrate: Double = 0.0,
    val success: Boolean = false,
    val errorMessage: String? = null
)

enum class TransferPhase {
    IDLE,
    HANDSHAKE,
    HEADER,
    TRANSFERRING,
    COMPLETE,
    FAILED
}

data class TransferProgress(
    val phase: TransferPhase = TransferPhase.IDLE,
    val currentPacket: Int = 0,
    val totalPackets: Int = 0,
    val message: String = ""
)
