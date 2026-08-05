package com.example.smsgateway.lan

import com.example.smsgateway.SerialGroupCategory
import com.example.smsgateway.SerialGroupGenerationMode
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val ok: Boolean = true,
    val version: String = "1.0.0",
    val serverTime: Long,
)

@Serializable
data class ClubAutoMessageTickResponse(
    val ok: Boolean = true,
)

@Serializable
data class StatsResponse(
    val total: Int,
    val unused: Int,
    val registered: Int,
)

@Serializable
data class SimQuotaStatusResponse(
    val label: String,
    val subscriptionId: Int,
    val role: String,
    val sent: Int,
    val limit: Int,
    val remaining: Int,
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
    val removed: Int? = null,
    val message: String? = null,
    val key: String? = null,
    val title: String? = null,
    val at: Long,
)

@Serializable
data class SyncResponse(
    val serverTime: Long,
    val cursor: Long,
    val stats: StatsResponse,
    val changes: List<SyncChangeResponse>,
    val sims: List<SimQuotaStatusResponse> = emptyList(),
)

@Serializable
data class SimQuotaSettingsResponse(
    val dailyLimit: Int,
    val backupSimsEnabled: Boolean,
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
    val category: String = SerialGroupCategory.DEFAULT,
    val warrantyMonths: Int? = null,
    val warrantyKm: Int? = null,
    val imageVerificationRequired: Boolean = false,
)

@Serializable
data class ComplexBatchRequestBody(
    val count: Int,
    val letterCount: Int = 3,
    val digitCount: Int = 7,
    /** Fixed body pattern (C=letter, D=digit). Omit for random letter positions. */
    val pattern: String? = null,
    /** Optional fixed prefix for every serial (empty / omitted = no prefix). */
    val prefix: String? = null,
    val city: String? = null,
    val km: Int? = null,
    val description: String? = null,
    val score: Int = 1,
    val category: String = SerialGroupCategory.DEFAULT,
    val warrantyMonths: Int? = null,
    val warrantyKm: Int? = null,
    val imageVerificationRequired: Boolean = false,
)

@Serializable
data class ImportBatchRequestBody(
    val serials: List<String> = emptyList(),
    val city: String? = null,
    val km: Int? = null,
    val description: String? = null,
    val score: Int = 1,
    val category: String = SerialGroupCategory.DEFAULT,
    val warrantyMonths: Int? = null,
    val warrantyKm: Int? = null,
    val imageVerificationRequired: Boolean = false,
)

