<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import NavBar from '@/components/NavBar.vue'
import ConfirmModal from '@/components/ConfirmModal.vue'
import DuplicateSerialWarningModal from '@/components/DuplicateSerialWarningModal.vue'
import SerialGroupCard from '@/components/SerialGroupCard.vue'
import UiSwitch from '@/components/UiSwitch.vue'
import {
  createComplexSerialBatch,
  createImportSerialBatch,
  createSerialBatch,
  deleteSerialGroup,
  fetchSerialGroups,
} from '@/api/client'
import { isDuplicateSerialError } from '@/api/types'
import type { DuplicateRangeItem, SerialGroup, SerialGroupCategory } from '@/api/types'
import { parseSerialsFromExcelFile } from '@/utils/parseExcelSerials'

const SERIAL_CATEGORY_OPTIONS: { value: SerialGroupCategory; label: string }[] = [
  { value: 'end_user_client', label: 'مشتری نهایی' },
  { value: 'seller_to_end_user', label: 'فروشنده به مشتری نهایی' },
]

type GenerationTab = 'range' | 'complex' | 'import'

/** Must match SerialGenerator.LARGE_BATCH_THRESHOLD on the Android gateway. */
const MAX_SERIAL_BATCH = 500_000

const activeTab = ref<GenerationTab>('range')

const prefix = ref('M')
const start = ref(100)
const end = ref(200)
const padWidth = ref(0)
const description = ref('')
const score = ref(1)
const category = ref<SerialGroupCategory>('end_user_client')
const warrantyMonths = ref<number | ''>(6)
const warrantyKm = ref<number | ''>('')
const imageVerificationRequired = ref(false)
const loading = ref(false)
const error = ref('')
const result = ref('')

const complexCount = ref(100)
const letterCount = ref(3)
const digitCount = ref(7)
const complexUsePrefix = ref(false)
const complexPrefix = ref('')
const complexDescription = ref('')
const complexScore = ref(1)
const complexCategory = ref<SerialGroupCategory>('end_user_client')
const complexWarrantyMonths = ref<number | ''>(6)
const complexWarrantyKm = ref<number | ''>('')
const complexImageVerificationRequired = ref(false)
const complexLoading = ref(false)
const complexError = ref('')
const complexResult = ref('')

const importFileName = ref('')
const importSerials = ref<string[]>([])
const importInvalidSample = ref<string[]>([])
const importEmptyRows = ref(0)
const importDescription = ref('')
const importScore = ref(1)
const importCategory = ref<SerialGroupCategory>('end_user_client')
const importWarrantyMonths = ref<number | ''>(6)
const importWarrantyKm = ref<number | ''>('')
const importImageVerificationRequired = ref(false)
const importParsing = ref(false)
const importLoading = ref(false)
const importError = ref('')
const importResult = ref('')

const groups = ref<SerialGroup[]>([])
const groupsLoading = ref(false)
const groupsError = ref('')
const deleteTarget = ref<SerialGroup | null>(null)
const deleting = ref(false)
const duplicateWarningOpen = ref(false)
const duplicateCount = ref(0)
const duplicateRanges = ref<DuplicateRangeItem[]>([])
const duplicateMessage = ref('')

const count = computed(() => {
  if (start.value > end.value || start.value < 0 || end.value < 0) return 0
  return end.value - start.value + 1
})

const complexLength = computed(() => letterCount.value + digitCount.value)

const complexNormalizedPrefix = computed(() => {
  if (!complexUsePrefix.value) return ''
  return complexPrefix.value.trim().toUpperCase().replace(/[^A-Z0-9]/g, '')
})

const complexTotalLength = computed(
  () => complexNormalizedPrefix.value.length + complexLength.value,
)

