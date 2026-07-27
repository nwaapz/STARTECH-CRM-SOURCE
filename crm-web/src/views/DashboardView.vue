<script setup lang="ts">
import NavBar from '@/components/NavBar.vue'
import ToastStack from '@/components/ToastStack.vue'
import SerialTable from '@/components/SerialTable.vue'
import SerialErrorDetailModal from '@/components/SerialErrorDetailModal.vue'
import JalaliDateSelects from '@/components/JalaliDateSelects.vue'
import { useSync } from '@/composables/useSync'
import { fetchSerials, fetchSerialErrorClients, fetchRegistrationsByDay } from '@/api/client'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import type { SerialErrorClientSummary, SerialRow } from '@/api/types'
import { formatDateTimeFa } from '@/utils/formatCommunication'
import { formatPhoneForDisplay } from '@/utils/formatPhone'
import { serialErrorReasonLabelFa } from '@/utils/formatSerialError'
import { formatSyncChangeFa } from '@/utils/formatSyncChange'
import { currentJalaliMonthRange } from '@/utils/jalali'
import { Chart, BarController, BarElement, CategoryScale, LinearScale, Tooltip } from 'chart.js'

Chart.register(BarController, BarElement, CategoryScale, LinearScale, Tooltip)

const { stats, sims, changes, connected, toasts } = useSync({ intervalMs: 1000 })

const sortedChanges = computed(() => [...changes.value].sort((a, b) => b.at - a.at))

const eventsPage = ref(1)
const eventsPageSize = 9
const eventsTotal = computed(() => sortedChanges.value.length)
const paginatedChanges = computed(() =>
  sortedChanges.value.slice(
    (eventsPage.value - 1) * eventsPageSize,
    eventsPage.value * eventsPageSize,
  ),
)

function prevEventsPage() {
  if (eventsPage.value > 1) {
    eventsPage.value -= 1
  }
}

function nextEventsPage() {
  if (eventsPage.value * eventsPageSize < eventsTotal.value) {
    eventsPage.value += 1
  }
}

const errorsPage = ref(1)
const errorsLimit = 9
const errorsTotal = ref(0)
const errorClients = ref<SerialErrorClientSummary[]>([])
const selectedErrorPhone = ref('')
const errorModalOpen = ref(false)
let errorsPollTimer: ReturnType<typeof setInterval> | undefined

async function loadErrors() {
  try {
    const response = await fetchSerialErrorClients({
      page: errorsPage.value,
      limit: errorsLimit,
    })
    errorClients.value = response.items
    errorsTotal.value = response.total
  } catch {
    /* ignore on dashboard */
  }
}

function prevErrorsPage() {
  if (errorsPage.value > 1) {
    errorsPage.value -= 1
    void loadErrors()
  }
}

function nextErrorsPage() {
  if (errorsPage.value * errorsLimit < errorsTotal.value) {
    errorsPage.value += 1
    void loadErrors()
  }
}

function openErrorDetail(phone: string) {
  selectedErrorPhone.value = phone
  errorModalOpen.value = true
}

function closeErrorDetail() {
  errorModalOpen.value = false
}

const registersPage = ref(1)
const registersLimit = 9
const registersTotal = ref(0)
const recentRows = ref<SerialRow[]>([])

const chartCanvas = ref<HTMLCanvasElement | null>(null)
let chartInstance: Chart | null = null
const monthDefault = currentJalaliMonthRange()
const chartFromDraft = ref(monthDefault.from)
const chartToDraft = ref(monthDefault.to)
const chartFrom = ref(monthDefault.from)
const chartTo = ref(monthDefault.to)
const chartLoading = ref(false)
const chartError = ref('')

function chartLabel(day: string, sameMonth: boolean): string {
  // day = YYYY/MM/DD
  if (sameMonth && day.length >= 10) {
    return day.slice(8)
  }
  if (day.length >= 10) {
    return day.slice(5) // MM/DD
  }
  return day
}

