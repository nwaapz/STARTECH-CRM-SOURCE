<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import NavBar from '@/components/NavBar.vue'
import AnnouncementCard from '@/components/AnnouncementCard.vue'
import ClubAutoMessageCard from '@/components/ClubAutoMessageCard.vue'
import JalaliDateSelects from '@/components/JalaliDateSelects.vue'
import UiSwitch from '@/components/UiSwitch.vue'
import {
  createAnnouncement,
  createClubAutoMessage,
  fetchAnnouncements,
  fetchClubAutoMessages,
  fetchClubWelcomeSettings,
  fetchSerialGroups,
  fetchSerials,
  updateClubWelcomeSettings,
} from '@/api/client'
import type { ClubAnnouncement, ClubWelcomeSettings } from '@/api/types'
import type { ClubAutoMessageRule, SerialGroup, SerialRow } from '@/api/types'
import { formatPhoneForDisplay } from '@/utils/formatPhone'
import { todayJalaliDate } from '@/utils/jalali'

type ClubTab = 'customers' | 'announcements' | 'welcome' | 'auto'

interface CustomerRow {
  phone: string
  registrationCount: number
  latestDate: string | undefined
  latestCity: string | undefined
  latestKm: number | undefined
  pairedRubika: boolean
  pairedTelegram: boolean
  pairedBale: boolean
}

function warrantyLabel(months?: number | null, km?: number | null): string {
  const parts: string[] = []
  if (months != null && months > 0) parts.push(`${months} ماه`)
  if (km != null && km > 0) parts.push(`${km} کیلومتر`)
  return parts.length ? parts.join(' یا ') : 'بدون گارانتی'
}

const clubTab = ref<ClubTab>('customers')

const searchQuery = ref('')
const page = ref(1)
const limit = ref(50)
const total = ref(0)
const serials = ref<SerialRow[]>([])
const loading = ref(false)
const error = ref('')

const rules = ref<ClubAutoMessageRule[]>([])
const rulesLoading = ref(false)
const rulesError = ref('')
const createBusy = ref(false)
const createError = ref('')
const createSuccess = ref('')

const serialGroups = ref<SerialGroup[]>([])
const groupsLoading = ref(false)
const selectedGroupIds = ref<Set<number>>(new Set())

const SERIAL_PLACEHOLDER = '{سریال}'
const KM_PLACEHOLDER = '{کیلومتر}'
const DATE_PLACEHOLDER = '{تاریخ}'

const title = ref('')
const message = ref('')
const offsetMonths = ref(3)
const offsetDays = ref(0)
const sendHour = ref(10)
const sendMinute = ref(0)
const delaySeconds = ref(10)
const dailyCap = ref(200)
const sendSms = ref(true)
const sendRubika = ref(false)
const sendTelegram = ref(false)
const sendBale = ref(false)
const enabled = ref(true)

const announcements = ref<ClubAnnouncement[]>([])
const announcementsLoading = ref(false)
const announcementsError = ref('')
const annCreateBusy = ref(false)
const annCreateError = ref('')
const annCreateSuccess = ref('')
const annTitle = ref('')
const annMessage = ref('')
const annCeiling = ref(todayJalaliDate())
const annDailyCap = ref(300)
const annDelaySeconds = ref(2)
const annEnabled = ref(true)

const welcomeSettings = ref<ClubWelcomeSettings>({ enabled: false, message: '' })
const welcomeLoading = ref(false)
const welcomeSaving = ref(false)
const welcomeError = ref('')
const welcomeSuccess = ref('')

const customers = computed<CustomerRow[]>(() => {
  const map = new Map<string, CustomerRow>()
  for (const row of serials.value) {
    if (!row.phone) continue
    const existing = map.get(row.phone)
    if (existing) {
      existing.registrationCount += 1
      existing.pairedRubika = existing.pairedRubika || !!row.pairedRubika
      existing.pairedTelegram = existing.pairedTelegram || !!row.pairedTelegram
      existing.pairedBale = existing.pairedBale || !!row.pairedBale
      continue
    }
    map.set(row.phone, {
      phone: row.phone,
      registrationCount: 1,
      latestDate: row.date,
      latestCity: row.city,
      latestKm: row.km,
      pairedRubika: !!row.pairedRubika,
      pairedTelegram: !!row.pairedTelegram,
      pairedBale: !!row.pairedBale,
    })
  }
  return Array.from(map.values())
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const response = await fetchSerials({
      registered: true,
      search: searchQuery.value || undefined,
      page: page.value,
      limit: limit.value,
    })
    serials.value = response.items
    total.value = response.total
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا'
  } finally {
    loading.value = false
  }
}

