export interface IranProvince {
  id: string
  name: string
  cities: string[]
}

export function provinceById(
  provinces: IranProvince[],
  id: string | null | undefined,
): IranProvince | undefined {
  if (!id) return undefined
  return provinces.find((p) => p.id === id)
}

export function provinceLabel(
  provinces: IranProvince[],
  id: string | null | undefined,
): string {
  return provinceById(provinces, id)?.name ?? 'همه استان‌ها'
}

export type MassSmsAudienceType = 'registered' | 'error' | 'all'

export function audienceLabel(type: MassSmsAudienceType): string {
  switch (type) {
    case 'registered':
      return 'ثبت‌شده با سریال صحیح'
    case 'error':
      return 'مشتریان خطادار'
    case 'all':
      return 'همه مشتریان'
  }
}

export function campaignStatusLabel(status: string): string {
  switch (status) {
    case 'queued':
      return 'در صف'
    case 'running':
      return 'در حال ارسال'
    case 'paused':
      return 'متوقف'
    case 'completed':
      return 'تکمیل‌شده'
    case 'cancelled':
      return 'لغو‌شده'
    default:
      return status
  }
}

export function recipientStatusLabel(status: string): string {
  switch (status) {
    case 'pending':
      return 'در انتظار'
    case 'sending':
      return 'در حال ارسال'
    case 'sent':
      return 'ارسال‌شده'
    case 'failed':
      return 'ناموفق'
    case 'skipped':
      return 'رد‌شده (بدون چت ربات)'
    default:
      return status
  }
}
