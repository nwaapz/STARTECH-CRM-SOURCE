<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  fetchHealth,
  fetchPing,
  fetchSync,
  formatConnectionError,
  setPin,
  setSyncCursor,
} from '@/api/client'

const router = useRouter()
const pin = ref('')
const error = ref('')
const loading = ref(false)
const serverStatus = ref<'checking' | 'online' | 'offline'>('checking')

async function checkServer() {
  serverStatus.value = 'checking'
  serverStatus.value = (await fetchPing()) ? 'online' : 'offline'
}

onMounted(() => {
  void checkServer()
})

async function submit() {
  error.value = ''
  loading.value = true
  try {
    if (serverStatus.value !== 'online') {
      const reachable = await fetchPing()
      serverStatus.value = reachable ? 'online' : 'offline'
      if (!reachable) {
        error.value = formatConnectionError(new TypeError('Failed to fetch'))
        return
      }
    }
    setPin(pin.value)
    await fetchHealth()
    const sync = await fetchSync(0)
    setSyncCursor(sync.cursor)
    await router.push('/')
  } catch (e) {
    error.value = formatConnectionError(e)
    if (error.value.includes('سرور CRM')) {
      serverStatus.value = 'offline'
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page center">
    <div class="card login-card">
      <h1>ورود به CRM</h1>
      <p class="hint">رمز PIN را از تنظیمات گوشی (CRM شبکه محلی) وارد کنید.</p>

      <div
        class="connection-status"
        :class="{
          checking: serverStatus === 'checking',
          online: serverStatus === 'online',
          offline: serverStatus === 'offline',
        }"
      >
        <template v-if="serverStatus === 'checking'">در حال بررسی اتصال به سرور…</template>
        <template v-else-if="serverStatus === 'online'">سرور CRM در دسترس است — PIN را وارد کنید.</template>
        <template v-else>
          سرور CRM در دسترس نیست. هات‌اسپات گوشی را روشن کنید، PC را به Wi‑Fi گوشی وصل کنید،
          درگاه را در اپ روشن کنید، و «CRM شبکه محلی» را فعال کنید.
        </template>
      </div>

      <label>
        PIN
        <input v-model="pin" type="password" inputmode="numeric" maxlength="12" />
      </label>
      <p v-if="error" class="error">{{ error }}</p>
      <div class="login-actions">
        <button class="primary" :disabled="loading || !pin" @click="submit">
          {{ loading ? 'در حال اتصال...' : 'ورود' }}
        </button>
        <button class="secondary" type="button" :disabled="loading" @click="checkServer">
          بررسی مجدد
        </button>
      </div>
    </div>
  </div>
</template>
