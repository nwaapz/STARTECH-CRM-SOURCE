<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import type { SerialRow } from '@/api/types'
import { formatPhoneForDisplay } from '@/utils/formatPhone'
import { formatRelativeTimeFa } from '@/utils/formatRelativeTimeFa'

const props = defineProps<{
  items: SerialRow[]
  showReminderActions?: boolean
  sendingReminderId?: number | null
}>()

const emit = defineEmits<{
  sendReminder: [row: SerialRow]
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
  return Boolean(row.phone?.trim()) && (!row.city?.trim() || row.km == null)
}

function missingFieldsLabel(row: SerialRow): string {
  const missing: string[] = []
  if (!row.city) missing.push('شهر')
  if (row.km == null) missing.push('کیلومتر')
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
          <th v-if="showReminderActions">یادآوری تکمیل</th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="items.length === 0">
          <td :colspan="showReminderActions ? 6 : 5" class="empty">موردی یافت نشد</td>
        </tr>
        <tr v-for="row in items" :key="row.id">
          <td>{{ row.serial }}</td>
          <td>{{ row.phone ? formatPhoneForDisplay(row.phone) : '—' }}</td>
          <td>{{ row.date ?? '—' }}</td>
          <td>{{ row.city ?? '—' }}</td>
          <td>{{ row.km ?? '—' }}</td>
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
</style>
