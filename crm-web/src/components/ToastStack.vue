<script setup lang="ts">
import type { ToastMessage } from '@/composables/useSync'

defineProps<{
  items: ToastMessage[]
}>()
</script>

<template>
  <div class="toast-stack">
    <div v-for="item in items" :key="item.id" class="toast">
      {{ item.text }}
    </div>
  </div>
</template>
