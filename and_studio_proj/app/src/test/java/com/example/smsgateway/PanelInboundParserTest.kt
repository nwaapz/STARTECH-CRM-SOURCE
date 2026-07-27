package com.example.smsgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelInboundParserTest {
    @Test
    fun parseSemicolonFormat() {
        val parsed = PanelInboundParser.parse("M100 تبریز 3350;09121234567")
        assertNotNull(parsed)
        assertEquals("M100 تبریز 3350", parsed!!.message)
        assertEquals("09121234567", parsed.customerPhone)
    }

    @Test
    fun parseColonFormat() {
        val parsed = PanelInboundParser.parse("M100 تبریز 3350:09129876543")
        assertNotNull(parsed)
        assertEquals("M100 تبریز 3350", parsed!!.message)
        assertEquals("09129876543", parsed.customerPhone)
    }

    @Test
    fun parseUsesLastSeparatorWhenMessageContainsColon() {
        val parsed = PanelInboundParser.parse("part1:part2;09121111111")
        assertNotNull(parsed)
        assertEquals("part1:part2", parsed!!.message)
        assertEquals("09121111111", parsed.customerPhone)
    }

    @Test
    fun parseRejectsInvalidPayload() {
        assertNull(PanelInboundParser.parse("no-separator-here"))
        assertNull(PanelInboundParser.parse(";09121234567"))
        assertNull(PanelInboundParser.parse("message;"))
    }

    @Test
    fun isPanelSenderMatchesConfiguredShortCodes() {
        val codes = setOf("20001390", "2001390", "+9820001390")
        assertTrue(PanelInboundParser.isPanelSender("20001390", codes))
        assertTrue(PanelInboundParser.isPanelSender("+9820001390", codes))
        assertFalse(PanelInboundParser.isPanelSender("09121234567", codes))
    }
}
