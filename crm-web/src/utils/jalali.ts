import moment from 'moment-jalaali'

moment.loadPersian({ dialect: 'persian-modern' })

/** Today's Jalali date, e.g. 1404/04/12 */
export function todayJalaliDate(): string {
  return moment().format('jYYYY/jMM/jDD')
}

/** First and last day of the current Jalali month. */
export function currentJalaliMonthRange(): { from: string; to: string } {
  const from = moment().startOf('jMonth').format('jYYYY/jMM/jDD')
  const to = moment().endOf('jMonth').format('jYYYY/jMM/jDD')
  return { from, to }
}

/** Zero-padded Jalali date for a day in the given Jalali month (monthIndex 0–11). */
export function formatJalaliDayValue(year: number, monthIndex: number, day: number): string {
  return moment(`${year}/${monthIndex + 1}/${day}`, 'jYYYY/jM/jD').format('jYYYY/jMM/jDD')
}

/** Saturday-first column offset for day 1 of a Jalali month. */
export function firstDayWeekOffset(year: number, monthIndex: number): number {
  const first = moment(`${year}/${monthIndex + 1}/1`, 'jYYYY/jM/jD')
  return (first.day() + 1) % 7
}

/** Persian month names, index 0 = فروردین. */
export const JALALI_MONTH_NAMES = [
  'فروردین',
  'اردیبهشت',
  'خرداد',
  'تیر',
  'مرداد',
  'شهریور',
  'مهر',
  'آبان',
  'آذر',
  'دی',
  'بهمن',
  'اسفند',
] as const

/** Current Jalali year, e.g. 1404. */
export function currentJalaliYear(): number {
  return moment().jYear()
}

/** Number of days in a Jalali month (month 1–12). */
export function jalaliDaysInMonth(year: number, month: number): number {
  const days = moment.jDaysInMonth(year, month - 1)
  return Number.isFinite(days) && days > 0 ? days : 31
}

/** Converts Persian (۰-۹) and Arabic-Indic (٠-٩) digits to Latin (0-9). */
export function toLatinDigits(value: string): string {
  return value
    .replace(/[\u06F0-\u06F9]/g, (d) => String(d.charCodeAt(0) - 0x06f0))
    .replace(/[\u0660-\u0669]/g, (d) => String(d.charCodeAt(0) - 0x0660))
}

/** Parses a Jalali date string (jYYYY/jMM/jDD) into numeric parts, or null. */
export function parseJalaliParts(
  value?: string | null,
): { year: number; month: number; day: number } | null {
  if (!value) return null
  const match = toLatinDigits(value.trim()).match(/^(\d{1,4})[/-](\d{1,2})[/-](\d{1,2})$/)
  if (!match) return null
  const year = Number.parseInt(match[1], 10)
  const month = Number.parseInt(match[2], 10)
  const day = Number.parseInt(match[3], 10)
  if (!year || month < 1 || month > 12 || day < 1 || day > 31) return null
  return { year, month, day }
}
