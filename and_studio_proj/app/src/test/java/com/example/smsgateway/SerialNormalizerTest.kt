package com.example.smsgateway

import org.junit.Assert.assertEquals
import org.junit.Test

class SerialNormalizerTest {
    @Test
    fun normalizePrefix_uppercasesLetters() {
        assertEquals("S", SerialNormalizer.normalizePrefix("s"))
        assertEquals("M", SerialNormalizer.normalizePrefix(" m "))
    }

    @Test
    fun normalizeSerial_treatsMixedCaseAsEquivalent() {
        assertEquals("S100", SerialNormalizer.normalizeSerial("s100"))
        assertEquals("S100", SerialNormalizer.normalizeSerial("S100"))
        assertEquals("M900000", SerialNormalizer.normalizeSerial("m900000"))
    }
}
