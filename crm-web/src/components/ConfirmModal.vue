<script setup lang="ts">
defineProps<{
  open: boolean
  title: string
  message: string
  confirmLabel?: string
  cancelLabel?: string
  loading?: boolean
}>()

const emit = defineEmits<{
  confirm: []
  cancel: []
}>()

function handleOverlayClick() {
  emit('cancel')
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="modal-overlay" @click="handleOverlayClick">
      <div
        class="modal-panel confirm-panel"
        role="dialog"
        aria-modal="true"
        @click.stop
      >
        <div class="modal-header">
          <h2>{{ title }}</h2>
        </div>
        <div class="modal-body">
          <p>{{ message }}</p>
        </div>
        <div class="modal-actions">
          <button type="button" class="secondary" :disabled="loading" @click="emit('cancel')">
            {{ cancelLabel ?? 'انصراف' }}
          </button>
          <button type="button" class="danger" :disabled="loading" @click="emit('confirm')">
            {{ loading ? 'در حال حذف...' : (confirmLabel ?? 'حذف') }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
