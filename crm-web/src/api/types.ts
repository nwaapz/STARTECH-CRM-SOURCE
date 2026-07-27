export interface HealthResponse {
  ok: boolean
  version: string
  serverTime: number
}

export interface Stats {
  total: number
  unused: number
  registered: number
}

export interface SimQuotaStatus {
  label: string
  subscriptionId: number
  role: 'primary' | 'backup' | string
  sent: number
  limit: number
  remaining: number
}

export interface SimQuotaSettings {
  dailyLimit: number
  backupSimsEnabled: boolean
}

export interface SyncChange {
  type:
    | 'registered'
    | 'registration_updated'
    | 'serials_added'
    | 'serials_removed'
    | 'settings_changed'
    | 'club_auto_created'
    | 'club_auto_updated'
    | 'club_auto_deleted'
    | 'club_auto_run'
    | 'photo_approved'
    | 'photo_rejected'
    | 'bot_connected'
    | 'bot_disconnected'
    | string
  serial?: string
  phone?: string
  date?: string
  city?: string
  km?: number
  inserted?: number
  skipped?: number
  total?: number
  removed?: number
  groupId?: number
  key?: string
  title?: string
  ruleId?: number
  message?: string
  at: number
}

export interface SyncResponse {
  serverTime: number
  cursor: number
  stats: Stats
  changes: SyncChange[]
  sims?: SimQuotaStatus[]
}

export type SerialGroupCategory = 'end_user_client' | 'seller_to_end_user'
export type SerialGroupGenerationMode = 'range' | 'complex' | 'import'

export interface BatchRequest {
  prefix: string
  start: number
  end: number
  padWidth: number
  city?: string
  km?: number
  description?: string
  score?: number
  category?: SerialGroupCategory
  warrantyMonths?: number | null
  warrantyKm?: number | null
  imageVerificationRequired?: boolean
}

export interface ComplexBatchRequest {
  count: number
  letterCount: number
  digitCount: number
  /** Optional fixed prefix (e.g. "G"). Omit or empty for no prefix. */
  prefix?: string
  city?: string
  km?: number
  description?: string
  score?: number
  category?: SerialGroupCategory
  warrantyMonths?: number | null
  warrantyKm?: number | null
  imageVerificationRequired?: boolean
}

export interface ImportBatchRequest {
  serials: string[]
  city?: string
  km?: number
  description?: string
  score?: number
  category?: SerialGroupCategory
  warrantyMonths?: number | null
  warrantyKm?: number | null
  imageVerificationRequired?: boolean
}

export interface BatchResponse {
  inserted: number
  skipped: number
  total: number
  groupId?: number | null
}

export interface SerialGroup {
  id: number
  prefix: string
  startNumber: number
  endNumber: number
  padWidth: number
  rangeLabel: string
  description?: string | null
  score: number
  category?: SerialGroupCategory
  generationMode?: SerialGroupGenerationMode
  warrantyMonths?: number | null
  warrantyKm?: number | null
  imageVerificationRequired?: boolean
  total: number
  used: number
  unused: number
  salePercentage: number
  createdAt: number
}

export interface SerialGroupListResponse {
  items: SerialGroup[]
}

export interface SerialGroupSerialsResponse {
  groupId: number
  serials: string[]
}

export interface UpdateSerialGroupRequest {
  description?: string | null
  score?: number
  category?: SerialGroupCategory
  warrantyMonths?: number | null
  warrantyKm?: number | null
  imageVerificationRequired?: boolean
}

export interface ExtendSerialGroupRangeRequest {
  addBelow?: number
  addAbove?: number
  newStart?: number
  newEnd?: number
}

export interface ExtendSerialGroupRangeResponse {
  inserted: number
  skipped: number
  startNumber: number
  endNumber: number
  group: SerialGroup
}

export type RegistrationSource = 'sms' | 'rubika' | 'telegram' | 'bale'

