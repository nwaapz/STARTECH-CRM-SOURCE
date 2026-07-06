package com.datacall.ccp.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

object CallSessionState {
    @Volatile var isListening: Boolean = false

    @Volatile var incomingNumber: String? = null

    @Volatile var incomingCallPending: Boolean = false

    @Volatile var callOffHook: Boolean = false

    @Volatile var isReceiving: Boolean = false

    fun resetCall() {
        incomingNumber = null
        incomingCallPending = false
        callOffHook = false
        isReceiving = false
    }

    fun stopListening() {
        isListening = false
        resetCall()
    }
}

class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (!CallSessionState.isListening) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                CallSessionState.incomingNumber = number
                CallSessionState.incomingCallPending = true
                DataTransferService.startIncomingAlert(context, number)
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (CallSessionState.isReceiving) return
                CallSessionState.callOffHook = true
                CallSessionState.incomingCallPending = false
                CallSessionState.isReceiving = true
                DataTransferService.startReceiveMode(context)
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                CallSessionState.resetCall()
                if (CallSessionState.isListening) {
                    DataTransferService.armListening(context)
                } else {
                    DataTransferService.stop(context)
                }
            }
        }
    }
}
