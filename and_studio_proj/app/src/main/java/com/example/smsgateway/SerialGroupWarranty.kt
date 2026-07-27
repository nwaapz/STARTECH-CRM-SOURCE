package com.example.smsgateway

object SerialGroupWarranty {
    fun requireValid(months: Int?, km: Int?): Pair<Int?, Int?> {
        val normalizedMonths = months?.takeIf { it > 0 }
        val normalizedKm = km?.takeIf { it > 0 }
        if (normalizedMonths == null && normalizedKm == null) {
            throw SerialGenerator.ValidationException(
                "حداقل یکی از مدت گارانتی (ماه) یا کیلومتر گارانتی باید وارد شود.",
            )
        }
        return normalizedMonths to normalizedKm
    }

    fun normalizeOptional(months: Int?, km: Int?): Pair<Int?, Int?> {
        return months?.takeIf { it > 0 } to km?.takeIf { it > 0 }
    }

    fun formatFa(months: Int?, km: Int?): String? {
        val parts = mutableListOf<String>()
        if (months != null && months > 0) {
            parts.add("$months ماه")
        }
        if (km != null && km > 0) {
            parts.add("$km کیلومتر")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" یا ")
    }
}
