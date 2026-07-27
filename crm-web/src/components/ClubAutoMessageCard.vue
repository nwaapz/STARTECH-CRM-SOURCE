<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import ConfirmModal from '@/components/ConfirmModal.vue'
import UiSwitch from '@/components/UiSwitch.vue'
import type {
  ClubAutoMessageRule,
  ClubAutoMessageSendItem,
  ClubAutoMessagePreviewItem,
  ClubAutoMessageUpsertRequest,
  SerialGroup,
} from '@/api/types'
import {
  deleteClubAutoMessage,
  fetchClubAutoMessagePreview,
  fetchClubAutoMessageSends,
  runClubAutoMessageNow,
  tickClubAutoMessages,
  updateClubAutoMessage,
} from '@/api/client'
import { formatDateTimeFa } from '@/utils/formatCommunication'
import { formatPhoneForDisplay } from '@/utils/formatPhone'
import { todayJalaliDate } from '@/utils/jalali'

const props = defineProps<{
  rule: ClubAutoMessageRule
  serialGroups: SerialGroup[]
}>()

const emit = defineEmits<{
  updated: [rule: ClubAutoMessageRule]
  removed: [id: number]
}>()

const SERIAL_PLACEHOLDER = '{سریال}'
const KM_PLACEHOLDER = '{کیلومتر}'
const DATE_PLACEHOLDER = '{تاریخ}'

function insertPlaceholder(token: string) {
  const current = editMessage.value
  editMessage.value =
    current && !current.endsWith(' ') && current.length > 0
      ? `${current} ${token}`
      : `${current}${token}`
}

function insertSerialPlaceholder() {
  insertPlaceholder(SERIAL_PLACEHOLDER)
}

function insertKmPlaceholder() {
  insertPlaceholder(KM_PLACEHOLDER)
}

function insertDatePlaceholder() {
  insertPlaceholder(DATE_PLACEHOLDER)
}

const editing = ref(false)
const actionLoading = ref(false)
const deleteOpen = ref(false)
const error = ref('')

const editTitle = ref('')
const editMessage = ref('')
const editOffsetMonths = ref(3)
const editOffsetDays = ref(0)
const editSendHour = ref(10)
const editSendMinute = ref(0)
const editDelaySeconds = ref(10)
const editDailyCap = ref(200)
const editSendSms = ref(true)
const editSendRubika = ref(false)
const editSendTelegram = ref(false)
const editSendBale = ref(false)
const editSelectedGroupIds = ref<Set<number>>(new Set())
const editEnabled = ref(true)

const recipientsOpen = ref(false)
const recipientsTab = ref<'preview' | 'sent'>('preview')
const recipientsLoading = ref(false)
const recipientsError = ref('')
const recipients = ref<ClubAutoMessageSendItem[]>([])
const recipientsTotal = ref(0)
const recipientsPage = ref(1)
const recipientsLimit = 50

const previewItems = ref<ClubAutoMessagePreviewItem[]>([])
const previewTotal = ref(0)
const previewPending = ref(0)
const previewBlocked = ref(0)
const previewAlreadySent = ref(0)
const previewCutoff = ref('')
const previewPage = ref(1)

function warrantyLabel(months?: number | null, km?: number | null): string {
  const parts: string[] = []
  if (months != null && months > 0) parts.push(`${months} ماه`)
  if (km != null && km > 0) parts.push(`${km} کیلومتر`)
  return parts.length ? parts.join(' یا ') : 'بدون گارانتی'
}

function channelLabel(channel: string): string {
  switch (channel) {
    case 'sms':
      return 'پیامک'
    case 'rubika':
      return 'روبیکا'
    case 'telegram':
      return 'تلگرام'
    case 'bale':
      return 'بله'
    default:
      return channel
  }
}

function previewStatusLabel(status: string): string {
  switch (status) {
    case 'pending':
      return 'آماده ارسال'
    case 'already_sent':
      return 'قبلاً ارسال شده'
    case 'no_chat':
      return 'بدون چت‌آیدی'
    case 'no_token':
      return 'توکن تنظیم نشده'
    default:
      return status
  }
}

const offsetLabel = computed(() => {
  const parts: string[] = []
  if (props.rule.offsetMonths > 0) parts.push(`${props.rule.offsetMonths} ماه`)
  if (props.rule.offsetDays > 0) parts.push(`${props.rule.offsetDays} روز`)
  if (props.rule.offsetDays < 0) parts.push(`${Math.abs(props.rule.offsetDays)} روز مانده`)
  return parts.length ? parts.join(' و ') : '—'
})