async function loadWelcomeSettings() {
  welcomeLoading.value = true
  welcomeError.value = ''
  try {
    welcomeSettings.value = await fetchClubWelcomeSettings()
  } catch (e) {
    welcomeError.value = e instanceof Error ? e.message : 'خطا در بارگذاری پیام خوش‌آمد'
  } finally {
    welcomeLoading.value = false
  }
}

async function saveWelcomeSettings() {
  welcomeSaving.value = true
  welcomeError.value = ''
  welcomeSuccess.value = ''
  try {
    welcomeSettings.value = await updateClubWelcomeSettings({
      enabled: welcomeSettings.value.enabled,
      message: welcomeSettings.value.message.trim(),
    })
    welcomeSuccess.value = 'تنظیمات پیام خوش‌آمد ذخیره شد.'
  } catch (e) {
    welcomeError.value = e instanceof Error ? e.message : 'خطا در ذخیره'
  } finally {
    welcomeSaving.value = false
  }
}

async function loadAnnouncements(silent = false) {
  if (!silent) {
    announcementsLoading.value = true
  }
  announcementsError.value = ''
  try {
    const response = await fetchAnnouncements()
    announcements.value = response.items
  } catch (e) {
    if (!silent) {
      announcementsError.value = e instanceof Error ? e.message : 'خطا در بارگذاری اطلاعیه‌ها'
    }
  } finally {
    if (!silent) {
      announcementsLoading.value = false
    }
  }
}

function insertAnnPlaceholder(token: string) {
  const current = annMessage.value
  annMessage.value =
    current.length > 0 && !current.endsWith(' ')
      ? `${current} ${token}`
      : `${current}${token}`
}

async function createAnnouncementRule() {
  annCreateBusy.value = true
  annCreateError.value = ''
  annCreateSuccess.value = ''
  if (!annMessage.value.trim() || !annCeiling.value) {
    annCreateError.value = 'متن و تاریخ سقف الزامی است'
    annCreateBusy.value = false
    return
  }
  try {
    const created = await createAnnouncement({
      title: annTitle.value.trim(),
      message: annMessage.value.trim(),
      ceilingDateJalali: annCeiling.value,
      dailyCap: Math.max(1, Math.min(10000, Number(annDailyCap.value) || 300)),
      delaySeconds: Math.max(0, Math.min(120, Number(annDelaySeconds.value) || 0)),
      enabled: annEnabled.value,
    })
    announcements.value = [created, ...announcements.value]
    annCreateSuccess.value = 'اطلاعیه ایجاد شد'
    annMessage.value = ''
    annTitle.value = ''
    scheduleRulesPoll()
  } catch (e) {
    annCreateError.value = e instanceof Error ? e.message : 'خطا در ایجاد'
  } finally {
    annCreateBusy.value = false
  }
}

function onAnnouncementUpdated(item: ClubAnnouncement) {
  announcements.value = announcements.value.map((row) => (row.id === item.id ? item : row))
  scheduleRulesPoll()
}

function onAnnouncementRemoved(id: number) {
  announcements.value = announcements.value.filter((row) => row.id !== id)
  scheduleRulesPoll()
}

async function loadRules(silent = false) {
  if (!silent) {
    rulesLoading.value = true
  }
  rulesError.value = ''
  try {
    const response = await fetchClubAutoMessages()
    rules.value = response.items
  } catch (e) {
    if (!silent) {
      rulesError.value = e instanceof Error ? e.message : 'خطا در بارگذاری پیام‌های خودکار'
    }
  } finally {
    if (!silent) {
      rulesLoading.value = false
    }
  }
}

async function loadSerialGroups() {
  groupsLoading.value = true
  try {
    const response = await fetchSerialGroups()
    serialGroups.value = response.items
  } catch {
    serialGroups.value = []
  } finally {
    groupsLoading.value = false
  }
}

function isGroupSelected(id: number): boolean {
  return selectedGroupIds.value.has(id)
}

