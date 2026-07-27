<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { fetchMassSmsProvinces, updateSerialRow } from '@/api/client'
import type { IranProvince, RegistrationSource, SerialRow } from '@/api/types'
import { formatPhoneForDisplay } from '@/utils/formatPhone'
import { provinceById } from '@/data/iranProvinces'
import {
  JALALI_MONTH_NAMES,
  currentJalaliYear,
  jalaliDaysInMonth,
  parseJalaliParts,
  toLatinDigits,
} from '@/utils/jalali'

const props = defineProps<{
  open: boolean
  row: SerialRow | null
}>()

const emit = defineEmits<{
  close: []
  saved: [row: SerialRow]
}>()

const provinces = ref<IranProvince[]>([])
const editSerial = ref('')
const editYear = ref<number | null>(null)
const editMonth = ref<number | null>(null)
const editDay = ref<number | null>(null)
const editHour = ref('00')
const editMinute = ref('00')
const editSecond = ref('00')
const editCity = ref('')
const editKm = ref('')
const provinceId = ref('')
const cityFromList = ref('')
const useCityList = ref(true)
const saving = ref(false)
const error = ref('')

const cityOptions = computed(() => {
  if (!provinceId.value) return []
  return provinceById(provinces.value, provinceId.value)?.cities ?? []
})

const monthOptions = JALALI_MONTH_NAMES.map((name, index) => ({
  value: index + 1,
  label: name,
}))

const yearOptions = computed(() => {
  const current = currentJalaliYear()
  const years: number[] = []
  for (let year = current + 1; year >= current - 20; year--) {
    years.push(year)
  }
  if (editYear.value && !years.includes(editYear.value)) {
    years.push(editYear.value)
    years.sort((a, b) => b - a)
  }
  return years
})

const dayOptions = computed(() => {
  const year = editYear.value ?? currentJalaliYear()
  const month = editMonth.value ?? 1
  const count = jalaliDaysInMonth(year, month)
  return Array.from({ length: count }, (_, i) => i + 1)
})

const composedDate = computed(() => {
  if (!editYear.value || !editMonth.value || !editDay.value) return ''
  const month = String(editMonth.value).padStart(2, '0')
  const day = String(editDay.value).padStart(2, '0')
  return `${editYear.value}/${month}/${day}`
})

const sourceLabel = computed(() => {
  switch (props.row?.registrationSource) {
    case 'sms':
      return 'پیامک'
    case 'rubika':
      return 'روبیکا'
    case 'telegram':
      return 'تلگرام'
    case 'bale':
      return 'بله'
    default:
      return props.row?.registrationSource?.trim() || '—'
  }
})

function parseDateTime(value?: string | null): {
  date: string
  hour: string
  minute: string
  second: string
} {
  if (!value?.trim()) {
    return { date: '', hour: '00', minute: '00', second: '00' }
  }
  const trimmed = toLatinDigits(value.trim())
  const spaceIndex = trimmed.indexOf(' ')
  if (spaceIndex === -1) {
    return { date: trimmed, hour: '00', minute: '00', second: '00' }
  }
  const date = trimmed.slice(0, spaceIndex)
  const timeParts = trimmed.slice(spaceIndex + 1).split(':')
  return {
    date,
    hour: timeParts[0]?.padStart(2, '0') ?? '00',
    minute: timeParts[1]?.padStart(2, '0') ?? '00',
    second: timeParts[2]?.padStart(2, '0') ?? '00',
  }
}

function clampTimePart(value: string, max: number): string {
  const digits = value.replace(/\D/g, '').slice(0, 2)
  if (!digits) return '00'
  const num = Math.min(max, Math.max(0, Number.parseInt(digits, 10)))
  return String(num).padStart(2, '0')
}

function findProvinceForCity(city: string): string {
  const normalized = city.trim()
  if (!normalized) return ''
  for (const province of provinces.value) {
    if (province.cities.some((c) => c === normalized)) {
      return province.id
    }
  }
  return ''
}

function resetForm() {
  if (!props.row) return
  editSerial.value = props.row.serial
  const parsed = parseDateTime(props.row.date)
  const dateParts = parseJalaliParts(parsed.date)
  editYear.value = dateParts?.year ?? null
  editMonth.value = dateParts?.month ?? null
  editDay.value = dateParts?.day ?? null
  editHour.value = parsed.hour
  editMinute.value = parsed.minute
  editSecond.value = parsed.second
  editCity.value = props.row.city ?? ''
  editKm.value = props.row.km != null ? String(props.row.km) : ''
  const matchedProvince = findProvinceForCity(editCity.value)
  if (matchedProvince) {
    provinceId.value = matchedProvince
    cityFromList.value = editCity.value
    useCityList.value = true
  } else {
    provinceId.value = ''
    cityFromList.value = ''
    useCityList.value = false
  }
  error.value = ''
}

