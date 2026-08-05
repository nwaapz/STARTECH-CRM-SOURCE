<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import NavBar from '@/components/NavBar.vue'
import {
  changeCrmUserPassword,
  createCrmUser,
  deleteCrmUser,
  fetchCloudSyncStatus,
  fetchCrmUsers,
  putCloudSyncConfig,
  runCloudSyncGet,
  runCloudSyncSend,
  type CloudSyncStatus,
  type CrmUser,
} from '@/api/client'
import { formatDateTimeFa } from '@/utils/formatCommunication'
import { formatRelativeTimeFa } from '@/utils/formatRelativeTimeFa'

const users = ref<CrmUser[]>([])
const sync = ref<CloudSyncStatus | null>(null)
const error = ref('')
const username = ref('')
const password = ref('')
const role = ref(4)
const syncUrl = ref('')
const syncToken = ref('')

const syncBusy = ref(false)
const syncMode = ref<'get' | 'send' | null>(null)
const syncSaving = ref(false)
const syncFeedback = ref('')
const syncFeedbackOk = ref(true)
const syncPercent = ref(0)
const syncDetail = ref('')

let progressPollTimer: ReturnType<typeof setInterval> | null = null

const syncPhaseLabel = computed(() => {
  switch (sync.value?.phase ?? (syncBusy.value ? 'push' : 'idle')) {
    case 'fetch':
      return 'در حال دریافت بافرها…'
    case 'push':
      return 'در حال ارسال به سرور…'
    case 'apply':
      return 'در حال اعمال تغییرات…'
    case 'conflicts':
      return 'تداخل روی گوشی — در اپ انتخاب کنید'
    case 'done':
      return 'همگام‌سازی تمام شد'
    case 'error':
      return 'خطا در همگام‌سازی'
    default:
      return syncBusy.value ? 'در حال همگام‌سازی…' : ''
  }
})

function formatSyncTime(ms: number | undefined): string {
  if (!ms || ms <= 0) return '—'
  const { date, time } = formatDateTimeFa(ms)
  return `${date} ${time} (${formatRelativeTimeFa(ms)})`
}

function translateSyncMessage(raw: string | null | undefined): string {
  if (!raw) return 'خطای نامشخص'
  const lower = raw.toLowerCase()
  if (lower.includes('url/token not configured') || lower.includes('not configured')) {
    return 'آدرس یا توکن تنظیم نشده — ابتدا ذخیره کنید'
  }
  if (lower.includes('push failed')) {
    return `خطا در ارسال به سرور: ${raw}`
  }
  if (lower.includes('pull failed')) {
    return `خطا در دریافت از سرور: ${raw}`
  }
  if (lower.startsWith('sync ok')) {
    return `همگام‌سازی موفق بود — ${raw}`
  }
  if (lower.includes('failed to connect') || lower.includes('unable to resolve') || lower.includes('timeout')) {
    return `اتصال به سرور برقرار نشد: ${raw}`
  }
  return raw
}

function stopProgressPoll() {
  if (progressPollTimer != null) {
    clearInterval(progressPollTimer)
    progressPollTimer = null
  }
}

async function pollSyncProgress() {
  try {
    const status = await fetchCloudSyncStatus()
    sync.value = status
    if (status.url) syncUrl.value = status.url
    if (typeof status.percent === 'number') {
      syncPercent.value = Math.max(syncPercent.value, status.percent)
    }
    if (status.detail) syncDetail.value = status.detail
  } catch {
    /* keep last known progress while request is in flight */
  }
}

function startProgressPoll() {
  stopProgressPoll()
  syncPercent.value = 5
  syncDetail.value = 'شروع همگام‌سازی…'
  void pollSyncProgress()
  progressPollTimer = setInterval(() => {
    void pollSyncProgress()
  }, 700)
}

async function load() {
  error.value = ''
  try {
    users.value = await fetchCrmUsers()
    sync.value = await fetchCloudSyncStatus()
    syncUrl.value = sync.value.url ?? ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا'
  }
}

async function addUser() {
  try {
    await createCrmUser({ username: username.value, password: password.value, role: role.value })
    username.value = ''
    password.value = ''
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا'
  }
}

async function removeUser(id: number) {
  if (!confirm('حذف کاربر؟')) return
  await deleteCrmUser(id)
  await load()
}

async function resetPassword(id: number) {
  const next = prompt('رمز جدید')
  if (!next || next.length < 4) return
  await changeCrmUserPassword(id, next)
}

