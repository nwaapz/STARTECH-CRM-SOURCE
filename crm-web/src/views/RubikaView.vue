<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  connectRubikaBot,
  disconnectRubikaBot,
  fetchRubikaMessages,
  fetchRubikaStatus,
  updateRubikaSettings,
} from '@/api/client'
import type { RubikaMessage, RubikaStatus } from '@/api/types'

const loading = ref(true)
const connecting = ref(false)
const disconnecting = ref(false)
const savingSerial = ref(false)
const error = ref('')
const tokenInput = ref('')
const status = ref<RubikaStatus>({
  active: false,
  serialEnabled: false,
  polling: false,
})
const messages = ref<RubikaMessage[]>([])
const serialEnabled = ref(false)

let pollTimer: ReturnType<typeof setInterval> | null = null
let lastTimestamp = 0

const statusLabel = computed(() => {
  if (!status.value.active) return 'ربات غیرفعال'
  const parts = ['ربات متصل است']
  if (status.value.botTitle) parts.push(`— ${status.value.botTitle}`)
  if (status.value.botUsername) parts.push(`(@${status.value.botUsername})`)
  return parts.join(' ')
})

function truncateChatId(chatId: string): string {
  if (chatId.length <= 12) return chatId
  return `${chatId.slice(0, 6)}…${chatId.slice(-4)}`
}

