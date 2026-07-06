export function formatDateTimeFa(timestamp: number): { date: string; time: string } {
  const date = new Date(timestamp)
  return {
    date: date.toLocaleDateString('fa-IR'),
    time: date.toLocaleTimeString('fa-IR', {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    }),
  }
}

export function truncateText(text: string, maxLength = 60): string {
  if (text.length <= maxLength) return text
  return `${text.slice(0, maxLength)}…`
}

export function statusLabelFa(status: string | null | undefined): string {
  switch (status) {
    case 'sent':
      return 'ارسال شد'
    case 'gateway_error':
      return 'خطای درگاه'
    case 'failed':
      return 'ناموفق'
    default:
      return status ?? ''
  }
}