async function saveSync() {
  syncFeedback.value = ''
  syncSaving.value = true
  try {
    if (!syncUrl.value.trim()) {
      syncFeedbackOk.value = false
      syncFeedback.value = 'آدرس پایه را وارد کنید'
      return
    }
    await putCloudSyncConfig({
      url: syncUrl.value.trim(),
      token: syncToken.value.trim() || undefined,
    })
    syncToken.value = ''
    await load()
    syncFeedbackOk.value = true
    syncFeedback.value = sync.value?.configured
      ? 'تنظیمات همگام‌سازی ذخیره شد'
      : 'آدرس ذخیره شد — توکن را هم وارد و ذخیره کنید'
  } catch (e) {
    syncFeedbackOk.value = false
    syncFeedback.value = e instanceof Error ? e.message : 'خطا در ذخیره'
  } finally {
    syncSaving.value = false
  }
}

async function runSync(mode: 'get' | 'send') {
  syncFeedback.value = ''
  syncBusy.value = true
  syncMode.value = mode
  syncPercent.value = 0
  startProgressPoll()
  try {
    if (!syncUrl.value.trim()) {
      syncFeedbackOk.value = false
      syncFeedback.value = 'آدرس پایه را وارد کنید'
      return
    }
    // Match Android: persist URL/token before syncing so typed values are used
    await putCloudSyncConfig({
      url: syncUrl.value.trim(),
      token: syncToken.value.trim() || undefined,
    })
    if (syncToken.value.trim()) syncToken.value = ''

    const result = mode === 'get' ? await runCloudSyncGet() : await runCloudSyncSend()
    syncPercent.value = result.ok ? 100 : syncPercent.value
    syncFeedbackOk.value = result.ok
    syncFeedback.value = translateSyncMessage(
      result.message ?? (result.ok ? 'Sync ok' : 'Sync failed'),
    )
    await load()
  } catch (e) {
    syncFeedbackOk.value = false
    syncFeedback.value = e instanceof Error ? e.message : 'خطا در همگام‌سازی'
  } finally {
    stopProgressPoll()
    syncBusy.value = false
    syncMode.value = null
    void pollSyncProgress()
  }
}

function roleLabel(r: number): string {
  if (r >= 5) return 'ابرمیر'
  if (r >= 4) return 'مدیر'
  return 'گزارش‌گیر'
}

onMounted(() => {
  void load()
})

onUnmounted(() => {
  stopProgressPoll()
})
</script>

