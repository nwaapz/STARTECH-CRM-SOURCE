import { readFileSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'

export interface EngineConfig {
  /**
   * Phone LAN base URL, e.g. http://192.168.43.1:8765. Leave empty to
   * auto-detect the phone via the PC's default gateway (recommended for
   * hotspot use, where the IP changes every session).
   */
  baseUrl: string
  /** Port the phone CRM listens on; used for auto-detection. Default 8765. */
  port: number
  /** 6-digit LAN PIN shown in the phone app settings. */
  pin: string
  /** How often to poll the phone for work (ms). */
  pollIntervalMs: number
  /** Max recipients to claim per batch. */
  batchLimit: number
  /** Run Chromium headless. Keep false for first-run QR login. */
  headless: boolean
  /** Persistent Chromium profile dir so WhatsApp login survives restarts. */
  userDataDir: string
  /**
   * Optional proxy for the WhatsApp Web browser only, e.g. "http://127.0.0.1:10808"
   * (v2ray/Xray local mixed port) or "socks5://127.0.0.1:10808". Leave empty to use a
   * direct connection. This lets WhatsApp go through the proxy while the PC's system
   * network (and the phone hotspot LAN) stays untouched.
   */
  proxyServer: string
}

const DEFAULTS: Omit<EngineConfig, 'baseUrl' | 'pin'> = {
  port: 8765,
  pollIntervalMs: 4000,
  batchLimit: 5,
  headless: false,
  userDataDir: '.wa-profile',
  proxyServer: '',
}

export function loadConfig(): EngineConfig {
  const file = resolve(process.cwd(), 'config.json')
  let fromFile: Partial<EngineConfig> = {}
  if (existsSync(file)) {
    try {
      fromFile = JSON.parse(readFileSync(file, 'utf8')) as Partial<EngineConfig>
    } catch (error) {
      throw new Error(`Failed to parse config.json: ${(error as Error).message}`)
    }
  }

  const baseUrl = (process.env.WA_BASE_URL ?? fromFile.baseUrl ?? '').trim().replace(/\/$/, '')
  const pin = (process.env.WA_PIN ?? fromFile.pin ?? '').trim()

  if (!pin) {
    throw new Error('Missing pin. Set it in config.json or the WA_PIN env var.')
  }

  return {
    baseUrl,
    port: numberEnv('WA_PORT', fromFile.port, DEFAULTS.port),
    pin,
    pollIntervalMs: numberEnv('WA_POLL_MS', fromFile.pollIntervalMs, DEFAULTS.pollIntervalMs),
    batchLimit: numberEnv('WA_BATCH_LIMIT', fromFile.batchLimit, DEFAULTS.batchLimit),
    headless: boolEnv('WA_HEADLESS', fromFile.headless, DEFAULTS.headless),
    userDataDir: (process.env.WA_USER_DATA_DIR ?? fromFile.userDataDir ?? DEFAULTS.userDataDir).trim(),
    proxyServer: (process.env.WA_PROXY_SERVER ?? fromFile.proxyServer ?? DEFAULTS.proxyServer).trim(),
  }
}

function numberEnv(env: string, fromFile: number | undefined, fallback: number): number {
  const raw = process.env[env]
  if (raw != null && raw.trim() !== '') {
    const parsed = Number(raw)
    if (Number.isFinite(parsed)) return parsed
  }
  return typeof fromFile === 'number' && Number.isFinite(fromFile) ? fromFile : fallback
}

function boolEnv(env: string, fromFile: boolean | undefined, fallback: boolean): boolean {
  const raw = process.env[env]
  if (raw != null && raw.trim() !== '') {
    return raw.trim().toLowerCase() === 'true' || raw.trim() === '1'
  }
  return typeof fromFile === 'boolean' ? fromFile : fallback
}