function formatTime(timestamp: number): string {
  return new Intl.DateTimeFormat('fa-IR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(timestamp))
}

async function loadStatus() {
  status.value = await fetchRubikaStatus()
  serialEnabled.value = status.value.serialEnabled
}

async function loadMessages(options: { silent?: boolean } = {}) {
  if (!options.silent) {
    loading.value = true
  }
  try {
    const response = await fetchRubikaMessages({
      since: lastTimestamp > 0 ? lastTimestamp : undefined,
      limit: 200,
    })
    if (lastTimestamp > 0 && response.items.length > 0) {
      const existingIds = new Set(messages.value.map((item) => item.messageId))
      const fresh = response.items.filter((item) => !existingIds.has(item.messageId))
      if (fresh.length > 0) {
        messages.value = [...messages.value, ...fresh]
      }
    } else if (lastTimestamp === 0) {
      messages.value = response.items
    }
    const latest = messages.value.at(-1)
    if (latest) {
      lastTimestamp = latest.timestamp
    }
  } catch (e) {
    if (!options.silent) {
      error.value = e instanceof Error ? e.message : 'خطا در بارگذاری پیام‌ها'
    }
  } finally {
    if (!options.silent) {
      loading.value = false
    }
  }
}

async function refreshAll(options: { silent?: boolean } = {}) {
  error.value = ''
  try {
    await loadStatus()
    if (lastTimestamp === 0 || !options.silent) {
      lastTimestamp = 0
      await loadMessages({ silent: options.silent })
    } else {
      await loadMessages({ silent: true })
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا'
  }
}

async function connectBot() {
  connecting.value = true
  error.value = ''
  try {
    status.value = await connectRubikaBot({ token: tokenInput.value.trim() })
    serialEnabled.value = status.value.serialEnabled
    lastTimestamp = 0
    await loadMessages()
    startPolling()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'اتصال به ربات ناموفق بود'
  } finally {
    connecting.value = false
  }
}

async function disconnectBot() {
  disconnecting.value = true
  error.value = ''
  try {
    status.value = await disconnectRubikaBot()
    serialEnabled.value = false
    tokenInput.value = ''
    stopPolling()
    lastTimestamp = 0
    await loadMessages()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'قطع اتصال ناموفق بود'
  } finally {
    disconnecting.value = false
  }
}

async function toggleSerialEnabled() {
  savingSerial.value = true
  error.value = ''
  try {
    const updated = await updateRubikaSettings({ serialEnabled: serialEnabled.value })
    serialEnabled.value = updated.serialEnabled
    status.value = { ...status.value, serialEnabled: updated.serialEnabled }
  } catch (e) {
    serialEnabled.value = status.value.serialEnabled
    error.value = e instanceof Error ? e.message : 'خطا در ذخیره تنظیمات'
  } finally {
    savingSerial.value = false
  }
}

function startPolling() {
  stopPolling()
  pollTimer = setInterval(() => {
    void refreshAll({ silent: true })
  }, 1500)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

onMounted(async () => {
  await refreshAll()
  if (status.value.active) {
    startPolling()
  }
})

onUnmounted(stopPolling)
</script>

<template>
  <main class="page">
    <div class="card">
      <h1>روبیکا</h1>
      <p class="hint">توکن ربات را وارد کنید. دریافت پیام‌ها از طریق اپلیکیشن انجام می‌شود.</p>

      <label class="field">
        <span>توکن ربات</span>
        <input
          v-model="tokenInput"
          type="text"
          dir="ltr"
          :disabled="connecting || status.active"
          placeholder="SUPER_SECRET_TOKEN"
        />
      </label>

      <div class="actions">
        <button type="button" :disabled="connecting || !tokenInput.trim() || status.active" @click="connectBot">
          {{ connecting ? 'در حال اتصال…' : 'اتصال' }}
        </button>
        <button
          v-if="status.active"
          type="button"
          class="secondary"
          :disabled="disconnecting"
          @click="disconnectBot"
        >
          {{ disconnecting ? 'در حال قطع…' : 'قطع اتصال' }}
        </button>
      </div>

      <p class="status-line" :class="{ active: status.active, inactive: !status.active }">
        <span class="dot" />
        {{ statusLabel }}
        <span v-if="status.active && status.polling" class="polling-badge">در حال دریافت</span>
      </p>

      <label v-if="status.active" class="toggle-row">
        <input
          v-model="serialEnabled"
          type="checkbox"
          :disabled="savingSerial"
          @change="toggleSerialEnabled"
        />
        <span>دریافت سریال از روبیکا</span>
      </label>

      <p v-if="error" class="error">{{ error }}</p>
    </div>

    <div v-if="status.active || messages.length > 0" class="card messages-card">
      <div class="messages-header">
        <h2>پیام‌های دریافتی</h2>
        <span v-if="status.active && status.polling" class="polling-badge">در حال دریافت</span>
      </div>

      <p v-if="loading">در حال بارگذاری…</p>
      <p v-else-if="messages.length === 0" class="hint">هنوز پیامی دریافت نشده</p>

      <ul v-else class="message-list">
        <li v-for="item in messages" :key="item.messageId" class="message-item">
          <div class="message-meta">
            <span>{{ truncateChatId(item.chatId) }}</span>
            <span>{{ formatTime(item.timestamp) }}</span>
          </div>
          <p class="message-direction">{{ item.direction === 'incoming' ? '↓' : '↑' }}</p>
          <p v-if="item.senderName" class="message-sender">{{ item.senderName }}</p>
          <p class="message-text">{{ item.text || '—' }}</p>
          <p v-if="item.phoneNumber" class="message-phone">شماره: {{ item.phoneNumber }}</p>
        </li>
      </ul>
    </div>
  </main>
</template>

<style scoped>
.messages-card {
  margin-top: 1rem;
}

.messages-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  margin-bottom: 0.75rem;
}

.status-line {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 0.75rem;
}

.status-line.active {
  color: #2e7d32;
}

.status-line.inactive {
  color: #666;
}

.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: currentColor;
}

.polling-badge {
  font-size: 0.85rem;
  color: #2e7d32;
}

.toggle-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 1rem;
}

.field {
  display: grid;
  gap: 0.35rem;
  margin-top: 0.75rem;
}

.field input {
  width: 100%;
  padding: 0.55rem 0.75rem;
  border: 1px solid #ccc;
  border-radius: 8px;
}

.message-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 0.75rem;
  max-height: 60vh;
  overflow-y: auto;
}

.message-item {
  border: 1px solid #e5e5e5;
  border-radius: 10px;
  padding: 0.75rem;
}

.message-meta {
  display: flex;
  justify-content: space-between;
  gap: 0.5rem;
  font-size: 0.85rem;
  color: #666;
}

.message-direction {
  margin: 0.25rem 0;
  font-size: 0.85rem;
}

.message-sender {
  margin: 0;
  font-size: 0.85rem;
  color: #444;
}

.message-text {
  margin: 0;
  white-space: pre-wrap;
}

.message-phone {
  margin: 0.35rem 0 0;
  font-size: 0.85rem;
  color: #444;
}
</style>
