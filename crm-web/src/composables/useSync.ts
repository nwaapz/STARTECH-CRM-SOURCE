import { onMounted, onUnmounted, ref } from 'vue'
import { fetchSync, getSyncCursor, setSyncCursor } from '@/api/client'
import type { SimQuotaStatus, Stats, SyncChange } from '@/api/types'
import { formatPhoneForDisplay } from '@/utils/formatPhone'

export interface ToastMessage {
  id: string
  text: string
}

function prependChanges(existing: SyncChange[], incoming: SyncChange[]): SyncChange[] {
  return [...incoming, ...existing].slice(0, 50)
}

export function useSync(options: { intervalMs?: number; enabled?: () => boolean } = {}) {
  const intervalMs = options.intervalMs ?? 1000
  const stats = ref<Stats>({ total: 0, unused: 0, registered: 0 })
  const sims = ref<SimQuotaStatus[]>([])
  const changes = ref<SyncChange[]>([])
  const connected = ref(true)
  const cursor = ref(0)
  const toasts = ref<ToastMessage[]>([])
  const seenEvents = new Set<string>()
  const ready = ref(false)

  let timer: ReturnType<typeof setTimeout> | null = null
  let backoffMs = intervalMs

  function pushToast(text: string, key: string) {
    if (seenEvents.has(key)) return
    seenEvents.add(key)
    const id = `${Date.now()}-${Math.random()}`
    toasts.value = [...toasts.value, { id, text }]
    setTimeout(() => {
      toasts.value = toasts.value.filter((t) => t.id !== id)
    }, 5000)
  }

  function notifyChanges(responseChanges: SyncChange[]) {
    for (const change of responseChanges) {
      if (change.type === 'registered' && change.serial) {
        pushToast(
          `ثبت شد: ${change.serial} — ${formatPhoneForDisplay(change.phone)}`,
          `registered-${change.serial}-${change.at}`,
        )
      } else if (change.type === 'registration_updated' && change.serial) {
        pushToast(
          `تکمیل شد: ${change.serial}`,
          `updated-${change.serial}-${change.at}`,
        )
      } else if (change.type === 'serials_added') {
        pushToast(
          `سریال اضافه شد: ${change.inserted ?? 0} مورد`,
          `added-${change.at}`,
        )
      } else if (change.type === 'serials_removed') {
        pushToast(
          `سریال حذف شد: ${change.removed ?? 0} مورد`,
          `removed-${change.at}`,
        )
      } else if (change.type === 'photo_approved' || change.type === 'photo_rejected') {
        pushToast(
          change.message || (change.type === 'photo_approved' ? 'تأیید عکس' : 'رد عکس'),
          `${change.type}-${change.serial ?? ''}-${change.at}`,
        )
      } else if (change.type === 'bot_connected' || change.type === 'bot_disconnected') {
        pushToast(
          change.message || change.type,
          `${change.type}-${change.key ?? ''}-${change.at}`,
        )
      } else if (change.type === 'settings_changed') {
        pushToast(
          change.message || 'تنظیمات ذخیره شد',
          `settings-${change.key ?? ''}-${change.at}`,
        )
      } else if (
        change.type === 'club_auto_created' ||
        change.type === 'club_auto_updated' ||
        change.type === 'club_auto_deleted' ||
        change.type === 'club_auto_run'
      ) {
        pushToast(
          change.message || 'پیام خودکار باشگاه',
          `${change.type}-${change.ruleId ?? ''}-${change.at}`,
        )
      }
    }
  }

  async function initialize() {
    try {
      const bootstrap = await fetchSync(0)
      connected.value = true
      stats.value = bootstrap.stats
      sims.value = bootstrap.sims ?? []
      changes.value = prependChanges([], bootstrap.changes)

      let storedCursor = getSyncCursor()
      if (storedCursor === 0) {
        storedCursor = bootstrap.cursor
        setSyncCursor(storedCursor)
      }
      cursor.value = storedCursor
    } catch {
      connected.value = false
    } finally {
      ready.value = true
    }
  }

  async function poll() {
    if (!ready.value) {
      scheduleNext(intervalMs)
      return
    }
    if (options.enabled && !options.enabled()) {
      scheduleNext(intervalMs)
      return
    }
    if (document.visibilityState === 'hidden') {
      scheduleNext(intervalMs)
      return
    }
    try {
      const response = await fetchSync(cursor.value)
      connected.value = true
      backoffMs = intervalMs
      stats.value = response.stats
      sims.value = response.sims ?? []
      cursor.value = response.cursor
      setSyncCursor(response.cursor)
      if (response.changes.length > 0) {
        changes.value = prependChanges(changes.value, response.changes)
        notifyChanges(response.changes)
      }
      scheduleNext(intervalMs)
    } catch {
      connected.value = false
      backoffMs = Math.min(backoffMs * 2, 10000)
      scheduleNext(backoffMs)
    }
  }

  function scheduleNext(delay: number) {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      void poll()
    }, delay)
  }

  onMounted(() => {
    void initialize().then(() => {
      void poll()
    })
  })

  onUnmounted(() => {
    if (timer) clearTimeout(timer)
  })

  return { stats, sims, changes, connected, toasts, cursor }
}