export interface SerialRow {
  id: number
  serial: string
  phone?: string
  date?: string
  city?: string
  km?: number
  needsCompletion?: boolean
  missingCity?: boolean
  missingKm?: boolean
  /** City shown from a prior registration for the same phone (not stored on this serial). */
  cityInherited?: boolean
  category?: SerialGroupCategory | string | null
  completionReminderSent?: boolean
  completionReminderSentAt?: number | null
  registrationSource?: RegistrationSource | string | null
  pairedRubika?: boolean
  pairedTelegram?: boolean
  pairedBale?: boolean
}

export interface UpdateSerialRowRequest {
  serial: string
  date: string
  city?: string | null
  km?: number | null
}

export interface SerialListResponse {
  items: SerialRow[]
  total: number
  page: number
  limit: number
}

export interface ApiError {
  error: string
}

export interface DuplicateRangeItem {
  rangeLabel: string
  count: number
}

export interface DuplicateSerialErrorBody {
  error: string
  duplicateCount: number
  duplicateRanges: DuplicateRangeItem[]
}

export class DuplicateSerialError extends Error {
  duplicateCount: number
  duplicateRanges: DuplicateRangeItem[]

  constructor(body: DuplicateSerialErrorBody) {
    super(body.error)
    this.name = 'DuplicateSerialError'
    this.duplicateCount = body.duplicateCount
    this.duplicateRanges = body.duplicateRanges
  }
}

export function isDuplicateSerialError(e: unknown): e is DuplicateSerialError {
  return e instanceof DuplicateSerialError
}

export type SmsField = 'SERIAL' | 'CITY' | 'KM'
export type SmsProcessingMode = 'SERIAL_ONLY' | 'COMBINED_PATTERN' | 'STEP_BY_STEP'
export type StepFlowType = 'SEQUENTIAL' | 'COMBINED_SECOND'

export interface SmsProcessingSettings {
  mode: SmsProcessingMode
  separator: string
  combinedPattern: SmsField[]
  stepFieldOrder: SmsField[]
  stepFlowType: StepFlowType
  sessionTimeoutMinutes: number
}

export interface SmsMessagePlaceholderHint {
  token: string
  labelFa: string
}

export interface SmsMessageDefinition {
  id: string
  categoryFa: string
  titleFa: string
  descriptionFa: string
  placeholders: SmsMessagePlaceholderHint[]
  template: string
}

export interface SmsMessagesSettings {
  messages: SmsMessageDefinition[]
}

export type CommunicationDirection = 'incoming' | 'outgoing'

export type CommunicationChannel = 'sms' | 'rubika' | 'telegram' | 'bale'

export interface CommunicationMessage {
  id: number
  phone: string
  direction: CommunicationDirection
  body: string
  timestamp: number
  status?: string | null
  simLabel?: string | null
  channel?: CommunicationChannel | string
}

export interface CommunicationSendRequest {
  phone: string
  body: string
  channels: CommunicationChannel[]
}

export interface CommunicationSendChannelResult {
  channel: CommunicationChannel | string
  ok: boolean
  error?: string | null
}

export interface CommunicationSendResponse {
  phone: string
  results: CommunicationSendChannelResult[]
}

export interface CommunicationListResponse {
  items: CommunicationMessage[]
  total: number
  page: number
  limit: number
}

export interface CommunicationThreadResponse {
  phone: string
  items: CommunicationMessage[]
  hasMore: boolean
}

export type SerialRegistrationErrorReason = 'not_found' | 'already_used'

export interface SerialRegistrationError {
  phone: string
  serial: string
  reason: SerialRegistrationErrorReason
  at: number
}

export interface SerialErrorClientSummary {
  phone: string
  latestAt: number
  latestSerial: string
  latestReason: SerialRegistrationErrorReason
  attemptCount: number
}

export interface SerialErrorClientListResponse {
  items: SerialErrorClientSummary[]
  total: number
  page: number
  limit: number
}

