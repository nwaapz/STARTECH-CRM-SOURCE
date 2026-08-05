<script setup lang="ts">
import { onMounted } from 'vue'
import { RouterView } from 'vue-router'
import { getPin, triggerCloudAutoPull } from '@/api/client'

onMounted(() => {
  // Silent, once-per-load pull so every page reload gets fresh data if the
  // server is reachable. Fails silently (leaves changes pending) otherwise.
  if (!getPin()) return
  triggerCloudAutoPull().catch(() => {
    /* server unreachable/not configured — user can still tap "Get from server" */
  })
})
</script>

<template>
  <RouterView />
</template>
