<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import NavBar from '@/components/NavBar.vue'
import { fetchSerials } from '@/api/client'
import type { SerialRow } from '@/api/types'
import { formatPhoneForDisplay } from '@/utils/formatPhone'

interface CustomerRow {
  phone: string
  registrationCount: number
  latestDate: string | undefined
  latestCity: string | undefined
  latestKm: number | undefined
}

const searchQuery = ref('')
const page = ref(1)
const limit = ref(50)
const total = ref(0)
const serials = ref<SerialRow[]>([])
const loading = ref(false)
const error = ref('')

// Customers are deduplicated client-side by phone number within the current
// batch of registered serials returned by the server.
const customers = computed<CustomerRow[]>(() => {
  const map = new Map<string, CustomerRow>()
  for (const row of serials.value) {
    if (!row.phone) continue
    const existing = map.get(row.phone)
    if (existing) {
      existing.registrationCount += 1
      // Keep the most recent registration's details (rows arrive newest-first
      // from the backend, so the first seen for a phone is the latest).
      continue
    }
    map.set(row.phone, {
      phone: row.phone,
      registrationCount: 1,
      latestDate: row.date,
      latestCity: row.city,
      latestKm: row.km,
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

onMounted(() => {
  void load()
})
</script>

<template>
  <div>
    <NavBar />
    <div class="page">
      <div class="card">
        <h1>باشگاه مشتریان</h1>
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
                <th>تعداد ثبت</th>
                <th>آخرین تاریخ</th>
                <th>شهر</th>
                <th>آخرین کیلومتر</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="loading && customers.length === 0">
                <td colspan="5" class="empty">در حال بارگذاری…</td>
              </tr>
              <tr v-else-if="customers.length === 0">
                <td colspan="5" class="empty">مشتری‌ای یافت نشد.</td>
              </tr>
              <tr v-for="customer in customers" :key="customer.phone">
                <td>{{ formatPhoneForDisplay(customer.phone) }}</td>
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
      </div>
    </div>
  </div>
</template>