export interface SerialErrorListResponse {
  phone: string
  items: SerialRegistrationError[]
}

export interface IranProvince {
  id: string
  name: string
  cities: string[]
}

export interface IranProvinceListResponse {
  items: IranProvince[]
}

export type MassSmsAudienceType = 'registered' | 'error' | 'all'

export type MassSmsChannel = 'sms' | 'rubika' | 'telegram' | 'bale'

export type MassSmsCampaignStatus = 'queued' | 'running' | 'paused' | 'completed' | 'cancelled'

export interface MassSmsMediaResponse {
  assetId: number
  fileName: string
  mimeType: string
  kind: 'photo' | 'document'
  sizeBytes: number
}

export interface MassSmsPreviewRequest {
  audienceType: MassSmsAudienceType
  provinceId?: string | null
  cityName?: string | null
  serialGroupIds?: number[] | null
  registrationFromDate?: string | null
  registrationToDate?: string | null
  excludePairedRubika?: boolean
  excludePairedTelegram?: boolean
  excludePairedBale?: boolean
}

export interface MassSmsPreviewResponse {
  count: number
  samplePhones: string[]
}

export interface MassSmsCreateRequest {
  message: string
  delaySeconds?: number
  audienceType: MassSmsAudienceType
  channel?: MassSmsChannel
  mediaAssetId?: number | null
  provinceId?: string | null
  cityName?: string | null
  serialGroupIds?: number[] | null
  registrationFromDate?: string | null
  registrationToDate?: string | null
  excludePairedRubika?: boolean
  excludePairedTelegram?: boolean
  excludePairedBale?: boolean
}

export interface MassSmsCampaign {
  id: number
  message: string
  delaySeconds: number
  audienceType: MassSmsAudienceType
  channel?: MassSmsChannel
  mediaAssetId?: number | null
  mediaFileName?: string | null
  provinceId?: string | null
  provinceName?: string | null
  cityName?: string | null
  serialGroupIds?: number[] | null
  serialGroupLabels?: string[] | null
  registrationFromDate?: string | null
  registrationToDate?: string | null
  status: MassSmsCampaignStatus
  totalCount: number
  sentCount: number
  failedCount: number
  skippedCount?: number
  pendingCount: number
  createdAt: number
  startedAt?: number | null
  completedAt?: number | null
  queueOrder: number
  queuePosition?: number | null
}

export interface MassSmsCampaignListResponse {
  items: MassSmsCampaign[]
}

export interface MassSmsRecipient {
  id: number
  phone: string
  status: 'pending' | 'sent' | 'failed' | 'skipped'
  sentAt?: number | null
  errorMessage?: string | null
}

export interface MassSmsCampaignDetailResponse {
  campaign: MassSmsCampaign
  recipients: MassSmsRecipient[]
  total: number
  page: number
  limit: number
}

export interface WhatsAppConfig {
  searchBoxSelector: string
  searchResultSelector: string
  messageInputSelector: string
  sendButtonSelector: string
  openMode: 'search' | 'link'
  searchByPhoneFirst: boolean
  sendDelaySeconds: number
  perContactTimeoutMs: number
}

export interface WhatsAppEngineStatus {
  online: boolean
  loggedIn: boolean
  lastSeen: number | null
  staleThresholdMs: number
}

export interface WhatsAppPreviewRequest {
  audienceType: MassSmsAudienceType
  provinceId?: string | null
  cityName?: string | null
  serialGroupIds?: number[] | null
  registrationFromDate?: string | null
  registrationToDate?: string | null
  excludePairedRubika?: boolean
  excludePairedTelegram?: boolean
  excludePairedBale?: boolean
}

export interface WhatsAppPreviewResponse {
  count: number
  samplePhones: string[]
}

