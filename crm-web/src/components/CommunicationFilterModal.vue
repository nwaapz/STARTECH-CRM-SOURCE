<script setup lang="ts">
import { ref, watch } from 'vue'

export type DirectionFilter = 'all' | 'incoming' | 'outgoing'

export interface CommunicationFilters {
  direction: DirectionFilter
  phone: string
}

const props = defineProps<{
  open: boolean
  direction: DirectionFilter
  phone: string
}>()

const emit = defineEmits<{
  apply: [filters: CommunicationFilters]
  close: []
}>()

const draftDirection = ref<DirectionFilter>('all')
const draftPhone = ref('')

watch(
  () => [props.open, props.direction, props.phone] as const,
  ([open, direction, phone]) => {
    if (open) {
      draftDirection.value = direction
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
