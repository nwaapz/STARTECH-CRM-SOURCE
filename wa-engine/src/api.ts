import type { EngineConfig } from './config.js'

export interface WhatsAppRemoteConfig {
  searchBoxSelector: string
  searchResultSelector: string
  messageInputSelector: string
  sendButtonSelector: string
  openMode: 'search' | 'link'
  searchByPhoneFirst: boolean
  sendDelaySeconds: number
  perContactTimeoutMs: number
}

export interface BatchRecipient {
  recipientId: number
  phoneIntl: string
  contactName?: string | null
}

export interface NextBatch {
  campaignId?: number | null
  message?: string | null
  recipients: BatchRecipient[]
}

export interface ReportItem {
  recipientId: number
  success: boolean
  error?: string
}

export class GatewayApi {
  constructor(private readonly config: EngineConfig) {}

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const headers = new Headers(init.headers)
    headers.set('Authorization', `Bearer ${this.config.pin}`)
    if (init.body && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json')
    }
    const response = await fetch(`${this.config.baseUrl}${path}`, { ...init, headers })
    if (!response.ok) {
      const text = await response.text().catch(() => '')
      throw new Error(`${init.method ?? 'GET'} ${path} -> ${response.status} ${text}`)
    }
    if (response.status === 204) return undefined as T
    return (await response.json()) as T
  }

  heartbeat(loggedIn: boolean): Promise<unknown> {
    return this.request('/api/whatsapp/engine/heartbeat', {
      method: 'POST',
      body: JSON.stringify({ loggedIn }),
    })
  }

  getConfig(): Promise<WhatsAppRemoteConfig> {
    return this.request<WhatsAppRemoteConfig>('/api/whatsapp/config')
  }

  nextBatch(limit: number): Promise<NextBatch> {
    return this.request<NextBatch>(`/api/whatsapp/next-batch?limit=${limit}`)
  }

  report(results: ReportItem[]): Promise<unknown> {
    return this.request('/api/whatsapp/report', {
      method: 'POST',
      body: JSON.stringify({ results }),
    })
  }
}
