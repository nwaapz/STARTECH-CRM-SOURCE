import { loadConfig } from './config.js'
import { GatewayApi, type ReportItem, type WhatsAppRemoteConfig } from './api.js'
import { WaClient } from './waClient.js'
import { detectPhoneBaseUrl, pingCrm } from './netdetect.js'

const config = loadConfig()
const api = new GatewayApi(config)
const wa = new WaClient(config)

let stopping = false

function log(message: string): void {
  const ts = new Date().toISOString()
  console.log(`[${ts}] ${message}`)
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms))
}

/**
 * Ensures config.baseUrl points at a reachable phone CRM. Uses the configured
 * URL when it works, otherwise auto-detects via the default gateway. Returns
 * true once a working URL is set.
 */
async function resolveBaseUrl(): Promise<boolean> {
  if (config.baseUrl && (await pingCrm(config.baseUrl))) {
    return true
  }
  if (config.baseUrl) {
    log(`configured baseUrl ${config.baseUrl} is unreachable; auto-detecting phone...`)
  } else {
    log('no baseUrl set; auto-detecting phone via default gateway...')
  }
  const detected = await detectPhoneBaseUrl(config.port)
  if (detected) {
    if (detected !== config.baseUrl) log(`phone found at ${detected}`)
    config.baseUrl = detected
    return true
  }
  return false
}

async function heartbeatSafe(loggedIn: boolean): Promise<void> {
  try {
    await api.heartbeat(loggedIn)
  } catch (error) {
    log(`heartbeat failed: ${(error as Error).message}`)
  }
}

async function processOnce(cfg: WhatsAppRemoteConfig): Promise<void> {
  const batch = await api.nextBatch(config.batchLimit)
  if (!batch.recipients.length || !batch.message || batch.campaignId == null) {
    return
  }
  log(`Processing batch of ${batch.recipients.length} for campaign #${batch.campaignId}`)

  const results: ReportItem[] = []
  for (const recipient of batch.recipients) {
    if (stopping) break
    const label = recipient.contactName ?? recipient.phoneIntl
    const error = await wa.sendToRecipient(recipient, batch.message, cfg)
    if (error) {
      log(`  x ${label}: ${error}`)
      results.push({ recipientId: recipient.recipientId, success: false, error })
    } else {
      log(`  ✓ ${label}`)
      results.push({ recipientId: recipient.recipientId, success: true })
    }
    // Report incrementally so progress is visible on the dashboard even mid-batch.
    try {
      await api.report([results[results.length - 1]])
    } catch (reportError) {
      log(`  report failed: ${(reportError as Error).message}`)
    }
    await sleep(Math.max(1, cfg.sendDelaySeconds) * 1000)
  }
}

async function mainLoop(): Promise<void> {
  while (!stopping && !(await resolveBaseUrl())) {
    log('phone not found on the network; is the PC connected to the hotspot? retrying...')
    await sleep(config.pollIntervalMs)
  }
  if (stopping) return
  log(`Connecting to phone at ${config.baseUrl}`)
  await wa.start()
  log('Chromium started. If not logged in, scan the WhatsApp Web QR in the opened window.')

  while (!stopping) {
    let cfg: WhatsAppRemoteConfig
    try {
      cfg = await api.getConfig()
    } catch (error) {
      log(`cannot reach phone (${(error as Error).message}); re-detecting...`)
      await resolveBaseUrl()
      await sleep(config.pollIntervalMs)
      continue
    }

    const loggedIn = await wa.isLoggedIn(cfg)
    await heartbeatSafe(loggedIn)

    if (!loggedIn) {
      log('waiting for WhatsApp Web login (scan QR)...')
      await sleep(config.pollIntervalMs)
      continue
    }

    try {
      await processOnce(cfg)
    } catch (error) {
      log(`batch error: ${(error as Error).message}`)
    }
    await sleep(config.pollIntervalMs)
  }
}

async function shutdown(): Promise<void> {
  if (stopping) return
  stopping = true
  log('Shutting down...')
  await wa.stop()
  process.exit(0)
}

process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)

mainLoop().catch(async (error) => {
  log(`fatal: ${(error as Error).message}`)
  await wa.stop()
  process.exit(1)
})
