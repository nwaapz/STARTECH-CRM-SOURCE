package com.datacall.ccp.call

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

class CallAudioRouter(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var savedMode: Int = AudioManager.MODE_NORMAL
    private var savedSpeakerphone: Boolean = false
    private var savedMicMuted: Boolean = false
    private var savedVoiceVolume: Int = 0
    private var savedMusicVolume: Int = 0
    private var stateSaved: Boolean = false

    fun prepareForPreview() {
        saveStateIfNeeded()
        audioManager.mode = AudioManager.MODE_NORMAL
        forceSpeakerphone(true)
        boostPlaybackVolume()
        audioManager.isMicrophoneMute = false
        requestAudioFocus()
    }

    fun prepareForTransmit() {
        saveStateIfNeeded()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        forceSpeakerphone(true)
        boostPlaybackVolume()
        lowerVoiceCallVolume()
        audioManager.isMicrophoneMute = true
        requestAudioFocus()
    }

    fun prepareForReceive() {
        saveStateIfNeeded()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        forceSpeakerphone(true)
        boostPlaybackVolume()
        audioManager.isMicrophoneMute = false
        requestAudioFocus()
    }

    fun restore() {
        if (!stateSaved) return
        audioManager.isMicrophoneMute = savedMicMuted
        audioManager.isSpeakerphoneOn = savedSpeakerphone
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVoiceVolume, 0)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
        audioManager.mode = savedMode
        abandonAudioFocus()
        stateSaved = false
    }

    private fun saveStateIfNeeded() {
        if (stateSaved) return
        savedMode = audioManager.mode
        savedSpeakerphone = audioManager.isSpeakerphoneOn
        savedMicMuted = audioManager.isMicrophoneMute
        savedVoiceVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        savedMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        stateSaved = true
    }

    private fun forceSpeakerphone(enabled: Boolean) {
        audioManager.isSpeakerphoneOn = enabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speaker = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null) {
                audioManager.setCommunicationDevice(speaker)
            }
        }
    }

    private fun boostPlaybackVolume() {
        val musicMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, musicMax, 0)
        val voiceMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        val boostedVoice = (voiceMax * 0.85f).toInt().coerceAtLeast(1)
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, boostedVoice, 0)
    }

    private fun lowerVoiceCallVolume() {
        val voiceMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        val lowered = (voiceMax * 0.35f).toInt().coerceAtLeast(1)
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, lowered, 0)
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAcceptsDelayedFocusGain(false)
                .build()
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Best-effort abandon; focus request object not retained in v1
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
}
