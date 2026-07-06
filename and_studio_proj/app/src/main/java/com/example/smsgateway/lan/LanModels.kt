package com.example.smsgateway.lan

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val ok: Boolean = true,
    val version: String = "1.0.0",
    val serverTime: Long,
)

@Serializable
data class StatsResponse(
    val total: Int,
    val unused: Int,
    val registered: Int,
)

@Serializable
data class SyncChangeResponse(
    val type: String,
    val serial: String? = null,
    val phone: String? = null,
    val date: String? = null,
    val city: String? = null,
    val km: Int? = null,
    val inserted: Int? = null,
    val skipped: Int? = null,
    val total: Int? = null,
    val at: Long,
)

@Serializable
data class SyncResponse(
    val serverTime: Long,
    val cursor: Long,
    val stats: StatsResponse,
    val changes: List<SyncChangeResponse>,
)

@Serializable
data class BatchRequestBody(
    val prefix: String = "",
    val start: Int,
    val end: Int,
    val padWidth: Int = 0,
    val city: String? = null,
    val km: Int? = null,
    val description: String? = null,
    val score: Int = 1,
)

@Serializable
data class BatchResponseBody(
    val inserted: Int,
    val skipped: Int,
    val total: Int,
    val groupId: Long? = null,
)

@Serializable
data class SerialGroupResponse(
    val id: Long,
    val prefix: String,
    val startNumber: Int,
    val endNumber: Int,
    val padWidth: Int,
    val rangeLabel: String,
    val description: String? = null,
    val score: Int,
    val total: Int,
    val used: Int,
    val unused: Int,
    val salePercentage: Double,
    val createdAt: Long,
)

@Serializable
data class SerialGroupListResponse(
    val items: List<SerialGroupResponse>,
)

@Serializable
data class UpdateSerialGroupRequestBody(
    val description: String? = null,
    val score: Int? = null,
)

@Serializable
data class ExtendSerialGroupRangeRequestBody(
    val addBelow: Int? = null,
    val addAbove: Int? = null,
    val newStart: Int? = null,
    val newEnd: Int? = null,
)

@Serializable
data class ExtendSerialGroupRangeResponseBody(
    val inserted: Int,
    val skipped: Int,
    val startNumber: Int,
    val endNumber: Int,
    val group: SerialGroupResponse,
)

@Serializable
data class SerialRowResponse(
    val id: Long,
    val serial: String,
    val phone: String? = null,
    val date: String? = null,
    val city: String? = null,
    val km: Int? = null,
    val needsCompletion: Boolean = false,
    val completionReminderSent: Boolean = false,
    val completionReminderSentAt: Long? = null,
)

@Serializable
data class SerialListResponse(
    val items: List<SerialRowResponse>,
    val total: Int,
    val page: Int,
    val limit: Int,
)

@Serializable
data class ErrorResponse(
    val error: String,
)

@Serializable
data class DuplicateRangeResponse(
    val rangeLabel: String,
    val count: Int,
)

@Serializable
data class DuplicateSerialErrorResponse(
    val error: String,
    val duplicateCount: Int,
    val duplicateRanges: List<DuplicateRangeResponse>,
)

@Serializable
data class SmsProcessingSettingsResponse(
    val mode: String,
    val separator: String,
    val combinedPattern: List<String>,
    val stepFieldOrder: List<String>,
    val stepFlowType: String,
    val sessionTimeoutMinutes: Int,
)

@Serializable
data class SmsMessagePlaceholderHintResponse(
    val token: String,
    val labelFa: String,
)

@Serializable
data class SmsMessageDefinitionResponse(
    val id: String,
    val categoryFa: String,
    val titleFa: String,
    val descriptionFa: String,
    val placeholders: List<SmsMessagePlaceholderHintResponse>,
    val template: String,
)

@Serializable
data class SmsMessagesSettingsResponse(
    val messages: List<SmsMessageDefinitionResponse>,
)

@Serializable
data class CommunicationMessageResponse(
    val id: Long,
    val phone: String,
    val direction: String,
    val body: String,
    val timestamp: Long,
    val status: String? = null,
    val simLabel: String? = null,
)

@Serializable
data class CommunicationListResponse(
    val items: List<CommunicationMessageResponse>,
    val total: Int,
    val page: Int,
    val limit: Int,
)

@Serializable
data class CommunicationThreadResponse(
    val phone: String,
    val items: List<CommunicationMessageResponse>,
    val hasMore: Boolean,
)

