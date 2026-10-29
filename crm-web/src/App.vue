<script setup lang="ts">
import { onMounted } from 'vue'
import { RouterView } from 'vue-router'
import { getPin, triggerCloudAutoPull } from '@/api/client'
import PerfDebugPanel from '@/components/PerfDebugPanel.vue'
import { isPerfDebugEnabled, perfPageNote } from '@/utils/perfDebug'

onMounted(() => {
  if (isPerfDebugEnabled()) {
    perfPageNote('App mounted — perf debug ON')
  }
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
  <PerfDebugPanel />
</template>
