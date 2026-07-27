<script setup lang="ts">
import { ref } from 'vue'

const props = defineProps<{
  url: string
}>()

const copied = ref(false)
let copyTimer: ReturnType<typeof setTimeout> | null = null

async function copyUrl() {
  try {
    await navigator.clipboard.writeText(props.url)
  } catch {
    const input = document.createElement('input')
    input.value = props.url
    document.body.appendChild(input)
    input.select()
    document.execCommand('copy')
    document.body.removeChild(input)
  }
  copied.value = true
  if (copyTimer) clearTimeout(copyTimer)
  copyTimer = setTimeout(() => {
    copied.value = false
  }, 2000)
}
</script>

<template>
  <div class="bot-url">
    <span class="bot-url-label">لینک ربات (برای کپی در پیام انبوه)</span>
    <div class="bot-url-row">
      <input class="bot-url-input" type="text" dir="ltr" :value="url" readonly />
      <button type="button" class="bot-url-copy" @click="copyUrl">
        {{ copied ? 'کپی شد' : 'کپی' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.bot-url {
  display: grid;
  gap: 0.35rem;
  margin-top: 0.85rem;
}

.bot-url-label {
  font-size: 0.9rem;
  color: #555;
}

.bot-url-row {
  display: flex;
  gap: 0.5rem;
  align-items: stretch;
}

.bot-url-input {
  flex: 1;
  min-width: 0;
  padding: 0.55rem 0.75rem;
  border: 1px solid #ccc;
  border-radius: 8px;
  background: #f7f9fa;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.9rem;
}

.bot-url-copy {
  flex-shrink: 0;
  padding: 0.45rem 0.95rem;
  border-radius: 8px;
  border: 1px solid #1976d2;
  background: #1976d2;
  color: #fff;
  cursor: pointer;
  font-size: 0.9rem;
}

.bot-url-copy:hover {
  background: #1565c0;
}
</style>
