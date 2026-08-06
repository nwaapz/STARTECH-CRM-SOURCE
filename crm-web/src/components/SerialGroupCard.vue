<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import DuplicateSerialWarningModal from '@/components/DuplicateSerialWarningModal.vue'
import UiSwitch from '@/components/UiSwitch.vue'
import type {
  ClubAutoMessageRule,
  DuplicateRangeItem,
  ExtendSerialGroupRangeRequest,
  SerialGroup,
  SerialGroupCategory,
} from '@/api/types'
import { extendSerialGroupRange, fetchSerialGroupSerials, updateSerialGroup } from '@/api/client'
import { isDuplicateSerialError } from '@/api/types'
import { formatDateTimeFa } from '@/utils/formatCommunication'
import { downloadSerialsAsExcel } from '@/utils/exportSerialsXlsx'
import {
  findLinkedWarrantyRules,
  loadClubAutoRules,
  syncWarrantyEndRule,
  syncWarrantyPreRule,
} from '@/utils/warrantyClubAuto'

const SERIAL_CATEGORY_OPTIONS: { value: SerialGroupCategory; label: string }[] = [
  { value: 'end_user_client', label: 'مشتری نهایی' },
  { value: 'seller_to_end_user', label: 'فروشنده به مشتری نهایی' },
]

function categoryLabel(category?: SerialGroupCategory): string {
  return (
    SERIAL_CATEGORY_OPTIONS.find((option) => option.value === category)?.label ??
    SERIAL_CATEGORY_OPTIONS[0].label
  )
}

function warrantyLabel(months?: number | null, km?: number | null): string {
  const parts: string[] = []
  if (months != null && months > 0) parts.push(`${months} ماه`)
  if (km != null && km > 0) parts.push(`${km} کیلومتر`)
  return parts.length ? parts.join(' یا ') : '—'
}

const props = defineProps<{
  group: SerialGroup
  deleting?: boolean
}>()

const emit = defineEmits<{
  remove: []
  updated: [group: SerialGroup]
}>()

const editing = ref(false)
const saving = ref(false)
const extending = ref(false)
const exporting = ref(false)
const error = ref('')
const duplicateWarningOpen = ref(false)
const duplicateCount = ref(0)
const duplicateRanges = ref<DuplicateRangeItem[]>([])
const duplicateMessage = ref('')

const editDescription = ref('')
const editScore = ref(1)
const editCategory = ref<SerialGroupCategory>('end_user_client')
const editWarrantyMonths = ref<number | ''>('')
const editWarrantyKm = ref<number | ''>('')
const editImageVerificationRequired = ref(false)
const extendMode = ref<'count' | 'target'>('count')
const addBelow = ref<number | ''>('')
const addAbove = ref<number | ''>('')
const newStart = ref<number | ''>('')
const newEnd = ref<number | ''>('')

const warrantyEndEnabled = ref(false)
const warrantyPreEnabled = ref(false)
const warrantyPreDays = ref(7)
const warrantySyncing = ref(false)
const endRule = ref<ClubAutoMessageRule | undefined>()
const preRule = ref<ClubAutoMessageRule | undefined>()

const createdLabel = computed(() => {
  const { date, time } = formatDateTimeFa(props.group.createdAt)
  return `${date} ${time}`
})

const isComplexGroup = computed(() => props.group.generationMode === 'complex')
const isImportGroup = computed(() => props.group.generationMode === 'import')
const isRangeGroup = computed(
  () => !isComplexGroup.value && !isImportGroup.value,
)
const modeBadgeLabel = computed(() => {
  if (isComplexGroup.value) return 'تصادفی'
  if (isImportGroup.value) return 'وارداتی'
  return ''
})
const hasWarrantyMonths = computed(
  () => props.group.warrantyMonths != null && props.group.warrantyMonths > 0,
)

watch(
  () => props.group,
  (group) => {
    resetForm(group)
  },
  { immediate: true },
)

onMounted(() => {
  void refreshWarrantyRules()
})

watch(
  () => props.group.id,
  () => {
    void refreshWarrantyRules()
  },
)

async function refreshWarrantyRules() {
  try {
    const rules = await loadClubAutoRules()
    const linked = findLinkedWarrantyRules(rules, props.group.id)
    endRule.value = linked.end
    preRule.value = linked.pre
    warrantyEndEnabled.value = !!linked.end
    warrantyPreEnabled.value = !!linked.pre
    if (linked.pre && linked.pre.offsetDays < 0) {
      warrantyPreDays.value = Math.abs(linked.pre.offsetDays)
    }
  } catch {
    /* ignore */
  }
}