const complexExample = computed(() => {
  const letters = 'ABCDEFGHJKLMNPQRSTUVWXYZ'
  const digits = '23456789'
  const bodyLen = Math.max(0, complexLength.value)
  const showLen = Math.min(bodyLen, 14)
  const letterSlots = Math.min(letterCount.value, showLen)
  const digitSlots = showLen - letterSlots
  const body: string[] = []
  for (let i = 0; i < letterSlots; i++) body.push(letters[i % letters.length]!)
  for (let i = 0; i < digitSlots; i++) body.push(digits[i % digits.length]!)
  // Shuffle lightly so preview looks mixed (deterministic)
  for (let i = body.length - 1; i > 0; i--) {
    const j = (i * 3 + 1) % (i + 1)
    const tmp = body[i]!
    body[i] = body[j]!
    body[j] = tmp
  }
  const sample = complexNormalizedPrefix.value + body.join('')
  return bodyLen > showLen ? `${sample}…` : sample
})

const complexPrefixValid = computed(() => {
  if (!complexUsePrefix.value) return true
  const raw = complexPrefix.value.trim()
  if (!raw) return false
  return /^[A-Za-z0-9]+$/.test(raw)
})

const complexValid = computed(() => {
  return (
    complexCount.value >= 1 &&
    complexCount.value <= MAX_SERIAL_BATCH &&
    letterCount.value >= 0 &&
    digitCount.value >= 0 &&
    (letterCount.value > 0 || digitCount.value > 0) &&
    complexLength.value >= 6 &&
    complexLength.value <= 32 &&
    complexTotalLength.value <= 32 &&
    complexPrefixValid.value &&
    complexScore.value >= 1 &&
    hasWarranty(complexWarrantyMonths.value, complexWarrantyKm.value)
  )
})

const importValid = computed(() => {
  return (
    importSerials.value.length > 0 &&
    importScore.value >= 1 &&
    !importParsing.value &&
    hasWarranty(importWarrantyMonths.value, importWarrantyKm.value)
  )
})

function hasWarranty(months: number | '', km: number | ''): boolean {
  const m = months === '' ? 0 : Number(months)
  const k = km === '' ? 0 : Number(km)
  return m > 0 || k > 0
}

function warrantyPayload(months: number | '', km: number | '') {
  return {
    warrantyMonths: months === '' || Number(months) <= 0 ? null : Number(months),
    warrantyKm: km === '' || Number(km) <= 0 ? null : Number(km),
  }
}

const importPreview = computed(() => importSerials.value.slice(0, 8))

const deleteMessage = computed(() => {
  if (!deleteTarget.value) return ''
  const g = deleteTarget.value
  return `آیا از حذف گروه «${g.rangeLabel}» مطمئن هستید؟ تمام ${g.total} سریال این گروه (شامل ${g.used} مورد ثبت‌شده) برای همیشه از پایگاه داده حذف می‌شوند.`
})

async function loadGroups() {
  groupsLoading.value = true
  groupsError.value = ''
  try {
    const response = await fetchSerialGroups()
    groups.value = response.items
  } catch (e) {
    groupsError.value = e instanceof Error ? e.message : 'خطا در بارگذاری گروه‌ها'
  } finally {
    groupsLoading.value = false
  }
}

async function submit() {
  error.value = ''
  result.value = ''
  if (score.value < 1) {
    error.value = 'امتیاز باید حداقل ۱ باشد'
    return
  }
  if (!hasWarranty(warrantyMonths.value, warrantyKm.value)) {
    error.value = 'حداقل یکی از مدت گارانتی (ماه) یا کیلومتر گارانتی را وارد کنید'
    return
  }
  loading.value = true
  try {
    const response = await createSerialBatch({
      prefix: prefix.value,
      start: start.value,
      end: end.value,
      padWidth: padWidth.value,
      description: description.value || undefined,
      score: score.value,
      category: category.value,
      imageVerificationRequired: imageVerificationRequired.value,
      ...warrantyPayload(warrantyMonths.value, warrantyKm.value),
    })
    result.value = `ثبت: ${response.inserted} • تکراری: ${response.skipped} • کل: ${response.total}`
    await loadGroups()
  } catch (e) {
    if (isDuplicateSerialError(e)) {
      duplicateCount.value = e.duplicateCount
      duplicateRanges.value = e.duplicateRanges
      duplicateMessage.value = e.message
      duplicateWarningOpen.value = true
    } else {
      error.value = e instanceof Error ? e.message : 'خطا'
    }
  } finally {
    loading.value = false
  }
}

