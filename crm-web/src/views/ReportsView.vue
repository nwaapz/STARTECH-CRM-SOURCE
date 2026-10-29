<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import NavBar from '@/components/NavBar.vue'
import SerialTable from '@/components/SerialTable.vue'
import SerialRowEditModal from '@/components/SerialRowEditModal.vue'
import JalaliDateSelects from '@/components/JalaliDateSelects.vue'
import UiSwitch from '@/components/UiSwitch.vue'
import { fetchMassSmsProvinces, fetchSerialGroups, fetchSerials, repairNullDates, sendCompletionReminder } from '@/api/client'
import type {
  IranProvince,
  RegistrationSource,
  SerialGroup,
  SerialGroupCategory,
  SerialRow,
} from '@/api/types'
import { formatPhoneForDisplay } from '@/utils/formatPhone'
import { provinceById } from '@/data/iranProvinces'
import { ensurePersianPdfFont, preparePersianPdfText } from '@/utils/persianPdf'
import { perfPageNote } from '@/utils/perfDebug'

const SERIAL_CATEGORY_OPTIONS: { value: SerialGroupCategory; label: string }[] = [
  { value: 'end_user_client', label: 'مشتری نهایی' },
  { value: 'seller_to_end_user', label: 'فروشنده به مشتری نهایی' },
]

const PDF_EXPORT_MAX_ROWS = 20000
const EXPORT_PAGE_SIZE = 500

const fromDate = ref('')
const toDate = ref('')
const searchQuery = ref('')
const filterSms = ref(false)
const filterRubika = ref(false)
const filterTelegram = ref(false)
const filterBale = ref(false)
const filterEndUser = ref(false)
const filterSeller = ref(false)
const provinces = ref<IranProvince[]>([])
const provinceId = ref('')
const cityName = ref('')
const serialGroups = ref<SerialGroup[]>([])
const groupsLoading = ref(false)
const allGroupsMode = ref(true)
const selectedGroupIds = ref<Set<number>>(new Set())
const page = ref(1)
const limit = ref(50)
const total = ref(0)
const items = ref<SerialRow[]>([])
const loading = ref(false)
const exporting = ref(false)
const repairingDates = ref(false)
const error = ref('')
const repairFeedback = ref('')
const repairFeedbackOk = ref(true)
const sendingReminderId = ref<number | null>(null)
const editingRow = ref<SerialRow | null>(null)
const editModalOpen = ref(false)
const lastSearchUsedDefaultEnd = ref(false)

function warrantyLabel(months?: number | null, km?: number | null): string {
  const parts: string[] = []
  if (months != null && months > 0) parts.push(`${months} ماه`)
  if (km != null && km > 0) parts.push(`${km} کیلومتر`)
  return parts.length ? parts.join(' یا ') : 'بدون گارانتی'
}

const activeSources = computed((): RegistrationSource[] => {
  const sources: RegistrationSource[] = []
  if (filterSms.value) sources.push('sms')
  if (filterRubika.value) sources.push('rubika')
  if (filterTelegram.value) sources.push('telegram')
  if (filterBale.value) sources.push('bale')
  return sources
})

const activeCategories = computed((): SerialGroupCategory[] => {
  const categories: SerialGroupCategory[] = []
  if (filterEndUser.value) categories.push('end_user_client')
  if (filterSeller.value) categories.push('seller_to_end_user')
  return categories
})

const cityOptions = computed(() => {
  if (!provinceId.value) return []
  return provinceById(provinces.value, provinceId.value)?.cities ?? []
})

const dateRangeHint = computed(() => {
  if (!fromDate.value && !toDate.value && !lastSearchUsedDefaultEnd.value) {
    return 'همه سریال‌های ثبت‌شده'
  }
  const endLabel = toDate.value || (lastSearchUsedDefaultEnd.value ? 'امروز' : '')
  if (fromDate.value && endLabel) {
    return `بازه: ${fromDate.value} تا ${endLabel}`
  }
  if (fromDate.value) {
    return `از ${fromDate.value}`
  }
  if (endLabel) {
    return `تا ${endLabel}`
  }
  return 'همه سریال‌های ثبت‌شده'
})

