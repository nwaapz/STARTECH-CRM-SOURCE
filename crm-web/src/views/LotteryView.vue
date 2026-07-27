<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import NavBar from '@/components/NavBar.vue'
import LotteryDrawStage from '@/components/LotteryDrawStage.vue'
import {
  confirmLotteryWinner,
  drawLottery,
  fetchLotteryParticipants,
  fetchLotteryPreset,
  fetchLotteryWinners,
  setLotteryPreset,
  type LotteryParticipant,
  type LotteryWinner,
} from '@/api/client'
import { formatPhoneForDisplay } from '@/utils/formatPhone'
import { ensureLotteryAudioContext } from '@/utils/lotterySfx'

const participants = ref<LotteryParticipant[]>([])
const winners = ref<LotteryWinner[]>([])
const winnersTotal = ref(0)
const city = ref('')
const phoneSearch = ref('')
const loading = ref(false)
const confirming = ref(false)
const error = ref('')
const drawn = ref<LotteryParticipant | null>(null)
const revealReady = ref(false)
const preset = ref('')
const animating = ref(false)

const cities = computed(() => {
  const set = new Set<string>()
  for (const p of participants.value) {
    if (p.city) set.add(p.city)
  }
  return [...set].sort((a, b) => a.localeCompare(b, 'fa'))
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [pool, win, pre] = await Promise.all([
      fetchLotteryParticipants({ city: city.value || undefined, phone: phoneSearch.value || undefined }),
      fetchLotteryWinners({ page: 1, limit: 30 }),
      fetchLotteryPreset().catch(() => ({ phone: null })),
    ])
    participants.value = pool.items
    winners.value = win.items
    winnersTotal.value = win.total
    preset.value = pre.phone ?? ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا'
  } finally {
    loading.value = false
  }
}

async function runDraw() {
  error.value = ''
  animating.value = false
  drawn.value = null
  revealReady.value = false
  ensureLotteryAudioContext()
  try {
    const result = await drawLottery({ city: city.value || undefined })
    drawn.value = result
    animating.value = true
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در قرعه‌کشی'
    animating.value = false
  }
}

function onDrawComplete() {
  animating.value = false
  revealReady.value = true
}

function onDrawCancel() {
  animating.value = false
}

async function confirm() {
  if (!drawn.value) return
  confirming.value = true
  error.value = ''
  try {
    await confirmLotteryWinner(drawn.value.phone)
    drawn.value = null
    revealReady.value = false
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در ثبت برنده'
  } finally {
    confirming.value = false
  }
}

async function savePreset() {
  try {
    await setLotteryPreset(preset.value.trim() || null)
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا'
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page">
    <NavBar />
    <main class="content">
      <h1>قرعه‌کشی فروشندگان</h1>
      <p class="hint">
        شرکت‌کنندگان از سریال‌های فروشنده (`seller_to_end_user` و بازه S440000–S450000) با امتیاز مثبت هستند.
        هر امتیاز شانس بیشتری می‌دهد.
      </p>
      <p v-if="error" class="error">{{ error }}</p>

      <div class="toolbar">
        <label>
          شهر
          <select v-model="city" @change="load">
            <option value="">همه</option>
            <option v-for="c in cities" :key="c" :value="c">{{ c }}</option>
          </select>
        </label>
        <label>
          جستجوی شماره
          <input v-model="phoneSearch" @keyup.enter="load" />
        </label>
        <button type="button" class="secondary" :disabled="loading" @click="load">بروزرسانی</button>
        <button
          type="button"
          :disabled="loading || animating || participants.length === 0"
          @click="runDraw"
        >
          شروع قرعه‌کشی
        </button>
      </div>

      <div class="draw-stage card">
        <LotteryDrawStage
          :running="animating"
          :winner-phone="drawn?.phone ?? null"
          :participants="participants"
          @complete="onDrawComplete"
          @cancel="onDrawCancel"
        />
        <div v-if="drawn && revealReady" class="winner-box">
          <p>
            برنده پیشنهادی:
            <strong>{{ formatPhoneForDisplay(drawn.phone) }}</strong>
            ({{ drawn.score.toLocaleString('fa-IR') }} امتیاز)
          </p>
          <button type="button" :disabled="confirming" @click="confirm">
            تأیید برنده و صفر کردن امتیاز
          </button>
        </div>
        <p v-else-if="animating" class="hint animating-hint">لطفاً تا پایان انیمیشن صبر کنید…</p>
      </div>

      <section class="card">
        <h2>شرکت‌کنندگان ({{ participants.length.toLocaleString('fa-IR') }})</h2>
        <table>
          <thead>
            <tr>
              <th>شماره</th>
              <th>امتیاز</th>
              <th>شهر</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="participants.length === 0">
              <td colspan="3" class="hint">شرکت‌کننده‌ای یافت نشد.</td>
            </tr>
            <tr v-for="p in participants" :key="p.phone">
              <td>{{ formatPhoneForDisplay(p.phone) }}</td>
              <td>{{ p.score.toLocaleString('fa-IR') }}</td>
              <td>{{ p.city || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <section class="card">
        <h2>برندگان ({{ winnersTotal.toLocaleString('fa-IR') }})</h2>
        <table>
          <thead>
            <tr>
              <th>شماره</th>
              <th>امتیاز هنگام برد</th>
              <th>تاریخ</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="winners.length === 0">
              <td colspan="3" class="hint">برنده‌ای ثبت نشده است.</td>
            </tr>
            <tr v-for="w in winners" :key="w.id">
              <td>{{ formatPhoneForDisplay(w.phone) }}</td>
              <td>{{ w.scoreAtWin.toLocaleString('fa-IR') }}</td>
              <td>{{ w.dateJalali }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <section class="card">
        <h2>برنده از پیش تعیین‌شده (فقط مدیر)</h2>
        <div class="toolbar">
          <input v-model="preset" placeholder="09..." />
          <button type="button" class="secondary" @click="savePreset">ذخیره</button>
        </div>
      </section>
    </main>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  align-items: end;
  margin-bottom: 1rem;
}
.draw-stage {
  min-height: 200px;
  text-align: center;
  margin-bottom: 1rem;
}
.winner-box {
  margin-top: 0.75rem;
  padding-top: 0.75rem;
  border-top: 1px solid #e5e7eb;
}
.animating-hint {
  margin-top: 0.5rem;
}
table {
  width: 100%;
  border-collapse: collapse;
}
th,
td {
  text-align: right;
  padding: 0.4rem 0.5rem;
  border-bottom: 1px solid #e5e7eb;
}
.error {
  color: #b91c1c;
}
</style>