async function submitComplex() {
  complexError.value = ''
  complexResult.value = ''
  if (letterCount.value < 0 || digitCount.value < 0) {
    complexError.value = 'تعداد حروف و ارقام نمی‌تواند منفی باشد'
    return
  }
  if (letterCount.value === 0 && digitCount.value === 0) {
    complexError.value = 'حداقل یک حرف یا رقم لازم است'
    return
  }
  if (complexLength.value < 6 || complexLength.value > 32) {
    complexError.value = 'طول بخش تصادفی (حروف + ارقام) باید بین ۶ تا ۳۲ باشد'
    return
  }
  if (complexUsePrefix.value) {
    const raw = complexPrefix.value.trim()
    if (!raw) {
      complexError.value = 'پیشوند را وارد کنید یا گزینه پیشوند ثابت را خاموش کنید'
      return
    }
    if (!/^[A-Za-z0-9]+$/.test(raw)) {
      complexError.value = 'پیشوند فقط می‌تواند شامل حروف انگلیسی و ارقام باشد'
      return
    }
  }
  if (complexTotalLength.value > 32) {
    complexError.value =
      'مجموع طول پیشوند و بخش تصادفی نباید بیشتر از ۳۲ کاراکتر باشد'
    return
  }
  if (complexScore.value < 1) {
    complexError.value = 'امتیاز باید حداقل ۱ باشد'
    return
  }
  if (complexCount.value < 1 || complexCount.value > MAX_SERIAL_BATCH) {
    complexError.value = `تعداد باید بین ۱ تا ${MAX_SERIAL_BATCH.toLocaleString('en-US')} باشد`
    return
  }
  if (!hasWarranty(complexWarrantyMonths.value, complexWarrantyKm.value)) {
    complexError.value = 'حداقل یکی از مدت گارانتی (ماه) یا کیلومتر گارانتی را وارد کنید'
    return
  }
  complexLoading.value = true
  try {
    const response = await createComplexSerialBatch({
      count: complexCount.value,
      letterCount: letterCount.value,
      digitCount: digitCount.value,
      prefix: complexNormalizedPrefix.value || undefined,
      description: complexDescription.value || undefined,
      score: complexScore.value,
      category: complexCategory.value,
      imageVerificationRequired: complexImageVerificationRequired.value,
      ...warrantyPayload(complexWarrantyMonths.value, complexWarrantyKm.value),
    })
    complexResult.value =
      `ثبت: ${response.inserted} • رد شده (تکراری): ${response.skipped} • هدف: ${response.total}`
    await loadGroups()
  } catch (e) {
    complexError.value = e instanceof Error ? e.message : 'خطا'
  } finally {
    complexLoading.value = false
  }
}

function clearImportFile() {
  importFileName.value = ''
  importSerials.value = []
  importInvalidSample.value = []
  importEmptyRows.value = 0
  importError.value = ''
  importResult.value = ''
}

async function onImportFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  importResult.value = ''
  importError.value = ''
  if (!file) {
    clearImportFile()
    return
  }
  importParsing.value = true
  try {
    const parsed = await parseSerialsFromExcelFile(file)
    importFileName.value = parsed.fileName
    importSerials.value = parsed.serials
    importInvalidSample.value = parsed.invalidRows
    importEmptyRows.value = parsed.emptyRows
    if (parsed.serials.length === 0) {
      importError.value = 'در ستون اول هیچ سریال معتبری یافت نشد'
    }
  } catch (e) {
    clearImportFile()
    importError.value = e instanceof Error ? e.message : 'خطا در خواندن فایل اکسل'
  } finally {
    importParsing.value = false
    input.value = ''
  }
}

