package com.example.smsgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelInboundParserTest {
    @Test
    fun parsePhoneThenDataColon() {
        val parsed = PanelInboundParser.parse("9902648065:M228768")
        assertNotNull(parsed)
        assertEquals("M228768", parsed!!.message)
        assertEquals("09902648065", parsed.customerPhone)
    }

    @Test
    fun parsePhoneThenCity() {
        val parsed = PanelInboundParser.parse("9902648065:اهواز")
        assertNotNull(parsed)
        assertEquals("اهواز", parsed!!.message)
        assertEquals("09902648065", parsed.customerPhone)
    }

    @Test
    fun parseDataThenPhoneSemicolon() {
        val parsed = PanelInboundParser.parse("M100 تبریز 3350;9902648065")
        assertNotNull(parsed)
        assertEquals("M100 تبریز 3350", parsed!!.message)
        assertEquals("09902648065", parsed.customerPhone)
    }

    @Test
    fun parseStripsFooterAfterPhoneData() {
        val parsed = PanelInboundParser.parse("9902648065:M228768\nلغو 11")
        assertNotNull(parsed)
        assertEquals("M228768", parsed!!.message)
        assertEquals("09902648065", parsed.customerPhone)
    }

    @Test
    fun parseStripsFooterWithoutSpace() {
        val parsed = PanelInboundParser.parse("9902648065:اهواز\nلغو11")
        assertNotNull(parsed)
        assertEquals("اهواز", parsed!!.message)
        assertEquals("09902648065", parsed.customerPhone)
    }

    @Test
    fun parseStripsPersianDigitFooter() {
        val parsed = PanelInboundParser.parse("9902648065:M100\nلغو ۱۱")
        assertNotNull(parsed)
        assertEquals("M100", parsed!!.message)
        assertEquals("09902648065", parsed.customerPhone)
    }

    @Test
    fun parseUsesLocal09Phone() {
        val parsed = PanelInboundParser.parse("09902648065:M228768")
        assertNotNull(parsed)
        assertEquals("M228768", parsed!!.message)
        assertEquals("09902648065", parsed.customerPhone)
    }

    @Test
    fun parseRejectsInvalidPayload() {
        assertNull(PanelInboundParser.parse("no-separator-here"))
        assertNull(PanelInboundParser.parse(";09121234567"))
        assertNull(PanelInboundParser.parse("message;"))
        assertNull(PanelInboundParser.parse("لغو 11"))
        assertNull(PanelInboundParser.parse("9902648065:"))
        assertNull(PanelInboundParser.parse("onlytext:alsotext"))
    }

    @Test
    fun parseRejectsNonIranianMobilePhoneToken() {
        assertNull(PanelInboundParser.parse("1000:M228768"))
        assertNull(PanelInboundParser.parse("88937656753253:M228768"))
        assertNull(PanelInboundParser.parse("M228768:BankMelli"))
    }

    @Test
    fun isPanelSenderMatchesConfiguredShortCodes() {
        val codes = setOf("20001390", "2001390", "+9820001390")
        assertTrue(PanelInboundParser.isPanelSender("20001390", codes))
        assertTrue(PanelInboundParser.isPanelSender("2001390", codes))
        assertTrue(PanelInboundParser.isPanelSender("+9820001390", codes))
        assertFalse(PanelInboundParser.isPanelSender("09121234567", codes))
        assertFalse(PanelInboundParser.isPanelSender("9902648065", codes))
    }

    @Test
    fun stripMelipayamakFooter_leavesNormalText() {
        assertEquals("M100 تبریز", PanelInboundParser.stripMelipayamakFooter("M100 تبریز"))
        assertEquals("M100", PanelInboundParser.stripMelipayamakFooter("M100\nلغو 11"))
    }
}
