const MINUTE_MS = 60_000
const HOUR_MS = 60 * MINUTE_MS
const DAY_MS = 24 * HOUR_MS
const MONTH_MS = 30 * DAY_MS
const YEAR_MS = 365 * DAY_MS

function formatPersianNumber(value: number): string {
  return value.toLocaleString('fa-IR')
}

export function formatRelativeTimeFa(sentAtMs: number, nowMs = Date.now()): string {
  const elapsedMs = Math.max(0, nowMs - sentAtMs)

  if (elapsedMs < MINUTE_MS) {
    return 'همین الان'
  }

  const minutes = Math.floor(elapsedMs / MINUTE_MS)
  if (minutes < 60) {
    return `${formatPersianNumber(minutes)} دقیقه پیش`
  }

  const hours = Math.floor(elapsedMs / HOUR_MS)
  if (hours < 24) {
    return `${formatPersianNumber(hours)} ساعت پیش`
  }

  const days = Math.floor(elapsedMs / DAY_MS)
  if (days < 30) {
    return `${formatPersianNumber(days)} روز پیش`
  }

  const months = Math.floor(elapsedMs / MONTH_MS)
  if (months < 12) {
    return `${formatPersianNumber(months)} ماه پیش`
  }

  const years = Math.floor(elapsedMs / YEAR_MS)
  return `${formatPersianNumber(Math.max(years, 1))} سال پیش`
}
