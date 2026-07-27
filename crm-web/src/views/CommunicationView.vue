<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import NavBar from '@/components/NavBar.vue'
import ConversationModal from '@/components/ConversationModal.vue'
import CommunicationFilterModal, {
  type ChannelFilter,
  type CommunicationFilters,
  type DirectionFilter,
} from '@/components/CommunicationFilterModal.vue'
import { fetchCommunications } from '@/api/client'
import type { CommunicationChannel, CommunicationMessage } from '@/api/types'
import {
  formatDateTimeFa,
  statusLabelFa,
  truncateText,
} from '@/utils/formatCommunication'
import { formatPhoneForDisplay } from '@/utils/formatPhone'

const CHANNEL_META: Record<
  CommunicationChannel,
  { label: string; className: string }
> = {
  sms: { label: 'پیامک', className: 'ch-sms' },
  rubika: { label: 'روبیکا', className: 'ch-rubika' },
  telegram: { label: 'تلگرام', className: 'ch-telegram' },
  bale: { label: 'بله', className: 'ch-bale' },
}

function channelMeta(channel?: string | null) {
  const key = (channel ?? 'sms') as CommunicationChannel
  return CHANNEL_META[key] ?? CHANNEL_META.sms
}

function contactLabel(phone: string, channel?: string | null): string {
  if (phone.startsWith('bot:')) {
    return `چت ${channelMeta(channel ?? phone.split(':')[1]).label}`
  }
  return formatPhoneForDisplay(phone) || phone
}

const page = ref(1)
const limit = ref(50)
const total = ref(0)
const items = ref<CommunicationMessage[]>([])
const loading = ref(false)
const error = ref('')
const selectedPhone = ref<string | null>(null)
const modalOpen = ref(false)
const filterModalOpen = ref(false)
const appliedDirection = ref<DirectionFilter>('all')
const appliedChannel = ref<ChannelFilter>('all')
const appliedPhone = ref('')

const hasFilters = computed(
  () =>
    appliedDirection.value !== 'all' ||
    appliedChannel.value !== 'all' ||
    !!appliedPhone.value,
)

let pollTimer: ReturnType<typeof setInterval> | null = null

async function load(options: { silent?: boolean; resetPage?: boolean } = {}) {
  if (options.resetPage) {
    page.value = 1
  }
  if (!options.silent) {
    loading.value = true
  }
  error.value = ''
  try {
    const response = await fetchCommunications({
      page: page.value,
      limit: limit.value,
      direction: appliedDirection.value === 'all' ? undefined : appliedDirection.value,
      channel: appliedChannel.value === 'all' ? undefined : appliedChannel.value,
      phone: appliedPhone.value || undefined,
    })
    items.value = response.items
    total.value = response.total
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا'
  } finally {
    if (!options.silent) {
      loading.value = false
    }
  }
}

function openThread(phone: string) {
  selectedPhone.value = phone
  modalOpen.value = true
}

function closeThread() {
  modalOpen.value = false
}

function openFilterModal() {
  filterModalOpen.value = true
}

function closeFilterModal() {
  filterModalOpen.value = false
}

function applyFilters(filters: CommunicationFilters) {
  appliedDirection.value = filters.direction
  appliedChannel.value = filters.channel
  appliedPhone.value = filters.phone
  filterModalOpen.value = false
  void load({ resetPage: true })
}

function clearDirectionFilter() {
  appliedDirection.value = 'all'
  void load({ resetPage: true })
}

function clearChannelFilter() {
  appliedChannel.value = 'all'
  void load({ resetPage: true })
}

function clearPhoneFilter() {
  appliedPhone.value = ''
  void load({ resetPage: true })
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
  pollTimer = setInterval(() => {
    if (page.value === 1 && !modalOpen.value && !filterModalOpen.value) {
      void load({ silent: true })
    }
  }, 3000)
})

onUnmounted(() => {
  if (pollTimer) {
    clearInterval(pollTimer)
  }
})
</script>

