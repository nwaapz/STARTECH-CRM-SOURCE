import type { jsPDF } from 'jspdf'
import reshaper from 'arabic-persian-reshaper'

const FONT_NAME = 'Vazirmatn'
const FONT_FILE = 'Vazirmatn-Regular.ttf'

let fontBase64Promise: Promise<string> | null = null
const fontsRegistered = new WeakSet<object>()

async function loadVazirmatnBase64(): Promise<string> {
  if (!fontBase64Promise) {
    fontBase64Promise = (async () => {
      const url = new URL('../assets/fonts/Vazirmatn-Regular.ttf', import.meta.url)
      const res = await fetch(url)
      if (!res.ok) {
        throw new Error('Failed to load Persian PDF font')
      }
      const buffer = await res.arrayBuffer()
      const bytes = new Uint8Array(buffer)
      let binary = ''
      const chunk = 0x8000
      for (let i = 0; i < bytes.length; i += chunk) {
        binary += String.fromCharCode(...bytes.subarray(i, i + chunk))
      }
      return btoa(binary)
    })()
  }
  return fontBase64Promise
}

/** Register Vazirmatn on a jsPDF instance (once per doc). */
export async function ensurePersianPdfFont(doc: jsPDF): Promise<string> {
  if (!fontsRegistered.has(doc)) {
    const base64 = await loadVazirmatnBase64()
    doc.addFileToVFS(FONT_FILE, base64)
    doc.addFont(FONT_FILE, FONT_NAME, 'normal')
    fontsRegistered.add(doc)
  }
  doc.setFont(FONT_NAME, 'normal')
  return FONT_NAME
}

/**
 * Shape Persian/Arabic presentation forms for jsPDF.
 * Do NOT reverse here — reversing made labels like «سریال» render as «لایرس».
 */
export function preparePersianPdfText(value: string | number | null | undefined): string {
  const text = value == null ? '' : String(value)
  if (!text) return ''
  if (!/[\u0600-\u06FF]/.test(text)) {
    return text
  }
  try {
    return reshaper.PersianShaper.convertArabic(text)
  } catch {
    return text
  }
}
