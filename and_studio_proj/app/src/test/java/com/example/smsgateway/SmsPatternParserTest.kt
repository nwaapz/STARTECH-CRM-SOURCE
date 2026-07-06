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
}
