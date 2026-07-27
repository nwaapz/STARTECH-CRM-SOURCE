<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import ConfirmModal from '@/components/ConfirmModal.vue'
import type { MassSmsCampaign, MassSmsRecipient } from '@/api/types'
import {
  deleteMassSmsCampaign,
  fetchMassSmsCampaignDetail,
  pauseMassSmsCampaign,
  resumeMassSmsCampaign,
} from '@/api/client'
import { audienceLabel, campaignStatusLabel, recipientStatusLabel } from '@/data/iranProvinces'
import { formatDateTimeFa } from '@/utils/formatCommunication'
import { formatPhoneForDisplay } from '@/utils/formatPhone'

const props = defineProps<{
  campaign: MassSmsCampaign
}>()

const emit = defineEmits<{
  updated: [campaign: MassSmsCampaign]
  removed: [id: number]
}>()

const expanded = ref(false)
const loadingRecipients = ref(false)
const recipients = ref<MassSmsRecipient[]>([])
const recipientsTotal = ref(0)
const actionLoading = ref(false)
const deleteOpen = ref(false)
const error = ref('')

const processedCount = computed(
  () =>
    props.campaign.sentCount +
    props.campaign.failedCount +
    (props.campaign.skippedCount ?? 0),
)

const progressPercent = computed(() => {
  if (props.campaign.totalCount <= 0) return 0
  return Math.round((processedCount.value / props.campaign.totalCount) * 100)
})

const channelLabel = computed(() => {
  switch (props.campaign.channel) {
    case 'rubika':
      return 'ربات روبیکا'
    case 'telegram':
      return 'ربات تلگرام'
    case 'bale':
      return 'ربات بله'
    default:
      return 'پیامک'
  }
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
    const response = await fetchMassSmsCampaignDetail(props.campaign.id, { limit: 200 })
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
    const updated = await pauseMassSmsCampaign(props.campaign.id)
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
    const updated = await resumeMassSmsCampaign(props.campaign.id)
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
    await deleteMassSmsCampaign(props.campaign.id)
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
        <dt>کانال</dt>
        <dd>{{ channelLabel }}</dd>
      </div>
      <div v-if="campaign.mediaFileName">
        <dt>پیوست</dt>
        <dd>{{ campaign.mediaFileName }}</dd>
      </div>
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
        <dt>تأخیر</dt>
        <dd>{{ campaign.delaySeconds }} ثانیه</dd>
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
        {{ processedCount }} / {{ campaign.totalCount }}
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
      <div v-if="campaign.channel && campaign.channel !== 'sms'">
        <dt>رد‌شده</dt>
        <dd>{{ campaign.skippedCount ?? 0 }}</dd>
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
            <th>وضعیت</th>
            <th>زمان</th>
            <th>خطا</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in recipients" :key="row.id">
            <td>{{ formatPhoneForDisplay(row.phone) }}</td>
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
      title="حذف ارسال انبوه"
      message="آیا از حذف این کارت ارسال انبوه مطمئن هستید؟ تمام داده‌های مربوطه حذف می‌شوند."
      confirm-label="حذف"
      @confirm="confirmDelete"
      @cancel="deleteOpen = false"
    />
  </article>
</template>
