<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { fetchCommunicationThread, sendCommunication } from '@/api/client'
import type { CommunicationChannel, CommunicationMessage } from '@/api/types'
import { formatDateTimeFa, statusLabelFa } from '@/utils/formatCommunication'
import { formatPhoneForDisplay } from '@/utils/formatPhone'

const props = defineProps<{
  phone: string
  open: boolean
}>()

const emit = defineEmits<{
  close: []
}>()

type ChannelMeta = {
  key: CommunicationChannel
  label: string
  className: string
}

const CHANNELS: ChannelMeta[] = [
  { key: 'sms', label: 'پیامک', className: 'ch-sms' },
  { key: 'rubika', label: 'روبیکا', className: 'ch-rubika' },
  { key: 'telegram', label: 'تلگرام', className: 'ch-telegram' },
  { key: 'bale', label: 'بله', className: 'ch-bale' },
]

function channelMeta(channel?: string | null): ChannelMeta {
  return CHANNELS.find((c) => c.key === channel) ?? CHANNELS[0]
}

const messages = ref<CommunicationMessage[]>([])
const hasMore = ref(false)
const loading = ref(false)
const loadingOlder = ref(false)
const error = ref('')
const scrollEl = ref<HTMLElement | null>(null)

const draft = ref('')
const selectedChannels = ref<Record<CommunicationChannel, boolean>>({
  sms: true,
  rubika: false,
  telegram: false,
  bale: false,
})
const sending = ref(false)
const sendError = ref('')
const sendNotice = ref('')

function toggleChannel(key: CommunicationChannel) {
  selectedChannels.value[key] = !selectedChannels.value[key]
}

async function loadInitial() {
  loading.value = true
  error.value = ''
  messages.value = []
  hasMore.value = false
  try {
    const response = await fetchCommunicationThread({ phone: props.phone, limit: 50 })
    messages.value = response.items
    hasMore.value = response.hasMore
    await nextTick()
    scrollToBottom()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا'
  } finally {
    loading.value = false
  }
}

async function sendMessage() {
  const text = draft.value.trim()
  sendError.value = ''
  sendNotice.value = ''
  if (!text) {
    sendError.value = 'متن پیام را وارد کنید.'
    return
  }
  const channels = CHANNELS.map((c) => c.key).filter((key) => selectedChannels.value[key])
  if (channels.length === 0) {
    sendError.value = 'حداقل یک تونل را انتخاب کنید.'
    return
  }
  sending.value = true
  try {
    const response = await sendCommunication({ phone: props.phone, body: text, channels })
    const failed = response.results.filter((r) => !r.ok)
    const okCount = response.results.length - failed.length
    if (failed.length > 0) {
      const parts = failed.map((r) => `${channelMeta(r.channel).label}: ${r.error ?? 'خطا'}`)
      sendError.value = parts.join(' — ')
    }
    if (okCount > 0) {
      draft.value = ''
      sendNotice.value = `ارسال شد از طریق ${okCount.toLocaleString('fa-IR')} تونل`
      await loadInitial()
    }
  } catch (e) {
    sendError.value = e instanceof Error ? e.message : 'خطا در ارسال'
  } finally {
    sending.value = false
  }
}