@Serializable
data class BatchResponseBody(
    val inserted: Int,
    val skipped: Int,
    val total: Int,
    val groupId: Long? = null,
    val usedInserted: Int = 0,
    val unusedInserted: Int = 0,
    val invalidCount: Int = 0,
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
    val category: String = SerialGroupCategory.DEFAULT,
    val generationMode: String = SerialGroupGenerationMode.DEFAULT,
    val warrantyMonths: Int? = null,
    val warrantyKm: Int? = null,
    val imageVerificationRequired: Boolean = false,
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
data class SerialGroupSerialsResponse(
    val groupId: Long,
    val serials: List<String>,
)

@Serializable
data class UpdateSerialGroupRequestBody(
    val description: String? = null,
    val score: Int? = null,
    val category: String? = null,
    val warrantyMonths: Int? = null,
    val warrantyKm: Int? = null,
    val imageVerificationRequired: Boolean? = null,
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
data class UpdateSerialRegistrationRequest(
    val serial: String,
    val date: String,
    val city: String? = null,
    val km: Int? = null,
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
    val missingCity: Boolean = false,
    val missingKm: Boolean = false,
    val cityInherited: Boolean = false,
    val category: String? = null,
    val completionReminderSent: Boolean = false,
    val completionReminderSentAt: Long? = null,
    val registrationSource: String? = null,
    val pairedRubika: Boolean = false,
    val pairedTelegram: Boolean = false,
    val pairedBale: Boolean = false,
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
    val channel: String = "sms",
)

@Serializable
data class CommunicationSendRequest(
    val phone: String,
    val body: String,
    val channels: List<String> = emptyList(),
)

@Serializable
data class CommunicationSendChannelResult(
    val channel: String,
    val ok: Boolean,
    val error: String? = null,
)

@Serializable
data class CommunicationSendResponse(
    val phone: String,
    val results: List<CommunicationSendChannelResult>,
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
    val excludePairedRubika: Boolean = false,
    val excludePairedTelegram: Boolean = false,
    val excludePairedBale: Boolean = false,
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
    val channel: String = "sms",
    val mediaAssetId: Long? = null,
    val provinceId: String? = null,
    val cityName: String? = null,
    val serialGroupIds: List<Long>? = null,
    val registrationFromDate: String? = null,
    val registrationToDate: String? = null,
    val excludePairedRubika: Boolean = false,
    val excludePairedTelegram: Boolean = false,
    val excludePairedBale: Boolean = false,
)

@Serializable
data class MassSmsMediaResponse(
    val assetId: Long,
    val fileName: String,
    val mimeType: String,
    val kind: String,
    val sizeBytes: Long,
)

@Serializable
data class MassSmsCampaignResponse(
    val id: Long,
    val message: String,
    val delaySeconds: Int,
    val audienceType: String,
    val channel: String = "sms",
    val mediaAssetId: Long? = null,
    val mediaFileName: String? = null,
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
    val skippedCount: Int = 0,
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
data class WhatsAppConfigResponse(
    val searchBoxSelector: String,
    val searchResultSelector: String,
    val messageInputSelector: String,
    val sendButtonSelector: String,
    val openMode: String,
    val searchByPhoneFirst: Boolean,
    val sendDelaySeconds: Int,
    val perContactTimeoutMs: Int,
)

@Serializable
data class WhatsAppConfigUpdateRequest(
    val searchBoxSelector: String,
    val searchResultSelector: String,
    val messageInputSelector: String,
    val sendButtonSelector: String,
    val openMode: String,
    val searchByPhoneFirst: Boolean,
    val sendDelaySeconds: Int,
    val perContactTimeoutMs: Int,
)

@Serializable
data class WhatsAppEngineHeartbeatRequest(
    val loggedIn: Boolean = false,
)

@Serializable
data class WhatsAppEngineStatusResponse(
    val online: Boolean,
    val loggedIn: Boolean,
    val lastSeen: Long?,
    val staleThresholdMs: Long,
)

@Serializable
data class WhatsAppPreviewRequest(
    val audienceType: String,
    val provinceId: String? = null,
    val cityName: String? = null,
    val serialGroupIds: List<Long>? = null,
    val registrationFromDate: String? = null,
    val registrationToDate: String? = null,
    val excludePairedRubika: Boolean = false,
    val excludePairedTelegram: Boolean = false,
    val excludePairedBale: Boolean = false,
)

@Serializable
data class WhatsAppPreviewResponse(
    val count: Int,
    val samplePhones: List<String>,
)

@Serializable
data class WhatsAppCreateRequest(
    val message: String,
    val audienceType: String,
    val provinceId: String? = null,
    val cityName: String? = null,
    val serialGroupIds: List<Long>? = null,
    val registrationFromDate: String? = null,
    val registrationToDate: String? = null,
    val excludePairedRubika: Boolean = false,
    val excludePairedTelegram: Boolean = false,
    val excludePairedBale: Boolean = false,
)

@Serializable
data class WhatsAppCampaignResponse(
    val id: Long,
    val message: String,
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
data class WhatsAppCampaignListResponse(
    val items: List<WhatsAppCampaignResponse>,
)

@Serializable
data class WhatsAppRecipientResponse(
    val id: Long,
    val phone: String,
    val phoneIntl: String,
    val contactName: String? = null,
    val status: String,
    val sentAt: Long? = null,
    val errorMessage: String? = null,
)

@Serializable
data class WhatsAppCampaignDetailResponse(
    val campaign: WhatsAppCampaignResponse,
    val recipients: List<WhatsAppRecipientResponse>,
    val total: Int,
    val page: Int,
    val limit: Int,
)

@Serializable
data class WhatsAppBatchRecipient(
    val recipientId: Long,
    val phoneIntl: String,
    val contactName: String? = null,
)

@Serializable
data class WhatsAppNextBatchResponse(
    val campaignId: Long? = null,
    val message: String? = null,
    val recipients: List<WhatsAppBatchRecipient> = emptyList(),
)

@Serializable
data class WhatsAppReportItem(
    val recipientId: Long,
    val success: Boolean,
    val error: String? = null,
)

@Serializable
data class WhatsAppReportRequest(
    val results: List<WhatsAppReportItem> = emptyList(),
)

@Serializable
data class WhatsAppReportResponse(
    val updated: Int,
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
    val inviteEnabled: Boolean = false,
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
    val inviteEnabled: Boolean = false,
    val inviteMessage: String = "",
    val menuRegisterWarranty: Boolean = true,
    val menuWarrantyStatus: Boolean = true,
    val menuSendProductPhoto: Boolean = true,
    val menuInviteFriends: Boolean = true,
    val menuMyScore: Boolean = true,
    val menuSupport: Boolean = true,
    val menuHelp: Boolean = true,
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
    val inviteEnabled: Boolean = false,
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
    val inviteEnabled: Boolean = false,
    val inviteMessage: String = "",
    val menuRegisterWarranty: Boolean = true,
    val menuWarrantyStatus: Boolean = true,
    val menuSendProductPhoto: Boolean = true,
    val menuInviteFriends: Boolean = true,
    val menuMyScore: Boolean = true,
    val menuSupport: Boolean = true,
    val menuHelp: Boolean = true,
)


@Serializable
data class BaleConnectRequestBody(
    val token: String,
)

@Serializable
data class BaleStatusResponse(
    val active: Boolean,
    val botTitle: String? = null,
    val botUsername: String? = null,
    val serialEnabled: Boolean,
    val inviteEnabled: Boolean = false,
    val polling: Boolean,
)

@Serializable
data class BaleMessageResponse(
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
data class BaleMessageListResponse(
    val items: List<BaleMessageResponse>,
    val total: Int,
)

@Serializable
data class BaleSettingsResponse(
    val serialEnabled: Boolean,
    val inviteEnabled: Boolean = false,
    val inviteMessage: String = "",
    val menuRegisterWarranty: Boolean = true,
    val menuWarrantyStatus: Boolean = true,
    val menuSendProductPhoto: Boolean = true,
    val menuInviteFriends: Boolean = true,
    val menuMyScore: Boolean = true,
    val menuSupport: Boolean = true,
    val menuHelp: Boolean = true,
)
@Serializable
data class ClubAutoMessageCreateRequest(
    val title: String = "",
    val message: String,
    val offsetMonths: Int = 0,
    val offsetDays: Int = 0,
    val sendHour: Int = 10,
    val sendMinute: Int = 0,
    val delaySeconds: Int = 10,
    val dailyCap: Int = 200,
    val sendSms: Boolean = true,
    val sendRubika: Boolean = false,
    val sendTelegram: Boolean = false,
    val sendBale: Boolean = false,
    val serialCategory: String = SerialGroupCategory.DEFAULT,
    val serialGroupIds: List<Long>? = null,
    val enabled: Boolean = true,
    /** warranty_end | warranty_pre | null */
    val autoSource: String? = null,
    val linkedSerialGroupId: Long? = null,
)

@Serializable
data class ClubAutoMessageUpdateRequest(
    val title: String = "",
    val message: String,
    val offsetMonths: Int = 0,
    val offsetDays: Int = 0,
    val sendHour: Int = 10,
    val sendMinute: Int = 0,
    val delaySeconds: Int = 10,
    val dailyCap: Int = 200,
    val sendSms: Boolean = true,
    val sendRubika: Boolean = false,
    val sendTelegram: Boolean = false,
    val sendBale: Boolean = false,
    val serialCategory: String = SerialGroupCategory.DEFAULT,
    val serialGroupIds: List<Long>? = null,
    val enabled: Boolean = true,
    val autoSource: String? = null,
    val linkedSerialGroupId: Long? = null,
)

@Serializable
data class ClubAutoMessageRuleResponse(
    val id: Long,
    val title: String,
    val message: String,
    val offsetMonths: Int,
    val offsetDays: Int,
    val sendHour: Int,
    val sendMinute: Int,
    val delaySeconds: Int = 10,
    val dailyCap: Int = 200,
    val sendSms: Boolean,
    val sendRubika: Boolean,
    val sendTelegram: Boolean,
    val sendBale: Boolean,
    val serialCategory: String = SerialGroupCategory.DEFAULT,
    val serialGroupIds: List<Long>? = null,
    val serialGroupLabels: List<String>? = null,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRunAt: Long? = null,
    val lastRunDateJalali: String? = null,
    val lastSuccessCount: Int = 0,
    val lastFailCount: Int = 0,
    /** Permanent count of recorded successful sends (all channels). */
    val totalSentCount: Int = 0,
    val runStatus: String = "idle",
    val runStartedAt: Long? = null,
    val runTotalCount: Int = 0,
    val runProcessedCount: Int = 0,
    val runSentCount: Int = 0,
    val runFailCount: Int = 0,
    val autoSource: String? = null,
    val linkedSerialGroupId: Long? = null,
)

@Serializable
data class ClubAutoMessageRuleListResponse(
    val items: List<ClubAutoMessageRuleResponse>,
)

@Serializable
data class ClubAutoMessageSendItem(
    val id: Long,
    val phone: String,
    val serial: String = "",
    val channel: String,
    val sentAt: Long,
    val sentDateJalali: String,
)

@Serializable
data class ClubAutoMessageSendListResponse(
    val items: List<ClubAutoMessageSendItem>,
    val total: Int,
    val page: Int,
    val limit: Int,
)

@Serializable
data class ClubAutoMessagePreviewChannelItem(
    val channel: String,
    val status: String,
)

@Serializable
data class ClubAutoMessagePreviewItem(
    val phone: String,
    val serial: String = "",
    val channels: List<ClubAutoMessagePreviewChannelItem>,
    val willSendAny: Boolean,
)

@Serializable
data class ClubAutoMessagePreviewResponse(
    val items: List<ClubAutoMessagePreviewItem>,
    val total: Int,
    val pendingCount: Int,
    val blockedCount: Int,
    val alreadyFullySentCount: Int,
    val cutoffDateJalali: String,
    val dailyCap: Int = 200,
    val sentToday: Int = 0,
    val remainingToday: Int = 200,
    val page: Int,
    val limit: Int,
)

@Serializable
data class SerialPhotoSubmissionResponse(
    val id: Long,
    val serial: String,
    val serialRowId: Long? = null,
    val groupId: Long? = null,
    val groupLabel: String? = null,
    val phone: String,
    val channel: String,
    val status: String,
    val imageUrl: String,
    val mimeType: String? = null,
    val score: Int = 0,
    val scoreAwarded: Int = 0,
    val createdAt: Long,
    val reviewedAt: Long? = null,
    val reviewNote: String? = null,
)

@Serializable
data class SerialPhotoSubmissionListResponse(
    val items: List<SerialPhotoSubmissionResponse>,
    val total: Int,
    val page: Int,
    val limit: Int,
)

@Serializable
data class SerialPhotoReviewRequest(
    val note: String? = null,
    /** Reject reason: unclear | not_related | wrong_serial | other */
    val reasonCode: String? = null,
)

@Serializable
data class ReferralSettingsResponse(
    val enabled: Boolean = false,
    val basePoints: Int = 50,
    val decayPercent: Int = 50,
    val maxDepth: Int = 10,
)

@Serializable
data class ReferralCustomerResponse(
    val phone: String,
    val referralCode: String,
    val referredBy: String? = null,
    val directDownline: Int = 0,
    val score: Int = 0,
    val createdAt: Long,
)

@Serializable
data class ReferralCustomerListResponse(
    val items: List<ReferralCustomerResponse>,
    val total: Int,
    val page: Int,
    val limit: Int,
)

@Serializable
data class ReferralSummaryResponse(
    val totalCustomers: Int,
    val referredCustomers: Int,
)

@Serializable
data class AuthLoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class AuthLoginResponse(
    val token: String,
    val username: String,
    val role: Int,
)

@Serializable
data class AuthMeResponse(
    val id: Long,
    val username: String,
    val role: Int,
)

@Serializable
data class CrmUserResponse(
    val id: Long,
    val username: String,
    val role: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class CrmUserCreateRequest(
    val username: String,
    val password: String,
    val role: Int,
)

@Serializable
data class CrmPasswordChangeRequest(
    val password: String,
)

@Serializable
data class LotteryParticipantResponse(
    val phone: String,
    val score: Int,
    val city: String? = null,
)

@Serializable
data class LotteryParticipantListResponse(
    val items: List<LotteryParticipantResponse>,
    val total: Int,
)

@Serializable
data class LotteryDrawResponse(
    val phone: String,
    val score: Int,
    val city: String? = null,
)

@Serializable
data class LotteryConfirmRequest(
    val phone: String,
)

@Serializable
data class LotteryWinnerResponse(
    val id: Long,
    val phone: String,
    val city: String? = null,
    val scoreAtWin: Int,
    val dateJalali: String,
    val wonAt: Long,
)

@Serializable
data class LotteryWinnerListResponse(
    val items: List<LotteryWinnerResponse>,
    val total: Int,
    val page: Int,
    val limit: Int,
)

@Serializable
data class LotterySuspendRequest(
    val phone: String,
    val reason: String? = null,
)

@Serializable
data class LotteryPresetRequest(
    val phone: String? = null,
)

@Serializable
data class LotteryPresetResponse(
    val phone: String? = null,
)

@Serializable
data class DayCountResponse(
    val day: String,
    val count: Int,
)

@Serializable
data class RegistrationsByDayResponse(
    val from: String,
    val to: String,
    val days: List<DayCountResponse>,
)

@Serializable
data class CloudSyncStatusResponse(
    val configured: Boolean,
    val url: String? = null,
    val pendingOutbox: Int = 0,
    val lastPushMs: Long = 0,
    val lastPullMs: Long = 0,
    val running: Boolean = false,
    /** idle | fetch | push | apply | conflicts | done | error */
    val phase: String = "idle",
    val percent: Int = 0,
    val detail: String? = null,
    val pushDone: Int = 0,
    val pushTotal: Int = 0,
    val pullPage: Int = 0,
    val pullPagesEstimate: Int = 0,
    val applyRowsDone: Int = 0,
    val applyRowsTotal: Int = 0,
    val repairGroupsDone: Int = 0,
    val repairGroupsTotal: Int = 0,
    val conflictCount: Int = 0,
    val pendingRemote: Int = 0,
)

@Serializable
data class CloudSyncConfigRequest(
    val url: String? = null,
    val token: String? = null,
)

@Serializable
data class OkResponse(
    val ok: Boolean = true,
    val message: String? = null,
)
