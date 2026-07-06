package com.datacall.ccp.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class Crc32Test {
    @Test
    fun compute_isDeterministic() {
        val data = "test payload".toByteArray()
        val first = Crc32.compute(data)
        val second = Crc32.compute(data)
        assertEquals(first, second)
    }

    @Test
    fun writeReadRoundTrip() {
        val value = 0x12345678
        val bytes = Crc32.writeInt(value)
        assertEquals(value, Crc32.readInt(bytes, 0))
    }
}
