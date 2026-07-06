package com.datacall.ccp.session

import android.content.Context
import com.datacall.ccp.call.CallAudioRouter
import com.datacall.ccp.modem.AudioIo
import com.datacall.ccp.modem.FskConfig
import com.datacall.ccp.modem.FskDemodulator
import com.datacall.ccp.modem.FskModulator
import com.datacall.ccp.protocol.DataPacket
import com.datacall.ccp.protocol.PacketCodec
import com.datacall.ccp.protocol.ProtocolConstants
import com.datacall.ccp.protocol.TransferHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil

class TransferSession(
    private val context: Context,
    private val config: FskConfig = FskConfig.forCall(),
    private val loopback: Boolean = false,
    private val onProgress: (TransferProgress) -> Unit = {},
    private val onLog: (String) -> Unit = {}
) {
    private val modulator = FskModulator(config)
    private val demodulator = FskDemodulator(config)
    private val audioRouter = if (loopback) null else CallAudioRouter(context)
    private val audioIo = if (loopback) {
        AudioIo(context, config, null)
    } else {
        AudioIo(context, config, audioRouter)
    }
    private var loopbackBuffer = mutableListOf<Short>()

    fun previewDurationSeconds(sampleSeconds: Int = 3): Int {
        return sampleSeconds + 1
    }

    suspend fun previewHandshakeTone(sampleSeconds: Int = 3) {
        val handshake = modulator.modulateText(ProtocolConstants.HANDSHAKE_SENDER)
        val sampleBits = List(sampleSeconds * config.baudRate) { index -> index % 2 == 0 }
        val sampleTone = modulator.modulateBits(sampleBits)
        val combined = ShortArray(handshake.size + sampleTone.size)
        handshake.copyInto(combined)
        sampleTone.copyInto(combined, destinationOffset = handshake.size)
        audioIo.playPreview(combined)
    }

    fun estimateHandshakeSeconds(): Int {
        val bits = ProtocolConstants.HANDSHAKE_SENDER.toByteArray(Charsets.UTF_8).size * 8
        return ((bits * 1000L) / config.baudRate / 1000).toInt().coerceAtLeast(1)
    }

    fun estimateTransferSeconds(imageBytes: Int): Int {
        val dataBits = imageBytes * 8
        val overheadBits = 2000
        return ((dataBits + overheadBits) / config.baudRate).coerceAtLeast(1)
    }

    suspend fun sendImage(file: File): TransferMetrics {
        val start = System.currentTimeMillis()
        val data = file.readBytes()
        val packets = PacketCodec.splitIntoPackets(data)
        var retries = 0
        val crcFailures = 0
        try {
            if (!loopback) {
                onProgress(TransferProgress(TransferPhase.HANDSHAKE, message = "Waiting for call to settle (3s)"))
                log("Settle delay 3s")
                delay(3000)
            }

            val handshakeOk = performSenderHandshake()
            if (!handshakeOk) {
                throw IllegalStateException("Handshake failed after retries")
            }
            log("Receiver replied READY")

            val header = TransferHeader(
                type = ProtocolConstants.DATA_TYPE_IMAGE,
                size = data.size,
                packets = packets.size
            )
            onProgress(TransferProgress(TransferPhase.HEADER, message = "Sending header"))
            transmitText("${ProtocolConstants.FRAME_PREFIX}${header.toJson()}", forTransmit = true)
            delay(300)

            packets.forEachIndexed { index, packet ->
                onProgress(
                    TransferProgress(
                        TransferPhase.TRANSFERRING,
                        currentPacket = index + 1,
                        totalPackets = packets.size,
                        message = "Sending packet ${index + 1}/${packets.size}"
                    )
                )
                sendPacketWithRetry(packet) { nackId ->
                    retries++
                    log("Retransmit packet $nackId")
                }
            }

            transmitText(ProtocolConstants.DONE_MARKER, forTransmit = true)
            onProgress(TransferProgress(TransferPhase.COMPLETE, totalPackets = packets.size, message = "Done"))
            val duration = System.currentTimeMillis() - start
            return TransferMetrics(
                durationMs = duration,
                bytesTransferred = data.size,
                packetsSent = packets.size,
                packetsRetried = retries,
                crcFailures = crcFailures,
                effectiveBitrate = if (duration > 0) (data.size * 8.0 * 1000) / duration else 0.0,
                success = true
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onProgress(TransferProgress(TransferPhase.FAILED, message = e.message ?: "Send failed"))
            return TransferMetrics(
                durationMs = System.currentTimeMillis() - start,
                packetsRetried = retries,
                crcFailures = crcFailures,
                success = false,
                errorMessage = e.message
            )
        } finally {
            if (!loopback) audioIo.release()
        }
    }

    private suspend fun performSenderHandshake(): Boolean {
        val maxAttempts = 15
        repeat(maxAttempts) { attempt ->
            val attemptNum = attempt + 1
            onProgress(
                TransferProgress(
                    TransferPhase.HANDSHAKE,
                    message = "Handshake attempt $attemptNum/$maxAttempts"
                )
            )
            log("Sender attempt $attemptNum: ${ProtocolConstants.HANDSHAKE_SENDER}")
            transmitText(ProtocolConstants.HANDSHAKE_SENDER, forTransmit = true)
            delay(200)
            onProgress(TransferProgress(TransferPhase.HANDSHAKE, message = "Listening for READY ($attemptNum)"))
            val ready = listenForText(windowMs = 2000)
            if (ready.contains(ProtocolConstants.HANDSHAKE_RECEIVER)) {
                return true
            }
            log("No READY yet, decoded='$ready'")
            delay(300)
        }
        return false
    }

    suspend fun receiveImage(): Pair<File, TransferMetrics> {
        val start = System.currentTimeMillis()
        var retries = 0
        val crcFailures = 0
        try {
            if (!loopback) audioRouter?.prepareForReceive()

            onProgress(TransferProgress(TransferPhase.HANDSHAKE, message = "Listening for handshake"))
            val handshake = waitForHandshake(timeoutMs = 45_000)
            if (!handshake.contains(ProtocolConstants.HANDSHAKE_SENDER)) {
                throw IllegalStateException("Invalid handshake: '$handshake'")
            }
            log("Handshake received")
            transmitText(ProtocolConstants.HANDSHAKE_RECEIVER, forTransmit = false)

            onProgress(TransferProgress(TransferPhase.HEADER, message = "Waiting for header"))
            val headerText = waitForFrameHeader(timeoutMs = 30_000)
            val json = headerText.substringAfter(ProtocolConstants.FRAME_PREFIX, headerText).trim()
            val header = TransferHeader.fromJson(json)
            if (header.type != ProtocolConstants.DATA_TYPE_IMAGE) {
                throw IllegalStateException("Unsupported data type ${header.type}")
            }

            val received = mutableMapOf<Int, DataPacket>()
            val deadline = System.currentTimeMillis() + 180_000
            while (received.size < header.packets && System.currentTimeMillis() < deadline) {
                onProgress(
                    TransferProgress(
                        TransferPhase.TRANSFERRING,
                        currentPacket = received.size,
                        totalPackets = header.packets,
                        message = "Receiving ${received.size}/${header.packets}"
                    )
                )
                val packet = listenForPacket(windowMs = 4000)
                if (packet == null) {
                    val missing = (0 until header.packets).firstOrNull { !received.containsKey(it) }
                    if (missing != null) {
                        transmitText("${ProtocolConstants.NACK_PREFIX}$missing", forTransmit = false)
                        retries++
                    }
                    continue
                }
                if (received.containsKey(packet.packetId)) continue
                received[packet.packetId] = packet
                log("Packet ${packet.packetId + 1}/${packet.totalPackets}")
            }

            if (received.size < header.packets) {
                throw IllegalStateException("Timeout: received ${received.size}/${header.packets} packets")
            }

            val assembled = PacketCodec.reassemble(received, header.size)
                ?: throw IllegalStateException("Failed to reassemble image")
            val dir = File(context.filesDir, "received").apply { mkdirs() }
            val outFile = File(dir, "img_${System.currentTimeMillis()}.jpg")
            FileOutputStream(outFile).use { it.write(assembled) }

            onProgress(TransferProgress(TransferPhase.COMPLETE, totalPackets = header.packets, message = "Saved"))
            val duration = System.currentTimeMillis() - start
            return outFile to TransferMetrics(
                durationMs = duration,
                bytesTransferred = assembled.size,
                packetsReceived = header.packets,
                packetsRetried = retries,
                crcFailures = crcFailures,
                effectiveBitrate = if (duration > 0) (assembled.size * 8.0 * 1000) / duration else 0.0,
                success = true
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onProgress(TransferProgress(TransferPhase.FAILED, message = e.message ?: "Receive failed"))
            throw e
        } finally {
            if (!loopback) audioIo.release()
        }
    }

    private suspend fun waitForHandshake(timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val chunk = listenForText(windowMs = 1500)
            if (chunk.contains(ProtocolConstants.HANDSHAKE_SENDER)) return chunk
            log("Scanning for handshake...")
        }
        return ""
    }

    private suspend fun waitForFrameHeader(timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val chunk = listenForText(windowMs = 1500)
            if (chunk.contains(ProtocolConstants.FRAME_PREFIX) || chunk.contains("\"type\"")) return chunk
        }
        return ""
    }

    suspend fun runLoopbackTest(payload: ByteArray): TransferMetrics {
        val start = System.currentTimeMillis()
        val packets = PacketCodec.splitIntoPackets(payload)
        var ok = 0
        packets.forEach { if (loopbackPacketRoundTrip(it)) ok++ }
        val duration = System.currentTimeMillis() - start
        return TransferMetrics(
            durationMs = duration,
            bytesTransferred = payload.size,
            packetsSent = ok,
            packetsReceived = ok,
            effectiveBitrate = if (duration > 0) (payload.size * 8.0 * 1000) / duration else 0.0,
            success = ok == packets.size
        )
    }

    suspend fun loopbackRoundTrip(text: String): String {
        val samples = modulator.modulateText(text)
        demodulator.reset()
        return demodulator.demodulateToText(samples)
    }

    fun loopbackPacketRoundTrip(packet: DataPacket): Boolean {
        val encoded = PacketCodec.encode(packet)
        val samples = modulator.modulateBytes(encoded)
        demodulator.reset()
        val bytes = demodulator.demodulateToBytes(samples)
        for (i in 0 until bytes.size - ProtocolConstants.PACKET_OVERHEAD) {
            if (bytes[i] == ProtocolConstants.SYNC_BYTE_1 && bytes[i + 1] == ProtocolConstants.SYNC_BYTE_2) {
                val decoded = PacketCodec.decode(bytes.copyOfRange(i, bytes.size))
                if (decoded != null && decoded.packetId == packet.packetId) return true
            }
        }
        return false
    }

    private suspend fun sendPacketWithRetry(packet: DataPacket, onRetry: (Int) -> Unit) {
        var attempts = 0
        while (attempts < 5) {
            transmitPacket(packet)
            delay(200)
            val response = listenForText(windowMs = 3000)
            if (response.contains("${ProtocolConstants.NACK_PREFIX}${packet.packetId}")) {
                onRetry(packet.packetId)
                attempts++
                continue
            }
            return
        }
    }

    private suspend fun transmitText(text: String, forTransmit: Boolean) {
        val samples = modulator.modulateText(text)
        playSamples(samples, forTransmit)
    }

    private suspend fun transmitPacket(packet: DataPacket) {
        val encoded = PacketCodec.encode(packet)
        val samples = modulator.modulateBytes(encoded)
        playSamples(samples, forTransmit = true)
    }

    private suspend fun playSamples(samples: ShortArray, forTransmit: Boolean) {
        if (loopback) {
            loopbackBuffer.addAll(samples.toList())
            val durationMs = ceil(samples.size * 1000.0 / config.sampleRate).toLong() + 50
            delay(durationMs)
        } else if (forTransmit) {
            audioIo.playTransmit(samples)
        } else {
            audioIo.playReceive(samples)
        }
    }

    private suspend fun listenForText(windowMs: Long): String {
        if (loopback) {
            return receiveTextLoopback(windowMs)
        }
        val samples = audioIo.recordChunk(windowMs)
        return demodulator.demodulateToText(samples)
    }

    private suspend fun listenForPacket(windowMs: Long): DataPacket? {
        val samples = if (loopback) {
            receiveTextLoopback(windowMs)
            ShortArray(0)
        } else {
            audioIo.recordChunk(windowMs)
        }
        if (samples.isEmpty()) return null
        val bytes = demodulator.demodulateToBytes(samples)
        for (i in 0 until bytes.size - ProtocolConstants.PACKET_OVERHEAD) {
            if (bytes[i] == ProtocolConstants.SYNC_BYTE_1 && bytes[i + 1] == ProtocolConstants.SYNC_BYTE_2) {
                val packet = PacketCodec.decode(bytes.copyOfRange(i, bytes.size))
                if (packet != null) return packet
            }
        }
        return null
    }

    private suspend fun receiveTextLoopback(timeoutMs: Long): String {
        if (loopbackBuffer.isEmpty()) {
            delay(timeoutMs.coerceAtMost(500))
            return ""
        }
        val samples = loopbackBuffer.toShortArray()
        loopbackBuffer.clear()
        demodulator.reset()
        return demodulator.demodulateToText(samples)
    }

    private fun log(message: String) = onLog(message)
}
