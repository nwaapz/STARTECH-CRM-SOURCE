package com.example.smsgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.StringReader

class SerialStateCsvTest {
    @Test
    fun parse_mapsNamedHeaders_andUsedUnusedFromPhone() {
        val csv = """
            serial,phone,date,city,km
            ABC1001,,,
            ABC1002,09121234567,1403/04/15 10:22:00,Tehran,45000
            ABC1003,0,,
            ABC1004,  ,1403/01/01,,
        """.trimIndent()

        val result = SerialStateCsv.parse(StringReader(csv))
        assertEquals(4, result.rowsBySerial.size)

        val unused1 = result.rowsBySerial.getValue("ABC1001")
        assertNull(unused1.phone)
        assertNull(unused1.date)

        val used = result.rowsBySerial.getValue("ABC1002")
        assertEquals("09121234567", used.phone)
        assertEquals("1403/04/15 10:22:00", used.date)
        assertEquals("Tehran", used.city)
        assertEquals(45000, used.km)
        assertTrue(PhoneUtils.isRegisteredPhone(used.phone))

        val unusedSentinel = result.rowsBySerial.getValue("ABC1003")
        assertNull(unusedSentinel.phone)
        assertNull(unusedSentinel.date)
        assertFalse(PhoneUtils.isRegisteredPhone(unusedSentinel.phone))

        val unusedBlankPhone = result.rowsBySerial.getValue("ABC1004")
        assertNull(unusedBlankPhone.phone)
        assertNull(unusedBlankPhone.date)
    }

    @Test
    fun parse_acceptsDateJalaliAlias_andReorderedColumns() {
        val csv = """
            city,date_jalali,serial,phone,km
            Shiraz,1402/12/01,ZZ9,09120001122,12
        """.trimIndent()

        val result = SerialStateCsv.parse(StringReader(csv))
        val row = result.rowsBySerial.getValue("ZZ9")
        assertEquals("09120001122", row.phone)
        assertEquals("1402/12/01 00:00:00", row.date)
        assertEquals("Shiraz", row.city)
        assertEquals(12, row.km)
    }

    @Test
    fun parse_skipsInFileDuplicates_keepsFirst() {
        val csv = """
            serial,phone,date
            AA1,09121111111,1403/01/01 01:00:00
            AA1,09122222222,1403/02/02 02:00:00
        """.trimIndent()

        val result = SerialStateCsv.parse(StringReader(csv))
        assertEquals(1, result.rowsBySerial.size)
        assertEquals(1, result.stats.skippedInFileDuplicates)
        assertEquals("09121111111", result.rowsBySerial.getValue("AA1").phone)
    }

    @Test
    fun parse_reportsInvalidSerialSamples() {
        val csv = """
            serial,phone
            GOOD1,
            BAD-SERIAL,
            ALSO BAD!,
        """.trimIndent()

        val result = SerialStateCsv.parse(StringReader(csv))
        assertEquals(1, result.rowsBySerial.size)
        assertTrue(result.stats.invalidCount >= 2)
        assertTrue(result.stats.invalidSamples.isNotEmpty())
    }

    @Test
    fun parse_requiresSerialHeader() {
        try {
            SerialStateCsv.parse(StringReader("phone,date\n0912,1403/01/01"))
            fail("expected CsvFormatException")
        } catch (e: SerialStateCsv.CsvFormatException) {
            assertTrue(e.message!!.contains("serial"))
        }
    }

    @Test
    fun parse_treatsLiteralNullPhoneAsUnused() {
        val csv = """
            id,serial,phone,km,time,city,car_plate,date_jalali,category,score
            591217,M251437,NULL,NULL,0000-00-00,,NULL,NULL,seller_to_end_user,0
            1,M100,09121234567,10,2024-01-01,Tehran,NULL,1403/01/01 10:00:00,end_user_client,1
        """.trimIndent()

        val result = SerialStateCsv.parse(StringReader(csv))
        assertEquals(2, result.rowsBySerial.size)

        val unused = result.rowsBySerial.getValue("M251437")
        assertNull(unused.phone)
        assertNull(unused.date)
        assertNull(unused.city)
        assertNull(unused.km)
        assertFalse(PhoneUtils.isRegisteredPhone(unused.phone))

        val used = result.rowsBySerial.getValue("M100")
        assertEquals("09121234567", used.phone)
        assertEquals("1403/01/01 10:00:00", used.date)
        assertTrue(PhoneUtils.isRegisteredPhone(used.phone))
    }

    @Test
    fun parse_usesTimeColumnWhenDateJalaliNull_andConvertsGregorian() {
        val csv = """
            id,serial,phone,km,time,city,car_plate,date_jalali,category,score
            591243,M251463,09384997709,NULL,2025-12-21,,NULL,NULL,seller_to_end_user,10
        """.trimIndent()

        val result = SerialStateCsv.parse(StringReader(csv))
        val row = result.rowsBySerial.getValue("M251463")
        assertEquals("09384997709", row.phone)
        // 2025-12-21 Gregorian → Jalali 1404/09/30
        assertEquals("1404/09/30 00:00:00", row.date)
    }

    @Test
    fun parse_prefersDateJalaliOverTime() {
        val csv = """
            serial,phone,time,date_jalali
            ABC1,09121234567,2025-12-21,1403/01/02 15:30:00
        """.trimIndent()

        val row = SerialStateCsv.parse(StringReader(csv)).rowsBySerial.getValue("ABC1")
        assertEquals("1403/01/02 15:30:00", row.date)
    }

    @Test
    fun parseLine_handlesQuotedCommas() {
        val cols = SerialStateCsv.parseLine("""ABC1,"Tehran, Iran",0912""")
        assertEquals(listOf("ABC1", "Tehran, Iran", "0912"), cols)
    }
}
