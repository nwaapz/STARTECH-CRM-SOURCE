package com.example.smsgateway

import org.junit.Assert.assertEquals
import org.junit.Test

class SerialGeneratorDuplicateTest {
    @Test
    fun compressToRanges_mergesConsecutiveNumbers() {
        val ranges = SerialGenerator.compressToRanges(listOf(100, 101, 102, 500))
        assertEquals(listOf(100 to 102, 500 to 500), ranges)
    }

    @Test
    fun compressToRanges_emptyList() {
        assertEquals(emptyList<Pair<Int, Int>>(), SerialGenerator.compressToRanges(emptyList()))
    }

    @Test
    fun compressToRanges_singleNumber() {
        assertEquals(listOf(42 to 42), SerialGenerator.compressToRanges(listOf(42)))
    }

    @Test
    fun compressToRanges_deduplicatesAndSorts() {
        val ranges = SerialGenerator.compressToRanges(listOf(5, 3, 4, 3, 5))
        assertEquals(listOf(3 to 5), ranges)
    }

    @Test
    fun compressToRanges_multipleSeparateRanges() {
        val ranges = SerialGenerator.compressToRanges(listOf(1, 2, 10, 11, 12, 20))
        assertEquals(listOf(1 to 2, 10 to 12, 20 to 20), ranges)
    }
}
