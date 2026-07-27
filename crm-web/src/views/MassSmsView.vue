<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import NavBar from '@/components/NavBar.vue'
import MassSmsCampaignCard from '@/components/MassSmsCampaignCard.vue'
import JalaliDateTimePicker from '@/components/JalaliDateTimePicker.vue'
import type {
  IranProvince,
  MassSmsAudienceType,
  MassSmsCampaign,
  MassSmsChannel,
  SerialGroup,
} from '@/api/types'
import {
  createMassSmsCampaign,
  fetchMassSmsCampaigns,
  fetchMassSmsProvinces,
  fetchSerialGroups,
  previewMassSms,
  uploadMassSmsMedia,
} from '@/api/client'

const provinces = ref<IranProvince[]>([])
const serialGroups = ref<SerialGroup[]>([])
const groupsLoading = ref(false)
const provinceId = ref('')
const cityName = ref('')
const audienceType = ref<MassSmsAudienceType>('registered')
const allGroupsMode = ref(true)
const selectedGroupIds = ref<Set<number>>(new Set())
const allTimeMode = ref(true)
const registrationFromDate = ref('')
const registrationToDate = ref('')
const message = ref('')
const delaySeconds = ref(10)
const channel = ref<MassSmsChannel>('sms')
const mediaFile = ref<File | null>(null)
const mediaUploading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const excludePairedRubika = ref(false)
const excludePairedTelegram = ref(false)
const excludePairedBale = ref(false)
const recipientCount = ref<number | null>(null)
const campaigns = ref<MassSmsCampaign[]>([])
const loading = ref(false)
const countLoading = ref(false)
const listLoading = ref(false)
const initialLoaded = ref(false)
const error = ref('')
const success = ref('')
let pollTimer: ReturnType<typeof setInterval> | undefined
let countDebounceTimer: ReturnType<typeof setTimeout> | undefined
let countRequestId = 0

const cityOptions = computed(() => {
  if (!provinceId.value) return []
  return provinces.value.find((p) => p.id === provinceId.value)?.cities ?? []
})

const isBotChannel = computed(() => channel.value !== 'sms')

const channelHint = computed(() => {
  switch (channel.value) {
    case 'rubika':
      return 'فقط کاربرانی که ربات روبیکا را استارت کرده‌اند پیام دریافت می‌کنند؛ بقیه رد می‌شوند.'
    case 'telegram':
      return 'فقط کاربرانی که ربات تلگرام را استارت کرده‌اند پیام دریافت می‌کنند؛ بقیه رد می‌شوند.'
    case 'bale':
      return 'فقط کاربرانی که ربات بله را استارت کرده‌اند پیام دریافت می‌کنند؛ بقیه رد می‌شوند.'
    default:
      return ''
  }
})

const locationDisabled = computed(() => audienceType.value === 'error')
const groupFilterDisabled = computed(() => audienceType.value === 'error')
const dateFilterDisabled = computed(() => audienceType.value === 'error')

function warrantyLabel(months?: number | null, km?: number | null): string {
  const parts: string[] = []
  if (months != null && months > 0) parts.push(`${months} ماه`)
  if (km != null && km > 0) parts.push(`${km} کیلومتر`)
  return parts.length ? parts.join(' یا ') : 'بدون گارانتی'
}

const groupSelectionError = computed(() => {
  if (allGroupsMode.value || groupFilterDisabled.value) return null
  if (selectedGroupIds.value.size === 0) return 'حداقل یک گروه سریال انتخاب کنید'
  return null
})

const dateSelectionError = computed(() => {
  if (allTimeMode.value || dateFilterDisabled.value) return null
  if (!registrationFromDate.value && !registrationToDate.value) {
    return 'حداقل یک تاریخ ثبت‌نام انتخاب کنید'
  }
  if (
    registrationFromDate.value &&
    registrationToDate.value &&
    registrationFromDate.value > registrationToDate.value
  ) {
    return 'تاریخ شروع نمی‌تواند بعد از تاریخ پایان باشد'
  }
  return null
})