async function onWarrantyEndToggle(next: boolean) {
  if (!hasWarrantyMonths.value && next) {
    error.value = 'ابتدا مدت گارانتی (ماه) را در ویرایش گروه ذخیره کنید'
    warrantyEndEnabled.value = false
    return
  }
  warrantySyncing.value = true
  error.value = ''
  try {
    const rule = await syncWarrantyEndRule({
      group: props.group,
      enabled: next,
      existing: endRule.value,
    })
    endRule.value = rule ?? undefined
    warrantyEndEnabled.value = !!rule
  } catch (e) {
    warrantyEndEnabled.value = !!endRule.value
    error.value = e instanceof Error ? e.message : 'خطا در تنظیم پیام پایان گارانتی'
  } finally {
    warrantySyncing.value = false
  }
}

async function onWarrantyPreToggle(next: boolean) {
  if (!hasWarrantyMonths.value && next) {
    error.value = 'ابتدا مدت گارانتی (ماه) را در ویرایش گروه ذخیره کنید'
    warrantyPreEnabled.value = false
    return
  }
  warrantySyncing.value = true
  error.value = ''
  try {
    const rule = await syncWarrantyPreRule({
      group: props.group,
      enabled: next,
      daysBefore: warrantyPreDays.value,
      existing: preRule.value,
    })
    preRule.value = rule ?? undefined
    warrantyPreEnabled.value = !!rule
  } catch (e) {
    warrantyPreEnabled.value = !!preRule.value
    error.value = e instanceof Error ? e.message : 'خطا در تنظیم یادآوری گارانتی'
  } finally {
    warrantySyncing.value = false
  }
}

async function onWarrantyPreDaysChange() {
  if (!warrantyPreEnabled.value) return
  await onWarrantyPreToggle(true)
}

function resetForm(group: SerialGroup) {
  editDescription.value = group.description ?? ''
  editScore.value = group.score
  editCategory.value = group.category ?? 'end_user_client'
  editWarrantyMonths.value = group.warrantyMonths ?? ''
  editWarrantyKm.value = group.warrantyKm ?? ''
  editImageVerificationRequired.value = !!group.imageVerificationRequired
  addBelow.value = ''
  addAbove.value = ''
  newStart.value = ''
  newEnd.value = ''
  error.value = ''
}

function toggleEdit() {
  editing.value = !editing.value
  if (editing.value) {
    resetForm(props.group)
  }
}

async function saveMetadata() {
  if (editScore.value < 1) {
    error.value = 'امتیاز باید حداقل ۱ باشد'
    return
  }
  const months =
    editWarrantyMonths.value === '' || Number(editWarrantyMonths.value) <= 0
      ? null
      : Number(editWarrantyMonths.value)
  const km =
    editWarrantyKm.value === '' || Number(editWarrantyKm.value) <= 0
      ? null
      : Number(editWarrantyKm.value)
  if (months == null && km == null) {
    error.value = 'حداقل یکی از مدت گارانتی (ماه) یا کیلومتر گارانتی را وارد کنید'
    return
  }
  saving.value = true
  error.value = ''
  try {
    const updated = await updateSerialGroup(props.group.id, {
      description: editDescription.value || null,
      score: editScore.value,
      category: editCategory.value,
      warrantyMonths: months,
      warrantyKm: km,
      imageVerificationRequired: editImageVerificationRequired.value,
    })
    emit('updated', updated)
    if (warrantyEndEnabled.value && months != null && months > 0) {
      endRule.value =
        (await syncWarrantyEndRule({
          group: updated,
          enabled: true,
          existing: endRule.value,
        })) ?? undefined
    }
    if (warrantyPreEnabled.value && months != null && months > 0) {
      preRule.value =
        (await syncWarrantyPreRule({
          group: updated,
          enabled: true,
          daysBefore: warrantyPreDays.value,
          existing: preRule.value,
        })) ?? undefined
    }
    editing.value = false
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در ذخیره'
  } finally {
    saving.value = false
  }
}

async function exportExcel() {
  if (props.group.total <= 0) {
    error.value = 'سریالی برای خروجی وجود ندارد'
    return
  }
  exporting.value = true
  error.value = ''
  try {
    const response = await fetchSerialGroupSerials(props.group.id)
    if (response.serials.length === 0) {
      error.value = 'سریالی برای خروجی وجود ندارد'
      return
    }
    downloadSerialsAsExcel(response.serials, props.group.rangeLabel)
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در خروجی اکسل'
  } finally {
    exporting.value = false
  }
}

