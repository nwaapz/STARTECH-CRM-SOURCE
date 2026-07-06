package com.example.smsgateway

import android.content.Context

class SerialVerifierFactory(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    fun get(): SerialVerifier {
        return when (settingsStore.getVerificationMode()) {
            VerificationMode.REMOTE -> RemoteSerialVerifier(GatewayApi(settingsStore))
            VerificationMode.INTERNAL -> LocalSerialVerifier(context)
        }
    }

    companion object {
        @Volatile
        private var instance: SerialVerifierFactory? = null

        fun getInstance(context: Context): SerialVerifierFactory {
            return instance ?: synchronized(this) {
                instance ?: SerialVerifierFactory(
                    context.applicationContext,
                    SettingsStore.getInstance(context),
                ).also { instance = it }
            }
        }
    }
}
