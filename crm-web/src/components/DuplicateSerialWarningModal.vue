<script setup lang="ts">
import { computed } from 'vue'
import type { DuplicateRangeItem } from '@/api/types'

const props = defineProps<{
  open: boolean
  duplicateCount: number
  duplicateRanges: DuplicateRangeItem[]
  message?: string
}>()

const emit = defineEmits<{
  close: []
}>()

const summary = computed(() => {
  if (props.message) return props.message
  return `تولید انجام نشد — ${props.duplicateCount} سریال تکراری یافت شد.`
})

const scrollable = computed(() => props.duplicateRanges.length > 3)

function handleOverlayClick() {
  emit('close')
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="modal-overlay" @click="handleOverlayClick">
      <div
        class="modal-panel duplicate-warning-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="duplicate-warning-title"
        @click.stop
      >
        <div class="modal-header">
          <h2 id="duplicate-warning-title">هشدار سریال تکراری</h2>
        </div>
        <div class="modal-body">
          <p class="duplicate-warning-summary">{{ summary }}</p>
          <ul
            class="duplicate-range-list"
            :class="{ scrollable }"
          >
            <li
              v-for="(range, index) in duplicateRanges"
              :key="`${range.rangeLabel}-${index}`"
              class="duplicate-range-item"
            >
              {{ range.rangeLabel }} ({{ range.count }} مورد)
            </li>
          </ul>
        </div>
        <div class="modal-actions">
          <button type="button" class="primary" @click="emit('close')">
            تأیید
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
