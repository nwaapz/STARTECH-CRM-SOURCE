package com.datacall.ccp.util

object PhoneUtils {
    fun normalizePhone(phone: String): String {
        val trimmed = normalizeToAsciiDigits(phone.trim()).replace(" ", "").replace("-", "")
        if (trimmed.startsWith("+98") && trimmed.length >= 13) {
            return "0" + trimmed.substring(3)
        }
        if (trimmed.startsWith("98") && trimmed.length >= 12 && trimmed[2].isDigit()) {
            return "0" + trimmed.substring(2)
        }
        if (trimmed.length == 10 && trimmed.startsWith('9') && trimmed.all(Char::isDigit)) {
            return "0$trimmed"
        }
        return trimmed
    }

    fun normalizeToAsciiDigits(input: String): String {
        return buildString(input.length) {
            input.forEach { ch ->
                when {
                    ch in '0'..'9' -> append(ch)
                    ch.isDigit() -> {
                        val digit = Character.digit(ch, 10)
                        if (digit >= 0) append('0' + digit)
                    }
                    else -> append(ch)
                }
            }
        }
    }

    fun phonesMatch(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val na = normalizePhone(a)
        val nb = normalizePhone(b)
        if (na == nb) return true
        return na.takeLast(10) == nb.takeLast(10)
    }
}
