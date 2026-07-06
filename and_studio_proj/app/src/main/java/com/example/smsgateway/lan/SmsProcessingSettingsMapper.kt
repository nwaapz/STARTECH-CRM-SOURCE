package com.example.smsgateway.lan

import com.example.smsgateway.SmsProcessingConfig

fun SmsProcessingConfig.toResponse(): SmsProcessingSettingsResponse {
    return SmsProcessingSettingsResponse(
        mode = mode.name,
        separator = separator.toString(),
        combinedPattern = combinedPattern.map { it.name },
        stepFieldOrder = stepFieldOrder.map { it.name },
        stepFlowType = stepFlowType.name,
        sessionTimeoutMinutes = sessionTimeoutMinutes,
    )
}

fun SmsProcessingSettingsResponse.toConfig(): SmsProcessingConfig {
    return SmsProcessingConfig.fromJsonObject(
        org.json.JSONObject().apply {
            put("mode", mode)
            put("separator", separator)
            put("combinedPattern", org.json.JSONArray(combinedPattern))
            put("stepFieldOrder", org.json.JSONArray(stepFieldOrder))
            put("stepFlowType", stepFlowType)
            put("sessionTimeoutMinutes", sessionTimeoutMinutes)
        },
    )
}