function buildExtendBody(): ExtendSerialGroupRangeRequest | null {
  if (extendMode.value === 'count') {
    const body: ExtendSerialGroupRangeRequest = {}
    if (addBelow.value !== '' && addBelow.value > 0) body.addBelow = Number(addBelow.value)
    if (addAbove.value !== '' && addAbove.value > 0) body.addAbove = Number(addAbove.value)
    if (!body.addBelow && !body.addAbove) return null
    return body
  }
  const body: ExtendSerialGroupRangeRequest = {}
  if (newStart.value !== '') body.newStart = Number(newStart.value)
  if (newEnd.value !== '') body.newEnd = Number(newEnd.value)
  if (body.newStart === undefined && body.newEnd === undefined) return null
  return body
}

async function saveExtend() {
  const body = buildExtendBody()
  if (!body) {
    error.value = 'مقدار توسعه بازه را وارد کنید'
    return
  }
  extending.value = true
  error.value = ''
  try {
    const response = await extendSerialGroupRange(props.group.id, body)
    emit('updated', response.group)
    addBelow.value = ''
    addAbove.value = ''
    newStart.value = ''
    newEnd.value = ''
  } catch (e) {
    if (isDuplicateSerialError(e)) {
      duplicateCount.value = e.duplicateCount
      duplicateRanges.value = e.duplicateRanges
      duplicateMessage.value = e.message
      duplicateWarningOpen.value = true
    } else {
      error.value = e instanceof Error ? e.message : 'خطا در توسعه بازه'
    }
  } finally {
    extending.value = false
  }
}
</script>

