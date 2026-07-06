package com.example.smsgateway

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneUtilsTest {
    @Test
    fun normalizeSearchPrefix_mapsPartialLocalNumber() {
        assertEquals("0913", PhoneUtils.normalizeSearchPrefix("0913"))
        assertEquals("0913", PhoneUtils.normalizeSearchPrefix("913"))
        assertEquals("0913", PhoneUtils.normalizeSearchPrefix("+98913"))
        assertEquals("0913", PhoneUtils.normalizeSearchPrefix("98913"))
    }

    @Test
    fun normalizeSearchPrefix_emptyForNonDigits() {
        assertEquals("", PhoneUtils.normalizeSearchPrefix(""))
        assertEquals("", PhoneUtils.normalizeSearchPrefix("abc"))
    }

    @Test
    fun isPhoneLikeInput_acceptsPhoneFormats() {
        assertEquals(true, PhoneUtils.isPhoneLikeInput("0913"))
        assertEquals(true, PhoneUtils.isPhoneLikeInput("+98913"))
        assertEquals(true, PhoneUtils.isPhoneLikeInput("913"))
        assertEquals(true, PhoneUtils.isPhoneLikeInput("98 913 123 4567"))
    }

    @Test
    fun isPhoneLikeInput_rejectsSerialLikeInput() {
        assertEquals(false, PhoneUtils.isPhoneLikeInput(""))
        assertEquals(false, PhoneUtils.isPhoneLikeInput("M100"))
        assertEquals(false, PhoneUtils.isPhoneLikeInput("abc"))
    }

    @Test
    fun normalizeToAsciiDigits_convertsPersianNumerals() {
        assertEquals("09121234567", PhoneUtils.normalizeToAsciiDigits("۰۹۱۲۱۲۳۴۵۶۷"))
    }

    @Test
    fun isPhoneLikeInput_acceptsPersianPhone() {
        assertEquals(true, PhoneUtils.isPhoneLikeInput("۰۹۱۲۱۲۳۴۵۶۷"))
        assertEquals("09121234567", PhoneUtils.formatForDisplay("۰۹۱۲۱۲۳۴۵۶۷"))
    }
}
