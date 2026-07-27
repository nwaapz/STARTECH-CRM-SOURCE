<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  fetchHealth,
  fetchPing,
  fetchSync,
  formatConnectionError,
  loginCrmUser,
  setPin,
  setSyncCursor,
} from '@/api/client'
import { isRemoteSurface } from '@/config/surface'

const router = useRouter()
const username = ref('admin')
const password = ref('')
const pin = ref('')
const usePin = ref(false)
const isRemote = isRemoteSurface()
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
    if (usePin.value) {
      setPin(pin.value)
    } else {
      const result = await loginCrmUser(username.value, password.value)
      setPin(result.token)
    }
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
      <h1>ورود به CRM Payamesh</h1>
      <p class="hint">
        <template v-if="isRemote">با نام کاربری و رمز وارد شوید.</template>
        <template v-else>با نام کاربری/رمز وارد شوید، یا از PIN شبکه محلی گوشی استفاده کنید.</template>
      </p>

      <div
        class="connection-status"
        :class="{
          checking: serverStatus === 'checking',
          online: serverStatus === 'online',
          offline: serverStatus === 'offline',
        }"
      >
        <template v-if="serverStatus === 'checking'">در حال بررسی اتصال به سرور…</template>
        <template v-else-if="serverStatus === 'online'">سرور CRM در دسترس است.</template>
        <template v-else-if="isRemote">
          سرور CRM در دسترس نیست. آدرس دامنه و پوشه /crm/api را بررسی کنید.
        </template>
        <template v-else>
          سرور CRM در دسترس نیست. هات‌اسپات گوشی و CRM شبکه محلی را بررسی کنید.
        </template>
      </div>

      <label v-if="!isRemote" class="check">
        <input v-model="usePin" type="checkbox" />
        ورود با PIN محلی
      </label>

      <template v-if="usePin">
        <label>
          PIN
          <input v-model="pin" type="password" inputmode="numeric" maxlength="12" />
        </label>
      </template>
      <template v-else>
        <label>
          نام کاربری
          <input v-model="username" autocomplete="username" />
        </label>
        <label>
          رمز
          <input v-model="password" type="password" autocomplete="current-password" />
        </label>
        <p class="hint">اولین اجرا: admin / admin123</p>
      </template>

      <p v-if="error" class="error">{{ error }}</p>
      <button type="button" class="primary" :disabled="loading" @click="submit">ورود</button>
    </div>
  </div>
</template>

<style scoped>
.check {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  margin-bottom: 0.75rem;
}
</style>
