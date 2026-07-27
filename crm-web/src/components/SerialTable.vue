<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import type { SerialRow } from '@/api/types'
import { formatPhoneForDisplay } from '@/utils/formatPhone'
import { formatRelativeTimeFa } from '@/utils/formatRelativeTimeFa'

const props = defineProps<{
  items: SerialRow[]
  showReminderActions?: boolean
  clickableRows?: boolean
  sendingReminderId?: number | null
}>()

const emit = defineEmits<{
  sendReminder: [row: SerialRow]
  rowClick: [row: SerialRow]
}>()

const nowMs = ref(Date.now())
let refreshTimer: ReturnType<typeof setInterval> | undefined

onMounted(() => {
  refreshTimer = setInterval(() => {
    nowMs.value = Date.now()
  }, 60_000)
})

onUnmounted(() => {
  if (refreshTimer) {
    clearInterval(refreshTimer)
  }
})

function rowNeedsCompletion(row: SerialRow): boolean {
  if (row.needsCompletion === true) return true
  if (row.needsCompletion === false) return false
  return isMissingCity(row) || isMissingKm(row)
}

function isMissingCity(row: SerialRow): boolean {
  if (row.missingCity === true) return true
  if (row.missingCity === false) return false
  return Boolean(row.phone?.trim()) && !row.city?.trim()
}

function isMissingKm(row: SerialRow): boolean {
  if (row.missingKm === true) return true
  if (row.missingKm === false) return false
  if (row.category === 'seller_to_end_user') return false
  return Boolean(row.phone?.trim()) && row.km == null
}

function missingFieldsLabel(row: SerialRow): string {
  const missing: string[] = []
  if (isMissingCity(row)) missing.push('شهر')
  if (isMissingKm(row)) missing.push('کیلومتر')
  return missing.join('، ')
}

function reminderButtonLabel(row: SerialRow): string {
  if (props.sendingReminderId === row.id) {
    return 'در حال ارسال...'
  }
  if (row.completionReminderSent) {
    return 'هشدار ارسال شد'
  }
  return 'ارسال پیامک تکمیل ثبت'
}

function reminderSentTimeLabel(row: SerialRow): string | null {
  if (!row.completionReminderSent || row.completionReminderSentAt == null) {
    return null
  }
  return formatRelativeTimeFa(row.completionReminderSentAt, nowMs.value)
}

function sourceLabel(row: SerialRow): string {
  switch (row.registrationSource) {
    case 'sms':
      return 'پیامک'
    case 'rubika':
      return 'روبیکا'
    case 'telegram':
      return 'تلگرام'
    case 'bale':
      return 'بله'
    default:
      return row.registrationSource?.trim() ? row.registrationSource : '—'
  }
}

function onRowClick(row: SerialRow, event: MouseEvent) {
  if (!props.clickableRows) return
  const target = event.target as HTMLElement
  if (target.closest('button')) return
  emit('rowClick', row)
}
</script>

<template>
  <div class="table-wrap">
    <table>
      <thead>
        <tr>
          <th>سریال</th>
          <th>تلفن</th>
          <th>تاریخ</th>
          <th>شهر</th>
          <th>کیلومتر</th>
          <th>منبع</th>
          <th v-if="showReminderActions">یادآوری تکمیل</th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="items.length === 0">
          <td :colspan="showReminderActions ? 7 : 6" class="empty">موردی یافت نشد</td>
        </tr>
        <tr
          v-for="row in items"
          :key="row.id"
          :class="{ 'row-clickable': clickableRows }"
          @click="onRowClick(row, $event)"
        >
          <td>{{ row.serial }}</td>
          <td>{{ row.phone ? formatPhoneForDisplay(row.phone) : '—' }}</td>
          <td>{{ row.date ?? '—' }}</td>
          <td :class="{ 'cell-incomplete': isMissingCity(row), 'cell-inherited': row.cityInherited }">
            {{ row.city ?? '—' }}
            <small v-if="row.cityInherited" class="cell-inherited-badge">از ثبت قبلی</small>
            <small v-else-if="isMissingCity(row)" class="cell-warn">ناقص</small>
          </td>
          <td :class="{ 'cell-incomplete': isMissingKm(row) }">
            {{ row.km ?? '—' }}
            <small v-if="isMissingKm(row)" class="cell-warn">ناقص</small>
          </td>
          <td>{{ sourceLabel(row) }}</td>
          <td v-if="showReminderActions" class="actions-cell">
            <template v-if="rowNeedsCompletion(row)">
              <button
                type="button"
                class="reminder-btn"
                :class="{ sent: row.completionReminderSent }"
                :disabled="row.completionReminderSent || sendingReminderId === row.id"
                @click="emit('sendReminder', row)"
              >
                {{ reminderButtonLabel(row) }}
              </button>
              <small v-if="reminderSentTimeLabel(row)" class="sent-time">
                {{ reminderSentTimeLabel(row) }}
              </small>
              <small v-else-if="!row.completionReminderSent" class="missing-hint">
                ناقص: {{ missingFieldsLabel(row) }}
              </small>
            </template>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.cell-incomplete {
  background: #fff8e1;
  color: #e65100;
  font-weight: 600;
}

.cell-inherited {
  color: #455a64;
}

.cell-warn {
  display: block;
  margin-top: 0.15rem;
  color: #ef6c00;
  font-size: 0.72rem;
  font-weight: 500;
}

.cell-inherited-badge {
  display: block;
  margin-top: 0.12rem;
  color: #90a4ae;
  font-size: 0.65rem;
  font-weight: 400;
  line-height: 1.2;
}

.actions-cell {
  min-width: 11rem;
  vertical-align: top;
}

.reminder-btn {
  display: block;
  width: 100%;
  background: #1565c0;
  color: #fff;
  font-size: 0.85rem;
  padding: 0.45rem 0.65rem;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}

.reminder-btn.sent,
.reminder-btn:disabled {
  background: #eceff1;
  color: #607d8b;
  cursor: not-allowed;
  opacity: 1;
}

.reminder-btn:not(:disabled):not(.sent):hover {
  background: #0d47a1;
}

.missing-hint {
  display: block;
  margin-top: 0.35rem;
  color: #78909c;
  font-size: 0.78rem;
}

.sent-time {
  display: block;
  margin-top: 0.3rem;
  color: #90a4ae;
  font-size: 0.72rem;
}

.row-clickable {
  cursor: pointer;
  transition: background 0.15s ease;
}

.row-clickable:hover {
  background: #f3f8ff;
}
</style>
