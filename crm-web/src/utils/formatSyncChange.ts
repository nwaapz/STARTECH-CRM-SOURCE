import type { SyncChange } from '@/api/types'
import { formatPhoneForDisplay } from '@/utils/formatPhone'

export function formatSyncChangeFa(event: SyncChange): string {
  switch (event.type) {
    case 'registered':
      return `ثبت ${event.serial ?? '—'} — ${formatPhoneForDisplay(event.phone) || '—'} — ${event.date ?? ''}`
    case 'registration_updated':
      return `تکمیل ثبت ${event.serial ?? '—'} — ${formatPhoneForDisplay(event.phone) || '—'}`
    case 'serials_added':
      return (
        event.message?.trim() ||
        `افزودن ${event.inserted ?? 0} سریال (تکراری: ${event.skipped ?? 0})`
      )
    case 'serials_removed':
      return (
        event.message?.trim() ||
        `حذف ${event.removed ?? 0} سریال`
      )
    case 'photo_approved':
      return event.message?.trim() || `تأیید عکس${event.serial ? ` سریال ${event.serial}` : ''}`
    case 'photo_rejected':
      return event.message?.trim() || `رد عکس${event.serial ? ` سریال ${event.serial}` : ''}`
    case 'bot_connected':
      return event.message?.trim() || `اتصال ربات${event.key ? ` ${event.key}` : ''}`
    case 'bot_disconnected':
      return event.message?.trim() || `قطع اتصال ربات${event.key ? ` ${event.key}` : ''}`
    case 'settings_changed':
      return event.message?.trim() || `تغییر تنظیمات${event.key ? `: ${event.key}` : ''}`
    case 'club_auto_created':
    case 'club_auto_updated':
    case 'club_auto_deleted':
    case 'club_auto_run':
      return event.message?.trim() || event.type
    default:
      return event.message?.trim() || event.type
  }
}
