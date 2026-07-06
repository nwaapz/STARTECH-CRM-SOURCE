package com.datacall.ccp.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneUtilsTest {
    @Test
    fun normalizeInternationalToLocal() {
        assertEquals("09121234567", PhoneUtils.normalizePhone("+989121234567"))
        assertEquals("09121234567", PhoneUtils.normalizePhone("989121234567"))
    }

    @Test
    fun phonesMatch_lastTenDigits() {
        assertTrue(PhoneUtils.phonesMatch("+989121234567", "09121234567"))
    }
}
