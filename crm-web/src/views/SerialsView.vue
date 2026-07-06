<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import NavBar from '@/components/NavBar.vue'
import ConfirmModal from '@/components/ConfirmModal.vue'
import DuplicateSerialWarningModal from '@/components/DuplicateSerialWarningModal.vue'
import SerialGroupCard from '@/components/SerialGroupCard.vue'
import { createSerialBatch, deleteSerialGroup, fetchSerialGroups } from '@/api/client'
import { isDuplicateSerialError } from '@/api/types'
import type { DuplicateRangeItem, SerialGroup } from '@/api/types'

const prefix = ref('M')
const start = ref(100)
const end = ref(200)
const padWidth = ref(0)
const city = ref('')
const km = ref<number | ''>('')
const description = ref('')
const score = ref(1)
const loading = ref(false)
const error = ref('')
const result = ref('')
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
  loading.value = true
  try {
    const response = await createSerialBatch({
      prefix: prefix.value,
      start: start.value,
      end: end.value,
      padWidth: padWidth.value,
      city: city.value || undefined,
      km: km.value === '' ? undefined : Number(km.value),
      description: description.value || undefined,
      score: score.value,
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
            شهر (اختیاری)
            <input v-model="city" type="text" />
          </label>
          <label>
            کیلومتر (اختیاری)
            <input v-model="km" type="number" min="0" />
          </label>
          <label>
            امتیاز قرعه‌کشی
            <input v-model.number="score" type="number" min="1" />
          </label>
          <label class="full-width">
            توضیحات گروه (اختیاری)
            <textarea v-model="description" rows="2" />
          </label>
        </div>
        <p class="hint">تعداد: {{ count }}</p>
        <p v-if="error" class="error">{{ error }}</p>
        <p v-if="result" class="success">{{ result }}</p>
        <button class="primary" :disabled="loading || count <= 0 || count > 10000" @click="submit">
          {{ loading ? 'در حال تولید...' : 'تولید سریال‌ها' }}
        </button>
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
