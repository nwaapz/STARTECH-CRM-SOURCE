<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import ConfirmModal from '@/components/ConfirmModal.vue'
import JalaliDateSelects from '@/components/JalaliDateSelects.vue'
import UiSwitch from '@/components/UiSwitch.vue'
import type { ClubAnnouncement, ClubAnnouncementSendItem, ClubAnnouncementUpsertRequest } from '@/api/types'
import {
  deleteAnnouncement,
  fetchAnnouncementSends,
  runAnnouncementNow,
  updateAnnouncement,
} from '@/api/client'
import { formatDateTimeFa } from '@/utils/formatCommunication'
import { formatPhoneForDisplay } from '@/utils/formatPhone'

const props = defineProps<{
  announcement: ClubAnnouncement
}>()

const emit = defineEmits<{
  updated: [announcement: ClubAnnouncement]
  removed: [id: number]
}>()

const SERIAL_PLACEHOLDER = '{سریال}'
const DATE_PLACEHOLDER = '{تاریخ}'

const editing = ref(false)
const actionLoading = ref(false)
const deleteOpen = ref(false)
const error = ref('')

const editTitle = ref('')
const editMessage = ref('')
const editCeiling = ref('')
const editDailyCap = ref(300)
const editDelaySeconds = ref(2)
const editEnabled = ref(true)

const recipientsOpen = ref(false)
const recipientsLoading = ref(false)
const recipientsError = ref('')
const recipients = ref<ClubAnnouncementSendItem[]>([])
const recipientsTotal = ref(0)
const recipientsPage = ref(1)
const recipientsLimit = 50

function insertPlaceholder(token: string) {
  const current = editMessage.value
  editMessage.value =
    current && !current.endsWith(' ') && current.length > 0
      ? `${current} ${token}`
      : `${current}${token}`
}

function syncEditFromAnnouncement(item: ClubAnnouncement) {
  editTitle.value = item.title
  editMessage.value = item.message
  editCeiling.value = item.ceilingDateJalali
  editDailyCap.value = item.dailyCap
  editDelaySeconds.value = item.delaySeconds
  editEnabled.value = item.enabled
}

watch(
  () => props.announcement,
  (item) => {
    if (!editing.value) syncEditFromAnnouncement(item)
  },
  { immediate: true },
)

const statusLabel = computed(() => {
  switch (props.announcement.status) {
    case 'running':
      return 'در حال ارسال'
    case 'completed':
      return 'تمام شد'
    case 'paused':
      return 'متوقف'
    default:
      return props.announcement.enabled ? 'در صف' : 'متوقف'
  }
})

const lastRunLabel = computed(() => {
  if (!props.announcement.lastRunAt) return 'هنوز اجرا نشده'
  const { date, time } = formatDateTimeFa(props.announcement.lastRunAt)
  return `${date} ${time}`
})

const progressPercent = computed(() => {
  const total = props.announcement.eligibleCount
  if (total <= 0) return 0
  return Math.min(100, Math.round((props.announcement.sentCount / total) * 100))
})

function startEdit() {
  syncEditFromAnnouncement(props.announcement)
  editing.value = true
  error.value = ''
}

function cancelEdit() {
  editing.value = false
  error.value = ''
}

function buildBody(): ClubAnnouncementUpsertRequest {
  return {
    title: editTitle.value.trim(),
    message: editMessage.value.trim(),
    ceilingDateJalali: editCeiling.value,
    dailyCap: Math.max(1, Math.min(10000, Number(editDailyCap.value) || 300)),
    delaySeconds: Math.max(0, Math.min(120, Number(editDelaySeconds.value) || 0)),
    enabled: editEnabled.value,
  }
}

