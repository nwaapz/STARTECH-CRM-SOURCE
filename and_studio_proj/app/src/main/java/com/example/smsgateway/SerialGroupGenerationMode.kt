package com.example.smsgateway

object SerialGroupGenerationMode {
    const val RANGE = "range"
    const val COMPLEX = "complex"
    const val IMPORT = "import"

    val ALL = setOf(RANGE, COMPLEX, IMPORT)

    const val DEFAULT = RANGE

    fun normalizeOrDefault(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        return if (trimmed in ALL) trimmed else DEFAULT
    }
}
