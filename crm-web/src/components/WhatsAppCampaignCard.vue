<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import ConfirmModal from '@/components/ConfirmModal.vue'
import type { WhatsAppCampaign, WhatsAppRecipient } from '@/api/types'
import {
  deleteWhatsAppCampaign,
  fetchWhatsAppCampaignDetail,
  pauseWhatsAppCampaign,
  resumeWhatsAppCampaign,
} from '@/api/client'
import { audienceLabel, campaignStatusLabel, recipientStatusLabel } from '@/data/iranProvinces'
import { formatDateTimeFa } from '@/utils/formatCommunication'
import { formatPhoneForDisplay } from '@/utils/formatPhone'

const props = defineProps<{
  campaign: WhatsAppCampaign
}>()

const emit = defineEmits<{
  updated: [campaign: WhatsAppCampaign]
  removed: [id: number]
}>()

const expanded = ref(false)
const loadingRecipients = ref(false)
const recipients = ref<WhatsAppRecipient[]>([])
const recipientsTotal = ref(0)
const actionLoading = ref(false)
const deleteOpen = ref(false)
const error = ref('')

const progressPercent = computed(() => {
  if (props.campaign.totalCount <= 0) return 0
  return Math.round(
    ((props.campaign.sentCount + props.campaign.failedCount) / props.campaign.totalCount) * 100,
  )
})

const createdLabel = computed(() => {
  const { date, time } = formatDateTimeFa(props.campaign.createdAt)
  return `${date} ${time}`
})

const startedLabel = computed(() => {
  if (!props.campaign.startedAt) return '—'
  const { date, time } = formatDateTimeFa(props.campaign.startedAt)
  return `${date} ${time}`
})

const completedLabel = computed(() => {
  if (!props.campaign.completedAt) return '—'
  const { date, time } = formatDateTimeFa(props.campaign.completedAt)
  return `${date} ${time}`
})

const locationSummary = computed(() => {
  if (props.campaign.audienceType === 'error') {
    return 'فیلتر مکانی: اعمال نمی‌شود (مشتریان خطادار)'
  }
  const province = props.campaign.provinceName ?? 'همه استان‌ها'
  const city = props.campaign.cityName ?? 'همه شهرها'
  return `${province} — ${city}`
})

const groupSummary = computed(() => {
  const labels = props.campaign.serialGroupLabels
  if (!labels?.length) return 'همه'
  return labels.join('، ')
})

const registrationDateSummary = computed(() => {
  const from = props.campaign.registrationFromDate
  const to = props.campaign.registrationToDate
  if (!from && !to) return 'همه زمان‌ها'
  if (from && to) return `${from} تا ${to}`
  if (from) return `از ${from}`
  return `تا ${to}`
})

watch(expanded, (open) => {
  if (open && recipients.value.length === 0) {
    void loadRecipients()
  }
})

async function loadRecipients() {
  loadingRecipients.value = true
  error.value = ''
  try {
    const response = await fetchWhatsAppCampaignDetail(props.campaign.id, { limit: 200 })
    recipients.value = response.recipients
    recipientsTotal.value = response.total
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در بارگذاری گیرندگان'
  } finally {
    loadingRecipients.value = false
  }
}

async function pause() {
  actionLoading.value = true
  error.value = ''
  try {
    const updated = await pauseWhatsAppCampaign(props.campaign.id)
    emit('updated', updated)
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در توقف'
  } finally {
    actionLoading.value = false
  }
}

async function resume() {
  actionLoading.value = true
  error.value = ''
  try {
    const updated = await resumeWhatsAppCampaign(props.campaign.id)
    emit('updated', updated)
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در ادامه'
  } finally {
    actionLoading.value = false
  }
}

async function confirmDelete() {
  actionLoading.value = true
  error.value = ''
  try {
    await deleteWhatsAppCampaign(props.campaign.id)
    deleteOpen.value = false
    emit('removed', props.campaign.id)
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در حذف'
  } finally {
    actionLoading.value = false
  }
}
</script>

