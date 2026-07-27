<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  connectTelegramBot,
  disconnectTelegramBot,
  fetchTelegramMessages,
  fetchTelegramSettings,
  fetchTelegramStatus,
  updateTelegramSettings,
} from '@/api/client'
import type { BotMenuSettings, TelegramMessage, TelegramSettings, TelegramStatus } from '@/api/types'
import BotUrlCopy from '@/components/BotUrlCopy.vue'
import UiSwitch from '@/components/UiSwitch.vue'
import { botPublicUrl } from '@/utils/botUrl'

const loading = ref(true)
const connecting = ref(false)
const disconnecting = ref(false)
const savingSerial = ref(false)
const savingInvite = ref(false)
const savingMessage = ref(false)
const savingMenu = ref(false)
const error = ref('')
const tokenInput = ref('')
const status = ref<TelegramStatus>({
  active: false,
  serialEnabled: false,
  inviteEnabled: false,
  polling: false,
})
const messages = ref<TelegramMessage[]>([])
const serialEnabled = ref(false)
const inviteEnabled = ref(false)
const inviteMessage = ref('')
const inviteMessageDirty = ref(false)
const menuRegisterWarranty = ref(true)
const menuWarrantyStatus = ref(true)
const menuSendProductPhoto = ref(true)
const menuInviteFriends = ref(true)
const menuMyScore = ref(true)
const menuSupport = ref(true)
const menuHelp = ref(true)

let pollTimer: ReturnType<typeof setInterval> | null = null
let lastTimestamp = 0

const statusLabel = computed(() => {
  if (!status.value.active) return 'ربات غیرفعال'
  const parts = ['ربات متصل است']
  if (status.value.botTitle) parts.push(`— ${status.value.botTitle}`)
  if (status.value.botUsername) parts.push(`(@${status.value.botUsername})`)
  return parts.join(' ')
})

const botUrl = computed(() => botPublicUrl('telegram', status.value.botUsername))

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

function applyMenuFromSettings(settings: BotMenuSettings) {
  menuRegisterWarranty.value = settings.menuRegisterWarranty
  menuWarrantyStatus.value = settings.menuWarrantyStatus
  menuSendProductPhoto.value = settings.menuSendProductPhoto ?? true
  menuInviteFriends.value = settings.menuInviteFriends
  menuMyScore.value = settings.menuMyScore ?? true
  menuSupport.value = settings.menuSupport
  menuHelp.value = settings.menuHelp
}

function resetMenuDefaults() {
  menuRegisterWarranty.value = true
  menuWarrantyStatus.value = true
  menuSendProductPhoto.value = true
  menuInviteFriends.value = true
  menuMyScore.value = true
  menuSupport.value = true
  menuHelp.value = true
}

function settingsPayload(): TelegramSettings {
  return {
    serialEnabled: serialEnabled.value,
    inviteEnabled: inviteEnabled.value,
    inviteMessage: inviteMessage.value,
    menuRegisterWarranty: menuRegisterWarranty.value,
    menuWarrantyStatus: menuWarrantyStatus.value,
    menuSendProductPhoto: menuSendProductPhoto.value,
    menuInviteFriends: menuInviteFriends.value,
    menuMyScore: menuMyScore.value,
    menuSupport: menuSupport.value,
    menuHelp: menuHelp.value,
  }
}

async function loadStatus(options: { syncFormFields?: boolean } = {}) {
  const syncFormFields = options.syncFormFields ?? true
  status.value = await fetchTelegramStatus()
  if (!syncFormFields) return
  serialEnabled.value = status.value.serialEnabled
  inviteEnabled.value = status.value.inviteEnabled
  if (status.value.active) {
    const settings = await fetchTelegramSettings()
    if (!inviteMessageDirty.value) {
      inviteMessage.value = settings.inviteMessage
    }
    applyMenuFromSettings(settings)
  }
}

