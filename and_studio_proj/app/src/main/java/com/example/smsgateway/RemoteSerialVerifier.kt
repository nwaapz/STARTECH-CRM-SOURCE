package com.example.smsgateway

class RemoteSerialVerifier(
    private val gatewayApi: GatewayApi,
) : SerialVerifier {
    override fun verify(sender: String, message: String, subscriptionId: Int): GatewayApi.GatewayResponse {
        return gatewayApi.forwardSms(sender, message)
    }
}
