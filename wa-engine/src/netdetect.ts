import { execFile } from 'node:child_process'
import { networkInterfaces } from 'node:os'
import { promisify } from 'node:util'

const execFileAsync = promisify(execFile)

/**
 * Discovers the phone's CRM base URL automatically.
 *
 * When the PC is connected to the phone's Wi-Fi hotspot, the phone is the PC's
 * default gateway. Its LAN IP changes every session (e.g. 10.139.21.55), so we
 * detect the gateway(s) from the OS routing table, plus a few subnet guesses,
 * and probe each candidate's /api/ping until one answers.
 */
export async function detectPhoneBaseUrl(port: number): Promise<string | null> {
  const candidates = await candidateHosts()
  for (const host of candidates) {
    const url = `http://${host}:${port}`
    if (await pingCrm(url)) return url
  }
  return null
}

/** Probe a single base URL and confirm it is the phone CRM. */
export async function pingCrm(baseUrl: string): Promise<boolean> {
  try {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), 3000)
    const response = await fetch(`${baseUrl}/api/ping`, { signal: controller.signal })
    clearTimeout(timer)
    if (!response.ok) return false
    const text = await response.text().catch(() => '')
    return text.toUpperCase().includes('CRM')
  } catch {
    return false
  }
}

async function candidateHosts(): Promise<string[]> {
  const hosts = new Set<string>()

  // 1) Default gateway(s) from the OS routing table — the phone when on hotspot.
  for (const gw of await defaultGateways()) hosts.add(gw)

  // 2) Fallback subnet guesses (.1) for every active IPv4 interface.
  const ifaces = networkInterfaces()
  for (const list of Object.values(ifaces)) {
    if (!list) continue
    for (const entry of list) {
      if (entry.family !== 'IPv4' || entry.internal) continue
      const parts = entry.address.split('.')
      if (parts.length === 4) {
        hosts.add(`${parts[0]}.${parts[1]}.${parts[2]}.1`)
      }
    }
  }

  return [...hosts]
}

async function defaultGateways(): Promise<string[]> {
  try {
    if (process.platform === 'win32') return await windowsGateways()
    return await unixGateways()
  } catch {
    return []
  }
}

async function windowsGateways(): Promise<string[]> {
  // `route print -4` lists default routes as: 0.0.0.0  0.0.0.0  <gateway> <iface> <metric>
  const { stdout } = await execFileAsync('route', ['print', '-4'], { windowsHide: true })
  const gateways: string[] = []
  for (const line of stdout.split(/\r?\n/)) {
    const cols = line.trim().split(/\s+/)
    if (cols.length >= 3 && cols[0] === '0.0.0.0' && cols[1] === '0.0.0.0') {
      if (isIpv4(cols[2])) gateways.push(cols[2])
    }
  }
  return gateways
}

async function unixGateways(): Promise<string[]> {
  // macOS/Linux: `netstat -rn` default line: "default  <gateway>  ..."
  const { stdout } = await execFileAsync('netstat', ['-rn'], {})
  const gateways: string[] = []
  for (const line of stdout.split(/\r?\n/)) {
    const cols = line.trim().split(/\s+/)
    if ((cols[0] === 'default' || cols[0] === '0.0.0.0') && isIpv4(cols[1])) {
      gateways.push(cols[1])
    }
  }
  return gateways
}

function isIpv4(value: string | undefined): boolean {
  if (!value) return false
  const parts = value.split('.')
  return parts.length === 4 && parts.every((p) => /^\d{1,3}$/.test(p) && Number(p) <= 255)
}
