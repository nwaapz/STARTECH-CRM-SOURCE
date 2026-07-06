package com.example.smsgateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceContactHelperTest {
    @Test
    fun phonesMatch_acceptsEquivalentIranianFormats() {
        assertTrue(DeviceContactHelper.phonesMatch("09121234567", "09121234567"))
        assertTrue(DeviceContactHelper.phonesMatch("+989121234567", "09121234567"))
        assertTrue(DeviceContactHelper.phonesMatch("989121234567", "09121234567"))
        assertTrue(DeviceContactHelper.phonesMatch("۰۹۱۲۱۲۳۴۵۶۷", "09121234567"))
    }

    @Test
    fun phonesMatch_rejectsDifferentNumbers() {
        assertFalse(DeviceContactHelper.phonesMatch("09121234567", "09131234567"))
    }

    @Test
    fun phonesMatch_rejectsBlankValues() {
        assertFalse(DeviceContactHelper.phonesMatch(null, "09121234567"))
        assertFalse(DeviceContactHelper.phonesMatch("09121234567", ""))
        assertFalse(DeviceContactHelper.phonesMatch("", ""))
    }

    @Test
    fun phonesMatch_normalizesSpaces() {
        assertTrue(DeviceContactHelper.phonesMatch("0912 123 4567", "09121234567"))
    }
}