async function loadMessages(options: { silent?: boolean } = {}) {
  if (!options.silent) {
    loading.value = true
  }
  try {
    const response = await fetchTelegramMessages({
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
    await loadStatus({ syncFormFields: !options.silent })
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
    status.value = await connectTelegramBot({ token: tokenInput.value.trim() })
    serialEnabled.value = status.value.serialEnabled
    inviteEnabled.value = status.value.inviteEnabled
    const settings = await fetchTelegramSettings()
    inviteMessage.value = settings.inviteMessage
    inviteMessageDirty.value = false
    applyMenuFromSettings(settings)
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
    status.value = await disconnectTelegramBot()
    serialEnabled.value = false
    inviteEnabled.value = false
    inviteMessage.value = ''
    inviteMessageDirty.value = false
    resetMenuDefaults()
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
    const updated = await updateTelegramSettings(settingsPayload())
    serialEnabled.value = updated.serialEnabled
    status.value = { ...status.value, serialEnabled: updated.serialEnabled }
  } catch (e) {
    serialEnabled.value = status.value.serialEnabled
    error.value = e instanceof Error ? e.message : 'خطا در ذخیره تنظیمات'
  } finally {
    savingSerial.value = false
  }
}

async function toggleInviteEnabled() {
  savingInvite.value = true
  error.value = ''
  try {
    const updated = await updateTelegramSettings(settingsPayload())
    inviteEnabled.value = updated.inviteEnabled
    status.value = { ...status.value, inviteEnabled: updated.inviteEnabled }
  } catch (e) {
    inviteEnabled.value = status.value.inviteEnabled
    error.value = e instanceof Error ? e.message : 'خطا در ذخیره تنظیمات'
  } finally {
    savingInvite.value = false
  }
}

async function saveInviteMessage() {
  savingMessage.value = true
  error.value = ''
  try {
    const updated = await updateTelegramSettings(settingsPayload())
    inviteMessage.value = updated.inviteMessage
    inviteMessageDirty.value = false
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در ذخیره متن پیام'
  } finally {
    savingMessage.value = false
  }
}

async function saveMenuSettings() {
  savingMenu.value = true
  error.value = ''
  const snapshot = {
    menuRegisterWarranty: menuRegisterWarranty.value,
    menuWarrantyStatus: menuWarrantyStatus.value,
    menuSendProductPhoto: menuSendProductPhoto.value,
    menuInviteFriends: menuInviteFriends.value,
    menuMyScore: menuMyScore.value,
    menuSupport: menuSupport.value,
    menuHelp: menuHelp.value,
  }
  try {
    const updated = await updateTelegramSettings(settingsPayload())
    applyMenuFromSettings(updated)
  } catch (e) {
    applyMenuFromSettings(snapshot)
    error.value = e instanceof Error ? e.message : 'خطا در ذخیره منوی ربات'
  } finally {
    savingMenu.value = false
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
      <h1>تلگرام</h1>
      <p class="hint">توکن ربات را از BotFather وارد کنید. دریافت پیام‌ها از طریق اپلیکیشن انجام می‌شود.</p>

      <label class="field">
        <span>توکن ربات</span>
        <input
          v-model="tokenInput"
          type="text"
          dir="ltr"
          :disabled="connecting || status.active"
          placeholder="123456789:ABCdefGHI..."
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

      <BotUrlCopy v-if="status.active && botUrl" :url="botUrl" />

      <UiSwitch
        v-if="status.active"
        v-model="serialEnabled"
        label="دریافت سریال از تلگرام"
        :disabled="savingSerial"
        @change="toggleSerialEnabled"
      />

      <UiSwitch
        v-if="status.active"
        v-model="inviteEnabled"
        label="ارسال لینک دعوت ربات به مشتریان جدید"
        :disabled="savingInvite"
        @change="toggleInviteEnabled"
      />

      <div v-if="status.active && inviteEnabled" class="field">
        <span>متن پیام دعوت (از @1 برای لینک ربات استفاده کنید)</span>
        <textarea
          v-model="inviteMessage"
          dir="rtl"
          rows="3"
          :disabled="savingMessage"
          placeholder="برای استفاده از خدمات ما در این ربات، روی لینک زیر کلیک کنید:&#10;@1"
          @input="inviteMessageDirty = true"
        />
        <button type="button" class="small" :disabled="savingMessage" @click="saveInviteMessage">
          {{ savingMessage ? 'در حال ذخیره…' : 'ذخیره متن پیام' }}
        </button>
      </div>

      <div v-if="status.active" class="menu-section">
        <h2>منوی ربات</h2>
        <p class="hint">
          تغییرات بلافاصله ذخیره می‌شود و در پیام بعدی مشتری اعمال می‌گردد. برای دیدن دکمه جدید، یک
          بار از ربات پیام بگیرید تا کیبورد به‌روز شود.
        </p>
        <UiSwitch
          v-model="menuRegisterWarranty"
          label="ثبت گارانتی"
          :disabled="savingMenu"
          @change="saveMenuSettings"
        />
        <UiSwitch
          v-model="menuWarrantyStatus"
          label="وضعیت گارانتی"
          :disabled="savingMenu"
          @change="saveMenuSettings"
        />
        <UiSwitch
          v-model="menuSendProductPhoto"
          label="ارسال عکس محصول (امتیاز / قرعه‌کشی)"
          :disabled="savingMenu"
          @change="saveMenuSettings"
        />
        <UiSwitch
          v-model="menuInviteFriends"
          label="دعوت دوستان"
          :disabled="savingMenu"
          @change="saveMenuSettings"
        />
        <UiSwitch
          v-model="menuMyScore"
          label="امتیاز من"
          :disabled="savingMenu"
          @change="saveMenuSettings"
        />
        <UiSwitch
          v-model="menuSupport"
          label="پشتیبانی"
          :disabled="savingMenu"
          @change="saveMenuSettings"
        />
        <UiSwitch
          v-model="menuHelp"
          label="راهنما"
          :disabled="savingMenu"
          @change="saveMenuSettings"
        />
      </div>

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

.menu-section {
  margin-top: 1.25rem;
  padding-top: 1rem;
  border-top: 1px solid #eceff1;
}

.menu-section h2 {
  margin-bottom: 0.35rem;
  font-size: 1.05rem;
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

.field textarea {
  width: 100%;
  padding: 0.55rem 0.75rem;
  border: 1px solid #ccc;
  border-radius: 8px;
  resize: vertical;
}

.field .small {
  margin-top: 0.35rem;
  padding: 0.4rem 0.9rem;
  font-size: 0.85rem;
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
