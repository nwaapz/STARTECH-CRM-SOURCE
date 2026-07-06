package com.example.smsgateway

object SerialNormalizer {
    fun normalizePrefix(prefix: String): String = prefix.trim().uppercase()

    fun normalizeSerial(serial: String): String = serial.trim().uppercase()
}
