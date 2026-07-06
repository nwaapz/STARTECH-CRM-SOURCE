package com.example.smsgateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RubikaApiPhoneExtractionTest {
    @Test
    fun extractPhoneFromMessage_readsContactMessage() {
        val message = JSONObject(
            """
            {
              "text": "",
              "contact_message": {
                "phone_number": "+989121234567",
                "first_name": "Ali"
              }
            }
            """.trimIndent(),
        )

        assertEquals("09121234567", RubikaApi.extractPhoneFromMessage(message))
    }

    @Test
    fun extractPhoneFromMessage_readsPlainTextPhone() {
        val message = JSONObject(
            """
            {
              "text": "۰۹۱۲۱۲۳۴۵۶۷"
            }
            """.trimIndent(),
        )

        assertEquals("09121234567", RubikaApi.extractPhoneFromMessage(message))
    }

    @Test
    fun extractPhoneFromText_findsPhoneInsideContactCardText() {
        assertEquals(
            "09121234567",
            RubikaApi.extractPhoneFromText("Ali Rezaei\n09121234567"),
        )
    }

    @Test
    fun extractPhoneFromMessage_ignoresNonPhoneText() {
        val message = JSONObject(
            """
            {
              "text": "سلام"
            }
            """.trimIndent(),
        )

        assertNull(RubikaApi.extractPhoneFromMessage(message))
    }
}