<template>
  <article class="mass-sms-card">
    <div class="mass-sms-card-header">
      <div>
        <span :class="['status-badge', campaign.status]">
          {{ campaignStatusLabel(campaign.status) }}
        </span>
        <span v-if="campaign.queuePosition" class="queue-badge">
          نوبت در صف: {{ campaign.queuePosition }}
        </span>
      </div>
      <span class="hint">ایجاد: {{ createdLabel }}</span>
    </div>

    <p class="mass-sms-message">{{ campaign.message }}</p>

    <dl class="mass-sms-meta">
      <div>
        <dt>مخاطب</dt>
        <dd>{{ audienceLabel(campaign.audienceType) }}</dd>
      </div>
      <div>
        <dt>مکان</dt>
        <dd>{{ locationSummary }}</dd>
      </div>
      <div>
        <dt>گروه سریال</dt>
        <dd>{{ groupSummary }}</dd>
      </div>
      <div>
        <dt>تاریخ ثبت‌نام</dt>
        <dd>{{ registrationDateSummary }}</dd>
      </div>
      <div>
        <dt>شروع</dt>
        <dd>{{ startedLabel }}</dd>
      </div>
      <div>
        <dt>پایان</dt>
        <dd>{{ completedLabel }}</dd>
      </div>
    </dl>

    <div class="progress-wrap">
      <div class="progress-bar">
        <div class="progress-fill" :style="{ width: `${progressPercent}%` }" />
      </div>
      <span class="progress-label">
        {{ campaign.sentCount + campaign.failedCount }} / {{ campaign.totalCount }}
        ({{ progressPercent }}%)
      </span>
    </div>

    <dl class="mass-sms-stats">
      <div>
        <dt>ارسال‌شده</dt>
        <dd class="success">{{ campaign.sentCount }}</dd>
      </div>
      <div>
        <dt>ناموفق</dt>
        <dd class="error">{{ campaign.failedCount }}</dd>
      </div>
      <div>
        <dt>باقی‌مانده</dt>
        <dd>{{ campaign.pendingCount }}</dd>
      </div>
    </dl>

    <div class="mass-sms-card-actions">
      <button
        v-if="campaign.status === 'running'"
        type="button"
        class="secondary"
        :disabled="actionLoading"
        @click="pause"
      >
        توقف
      </button>
      <button
        v-if="campaign.status === 'paused'"
        type="button"
        class="primary"
        :disabled="actionLoading"
        @click="resume"
      >
        ادامه
      </button>
      <button type="button" class="secondary" @click="expanded = !expanded">
        {{ expanded ? 'بستن گیرندگان' : 'نمایش گیرندگان' }}
      </button>
      <button
        type="button"
        class="danger"
        :disabled="actionLoading"
        @click="deleteOpen = true"
      >
        حذف
      </button>
    </div>

    <div v-if="expanded" class="recipients-panel">
      <p v-if="loadingRecipients" class="hint">در حال بارگذاری...</p>
      <table v-else-if="recipients.length" class="recipients-table">
        <thead>
          <tr>
            <th>شماره</th>
            <th>مخاطب</th>
            <th>وضعیت</th>
            <th>زمان</th>
            <th>خطا</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in recipients" :key="row.id">
            <td>{{ formatPhoneForDisplay(row.phone) }}</td>
            <td>{{ row.contactName ?? '—' }}</td>
            <td>{{ recipientStatusLabel(row.status) }}</td>
            <td>
              {{
                row.sentAt
                  ? (() => {
                      const t = formatDateTimeFa(row.sentAt)
                      return `${t.date} ${t.time}`
                    })()
                  : '—'
              }}
            </td>
            <td>{{ row.errorMessage ?? '—' }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="hint">گیرنده‌ای یافت نشد</p>
      <p v-if="recipientsTotal > recipients.length" class="hint">
        نمایش {{ recipients.length }} از {{ recipientsTotal }} گیرنده
      </p>
    </div>

    <p v-if="error" class="error">{{ error }}</p>

    <ConfirmModal
      :open="deleteOpen"
      title="حذف ارسال واتساپ"
      message="آیا از حذف این کارت ارسال واتساپ مطمئن هستید؟ تمام داده‌های مربوطه حذف می‌شوند."
      confirm-label="حذف"
      @confirm="confirmDelete"
      @cancel="deleteOpen = false"
    />
  </article>
</template>

<style scoped>
.mass-sms-card {
  border: 1px solid #e0e0e0;
  border-radius: 12px;
  padding: 1rem 1.25rem;
  background: #fff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
}

.mass-sms-card-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  margin-bottom: 0.5rem;
}

.status-badge {
  display: inline-block;
  padding: 0.2rem 0.7rem;
  border-radius: 999px;
  font-size: 0.85rem;
  font-weight: 600;
  background: #eceff1;
  color: #37474f;
}

.status-badge.running {
  background: #e8f5e9;
  color: #2e7d32;
}

.status-badge.paused {
  background: #fff3e0;
  color: #ef6c00;
}

.status-badge.completed {
  background: #e3f2fd;
  color: #1565c0;
}

.status-badge.queued {
  background: #f3e5f5;
  color: #6a1b9a;
}

.queue-badge {
  margin-inline-start: 0.5rem;
  font-size: 0.8rem;
  color: #607d8b;
}

.mass-sms-message {
  white-space: pre-wrap;
  background: #f5f7f8;
  border-radius: 8px;
  padding: 0.65rem 0.85rem;
  margin: 0.5rem 0;
}

.mass-sms-meta,
.mass-sms-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr));
  gap: 0.5rem 1rem;
  margin: 0.5rem 0;
}

.mass-sms-meta div,
.mass-sms-stats div {
  display: flex;
  flex-direction: column;
}

.mass-sms-meta dt,
.mass-sms-stats dt {
  font-size: 0.78rem;
  color: #78909c;
}

.mass-sms-meta dd,
.mass-sms-stats dd {
  margin: 0;
  font-weight: 600;
}

dd.success {
  color: #2e7d32;
}

dd.error {
  color: #c62828;
}

.progress-wrap {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin: 0.75rem 0;
}

.progress-bar {
  flex: 1;
  height: 10px;
  background: #eceff1;
  border-radius: 999px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: #25d366;
  transition: width 0.3s;
}

.progress-label {
  font-size: 0.82rem;
  color: #546e7a;
  white-space: nowrap;
}

.mass-sms-card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-top: 0.5rem;
}

.recipients-panel {
  margin-top: 0.85rem;
  border-top: 1px solid #eceff1;
  padding-top: 0.75rem;
}

.recipients-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.85rem;
}

.recipients-table th,
.recipients-table td {
  text-align: right;
  padding: 0.35rem 0.5rem;
  border-bottom: 1px solid #eceff1;
}

.hint {
  color: #78909c;
  font-size: 0.85rem;
}

.error {
  color: #c62828;
}

button {
  padding: 0.5rem 1rem;
  border-radius: 8px;
  border: 1px solid transparent;
  font: inherit;
  cursor: pointer;
}

button.primary {
  background: #25d366;
  color: #fff;
}

button.secondary {
  background: #fff;
  border-color: #b0bec5;
}

button.danger {
  background: #fff;
  border-color: #ef9a9a;
  color: #c62828;
}

button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
