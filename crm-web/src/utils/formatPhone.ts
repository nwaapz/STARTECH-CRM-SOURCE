/**
 * Display Iranian mobile numbers as 09... instead of +98 / 98 international prefix.
 */
export function formatPhoneForDisplay(phone: string | null | undefined): string {
  if (!phone) return ''
  const trimmed = phone.trim().replace(/\s/g, '')
  if (trimmed.startsWith('+98') && trimmed.length >= 13) {
    return `0${trimmed.slice(3)}`
  }
  if (trimmed.startsWith('98') && trimmed.length >= 12 && /\d/.test(trimmed[2] ?? '')) {
    return `0${trimmed.slice(2)}`
  }
  return trimmed
}
