package com.example.smsgateway

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsPatternParserTest {
    @Test
    fun parseCombinedFlexible_serialAndKmWithoutCity_leavesCityNull() = runBlocking {
        val result = SmsPatternParser.parseCombinedFlexible(
            message = "m113#3340",
            separator = '#',
            requiredFields = listOf(SmsField.SERIAL, SmsField.KM, SmsField.CITY),
            isValidSerial = { serial -> serial.equals("m113", ignoreCase = true) },
        )

        val fields = result.fields
        assertNull(result.error)
        requireNotNull(fields)
        assertEquals("M113", fields.serial)
        assertEquals(3340, fields.km)
        assertNull(fields.city)
    }

    @Test
    fun parseCombinedFlexible_serialKmAndCity_parsesAllFields() = runBlocking {
        val result = SmsPatternParser.parseCombinedFlexible(
            message = "m113#3340#تبریز",
            separator = '#',
            requiredFields = listOf(SmsField.SERIAL, SmsField.KM, SmsField.CITY),
            isValidSerial = { serial -> serial.equals("m113", ignoreCase = true) },
        )

        val fields = result.fields
        requireNotNull(fields)
        assertEquals("M113", fields.serial)
        assertEquals(3340, fields.km)
        assertEquals("تبریز", fields.city)
    }

    @Test
    fun parseCombinedFlexible_rejectsSeparatorFragmentsAsCity() = runBlocking {
        val result = SmsPatternParser.parseCombinedFlexible(
            message = "m113#3340",
            separator = '#',
            requiredFields = listOf(SmsField.SERIAL, SmsField.KM, SmsField.CITY),
            isValidSerial = { serial -> serial.equals("m113", ignoreCase = true) },
        )

        val city = result.fields?.city
        assertTrue(city.isNullOrBlank() || city.none { it == '#' })
    }

    @Test
    fun extractRemainingFields_bothMissing_parsesCityAndKmTogether() {
        val fields = SmsPatternParser.extractRemainingFields("Tehran 12000", needsCity = true, needsKm = true)

        assertEquals("Tehran", fields.city)
        assertEquals(12000, fields.km)
    }

    @Test
    fun extractRemainingFields_bothMissing_cityOnlyMessage_keepsKmNull() {
        // Customer answers city and km in two separate follow-up SMS: this call only sees
        // the city-only message, so km must stay null (not silently invented/rejected).
        val fields = SmsPatternParser.extractRemainingFields("Tehran", needsCity = true, needsKm = true)

        assertEquals("Tehran", fields.city)
        assertNull(fields.km)
    }

    @Test
    fun extractRemainingFields_bothMissing_kmOnlyMessage_keepsCityNull() {
        val fields = SmsPatternParser.extractRemainingFields("12000", needsCity = true, needsKm = true)

        assertNull(fields.city)
        assertEquals(12000, fields.km)
    }

    @Test
    fun extractRemainingFields_onlyCityMissing_ignoresKmParsing() {
        val fields = SmsPatternParser.extractRemainingFields("Tehran", needsCity = true, needsKm = false)

        assertEquals("Tehran", fields.city)
        assertNull(fields.km)
    }

    @Test
    fun extractRemainingFields_onlyKmMissing_parsesDigitsOnly() {
        val fields = SmsPatternParser.extractRemainingFields("12000", needsCity = false, needsKm = true)

        assertNull(fields.city)
        assertEquals(12000, fields.km)
    }

    @Test
    fun extractRemainingFields_nothingNeeded_returnsNulls() {
        val fields = SmsPatternParser.extractRemainingFields("Tehran 12000", needsCity = false, needsKm = false)

        assertNull(fields.city)
        assertNull(fields.km)
    }

    @Test
    fun extractRemainingFields_blankText_returnsNulls() {
        val fields = SmsPatternParser.extractRemainingFields("   ", needsCity = true, needsKm = true)

        assertNull(fields.city)
        assertNull(fields.km)
    }
}
