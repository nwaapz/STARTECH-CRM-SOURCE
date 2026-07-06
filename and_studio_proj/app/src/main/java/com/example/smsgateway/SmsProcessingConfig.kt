package com.example.smsgateway

import org.json.JSONArray
import org.json.JSONObject

enum class SmsProcessingMode {
    SERIAL_ONLY,
    COMBINED_PATTERN,
    STEP_BY_STEP,
}

enum class SmsField {
    SERIAL,
    CITY,
    KM,
}

enum class StepFlowType {
    SEQUENTIAL,
    COMBINED_SECOND,
}

data class SmsProcessingConfig(
    val mode: SmsProcessingMode = SmsProcessingMode.SERIAL_ONLY,
    val separator: Char = '#',
    val combinedPattern: List<SmsField> = listOf(SmsField.SERIAL, SmsField.KM, SmsField.CITY),
    val stepFieldOrder: List<SmsField> = listOf(SmsField.SERIAL, SmsField.CITY, SmsField.KM),
    val stepFlowType: StepFlowType = StepFlowType.SEQUENTIAL,
    val sessionTimeoutMinutes: Int = 30,
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("mode", mode.name)
            put("separator", separator.toString())
            put("combinedPattern", JSONArray(combinedPattern.map { it.name }))
            put("stepFieldOrder", JSONArray(stepFieldOrder.map { it.name }))
            put("stepFlowType", stepFlowType.name)
            put("sessionTimeoutMinutes", sessionTimeoutMinutes)
        }
    }

    fun validate(): String? {
        if (separator !in ALLOWED_SEPARATORS) {
            return "Separator must be one of: ${ALLOWED_SEPARATORS.joinToString()}"
        }
        if (sessionTimeoutMinutes !in 1..1440) {
            return "Session timeout must be between 1 and 1440 minutes"
        }
        if (mode == SmsProcessingMode.COMBINED_PATTERN) {
            if (combinedPattern.count { it == SmsField.SERIAL } != 1) {
                return "Combined pattern must include serial exactly once"
            }
            if (combinedPattern.isEmpty()) {
                return "Combined pattern cannot be empty"
            }
        }
        if (mode == SmsProcessingMode.STEP_BY_STEP) {
            if (stepFieldOrder.firstOrNull() != SmsField.SERIAL) {
                return "Step field order must start with serial"
            }
            if (stepFieldOrder.count { it == SmsField.SERIAL } != 1) {
                return "Step field order must include serial exactly once"
            }
        }
        return null
    }

    fun normalized(): SmsProcessingConfig {
        return when (mode) {
            SmsProcessingMode.COMBINED_PATTERN -> copy(combinedPattern = DEFAULT_COMBINED_PATTERN)
            else -> this
        }
    }

    fun stepFieldsAfterSerial(): List<SmsField> = stepFieldOrder.filter { it != SmsField.SERIAL }

    companion object {
        val ALLOWED_SEPARATORS = setOf('#', '*', '-')
        val DEFAULT_COMBINED_PATTERN = listOf(SmsField.SERIAL, SmsField.KM, SmsField.CITY)
        const val SESSION_COMBINED = "COMBINED"

        fun default(): SmsProcessingConfig = SmsProcessingConfig()

        fun fromJsonObject(json: JSONObject): SmsProcessingConfig {
            val mode = runCatching {
                SmsProcessingMode.valueOf(json.optString("mode", SmsProcessingMode.SERIAL_ONLY.name))
            }.getOrDefault(SmsProcessingMode.SERIAL_ONLY)

            val separatorRaw = json.optString("separator", "#")
            val separator = separatorRaw.firstOrNull()?.takeIf { it in ALLOWED_SEPARATORS } ?: '#'

            val combinedPattern = parseFieldList(
                json.optJSONArray("combinedPattern"),
                listOf(SmsField.SERIAL, SmsField.KM, SmsField.CITY),
            )
            val stepFieldOrder = parseFieldList(
                json.optJSONArray("stepFieldOrder"),
                listOf(SmsField.SERIAL, SmsField.CITY, SmsField.KM),
            )
            val stepFlowType = runCatching {
                StepFlowType.valueOf(json.optString("stepFlowType", StepFlowType.SEQUENTIAL.name))
            }.getOrDefault(StepFlowType.SEQUENTIAL)

            val sessionTimeoutMinutes = json.optInt("sessionTimeoutMinutes", 30).coerceIn(1, 1440)

            return SmsProcessingConfig(
                mode = mode,
                separator = separator,
                combinedPattern = combinedPattern,
                stepFieldOrder = stepFieldOrder,
                stepFlowType = stepFlowType,
                sessionTimeoutMinutes = sessionTimeoutMinutes,
            )
        }

        fun fromJsonString(raw: String?): SmsProcessingConfig {
            if (raw.isNullOrBlank()) return default()
            return runCatching { fromJsonObject(JSONObject(raw)) }.getOrDefault(default())
        }

        private fun parseFieldList(array: JSONArray?, fallback: List<SmsField>): List<SmsField> {
            if (array == null || array.length() == 0) return fallback
            val parsed = (0 until array.length()).mapNotNull { index ->
                runCatching { SmsField.valueOf(array.getString(index)) }.getOrNull()
            }
            return parsed.ifEmpty { fallback }
        }

        fun fieldPreviewLabel(field: SmsField): String = when (field) {
            SmsField.SERIAL -> "سریال"
            SmsField.CITY -> "شهر"
            SmsField.KM -> "کیلومتر"
        }

        fun fieldPromptLabel(field: SmsField): String = when (field) {
            SmsField.SERIAL -> "شماره سریال"
            SmsField.CITY -> "شهر"
            SmsField.KM -> "کیلومتر"
        }
    }
}