async function saveEdit() {
  if (!editMessage.value.trim() || !editCeiling.value) {
    error.value = 'متن و تاریخ سقف الزامی است'
    return
  }
  actionLoading.value = true
  error.value = ''
  try {
    const updated = await updateAnnouncement(props.announcement.id, buildBody())
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
    const updated = await updateAnnouncement(props.announcement.id, {
      title: props.announcement.title,
      message: props.announcement.message,
      ceilingDateJalali: props.announcement.ceilingDateJalali,
      dailyCap: props.announcement.dailyCap,
      delaySeconds: props.announcement.delaySeconds,
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
    const updated = await runAnnouncementNow(props.announcement.id)
    emit('updated', updated)
    if (recipientsOpen.value) await loadRecipients(1)
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
    await deleteAnnouncement(props.announcement.id)
    deleteOpen.value = false
    emit('removed', props.announcement.id)
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
  await loadRecipients(1)
}

async function loadRecipients(page: number) {
  recipientsLoading.value = true
  recipientsError.value = ''
  recipientsPage.value = page
  try {
    const response = await fetchAnnouncementSends(
      props.announcement.id,
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

function formatSentAt(item: ClubAnnouncementSendItem): string {
  const { date, time } = formatDateTimeFa(item.sentAt)
  return `${date} ${time}`
}

function sendStatusLabel(status: string): string {
  if (status === 'failed') return 'ناموفق'
  return 'ارسال شد'
}
</script>

<template>
  <article class="club-rule-card" :class="{ disabled: !announcement.enabled }">
    <div class="club-rule-header">
      <div>
        <h3>{{ announcement.title || 'اطلاعیه' }}</h3>
        <p class="hint">
          سقف تاریخ: {{ announcement.ceilingDateJalali || '—' }} — روز جاری ارسال:
          {{ announcement.cursorDateJalali || '—' }} — سقف روزانه:
          {{ announcement.dailyCap.toLocaleString('fa-IR') }} شماره — تأخیر:
          {{ announcement.delaySeconds.toLocaleString('fa-IR') }} ثانیه
        </p>
        <p class="countdown" :class="announcement.status">
          وضعیت: {{ statusLabel }} — ارسال‌شده
          {{ announcement.sentCount.toLocaleString('fa-IR') }} از
          {{ announcement.eligibleCount.toLocaleString('fa-IR') }} واجد شرایط —
          باقی‌مانده {{ announcement.remainingCount.toLocaleString('fa-IR') }}
          (حدود {{ announcement.etaDays.toLocaleString('fa-IR') }} روز)
        </p>
        <div class="progress-wrap">
          <div class="progress-bar">
            <div class="progress-fill" :style="{ width: `${progressPercent}%` }" />
          </div>
          <span class="progress-label">
            {{ progressPercent.toLocaleString('fa-IR') }}٪ —
            موفق این اجرا: {{ announcement.lastSuccessCount.toLocaleString('fa-IR') }} /
            ناموفق: {{ announcement.lastFailCount.toLocaleString('fa-IR') }}
          </span>
        </div>
      </div>
      <UiSwitch
        :model-value="announcement.enabled"
        :disabled="actionLoading || editing || announcement.status === 'completed'"
        label="فعال"
        @update:model-value="toggleEnabled"
      />
    </div>

    <p class="message-preview">{{ announcement.message }}</p>
    <p class="hint">آخرین اجرا: {{ lastRunLabel }}</p>

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
        پارامترها:
        <button type="button" class="link-btn" @click="insertPlaceholder(SERIAL_PLACEHOLDER)">
          {{ SERIAL_PLACEHOLDER }}
        </button>
        <button type="button" class="link-btn" @click="insertPlaceholder(DATE_PLACEHOLDER)">
          {{ DATE_PLACEHOLDER }}
        </button>
        از آخرین ثبت همان شماره.
      </p>
      <div class="form-grid">
        <label class="full-width">
          تاریخ سقف (جلالی)
          <JalaliDateSelects v-model="editCeiling" />
        </label>
        <label>
          سقف روزانه
          <input v-model.number="editDailyCap" type="number" min="1" max="10000" />
        </label>
        <label>
          تأخیر بین پیام‌ها (ثانیه)
          <input v-model.number="editDelaySeconds" type="number" min="0" max="120" />
        </label>
      </div>
      <div class="actions">
        <button class="primary" :disabled="actionLoading" @click="saveEdit">ذخیره</button>
        <button class="secondary" :disabled="actionLoading" @click="cancelEdit">انصراف</button>
      </div>
    </div>

    <div v-else class="actions">
      <button class="secondary" :disabled="actionLoading" @click="startEdit">ویرایش</button>
      <button
        class="secondary"
        :disabled="actionLoading || announcement.status === 'completed'"
        @click="runNow"
      >
        اجرای فوری
      </button>
      <button class="secondary" :disabled="actionLoading" @click="toggleRecipients">
        {{ recipientsOpen ? 'بستن فهرست' : 'فهرست گیرندگان' }}
      </button>
      <button class="danger" :disabled="actionLoading" @click="deleteOpen = true">حذف</button>
    </div>

    <div v-if="recipientsOpen" class="recipients-panel">
      <p v-if="recipientsLoading" class="hint">در حال بارگذاری…</p>
      <p v-else-if="recipientsError" class="error">{{ recipientsError }}</p>
      <template v-else>
        <p v-if="recipients.length === 0" class="hint">هنوز گیرنده‌ای ثبت نشده است.</p>
        <div v-else class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>شماره</th>
                <th>سریال</th>
                <th>تاریخ شمسی ارسال</th>
                <th>زمان</th>
                <th>وضعیت</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in recipients" :key="item.id">
                <td>{{ formatPhoneForDisplay(item.phone) }}</td>
                <td>{{ item.serial || '—' }}</td>
                <td>{{ item.sentDateJalali || '—' }}</td>
                <td>{{ formatSentAt(item) }}</td>
                <td>{{ sendStatusLabel(item.status) }}</td>
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
      title="حذف اطلاعیه"
      :message="`آیا از حذف «${announcement.title || 'اطلاعیه'}» مطمئن هستید؟ دفتر ارسال هم پاک می‌شود.`"
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
  color: #1565c0;
}

.countdown.completed {
  color: #2e7d32;
}

.countdown.paused {
  color: #757575;
}

.countdown.running {
  color: #ef6c00;
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

.link-btn {
  padding: 0;
  border: none;
  background: none;
  color: #1565c0;
  font: inherit;
  cursor: pointer;
  text-decoration: underline;
}

.recipients-panel {
  margin-top: 0.75rem;
  padding-top: 0.75rem;
  border-top: 1px solid #e0e0e0;
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

.full-width {
  grid-column: 1 / -1;
}

.danger {
  background: #c62828;
  color: #fff;
  border: none;
}
</style>