async function loadProvinces() {
  try {
    const response = await fetchMassSmsProvinces()
    provinces.value = response.items
  } catch {
    provinces.value = []
  }
}

function buildDateTime(): string {
  const date = composedDate.value
  const hour = clampTimePart(editHour.value, 23)
  const minute = clampTimePart(editMinute.value, 59)
  const second = clampTimePart(editSecond.value, 59)
  editHour.value = hour
  editMinute.value = minute
  editSecond.value = second
  return `${date} ${hour}:${minute}:${second}`
}

function resolvedCity(): string {
  if (useCityList.value && cityFromList.value) {
    return cityFromList.value.trim()
  }
  return editCity.value.trim()
}

async function save() {
  if (!props.row) return
  const serial = editSerial.value.trim()
  if (!serial) {
    error.value = 'سریال الزامی است'
    return
  }
  if (!composedDate.value) {
    error.value = 'تاریخ الزامی است'
    return
  }
  const kmRaw = editKm.value.trim()
  const km = kmRaw ? Number.parseInt(kmRaw, 10) : null
  if (kmRaw && (km == null || Number.isNaN(km) || km < 0)) {
    error.value = 'کیلومتر نامعتبر است'
    return
  }

  saving.value = true
  error.value = ''
  try {
    const updated = await updateSerialRow(props.row.id, {
      serial,
      date: buildDateTime(),
      city: resolvedCity() || null,
      km,
    })
    emit('saved', updated)
    emit('close')
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در ذخیره'
  } finally {
    saving.value = false
  }
}

function handleOverlayClick(event: MouseEvent) {
  if (event.target === event.currentTarget) {
    emit('close')
  }
}

watch(
  () => [props.open, props.row?.id] as const,
  ([open]) => {
    if (open) {
      resetForm()
      if (provinces.value.length === 0) {
        void loadProvinces()
      }
    }
  },
  { immediate: true },
)

watch([editYear, editMonth], () => {
  if (editDay.value == null) return
  const maxDay = jalaliDaysInMonth(
    editYear.value ?? currentJalaliYear(),
    editMonth.value ?? 1,
  )
  if (editDay.value > maxDay) {
    editDay.value = maxDay
  }
})

watch(provinceId, () => {
  cityFromList.value = ''
})

watch(cityFromList, (value) => {
  if (useCityList.value && value) {
    editCity.value = value
  }
})

watch(useCityList, (enabled) => {
  if (enabled && cityFromList.value) {
    editCity.value = cityFromList.value
  }
})
</script>