@Serializable
data class SerialRegistrationErrorResponse(
    val phone: String,
    val serial: String,
    val reason: String,
    val at: Long,
)

@Serializable
data class SerialErrorClientSummaryResponse(
    val phone: String,
    val latestAt: Long,
    val latestSerial: String,
    val latestReason: String,
    val attemptCount: Int,
)

@Serializable
data class SerialErrorClientListResponse(
    val items: List<SerialErrorClientSummaryResponse>,
    val total: Int,
    val page: Int,
    val limit: Int,
)

@Serializable
data class SerialErrorListResponse(
    val phone: String,
    val items: List<SerialRegistrationErrorResponse>,
)

@Serializable
data class IranProvinceResponse(
    val id: String,
    val name: String,
    val cities: List<String>,
)

@Serializable
data class IranProvinceListResponse(
    val items: List<IranProvinceResponse>,
)

@Serializable
data class MassSmsPreviewRequest(
    val audienceType: String,
    val provinceId: String? = null,
    val cityName: String? = null,
    val serialGroupIds: List<Long>? = null,
    val registrationFromDate: String? = null,
    val registrationToDate: String? = null,
)

@Serializable
data class MassSmsPreviewResponse(
    val count: Int,
    val samplePhones: List<String>,
)

@Serializable
data class MassSmsCreateRequest(
    val message: String,
    val delaySeconds: Int = 10,
    val audienceType: String,
    val provinceId: String? = null,
    val cityName: String? = null,
    val serialGroupIds: List<Long>? = null,
    val registrationFromDate: String? = null,
    val registrationToDate: String? = null,
)

@Serializable
data class MassSmsCampaignResponse(
    val id: Long,
    val message: String,
    val delaySeconds: Int,
    val audienceType: String,
    val provinceId: String? = null,
    val provinceName: String? = null,
    val cityName: String? = null,
    val serialGroupIds: List<Long>? = null,
    val serialGroupLabels: List<String>? = null,
    val registrationFromDate: String? = null,
    val registrationToDate: String? = null,
    val status: String,
    val totalCount: Int,
    val sentCount: Int,
    val failedCount: Int,
    val pendingCount: Int,
    val createdAt: Long,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val queueOrder: Long,
    val queuePosition: Int? = null,
)

@Serializable
data class MassSmsCampaignListResponse(
    val items: List<MassSmsCampaignResponse>,
)

@Serializable
data class MassSmsRecipientResponse(
    val id: Long,
    val phone: String,
    val status: String,
    val sentAt: Long? = null,
    val errorMessage: String? = null,
)

@Serializable
data class MassSmsCampaignDetailResponse(
    val campaign: MassSmsCampaignResponse,
    val recipients: List<MassSmsRecipientResponse>,
    val total: Int,
    val page: Int,
    val limit: Int,
)

@Serializable
data class RubikaConnectRequestBody(
    val token: String,
)

@Serializable
data class RubikaStatusResponse(
    val active: Boolean,
    val botTitle: String? = null,
    val botUsername: String? = null,
    val serialEnabled: Boolean,
    val polling: Boolean,
)

@Serializable
data class RubikaMessageResponse(
    val messageId: String,
    val chatId: String,
    val senderId: String? = null,
    val text: String,
    val timestamp: Long,
    val direction: String,
    val phoneNumber: String? = null,
    val senderName: String? = null,
)

@Serializable
data class RubikaMessageListResponse(
    val items: List<RubikaMessageResponse>,
    val total: Int,
)

@Serializable
data class RubikaSettingsResponse(
    val serialEnabled: Boolean,
)

@Serializable
data class TelegramConnectRequestBody(
    val token: String,
)

@Serializable
data class TelegramStatusResponse(
    val active: Boolean,
    val botTitle: String? = null,
    val botUsername: String? = null,
    val serialEnabled: Boolean,
    val polling: Boolean,
)

@Serializable
data class TelegramMessageResponse(
    val messageId: String,
    val chatId: String,
    val senderId: String? = null,
    val text: String,
    val timestamp: Long,
    val direction: String,
    val phoneNumber: String? = null,
    val senderName: String? = null,
)

@Serializable
data class TelegramMessageListResponse(
    val items: List<TelegramMessageResponse>,
    val total: Int,
)

@Serializable
data class TelegramSettingsResponse(
    val serialEnabled: Boolean,
)
