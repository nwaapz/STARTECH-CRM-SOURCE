package com.example.smsgateway

object SerialGroupCategory {
    const val END_USER_CLIENT = "end_user_client"
    const val SELLER_TO_END_USER = "seller_to_end_user"

    val ALL = setOf(END_USER_CLIENT, SELLER_TO_END_USER)

    const val DEFAULT = END_USER_CLIENT

    fun normalizeOrDefault(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        return if (trimmed in ALL) trimmed else DEFAULT
    }

    fun requireValid(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return DEFAULT
        if (trimmed !in ALL) {
            throw SerialGenerator.ValidationException(
                "Category must be \"$END_USER_CLIENT\" or \"$SELLER_TO_END_USER\".",
            )
        }
        return trimmed
    }
}