<template>
  <article class="group-card">
    <div class="group-card-header">
      <div>
        <h3>{{ group.rangeLabel }}</h3>
        <p class="group-created">ایجاد: {{ createdLabel }}</p>
      </div>
      <div class="group-header-badges">
        <span v-if="modeBadgeLabel" class="mode-badge">{{ modeBadgeLabel }}</span>
        <span class="category-badge">{{ categoryLabel(group.category ?? 'end_user_client') }}</span>
        <span class="score-badge">امتیاز: {{ group.score }}</span>
      </div>
    </div>

    <template v-if="!editing">
      <p v-if="group.description" class="group-description">{{ group.description }}</p>
      <p v-else class="hint">بدون توضیحات</p>
      <p class="hint">گارانتی: {{ warrantyLabel(group.warrantyMonths, group.warrantyKm) }}</p>
      <span
        class="image-verify-badge"
        :class="group.imageVerificationRequired ? 'on' : 'off'"
      >
        {{ group.imageVerificationRequired ? 'تأیید عکس: فعال' : 'تأیید عکس: غیرفعال' }}
      </span>

      <div class="warranty-auto-panel">
        <div
          class="feature-toggle-panel"
          :class="{ on: warrantyEndEnabled, disabled: !hasWarrantyMonths || warrantySyncing }"
        >
          <UiSwitch
            :model-value="warrantyEndEnabled"
            label="پیامک پایان گارانتی"
            description="پس از پایان مدت گارانتی (بر اساس تاریخ ثبت)، پیام خودکار مثل باشگاه مشتریان ارسال می‌شود و کارت آن در باشگاه ظاهر می‌گردد."
            :disabled="!hasWarrantyMonths || warrantySyncing"
            @update:model-value="onWarrantyEndToggle"
          />
        </div>
        <div
          class="feature-toggle-panel"
          :class="{ on: warrantyPreEnabled, disabled: !hasWarrantyMonths || warrantySyncing }"
        >
          <UiSwitch
            :model-value="warrantyPreEnabled"
            label="یادآوری قبل از پایان گارانتی"
            description="فقط در همان روزی که دقیقاً این تعداد روز تا پایان گارانتی مانده، پیام ارسال می‌شود (بعد از آن روز دیگر ارسال نمی‌شود)."
            :disabled="!hasWarrantyMonths || warrantySyncing"
            @update:model-value="onWarrantyPreToggle"
          />
          <label class="warranty-pre-days">
            چند روز مانده
            <input
              v-model.number="warrantyPreDays"
              type="number"
              min="1"
              max="366"
              :disabled="!warrantyPreEnabled || warrantySyncing"
              @change="onWarrantyPreDaysChange"
            />
          </label>
        </div>
        <p v-if="!hasWarrantyMonths" class="hint">
          برای این پیام‌ها باید مدت گارانتی بر حسب ماه در ویرایش گروه تنظیم شده باشد.
        </p>
      </div>

      <dl class="group-stats">
        <div>
          <dt>کل</dt>
          <dd>{{ group.total }}</dd>
        </div>
        <div>
          <dt>استفاده‌شده</dt>
          <dd>{{ group.used }}</dd>
        </div>
        <div>
          <dt>استفاده‌نشده</dt>
          <dd>{{ group.unused }}</dd>
        </div>
        <div>
          <dt>درصد فروش</dt>
          <dd>{{ group.salePercentage }}%</dd>
        </div>
      </dl>
      <p v-if="error" class="error">{{ error }}</p>
      <div class="group-card-actions">
        <button type="button" class="secondary" @click="toggleEdit">ویرایش</button>
        <button
          type="button"
          class="secondary"
          :disabled="exporting || group.total <= 0"
          @click="exportExcel"
        >
          {{ exporting ? 'در حال خروجی...' : 'خروجی اکسل' }}
        </button>
        <button
          type="button"
          class="danger"
          :disabled="deleting"
          @click="emit('remove')"
        >
          {{ deleting ? 'در حال حذف...' : 'حذف گروه' }}
        </button>
      </div>
    </template>

    <template v-else>
      <div class="group-edit-section">
        <label>
          توضیحات گروه
          <textarea v-model="editDescription" rows="2" />
        </label>
        <label>
          امتیاز قرعه‌کشی
          <input v-model.number="editScore" type="number" min="1" />
        </label>
        <label>
          دسته سریال
          <select v-model="editCategory">
            <option
              v-for="option in SERIAL_CATEGORY_OPTIONS"
              :key="option.value"
              :value="option.value"
            >
              {{ option.label }}
            </option>
          </select>
        </label>
        <div
          class="feature-toggle-panel"
          :class="{ on: editImageVerificationRequired }"
        >
          <UiSwitch
            v-model="editImageVerificationRequired"
            label="تأیید عکس محصول"
            description="پس از ثبت سریال، مشتری باید عکس محصول را بفرستد تا امتیاز قرعه‌کشی پس از تأیید ادمین اعطا شود."
          />
        </div>
        <label>
          مدت گارانتی (ماه)
          <input v-model.number="editWarrantyMonths" type="number" min="0" />
        </label>
        <label>
          گارانتی کیلومتر
          <input v-model.number="editWarrantyKm" type="number" min="0" />
        </label>
        <button type="button" class="primary" :disabled="saving" @click="saveMetadata">
          {{ saving ? 'در حال ذخیره...' : 'ذخیره تغییرات' }}
        </button>
      </div>

      <div v-if="isRangeGroup" class="group-edit-section">
        <h4>توسعه بازه</h4>
        <p class="hint">بازه فعلی: {{ group.startNumber }} تا {{ group.endNumber }}</p>
        <div class="extend-mode-tabs">
          <button
            type="button"
            :class="['tab', { active: extendMode === 'count' }]"
            @click="extendMode = 'count'"
          >
            افزودن تعداد
          </button>
          <button
            type="button"
            :class="['tab', { active: extendMode === 'target' }]"
            @click="extendMode = 'target'"
          >
            تعیین مرز
          </button>
        </div>

        <div v-if="extendMode === 'count'" class="form-grid compact">
          <label>
            افزودن به پایین
            <input v-model="addBelow" type="number" min="1" placeholder="تعداد" />
          </label>
          <label>
            افزودن به بالا
            <input v-model="addAbove" type="number" min="1" placeholder="تعداد" />
          </label>
        </div>
        <div v-else class="form-grid compact">
          <label>
            شماره شروع جدید (≤ {{ group.startNumber }})
            <input v-model="newStart" type="number" min="0" :max="group.startNumber" />
          </label>
          <label>
            شماره پایان جدید (≥ {{ group.endNumber }})
            <input v-model="newEnd" type="number" :min="group.endNumber" />
          </label>
        </div>

        <button type="button" class="primary" :disabled="extending" @click="saveExtend">
          {{ extending ? 'در حال توسعه...' : 'توسعه بازه' }}
        </button>
      </div>
      <p v-else class="hint">گروه‌های تصادفی و وارداتی قابل توسعه بازه‌ای نیستند.</p>

      <p v-if="error" class="error">{{ error }}</p>

      <div class="group-card-actions">
        <button type="button" class="secondary" @click="toggleEdit">بستن</button>
        <button
          type="button"
          class="danger"
          :disabled="deleting"
          @click="emit('remove')"
        >
          حذف گروه
        </button>
      </div>
    </template>

    <DuplicateSerialWarningModal
      :open="duplicateWarningOpen"
      :duplicate-count="duplicateCount"
      :duplicate-ranges="duplicateRanges"
      :message="duplicateMessage"
      @close="duplicateWarningOpen = false"
    />
  </article>
</template>