<template>
  <div>
    <NavBar />
    <div class="page">
      <div class="card">
        <div class="comm-header">
          <h1>ارتباطات</h1>
          <button
            type="button"
            class="filter-trigger"
            :class="{ 'has-filters': hasFilters }"
            @click="openFilterModal"
          >
            <span class="filter-trigger-icon" aria-hidden="true">⚙</span>
            فیلتر
            <span
              v-if="hasFilters"
              class="filter-trigger-dot"
              aria-hidden="true"
            />
          </button>
        </div>
        <p class="hint">
          همه پیام‌های ورودی و خروجی از پیامک، روبیکا، تلگرام و بله — برای مشاهده گفتگو روی هر ردیف کلیک
          کنید.
        </p>

        <div v-if="hasFilters" class="filter-bar">
          <span
            v-if="appliedDirection !== 'all'"
            class="filter-chip"
          >
            {{ appliedDirection === 'incoming' ? 'دریافتی' : 'ارسالی' }}
            <button
              type="button"
              class="filter-chip-close"
              aria-label="حذف فیلتر جهت"
              @click="clearDirectionFilter"
            >
              ×
            </button>
          </span>
          <span v-if="appliedChannel !== 'all'" class="filter-chip">
            {{ channelMeta(appliedChannel).label }}
            <button
              type="button"
              class="filter-chip-close"
              aria-label="حذف فیلتر کانال"
              @click="clearChannelFilter"
            >
              ×
            </button>
          </span>
          <span v-if="appliedPhone" class="filter-chip">
            شماره: {{ formatPhoneForDisplay(appliedPhone) || appliedPhone }}
            <button
              type="button"
              class="filter-chip-close"
              aria-label="حذف فیلتر شماره"
              @click="clearPhoneFilter"
            >
              ×
            </button>
          </span>
        </div>

        <p v-if="error" class="error">{{ error }}</p>
        <p class="hint">نتایج: {{ total.toLocaleString('fa-IR') }} — صفحه {{ page.toLocaleString('fa-IR') }}</p>

        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>جهت</th>
                <th>کانال</th>
                <th>شماره</th>
                <th>پیام</th>
                <th>تاریخ</th>
                <th>ساعت</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="loading && items.length === 0">
                <td colspan="6" class="hint">در حال بارگذاری…</td>
              </tr>
              <tr v-else-if="items.length === 0">
                <td colspan="6" class="hint">پیامی ثبت نشده است.</td>
              </tr>
              <tr
                v-for="item in items"
                :key="item.id"
                class="comm-row"
                @click="openThread(item.phone)"
              >
                <td>
                  <span
                    class="comm-direction"
                    :class="item.direction === 'incoming' ? 'comm-direction-in' : 'comm-direction-out'"
                    :title="item.direction === 'incoming' ? 'دریافتی' : 'ارسالی'"
                  >
                    {{ item.direction === 'incoming' ? '↓' : '↑' }}
                  </span>
                </td>
                <td>
                  <span class="channel-badge" :class="channelMeta(item.channel).className">
                    {{ channelMeta(item.channel).label }}
                  </span>
                </td>
                <td>{{ contactLabel(item.phone, item.channel) }}</td>
                <td class="comm-message-cell">
                  {{ truncateText(item.body) }}
                  <span
                    v-if="item.direction === 'outgoing' && item.status && item.status !== 'sent'"
                    class="comm-status"
                  >
                    ({{ statusLabelFa(item.status) }})
                  </span>
                </td>
                <td>{{ formatDateTimeFa(item.timestamp).date }}</td>
                <td>{{ formatDateTimeFa(item.timestamp).time }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="actions">
          <button class="secondary" :disabled="page <= 1 || loading" @click="prevPage">قبلی</button>
          <button class="secondary" :disabled="page * limit >= total || loading" @click="nextPage">
            بعدی
          </button>
        </div>
      </div>
    </div>

    <CommunicationFilterModal
      :open="filterModalOpen"
      :direction="appliedDirection"
      :channel="appliedChannel"
      :phone="appliedPhone"
      @apply="applyFilters"
      @close="closeFilterModal"
    />

    <ConversationModal
      v-if="selectedPhone"
      :phone="selectedPhone"
      :open="modalOpen"
      @close="closeThread"
    />
  </div>
</template>
