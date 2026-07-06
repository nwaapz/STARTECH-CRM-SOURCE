import type {
  BatchRequest,
  BatchResponse,
  CommunicationListResponse,
  CommunicationThreadResponse,
  SerialErrorClientListResponse,
  SerialErrorListResponse,
  DuplicateSerialErrorBody,
  HealthResponse,
  SerialGroupListResponse,
  ExtendSerialGroupRangeRequest,
  ExtendSerialGroupRangeResponse,
  UpdateSerialGroupRequest,
  SerialGroup,
  SerialListResponse,
  SerialRow,
  SmsProcessingSettings,
  SmsMessagesSettings,
  SyncResponse,
  IranProvinceListResponse,
  MassSmsPreviewRequest,
  MassSmsPreviewResponse,
  MassSmsCreateRequest,
  MassSmsCampaign,
  MassSmsCampaignListResponse,
  MassSmsCampaignDetailResponse,
  RubikaStatus,
  RubikaMessageListResponse,
  RubikaSettings,
  RubikaConnectRequest,
  TelegramStatus,
  TelegramMessageListResponse,
  TelegramSettings,
  TelegramConnectRequest,
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

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (!headers.has('Content-Type') && init.body) {
    headers.set('Content-Type', 'application/json')
  }
  const pin = getPin()
  if (pin) {
    headers.set('Authorization', `Bearer ${pin}`)
  }
  const response = await fetch(path, { ...init, headers })
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
    console.warn('[CRM API]', path, response.status, message)
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
    const response = await fetch('/api/ping', { signal: controller.signal })
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

export function fetchSerialGroups(): Promise<SerialGroupListResponse> {
  return request<SerialGroupListResponse>('/api/serial-groups')
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
  if (params.page) query.set('page', String(params.page))
  if (params.limit) query.set('limit', String(params.limit))
  return request<SerialListResponse>(`/api/serials?${query.toString()}`)
}

export function sendCompletionReminder(id: number): Promise<SerialRow> {
  return request<SerialRow>(`/api/serials/${id}/send-completion-reminder`, {
    method: 'POST',
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
  phone?: string
}): Promise<CommunicationListResponse> {
  const query = new URLSearchParams()
  if (params.page) query.set('page', String(params.page))
  if (params.limit) query.set('limit', String(params.limit))
  if (params.direction) query.set('direction', params.direction)
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

export function fetchRubikaStatus(): Promise<RubikaStatus> {
  return request<RubikaStatus>('/api/rubika/status')
}

export function connectRubikaBot(body: RubikaConnectRequest): Promise<RubikaStatus> {
  return request<RubikaStatus>('/api/rubika/connect', {
    method: 'POST',
    body: JSON.stringify(body),
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
