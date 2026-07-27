<script setup lang="ts">
import { ref, watch } from 'vue'
import type { CommunicationChannel } from '@/api/types'

export type DirectionFilter = 'all' | 'incoming' | 'outgoing'
export type ChannelFilter = 'all' | CommunicationChannel

export interface CommunicationFilters {
  direction: DirectionFilter
  channel: ChannelFilter
  phone: string
}

const props = defineProps<{
  open: boolean
  direction: DirectionFilter
  channel: ChannelFilter
  phone: string
}>()

const emit = defineEmits<{
  apply: [filters: CommunicationFilters]
  close: []
}>()

const draftDirection = ref<DirectionFilter>('all')
const draftChannel = ref<ChannelFilter>('all')
const draftPhone = ref('')

watch(
  () => [props.open, props.direction, props.channel, props.phone] as const,
  ([open, direction, channel, phone]) => {
    if (open) {
      draftDirection.value = direction
      draftChannel.value = channel
      draftPhone.value = phone
    }
  },
  { immediate: true },
)

function handleOverlayClick() {
  emit('close')
}

function apply() {
  emit('apply', {
    direction: draftDirection.value,
    channel: draftChannel.value,
    phone: draftPhone.value.trim(),
  })
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="modal-overlay" @click="handleOverlayClick">
      <div
        class="modal-panel filter-modal-panel"
        role="dialog"
        aria-modal="true"
        @click.stop
      >
        <div class="modal-header">
          <h2>فیلتر پیام‌ها</h2>
          <button type="button" class="modal-close" aria-label="بستن" @click="emit('close')">
            ×
          </button>
        </div>
        <div class="modal-body filter-modal-body">
          <fieldset class="filter-fieldset">
            <legend>نوع پیام</legend>
            <label class="filter-radio">
              <input v-model="draftDirection" type="radio" value="all" />
              همه پیام‌ها
            </label>
            <label class="filter-radio">
              <input v-model="draftDirection" type="radio" value="incoming" />
              پیام‌های دریافتی
            </label>
            <label class="filter-radio">
              <input v-model="draftDirection" type="radio" value="outgoing" />
              پیام‌های ارسالی
            </label>
          </fieldset>
          <fieldset class="filter-fieldset">
            <legend>کانال</legend>
            <label class="filter-radio">
              <input v-model="draftChannel" type="radio" value="all" />
              همه کانال‌ها
            </label>
            <label class="filter-radio">
              <input v-model="draftChannel" type="radio" value="sms" />
              پیامک
            </label>
            <label class="filter-radio">
              <input v-model="draftChannel" type="radio" value="rubika" />
              روبیکا
            </label>
            <label class="filter-radio">
              <input v-model="draftChannel" type="radio" value="telegram" />
              تلگرام
            </label>
            <label class="filter-radio">
              <input v-model="draftChannel" type="radio" value="bale" />
              بله
            </label>
          </fieldset>
          <label>
            شماره تلفن
            <input v-model="draftPhone" type="text" placeholder="مثال: 0913" />
            <span class="hint">جستجوی جزئی — همه شماره‌هایی که با این پیشوند شروع شوند</span>
          </label>
        </div>
        <div class="modal-actions">
          <button type="button" class="secondary" @click="emit('close')">انصراف</button>
          <button type="button" class="primary" @click="apply">اعمال</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