<template>
  <Teleport to="body">
    <div v-if="open && row" class="modal-overlay" @click="handleOverlayClick">
      <div
        class="modal-panel serial-edit-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="serial-edit-title"
        @click.stop
      >
        <div class="serial-edit-header">
          <div>
            <p class="serial-edit-eyebrow">ویرایش ثبت مشتری</p>
            <h2 id="serial-edit-title">{{ formatPhoneForDisplay(row.phone) }}</h2>
          </div>
          <button type="button" class="modal-close" aria-label="بستن" @click="emit('close')">
            ×
          </button>
        </div>

        <div class="serial-edit-meta">
          <span class="meta-chip source">{{ sourceLabel }}</span>
          <span class="meta-chip">شناسه: {{ row.id }}</span>
        </div>

        <div class="modal-body serial-edit-body">
          <div class="field-card">
            <label class="field-label">
              سریال
              <input v-model="editSerial" type="text" class="serial-input" autocomplete="off" />
            </label>
          </div>

          <div class="field-card">
            <p class="field-group-title">تاریخ و زمان ثبت</p>
            <div class="field-label">
              تاریخ
              <div class="date-row">
                <select v-model.number="editYear" aria-label="سال">
                  <option :value="null" disabled>سال</option>
                  <option v-for="year in yearOptions" :key="year" :value="year">
                    {{ year }}
                  </option>
                </select>
                <select v-model.number="editMonth" aria-label="ماه">
                  <option :value="null" disabled>ماه</option>
                  <option v-for="m in monthOptions" :key="m.value" :value="m.value">
                    {{ m.label }}
                  </option>
                </select>
                <select v-model.number="editDay" aria-label="روز">
                  <option :value="null" disabled>روز</option>
                  <option v-for="day in dayOptions" :key="day" :value="day">
                    {{ day }}
                  </option>
                </select>
              </div>
            </div>
            <div class="time-row">
              <label>
                ساعت
                <input
                  v-model="editHour"
                  type="text"
                  inputmode="numeric"
                  maxlength="2"
                  placeholder="00"
                />
              </label>
              <span class="time-sep">:</span>
              <label>
                دقیقه
                <input
                  v-model="editMinute"
                  type="text"
                  inputmode="numeric"
                  maxlength="2"
                  placeholder="00"
                />
              </label>
              <span class="time-sep">:</span>
              <label>
                ثانیه
                <input
                  v-model="editSecond"
                  type="text"
                  inputmode="numeric"
                  maxlength="2"
                  placeholder="00"
                />
              </label>
            </div>
          </div>

          <div class="field-card">
            <div class="city-mode-row">
              <p class="field-group-title">شهر</p>
              <button
                type="button"
                class="city-mode-btn"
                @click="useCityList = !useCityList"
              >
                {{ useCityList ? 'ورود دستی' : 'انتخاب از لیست' }}
              </button>
            </div>
            <template v-if="useCityList">
              <label class="field-label">
                استان
                <select v-model="provinceId">
                  <option value="">انتخاب استان</option>
                  <option v-for="p in provinces" :key="p.id" :value="p.id">
                    {{ p.name }}
                  </option>
                </select>
              </label>
              <label class="field-label" :class="{ disabled: !provinceId }">
                شهر
                <select v-model="cityFromList" :disabled="!provinceId">
                  <option value="">انتخاب شهر</option>
                  <option v-for="city in cityOptions" :key="city" :value="city">
                    {{ city }}
                  </option>
                </select>
              </label>
            </template>
            <label v-else class="field-label">
              نام شهر
              <input v-model="editCity" type="text" placeholder="مثال: تهران" />
            </label>
          </div>

          <div class="field-card">
            <label class="field-label">
              کیلومتر
              <input
                v-model="editKm"
                type="number"
                min="0"
                inputmode="numeric"
                placeholder="مثال: 45000"
              />
            </label>
          </div>

          <p v-if="error" class="error">{{ error }}</p>
        </div>

        <div class="modal-actions serial-edit-actions">
          <button type="button" class="secondary" :disabled="saving" @click="emit('close')">
            انصراف
          </button>
          <button type="button" class="primary" :disabled="saving" @click="save">
            {{ saving ? 'در حال ذخیره...' : 'ذخیره تغییرات' }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.serial-edit-panel {
  width: min(520px, 100%);
  max-height: min(88vh, 760px);
  border-radius: 16px;
  overflow: hidden;
}

.serial-edit-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  padding: 1.1rem 1.15rem 0.85rem;
  background: linear-gradient(135deg, #0d47a1 0%, #1565c0 55%, #1976d2 100%);
  color: #fff;
}

.serial-edit-eyebrow {
  margin: 0 0 0.2rem;
  font-size: 0.78rem;
  opacity: 0.88;
}

.serial-edit-header h2 {
  margin: 0;
  font-size: 1.35rem;
  letter-spacing: 0.02em;
}

.serial-edit-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 0.45rem;
  padding: 0.7rem 1.15rem;
  background: #e3f2fd;
  border-bottom: 1px solid #bbdefb;
}

.meta-chip {
  display: inline-flex;
  align-items: center;
  padding: 0.2rem 0.65rem;
  border-radius: 999px;
  background: #fff;
  color: #455a64;
  font-size: 0.8rem;
  font-weight: 600;
}

.meta-chip.source {
  background: #1565c0;
  color: #fff;
}

.serial-edit-body {
  display: flex;
  flex-direction: column;
  gap: 0.85rem;
  padding: 1rem 1.15rem 0.5rem;
  overflow-y: auto;
}

.field-card {
  padding: 0.85rem 0.9rem;
  border: 1px solid #e0e7eb;
  border-radius: 12px;
  background: #fafcfd;
}

.field-group-title {
  margin: 0 0 0.65rem;
  font-size: 0.88rem;
  font-weight: 700;
  color: #37474f;
}

.field-label {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  margin-bottom: 0.65rem;
  font-weight: 600;
  font-size: 0.86rem;
}

.field-label:last-child {
  margin-bottom: 0;
}

.serial-input {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  letter-spacing: 0.04em;
}

.date-row {
  display: grid;
  grid-template-columns: 1.1fr 1.3fr 1fr;
  gap: 0.4rem;
}

.date-row select {
  width: 100%;
}

.time-row {
  display: grid;
  grid-template-columns: 1fr auto 1fr auto 1fr;
  align-items: end;
  gap: 0.35rem;
}

.time-row label {
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
  font-size: 0.8rem;
  font-weight: 600;
  color: #546e7a;
}

.time-row input {
  text-align: center;
  font-variant-numeric: tabular-nums;
}

.time-sep {
  padding-bottom: 0.55rem;
  color: #90a4ae;
  font-weight: 700;
}

.city-mode-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  margin-bottom: 0.65rem;
}

.city-mode-row .field-group-title {
  margin: 0;
}

.city-mode-btn {
  padding: 0.25rem 0.55rem;
  border: none;
  background: transparent;
  color: #1565c0;
  font: inherit;
  font-size: 0.8rem;
  font-weight: 600;
  cursor: pointer;
  text-decoration: underline;
}

.serial-edit-actions {
  padding: 0.85rem 1.15rem 1rem;
  border-top: 1px solid #eceff1;
  background: #fff;
}
</style>