const filterValidationError = computed(
  () => groupSelectionError.value || dateSelectionError.value,
)

const dateRangeHint = computed(() => {
  if (allTimeMode.value || dateFilterDisabled.value) return 'همه زمان‌ها'
  if (registrationFromDate.value && registrationToDate.value) {
    return `${registrationFromDate.value} تا ${registrationToDate.value}`
  }
  if (registrationFromDate.value) return `از ${registrationFromDate.value}`
  if (registrationToDate.value) return `تا ${registrationToDate.value}`
  return 'بازه انتخاب نشده'
})

const recipientCountLabel = computed(() => {
  if (countLoading.value) return 'در حال محاسبه...'
  if (filterValidationError.value) return '—'
  if (recipientCount.value === null) return '—'
  return `${recipientCount.value.toLocaleString('fa-IR')} نفر`
})

function buildFilterBody() {
  return {
    audienceType: audienceType.value,
    provinceId: locationDisabled.value || !provinceId.value ? null : provinceId.value,
    cityName: locationDisabled.value || !cityName.value ? null : cityName.value,
    serialGroupIds:
      groupFilterDisabled.value || allGroupsMode.value
        ? null
        : Array.from(selectedGroupIds.value),
    registrationFromDate:
      dateFilterDisabled.value || allTimeMode.value
        ? null
        : registrationFromDate.value || null,
    registrationToDate:
      dateFilterDisabled.value || allTimeMode.value
        ? null
        : registrationToDate.value || null,
    excludePairedRubika: excludePairedRubika.value,
    excludePairedTelegram: excludePairedTelegram.value,
    excludePairedBale: excludePairedBale.value,
  }
}

function enableAllTimeMode() {
  allTimeMode.value = true
}

function enableDateRangeMode() {
  allTimeMode.value = false
}

function enableManualGroupSelection() {
  allGroupsMode.value = false
}

function enableAllGroupsMode() {
  allGroupsMode.value = true
}