async function submitImport() {
  importError.value = ''
  importResult.value = ''
  if (importSerials.value.length === 0) {
    importError.value = 'ابتدا فایل اکسل را انتخاب کنید'
    return
  }
  if (importScore.value < 1) {
    importError.value = 'امتیاز باید حداقل ۱ باشد'
    return
  }
  if (!hasWarranty(importWarrantyMonths.value, importWarrantyKm.value)) {
    importError.value = 'حداقل یکی از مدت گارانتی (ماه) یا کیلومتر گارانتی را وارد کنید'
    return
  }
  importLoading.value = true
  try {
    const response = await createImportSerialBatch({
      serials: importSerials.value,
      description: importDescription.value || undefined,
      score: importScore.value,
      category: importCategory.value,
      imageVerificationRequired: importImageVerificationRequired.value,
      ...warrantyPayload(importWarrantyMonths.value, importWarrantyKm.value),
    })
    importResult.value =
      `ثبت: ${response.inserted} • رد شده (تکراری): ${response.skipped} • خوانده‌شده: ${response.total}`
    await loadGroups()
  } catch (e) {
    importError.value = e instanceof Error ? e.message : 'خطا در ورود سریال‌ها'
  } finally {
    importLoading.value = false
  }
}

function requestDelete(group: SerialGroup) {
  deleteTarget.value = group
}

function onGroupUpdated(updated: SerialGroup) {
  groups.value = groups.value.map((g) => (g.id === updated.id ? updated : g))
}

function cancelDelete() {
  deleteTarget.value = null
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    await deleteSerialGroup(deleteTarget.value.id)
    deleteTarget.value = null
    await loadGroups()
  } catch (e) {
    groupsError.value = e instanceof Error ? e.message : 'خطا در حذف گروه'
  } finally {
    deleting.value = false
  }
}

onMounted(() => {
  void loadGroups()
})
</script>

