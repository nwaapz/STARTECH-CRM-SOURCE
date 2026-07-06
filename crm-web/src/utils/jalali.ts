import moment from 'moment-jalaali'

moment.loadPersian({ dialect: 'persian-modern' })

/** Today's Jalali date, e.g. 1404/04/12 */
export function todayJalaliDate(): string {
  return moment().format('jYYYY/jMM/jDD')
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