async function loadChart() {
  chartLoading.value = true
  chartError.value = ''
  try {
    const from = chartFromDraft.value.trim()
    const to = chartToDraft.value.trim()
    if (from && to && from > to) {
      chartError.value = 'تاریخ شروع نمی‌تواند بعد از تاریخ پایان باشد.'
      return
    }
    const data = await fetchRegistrationsByDay({
      from: from || undefined,
      to: to || undefined,
    })
    chartFrom.value = data.from
    chartTo.value = data.to
    chartFromDraft.value = data.from
    chartToDraft.value = data.to
    const sameMonth =
      data.from.length >= 7 && data.to.length >= 7 && data.from.slice(0, 7) === data.to.slice(0, 7)
    const labels = data.days.map((d) => chartLabel(d.day, sameMonth))
    const counts = data.days.map((d) => d.count)
    if (!chartCanvas.value) return
    if (chartInstance) chartInstance.destroy()
    chartInstance = new Chart(chartCanvas.value, {
      type: 'bar',
      data: {
        labels,
        datasets: [
          {
            label: 'ثبت‌شده',
            data: counts,
            backgroundColor: 'rgba(54, 162, 235, 0.55)',
            borderColor: 'rgba(54, 162, 235, 1)',
            borderWidth: 1,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        scales: {
          y: {
            beginAtZero: true,
            ticks: { stepSize: 1 },
          },
        },
        plugins: { legend: { display: false } },
      },
    })
  } catch (e) {
    chartError.value = e instanceof Error ? e.message : 'خطا در بارگذاری نمودار'
  } finally {
    chartLoading.value = false
  }
}

function applyChartRange() {
  void loadChart()
}

function resetChartToThisMonth() {
  const range = currentJalaliMonthRange()
  chartFromDraft.value = range.from
  chartToDraft.value = range.to
  void loadChart()
}

async function loadRecent() {
  try {
    const response = await fetchSerials({
      registered: true,
      page: registersPage.value,
      limit: registersLimit,
    })
    recentRows.value = response.items
    registersTotal.value = response.total
  } catch {
    /* ignore on dashboard */
  }
}

function prevRegistersPage() {
  if (registersPage.value > 1) {
    registersPage.value -= 1
    void loadRecent()
  }
}

function nextRegistersPage() {
  if (registersPage.value * registersLimit < registersTotal.value) {
    registersPage.value += 1
    void loadRecent()
  }
}

onMounted(() => {
  void loadRecent()
  void loadErrors()
  void loadChart()
  errorsPollTimer = setInterval(() => {
    void loadErrors()
  }, 5000)
})

onUnmounted(() => {
  if (errorsPollTimer) {
    clearInterval(errorsPollTimer)
  }
  if (chartInstance) {
    chartInstance.destroy()
    chartInstance = null
  }
})
</script>

<template>
  <div>
    <NavBar />
    <ToastStack :items="toasts" />
    <div class="page">
      <div v-if="!connected" class="banner error">اتصال قطع است — در حال تلاش مجدد...</div>
      <div class="stats-grid">
        <div class="stat-card">
          <div class="stat-label">کل سریال‌ها</div>
          <div class="stat-value">{{ stats.total }}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">استفاده‌نشده</div>
          <div class="stat-value">{{ stats.unused }}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">ثبت‌شده</div>
          <div class="stat-value">{{ stats.registered }}</div>
        </div>
      </div>

      <div v-if="sims.length > 0" class="stats-grid sim-stats">
        <div v-for="sim in sims" :key="sim.subscriptionId" class="stat-card">
          <div class="stat-label">
            {{ sim.label }}
            <span class="sim-role">{{ sim.role === 'backup' ? 'پشتیبان' : 'اصلی' }}</span>
          </div>
          <div class="stat-value">{{ sim.sent }}/{{ sim.limit }}</div>
        </div>
      </div>

      <section class="section">
        <div class="dashboard-panel">
          <h2>نمودار ثبت روزانه</h2>
          <p class="hint">
            از {{ chartFrom || '—' }} تا {{ chartTo || '—' }} — محور برای همه روزهای بازه
          </p>
          <div class="chart-range toolbar">
            <div class="chart-range-field">
              <span class="chart-range-label">از تاریخ</span>
              <JalaliDateSelects v-model="chartFromDraft" />
            </div>
            <div class="chart-range-field">
              <span class="chart-range-label">تا تاریخ</span>
              <JalaliDateSelects v-model="chartToDraft" />
            </div>
            <button type="button" :disabled="chartLoading" @click="applyChartRange">اعمال بازه</button>
            <button type="button" class="secondary" :disabled="chartLoading" @click="resetChartToThisMonth">
              ماه جاری
            </button>
          </div>
          <p v-if="chartError" class="error">{{ chartError }}</p>
          <div class="chart-wrap">
            <canvas ref="chartCanvas"></canvas>
          </div>
        </div>
      </section>

      <section class="section">
        <div class="dashboard-panel">
          <h2>خطاهای ثبت سریال</h2>
          <p class="hint dashboard-panel-meta">نتایج: {{ errorsTotal }} — صفحه {{ errorsPage }}</p>
          <div class="dashboard-panel-body dashboard-paged-table">
            <div class="table-wrap dashboard-inner-table">
              <table>
                <thead>
                  <tr>
                    <th>تلفن</th>
                    <th>آخرین سریال</th>
                    <th>علت</th>
                    <th>تعداد</th>
                    <th>آخرین زمان</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="errorClients.length === 0">
                    <td colspan="5" class="empty">موردی یافت نشد</td>
                  </tr>
                  <tr
                    v-for="client in errorClients"
                    :key="client.phone"
                    class="comm-row"
                    @click="openErrorDetail(client.phone)"
                  >
                    <td>{{ formatPhoneForDisplay(client.phone) || '—' }}</td>
                    <td>{{ client.latestSerial }}</td>
                    <td>{{ serialErrorReasonLabelFa(client.latestReason) }}</td>
                    <td>{{ client.attemptCount }}</td>
                    <td>
                      {{ formatDateTimeFa(client.latestAt).date }}
                      {{ formatDateTimeFa(client.latestAt).time }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
          <div class="dashboard-panel-footer actions">
            <button class="secondary" :disabled="errorsPage <= 1" @click="prevErrorsPage">
              قبلی
            </button>
            <button
              class="secondary"
              :disabled="errorsPage * errorsLimit >= errorsTotal"
              @click="nextErrorsPage"
            >
              بعدی
            </button>
          </div>
        </div>
      </section>

      <section class="section">
        <div class="dashboard-panel">
          <h2>رویدادهای اخیر</h2>
          <p class="hint dashboard-panel-meta">نتایج: {{ eventsTotal }} — صفحه {{ eventsPage }}</p>
          <div class="dashboard-panel-body dashboard-paged-events">
            <ul class="event-list">
              <li v-if="eventsTotal === 0">هنوز رویدادی نیست</li>
              <li v-for="(event, index) in paginatedChanges" :key="`${event.type}-${event.at}-${index}`">
                {{ formatSyncChangeFa(event) }}
              </li>
            </ul>
          </div>
          <div class="dashboard-panel-footer actions">
            <button class="secondary" :disabled="eventsPage <= 1" @click="prevEventsPage">قبلی</button>
            <button
              class="secondary"
              :disabled="eventsPage * eventsPageSize >= eventsTotal"
              @click="nextEventsPage"
            >
              بعدی
            </button>
          </div>
        </div>
      </section>

      <section class="section">
        <div class="dashboard-panel">
          <h2>آخرین ثبت‌ها</h2>
          <p class="hint dashboard-panel-meta">نتایج: {{ registersTotal }} — صفحه {{ registersPage }}</p>
          <div class="dashboard-panel-body dashboard-paged-table">
            <SerialTable :items="recentRows" class="dashboard-inner-table" />
          </div>
          <div class="dashboard-panel-footer actions">
            <button class="secondary" :disabled="registersPage <= 1" @click="prevRegistersPage">
              قبلی
            </button>
            <button
              class="secondary"
              :disabled="registersPage * registersLimit >= registersTotal"
              @click="nextRegistersPage"
            >
              بعدی
            </button>
          </div>
        </div>
      </section>
    </div>

    <SerialErrorDetailModal
      :phone="selectedErrorPhone"
      :open="errorModalOpen"
      @close="closeErrorDetail"
    />
  </div>
</template>

<style scoped>
.dashboard-panel {
  background: #fff;
  border: 1px solid #e8edf2;
  border-radius: 12px;
  padding: 1.25rem;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.05);
  display: flex;
  flex-direction: column;
}

.dashboard-panel h2 {
  margin-bottom: 0.35rem;
}

.chart-range {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  align-items: end;
  margin: 0.75rem 0 1rem;
}

.chart-range-field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.chart-range-label {
  font-size: 0.85rem;
  color: #546e7a;
}

.chart-wrap {
  width: 100%;
  height: 320px;
  position: relative;
}

.error {
  color: #b91c1c;
  margin: 0 0 0.75rem;
}

.dashboard-panel-meta {
  margin: 0 0 0.75rem;
}

.dashboard-panel-body {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.dashboard-paged-events {
  min-height: calc(9 * (2.75rem + 0.5rem));
}

.dashboard-paged-table {
  min-height: calc(2.75rem + 9 * 2.75rem);
}

.dashboard-panel-body .event-list {
  flex: 1;
}

.dashboard-panel-body .event-list li {
  background: #f5f7fa;
}

.dashboard-panel-body :deep(.dashboard-inner-table.table-wrap),
.dashboard-panel-body :deep(.table-wrap.dashboard-inner-table) {
  flex: 1;
  box-shadow: none;
  background: transparent;
  border-radius: 0;
}

.dashboard-panel-footer {
  margin: 0;
  padding-top: 0.85rem;
  margin-top: 0.75rem;
  border-top: 1px solid #eceff1;
}
</style>
