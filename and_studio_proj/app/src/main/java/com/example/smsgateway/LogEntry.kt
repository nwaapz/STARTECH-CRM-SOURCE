package com.example.smsgateway

data class LogEntry(
    val timestamp: Long,
    val sender: String,
    val inbound: String,
    val reply: String?,
    val status: String,
    val error: String?,
    val simLabel: String? = null,
)
