package com.example.smsgateway

import com.example.smsgateway.data.GatewayDatabase

class LocalSerialVerifier(
    context: android.content.Context,
) : SerialVerifier {
    private val engine = SmsProcessingEngine.getInstance(context)

    override fun verify(sender: String, message: String, subscriptionId: Int): GatewayApi.GatewayResponse {
        return engine.process(sender, message, subscriptionId)
    }
}