function toggleGroup(id: number) {
  if (allGroupsMode.value || groupFilterDisabled.value) return
  const next = new Set(selectedGroupIds.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  selectedGroupIds.value = next
}

function selectAllGroups() {
  selectedGroupIds.value = new Set(serialGroups.value.map((g) => g.id))
}

function clearGroupSelection() {
  selectedGroupIds.value = new Set()
}

function isGroupSelected(id: number) {
  return allGroupsMode.value || selectedGroupIds.value.has(id)
}

function scheduleRecipientCountRefresh() {
  if (countDebounceTimer) clearTimeout(countDebounceTimer)
  countDebounceTimer = setTimeout(() => {
    void refreshRecipientCount()
  }, 350)
}

async function refreshRecipientCount() {
  if (filterValidationError.value) {
    recipientCount.value = 0
    countLoading.value = false
    return
  }

  const requestId = ++countRequestId
  countLoading.value = true
  try {
    const response = await previewMassSms(buildFilterBody())
    if (requestId !== countRequestId) return
    recipientCount.value = response.count
  } catch {
    if (requestId !== countRequestId) return
    recipientCount.value = null
  } finally {
    if (requestId === countRequestId) {
      countLoading.value = false
    }
  }
}

watch(audienceType, (value) => {
  if (value === 'error') {
    provinceId.value = ''
    cityName.value = ''
    allGroupsMode.value = true
    selectedGroupIds.value = new Set()
    allTimeMode.value = true
    registrationFromDate.value = ''
    registrationToDate.value = ''
  }
})

watch(
  [
    audienceType,
    provinceId,
    cityName,
    allGroupsMode,
    selectedGroupIds,
    allTimeMode,
    registrationFromDate,
    registrationToDate,
    excludePairedRubika,
    excludePairedTelegram,
    excludePairedBale,
  ],
  () => {
    scheduleRecipientCountRefresh()
  },
  { deep: true },
)

watch(provinceId, () => {
  cityName.value = ''
})

async function loadProvinces() {
  try {
    const response = await fetchMassSmsProvinces()
    provinces.value = response.items
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در بارگذاری استان‌ها'
  }
}

async function loadSerialGroups() {
  groupsLoading.value = true
  try {
    const response = await fetchSerialGroups()
    serialGroups.value = response.items
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در بارگذاری گروه‌های سریال'
  } finally {
    groupsLoading.value = false
  }
}

async function loadCampaigns(isPoll = false) {
  if (!isPoll) {
    listLoading.value = true
  }
  try {
    const response = await fetchMassSmsCampaigns()
    campaigns.value = response.items
  } catch {
    /* ignore polling errors */
  } finally {
    if (!isPoll) {
      listLoading.value = false
    }
    initialLoaded.value = true
  }
}

async function submit() {
  error.value = ''
  success.value = ''
  if (!message.value.trim()) {
    error.value = 'متن پیام الزامی است'
    return
  }
  if (delaySeconds.value < 1) {
    error.value = 'تأخیر باید حداقل ۱ ثانیه باشد'
    return
  }
  if (filterValidationError.value) {
    error.value = filterValidationError.value
    return
  }
  if (recipientCount.value === 0) {
    error.value = 'هیچ گیرنده‌ای با فیلتر انتخاب‌شده یافت نشد'
    return
  }
  loading.value = true
  try {
    let mediaAssetId: number | null = null
    if (isBotChannel.value && mediaFile.value) {
      mediaUploading.value = true
      try {
        const uploaded = await uploadMassSmsMedia(mediaFile.value)
        mediaAssetId = uploaded.assetId
      } finally {
        mediaUploading.value = false
      }
    }
    const created = await createMassSmsCampaign({
      ...buildFilterBody(),
      message: message.value.trim(),
      delaySeconds: delaySeconds.value,
      channel: channel.value,
      mediaAssetId,
    })
    success.value =
      created.status === 'queued'
        ? `کارت در صف قرار گرفت (نوبت ${created.queuePosition ?? '—'})`
        : 'ارسال انبوه شروع شد'
    message.value = ''
    clearMediaFile()
    await loadCampaigns()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در ایجاد ارسال'
  } finally {
    loading.value = false
  }
}

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  mediaFile.value = input.files?.[0] ?? null
}

function clearMediaFile() {
  mediaFile.value = null
  if (fileInput.value) fileInput.value.value = ''
}

watch(channel, (value) => {
  if (value === 'sms') clearMediaFile()
})

function onCampaignUpdated(updated: MassSmsCampaign) {
  const index = campaigns.value.findIndex((c) => c.id === updated.id)
  if (index >= 0) {
    campaigns.value[index] = updated
  }
}

function onCampaignRemoved(id: number) {
  campaigns.value = campaigns.value.filter((c) => c.id !== id)
}

