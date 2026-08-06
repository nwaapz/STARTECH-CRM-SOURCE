<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import NavBar from '@/components/NavBar.vue'
import {
  clearSmsProcessingDebugLog,
  fetchSmsProcessingDebugLog,
} from '@/api/client'
import type { SmsProcessingDebugLogEntry } from '@/api/types'

const loading = ref(false)
const error = ref('')
const feedback = ref('')
const entries = ref<SmsProcessingDebugLogEntry[]>([])
const text = ref('')
const count = ref(0)
const phoneFilter = ref('')
const autoRefresh = ref(true)
const copying = ref(false)

let pollTimer: ReturnType<typeof setInterval> | null = null

async function loadLog() {
  loading.value = true
  error.value = ''
  try {
    const response = await fetchSmsProcessingDebugLog({
      limit: 800,
      phone: phoneFilter.value.trim() || undefined,
    })
    entries.value = response.entries
    text.value = response.text
    count.value = response.count
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در بارگذاری لاگ'
  } finally {
    loading.value = false
  }
}

async function clearLog() {
  error.value = ''
  feedback.value = ''
  try {
    await clearSmsProcessingDebugLog()
    feedback.value = 'لاگ پاک شد'
    await loadLog()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'پاک کردن لاگ ناموفق بود'
  }
}

async function copyAll() {
  copying.value = true
  feedback.value = ''
  error.value = ''
  try {
    const payload = text.value || entries.value.map((e) => e.line).reverse().join('\n')
    await navigator.clipboard.writeText(payload)
    feedback.value = 'کل لاگ کپی شد — می‌توانید برای دیباگ بفرستید'
  } catch {
    error.value = 'کپی در کلیپ‌بورد ممکن نشد'
  } finally {
    copying.value = false
  }
}

function startPoll() {
  stopPoll()
  if (!autoRefresh.value) return
  pollTimer = setInterval(() => {
    void loadLog()
  }, 2000)
}

function stopPoll() {
  if (pollTimer != null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function onAutoRefreshChange() {
  startPoll()
}

onMounted(() => {
  void loadLog()
  startPoll()
})

onUnmounted(() => {
  stopPoll()
})
</script>

<template>
  <div class="page">
    <NavBar />
    <div class="content">
      <div class="card">
        <h1>لاگ پردازش پیامک</h1>
        <p class="hint">
          ردپای کامل پردازش ثبت سریال برای همهٔ حالت‌ها (۱، ۲، ۳) — شامل جلسهٔ مرحله‌ای،
          پارس فیلدها، و نتیجهٔ هر پیام. برای پیدا کردن باگ، یک سناریو را اجرا کنید، سپس
          «کپی همه» را بزنید و متن را بفرستید.
        </p>

        <div class="toolbar">
          <label class="filter">
            فیلتر شماره
            <input
              v-model="phoneFilter"
              type="text"
              inputmode="tel"
              placeholder="مثلاً 0912…"
              @keyup.enter="loadLog"
            />
          </label>
          <label class="auto">
            <input v-model="autoRefresh" type="checkbox" @change="onAutoRefreshChange" />
            تازه‌سازی خودکار
          </label>
          <button type="button" @click="loadLog" :disabled="loading">بروزرسانی</button>
          <button type="button" class="primary" @click="copyAll" :disabled="copying || !text">
            {{ copying ? '…' : 'کپی همه' }}
          </button>
          <button type="button" class="danger" @click="clearLog">پاک کردن لاگ</button>
        </div>

        <p v-if="error" class="error">{{ error }}</p>
        <p v-if="feedback" class="success">{{ feedback }}</p>
        <p class="meta">{{ count }} خط (به ترتیب زمان — قدیمی‌تر بالا)</p>

        <pre v-if="entries.length" class="log-pane" dir="ltr">{{ text }}</pre>
        <p v-else class="hint">
          هنوز لاگی نیست. یک پیامک ثبت سریال بفرستید؛ اینجا پر می‌شود.
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  align-items: flex-end;
  margin: 1rem 0;
}

.filter {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  font-size: 0.9rem;
}

.filter input {
  min-width: 10rem;
  padding: 0.35rem 0.5rem;
}

.auto {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  font-size: 0.9rem;
  margin-bottom: 0.2rem;
}

.danger {
  background: #b33;
  color: #fff;
  border: none;
  border-radius: 6px;
  padding: 0.45rem 0.85rem;
  cursor: pointer;
}

.meta {
  color: #666;
  font-size: 0.85rem;
  margin: 0.25rem 0 0.75rem;
}

.log-pane {
  margin: 0;
  padding: 0.85rem 1rem;
  background: #111;
  color: #d6ffd6;
  border-radius: 8px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.78rem;
  line-height: 1.45;
  max-height: 70vh;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
