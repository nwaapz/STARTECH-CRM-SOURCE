import type {
  BatchRequest,
  ComplexBatchRequest,
  ImportBatchRequest,
  ImportCsvMeta,
  ImportCsvResponse,
  BatchResponse,
  CommunicationListResponse,
  CommunicationThreadResponse,
  CommunicationSendRequest,
  CommunicationSendResponse,
  SerialErrorClientListResponse,
  SerialErrorListResponse,
  DuplicateSerialErrorBody,
  HealthResponse,
  SerialGroupListResponse,
  SerialGroupSerialsResponse,
  ExtendSerialGroupRangeRequest,
  ExtendSerialGroupRangeResponse,
  UpdateSerialGroupRequest,
  SerialGroup,
  SerialListResponse,
  SerialRow,
  UpdateSerialRowRequest,
  SmsProcessingSettings,
  SmsMessagesSettings,
  SimQuotaSettings,
  SyncResponse,
  IranProvinceListResponse,
  MassSmsPreviewRequest,
  MassSmsPreviewResponse,
  MassSmsCreateRequest,
  MassSmsCampaign,
  MassSmsCampaignListResponse,
  MassSmsCampaignDetailResponse,
  MassSmsMediaResponse,
  WhatsAppConfig,
  WhatsAppEngineStatus,
  WhatsAppPreviewRequest,
  WhatsAppPreviewResponse,
  WhatsAppCreateRequest,
  WhatsAppCampaign,
  WhatsAppCampaignListResponse,
  WhatsAppCampaignDetailResponse,
  RubikaStatus,
  RubikaMessageListResponse,
  RubikaSettings,
  RubikaConnectRequest,
  TelegramStatus,
  TelegramMessageListResponse,
  TelegramSettings,
  TelegramConnectRequest,
  BaleStatus,
  BaleMessageListResponse,
  BaleSettings,
  BaleConnectRequest,
  ClubAutoMessageUpsertRequest,
  ClubAutoMessageRule,
  ClubAutoMessageRuleListResponse,
  ClubAutoMessageSendListResponse,
  ClubAutoMessagePreviewResponse,
  SerialPhotoSubmission,
  SerialPhotoSubmissionListResponse,
  ReferralSettings,
  ReferralCustomerListResponse,
} from './types'
import { DuplicateSerialError } from './types'

const PIN_KEY = 'gateway_pin'
const SYNC_CURSOR_KEY = 'gateway_sync_cursor'

export function getPin(): string | null {
  return sessionStorage.getItem(PIN_KEY)
}

export function setPin(pin: string): void {
  sessionStorage.setItem(PIN_KEY, pin.trim())
}

export function getSyncCursor(): number {
  const raw = sessionStorage.getItem(SYNC_CURSOR_KEY)
  if (!raw) return 0
  const parsed = Number(raw)
  return Number.isFinite(parsed) ? parsed : 0
}

export function setSyncCursor(cursor: number): void {
  sessionStorage.setItem(SYNC_CURSOR_KEY, String(cursor))
}

export function clearSyncCursor(): void {
  sessionStorage.removeItem(SYNC_CURSOR_KEY)
}

export function clearPin(): void {
  sessionStorage.removeItem(PIN_KEY)
  clearSyncCursor()
}