export interface WhatsAppCreateRequest {
  message: string
  audienceType: MassSmsAudienceType
  provinceId?: string | null
  cityName?: string | null
  serialGroupIds?: number[] | null
  registrationFromDate?: string | null
  registrationToDate?: string | null
  excludePairedRubika?: boolean
  excludePairedTelegram?: boolean
  excludePairedBale?: boolean
}

export interface WhatsAppCampaign {
  id: number
  message: string
  audienceType: MassSmsAudienceType
  provinceId?: string | null
  provinceName?: string | null
  cityName?: string | null
  serialGroupIds?: number[] | null
  serialGroupLabels?: string[] | null
  registrationFromDate?: string | null
  registrationToDate?: string | null
  status: MassSmsCampaignStatus
  totalCount: number
  sentCount: number
  failedCount: number
  pendingCount: number
  createdAt: number
  startedAt?: number | null
  completedAt?: number | null
  queueOrder: number
  queuePosition?: number | null
}

export interface WhatsAppCampaignListResponse {
  items: WhatsAppCampaign[]
}

export interface WhatsAppRecipient {
  id: number
  phone: string
  phoneIntl: string
  contactName?: string | null
  status: 'pending' | 'sending' | 'sent' | 'failed'
  sentAt?: number | null
  errorMessage?: string | null
}

export interface WhatsAppCampaignDetailResponse {
  campaign: WhatsAppCampaign
  recipients: WhatsAppRecipient[]
  total: number
  page: number
  limit: number
}

export interface RubikaStatus {
  active: boolean
  botTitle?: string | null
  botUsername?: string | null
  serialEnabled: boolean
  inviteEnabled: boolean
  polling: boolean
}

export interface RubikaMessage {
  messageId: string
  chatId: string
  senderId?: string | null
  text: string
  timestamp: number
  direction: 'incoming' | 'outgoing'
  phoneNumber?: string | null
  senderName?: string | null
}

export interface RubikaMessageListResponse {
  items: RubikaMessage[]
  total: number
}

export interface BotMenuSettings {
  menuRegisterWarranty: boolean
  menuWarrantyStatus: boolean
  menuSendProductPhoto: boolean
  menuInviteFriends: boolean
  menuMyScore: boolean
  menuSupport: boolean
  menuHelp: boolean
}

export type SerialPhotoSubmissionStatus = 'pending' | 'approved' | 'rejected'

export interface SerialPhotoSubmission {
  id: number
  serial: string
  serialRowId?: number | null
  groupId?: number | null
  groupLabel?: string | null
  phone: string
  channel: string
  status: SerialPhotoSubmissionStatus | string
  imageUrl: string
  mimeType?: string | null
  score: number
  scoreAwarded: number
  createdAt: number
  reviewedAt?: number | null
  reviewNote?: string | null
}

export interface SerialPhotoSubmissionListResponse {
  items: SerialPhotoSubmission[]
  total: number
  page: number
  limit: number
}

export interface ReferralSettings {
  enabled: boolean
  basePoints: number
  decayPercent: number
  maxDepth: number
}

export interface ReferralCustomer {
  phone: string
  referralCode: string
  referredBy?: string | null
  directDownline: number
  score: number
  createdAt: number
}

export interface ReferralCustomerListResponse {
  items: ReferralCustomer[]
  total: number
  page: number
  limit: number
}

export interface RubikaSettings extends BotMenuSettings {
  serialEnabled: boolean
  inviteEnabled: boolean
  inviteMessage: string
}

export interface RubikaConnectRequest {
  token: string
}

export interface TelegramStatus {
  active: boolean
  botTitle?: string | null
  botUsername?: string | null
  serialEnabled: boolean
  inviteEnabled: boolean
  polling: boolean
}

export interface TelegramMessage {
  messageId: string
  chatId: string
  senderId?: string | null
  text: string
  timestamp: number
  direction: 'incoming' | 'outgoing'
  phoneNumber?: string | null
  senderName?: string | null
}