const sourceHint = computed(() => {
  if (activeSources.value.length === 0) return 'همه کانال‌ها'
  const labels: Record<RegistrationSource, string> = {
    sms: 'پیامک',
    rubika: 'روبیکا',
    telegram: 'تلگرام',
    bale: 'بله',
  }
  return activeSources.value.map((s) => labels[s]).join('، ')
})

const categoryHint = computed(() => {
  if (activeCategories.value.length === 0) return 'همه انواع مشتری'
  return activeCategories.value
    .map((c) => SERIAL_CATEGORY_OPTIONS.find((o) => o.value === c)?.label ?? c)
    .join('، ')
})

const groupHint = computed(() => {
  if (allGroupsMode.value) return 'همه گروه‌ها'
  if (selectedGroupIds.value.size === 0) return 'هیچ گروهی انتخاب نشده'
  return `${selectedGroupIds.value.size.toLocaleString('fa-IR')} گروه`
})

const locationHint = computed(() => {
  if (!provinceId.value) return 'همه استان‌ها'
  const province = provinceById(provinces.value, provinceId.value)
  const provinceName = province?.name ?? provinceId.value
  if (!cityName.value) return provinceName
  return `${provinceName} — ${cityName.value}`
})

const groupSelectionError = computed(() => {
  if (allGroupsMode.value) return null
  if (selectedGroupIds.value.size === 0) return 'حداقل یک گروه سریال انتخاب کنید'
  return null
})

watch(provinceId, () => {
  cityName.value = ''
})

function enableAllGroupsMode() {
  allGroupsMode.value = true
}

function enableManualGroupSelection() {
  allGroupsMode.value = false
  if (selectedGroupIds.value.size === 0 && serialGroups.value.length > 0) {
    selectedGroupIds.value = new Set(serialGroups.value.map((g) => g.id))
  }
}

function isGroupSelected(id: number): boolean {
  return allGroupsMode.value || selectedGroupIds.value.has(id)
}