function toggleGroup(id: number) {
  const next = new Set(selectedGroupIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selectedGroupIds.value = next
}

function selectAllGroups() {
  selectedGroupIds.value = new Set(serialGroups.value.map((g) => g.id))
}

function clearGroupSelection() {
  selectedGroupIds.value = new Set()
}

function insertPlaceholder(token: string) {
  const current = message.value
  message.value =
    current.length > 0 && !current.endsWith(' ')
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

async function runSearch() {
  page.value = 1
  await load()
}

function prevPage() {
  if (page.value > 1) {
    page.value -= 1
    void load()
  }
}

function nextPage() {
  if (page.value * limit.value < total.value) {
    page.value += 1
    void load()
  }
}

async function createRule() {
  createBusy.value = true
  createError.value = ''
  createSuccess.value = ''
  if (selectedGroupIds.value.size === 0) {
    createError.value = 'حداقل یک گروه سریال انتخاب کنید'
    createBusy.value = false
    return
  }
  try {
    const created = await createClubAutoMessage({
      title: title.value.trim(),
      message: message.value.trim(),
      offsetMonths: Number(offsetMonths.value) || 0,
      offsetDays: Number(offsetDays.value) || 0,
      sendHour: Number(sendHour.value) || 0,
      sendMinute: Number(sendMinute.value) || 0,
      delaySeconds: Math.max(1, Number(delaySeconds.value) || 10),
      dailyCap: Math.max(1, Math.min(10000, Number(dailyCap.value) || 200)),
      sendSms: sendSms.value,
      sendRubika: sendRubika.value,
      sendTelegram: sendTelegram.value,
      sendBale: sendBale.value,
      serialGroupIds: Array.from(selectedGroupIds.value),
      enabled: enabled.value,
    })
    rules.value = [created, ...rules.value]
    createSuccess.value = 'پیام خودکار ایجاد شد'
    message.value = ''
    title.value = ''
    scheduleRulesPoll()
  } catch (e) {
    createError.value = e instanceof Error ? e.message : 'خطا در ایجاد'
  } finally {
    createBusy.value = false
  }
}

function onRuleUpdated(rule: ClubAutoMessageRule) {
  rules.value = rules.value.map((item) => (item.id === rule.id ? rule : item))
  scheduleRulesPoll()
}

function onRuleRemoved(id: number) {
  rules.value = rules.value.filter((item) => item.id !== id)
  scheduleRulesPoll()
}

function isRuleDueOrRunning(rule: ClubAutoMessageRule): boolean {
  if (!rule.enabled) return false
  if (rule.runStatus === 'running') return true
  if (rule.lastRunDateJalali === todayJalaliDate()) return false
  const now = new Date()
  const nowMinutes = now.getHours() * 60 + now.getMinutes()
  return nowMinutes >= rule.sendHour * 60 + rule.sendMinute
}

function needsFastRulesPoll(): boolean {
  return (
    rules.value.some(isRuleDueOrRunning) ||
    announcements.value.some((item) => item.enabled && item.status === 'running')
  )
}

/** Keep last-run / progress fresh; poll faster while due or sending. */
let rulesPollTimer: ReturnType<typeof setInterval> | undefined

function scheduleRulesPoll() {
  if (rulesPollTimer) clearInterval(rulesPollTimer)
  const intervalMs = needsFastRulesPoll() ? 1_000 : 15_000
  rulesPollTimer = setInterval(() => {
    void Promise.all([loadRules(true), loadAnnouncements(true)]).then(() => {
      const nextMs = needsFastRulesPoll() ? 1_000 : 15_000
      if (intervalMs !== nextMs) scheduleRulesPoll()
    })
  }, intervalMs)
}

onMounted(() => {
  void load()
  void loadWelcomeSettings()
  void loadAnnouncements()
  void loadRules().then(() => scheduleRulesPoll())
  void loadSerialGroups()
})

onUnmounted(() => {
  if (rulesPollTimer) clearInterval(rulesPollTimer)
})
</script>

<template>
  <div>
    <NavBar />
    <div class="page">
      <div class="card">
        <h1>باشگاه مشتریان</h1>
        <div class="generation-tabs">
          <button
            type="button"
            :class="['tab', { active: clubTab === 'customers' }]"
            @click="clubTab = 'customers'"
          >
            مشتریان
          </button>
          <button
            type="button"
            :class="['tab', { active: clubTab === 'announcements' }]"
            @click="clubTab = 'announcements'"
          >
            اطلاعیه‌ها
          </button>
          <button
            type="button"
            :class="['tab', { active: clubTab === 'welcome' }]"
            @click="clubTab = 'welcome'"
          >
            خوش‌آمد
          </button>
          <button
            type="button"
            :class="['tab', { active: clubTab === 'auto' }]"
            @click="clubTab = 'auto'"
          >
            پیام‌های خودکار
          </button>
        </div>
        <template v-if="clubTab === 'customers'">
        <p class="hint">
          فهرست مشتریان بر اساس شماره تلفن، از سریال‌های ثبت‌شده گردآوری می‌شود. ادغام بر اساس شماره
          در صفحه فعلی انجام می‌شود.
        </p>
        <div class="form-grid">
          <label>
            جستجو (سریال / تلفن)
            <input
              v-model="searchQuery"
              type="text"
              placeholder="مثال: M100 یا 0913"
              @keyup.enter="runSearch"
            />
          </label>
        </div>
        <div class="actions">
          <button class="primary" :disabled="loading" @click="runSearch">جستجو</button>
        </div>
        <p v-if="error" class="error">{{ error }}</p>
        <p class="hint">
          نتایج: {{ total.toLocaleString('fa-IR') }} — صفحه {{ page.toLocaleString('fa-IR') }}
        </p>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>تلفن</th>
                <th>ربات</th>
                <th>تعداد ثبت</th>
                <th>آخرین تاریخ</th>
                <th>شهر</th>
                <th>آخرین کیلومتر</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="loading && customers.length === 0">
                <td colspan="6" class="empty">در حال بارگذاری…</td>
              </tr>
              <tr v-else-if="customers.length === 0">
                <td colspan="6" class="empty">مشتری‌ای یافت نشد.</td>
              </tr>
              <tr v-for="customer in customers" :key="customer.phone">
                <td>{{ formatPhoneForDisplay(customer.phone) }}</td>
                <td>
                  <span v-if="customer.pairedRubika || customer.pairedTelegram || customer.pairedBale" class="bot-badges">
                    <span v-if="customer.pairedRubika" class="bot-badge rubika">روبیکا</span>
                    <span v-if="customer.pairedTelegram" class="bot-badge telegram">تلگرام</span>
                    <span v-if="customer.pairedBale" class="bot-badge bale">بله</span>
                  </span>
                  <span v-else class="muted">—</span>
                </td>
                <td>{{ customer.registrationCount.toLocaleString('fa-IR') }}</td>
                <td>{{ customer.latestDate ?? '—' }}</td>
                <td>{{ customer.latestCity ?? '—' }}</td>
                <td>{{ customer.latestKm ?? '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="actions">
          <button class="secondary" :disabled="page <= 1" @click="prevPage">قبلی</button>
          <button class="secondary" :disabled="page * limit >= total" @click="nextPage">
            بعدی
          </button>
        </div>
        </template>

        <template v-if="clubTab === 'announcements'">
          <h2>اطلاعیه‌ها</h2>
          <p class="hint">
            پیامک به شماره‌های ثبت‌شده، از تاریخ سقف جلالی به عقب. هر شماره در هر اطلاعیه فقط یک‌بار
            پیامک می‌گیرد. سقف روزانه (مثلاً ۳۰۰) روی سرور با کرون باشگاه مشتریان ارسال می‌شود.
          </p>
          <div class="create-form">
            <label>
              عنوان (اختیاری)
              <input v-model="annTitle" type="text" placeholder="مثال: اطلاعیه طرح تابستان" />
            </label>
            <label>
              متن پیام
              <textarea
                v-model="annMessage"
                rows="3"
                placeholder="متن پیامک اطلاعیه"
              />
            </label>
            <p class="hint placeholder-hint">
              پارامترها از آخرین ثبت همان شماره:
              <button type="button" class="link-btn" @click="insertAnnPlaceholder(SERIAL_PLACEHOLDER)">
                {{ SERIAL_PLACEHOLDER }}
              </button>
              <button type="button" class="link-btn" @click="insertAnnPlaceholder(DATE_PLACEHOLDER)">
                {{ DATE_PLACEHOLDER }}
              </button>
            </p>
            <div class="form-grid">
              <label class="full-width">
                تاریخ سقف (جلالی)
                <JalaliDateSelects v-model="annCeiling" />
              </label>
              <label>
                سقف ارسال روزانه
                <input v-model.number="annDailyCap" type="number" min="1" max="10000" />
              </label>
              <label>
                تأخیر بین پیام‌ها (ثانیه)
                <input v-model.number="annDelaySeconds" type="number" min="0" max="120" />
              </label>
            </div>
            <div class="source-filter-toggles">
              <UiSwitch v-model="annEnabled" label="فعال پس از ایجاد" />
            </div>
            <div class="actions">
              <button
                class="primary"
                :disabled="annCreateBusy || !annMessage.trim() || !annCeiling"
                @click="createAnnouncementRule"
              >
                ایجاد اطلاعیه
              </button>
            </div>
            <p v-if="annCreateError" class="error">{{ annCreateError }}</p>
            <p v-if="annCreateSuccess" class="hint success">{{ annCreateSuccess }}</p>
          </div>
          <p v-if="announcementsLoading" class="hint">در حال بارگذاری اطلاعیه‌ها…</p>
          <p v-else-if="announcementsError" class="error">{{ announcementsError }}</p>
          <p v-else-if="announcements.length === 0" class="hint">هنوز اطلاعیه‌ای تعریف نشده است.</p>
          <AnnouncementCard
            v-for="item in announcements"
            :key="item.id"
            :announcement="item"
            @updated="onAnnouncementUpdated"
            @removed="onAnnouncementRemoved"
          />
        </template>

        <template v-if="clubTab === 'welcome'">
        <h2>پیام خوش‌آمد پس از ثبت</h2>
        <p class="hint">
          پس از ثبت موفق سریال (پیامک یا ربات)، علاوه بر پیام تأیید گارانتی، یک پیامک دوم با متن
          زیر برای مشتری ارسال می‌شود. روی سرور از پنل پیامک؛ روی گیت‌وی از سیم‌کارت یا ملی‌پیامک
          (مطابق تنظیمات ارسال).
        </p>
        <p v-if="welcomeLoading" class="hint">در حال بارگذاری…</p>
        <template v-else>
          <div class="source-filter-toggles">
            <UiSwitch v-model="welcomeSettings.enabled" label="ارسال پیام خوش‌آمد فعال" />
          </div>
          <label>
            متن پیام
            <textarea
              v-model="welcomeSettings.message"
              rows="4"
              placeholder="مثال: به خانواده ما خوش آمدید!"
              :disabled="!welcomeSettings.enabled"
            />
          </label>
          <p class="hint placeholder-hint">
            پارامترها:
            <code>{{ SERIAL_PLACEHOLDER }}</code>
            <code>{{ KM_PLACEHOLDER }}</code>
            <code>{{ DATE_PLACEHOLDER }}</code>
          </p>
          <div class="actions">
            <button class="primary" :disabled="welcomeSaving" @click="saveWelcomeSettings">
              ذخیره پیام خوش‌آمد
            </button>
          </div>
          <p v-if="welcomeError" class="error">{{ welcomeError }}</p>
          <p v-if="welcomeSuccess" class="hint success">{{ welcomeSuccess }}</p>
        </template>
        </template>

        <template v-if="clubTab === 'auto'">
        <h2>پیام‌های خودکار</h2>
        <p class="hint">
          قوانین را اینجا بسازید. اپلیکیشن گیت‌وی هر روز در ساعت تنظیم‌شده (بدون نیاز به باز بودن
          داشبورد) پیام را می‌فرستد. سرویس گیت‌وی باید روشن باشد.
        </p>
        <ul class="hint explain-list">
          <li>
            اگر فاصله را مثلاً ۶ ماه بگذارید، مشتریانی که حداقل ۶ ماه از ثبت‌نامشان گذشته و هنوز این
            پیام را نگرفته‌اند، هدف قرار می‌گیرند (اگر گیت‌وی یک روز خاموش بوده باشد، روز بعد جبران
            می‌شود).
          </li>
          <li>
            هر شماره برای هر قانون و هر کانال (پیامک / روبیکا / تلگرام / بله) فقط یک‌بار پیام می‌گیرد؛
            فهرست گیرندگان روی کارت هر قانون به‌صورت دائمی ذخیره می‌شود تا دوباره ارسال نشود.
          </li>
          <li>فقط گروه‌های سریال انتخاب‌شده در نظر گرفته می‌شوند.</li>
        </ul>

        <div class="create-form">
          <label>
            عنوان (اختیاری)
            <input v-model="title" type="text" placeholder="مثال: یادآوری ۳ ماهه" />
          </label>
          <label>
            متن پیام
            <textarea
              v-model="message"
              rows="3"
              placeholder="متن پیام ارسالی به مشتری"
            />
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
              <input v-model.number="offsetMonths" type="number" min="0" />
            </label>
            <label>
              روز قبل
              <input v-model.number="offsetDays" type="number" min="0" />
            </label>
            <label>
              ساعت ارسال روزانه
              <input v-model.number="sendHour" type="number" min="0" max="23" />
            </label>
            <label>
              دقیقه
              <input v-model.number="sendMinute" type="number" min="0" max="59" />
            </label>
            <label>
              تأخیر بین پیام‌ها (ثانیه)
              <input v-model.number="delaySeconds" type="number" min="1" />
            </label>
            <label>
              سقف ارسال روزانه (سریال)
              <input v-model.number="dailyCap" type="number" min="1" max="10000" />
            </label>
          </div>
          <p class="hint">
            هر سریال در هر قانون فقط یک‌بار آلارم می‌شود. اگر تعداد واجد شرایط زیاد باشد، فقط تا سقف روزانه
            ارسال می‌شود و بقیه روز بعد.
          </p>
          <section class="group-filter-section">
            <div class="group-filter-header">
              <h3>گروه سریال</h3>
              <div class="group-helper-actions">
                <button type="button" class="link-btn" @click="selectAllGroups">انتخاب همه</button>
                <button type="button" class="link-btn" @click="clearGroupSelection">پاک کردن</button>
              </div>
            </div>
            <p class="hint">هر دکمه نام گروه و مدت گارانتی آن را نشان می‌دهد.</p>
            <p v-if="groupsLoading" class="hint">در حال بارگذاری گروه‌ها...</p>
            <p v-else-if="serialGroups.length === 0" class="hint">هنوز گروه سریالی ثبت نشده است.</p>
            <div v-else class="group-chip-grid">
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
                <span v-if="group.description" class="group-chip-desc">{{ group.description }}</span>
              </button>
            </div>
          </section>
          <div class="source-filters">
            <p class="hint source-filters-label">کانال ارسال</p>
            <div class="source-filter-toggles">
              <UiSwitch v-model="sendSms" label="پیامک" />
              <UiSwitch v-model="sendRubika" label="روبیکا" />
              <UiSwitch v-model="sendTelegram" label="تلگرام" />
              <UiSwitch v-model="sendBale" label="بله" />
            </div>
          </div>
          <div class="source-filter-toggles">
            <UiSwitch v-model="enabled" label="فعال پس از ایجاد" />
          </div>
          <div class="actions">
            <button class="primary" :disabled="createBusy || !message.trim()" @click="createRule">
              ایجاد پیام خودکار
            </button>
          </div>
          <p v-if="createError" class="error">{{ createError }}</p>
          <p v-if="createSuccess" class="hint success">{{ createSuccess }}</p>
        </div>

        <p v-if="rulesLoading" class="hint">در حال بارگذاری قوانین…</p>
        <p v-else-if="rulesError" class="error">{{ rulesError }}</p>
        <p v-else-if="rules.length === 0" class="hint">هنوز پیام خودکاری تعریف نشده است.</p>
        <ClubAutoMessageCard
          v-for="rule in rules"
          :key="rule.id"
          :rule="rule"
          :serial-groups="serialGroups"
          @updated="onRuleUpdated"
          @removed="onRuleRemoved"
        />
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.bot-badges {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 0.35rem;
}

.bot-badge {
  display: inline-block;
  padding: 0.15rem 0.5rem;
  border-radius: 999px;
  font-size: 0.78rem;
  font-weight: 600;
  line-height: 1.4;
  white-space: nowrap;
}

.bot-badge.rubika {
  background: #e8f5e9;
  color: #2e7d32;
}

.bot-badge.bale {
  background: #e8f5e9;
  color: #1b5e20;
}
.bot-badge.telegram {
  background: #e3f2fd;
  color: #1565c0;
}

.muted {
  color: #9e9e9e;
}

.create-form {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  margin: 1rem 0;
}

.create-form textarea {
  resize: vertical;
  min-height: 4.5rem;
}

.success {
  color: #2e7d32;
}

h2 {
  margin: 0 0 0.5rem;
  font-size: 1.2rem;
}

.group-filter-section {
  margin: 0.25rem 0;
  padding: 1rem;
  border: 1px solid #e0e0e0;
  border-radius: 10px;
  background: #fafafa;
}

.group-filter-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  margin-bottom: 0.5rem;
}

.group-filter-header h3 {
  margin: 0;
  font-size: 1rem;
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
  max-height: 200px;
  overflow-y: auto;
  padding: 0.15rem;
}

.group-chip {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.15rem;
  padding: 0.5rem 0.85rem;
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

.group-chip-desc {
  font-size: 0.75rem;
  color: #607d8b;
}

.explain-list {
  margin: 0.5rem 0 1rem;
  padding-inline-start: 1.25rem;
  line-height: 1.7;
}
</style>