const warrantyAutoBadge = computed(() => {
  if (props.rule.autoSource === 'warranty_end') return 'پایان گارانتی'
  if (props.rule.autoSource === 'warranty_pre') return 'یادآوری گارانتی'
  return ''
})

const timeLabel = computed(() => {
  const h = String(props.rule.sendHour).padStart(2, '0')
  const m = String(props.rule.sendMinute).padStart(2, '0')
  return `${h}:${m}`
})

const channelsLabel = computed(() => {
  const channels: string[] = []
  if (props.rule.sendSms) channels.push('پیامک')
  if (props.rule.sendRubika) channels.push('روبیکا')
  if (props.rule.sendTelegram) channels.push('تلگرام')
  if (props.rule.sendBale) channels.push('بله')
  return channels.length ? channels.join('، ') : '—'
})

const groupsLabel = computed(() => {
  if (props.rule.serialGroupLabels?.length) {
    return props.rule.serialGroupLabels.join('؛ ')
  }
  const ids = props.rule.serialGroupIds ?? []
  if (!ids.length) return '—'
  return ids
    .map((id) => {
      const group = props.serialGroups.find((g) => g.id === id)
      if (!group) return `#${id}`
      return `${group.rangeLabel} — ${warrantyLabel(group.warrantyMonths, group.warrantyKm)}`
    })
    .join('؛ ')
})

const lastRunLabel = computed(() => {
  if (!props.rule.lastRunAt) return 'هنوز اجرا نشده'
  const { date, time } = formatDateTimeFa(props.rule.lastRunAt)
  return `${date} ${time}`
})

const totalSentLabel = computed(() =>
  (props.rule.totalSentCount ?? 0).toLocaleString('fa-IR'),
)

/** Ticks every second so remaining-time countdown stays live. */
const nowMs = ref(Date.now())
let countdownTimer: ReturnType<typeof setInterval> | undefined

onMounted(() => {
  countdownTimer = setInterval(() => {
    nowMs.value = Date.now()
  }, 1000)
})

onUnmounted(() => {
  if (countdownTimer) clearInterval(countdownTimer)
})

function pad2(n: number): string {
  return String(n).padStart(2, '0')
}

function formatDuration(ms: number): string {
  const totalSec = Math.max(0, Math.floor(ms / 1000))
  const hours = Math.floor(totalSec / 3600)
  const minutes = Math.floor((totalSec % 3600) / 60)
  const seconds = totalSec % 60
  return `${pad2(hours)}:${pad2(minutes)}:${pad2(seconds)}`
}

const alreadyRanToday = computed(() => {
  const jalali = props.rule.lastRunDateJalali
  return !!jalali && jalali === todayJalaliDate()
})

const nextSendAt = computed(() => {
  const now = new Date(nowMs.value)
  const target = new Date(now)
  target.setHours(props.rule.sendHour, props.rule.sendMinute, 0, 0)
  if (alreadyRanToday.value || now.getTime() >= target.getTime()) {
    target.setDate(target.getDate() + 1)
  }
  return target
})

const remainingMs = computed(() => Math.max(0, nextSendAt.value.getTime() - nowMs.value))

const isDueNow = computed(() => {
  if (!props.rule.enabled || alreadyRanToday.value) return false
  const now = new Date(nowMs.value)
  const target = new Date(now)
  target.setHours(props.rule.sendHour, props.rule.sendMinute, 0, 0)
  return now.getTime() >= target.getTime()
})

const isRunning = computed(() => props.rule.runStatus === 'running')

const delaySecondsLabel = computed(() =>
  (props.rule.delaySeconds ?? 10).toLocaleString('fa-IR'),
)

const dailyCapLabel = computed(() =>
  (props.rule.dailyCap ?? 200).toLocaleString('fa-IR'),
)

const runTotal = computed(() => props.rule.runTotalCount ?? 0)
const runProcessed = computed(() => props.rule.runProcessedCount ?? 0)
const runSent = computed(() => props.rule.runSentCount ?? 0)
const runFail = computed(() => props.rule.runFailCount ?? 0)

const progressPercent = computed(() => {
  if (runTotal.value <= 0) return 0
  return Math.min(100, Math.round((runProcessed.value / runTotal.value) * 100))
})