<template>
  <div class="page">
    <NavBar />
    <main class="content">
      <h1>راه‌اندازی ادمین</h1>
      <p class="hint">مدیریت کاربران CRM، نقش‌ها، و همگام‌سازی با سرور آنلاین.</p>
      <p v-if="error" class="error">{{ error }}</p>

      <section class="card">
        <h2>کاربران</h2>
        <table>
          <thead>
            <tr><th>نام</th><th>نقش</th><th></th></tr>
          </thead>
          <tbody>
            <tr v-for="u in users" :key="u.id">
              <td>{{ u.username }}</td>
              <td>{{ roleLabel(u.role) }}</td>
              <td class="actions">
                <button type="button" class="secondary" @click="resetPassword(u.id)">رمز</button>
                <button type="button" class="danger" @click="removeUser(u.id)">حذف</button>
              </td>
            </tr>
          </tbody>
        </table>
        <div class="toolbar">
          <input v-model="username" placeholder="نام کاربری" />
          <input v-model="password" type="password" placeholder="رمز" />
          <select v-model.number="role">
            <option :value="1">گزارش‌گیر</option>
            <option :value="4">مدیر</option>
            <option :value="5">ابرمیر</option>
          </select>
          <button type="button" @click="addUser">افزودن</button>
        </div>
      </section>

      <section class="card">
        <h2>همگام‌سازی بافر (گوشی ↔ سرور)</h2>
        <p class="hint sync-hint">
          فقط تغییرات بافرشده همگام می‌شوند (ثبت‌نام، ویرایش گزارش، تولید سریال).
          ورود CSV همگام نمی‌شود. آدرس پایه همان پوشه‌ای است که
          <code>api</code> دارد. توکن باید با
          <code>PAYAMESH_SYNC_TOKEN</code> روی سرور یکی باشد. تداخل‌ها فقط روی گوشی حل می‌شوند.
          همگام‌سازی خودکار در پس‌زمینه وجود ندارد؛ هر بار بارگذاری این صفحه به‌صورت خودکار و بی‌صدا
          اطلاعات جدید را دریافت می‌کند، و برای ارسال تغییرات از دکمه «ارسال به سرور» استفاده کنید.
        </p>
        <p v-if="sync" class="sync-status-line">
          وضعیت:
          <span :class="sync.configured ? 'ok-text' : 'error'">
            {{ sync.configured ? 'پیکربندی شده' : 'پیکربندی نشده — آدرس و توکن را ذخیره کنید' }}
          </span>
          — بافر محلی در انتظار: {{ sync.pendingOutbox.toLocaleString('fa-IR') }}
          <template v-if="(sync.conflictCount ?? 0) > 0">
            — تداخل روی گوشی: {{ (sync.conflictCount ?? 0).toLocaleString('fa-IR') }}
          </template>
        </p>
        <p v-if="sync" class="sync-times">
          آخرین تبادل: {{ formatSyncTime(Math.max(sync.lastPushMs || 0, sync.lastPullMs || 0)) }}
        </p>

        <div v-if="syncBusy || (sync?.running && syncPercent > 0) || sync?.phase === 'conflicts'" class="progress-wrap sync-progress">
          <div class="progress-bar">
            <div
              class="progress-fill"
              :class="{ indeterminate: syncBusy && syncPercent < 8 }"
              :style="{ width: `${Math.max(syncPercent, syncBusy ? 8 : 0)}%` }"
            />
          </div>
          <span class="progress-label">
            {{ syncPhaseLabel }}
            <template v-if="syncPercent > 0">
              — {{ syncPercent.toLocaleString('fa-IR') }}٪
            </template>
            <template v-if="syncDetail"> — {{ syncDetail }}</template>
          </span>
        </div>

        <p
          v-if="syncFeedback"
          class="sync-feedback"
          :class="syncFeedbackOk ? 'success' : 'error'"
        >
          {{ syncFeedback }}
        </p>

        <div class="toolbar">
          <label class="grow">
            آدرس پایه (مثلاً https://example.com/crm)
            <input v-model="syncUrl" :disabled="syncBusy || syncSaving" />
          </label>
          <label class="grow">
            توکن دستگاه
            <input
              v-model="syncToken"
              type="password"
              placeholder="در صورت تغییر وارد کنید"
              :disabled="syncBusy || syncSaving"
            />
          </label>
          <button
            type="button"
            class="secondary"
            :disabled="syncBusy || syncSaving || !syncUrl.trim()"
            @click="saveSync"
          >
            {{ syncSaving ? 'در حال ذخیره…' : 'ذخیره' }}
          </button>
          <button
            type="button"
            :disabled="syncBusy || syncSaving || !syncUrl.trim()"
            @click="runSync('get')"
          >
            {{ syncBusy && syncMode === 'get' ? 'در حال دریافت…' : 'دریافت از سرور' }}
          </button>
          <button
            type="button"
            :disabled="syncBusy || syncSaving || !syncUrl.trim()"
            @click="runSync('send')"
          >
            {{ syncBusy && syncMode === 'send' ? 'در حال ارسال…' : 'ارسال به سرور' }}
          </button>
        </div>
      </section>
    </main>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-top: 0.75rem;
  align-items: end;
}
.grow {
  flex: 1 1 220px;
}
table {
  width: 100%;
  border-collapse: collapse;
}
th, td {
  text-align: right;
  padding: 0.4rem;
  border-bottom: 1px solid #e5e7eb;
}
.actions {
  display: flex;
  gap: 0.35rem;
}
.error {
  color: #b91c1c;
}
.ok-text {
  color: #047857;
  font-weight: 600;
}
.danger {
  background: #b91c1c;
  color: #fff;
}
.sync-hint {
  margin-bottom: 0.5rem;
}
.sync-status-line,
.sync-times {
  margin: 0.35rem 0;
  font-size: 0.95rem;
}
.sync-feedback {
  margin: 0.75rem 0 0;
  padding: 0.55rem 0.75rem;
  border-radius: 8px;
  background: #f8fafc;
}
.sync-feedback.success {
  background: #ecfdf5;
}
.sync-feedback.error {
  background: #fef2f2;
}
.sync-progress {
  margin-top: 0.85rem;
}
.progress-fill.indeterminate {
  animation: sync-pulse 1.1s ease-in-out infinite;
}
@keyframes sync-pulse {
  0%,
  100% {
    opacity: 0.55;
  }
  50% {
    opacity: 1;
  }
}
</style>
