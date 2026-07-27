import * as XLSX from 'xlsx'

const SERIAL_PATTERN = /^[A-Za-z0-9]+$/
const MAX_SERIAL_LENGTH = 32
const MAX_IMPORT = 500_000

export interface ExcelSerialParseResult {
  serials: string[]
  emptyRows: number
  invalidRows: string[]
  fileName: string
}

/**
 * Reads the first worksheet and takes the first column as serial values.
 * Skips blank cells; collects a few invalid samples for UI feedback.
 */
export async function parseSerialsFromExcelFile(file: File): Promise<ExcelSerialParseResult> {
  const buffer = await file.arrayBuffer()
  const workbook = XLSX.read(buffer, { type: 'array', raw: false })
  const firstSheetName = workbook.SheetNames[0]
  if (!firstSheetName) {
    throw new Error('فایل اکسل خالی است یا برگه‌ای ندارد')
  }
  const sheet = workbook.Sheets[firstSheetName]
  const rows = XLSX.utils.sheet_to_json<(string | number | boolean | null | undefined)[]>(sheet, {
    header: 1,
    defval: '',
    raw: false,
  })

  const serials: string[] = []
  let emptyRows = 0
  const invalidRows: string[] = []

  for (const row of rows) {
    const cell = row?.[0]
    const raw = cell == null ? '' : String(cell).trim()
    if (!raw) {
      emptyRows += 1
      continue
    }
    const serial = raw.toUpperCase()
    if (serial.length > MAX_SERIAL_LENGTH || !SERIAL_PATTERN.test(serial)) {
      if (invalidRows.length < 5) invalidRows.push(raw)
      continue
    }
    serials.push(serial)
    if (serials.length > MAX_IMPORT) {
      throw new Error(`حداکثر ${MAX_IMPORT} سریال در هر ورود مجاز است`)
    }
  }

  return {
    serials,
    emptyRows,
    invalidRows,
    fileName: file.name,
  }
}