<template>
  <div>
    <NavBar />
    <div class="page">
      <div class="card">
        <h1>تولید سریال</h1>
        <div class="generation-tabs">
          <button
            type="button"
            :class="['tab', { active: activeTab === 'range' }]"
            @click="activeTab = 'range'"
          >
            تولید بازه‌ای
          </button>
          <button
            type="button"
            :class="['tab', { active: activeTab === 'complex' }]"
            @click="activeTab = 'complex'"
          >
            تولید پیچیده (تصادفی)
          </button>
          <button
            type="button"
            :class="['tab', { active: activeTab === 'import' }]"
            @click="activeTab = 'import'"
          >
            ورود از اکسل
          </button>
        </div>

        <template v-if="activeTab === 'range'">
          <p class="hint">پیشوند و بازه عددی را وارد کنید. مثال: M از 100 تا 900000</p>
          <div class="form-grid">
            <label>
              پیشوند
              <input v-model="prefix" type="text" />
            </label>
            <label>
              شماره شروع
              <input v-model.number="start" type="number" min="0" />
            </label>
            <label>
              شماره پایان
              <input v-model.number="end" type="number" min="0" />
            </label>
            <label>
              عرض صفر
              <input v-model.number="padWidth" type="number" min="0" />
            </label>
            <label>
              امتیاز قرعه‌کشی
              <input v-model.number="score" type="number" min="1" />
            </label>
            <label>
              دسته سریال
              <select v-model="category">
                <option
                  v-for="option in SERIAL_CATEGORY_OPTIONS"
                  :key="option.value"
                  :value="option.value"
                >
                  {{ option.label }}
                </option>
              </select>
            </label>
            <label>
              مدت گارانتی (ماه)
              <input v-model.number="warrantyMonths" type="number" min="0" placeholder="مثال: 6" />
            </label>
            <label>
              گارانتی کیلومتر
              <input v-model.number="warrantyKm" type="number" min="0" placeholder="مثال: 60000" />
            </label>
            <div class="feature-toggle-panel full-width" :class="{ on: imageVerificationRequired }">
              <UiSwitch
                v-model="imageVerificationRequired"
                label="تأیید عکس محصول"
                description="پس از ثبت سریال، مشتری باید عکس محصول را بفرستد تا امتیاز قرعه‌کشی پس از تأیید ادمین اعطا شود."
              />
            </div>
            <label class="full-width">
              توضیحات گروه (اختیاری)
              <textarea v-model="description" rows="2" />
            </label>
          </div>
          <p class="hint">حداقل یکی از مدت گارانتی یا کیلومتر گارانتی الزامی است.</p>
          <p class="hint">تعداد: {{ count }}</p>
          <p v-if="error" class="error">{{ error }}</p>
          <p v-if="result" class="success">{{ result }}</p>
          <button class="primary" :disabled="loading || count <= 0 || count > MAX_SERIAL_BATCH" @click="submit">
            {{ loading ? 'در حال تولید...' : 'تولید سریال‌ها' }}
          </button>
        </template>

        <template v-else-if="activeTab === 'complex'">
          <div class="import-guide">
            <p class="hint">
              در این حالت، بخش اصلی سریال به‌صورت
              <strong>تصادفی و غیرقابل حدس</strong>
              از ترکیب حروف و ارقام ساخته می‌شود. موقعیت حروف در بخش تصادفی هر بار جابه‌جا
              می‌شود تا الگوی ثابتی نداشته باشد.
            </p>
            <ul class="import-guide-list">
              <li>
                <strong>بدون پیشوند:</strong>
                کل سریال فقط همان بخش تصادفی است؛ مثلاً
                <code dir="ltr">7K2M9P4A</code>
              </li>
              <li>
                <strong>با پیشوند ثابت:</strong>
                یک متن ثابت (مثل
                <code dir="ltr">G</code>
                یا
                <code dir="ltr">PR</code>
                ) در
                <em>ابتدای همه سریال‌ها</em>
                می‌آید و بعد از آن بخش تصادفی قرار می‌گیرد؛ مثلاً
                <code dir="ltr">G7K2M9P4</code>
              </li>
              <li>
                پیشوند فقط حروف انگلیسی و رقم است و برای همه سریال‌های این گروه یکسان
                می‌ماند.
              </li>
              <li>
                تعداد حروف و ارقام مربوط به
                <strong>بخش تصادفی</strong>
                است (بدون احتساب پیشوند) و باید بین ۶ تا ۳۲ باشد.
              </li>
              <li>
                طول نهایی سریال (پیشوند + بخش تصادفی) حداکثر ۳۲ کاراکتر است.
              </li>
            </ul>
          </div>

          <div
            class="feature-toggle-panel full-width"
            :class="{ on: complexUsePrefix }"
            style="margin-bottom: 1rem"
          >
            <UiSwitch
              v-model="complexUsePrefix"
              label="پیشوند ثابت"
              description="اگر روشن باشد، متنی که وارد می‌کنید در ابتدای همه سریال‌ها ثابت می‌ماند. اگر خاموش باشد، سریال فقط بخش تصادفی خواهد بود."
            />
          </div>

          <div class="form-grid">
            <label v-if="complexUsePrefix" class="full-width">
              متن پیشوند
              <input
                v-model="complexPrefix"
                type="text"
                maxlength="16"
                placeholder="مثال: G"
                dir="ltr"
                autocomplete="off"
              />
              <span class="field-hint">
                فقط حروف انگلیسی و رقم — مثلاً
                <code dir="ltr">G</code>
                یا
                <code dir="ltr">AB12</code>
              </span>
            </label>
            <label>
              تعداد سریال
              <input v-model.number="complexCount" type="number" min="1" :max="MAX_SERIAL_BATCH" />
            </label>
            <label>
              تعداد حروف (بخش تصادفی)
              <input v-model.number="letterCount" type="number" min="0" max="32" />
            </label>
            <label>
              تعداد ارقام (بخش تصادفی)
              <input v-model.number="digitCount" type="number" min="0" max="32" />
            </label>
            <label>
              امتیاز قرعه‌کشی
              <input v-model.number="complexScore" type="number" min="1" />
            </label>
            <label>
              دسته سریال
              <select v-model="complexCategory">
                <option
                  v-for="option in SERIAL_CATEGORY_OPTIONS"
                  :key="option.value"
                  :value="option.value"
                >
                  {{ option.label }}
                </option>
              </select>
            </label>
            <label>
              مدت گارانتی (ماه)
              <input
                v-model.number="complexWarrantyMonths"
                type="number"
                min="0"
                placeholder="مثال: 6"
              />
            </label>
            <label>
              گارانتی کیلومتر
              <input
                v-model.number="complexWarrantyKm"
                type="number"
                min="0"
                placeholder="مثال: 60000"
              />
            </label>
            <div
              class="feature-toggle-panel full-width"
              :class="{ on: complexImageVerificationRequired }"
            >
              <UiSwitch
                v-model="complexImageVerificationRequired"
                label="تأیید عکس محصول"
                description="پس از ثبت سریال، مشتری باید عکس محصول را بفرستد تا امتیاز قرعه‌کشی پس از تأیید ادمین اعطا شود."
              />
            </div>
            <label class="full-width">
              توضیحات گروه (اختیاری)
              <textarea v-model="complexDescription" rows="2" />
            </label>
          </div>

          <div class="complex-preview" dir="rtl">
            <p class="hint">حداقل یکی از مدت گارانتی یا کیلومتر گارانتی الزامی است.</p>
            <p class="hint">
              <template v-if="complexUsePrefix && complexNormalizedPrefix">
                پیشوند:
                <code dir="ltr">{{ complexNormalizedPrefix }}</code>
                •
              </template>
              <template v-else-if="complexUsePrefix"> پیشوند: (وارد نشده) • </template>
              <template v-else> پیشوند: ندارد • </template>
              بخش تصادفی: {{ complexLength }} کاراکتر ({{ letterCount }} حرف +
              {{ digitCount }} رقم) • طول نهایی: {{ complexTotalLength }}
            </p>
            <p v-if="complexLength > 0" class="hint complex-example">
              نمونه شکل سریال:
              <code dir="ltr">{{ complexExample || '—' }}</code>
              <span class="field-hint"> (فقط برای نمایش؛ مقدار واقعی تصادفی است) </span>
            </p>
            <p class="hint">هدف تولید: {{ complexCount.toLocaleString('fa-IR') }} سریال</p>
          </div>

          <p v-if="complexError" class="error">{{ complexError }}</p>
          <p v-if="complexResult" class="success">{{ complexResult }}</p>
          <button
            class="primary"
            :disabled="complexLoading || !complexValid"
            @click="submitComplex"
          >
            {{ complexLoading ? 'در حال تولید...' : 'تولید سریال‌های تصادفی' }}
          </button>
        </template>

        <template v-else>
          <div class="import-guide">
            <p class="hint">
              فایل اکسل (.xlsx / .xls / .csv) را انتخاب کنید. فقط
              <strong>ستون اول</strong>
              به‌عنوان شماره سریال خوانده می‌شود؛ ستون‌های دیگر نادیده گرفته می‌شوند.
            </p>
            <ul class="import-guide-list">
              <li>هر ردیف در ستون اول یک سریال است (حروف و ارقام، حداکثر ۳۲ کاراکتر).</li>
              <li>ردیف‌های خالی رد می‌شوند.</li>
              <li>سریال‌های تکراری داخل فایل یا موجود در سیستم ثبت نمی‌شوند.</li>
              <li>حداکثر {{ MAX_SERIAL_BATCH.toLocaleString('fa-IR') }} سریال در هر ورود.</li>
            </ul>
          </div>
          <div class="form-grid">
            <label class="full-width">
              فایل اکسل
              <input
                type="file"
                accept=".xlsx,.xls,.csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.ms-excel,text/csv"
                :disabled="importParsing || importLoading"
                @change="onImportFileChange"
              />
            </label>
            <label>
              امتیاز قرعه‌کشی
              <input v-model.number="importScore" type="number" min="1" />
            </label>
            <label>
              دسته سریال
              <select v-model="importCategory">
                <option
                  v-for="option in SERIAL_CATEGORY_OPTIONS"
                  :key="option.value"
                  :value="option.value"
                >
                  {{ option.label }}
                </option>
              </select>
            </label>
            <label>
              مدت گارانتی (ماه)
              <input
                v-model.number="importWarrantyMonths"
                type="number"
                min="0"
                placeholder="مثال: 6"
              />
            </label>
            <label>
              گارانتی کیلومتر
              <input
                v-model.number="importWarrantyKm"
                type="number"
                min="0"
                placeholder="مثال: 60000"
              />
            </label>
            <div
              class="feature-toggle-panel full-width"
              :class="{ on: importImageVerificationRequired }"
            >
              <UiSwitch
                v-model="importImageVerificationRequired"
                label="تأیید عکس محصول"
                description="پس از ثبت سریال، مشتری باید عکس محصول را بفرستد تا امتیاز قرعه‌کشی پس از تأیید ادمین اعطا شود."
              />
            </div>
            <label class="full-width">
              توضیحات گروه (اختیاری)
              <textarea v-model="importDescription" rows="2" />
            </label>
          </div>
          <p class="hint">حداقل یکی از مدت گارانتی یا کیلومتر گارانتی الزامی است.</p>
          <p v-if="importParsing" class="hint">در حال خواندن فایل...</p>
          <p v-else-if="importFileName" class="hint">
            فایل: {{ importFileName }} • سریال معتبر: {{ importSerials.length }}
            <template v-if="importEmptyRows > 0"> • ردیف خالی: {{ importEmptyRows }}</template>
          </p>
          <p v-if="importInvalidSample.length" class="error">
            نمونه نامعتبر (رد شده): {{ importInvalidSample.join('، ') }}
          </p>
          <p v-if="importPreview.length" class="hint">
            پیش‌نمایش: {{ importPreview.join('، ')
            }}{{ importSerials.length > importPreview.length ? ' …' : '' }}
          </p>
          <p v-if="importError" class="error">{{ importError }}</p>
          <p v-if="importResult" class="success">{{ importResult }}</p>
          <div class="group-card-actions">
            <button
              type="button"
              class="primary"
              :disabled="importLoading || !importValid"
              @click="submitImport"
            >
              {{ importLoading ? 'در حال ورود...' : 'ورود سریال‌ها' }}
            </button>
            <button
              type="button"
              class="secondary"
              :disabled="importLoading || (!importFileName && importSerials.length === 0)"
              @click="clearImportFile"
            >
              پاک کردن فایل
            </button>
          </div>
        </template>
      </div>

      <section class="section">
        <h2>گروه‌های تولید شده</h2>
        <p v-if="groupsLoading" class="hint">در حال بارگذاری...</p>
        <p v-else-if="groupsError" class="error">{{ groupsError }}</p>
        <p v-else-if="groups.length === 0" class="hint">هنوز گروهی ثبت نشده است</p>
        <div v-else class="group-cards-grid">
          <SerialGroupCard
            v-for="group in groups"
            :key="group.id"
            :group="group"
            :deleting="deleting && deleteTarget?.id === group.id"
            @remove="requestDelete(group)"
            @updated="onGroupUpdated"
          />
        </div>
      </section>
    </div>

    <ConfirmModal
      :open="deleteTarget !== null"
      title="حذف گروه سریال"
      :message="deleteMessage"
      :loading="deleting"
      @confirm="confirmDelete"
      @cancel="cancelDelete"
    />

    <DuplicateSerialWarningModal
      :open="duplicateWarningOpen"
      :duplicate-count="duplicateCount"
      :duplicate-ranges="duplicateRanges"
      :message="duplicateMessage"
      @close="duplicateWarningOpen = false"
    />
  </div>
</template>