/** Prefix /api paths with Vite base (e.g. /crm/ on remote host). */
function apiUrl(path: string): string {
  if (/^https?:\/\//i.test(path)) return path
  const base = import.meta.env.BASE_URL || '/'
  const normalized = path.startsWith('/') ? path.slice(1) : path
  return `${base}${normalized}`
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (!headers.has('Content-Type') && init.body && !(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }
  const pin = getPin()
  if (pin) {
    headers.set('Authorization', `Bearer ${pin}`)
  }
  const url = apiUrl(path)
  const response = await fetch(url, { ...init, headers })
  if (!response.ok) {
    let message = response.statusText
    try {
      const body = await response.json()
      if (
        response.status === 409 &&
        body?.duplicateRanges &&
        Array.isArray(body.duplicateRanges)
      ) {
        throw new DuplicateSerialError(body as DuplicateSerialErrorBody)
      }
      if (body?.error) message = body.error
    } catch (e) {
      if (e instanceof DuplicateSerialError) throw e
      /* ignore parse errors */
    }
    console.warn('[CRM API]', url, response.status, message)
    throw new Error(message)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return response.json() as Promise<T>
}

export function fetchHealth(): Promise<HealthResponse> {
  return request<HealthResponse>('/api/health')
}

/** Unauthenticated reachability check — use before PIN login. */
export async function fetchPing(): Promise<boolean> {
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), 5000)
  try {
    const response = await fetch(apiUrl('/api/ping'), { signal: controller.signal })
    return response.ok
  } catch {
    return false
  } finally {
    window.clearTimeout(timeout)
  }
}

export function formatConnectionError(error: unknown): string {
  if (error instanceof Error) {
    const message = error.message.toLowerCase()
    if (message.includes('invalid pin')) {
      return 'رمز PIN اشتباه است — از تنظیمات گوشی کپی کنید.'
    }
    if (
      error.name === 'AbortError' ||
      message.includes('failed to fetch') ||
      message.includes('network') ||
      message.includes('load failed')
    ) {
      const surface = (import.meta.env.VITE_CRM_SURFACE as string | undefined)?.toLowerCase()
      if (surface === 'remote') {
        return 'سرور CRM در دسترس نیست. دامنه و مسیر /crm را بررسی کنید.'
      }
      return (
        'سرور CRM در دسترس نیست. هات‌اسپات گوشی را روشن کنید، PC را به Wi‑Fi گوشی وصل کنید، ' +
        'سپس آدرس نمایش‌داده‌شده در تنظیمات (مثلاً http://192.168.43.1:8765) را باز کنید.'
      )
    }
    return error.message
  }
  return 'خطا در اتصال'
}

export function fetchSync(since: number): Promise<SyncResponse> {
  return request<SyncResponse>(`/api/sync?since=${since}`)
}

export function createSerialBatch(body: BatchRequest): Promise<BatchResponse> {
  return request<BatchResponse>('/api/serials/batch', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function createComplexSerialBatch(body: ComplexBatchRequest): Promise<BatchResponse> {
  return request<BatchResponse>('/api/serials/complex-batch', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function createImportSerialBatch(body: ImportBatchRequest): Promise<BatchResponse> {
  return request<BatchResponse>('/api/serials/import-batch', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

/** Multipart CSV with registration state — LAN gateway / local DB only. */
export function createImportCsvBatch(
  file: File,
  meta: ImportCsvMeta,
): Promise<ImportCsvResponse> {
  const form = new FormData()
  form.append('file', file)
  if (meta.description) form.append('description', meta.description)
  form.append('score', String(meta.score ?? 1))
  form.append('category', meta.category ?? 'end_user_client')
  if (meta.warrantyMonths != null) form.append('warrantyMonths', String(meta.warrantyMonths))
  if (meta.warrantyKm != null) form.append('warrantyKm', String(meta.warrantyKm))
  form.append('imageVerificationRequired', meta.imageVerificationRequired ? 'true' : 'false')
  return request<ImportCsvResponse>('/api/serials/import-csv', {
    method: 'POST',
    body: form,
  })
}

export function fetchSerialGroups(): Promise<SerialGroupListResponse> {
  return request<SerialGroupListResponse>('/api/serial-groups')
}

export function fetchSerialGroupSerials(id: number): Promise<SerialGroupSerialsResponse> {
  return request<SerialGroupSerialsResponse>(`/api/serial-groups/${id}/serials`)
}

export function deleteSerialGroup(id: number): Promise<void> {
  return request<void>(`/api/serial-groups/${id}`, {
    method: 'DELETE',
  })
}

export function updateSerialGroup(
  id: number,
  body: UpdateSerialGroupRequest,
): Promise<SerialGroup> {
  return request(`/api/serial-groups/${id}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

export function extendSerialGroupRange(
  id: number,
  body: ExtendSerialGroupRangeRequest,
): Promise<ExtendSerialGroupRangeResponse> {
  return request<ExtendSerialGroupRangeResponse>(`/api/serial-groups/${id}/extend`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function fetchSerials(params: {
  registered?: boolean
  from?: string
  to?: string
  defaultEndToday?: boolean
  search?: string
  sources?: Array<'sms' | 'rubika' | 'telegram' | 'bale'>
  categories?: Array<'end_user_client' | 'seller_to_end_user'>
  groupIds?: number[]
  provinceId?: string
  cityName?: string
  page?: number
  limit?: number
}): Promise<SerialListResponse> {
  const query = new URLSearchParams()
  if (params.registered !== undefined) {
    query.set('registered', String(params.registered))
  }
  if (params.from) query.set('from', params.from)
  if (params.to) query.set('to', params.to)
  if (params.defaultEndToday) query.set('defaultEndToday', 'true')
  if (params.search) query.set('search', params.search)
  if (params.sources?.length) {
    for (const source of params.sources) {
      query.append('source', source)
    }
  }
  if (params.categories?.length) {
    for (const category of params.categories) {
      query.append('category', category)
    }
  }
  if (params.groupIds?.length) {
    for (const groupId of params.groupIds) {
      query.append('groupIds', String(groupId))
    }
  }
  if (params.provinceId) query.set('provinceId', params.provinceId)
  if (params.cityName) query.set('cityName', params.cityName)
  if (params.page) query.set('page', String(params.page))
  if (params.limit) query.set('limit', String(params.limit))
  return request<SerialListResponse>(`/api/serials?${query.toString()}`)
}

export function sendCompletionReminder(id: number): Promise<SerialRow> {
  return request<SerialRow>(`/api/serials/${id}/send-completion-reminder`, {
    method: 'POST',
  })
}

export function updateSerialRow(id: number, body: UpdateSerialRowRequest): Promise<SerialRow> {
  return request<SerialRow>(`/api/serials/${id}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

export function fetchSmsProcessingSettings(): Promise<SmsProcessingSettings> {
  return request<SmsProcessingSettings>('/api/settings/sms-processing')
}

export function updateSmsProcessingSettings(
  body: SmsProcessingSettings,
): Promise<SmsProcessingSettings> {
  return request<SmsProcessingSettings>('/api/settings/sms-processing', {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

export function fetchSimQuotaSettings(): Promise<SimQuotaSettings> {
  return request<SimQuotaSettings>('/api/settings/sim-quota')
}

export function updateSimQuotaSettings(body: SimQuotaSettings): Promise<SimQuotaSettings> {
  return request<SimQuotaSettings>('/api/settings/sim-quota', {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

export function fetchSmsMessages(): Promise<SmsMessagesSettings> {
  return request<SmsMessagesSettings>('/api/settings/sms-messages')
}

export function updateSmsMessages(body: SmsMessagesSettings): Promise<SmsMessagesSettings> {
  return request<SmsMessagesSettings>('/api/settings/sms-messages', {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

export function fetchCommunications(params: {
  page?: number
  limit?: number
  direction?: 'incoming' | 'outgoing'
  channel?: 'sms' | 'rubika' | 'telegram' | 'bale'
  phone?: string
}): Promise<CommunicationListResponse> {
  const query = new URLSearchParams()
  if (params.page) query.set('page', String(params.page))
  if (params.limit) query.set('limit', String(params.limit))
  if (params.direction) query.set('direction', params.direction)
  if (params.channel) query.set('channel', params.channel)
  if (params.phone) query.set('phone', params.phone)
  return request<CommunicationListResponse>(`/api/communications?${query.toString()}`)
}

export function fetchCommunicationThread(params: {
  phone: string
  before?: number
  limit?: number
}): Promise<CommunicationThreadResponse> {
  const query = new URLSearchParams()
  query.set('phone', params.phone)
  if (params.before !== undefined) query.set('before', String(params.before))
  if (params.limit) query.set('limit', String(params.limit))
  return request<CommunicationThreadResponse>(`/api/communications/thread?${query.toString()}`)
}

export function sendCommunication(
  body: CommunicationSendRequest,
): Promise<CommunicationSendResponse> {
  return request<CommunicationSendResponse>('/api/communications/send', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function fetchSerialErrorClients(params: {
  page?: number
  limit?: number
}): Promise<SerialErrorClientListResponse> {
  const query = new URLSearchParams()
  if (params.page) query.set('page', String(params.page))
  if (params.limit) query.set('limit', String(params.limit))
  return request<SerialErrorClientListResponse>(`/api/serial-errors/clients?${query.toString()}`)
}

export function fetchSerialErrorsByPhone(phone: string): Promise<SerialErrorListResponse> {
  const query = new URLSearchParams()
  query.set('phone', phone)
  return request<SerialErrorListResponse>(`/api/serial-errors?${query.toString()}`)
}

export function fetchMassSmsProvinces(): Promise<IranProvinceListResponse> {
  return request<IranProvinceListResponse>('/api/mass-sms/provinces')
}

export function previewMassSms(body: MassSmsPreviewRequest): Promise<MassSmsPreviewResponse> {
  return request<MassSmsPreviewResponse>('/api/mass-sms/preview', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function fetchMassSmsCampaigns(): Promise<MassSmsCampaignListResponse> {
  return request<MassSmsCampaignListResponse>('/api/mass-sms/campaigns')
}

export function fetchMassSmsCampaignDetail(
  id: number,
  params: { page?: number; limit?: number } = {},
): Promise<MassSmsCampaignDetailResponse> {
  const query = new URLSearchParams()
  if (params.page) query.set('page', String(params.page))
  if (params.limit) query.set('limit', String(params.limit))
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return request<MassSmsCampaignDetailResponse>(`/api/mass-sms/campaigns/${id}${suffix}`)
}

export function createMassSmsCampaign(body: MassSmsCreateRequest): Promise<MassSmsCampaign> {
  return request<MassSmsCampaign>('/api/mass-sms/campaigns', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function uploadMassSmsMedia(file: File): Promise<MassSmsMediaResponse> {
  const form = new FormData()
  form.append('file', file, file.name)
  return request<MassSmsMediaResponse>('/api/mass-sms/media', {
    method: 'POST',
    body: form,
  })
}

export function pauseMassSmsCampaign(id: number): Promise<MassSmsCampaign> {
  return request<MassSmsCampaign>(`/api/mass-sms/campaigns/${id}/pause`, {
    method: 'POST',
  })
}

export function resumeMassSmsCampaign(id: number): Promise<MassSmsCampaign> {
  return request<MassSmsCampaign>(`/api/mass-sms/campaigns/${id}/resume`, {
    method: 'POST',
  })
}

export function deleteMassSmsCampaign(id: number): Promise<void> {
  return request<void>(`/api/mass-sms/campaigns/${id}`, {
    method: 'DELETE',
  })
}

export function fetchWhatsAppConfig(): Promise<WhatsAppConfig> {
  return request<WhatsAppConfig>('/api/whatsapp/config')
}

export function updateWhatsAppConfig(body: WhatsAppConfig): Promise<WhatsAppConfig> {
  return request<WhatsAppConfig>('/api/whatsapp/config', {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

export function fetchWhatsAppEngineStatus(): Promise<WhatsAppEngineStatus> {
  return request<WhatsAppEngineStatus>('/api/whatsapp/engine/status')
}

export function previewWhatsApp(body: WhatsAppPreviewRequest): Promise<WhatsAppPreviewResponse> {
  return request<WhatsAppPreviewResponse>('/api/whatsapp/preview', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function fetchWhatsAppCampaigns(): Promise<WhatsAppCampaignListResponse> {
  return request<WhatsAppCampaignListResponse>('/api/whatsapp/campaigns')
}

export function fetchWhatsAppCampaignDetail(
  id: number,
  params: { page?: number; limit?: number } = {},
): Promise<WhatsAppCampaignDetailResponse> {
  const query = new URLSearchParams()
  if (params.page) query.set('page', String(params.page))
  if (params.limit) query.set('limit', String(params.limit))
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return request<WhatsAppCampaignDetailResponse>(`/api/whatsapp/campaigns/${id}${suffix}`)
}

export function createWhatsAppCampaign(body: WhatsAppCreateRequest): Promise<WhatsAppCampaign> {
  return request<WhatsAppCampaign>('/api/whatsapp/campaigns', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function pauseWhatsAppCampaign(id: number): Promise<WhatsAppCampaign> {
  return request<WhatsAppCampaign>(`/api/whatsapp/campaigns/${id}/pause`, {
    method: 'POST',
  })
}

export function resumeWhatsAppCampaign(id: number): Promise<WhatsAppCampaign> {
  return request<WhatsAppCampaign>(`/api/whatsapp/campaigns/${id}/resume`, {
    method: 'POST',
  })
}

export function deleteWhatsAppCampaign(id: number): Promise<void> {
  return request<void>(`/api/whatsapp/campaigns/${id}`, {
    method: 'DELETE',
  })
}

export function fetchRubikaStatus(): Promise<RubikaStatus> {
  return request<RubikaStatus>('/api/rubika/status')
}

export function connectRubikaBot(body: RubikaConnectRequest): Promise<RubikaStatus> {
  return request<RubikaStatus>('/api/rubika/connect', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

/** Remote: pull Rubika getUpdates once (server has no always-on app poller). */
export function pollRubikaBot(): Promise<{
  ok: boolean
  result: { processed?: number; error?: string; skipped?: boolean }
}> {
  return request('/api/rubika/poll', {
    method: 'POST',
    body: '{}',
  })
}

export function disconnectRubikaBot(): Promise<RubikaStatus> {
  return request<RubikaStatus>('/api/rubika/disconnect', {
    method: 'POST',
  })
}

export function fetchRubikaMessages(params: {
  since?: number
  limit?: number
} = {}): Promise<RubikaMessageListResponse> {
  const query = new URLSearchParams()
  if (params.since !== undefined) query.set('since', String(params.since))
  if (params.limit !== undefined) query.set('limit', String(params.limit))
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return request<RubikaMessageListResponse>(`/api/rubika/messages${suffix}`)
}

export function fetchRubikaSettings(): Promise<RubikaSettings> {
  return request<RubikaSettings>('/api/settings/rubika')
}

export function updateRubikaSettings(body: RubikaSettings): Promise<RubikaSettings> {
  return request<RubikaSettings>('/api/settings/rubika', {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

export function fetchTelegramStatus(): Promise<TelegramStatus> {
  return request<TelegramStatus>('/api/telegram/status')
}

export function connectTelegramBot(body: TelegramConnectRequest): Promise<TelegramStatus> {
  return request<TelegramStatus>('/api/telegram/connect', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function disconnectTelegramBot(): Promise<TelegramStatus> {
  return request<TelegramStatus>('/api/telegram/disconnect', {
    method: 'POST',
  })
}

export function fetchTelegramMessages(params: {
  since?: number
  limit?: number
} = {}): Promise<TelegramMessageListResponse> {
  const query = new URLSearchParams()
  if (params.since !== undefined) query.set('since', String(params.since))
  if (params.limit !== undefined) query.set('limit', String(params.limit))
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return request<TelegramMessageListResponse>(`/api/telegram/messages${suffix}`)
}

export function fetchTelegramSettings(): Promise<TelegramSettings> {
  return request<TelegramSettings>('/api/settings/telegram')
}

export function updateTelegramSettings(body: TelegramSettings): Promise<TelegramSettings> {
  return request<TelegramSettings>('/api/settings/telegram', {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

export function fetchBaleStatus(): Promise<BaleStatus> {
  return request<BaleStatus>('/api/bale/status')
}

export function connectBaleBot(body: BaleConnectRequest): Promise<BaleStatus> {
  return request<BaleStatus>('/api/bale/connect', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function pollBaleBot(): Promise<{
  channel: string
  processed?: number
  skipped?: boolean
  error?: string
}> {
  return request('/api/bale/poll', {
    method: 'POST',
  })
}

export function disconnectBaleBot(): Promise<BaleStatus> {
  return request<BaleStatus>('/api/bale/disconnect', {
    method: 'POST',
  })
}

export function fetchBaleMessages(params: {
  since?: number
  limit?: number
} = {}): Promise<BaleMessageListResponse> {
  const query = new URLSearchParams()
  if (params.since !== undefined) query.set('since', String(params.since))
  if (params.limit !== undefined) query.set('limit', String(params.limit))
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return request<BaleMessageListResponse>(`/api/bale/messages${suffix}`)
}

export function fetchBaleSettings(): Promise<BaleSettings> {
  return request<BaleSettings>('/api/settings/bale')
}

export function updateBaleSettings(body: BaleSettings): Promise<BaleSettings> {
  return request<BaleSettings>('/api/settings/bale', {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

export function fetchClubAutoMessages(): Promise<ClubAutoMessageRuleListResponse> {
  return request<ClubAutoMessageRuleListResponse>('/api/club-auto-messages')
}

export function createClubAutoMessage(
  body: ClubAutoMessageUpsertRequest,
): Promise<ClubAutoMessageRule> {
  return request<ClubAutoMessageRule>('/api/club-auto-messages', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function updateClubAutoMessage(
  id: number,
  body: ClubAutoMessageUpsertRequest,
): Promise<ClubAutoMessageRule> {
  return request<ClubAutoMessageRule>(`/api/club-auto-messages/${id}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

export function deleteClubAutoMessage(id: number): Promise<void> {
  return request<void>(`/api/club-auto-messages/${id}`, {
    method: 'DELETE',
  })
}

export function runClubAutoMessageNow(id: number): Promise<ClubAutoMessageRule> {
  return request<ClubAutoMessageRule>(`/api/club-auto-messages/${id}/run-now`, {
    method: 'POST',
  })
}

export function fetchClubAutoMessageSends(
  id: number,
  page = 1,
  limit = 50,
): Promise<ClubAutoMessageSendListResponse> {
  const q = new URLSearchParams({
    page: String(page),
    limit: String(limit),
  })
  return request<ClubAutoMessageSendListResponse>(
    `/api/club-auto-messages/${id}/sends?${q.toString()}`,
  )
}

export function fetchClubAutoMessagePreview(
  id: number,
  page = 1,
  limit = 50,
): Promise<ClubAutoMessagePreviewResponse> {
  const q = new URLSearchParams({
    page: String(page),
    limit: String(limit),
  })
  return request<ClubAutoMessagePreviewResponse>(
    `/api/club-auto-messages/${id}/preview?${q.toString()}`,
  )
}

export function tickClubAutoMessages(): Promise<{ ok: boolean }> {
  return request<{ ok: boolean }>('/api/club-auto-messages/tick', {
    method: 'POST',
  })
}

export function fetchSerialPhotoSubmissions(params: {
  status?: string
  page?: number
  limit?: number
} = {}): Promise<SerialPhotoSubmissionListResponse> {
  const q = new URLSearchParams()
  if (params.status) q.set('status', params.status)
  if (params.page != null) q.set('page', String(params.page))
  if (params.limit != null) q.set('limit', String(params.limit))
  const qs = q.toString()
  return request<SerialPhotoSubmissionListResponse>(
    `/api/serial-photo-submissions${qs ? `?${qs}` : ''}`,
  )
}

export function serialPhotoImageUrl(id: number): string {
  return `/api/serial-photo-submissions/${id}/image`
}

export async function fetchSerialPhotoBlobUrl(id: number): Promise<string> {
  const pin = getPin()
  const headers = new Headers()
  if (pin) headers.set('Authorization', `Bearer ${pin}`)
  const response = await fetch(`/api/serial-photo-submissions/${id}/image`, { headers })
  if (!response.ok) {
    throw new Error('بارگذاری تصویر ناموفق بود')
  }
  const blob = await response.blob()
  return URL.createObjectURL(blob)
}

export function approveSerialPhoto(
  id: number,
  note?: string,
): Promise<SerialPhotoSubmission> {
  return request<SerialPhotoSubmission>(`/api/serial-photo-submissions/${id}/approve`, {
    method: 'POST',
    body: JSON.stringify({ note: note ?? null }),
  })
}

export function rejectSerialPhoto(
  id: number,
  reasonCode: string,
  note?: string,
): Promise<SerialPhotoSubmission> {
  return request<SerialPhotoSubmission>(`/api/serial-photo-submissions/${id}/reject`, {
    method: 'POST',
    body: JSON.stringify({ reasonCode, note: note ?? null }),
  })
}

export function fetchReferralSettings(): Promise<ReferralSettings> {
  return request<ReferralSettings>('/api/settings/referral')
}

export function updateReferralSettings(settings: ReferralSettings): Promise<ReferralSettings> {
  return request<ReferralSettings>('/api/settings/referral', {
    method: 'PUT',
    body: JSON.stringify(settings),
  })
}

export function fetchReferralCustomers(params: {
  search?: string
  page?: number
  limit?: number
} = {}): Promise<ReferralCustomerListResponse> {
  const q = new URLSearchParams()
  if (params.search) q.set('search', params.search)
  if (params.page != null) q.set('page', String(params.page))
  if (params.limit != null) q.set('limit', String(params.limit))
  const qs = q.toString()
  return request<ReferralCustomerListResponse>(
    `/api/referral/customers${qs ? `?${qs}` : ''}`,
  )
}

export interface LotteryParticipant {
  phone: string
  score: number
  city?: string | null
}

export interface LotteryWinner {
  id: number
  phone: string
  city?: string | null
  scoreAtWin: number
  dateJalali: string
  wonAt: number
}

export interface CrmUser {
  id: number
  username: string
  role: number
  createdAt: number
  updatedAt: number
}

export interface CloudSyncStatus {
  configured: boolean
  url?: string | null
  pendingOutbox: number
  lastPushMs: number
  lastPullMs: number
  running?: boolean
  /** idle | fetch | push | apply | conflicts | done | error */
  phase?: string
  percent?: number
  detail?: string | null
  conflictCount?: number
  pendingRemote?: number
}

export interface DayCount {
  day: string
  count: number
}

export function fetchLotteryParticipants(params: {
  city?: string
  phone?: string
} = {}): Promise<{ items: LotteryParticipant[]; total: number }> {
  const q = new URLSearchParams()
  if (params.city) q.set('city', params.city)
  if (params.phone) q.set('phone', params.phone)
  const qs = q.toString()
  return request(`/api/lottery/participants${qs ? `?${qs}` : ''}`)
}

export function drawLottery(params: { city?: string } = {}): Promise<LotteryParticipant> {
  const q = new URLSearchParams()
  if (params.city) q.set('city', params.city)
  const qs = q.toString()
  return request(`/api/lottery/draw${qs ? `?${qs}` : ''}`, { method: 'POST', body: '{}' })
}

export function confirmLotteryWinner(phone: string): Promise<LotteryWinner> {
  return request('/api/lottery/confirm', {
    method: 'POST',
    body: JSON.stringify({ phone }),
  })
}

export function fetchLotteryWinners(params: {
  page?: number
  limit?: number
} = {}): Promise<{ items: LotteryWinner[]; total: number; page: number; limit: number }> {
  const q = new URLSearchParams()
  if (params.page != null) q.set('page', String(params.page))
  if (params.limit != null) q.set('limit', String(params.limit))
  const qs = q.toString()
  return request(`/api/lottery/winners${qs ? `?${qs}` : ''}`)
}

export function fetchLotteryPreset(): Promise<{ phone: string | null }> {
  return request('/api/lottery/preset')
}

export function setLotteryPreset(phone: string | null): Promise<{ phone: string | null }> {
  return request('/api/lottery/preset', {
    method: 'PUT',
    body: JSON.stringify({ phone }),
  })
}

export function fetchCrmUsers(): Promise<CrmUser[]> {
  return request('/api/admin/users')
}

export function createCrmUser(body: {
  username: string
  password: string
  role: number
}): Promise<CrmUser> {
  return request('/api/admin/users', { method: 'POST', body: JSON.stringify(body) })
}

export function deleteCrmUser(id: number): Promise<{ ok: boolean }> {
  return request(`/api/admin/users/${id}`, { method: 'DELETE' })
}

export function changeCrmUserPassword(id: number, password: string): Promise<{ ok: boolean }> {
  return request(`/api/admin/users/${id}/password`, {
    method: 'PUT',
    body: JSON.stringify({ password }),
  })
}

export function fetchCloudSyncStatus(): Promise<CloudSyncStatus> {
  return request('/api/admin/sync-status')
}

export function putCloudSyncConfig(body: {
  url?: string
  token?: string
}): Promise<{ ok: boolean }> {
  return request('/api/admin/sync-config', { method: 'PUT', body: JSON.stringify(body) })
}

/** "Get from server" button: backfill + pull. Applies/acks remote changes, never pushes. */
export function runCloudSyncGet(): Promise<{ ok: boolean; message?: string }> {
  return request('/api/admin/cloud-sync/get', { method: 'POST', body: '{}' })
}

/** "Send to server" button: upload-only push of the local outbox. */
export function runCloudSyncSend(): Promise<{ ok: boolean; message?: string }> {
  return request('/api/admin/cloud-sync/send', { method: 'POST', body: '{}' })
}

/**
 * Silent, cheap pull (no backfill) fired once per page load. Callers should swallow
 * any error — if the server isn't reachable the change just stays pending until the
 * user taps "Get from server".
 */
export function triggerCloudAutoPull(): Promise<{ ok: boolean; message?: string }> {
  return request('/api/admin/cloud-sync/pull', { method: 'POST', body: '{}' })
}

export function fetchRegistrationsByDay(params: {
  from?: string
  to?: string
} = {}): Promise<{ from: string; to: string; days: DayCount[] }> {
  const q = new URLSearchParams()
  if (params.from) q.set('from', params.from)
  if (params.to) q.set('to', params.to)
  const qs = q.toString()
  return request(`/api/stats/registrations-by-day${qs ? `?${qs}` : ''}`)
}

export function loginCrmUser(
  username: string,
  password: string,
): Promise<{ token: string; username: string; role: number }> {
  return request('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  })
}
