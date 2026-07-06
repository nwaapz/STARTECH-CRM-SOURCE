package com.datacall.ccp.call

import android.content.Context
import android.media.AudioManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

enum class CallState {
    IDLE,
    RINGING,
    OFFHOOK,
    UNKNOWN
}

class CallStateMonitor(private val context: Context) {
    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    fun observeCallState(): Flow<Pair<CallState, String?>> = callbackFlow {
        val listener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    trySend(mapState(state) to null)
                }
            }.also {
                telephonyManager.registerTelephonyCallback(context.mainExecutor, it)
            }
        } else {
            @Suppress("DEPRECATION")
            object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    trySend(mapState(state) to phoneNumber)
                }
            }.also {
                @Suppress("DEPRECATION")
                telephonyManager.listen(it, PhoneStateListener.LISTEN_CALL_STATE)
            }
        }

        awaitClose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyManager.unregisterTelephonyCallback(listener as TelephonyCallback)
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener as PhoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
        }
    }

    fun currentState(): CallState {
        @Suppress("DEPRECATION")
        return mapState(telephonyManager.callState)
    }

    private fun mapState(state: Int): CallState = when (state) {
        TelephonyManager.CALL_STATE_IDLE -> CallState.IDLE
        TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
        TelephonyManager.CALL_STATE_OFFHOOK -> CallState.OFFHOOK
        else -> CallState.UNKNOWN
    }
}

fun enableSpeakerphone(context: Context, enabled: Boolean) {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    audioManager.isSpeakerphoneOn = enabled
}