const showProgress = computed(
  () => isRunning.value || (runTotal.value > 0 && (isDueNow.value || alreadyRanToday.value)),
)

const remainingLabel = computed(() => {
  if (!props.rule.enabled) return 'غیرفعال — شمارش‌معکوس متوقف'
  if (isRunning.value) {
    return `در حال ارسال… ${runProcessed.value.toLocaleString('fa-IR')} / ${runTotal.value.toLocaleString('fa-IR')}`
  }
  if (alreadyRanToday.value) {
    return `امروز ارسال شد — مانده تا فردا: ${formatDuration(remainingMs.value)}`
  }
  if (isDueNow.value) return 'زمان ارسال فرا رسیده — در انتظار گیت‌وی…'
  return `مانده تا ارسال: ${formatDuration(remainingMs.value)}`
})

const countdownClass = computed(() => ({
  due: isDueNow.value && !isRunning.value,
  running: isRunning.value,
  done: alreadyRanToday.value && props.rule.enabled && !isRunning.value,
  paused: !props.rule.enabled,
}))

/** One nudge per rule per Jalali day when countdown hits due. */
let nudgedDueKey = ''

watch(isDueNow, (due) => {
  if (!due || !props.rule.enabled || alreadyRanToday.value || isRunning.value) return
  const key = `${props.rule.id}:${todayJalaliDate()}`
  if (nudgedDueKey === key) return
  nudgedDueKey = key
  void tickClubAutoMessages().catch(() => {
    /* gateway may be offline; device loop still handles send */
  })
})

watch(
  () => props.rule,
  (rule) => {
    if (editing.value) return
    syncEditFromRule(rule)
  },
  { immediate: true },
)

function syncEditFromRule(rule: ClubAutoMessageRule) {
  editTitle.value = rule.title
  editMessage.value = rule.message
  editOffsetMonths.value = rule.offsetMonths
  editOffsetDays.value = rule.offsetDays
  editSendHour.value = rule.sendHour
  editSendMinute.value = rule.sendMinute
  editDelaySeconds.value = rule.delaySeconds ?? 10
  editDailyCap.value = rule.dailyCap ?? 200
  editSendSms.value = rule.sendSms
  editSendRubika.value = rule.sendRubika
  editSendTelegram.value = rule.sendTelegram
  editSendBale.value = !!rule.sendBale
  editSelectedGroupIds.value = new Set(rule.serialGroupIds ?? [])
  editEnabled.value = rule.enabled
}

function startEdit() {
  syncEditFromRule(props.rule)
  editing.value = true
  error.value = ''
}

function cancelEdit() {
  editing.value = false
  error.value = ''
  syncEditFromRule(props.rule)
}

function isGroupSelected(id: number): boolean {
  return editSelectedGroupIds.value.has(id)
}