async function loadOlder() {
  if (loadingOlder.value || !hasMore.value || messages.value.length === 0) return
  const container = scrollEl.value
  const previousHeight = container?.scrollHeight ?? 0
  loadingOlder.value = true
  error.value = ''
  try {
    const oldest = messages.value[0]?.timestamp
    if (oldest === undefined) return
    const response = await fetchCommunicationThread({
      phone: props.phone,
      before: oldest,
      limit: 50,
    })
    messages.value = [...response.items, ...messages.value]
    hasMore.value = response.hasMore
    await nextTick()
    if (container) {
      container.scrollTop = container.scrollHeight - previousHeight
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا'
  } finally {
    loadingOlder.value = false
  }
}

function scrollToBottom() {
  const container = scrollEl.value
  if (container) {
    container.scrollTop = container.scrollHeight
  }
}

function onScroll(event: Event) {
  const target = event.target as HTMLElement
  if (target.scrollTop < 48 && hasMore.value && !loadingOlder.value) {
    void loadOlder()
  }
}

function handleOverlayClick(event: MouseEvent) {
  if (event.target === event.currentTarget) {
    emit('close')
  }
}

watch(
  () => [props.open, props.phone] as const,
  ([open]) => {
    if (open) {
      draft.value = ''
      sendError.value = ''
      sendNotice.value = ''
      void loadInitial()
    }
  },
  { immediate: true },
)
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="modal-overlay" @click="handleOverlayClick">
      <div
        class="modal-panel conversation-panel"
        role="dialog"
        aria-modal="true"
        @click.stop
      >
        <div class="modal-header">
          <h2>
            {{
              phone.startsWith('bot:')
                ? `چت ${channelMeta(phone.split(':')[1]).label}`
                : formatPhoneForDisplay(phone)
            }}
          </h2>
          <button type="button" class="modal-close" aria-label="بستن" @click="emit('close')">
            ×
          </button>
        </div>

        <p v-if="error" class="error modal-error">{{ error }}</p>
        <p v-if="loading" class="hint modal-loading">در حال بارگذاری…</p>
        <p v-else-if="loadingOlder" class="hint thread-loading-older">بارگذاری پیام‌های قدیمی‌تر…</p>

        <div ref="scrollEl" class="thread-scroll" @scroll="onScroll">
          <div v-if="!loading && messages.length === 0" class="hint thread-empty">
            پیامی برای این شماره ثبت نشده است.
          </div>
          <div
            v-for="message in messages"
            :key="message.id"
            class="bubble-row"
            :class="message.direction === 'incoming' ? 'bubble-row-in' : 'bubble-row-out'"
          >
            <div
              class="bubble"
              :class="message.direction === 'incoming' ? 'bubble-in' : 'bubble-out'"
            >
              <p class="bubble-body">{{ message.body }}</p>
              <div class="bubble-meta">
                <span class="channel-badge" :class="channelMeta(message.channel).className">
                  {{ channelMeta(message.channel).label }}
                </span>
                <span class="bubble-time">
                  {{ formatDateTimeFa(message.timestamp).date }}
                  {{ formatDateTimeFa(message.timestamp).time }}
                </span>
              </div>
              <p
                v-if="message.direction === 'outgoing' && message.status && message.status !== 'sent'"
                class="bubble-status"
              >
                {{ statusLabelFa(message.status) }}
              </p>
            </div>
          </div>
        </div>

        <div v-if="!phone.startsWith('bot:')" class="composer">
          <div class="composer-channels">
            <button
              v-for="ch in CHANNELS"
              :key="ch.key"
              type="button"
              class="channel-toggle"
              :class="[ch.className, { active: selectedChannels[ch.key] }]"
              :aria-pressed="selectedChannels[ch.key]"
              @click="toggleChannel(ch.key)"
            >
              {{ ch.label }}
            </button>
          </div>
          <div class="composer-input">
            <textarea
              v-model="draft"
              class="composer-textarea"
              rows="2"
              placeholder="پیامی برای مشتری بنویسید…"
              :disabled="sending"
              @keydown.enter.exact.prevent="sendMessage"
            />
            <button
              type="button"
              class="composer-send"
              :disabled="sending"
              @click="sendMessage"
            >
              {{ sending ? '…' : 'ارسال' }}
            </button>
          </div>
          <p v-if="sendError" class="error composer-feedback">{{ sendError }}</p>
          <p v-else-if="sendNotice" class="hint composer-feedback composer-ok">{{ sendNotice }}</p>
        </div>
        <p v-else class="hint modal-loading">
          تا ثبت شماره موبایل توسط کاربر، ارسال از این گفتگو ممکن نیست.
        </p>
      </div>
    </div>
  </Teleport>
</template>
