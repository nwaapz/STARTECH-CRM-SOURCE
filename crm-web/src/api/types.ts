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

export interface SyncChange {
  type: 'registered' | 'registration_updated' | 'serials_added'
  serial?: string
  phone?: string
  date?: string
  city?: string
  km?: number
  inserted?: number
  skipped?: number
  total?: number
  at: number
}

export interface SyncResponse {
  serverTime: number
  cursor: number
  stats: Stats
  changes: SyncChange[]
}

export interface BatchRequest {
  prefix: string
  start: number
  end: number
  padWidth: number
  city?: string
  km?: number
  description?: string
  score?: number
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
  total: number
  used: number
  unused: number
  salePercentage: number
  createdAt: number
}

export interface SerialGroupListResponse {
  items: SerialGroup[]
}

export interface UpdateSerialGroupRequest {
  description?: string | null
  score?: number
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

export interface SerialRow {
  id: number
  serial: string
  phone?: string
  date?: string
  city?: string
  km?: number
  needsCompletion?: boolean
  completionReminderSent?: boolean
  completionReminderSentAt?: number | null
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

export interface CommunicationMessage {
  id: number
  phone: string
  direction: CommunicationDirection
  body: string
  timestamp: number
  status?: string | null
  simLabel?: string | null
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

export type MassSmsCampaignStatus = 'queued' | 'running' | 'paused' | 'completed' | 'cancelled'

export interface MassSmsPreviewRequest {
  audienceType: MassSmsAudienceType
  provinceId?: string | null
  cityName?: string | null
  serialGroupIds?: number[] | null
  registrationFromDate?: string | null
  registrationToDate?: string | null
}

export interface MassSmsPreviewResponse {
  count: number
  samplePhones: string[]
}

export interface MassSmsCreateRequest {
  message: string
  delaySeconds?: number
  audienceType: MassSmsAudienceType
  provinceId?: string | null
  cityName?: string | null
  serialGroupIds?: number[] | null
  registrationFromDate?: string | null
  registrationToDate?: string | null
}

export interface MassSmsCampaign {
  id: number
  message: string
  delaySeconds: number
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

export interface MassSmsCampaignListResponse {
  items: MassSmsCampaign[]
}

export interface MassSmsRecipient {
  id: number
  phone: string
  status: 'pending' | 'sent' | 'failed'
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

export interface RubikaStatus {
  active: boolean
  botTitle?: string | null
  botUsername?: string | null
  serialEnabled: boolean
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

export interface RubikaSettings {
  serialEnabled: boolean
}

export interface RubikaConnectRequest {
  token: string
}

export interface TelegramStatus {
  active: boolean
  botTitle?: string | null
  botUsername?: string | null
  serialEnabled: boolean
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

export interface TelegramSettings {
  serialEnabled: boolean
}

export interface TelegramConnectRequest {
  token: string
}
