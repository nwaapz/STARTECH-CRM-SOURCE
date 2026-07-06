import type { SerialRegistrationErrorReason } from '@/api/types'

export function serialErrorReasonLabelFa(reason: SerialRegistrationErrorReason | string): string {
  switch (reason) {
    case 'not_found':
      return 'سریال در سیستم وجود ندارد'
    case 'already_used':
      return 'سریال قبلاً استفاده شده'
    default:
      return reason
  }
}
