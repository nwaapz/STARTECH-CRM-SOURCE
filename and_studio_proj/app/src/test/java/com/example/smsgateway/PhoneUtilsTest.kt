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
    fun isRegisteredPhone_requiresRealIranianMobile() {
        assertEquals(false, PhoneUtils.isRegisteredPhone(null))
        assertEquals(false, PhoneUtils.isRegisteredPhone(""))
        assertEquals(false, PhoneUtils.isRegisteredPhone("   "))
        assertEquals(false, PhoneUtils.isRegisteredPhone("0"))
        assertEquals(false, PhoneUtils.isRegisteredPhone("NULL"))
        assertEquals(false, PhoneUtils.isRegisteredPhone("null"))
        assertEquals(false, PhoneUtils.isRegisteredPhone("\\N"))
        assertEquals(false, PhoneUtils.isRegisteredPhone("-"))
        assertEquals(false, PhoneUtils.isRegisteredPhone("abc"))
        assertEquals(false, PhoneUtils.isRegisteredPhone("12345"))
        assertEquals(false, PhoneUtils.isRegisteredPhone("M251437"))
        // Bank / government / GSM-provider style senders (not Iranian mobile format).
        assertEquals(false, PhoneUtils.isRegisteredPhone("1000"))
        assertEquals(false, PhoneUtils.isRegisteredPhone("20001390"))
        assertEquals(false, PhoneUtils.isRegisteredPhone("2001390"))
        assertEquals(false, PhoneUtils.isRegisteredPhone("+9820001390"))
        // Same short code as bare digits / 00-prefixed international — the 10-digit
        // "9820001390" must not be mistaken for a bare mobile (no 098x prefix exists).
        assertEquals(false, PhoneUtils.isRegisteredPhone("9820001390"))
        assertEquals(false, PhoneUtils.isRegisteredPhone("009820001390"))
        assertEquals(true, PhoneUtils.isRegisteredPhone("00989121234567"))
        assertEquals(false, PhoneUtils.isRegisteredPhone("88937656753253"))
        assertEquals(false, PhoneUtils.isRegisteredPhone("BankMelli"))
        assertEquals(false, PhoneUtils.isRegisteredPhone("+9821"))
        assertEquals(true, PhoneUtils.isRegisteredPhone("09121234567"))
        assertEquals(true, PhoneUtils.isRegisteredPhone("+989121234567"))
        assertEquals(true, PhoneUtils.isRegisteredPhone("989121234567"))
        assertEquals(true, PhoneUtils.isRegisteredPhone("9121234567"))
        assertEquals(null, PhoneUtils.nullIfUnregistered("NULL"))
        assertEquals(null, PhoneUtils.nullIfUnregistered("not-a-phone"))
        assertEquals("09121234567", PhoneUtils.nullIfUnregistered("+989121234567"))
    }

    @Test
    fun resolveSmsSenderAddress_prefersPhoneOverContactName() {
        assertEquals(
            "09902648065",
            PhoneUtils.resolveSmsSenderAddress(
                originatingAddress = "09902648065",
                displayOriginatingAddress = "Hosein",
            ),
        )
        assertEquals(
            "09902648065",
            PhoneUtils.resolveSmsSenderAddress(
                originatingAddress = "Hosein",
                displayOriginatingAddress = "09902648065",
            ),
        )
        assertEquals(
            "",
            PhoneUtils.resolveSmsSenderAddress(
                originatingAddress = "20001390",
                displayOriginatingAddress = "ملی پیامک",
            ),
        )
        assertEquals(
            "",
            PhoneUtils.resolveSmsSenderAddress(
                originatingAddress = "Hosein",
                displayOriginatingAddress = null,
            ),
        )
    }

    @Test
    fun isRegisteredPhone_accepts0990StyleMobile() {
        assertEquals(true, PhoneUtils.isRegisteredPhone("09902648065"))
        assertEquals(true, PhoneUtils.isRegisteredPhone("+989902648065"))
        assertEquals(true, PhoneUtils.isRegisteredPhone("989902648065"))
        assertEquals(false, PhoneUtils.isRegisteredPhone("Hosein"))
    }
}
