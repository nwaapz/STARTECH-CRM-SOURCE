package com.example.smsgateway

interface SerialVerifier {
    fun verify(sender: String, message: String, subscriptionId: Int): GatewayApi.GatewayResponse
}
