<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { fetchCommunicationThread } from '@/api/client'
import type { CommunicationMessage } from '@/api/types'
import { formatDateTimeFa, statusLabelFa } from '@/utils/formatCommunication'
import { formatPhoneForDisplay } from '@/utils/formatPhone'

const props = defineProps<{
  phone: string
  open: boolean
}>()

const emit = defineEmits<{
  close: []
}>()

const messages = ref<CommunicationMessage[]>([])
const hasMore = ref(false)
const loading = ref(false)
const loadingOlder = ref(false)
const error = ref('')
const scrollEl = ref<HTMLElement | null>(null)

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
          <h2>{{ formatPhoneForDisplay(phone) }}</h2>
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
              <p class="bubble-time">
                {{ formatDateTimeFa(message.timestamp).date }}
                {{ formatDateTimeFa(message.timestamp).time }}
              </p>
              <p
                v-if="message.direction === 'outgoing' && message.status && message.status !== 'sent'"
                class="bubble-status"
              >
                {{ statusLabelFa(message.status) }}
              </p>
            </div>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>
