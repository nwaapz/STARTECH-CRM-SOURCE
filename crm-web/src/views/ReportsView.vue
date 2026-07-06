<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import NavBar from '@/components/NavBar.vue'
import SerialTable from '@/components/SerialTable.vue'
import JalaliDateTimePicker from '@/components/JalaliDateTimePicker.vue'
import { fetchSerials, sendCompletionReminder } from '@/api/client'
import type { SerialRow } from '@/api/types'
import { formatPhoneForDisplay } from '@/utils/formatPhone'

const fromDate = ref('')
const toDate = ref('')
const searchQuery = ref('')
const page = ref(1)
const limit = ref(50)
const total = ref(0)
const items = ref<SerialRow[]>([])
const loading = ref(false)
const error = ref('')
const sendingReminderId = ref<number | null>(null)
const lastSearchUsedDefaultEnd = ref(false)

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

async function load() {
  loading.value = true
  error.value = ''
  try {
    const response = await fetchSerials({
      registered: true,
      from: fromDate.value || undefined,
      to: toDate.value || undefined,
      defaultEndToday: lastSearchUsedDefaultEnd.value && !toDate.value ? true : undefined,
      search: searchQuery.value || undefined,
      page: page.value,
      limit: limit.value,
    })
    items.value = response.items
    total.value = response.total
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا'
  } finally {
    loading.value = false
  }
}

async function runSearch() {
  page.value = 1
  lastSearchUsedDefaultEnd.value = !toDate.value
  await load()
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

function exportCsv() {
  const header = ['serial', 'phone', 'date', 'city', 'km']
  const rows = items.value.map((row) =>
    [
      row.serial,
      row.phone ? formatPhoneForDisplay(row.phone) : '',
      row.date ?? '',
      row.city ?? '',
      row.km ?? '',
    ].join(','),
  )
  const csv = [header.join(','), ...rows].join('\n')
  const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = 'registered-serials.csv'
  link.click()
  URL.revokeObjectURL(url)
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
          تاریخ را از تقویم شمسی انتخاب کنید (بدون ساعت). با کلیک روی «جستجو»، اگر «تا تاریخ» خالی
          باشد تا پایان امروز فیلتر می‌شود.
        </p>
        <div class="form-grid">
          <label>
            از تاریخ
            <JalaliDateTimePicker v-model="fromDate" placeholder="انتخاب تاریخ شروع" />
          </label>
          <label>
            تا تاریخ
            <JalaliDateTimePicker v-model="toDate" placeholder="خالی = تا پایان امروز (در جستجو)" />
          </label>
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
          <button class="secondary" :disabled="items.length === 0" @click="exportCsv">
            خروجی CSV
          </button>
        </div>
        <p v-if="error" class="error">{{ error }}</p>
        <p class="hint">{{ dateRangeHint }} — نتایج: {{ total }} — صفحه {{ page }}</p>
        <SerialTable
          :items="items"
          show-reminder-actions
          :sending-reminder-id="sendingReminderId"
          @send-reminder="handleSendReminder"
        />
        <div class="actions">
          <button class="secondary" :disabled="page <= 1" @click="prevPage">قبلی</button>
          <button class="secondary" :disabled="page * limit >= total" @click="nextPage">
            بعدی
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
