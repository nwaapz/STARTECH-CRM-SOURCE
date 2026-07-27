<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import NavBar from '@/components/NavBar.vue'
import UiSwitch from '@/components/UiSwitch.vue'
import {
  fetchReferralCustomers,
  fetchReferralSettings,
  updateReferralSettings,
} from '@/api/client'
import type { ReferralCustomer, ReferralSettings } from '@/api/types'
import { formatPhoneForDisplay } from '@/utils/formatPhone'

const settings = ref<ReferralSettings>({
  enabled: false,
  basePoints: 50,
  decayPercent: 50,
  maxDepth: 10,
})
const settingsLoading = ref(false)
const settingsSaving = ref(false)
const settingsError = ref('')
const settingsSuccess = ref('')

const items = ref<ReferralCustomer[]>([])
const total = ref(0)
const page = ref(1)
const limit = 50
const search = ref('')
const listLoading = ref(false)
const listError = ref('')

const phoneByCode = computed(() => {
  const map = new Map<string, string>()
  for (const c of items.value) map.set(c.referralCode, c.phone)
  return map
})

const previewLevels = computed(() => {
  const out: { level: number; points: number }[] = []
  let points = settings.value.basePoints
  for (let level = 1; level <= settings.value.maxDepth; level++) {
    const p = Math.floor(points)
    if (p < 1) break
    out.push({ level, points: p })
    points = (points * settings.value.decayPercent) / 100
  }
  return out
})

async function loadSettings() {
  settingsLoading.value = true
  settingsError.value = ''
  try {
    settings.value = await fetchReferralSettings()
  } catch (e) {
    settingsError.value = e instanceof Error ? e.message : 'خطا در بارگذاری تنظیمات'
  } finally {
    settingsLoading.value = false
  }
}

async function saveSettings() {
  settingsSaving.value = true
  settingsError.value = ''
  settingsSuccess.value = ''
  try {
    settings.value = await updateReferralSettings({
      enabled: settings.value.enabled,
      basePoints: Math.max(0, Math.round(settings.value.basePoints)),
      decayPercent: Math.min(100, Math.max(0, Math.round(settings.value.decayPercent))),
      maxDepth: Math.min(50, Math.max(1, Math.round(settings.value.maxDepth))),
    })
    settingsSuccess.value = 'تنظیمات ذخیره شد.'
  } catch (e) {
    settingsError.value = e instanceof Error ? e.message : 'خطا در ذخیره تنظیمات'
  } finally {
    settingsSaving.value = false
  }
}

async function loadCustomers() {
  listLoading.value = true
  listError.value = ''
  try {
    const response = await fetchReferralCustomers({
      search: search.value.trim() || undefined,
      page: page.value,
      limit,
    })
    items.value = response.items
    total.value = response.total
  } catch (e) {
    listError.value = e instanceof Error ? e.message : 'خطا در بارگذاری فهرست'
  } finally {
    listLoading.value = false
  }
}

function inviterLabel(customer: ReferralCustomer): string {
  if (!customer.referredBy) return '—'
  return formatPhoneForDisplay(customer.referredBy)
}

let searchTimer: ReturnType<typeof setTimeout> | undefined
watch(search, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    page.value = 1
    void loadCustomers()
  }, 350)
})

onMounted(() => {
  void loadSettings()
  void loadCustomers()
})
</script>

