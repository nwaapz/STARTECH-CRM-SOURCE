package com.datacall.ccp.modem

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.datacall.ccp.call.CallAudioRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioIo(
    private val context: Context,
    private val config: FskConfig = FskConfig(),
    private val audioRouter: CallAudioRouter? = null
) {
    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null

    suspend fun playPreview(samples: ShortArray) = withContext(Dispatchers.IO) {
        audioRouter?.prepareForPreview()
        try {
            playInternal(samples, preferVoiceCall = false)
        } finally {
            audioRouter?.restore()
        }
    }

    suspend fun playTransmit(samples: ShortArray) = withContext(Dispatchers.IO) {
        audioRouter?.prepareForTransmit()
        playInternal(samples, preferVoiceCall = true)
    }

    suspend fun playReceive(samples: ShortArray) = withContext(Dispatchers.IO) {
        audioRouter?.prepareForReceive()
        playInternal(samples, preferVoiceCall = true)
    }

    private suspend fun playInternal(samples: ShortArray, preferVoiceCall: Boolean) {
        stopPlayback()
        val padded = padWithSilence(samples)
        val track = if (preferVoiceCall) {
            createVoiceCallTrack(padded) ?: createMediaTrack(padded) ?: createVoiceCommunicationTrack(padded)
        } else {
            createMediaTrack(padded) ?: createVoiceCommunicationTrack(padded) ?: createVoiceCallTrack(padded)
        } ?: throw IllegalStateException("Unable to play audio on this device")

        audioTrack = track
        track.play()
        waitForPlaybackComplete(track, padded.size)
        stopPlayback()
    }

    private suspend fun waitForPlaybackComplete(track: AudioTrack, totalFrames: Int) {
        val expectedMs = (totalFrames * 1000L) / config.sampleRate + 300
        val deadline = System.currentTimeMillis() + expectedMs
        while (System.currentTimeMillis() < deadline) {
            @Suppress("DEPRECATION")
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) break
            if (track.playbackHeadPosition >= totalFrames) break
            kotlinx.coroutines.delay(50)
        }
        kotlinx.coroutines.delay(100)
    }

    private fun padWithSilence(samples: ShortArray): ShortArray {
        val pad = config.silenceSamples
        if (pad <= 0) return samples
        val out = ShortArray(samples.size + pad * 2)
        // Leading and trailing silence
        samples.copyInto(out, destinationOffset = pad)
        return out
    }

    private fun createVoiceCallTrack(samples: ShortArray): AudioTrack? {
        return try {
            @Suppress("DEPRECATION")
            val minBuffer = AudioTrack.getMinBufferSize(
                config.sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                config.sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, samples.size * 2),
                AudioTrack.MODE_STATIC
            )
            track.write(samples, 0, samples.size)
            track
        } catch (_: Exception) {
            null
        }
    }

    private fun createMediaTrack(samples: ShortArray): AudioTrack? {
        return try {
            val minBuffer = AudioTrack.getMinBufferSize(
                config.sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(config.sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, samples.size * 2))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(samples, 0, samples.size)
            track
        } catch (_: Exception) {
            null
        }
    }

    private fun createVoiceCommunicationTrack(samples: ShortArray): AudioTrack? {
        return try {
            val minBuffer = AudioTrack.getMinBufferSize(
                config.sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(config.sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, samples.size * 2))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(samples, 0, samples.size)
            track
        } catch (_: Exception) {
            null
        }
    }

    suspend fun record(durationMs: Long): ShortArray = withContext(Dispatchers.IO) {
        audioRouter?.prepareForReceive()
        recordWithFallback(durationMs)
    }

    suspend fun recordChunk(chunkMs: Long): ShortArray = record(chunkMs)

    private fun recordWithFallback(durationMs: Long): ShortArray {
        stopRecording()
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        )
        for (source in sources) {
            val result = tryRecord(source, durationMs)
            if (result != null && result.isNotEmpty()) return result
        }
        return ShortArray(0)
    }

    private fun tryRecord(source: Int, durationMs: Long): ShortArray? {
        val sampleCount = ((config.sampleRate * durationMs) / 1000).toInt().coerceAtLeast(1)
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return null
        val record = try {
            AudioRecord(
                source,
                config.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, sampleCount * 2)
            )
        } catch (_: Exception) {
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        val buffer = ShortArray(sampleCount)
        return try {
            record.startRecording()
            audioRecord = record
            var read = 0
            while (read < sampleCount) {
                val chunk = record.read(buffer, read, sampleCount - read)
                if (chunk <= 0) break
                read += chunk
            }
            record.stop()
            if (read <= 0) null else if (read < sampleCount) buffer.copyOf(read) else buffer
        } catch (_: Exception) {
            null
        } finally {
            record.release()
            if (audioRecord == record) audioRecord = null
        }
    }

    fun stopPlayback() {
        audioTrack?.let {
            if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop()
            it.release()
        }
        audioTrack = null
    }

    fun stopRecording() {
        audioRecord?.let {
            if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
            it.release()
        }
        audioRecord = null
    }

    fun release() {
        stopPlayback()
        stopRecording()
        audioRouter?.restore()
    }
}
