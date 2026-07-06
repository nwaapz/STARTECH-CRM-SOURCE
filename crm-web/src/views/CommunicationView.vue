<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import NavBar from '@/components/NavBar.vue'
import ConversationModal from '@/components/ConversationModal.vue'
import CommunicationFilterModal, {
  type CommunicationFilters,
  type DirectionFilter,
} from '@/components/CommunicationFilterModal.vue'
import { fetchCommunications } from '@/api/client'
import type { CommunicationMessage } from '@/api/types'
import {
  formatDateTimeFa,
  statusLabelFa,
  truncateText,
} from '@/utils/formatCommunication'
import { formatPhoneForDisplay } from '@/utils/formatPhone'

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
const appliedPhone = ref('')

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
  appliedPhone.value = filters.phone
  filterModalOpen.value = false
  void load({ resetPage: true })
}

function clearDirectionFilter() {
  appliedDirection.value = 'all'
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
          <button type="button" class="secondary" @click="openFilterModal">فیلتر</button>
        </div>
        <p class="hint">تاریخچه پیامک‌های دریافتی و ارسالی — برای مشاهده گفتگو روی هر ردیف کلیک کنید.</p>

        <div
          v-if="appliedDirection !== 'all' || appliedPhone"
          class="filter-bar"
        >
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
                <th>شماره</th>
                <th>پیام</th>
                <th>تاریخ</th>
                <th>ساعت</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="loading && items.length === 0">
                <td colspan="5" class="hint">در حال بارگذاری…</td>
              </tr>
              <tr v-else-if="items.length === 0">
                <td colspan="5" class="hint">پیامی ثبت نشده است.</td>
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
                <td>{{ formatPhoneForDisplay(item.phone) }}</td>
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