<template>
  <div>
    <NavBar />
    <div class="page">
      <div class="card">
        <h1>سیستم دعوت چندسطحی (پلکانی)</h1>
        <p class="hint">
          هر مشتری یک کد دعوت اختصاصی دارد. وقتی فرد دعوت‌شده خرید (ثبت سریال) می‌کند، دعوت‌کننده و
          دعوت‌کنندهٔ او و به همین ترتیب تا بالای زنجیره، امتیاز می‌گیرند. امتیاز هر سطح با ضریب
          کاهش کم می‌شود تا به صفر برسد یا به حداکثر عمق تعیین‌شده.
        </p>

        <p v-if="settingsError" class="error">{{ settingsError }}</p>
        <p v-if="settingsSuccess" class="success">{{ settingsSuccess }}</p>

        <UiSwitch
          v-model="settings.enabled"
          label="فعال‌سازی سیستم دعوت پلکانی"
          description="با فعال بودن، کد دعوت در لینک‌های دعوت ربات قرار می‌گیرد و امتیازها هنگام خرید محاسبه می‌شوند."
          :disabled="settingsLoading || settingsSaving"
        />

        <div class="fields">
          <label>
            امتیاز سطح اول
            <input
              v-model.number="settings.basePoints"
              type="number"
              min="0"
              :disabled="settingsLoading || settingsSaving"
            />
          </label>
          <label>
            ضریب کاهش هر سطح (٪)
            <input
              v-model.number="settings.decayPercent"
              type="number"
              min="0"
              max="100"
              :disabled="settingsLoading || settingsSaving"
            />
          </label>
          <label>
            حداکثر عمق زنجیره
            <input
              v-model.number="settings.maxDepth"
              type="number"
              min="1"
              max="50"
              :disabled="settingsLoading || settingsSaving"
            />
          </label>
        </div>

        <div class="preview">
          <span class="hint">پیش‌نمایش امتیاز هر سطح:</span>
          <span v-for="row in previewLevels" :key="row.level" class="chip">
            سطح {{ row.level.toLocaleString('fa-IR') }}: {{ row.points.toLocaleString('fa-IR') }}
          </span>
          <span v-if="previewLevels.length === 0" class="hint">—</span>
        </div>

        <div class="actions">
          <button class="primary" :disabled="settingsSaving || settingsLoading" @click="saveSettings">
            {{ settingsSaving ? 'در حال ذخیره…' : 'ذخیره تنظیمات' }}
          </button>
        </div>
      </div>

      <div class="card">
        <h2>مشتریان و امتیازها</h2>
        <div class="filters">
          <label>
            جستجو (شماره یا کد)
            <input v-model="search" type="text" placeholder="مثلاً 0912… یا ABCD1234" />
          </label>
          <button class="secondary" :disabled="listLoading" @click="loadCustomers">بروزرسانی</button>
        </div>

        <p v-if="listError" class="error">{{ listError }}</p>
        <p v-if="listLoading" class="hint">در حال بارگذاری…</p>
        <p v-else-if="items.length === 0" class="hint">موردی یافت نشد.</p>
        <table v-else class="referral-table">
          <thead>
            <tr>
              <th>شماره</th>
              <th>کد دعوت</th>
              <th>دعوت‌کننده</th>
              <th>دعوت مستقیم</th>
              <th>امتیاز</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in items" :key="c.phone">
              <td>{{ formatPhoneForDisplay(c.phone) }}</td>
              <td><code>{{ c.referralCode }}</code></td>
              <td>{{ inviterLabel(c) }}</td>
              <td>{{ c.directDownline.toLocaleString('fa-IR') }}</td>
              <td><strong>{{ c.score.toLocaleString('fa-IR') }}</strong></td>
            </tr>
          </tbody>
        </table>

        <div class="actions">
          <button class="secondary" :disabled="page <= 1 || listLoading" @click="page--; loadCustomers()">
            قبلی
          </button>
          <span class="hint">
            صفحه {{ page.toLocaleString('fa-IR') }} —
            {{ total.toLocaleString('fa-IR') }} مشتری
          </span>
          <button
            class="secondary"
            :disabled="page * limit >= total || listLoading"
            @click="page++; loadCustomers()"
          >
            بعدی
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.fields {
  display: flex;
  flex-wrap: wrap;
  gap: 1rem;
  margin: 1rem 0;
}

.fields label {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  font-size: 0.9rem;
  font-weight: 600;
}

.fields input {
  width: 10rem;
  padding: 0.5rem 0.6rem;
  border: 1px solid #cfd8dc;
  border-radius: 8px;
  font: inherit;
}

.preview {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 1rem;
}

.chip {
  background: #e8f5e9;
  color: #1b5e20;
  border-radius: 999px;
  padding: 0.25rem 0.7rem;
  font-size: 0.85rem;
}

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 1rem;
  align-items: flex-end;
  margin-bottom: 1rem;
}

.filters label {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  font-size: 0.9rem;
  font-weight: 600;
}

.filters input {
  padding: 0.5rem 0.6rem;
  border: 1px solid #cfd8dc;
  border-radius: 8px;
  font: inherit;
  min-width: 14rem;
}

.referral-table {
  width: 100%;
  border-collapse: collapse;
}

.referral-table th,
.referral-table td {
  text-align: right;
  padding: 0.55rem 0.6rem;
  border-bottom: 1px solid #eceff1;
  font-size: 0.9rem;
}

.referral-table th {
  color: #546e7a;
  font-weight: 700;
}

.referral-table code {
  background: #f5f5f5;
  padding: 0.1rem 0.4rem;
  border-radius: 6px;
}

.success {
  color: #2e7d32;
  font-weight: 600;
}

.actions {
  display: flex;
  gap: 1rem;
  align-items: center;
  margin-top: 1rem;
}
</style>
