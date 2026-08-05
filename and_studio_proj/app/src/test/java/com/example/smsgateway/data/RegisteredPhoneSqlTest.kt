package com.example.smsgateway.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegisteredPhoneSqlTest {
    @Test
    fun clauses_avoidExpensiveGlobAndReplace() {
        assertFalse(RegisteredPhoneSql.IS_REGISTERED.contains("GLOB", ignoreCase = true))
        assertFalse(RegisteredPhoneSql.IS_REGISTERED.contains("replace(", ignoreCase = true))
        assertTrue(RegisteredPhoneSql.IS_REGISTERED.contains("phone IS NOT NULL"))
        assertTrue(RegisteredPhoneSql.IS_UNUSED.startsWith("NOT ("))
    }
}