function toggleGroup(id: number) {
  if (allGroupsMode.value) return
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

async function loadProvinces() {
  try {
    const response = await fetchMassSmsProvinces()
    provinces.value = response.items
  } catch {
    provinces.value = []
  }
}

async function loadGroups() {
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

async function load() {
  if (groupSelectionError.value) {
    error.value = groupSelectionError.value
    return
  }
  const t0 = performance.now()
  perfPageNote('Reports load() start')
  loading.value = true
  error.value = ''
  try {
    const response = await fetchSerials({
      ...currentReportFilters(),
      page: page.value,
      limit: limit.value,
    })
    items.value = response.items
    total.value = response.total
    perfPageNote(`Reports load() done ${Math.round(performance.now() - t0)}ms (${response.items.length}/${response.total})`)
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا'
    perfPageNote(`Reports load() failed ${Math.round(performance.now() - t0)}ms`)
  } finally {
    loading.value = false
  }
}

function currentReportFilters() {
  return {
    registered: true as const,
    from: fromDate.value || undefined,
    to: toDate.value || undefined,
    defaultEndToday: lastSearchUsedDefaultEnd.value && !toDate.value ? true : undefined,
    search: searchQuery.value || undefined,
    sources: activeSources.value.length ? activeSources.value : undefined,
    categories: activeCategories.value.length ? activeCategories.value : undefined,
    groupIds: allGroupsMode.value ? undefined : Array.from(selectedGroupIds.value),
    provinceId: provinceId.value || undefined,
    cityName: provinceId.value && cityName.value ? cityName.value : undefined,
  }
}

/** Fetch every page of the current report filter (not just the visible page). */
async function fetchAllReportRows(maxRows?: number): Promise<SerialRow[]> {
  const all: SerialRow[] = []
  let pageNo = 1
  let totalCount = Number.POSITIVE_INFINITY
  while (all.length < totalCount) {
    const response = await fetchSerials({
      ...currentReportFilters(),
      page: pageNo,
      limit: EXPORT_PAGE_SIZE,
    })
    totalCount = response.total
    if (maxRows != null && totalCount > maxRows) {
      throw new Error(
        `تعداد نتایج (${totalCount.toLocaleString('fa-IR')}) برای این خروجی زیاد است. فیلتر را محدودتر کنید یا از CSV استفاده کنید (حداکثر PDF: ${maxRows.toLocaleString('fa-IR')}).`,
      )
    }
    all.push(...response.items)
    if (response.items.length === 0) break
    pageNo += 1
    // Safety against infinite loops if API misbehaves.
    if (pageNo > Math.ceil(totalCount / EXPORT_PAGE_SIZE) + 2) break
  }
  return all
}

async function runSearch() {
  page.value = 1
  lastSearchUsedDefaultEnd.value = !toDate.value
  await load()
}

async function runRepairNullDates() {
  repairingDates.value = true
  error.value = ''
  repairFeedback.value = ''
  try {
    const result = await repairNullDates()
    repairFeedbackOk.value = result.ok
    repairFeedback.value =
      result.message ??
      `اصلاح شد: محلی ${result.fixedLocal}، سرور ${result.fixedRemote}`
    await load()
  } catch (e) {
    repairFeedbackOk.value = false
    repairFeedback.value = e instanceof Error ? e.message : 'خطا در اصلاح تاریخ‌های خالی'
  } finally {
    repairingDates.value = false
  }
}

async function handleSendReminder(row: SerialRow) {
  sendingReminderId.value = row.id
  error.value = ''
  try {
    const updated = await sendCompletionReminder(row.id)
    items.value = items.value.map((item) => (item.id === updated.id ? updated : item))
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در ارسال پیامک'
  } finally {
    sendingReminderId.value = null
  }
}

function openRowEditor(row: SerialRow) {
  editingRow.value = row
  editModalOpen.value = true
}

function closeRowEditor() {
  editModalOpen.value = false
  editingRow.value = null
}

function handleRowSaved(updated: SerialRow) {
  items.value = items.value.map((item) => (item.id === updated.id ? updated : item))
}

function mapExportRow(row: SerialRow): string[] {
  return [
    row.serial,
    row.phone ? formatPhoneForDisplay(row.phone) : '',
    row.date ?? '',
    row.city ?? '',
    row.km != null ? String(row.km) : '',
    row.registrationSource ?? '',
  ]
}

async function exportCsv() {
  if (groupSelectionError.value) {
    error.value = groupSelectionError.value
    return
  }
  exporting.value = true
  error.value = ''
  try {
    const rows = await fetchAllReportRows()
    const header = ['serial', 'phone', 'date', 'city', 'km', 'source']
    const lines = rows.map((row) => mapExportRow(row).join(','))
    const csv = [header.join(','), ...lines].join('\n')
    const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8;' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = 'registered-serials.csv'
    link.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در خروجی CSV'
  } finally {
    exporting.value = false
  }
}

async function exportPdf() {
  if (groupSelectionError.value) {
    error.value = groupSelectionError.value
    return
  }
  exporting.value = true
  error.value = ''
  try {
    const rows = await fetchAllReportRows(PDF_EXPORT_MAX_ROWS)
    const { jsPDF } = await import('jspdf')
    const autoTable = (await import('jspdf-autotable')).default
    const doc = new jsPDF({ orientation: 'landscape' })
    const fontName = await ensurePersianPdfFont(doc)

    const head = [['سریال', 'تلفن', 'تاریخ', 'شهر', 'کیلومتر', 'منبع']].map((row) =>
      row.map((cell) => preparePersianPdfText(cell)),
    )
    const body = rows.map((row) => mapExportRow(row).map((cell) => preparePersianPdfText(cell)))

    autoTable(doc, {
      head,
      body,
      styles: {
        font: fontName,
        fontStyle: 'normal',
        fontSize: 8,
        halign: 'center',
      },
      headStyles: {
        font: fontName,
        fontStyle: 'normal',
        fontSize: 9,
        halign: 'center',
      },
      // City column often Persian — keep centered with shaped glyphs.
    })
    doc.save('registered-serials.pdf')
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در خروجی PDF'
  } finally {
    exporting.value = false
  }
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

onMounted(() => {
  perfPageNote('Reports mounted')
  void loadProvinces()
  void loadGroups()
  void load()
})
</script>

<template>
  <div>
    <NavBar />
    <div class="page">
      <div class="card">
        <h1>گزارش سریال‌های ثبت‌شده</h1>
        <p class="hint">
          تاریخ را با انتخاب سال، ماه و روز وارد کنید. با کلیک روی «جستجو»، اگر «تا تاریخ» خالی
          باشد تا پایان امروز فیلتر می‌شود.
        </p>

        <section class="filter-section">
          <h2 class="filter-section-title">بازه تاریخ</h2>
          <div class="filter-row date-filter-row">
            <div class="filter-field">
              <span class="filter-label-text">از تاریخ</span>
              <JalaliDateSelects v-model="fromDate" clearable />
            </div>
            <div class="filter-field">
              <span class="filter-label-text">تا تاریخ</span>
              <span class="field-hint">خالی = تا پایان امروز</span>
              <JalaliDateSelects v-model="toDate" clearable />
            </div>
          </div>
        </section>

        <section class="filter-section">
          <h2 class="filter-section-title">جستجو</h2>
          <div class="filter-row">
            <label class="filter-field search-field">
              <span class="filter-label-text">سریال / تلفن</span>
              <input
                v-model="searchQuery"
                type="text"
                placeholder="مثال: M100 یا 0913"
                @keyup.enter="runSearch"
              />
            </label>
          </div>
        </section>

        <section class="filter-section">
          <h2 class="filter-section-title">موقعیت</h2>
          <div class="filter-row location-filter-row">
            <label class="filter-field">
              <span class="filter-label-text">استان</span>
              <select v-model="provinceId">
                <option value="">همه استان‌ها</option>
                <option v-for="p in provinces" :key="p.id" :value="p.id">
                  {{ p.name }}
                </option>
              </select>
            </label>
            <label class="filter-field" :class="{ disabled: !provinceId }">
              <span class="filter-label-text">شهر</span>
              <select v-model="cityName" :disabled="!provinceId">
                <option value="">همه شهرهای استان</option>
                <option v-for="city in cityOptions" :key="city" :value="city">
                  {{ city }}
                </option>
              </select>
            </label>
          </div>
        </section>
        <div class="source-filters">
          <p class="hint source-filters-label">منبع ثبت‌نام</p>
          <div class="source-filter-toggles">
            <UiSwitch v-model="filterSms" label="پیامک" />
            <UiSwitch v-model="filterRubika" label="روبیکا" />
            <UiSwitch v-model="filterTelegram" label="تلگرام" />
            <UiSwitch v-model="filterBale" label="بله" />
          </div>
          <p class="hint">اگر هیچکدام روشن نباشد، همه منابع نمایش داده می‌شوند.</p>
        </div>
        <div class="source-filters">
          <p class="hint source-filters-label">نوع مشتری (گروه سریال)</p>
          <div class="source-filter-toggles">
            <UiSwitch v-model="filterEndUser" label="مشتری نهایی" />
            <UiSwitch v-model="filterSeller" label="فروشنده به مشتری نهایی" />
          </div>
          <p class="hint">اگر هیچکدام روشن نباشد، همه انواع مشتری نمایش داده می‌شوند.</p>
        </div>
        <section class="group-filter-section">
          <div class="group-filter-header">
            <h2>فیلتر گروه سریال</h2>
            <div class="group-mode-actions">
              <button
                type="button"
                class="mode-chip"
                :class="{ active: allGroupsMode }"
                @click="enableAllGroupsMode"
              >
                همه گروه‌ها
              </button>
              <button
                type="button"
                class="mode-chip"
                :class="{ active: !allGroupsMode }"
                @click="enableManualGroupSelection"
              >
                انتخاب دستی
              </button>
            </div>
          </div>
          <p v-if="groupsLoading" class="hint">در حال بارگذاری گروه‌ها...</p>
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
                :disabled="allGroupsMode"
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
        <div class="actions">
          <button class="primary" :disabled="loading || exporting || repairingDates" @click="runSearch">
            جستجو
          </button>
          <button
            class="secondary"
            :disabled="loading || exporting || repairingDates"
            @click="runRepairNullDates"
          >
            {{ repairingDates ? 'در حال اصلاح تاریخ…' : 'اصلاح تاریخ‌های خالی' }}
          </button>
          <button
            class="secondary"
            :disabled="loading || exporting || repairingDates || total === 0"
            @click="exportCsv"
          >
            {{ exporting ? 'در حال آماده‌سازی...' : 'خروجی CSV' }}
          </button>
          <button
            class="secondary"
            :disabled="loading || exporting || repairingDates || total === 0"
            @click="exportPdf"
          >
            {{ exporting ? 'در حال آماده‌سازی...' : 'خروجی PDF' }}
          </button>
        </div>
        <p v-if="error" class="error">{{ error }}</p>
        <p
          v-if="repairFeedback"
          class="hint"
          :class="repairFeedbackOk ? 'repair-ok' : 'error'"
        >
          {{ repairFeedback }}
        </p>
        <p class="hint">
          {{ dateRangeHint }} — مکان: {{ locationHint }} — منبع: {{ sourceHint }} — نوع مشتری:
          {{ categoryHint }} — گروه: {{ groupHint }} — نتایج: {{ total }} — صفحه {{ page }}
        </p>
        <p class="hint row-edit-hint">برای ویرایش سریال، تلفن، تاریخ، شهر یا کیلومتر روی هر ردیف کلیک کنید.</p>
        <SerialTable
          :items="items"
          show-reminder-actions
          clickable-rows
          :sending-reminder-id="sendingReminderId"
          @send-reminder="handleSendReminder"
          @row-click="openRowEditor"
        />
        <div class="actions">
          <button class="secondary" :disabled="page <= 1" @click="prevPage">قبلی</button>
          <button class="secondary" :disabled="page * limit >= total" @click="nextPage">
            بعدی
          </button>
        </div>
      </div>
    </div>

    <SerialRowEditModal
      :open="editModalOpen"
      :row="editingRow"
      @close="closeRowEditor"
      @saved="handleRowSaved"
    />
  </div>
</template>

<style scoped>
.filter-section {
  margin: 1rem 0;
  padding: 0.9rem 1rem;
  border: 1px solid #e0e0e0;
  border-radius: 10px;
  background: #fafafa;
}

.filter-section-title {
  margin: 0 0 0.75rem;
  font-size: 0.95rem;
  font-weight: 700;
  color: #37474f;
}

.filter-row {
  display: grid;
  gap: 0.85rem 1rem;
  align-items: start;
}

.date-filter-row,
.location-filter-row {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.filter-field {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  margin: 0;
  font-weight: 600;
  font-size: 0.88rem;
  min-width: 0;
}

.filter-label-text {
  display: block;
}

.filter-field :deep(input),
.filter-field :deep(select) {
  width: 100%;
  box-sizing: border-box;
}

.field-hint {
  font-weight: 500;
  font-size: 0.78rem;
  color: #78909c;
}

.search-field {
  max-width: 28rem;
}

@media (max-width: 720px) {
  .date-filter-row,
  .location-filter-row {
    grid-template-columns: 1fr;
  }

  .search-field {
    max-width: none;
  }
}

.group-filter-section {
  margin: 1rem 0;
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
  transition:
    background 0.15s,
    border-color 0.15s,
    color 0.15s;
}

.mode-chip.active {
  background: #1565c0;
  border-color: #1565c0;
  color: #fff;
}

.group-helper-actions {
  display: flex;
  gap: 1rem;
  margin-bottom: 0.65rem;
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
  transition:
    background 0.15s,
    border-color 0.15s;
}

.group-chip.selected {
  background: #e3f2fd;
  border-color: #1565c0;
}

.group-chip.readonly {
  cursor: default;
}

.group-chip:disabled {
  opacity: 0.85;
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

.row-edit-hint {
  margin-top: -0.35rem;
  color: #1565c0;
  font-weight: 600;
}

.repair-ok {
  color: #2e7d32;
}
</style>
