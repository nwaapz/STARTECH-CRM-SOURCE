import {
  createClubAutoMessage,
  deleteClubAutoMessage,
  fetchClubAutoMessages,
  updateClubAutoMessage,
} from '@/api/client'
import type { ClubAutoMessageRule, SerialGroup } from '@/api/types'

export const WARRANTY_END_SOURCE = 'warranty_end'
export const WARRANTY_PRE_SOURCE = 'warranty_pre'

export function findLinkedWarrantyRules(
  rules: ClubAutoMessageRule[],
  groupId: number,
): { end?: ClubAutoMessageRule; pre?: ClubAutoMessageRule } {
  const linked = rules.filter(
    (r) =>
      r.linkedSerialGroupId === groupId ||
      (r.serialGroupIds?.length === 1 && r.serialGroupIds[0] === groupId && !!r.autoSource),
  )
  return {
    end: linked.find((r) => r.autoSource === WARRANTY_END_SOURCE),
    pre: linked.find((r) => r.autoSource === WARRANTY_PRE_SOURCE),
  }
}

export function defaultWarrantyEndMessage(months: number): string {
  return `گارانتی ${months} ماهه محصول شما به پایان رسید. سریال: {سریال}`
}

export function defaultWarrantyPreMessage(days: number): string {
  return `${days} روز تا پایان گارانتی محصول شما باقی مانده است. سریال: {سریال}`
}

export async function loadClubAutoRules(): Promise<ClubAutoMessageRule[]> {
  const response = await fetchClubAutoMessages()
  return response.items
}

export async function syncWarrantyEndRule(options: {
  group: SerialGroup
  enabled: boolean
  existing?: ClubAutoMessageRule
}): Promise<ClubAutoMessageRule | null> {
  const months = options.group.warrantyMonths
  if (options.enabled) {
    if (months == null || months <= 0) {
      throw new Error('برای پیام پایان گارانتی، مدت گارانتی (ماه) روی گروه لازم است')
    }
    const body = {
      title: `پایان گارانتی — ${options.group.rangeLabel}`,
      message: options.existing?.message?.trim() || defaultWarrantyEndMessage(months),
      offsetMonths: months,
      offsetDays: 0,
      sendHour: options.existing?.sendHour ?? 10,
      sendMinute: options.existing?.sendMinute ?? 0,
      delaySeconds: options.existing?.delaySeconds ?? 10,
      dailyCap: options.existing?.dailyCap ?? 200,
      sendSms: options.existing?.sendSms ?? true,
      sendRubika: options.existing?.sendRubika ?? false,
      sendTelegram: options.existing?.sendTelegram ?? false,
      sendBale: options.existing?.sendBale ?? false,
      serialCategory: options.group.category ?? 'end_user_client',
      serialGroupIds: [options.group.id],
      enabled: true,
      autoSource: WARRANTY_END_SOURCE as const,
      linkedSerialGroupId: options.group.id,
    }
    if (options.existing) {
      return updateClubAutoMessage(options.existing.id, body)
    }
    return createClubAutoMessage(body)
  }
  if (options.existing) {
    await deleteClubAutoMessage(options.existing.id)
  }
  return null
}

export async function syncWarrantyPreRule(options: {
  group: SerialGroup
  enabled: boolean
  daysBefore: number
  existing?: ClubAutoMessageRule
}): Promise<ClubAutoMessageRule | null> {
  const months = options.group.warrantyMonths
  const days = Math.max(1, Math.min(366, Math.round(options.daysBefore)))
  if (options.enabled) {
    if (months == null || months <= 0) {
      throw new Error('برای یادآوری قبل از پایان گارانتی، مدت گارانتی (ماه) روی گروه لازم است')
    }
    const body = {
      title: `یادآوری گارانتی — ${days} روز مانده — ${options.group.rangeLabel}`,
      message: options.existing?.message?.trim() || defaultWarrantyPreMessage(days),
      offsetMonths: months,
      offsetDays: -days,
      sendHour: options.existing?.sendHour ?? 10,
      sendMinute: options.existing?.sendMinute ?? 0,
      delaySeconds: options.existing?.delaySeconds ?? 10,
      dailyCap: options.existing?.dailyCap ?? 200,
      sendSms: options.existing?.sendSms ?? true,
      sendRubika: options.existing?.sendRubika ?? false,
      sendTelegram: options.existing?.sendTelegram ?? false,
      sendBale: options.existing?.sendBale ?? false,
      serialCategory: options.group.category ?? 'end_user_client',
      serialGroupIds: [options.group.id],
      enabled: true,
      autoSource: WARRANTY_PRE_SOURCE as const,
      linkedSerialGroupId: options.group.id,
    }
    if (options.existing) {
      return updateClubAutoMessage(options.existing.id, body)
    }
    return createClubAutoMessage(body)
  }
  if (options.existing) {
    await deleteClubAutoMessage(options.existing.id)
  }
  return null
}
