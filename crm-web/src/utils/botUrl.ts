export type BotUrlChannel = 'rubika' | 'telegram' | 'bale'

/** Public deep-link for a connected bot (matches Android BotInviteHelper). */
export function botPublicUrl(
  channel: BotUrlChannel,
  username: string | null | undefined,
): string | null {
  const raw = username?.trim()
  if (!raw) return null
  const clean = raw.replace(/^@+/, '')
  if (!clean) return null
  switch (channel) {
    case 'rubika':
      return `https://rubika.ir/${clean}`
    case 'telegram':
      return `https://t.me/${clean}`
    case 'bale':
      return `https://ble.ir/${clean}`
  }
}
