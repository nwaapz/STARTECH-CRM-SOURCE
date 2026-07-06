<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import NavBar from '@/components/NavBar.vue'
import { fetchSmsMessages, updateSmsMessages } from '@/api/client'
import type { SmsMessageDefinition } from '@/api/types'

const loading = ref(true)
const saving = ref(false)
const error = ref('')
const success = ref('')
const messages = ref<SmsMessageDefinition[]>([])

const SAMPLE_VALUES: Record<string, Record<string, string>> = {
  step_prompt_field: { '@1': 'شهر' },
  step_prompt_city_km: { '@1': 'تبریز', '@2': '3350' },
  combined_format_error: { '@1': 'M100', '@2': 'تبریز', '@3': '3350' },
  partial_missing_city_km: { '@1': 'M100 تبریز 3350' },
  partial_missing_city: { '@1': 'M100 تبریز' },
  km_invalid_with_example: { '@1': 'M100 3350' },
  completion_reminder: { '@1': 'شهر و کیلومتر', '@2': 'M100 تبریز 3350' },
  completion_reminder_step_combined: { '@1': 'M100', '@2': 'تبریز', '@3': '3350' },
  completion_reminder_step_sequential: {
    '@1': 'سریال: M100، شهر: تبریز، کیلومتر: 3350',
  },
}

const categories = computed(() => {
  const seen = new Set<string>()
  return messages.value
    .map((message) => message.categoryFa)
    .filter((category) => {
      if (seen.has(category)) return false
      seen.add(category)
      return true
    })
})

function messagesInCategory(category: string): SmsMessageDefinition[] {
  return messages.value.filter((message) => message.categoryFa === category)
}

function previewText(message: SmsMessageDefinition): string {
  let text = message.template
  const samples = SAMPLE_VALUES[message.id] ?? {}
  Object.entries(samples).forEach(([token, value]) => {
    text = text.split(token).join(value)
  })
  return text
}

async function loadMessages() {
  loading.value = true
  error.value = ''
  try {
    const response = await fetchSmsMessages()
    messages.value = response.messages
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در بارگذاری پیام‌ها'
  } finally {
    loading.value = false
  }
}

async function saveMessages() {
  saving.value = true
  error.value = ''
  success.value = ''
  try {
    const response = await updateSmsMessages({ messages: messages.value })
    messages.value = response.messages
    success.value = 'پیام‌ها ذخیره شد.'
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در ذخیره پیام‌ها'
  } finally {
    saving.value = false
  }
}

async function resetAll() {
  await loadMessages()
  success.value = ''
  error.value = ''
}

onMounted(loadMessages)
</script>

<template>
  <NavBar />
  <main class="page">
    <div class="card">
      <h1>پیام‌های مشتری</h1>
      <p class="hint">
        متن پیامک‌های ارسالی به مشتری را ویرایش کنید. برای مقادیر پویا از @1، @2 و … استفاده کنید.
      </p>

      <p v-if="loading">در حال بارگذاری…</p>

      <template v-else>
        <section v-for="category in categories" :key="category" class="message-category">
          <h2>{{ category }}</h2>

          <article v-for="message in messagesInCategory(category)" :key="message.id" class="message-block">
            <h3>{{ message.titleFa }}</h3>
            <p class="hint">{{ message.descriptionFa }}</p>

            <textarea
              v-model="message.template"
              class="message-template"
              dir="rtl"
              rows="3"
            />

            <ul v-if="message.placeholders.length" class="placeholder-hints">
              <li v-for="hint in message.placeholders" :key="hint.token">
                {{ hint.token }} — {{ hint.labelFa }}
              </li>
            </ul>

            <p class="message-preview">پیش‌نمایش: {{ previewText(message) }}</p>
          </article>
        </section>

        <p v-if="error" class="error">{{ error }}</p>
        <p v-if="success" class="success">{{ success }}</p>

        <div class="actions">
          <button type="button" :disabled="saving" @click="saveMessages">
            {{ saving ? 'در حال ذخیره…' : 'ذخیره پیام‌ها' }}
          </button>
          <button type="button" class="secondary" :disabled="loading || saving" @click="resetAll">
            بازنشانی
          </button>
        </div>
      </template>
    </div>
  </main>
</template>
