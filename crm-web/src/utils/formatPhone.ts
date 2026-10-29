function toAsciiDigits(input: string): string {
  return input.replace(/[۰-۹٠-٩]/g, (ch) => {
    const code = ch.charCodeAt(0)
    if (code >= 0x06f0 && code <= 0x06f9) return String(code - 0x06f0)
    if (code >= 0x0660 && code <= 0x0669) return String(code - 0x0660)
    return ch
  })
}

/**
 * Display Iranian mobile numbers as 09... instead of +98 / 98 international prefix.
 */
export function formatPhoneForDisplay(phone: string | null | undefined): string {
  if (!phone) return ''
  const trimmed = toAsciiDigits(phone.trim()).replace(/\s/g, '')
  if (trimmed.startsWith('+98') && trimmed.length >= 13) {
    return `0${trimmed.slice(3)}`
  }
  if (trimmed.startsWith('98') && trimmed.length >= 12 && /\d/.test(trimmed[2] ?? '')) {
    return `0${trimmed.slice(2)}`
  }
  if (trimmed.length === 10 && trimmed.startsWith('9') && /^\d+$/.test(trimmed)) {
    return `0${trimmed}`
  }
  return trimmed
}

/** Normalize user input to local 09XXXXXXXXX form for save/API. */
export function normalizePhoneInput(phone: string): string {
  return formatPhoneForDisplay(phone)
}

export function isValidIranianMobile(phone: string): boolean {
  const normalized = normalizePhoneInput(phone)
  return /^09\d{9}$/.test(normalized)
}