function toggleGroup(id: number) {
  const next = new Set(editSelectedGroupIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  editSelectedGroupIds.value = next
}

function selectAllGroups() {
  editSelectedGroupIds.value = new Set(props.serialGroups.map((g) => g.id))
}

function clearGroupSelection() {
  editSelectedGroupIds.value = new Set()
}

function buildBody(): ClubAutoMessageUpsertRequest {
  return {
    title: editTitle.value.trim(),
    message: editMessage.value.trim(),
    offsetMonths: Number(editOffsetMonths.value) || 0,
    offsetDays: Number(editOffsetDays.value) || 0,
    sendHour: Number(editSendHour.value) || 0,
    sendMinute: Number(editSendMinute.value) || 0,
    delaySeconds: Math.max(1, Number(editDelaySeconds.value) || 10),
    dailyCap: Math.max(1, Math.min(10000, Number(editDailyCap.value) || 200)),
    sendSms: editSendSms.value,
    sendRubika: editSendRubika.value,
    sendTelegram: editSendTelegram.value,
    sendBale: editSendBale.value,
    serialGroupIds: Array.from(editSelectedGroupIds.value),
    enabled: editEnabled.value,
  }
}

async function saveEdit() {
  if (editSelectedGroupIds.value.size === 0) {
    error.value = 'حداقل یک گروه سریال انتخاب کنید'
    return
  }
  actionLoading.value = true
  error.value = ''
  try {
    const updated = await updateClubAutoMessage(props.rule.id, buildBody())
    editing.value = false
    emit('updated', updated)
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در ذخیره'
  } finally {
    actionLoading.value = false
  }
}

async function toggleEnabled(next: boolean) {
  actionLoading.value = true
  error.value = ''
  try {
    const updated = await updateClubAutoMessage(props.rule.id, {
      title: props.rule.title,
      message: props.rule.message,
      offsetMonths: props.rule.offsetMonths,
      offsetDays: props.rule.offsetDays,
      sendHour: props.rule.sendHour,
      sendMinute: props.rule.sendMinute,
      delaySeconds: props.rule.delaySeconds ?? 10,
      dailyCap: props.rule.dailyCap ?? 200,
      sendSms: props.rule.sendSms,
      sendRubika: props.rule.sendRubika,
      sendTelegram: props.rule.sendTelegram,
      sendBale: props.rule.sendBale ?? false,
      serialGroupIds: props.rule.serialGroupIds ?? [],
      enabled: next,
    })
    emit('updated', updated)
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در به‌روزرسانی'
  } finally {
    actionLoading.value = false
  }
}

async function runNow() {
  actionLoading.value = true
  error.value = ''
  try {
    const updated = await runClubAutoMessageNow(props.rule.id)
    emit('updated', updated)
    if (recipientsOpen.value) {
      if (recipientsTab.value === 'preview') {
        await loadPreview(1)
      } else {
        await loadRecipients(1)
      }
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در اجرای فوری'
  } finally {
    actionLoading.value = false
  }
}

async function confirmDelete() {
  actionLoading.value = true
  error.value = ''
  try {
    await deleteClubAutoMessage(props.rule.id)
    deleteOpen.value = false
    emit('removed', props.rule.id)
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در حذف'
  } finally {
    actionLoading.value = false
  }
}

async function toggleRecipients() {
  if (recipientsOpen.value) {
    recipientsOpen.value = false
    return
  }
  recipientsOpen.value = true
  recipientsTab.value = 'preview'
  await loadPreview(1)
}

async function selectRecipientsTab(tab: 'preview' | 'sent') {
  recipientsTab.value = tab
  if (tab === 'preview') {
    await loadPreview(previewPage.value || 1)
  } else {
    await loadRecipients(recipientsPage.value || 1)
  }
}

async function loadPreview(page: number) {
  recipientsLoading.value = true
  recipientsError.value = ''
  previewPage.value = page
  try {
    const response = await fetchClubAutoMessagePreview(
      props.rule.id,
      page,
      recipientsLimit,
    )
    previewItems.value = response.items
    previewTotal.value = response.total
    previewPending.value = response.pendingCount
    previewBlocked.value = response.blockedCount
    previewAlreadySent.value = response.alreadyFullySentCount
    previewCutoff.value = response.cutoffDateJalali
  } catch (e) {
    recipientsError.value = e instanceof Error ? e.message : 'خطا در بارگذاری پیش‌نمایش'
  } finally {
    recipientsLoading.value = false
  }
}

async function loadRecipients(page: number) {
  recipientsLoading.value = true
  recipientsError.value = ''
  recipientsPage.value = page
  try {
    const response = await fetchClubAutoMessageSends(
      props.rule.id,
      page,
      recipientsLimit,
    )
    recipients.value = response.items
    recipientsTotal.value = response.total
  } catch (e) {
    recipientsError.value = e instanceof Error ? e.message : 'خطا در بارگذاری گیرندگان'
  } finally {
    recipientsLoading.value = false
  }
}

function formatSentAt(item: ClubAutoMessageSendItem): string {
  const { date, time } = formatDateTimeFa(item.sentAt)
  return `${date} ${time}`
}

function formatChannelsStatus(item: ClubAutoMessagePreviewItem): string {
  return item.channels
    .map((ch) => `${channelLabel(ch.channel)}: ${previewStatusLabel(ch.status)}`)
    .join(' — ')
}
</script>

<template>
  <article class="club-rule-card" :class="{ disabled: !rule.enabled }">
    <div class="club-rule-header">
      <div>
        <h3>{{ rule.title }}</h3>
        <span v-if="warrantyAutoBadge" class="warranty-source-badge">{{ warrantyAutoBadge }}</span>
        <p class="hint">
          فاصله: {{ offsetLabel }} — ساعت روزانه: {{ timeLabel }} — تأخیر:
          {{ delaySecondsLabel }} ثانیه — سقف روزانه: {{ dailyCapLabel }} سریال — کانال:
          {{ channelsLabel }}
        </p>
        <p class="hint">گروه‌ها: {{ groupsLabel }}</p>
        <p class="countdown" :class="countdownClass">
          {{ remainingLabel }}
        </p>
        <div v-if="showProgress" class="progress-wrap">
          <div class="progress-bar">
            <div class="progress-fill" :style="{ width: `${progressPercent}%` }" />
          </div>
          <span class="progress-label">
            {{ runProcessed.toLocaleString('fa-IR') }} /
            {{ runTotal.toLocaleString('fa-IR') }}
            ({{ progressPercent.toLocaleString('fa-IR') }}%) —
            موفق: {{ runSent.toLocaleString('fa-IR') }} /
            ناموفق: {{ runFail.toLocaleString('fa-IR') }}
          </span>
        </div>
      </div>
      <UiSwitch
        :model-value="rule.enabled"
        :disabled="actionLoading || editing"
        label="فعال"
        @update:model-value="toggleEnabled"
      />
    </div>

    <p class="message-preview">{{ rule.message }}</p>
    <p class="hint">
      آخرین اجرا: {{ lastRunLabel }}
      <template v-if="rule.lastRunAt">
        — موفق: {{ rule.lastSuccessCount.toLocaleString('fa-IR') }} / ناموفق:
        {{ rule.lastFailCount.toLocaleString('fa-IR') }}
      </template>
    </p>
    <p class="hint">
      آلارم‌های ثبت‌شده: {{ totalSentLabel }} — هر سریال در هر کانال فقط یک‌بار؛ باقی‌مانده بیش از سقف روزانه فردا ارسال می‌شود
    </p>

    <div v-if="editing" class="edit-form">
      <label>
        عنوان
        <input v-model="editTitle" type="text" />
      </label>
      <label>
        متن پیام
        <textarea v-model="editMessage" rows="3" />
      </label>
      <p class="hint placeholder-hint">
        پارامترهای قابل استفاده:
        <button type="button" class="link-btn" @click="insertSerialPlaceholder">{{ SERIAL_PLACEHOLDER }}</button>
        <button type="button" class="link-btn" @click="insertKmPlaceholder">{{ KM_PLACEHOLDER }}</button>
        <button type="button" class="link-btn" @click="insertDatePlaceholder">{{ DATE_PLACEHOLDER }}</button>
        — هنگام ارسال با مقادیر ثبت‌شده هر مشتری جایگزین می‌شوند.
      </p>
      <div class="form-grid">
        <label>
          ماه قبل
          <input v-model.number="editOffsetMonths" type="number" min="0" />
        </label>
        <label>
          روز قبل
          <input v-model.number="editOffsetDays" type="number" min="0" />
        </label>
        <label>
          ساعت
          <input v-model.number="editSendHour" type="number" min="0" max="23" />
        </label>
        <label>
          دقیقه
          <input v-model.number="editSendMinute" type="number" min="0" max="59" />
        </label>
        <label>
          تأخیر بین پیام‌ها (ثانیه)
          <input v-model.number="editDelaySeconds" type="number" min="1" />
        </label>
        <label>
          سقف ارسال روزانه (سریال)
          <input v-model.number="editDailyCap" type="number" min="1" max="10000" />
        </label>
      </div>
      <p class="hint">
        سریال‌های واجد شرایط قدیمی‌تر اول ارسال می‌شوند. اگر بیش از سقف روزانه باشند، بقیه روز بعد.
      </p>
      <div class="group-edit-block">
        <div class="group-helper-actions">
          <button type="button" class="link-btn" @click="selectAllGroups">انتخاب همه</button>
          <button type="button" class="link-btn" @click="clearGroupSelection">پاک کردن</button>
        </div>
        <div class="group-chip-grid">
          <button
            v-for="group in serialGroups"
            :key="group.id"
            type="button"
            class="group-chip"
            :class="{ selected: isGroupSelected(group.id) }"
            @click="toggleGroup(group.id)"
          >
            <span class="group-chip-label">{{ group.rangeLabel }}</span>
            <span class="group-chip-warranty">{{
              warrantyLabel(group.warrantyMonths, group.warrantyKm)
            }}</span>
          </button>
        </div>
      </div>
      <div class="source-filter-toggles">
        <UiSwitch v-model="editSendSms" label="پیامک" />
        <UiSwitch v-model="editSendRubika" label="روبیکا" />
        <UiSwitch v-model="editSendTelegram" label="تلگرام" />
        <UiSwitch v-model="editSendBale" label="بله" />
      </div>
      <div class="actions">
        <button class="primary" :disabled="actionLoading" @click="saveEdit">ذخیره</button>
        <button class="secondary" :disabled="actionLoading" @click="cancelEdit">انصراف</button>
      </div>
    </div>

    <div v-else class="actions">
      <button class="secondary" :disabled="actionLoading" @click="startEdit">ویرایش</button>
      <button class="secondary" :disabled="actionLoading" @click="runNow">اجرای فوری</button>
      <button class="secondary" :disabled="actionLoading" @click="toggleRecipients">
        {{ recipientsOpen ? 'بستن فهرست' : 'فهرست گیرندگان' }}
      </button>
      <button class="danger" :disabled="actionLoading" @click="deleteOpen = true">حذف</button>
    </div>

    <div v-if="recipientsOpen" class="recipients-panel">
      <div class="recipients-tabs">
        <button
          type="button"
          class="tab-btn"
          :class="{ active: recipientsTab === 'preview' }"
          :disabled="recipientsLoading"
          @click="selectRecipientsTab('preview')"
        >
          پیش‌نمایش قبل از ارسال
        </button>
        <button
          type="button"
          class="tab-btn"
          :class="{ active: recipientsTab === 'sent' }"
          :disabled="recipientsLoading"
          @click="selectRecipientsTab('sent')"
        >
          ارسال‌شده‌ها
        </button>
      </div>

      <p v-if="recipientsLoading" class="hint">در حال بارگذاری…</p>
      <p v-else-if="recipientsError" class="error">{{ recipientsError }}</p>
      <template v-else-if="recipientsTab === 'preview'">
        <p class="hint">
          ثبت‌نام تا تاریخ {{ previewCutoff || '—' }} —
          واجد شرایط: {{ previewTotal.toLocaleString('fa-IR') }} —
          آماده ارسال: {{ previewPending.toLocaleString('fa-IR') }} —
          مسدود/بدون چت: {{ previewBlocked.toLocaleString('fa-IR') }} —
          قبلاً کامل ارسال‌شده: {{ previewAlreadySent.toLocaleString('fa-IR') }}
        </p>
        <p v-if="previewItems.length === 0" class="hint">هیچ گیرنده‌ای برای این قانون پیدا نشد.</p>
        <div v-else class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>سریال</th>
                <th>شماره</th>
                <th>وضعیت کانال‌ها</th>
                <th>ارسال می‌شود؟</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="item in previewItems"
                :key="`${item.serial || ''}-${item.phone}`"
                :class="{ 'will-send': item.willSendAny }"
              >
                <td>{{ item.serial || '—' }}</td>
                <td>{{ formatPhoneForDisplay(item.phone) }}</td>
                <td>{{ formatChannelsStatus(item) }}</td>
                <td>{{ item.willSendAny ? 'بله' : 'خیر' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="actions recipients-pager">
          <button
            class="secondary"
            :disabled="previewPage <= 1 || recipientsLoading"
            @click="loadPreview(previewPage - 1)"
          >
            قبلی
          </button>
          <span class="hint">
            صفحه {{ previewPage.toLocaleString('fa-IR') }} —
            {{ previewTotal.toLocaleString('fa-IR') }} مورد
          </span>
          <button
            class="secondary"
            :disabled="previewPage * recipientsLimit >= previewTotal || recipientsLoading"
            @click="loadPreview(previewPage + 1)"
          >
            بعدی
          </button>
        </div>
      </template>
      <template v-else>
        <p v-if="recipients.length === 0" class="hint">هنوز گیرنده‌ای ثبت نشده است.</p>
        <div v-else class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>سریال</th>
                <th>شماره</th>
                <th>کانال</th>
                <th>تاریخ ارسال</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in recipients" :key="item.id">
                <td>{{ item.serial && !item.serial.startsWith('__phone__') ? item.serial : '—' }}</td>
                <td>{{ formatPhoneForDisplay(item.phone) }}</td>
                <td>{{ channelLabel(item.channel) }}</td>
                <td>{{ formatSentAt(item) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="actions recipients-pager">
          <button
            class="secondary"
            :disabled="recipientsPage <= 1 || recipientsLoading"
            @click="loadRecipients(recipientsPage - 1)"
          >
            قبلی
          </button>
          <span class="hint">
            صفحه {{ recipientsPage.toLocaleString('fa-IR') }} —
            {{ recipientsTotal.toLocaleString('fa-IR') }} مورد
          </span>
          <button
            class="secondary"
            :disabled="recipientsPage * recipientsLimit >= recipientsTotal || recipientsLoading"
            @click="loadRecipients(recipientsPage + 1)"
          >
            بعدی
          </button>
        </div>
      </template>
    </div>

    <p v-if="error" class="error">{{ error }}</p>

    <ConfirmModal
      :open="deleteOpen"
      title="حذف پیام خودکار"
      :message="`آیا از حذف «${rule.title}» مطمئن هستید؟ فهرست گیرندگان این قانون هم پاک می‌شود.`"
      confirm-label="حذف"
      :loading="actionLoading"
      @cancel="deleteOpen = false"
      @confirm="confirmDelete"
    />
  </article>
</template>

<style scoped>
.club-rule-card {
  margin-top: 1rem;
  padding: 1rem;
  border: 1px solid #e0e0e0;
  border-radius: 10px;
  background: #fafafa;
}

.club-rule-card.disabled {
  opacity: 0.72;
}

.club-rule-header {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.75rem;
}

.club-rule-header h3 {
  margin: 0 0 0.25rem;
  font-size: 1.05rem;
}

.countdown {
  margin: 0.35rem 0 0;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: #1565c0;
  font-family: ui-monospace, 'Cascadia Mono', 'Segoe UI Mono', monospace;
}

.countdown.due {
  color: #ef6c00;
}

.countdown.running {
  color: #1565c0;
}

.countdown.done {
  color: #2e7d32;
}

.countdown.paused {
  color: #757575;
  font-weight: 600;
}

.progress-wrap {
  margin-top: 0.5rem;
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  max-width: 420px;
}

.progress-bar {
  height: 8px;
  border-radius: 999px;
  background: #e0e0e0;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: #1565c0;
  transition: width 0.3s ease;
}

.progress-label {
  font-size: 0.85rem;
  color: #455a64;
}

.message-preview {
  margin: 0.75rem 0;
  white-space: pre-wrap;
  line-height: 1.6;
}

.edit-form {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  margin-top: 0.75rem;
}

.edit-form textarea {
  resize: vertical;
  min-height: 4.5rem;
}

.group-edit-block {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.group-helper-actions {
  display: flex;
  gap: 1rem;
}

.link-btn {
  padding: 0;
  border: none;
  background: none;
  color: #1565c0;
  font: inherit;
  cursor: pointer;
  text-decoration: underline;
}

.group-chip-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  max-height: 180px;
  overflow-y: auto;
}

.group-chip {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.15rem;
  padding: 0.45rem 0.75rem;
  border: 1px solid #cfd8dc;
  border-radius: 12px;
  background: #fff;
  font: inherit;
  cursor: pointer;
  text-align: right;
}

.group-chip.selected {
  background: #e3f2fd;
  border-color: #1565c0;
}

.group-chip-label {
  font-weight: 600;
  font-size: 0.9rem;
}

.group-chip-warranty {
  font-size: 0.8rem;
  color: #2e7d32;
  font-weight: 600;
}

.recipients-panel {
  margin-top: 0.75rem;
  padding-top: 0.75rem;
  border-top: 1px solid #e0e0e0;
}

.recipients-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
}

.tab-btn {
  padding: 0.35rem 0.75rem;
  border: 1px solid #cfd8dc;
  border-radius: 8px;
  background: #fff;
  font: inherit;
  cursor: pointer;
}

.tab-btn.active {
  background: #e3f2fd;
  border-color: #1565c0;
  color: #0d47a1;
  font-weight: 600;
}

.table-wrap tr.will-send td:last-child {
  color: #2e7d32;
  font-weight: 600;
}

.table-wrap {
  overflow-x: auto;
}

.recipients-panel table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.9rem;
}

.recipients-panel th,
.recipients-panel td {
  padding: 0.4rem 0.5rem;
  border-bottom: 1px solid #eee;
  text-align: right;
}

.recipients-pager {
  margin-top: 0.5rem;
  align-items: center;
}

.danger {
  background: #c62828;
  color: #fff;
  border: none;
}
</style>
