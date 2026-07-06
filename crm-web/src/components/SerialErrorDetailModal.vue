<script setup lang="ts">
import { ref, watch } from 'vue'
import { fetchSerialErrorsByPhone } from '@/api/client'
import type { SerialRegistrationError } from '@/api/types'
import { formatDateTimeFa } from '@/utils/formatCommunication'
import { formatPhoneForDisplay } from '@/utils/formatPhone'
import { serialErrorReasonLabelFa } from '@/utils/formatSerialError'

const props = defineProps<{
  phone: string
  open: boolean
}>()

const emit = defineEmits<{
  close: []
}>()

const items = ref<SerialRegistrationError[]>([])
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  items.value = []
  try {
    const response = await fetchSerialErrorsByPhone(props.phone)
    items.value = response.items
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا'
  } finally {
    loading.value = false
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
      void load()
    }
  },
  { immediate: true },
)
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="modal-overlay" @click="handleOverlayClick">
      <div
        class="modal-panel serial-error-panel"
        role="dialog"
        aria-modal="true"
        @click.stop
      >
        <div class="modal-header">
          <h2>خطاهای ثبت — {{ formatPhoneForDisplay(phone) }}</h2>
          <button type="button" class="modal-close" aria-label="بستن" @click="emit('close')">
            ×
          </button>
        </div>

        <p v-if="error" class="error modal-error">{{ error }}</p>
        <p v-if="loading" class="hint modal-loading">در حال بارگذاری…</p>

        <div v-else class="modal-body">
          <p v-if="items.length === 0" class="hint">خطایی برای این شماره ثبت نشده است.</p>
          <div v-else class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>تاریخ و زمان</th>
                  <th>سریال</th>
                  <th>علت</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, index) in items" :key="`${row.at}-${index}`">
                  <td>
                    {{ formatDateTimeFa(row.at).date }}
                    {{ formatDateTimeFa(row.at).time }}
                  </td>
                  <td>{{ row.serial }}</td>
                  <td>{{ serialErrorReasonLabelFa(row.reason) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.serial-error-panel {
  max-width: 640px;
  width: calc(100% - 2rem);
}
</style>