onMounted(async () => {
  await Promise.all([loadProvinces(), loadSerialGroups(), loadCampaigns()])
  scheduleRecipientCountRefresh()
  pollTimer = setInterval(() => {
    void loadCampaigns(true)
  }, 1000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
  if (countDebounceTimer) clearTimeout(countDebounceTimer)
})
</script>

<template>
  <div>
    <NavBar />
    <div class="page">
      <div class="card">
        <h1>ارسال پیام انبوه</h1>
        <p class="hint">
          گیرندگان بر اساس فیلتر انتخاب می‌شوند. ارسال با تأخیر قابل تنظیم انجام می‌شود و
          کارت‌ها به‌صورت FIFO (یکی پس از دیگری) پردازش می‌شوند.
        </p>

        <div class="form-grid">
          <label>
            نوع مخاطب
            <select v-model="audienceType">
              <option value="registered">ثبت‌شده با سریال صحیح</option>
              <option value="error">مشتریان خطادار (بدون سریال صحیح)</option>
              <option value="all">همه مشتریان</option>
            </select>
          </label>

          <label>
            تأخیر بین پیام‌ها (ثانیه)
            <input v-model.number="delaySeconds" type="number" min="1" />
          </label>
        </div>

        <div class="form-grid">
          <label>
            کانال ارسال (تونل)
            <select v-model="channel">
              <option value="sms">پیامک (SMS)</option>
              <option value="rubika">ربات روبیکا</option>
              <option value="telegram">ربات تلگرام</option>
              <option value="bale">ربات بله</option>
            </select>
          </label>

          <label v-if="isBotChannel">
            فایل پیوست (اختیاری)
            <input ref="fileInput" type="file" @change="onFileChange" />
          </label>
        </div>

        <p v-if="isBotChannel" class="hint">{{ channelHint }}</p>
        <div v-if="isBotChannel && mediaFile" class="media-selected">
          <span>پیوست: {{ mediaFile.name }}</span>
          <button type="button" class="link-btn" @click="clearMediaFile">حذف</button>
        </div>
        <p v-if="isBotChannel" class="hint">
          فایل فقط یک‌بار آپلود می‌شود و برای همه گیرندگان با شناسه فایل مجدداً استفاده می‌شود.
        </p>

        <section class="group-filter-section" :class="{ disabled: groupFilterDisabled }">
          <div class="group-filter-header">
            <h2>فیلتر گروه سریال</h2>
            <div class="group-mode-actions">
              <button
                type="button"
                class="mode-chip"
                :class="{ active: allGroupsMode && !groupFilterDisabled }"
                :disabled="groupFilterDisabled"
                @click="enableAllGroupsMode"
              >
                همه گروه‌ها
              </button>
              <button
                type="button"
                class="mode-chip"
                :class="{ active: !allGroupsMode && !groupFilterDisabled }"
                :disabled="groupFilterDisabled"
                @click="enableManualGroupSelection"
              >
                انتخاب دستی
              </button>
            </div>
          </div>

          <p v-if="groupFilterDisabled" class="hint">
            برای «مشتریان خطادار» فیلتر گروه سریال اعمال نمی‌شود.
          </p>
          <p v-else-if="groupsLoading" class="hint">در حال بارگذاری گروه‌ها...</p>
          <p v-else-if="serialGroups.length === 0" class="hint">هنوز گروه سریالی ثبت نشده است.</p>
          <template v-else>
            <div v-if="!allGroupsMode" class="group-helper-actions">
              <button type="button" class="link-btn" @click="selectAllGroups">انتخاب همه</button>
              <button type="button" class="link-btn" @click="clearGroupSelection">پاک کردن</button>
            </div>
            <div class="group-chip-grid">
              <button
                v-for="group in serialGroups"
                :key="group.id"
                type="button"
                class="group-chip"
                :class="{ selected: isGroupSelected(group.id), readonly: allGroupsMode }"
                :disabled="groupFilterDisabled || allGroupsMode"
                @click="toggleGroup(group.id)"
              >
                <span class="group-chip-label">{{ group.rangeLabel }}</span>
                <span class="group-chip-warranty">{{
                  warrantyLabel(group.warrantyMonths, group.warrantyKm)
                }}</span>
                <span v-if="group.description" class="group-chip-desc">{{ group.description }}</span>
              </button>
            </div>
          </template>
        </section>

        <section class="group-filter-section" :class="{ disabled: dateFilterDisabled }">
          <div class="group-filter-header">
            <h2>فیلتر تاریخ ثبت‌نام</h2>
            <div class="group-mode-actions">
              <button
                type="button"
                class="mode-chip"
                :class="{ active: allTimeMode && !dateFilterDisabled }"
                :disabled="dateFilterDisabled"
                @click="enableAllTimeMode"
              >
                همه زمان‌ها
              </button>
              <button
                type="button"
                class="mode-chip"
                :class="{ active: !allTimeMode && !dateFilterDisabled }"
                :disabled="dateFilterDisabled"
                @click="enableDateRangeMode"
              >
                بازه زمانی
              </button>
            </div>
          </div>

          <p v-if="dateFilterDisabled" class="hint">
            برای «مشتریان خطادار» فیلتر تاریخ ثبت‌نام اعمال نمی‌شود.
          </p>
          <p v-else-if="allTimeMode" class="hint">
            همه مشتریانی که سریال ثبت کرده‌اند (بدون محدودیت زمانی).
          </p>
          <template v-else>
            <div class="date-range-grid">
              <label>
                از تاریخ
                <JalaliDateTimePicker
                  v-model="registrationFromDate"
                  placeholder="تاریخ شروع"
                  clearable
                />
              </label>
              <label>
                تا تاریخ
                <JalaliDateTimePicker
                  v-model="registrationToDate"
                  placeholder="تاریخ پایان"
                  clearable
                />
              </label>
            </div>
            <p class="hint">بازه انتخاب‌شده: {{ dateRangeHint }}</p>
          </template>
        </section>

        <div class="form-grid">
          <label :class="{ disabled: locationDisabled }">
            استان
            <select v-model="provinceId" :disabled="locationDisabled">
              <option value="">همه استان‌ها</option>
              <option v-for="p in provinces" :key="p.id" :value="p.id">
                {{ p.name }}
              </option>
            </select>
          </label>

          <label :class="{ disabled: locationDisabled || !provinceId }">
            شهر
            <select v-model="cityName" :disabled="locationDisabled || !provinceId">
              <option value="">همه شهرهای استان</option>
              <option v-for="city in cityOptions" :key="city" :value="city">
                {{ city }}
              </option>
            </select>
          </label>
        </div>

        <p v-if="locationDisabled" class="hint">
          برای «مشتریان خطادار» فیلتر استان/شهر اعمال نمی‌شود — همه شماره‌های خطادار شامل می‌شوند.
        </p>

        <section class="group-filter-section">
          <div class="group-filter-header">
            <h2>استثنا از ارسال</h2>
          </div>
          <p class="hint">
            با تیک زدن هر گزینه، پیام برای مشتریانی که آن ربات را به شماره خود وصل کرده‌اند
            <strong>ارسال نمی‌شود</strong>.
          </p>
          <div class="exclude-bot-grid">
            <label class="exclude-bot-option" :class="{ on: excludePairedRubika }">
              <input v-model="excludePairedRubika" type="checkbox" />
              <span>
                ارسال نشود به کسانی که
                <strong>روبیکا</strong>
                را وصل کرده‌اند
              </span>
            </label>
            <label class="exclude-bot-option" :class="{ on: excludePairedTelegram }">
              <input v-model="excludePairedTelegram" type="checkbox" />
              <span>
                ارسال نشود به کسانی که
                <strong>تلگرام</strong>
                را وصل کرده‌اند
              </span>
            </label>
            <label class="exclude-bot-option" :class="{ on: excludePairedBale }">
              <input v-model="excludePairedBale" type="checkbox" />
              <span>
                ارسال نشود به کسانی که
                <strong>بله</strong>
                را وصل کرده‌اند
              </span>
            </label>
          </div>
        </section>

        <label>
          متن پیام
          <textarea v-model="message" rows="4" placeholder="متن پیامک..." />
        </label>

        <div class="mass-sms-form-actions">
          <div class="recipient-count" :class="{ loading: countLoading }">
            <span class="recipient-count-label">گیرندگان:</span>
            <strong>{{ recipientCountLabel }}</strong>
          </div>
          <button
            type="button"
            class="primary"
            :disabled="loading || mediaUploading || countLoading || recipientCount === 0"
            @click="submit"
          >
            {{ mediaUploading ? 'در حال آپلود فایل...' : loading ? 'در حال ایجاد...' : 'شروع ارسال' }}
          </button>
        </div>

        <p v-if="filterValidationError" class="hint">{{ filterValidationError }}</p>
        <p v-if="error" class="error">{{ error }}</p>
        <p v-if="success" class="success">{{ success }}</p>
      </div>

      <section class="mass-sms-list">
        <h2>کارت‌های ارسال</h2>
        <p v-if="!initialLoaded && listLoading" class="hint">در حال بارگذاری...</p>
        <p v-else-if="initialLoaded && campaigns.length === 0" class="hint">
          هنوز ارسال انبوهی ثبت نشده است.
        </p>
        <div v-else class="mass-sms-cards">
          <MassSmsCampaignCard
            v-for="campaign in campaigns"
            :key="campaign.id"
            :campaign="campaign"
            @updated="onCampaignUpdated"
            @removed="onCampaignRemoved"
          />
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
label.disabled {
  opacity: 0.6;
}

select,
textarea {
  padding: 0.65rem 0.75rem;
  border: 1px solid #cfd8dc;
  border-radius: 8px;
  font: inherit;
  width: 100%;
}

textarea {
  resize: vertical;
  min-height: 5rem;
}

.group-filter-section {
  margin: 1rem 0;
  padding: 1rem;
  border: 1px solid #e0e0e0;
  border-radius: 10px;
  background: #fafafa;
}

.group-filter-section.disabled {
  opacity: 0.65;
}

.group-filter-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  margin-bottom: 0.75rem;
}

