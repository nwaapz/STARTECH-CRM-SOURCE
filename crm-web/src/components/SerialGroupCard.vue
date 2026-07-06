<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import DuplicateSerialWarningModal from '@/components/DuplicateSerialWarningModal.vue'
import type { DuplicateRangeItem, ExtendSerialGroupRangeRequest, SerialGroup } from '@/api/types'
import { extendSerialGroupRange, updateSerialGroup } from '@/api/client'
import { isDuplicateSerialError } from '@/api/types'
import { formatDateTimeFa } from '@/utils/formatCommunication'

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
const error = ref('')
const duplicateWarningOpen = ref(false)
const duplicateCount = ref(0)
const duplicateRanges = ref<DuplicateRangeItem[]>([])
const duplicateMessage = ref('')

const editDescription = ref('')
const editScore = ref(1)
const extendMode = ref<'count' | 'target'>('count')
const addBelow = ref<number | ''>('')
const addAbove = ref<number | ''>('')
const newStart = ref<number | ''>('')
const newEnd = ref<number | ''>('')

const createdLabel = computed(() => {
  const { date, time } = formatDateTimeFa(props.group.createdAt)
  return `${date} ${time}`
})

watch(
  () => props.group,
  (group) => {
    resetForm(group)
  },
  { immediate: true },
)

function resetForm(group: SerialGroup) {
  editDescription.value = group.description ?? ''
  editScore.value = group.score
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
  saving.value = true
  error.value = ''
  try {
    const updated = await updateSerialGroup(props.group.id, {
      description: editDescription.value || null,
      score: editScore.value,
    })
    emit('updated', updated)
    editing.value = false
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در ذخیره'
  } finally {
    saving.value = false
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
      <span class="score-badge">امتیاز: {{ group.score }}</span>
    </div>

    <template v-if="!editing">
      <p v-if="group.description" class="group-description">{{ group.description }}</p>
      <p v-else class="hint">بدون توضیحات</p>
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
      <div class="group-card-actions">
        <button type="button" class="secondary" @click="toggleEdit">ویرایش</button>
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
        <button type="button" class="primary" :disabled="saving" @click="saveMetadata">
          {{ saving ? 'در حال ذخیره...' : 'ذخیره توضیحات و امتیاز' }}
        </button>
      </div>

      <div class="group-edit-section">
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
