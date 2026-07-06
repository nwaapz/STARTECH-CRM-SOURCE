package com.example.smsgateway

enum class SmsMessageId(val key: String) {
    REPLY_VALID("reply_valid"),
    REPLY_INVALID("reply_invalid"),
    SESSION_EXPIRED("session_expired"),
    STEP_PROMPT_FIELD("step_prompt_field"),
    STEP_PROMPT_CITY_KM("step_prompt_city_km"),
    KM_INVALID("km_invalid"),
    COMBINED_MISSING_CITY("combined_missing_city"),
    COMBINED_MISSING_KM("combined_missing_km"),
    COMBINED_FORMAT_ERROR("combined_format_error"),
    PARTIAL_MISSING_CITY_KM("partial_missing_city_km"),
    PARTIAL_MISSING_CITY("partial_missing_city"),
    KM_INVALID_WITH_EXAMPLE("km_invalid_with_example"),
    COMPLETION_REMINDER("completion_reminder"),
    COMPLETION_REMINDER_STEP_COMBINED("completion_reminder_step_combined"),
    COMPLETION_REMINDER_STEP_SEQUENTIAL("completion_reminder_step_sequential"),
    ;

    companion object {
        fun fromKey(key: String): SmsMessageId? = entries.find { it.key == key }
    }
}