.group-filter-header h2 {
  margin: 0;
  font-size: 1rem;
}

.group-mode-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.mode-chip {
  padding: 0.4rem 0.85rem;
  border: 1px solid #b0bec5;
  border-radius: 999px;
  background: #fff;
  font: inherit;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s, color 0.15s;
}

.mode-chip.active {
  background: #1565c0;
  border-color: #1565c0;
  color: #fff;
}

.mode-chip:disabled {
  cursor: not-allowed;
}

.group-helper-actions {
  display: flex;
  gap: 1rem;
  margin-bottom: 0.65rem;
}

.media-selected {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin: 0.35rem 0;
  font-size: 0.9rem;
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
  max-height: 160px;
  overflow-y: auto;
  padding: 0.15rem;
}

.group-chip {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.15rem;
  padding: 0.45rem 0.75rem;
  border: 1px solid #cfd8dc;
  border-radius: 999px;
  background: #fff;
  font: inherit;
  cursor: pointer;
  text-align: right;
  transition: background 0.15s, border-color 0.15s;
}

.group-chip.selected {
  background: #e3f2fd;
  border-color: #1565c0;
}

.group-chip.readonly {
  cursor: default;
  opacity: 0.85;
}

.group-chip:disabled {
  cursor: not-allowed;
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
  font-size: 0.78rem;
  color: #607d8b;
  max-width: 14rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.date-range-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
  gap: 0.75rem;
}

.exclude-bot-grid {
  display: flex;
  flex-direction: column;
  gap: 0.55rem;
}

.exclude-bot-option {
  display: flex;
  align-items: flex-start;
  gap: 0.55rem;
  padding: 0.65rem 0.85rem;
  border: 1px solid #cfd8dc;
  border-radius: 10px;
  background: #fff;
  font-size: 0.95rem;
  line-height: 1.45;
  cursor: pointer;
  user-select: none;
  transition: background 0.15s, border-color 0.15s;
}

.exclude-bot-option.on {
  background: #fff3e0;
  border-color: #ef6c00;
}

.exclude-bot-option input {
  width: 1.05rem;
  height: 1.05rem;
  margin-top: 0.15rem;
  flex-shrink: 0;
  accent-color: #ef6c00;
}

.mass-sms-form-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.75rem;
  margin-top: 1rem;
}

.recipient-count {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.55rem 0.9rem;
  background: #e8f5e9;
  border-radius: 8px;
  min-height: 2.5rem;
}

.recipient-count.loading {
  opacity: 0.75;
}

.recipient-count-label {
  color: #546e7a;
  font-size: 0.9rem;
}

.mass-sms-list {
  margin-top: 1.5rem;
}

.mass-sms-cards {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
</style>