export interface TelegramMessageListResponse {
  items: TelegramMessage[]
  total: number
}

export interface TelegramSettings extends BotMenuSettings {
  serialEnabled: boolean
  inviteEnabled: boolean
  inviteMessage: string
}

export interface TelegramConnectRequest {
  token: string
}

export interface BaleStatus {
  active: boolean
  botTitle?: string | null
  botUsername?: string | null
  serialEnabled: boolean
  inviteEnabled: boolean
  polling: boolean
}

export interface BaleMessage {
  messageId: string
  chatId: string
  senderId?: string | null
  text: string
  timestamp: number
  direction: 'incoming' | 'outgoing'
  phoneNumber?: string | null
  senderName?: string | null
}

export interface BaleMessageListResponse {
  items: BaleMessage[]
  total: number
}

export interface BaleSettings extends BotMenuSettings {
  serialEnabled: boolean
  inviteEnabled: boolean
  inviteMessage: string
}

export interface BaleConnectRequest {
  token: string
}

export type ClubAutoMessageAutoSource = 'warranty_end' | 'warranty_pre'

export interface ClubAutoMessageUpsertRequest {
  title?: string
  message: string
  offsetMonths: number
  offsetDays: number
  sendHour: number
  sendMinute: number
  delaySeconds?: number
  /** Max distinct serials to alarm per Jalali day (1–10000). */
  dailyCap?: number
  sendSms: boolean
  sendRubika: boolean
  sendTelegram: boolean
  sendBale?: boolean
  serialCategory?: SerialGroupCategory
  serialGroupIds: number[]
  enabled: boolean
  autoSource?: ClubAutoMessageAutoSource | null
  linkedSerialGroupId?: number | null
}

export interface ClubAutoMessageRule {
  id: number
  title: string
  message: string
  offsetMonths: number
  offsetDays: number
  sendHour: number
  sendMinute: number
  delaySeconds?: number
  dailyCap?: number
  sendSms: boolean
  sendRubika: boolean
  sendTelegram: boolean
  sendBale?: boolean
  serialCategory?: SerialGroupCategory
  serialGroupIds?: number[] | null
  serialGroupLabels?: string[] | null
  enabled: boolean
  createdAt: number
  updatedAt: number
  lastRunAt?: number | null
  lastRunDateJalali?: string | null
  lastSuccessCount: number
  lastFailCount: number
  totalSentCount?: number
  runStatus?: string
  runStartedAt?: number | null
  runTotalCount?: number
  runProcessedCount?: number
  runSentCount?: number
  runFailCount?: number
  autoSource?: ClubAutoMessageAutoSource | string | null
  linkedSerialGroupId?: number | null
}

export interface ClubAutoMessageRuleListResponse {
  items: ClubAutoMessageRule[]
}

export interface ClubAutoMessageSendItem {
  id: number
  phone: string
  serial?: string
  channel: string
  sentAt: number
  sentDateJalali: string
}

export interface ClubAutoMessageSendListResponse {
  items: ClubAutoMessageSendItem[]
  total: number
  page: number
  limit: number
}

export type ClubAutoMessagePreviewChannelStatus =
  | 'pending'
  | 'already_sent'
  | 'no_chat'
  | 'no_token'

export interface ClubAutoMessagePreviewChannelItem {
  channel: string
  status: ClubAutoMessagePreviewChannelStatus | string
}

export interface ClubAutoMessagePreviewItem {
  phone: string
  serial?: string
  channels: ClubAutoMessagePreviewChannelItem[]
  willSendAny: boolean
}

export interface ClubAutoMessagePreviewResponse {
  items: ClubAutoMessagePreviewItem[]
  total: number
  pendingCount: number
  blockedCount: number
  alreadyFullySentCount: number
  cutoffDateJalali: string
  dailyCap?: number
  sentToday?: number
  remainingToday?: number
  page: number
  limit: number
}
