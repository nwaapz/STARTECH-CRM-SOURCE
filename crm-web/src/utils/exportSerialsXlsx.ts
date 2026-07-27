/** Minimal XLSX (SpreadsheetML) writer — single column, opens in Excel. */

function escapeXml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function buildWorkbookXml(serials: string[]): string {
  const rows = serials
    .map((serial) => {
      const value = escapeXml(serial.trim().toUpperCase())
      return `<Row><Cell><Data ss:Type="String">${value}</Data></Cell></Row>`
    })
    .join('')

  return `<?xml version="1.0"?>
<?mso-application progid="Excel.Sheet"?>
<Workbook xmlns="urn:schemas-microsoft-com:office:spreadsheet"
 xmlns:o="urn:schemas-microsoft-com:office:office"
 xmlns:x="urn:schemas-microsoft-com:office:excel"
 xmlns:ss="urn:schemas-microsoft-com:office:spreadsheet"
 xmlns:html="http://www.w3.org/TR/REC-html40">
 <Worksheet ss:Name="Serials">
  <Table>
${rows}
  </Table>
 </Worksheet>
</Workbook>`
}

function sanitizeFilenamePart(value: string): string {
  return value.replace(/[\\/:*?"<>|]+/g, '_').trim() || 'serials'
}

export function downloadSerialsAsExcel(serials: string[], filenameBase: string): void {
  const xml = buildWorkbookXml(serials)
  const blob = new Blob([xml], {
    type: 'application/vnd.ms-excel;charset=utf-8;',
  })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${sanitizeFilenamePart(filenameBase)}.xls`
  link.click()
  URL.revokeObjectURL(url)
}
