<script setup lang="ts">
import { onMounted, ref } from 'vue'
import NavBar from '@/components/NavBar.vue'
import {
  changeCrmUserPassword,
  createCrmUser,
  deleteCrmUser,
  fetchCloudSyncStatus,
  fetchCrmUsers,
  putCloudSyncConfig,
  runCloudSyncNow,
  type CloudSyncStatus,
  type CrmUser,
} from '@/api/client'

const users = ref<CrmUser[]>([])
const sync = ref<CloudSyncStatus | null>(null)
const error = ref('')
const username = ref('')
const password = ref('')
const role = ref(4)
const syncUrl = ref('')
const syncToken = ref('')

async function load() {
  error.value = ''
  try {
    users.value = await fetchCrmUsers()
    sync.value = await fetchCloudSyncStatus()
    syncUrl.value = sync.value.url ?? ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا'
  }
}

async function addUser() {
  try {
    await createCrmUser({ username: username.value, password: password.value, role: role.value })
    username.value = ''
    password.value = ''
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا'
  }
}

async function removeUser(id: number) {
  if (!confirm('حذف کاربر؟')) return
  await deleteCrmUser(id)
  await load()
}

async function resetPassword(id: number) {
  const next = prompt('رمز جدید')
  if (!next || next.length < 4) return
  await changeCrmUserPassword(id, next)
}

async function saveSync() {
  await putCloudSyncConfig({ url: syncUrl.value, token: syncToken.value || undefined })
  await load()
}

async function syncNow() {
  await runCloudSyncNow()
  await load()
}

function roleLabel(r: number): string {
  if (r >= 5) return 'ابرمیر'
  if (r >= 4) return 'مدیر'
  return 'گزارش‌گیر'
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page">
    <NavBar />
    <main class="content">
      <h1>راه‌اندازی ادمین</h1>
      <p class="hint">مدیریت کاربران CRM، نقش‌ها، و همگام‌سازی با سرور آنلاین.</p>
      <p v-if="error" class="error">{{ error }}</p>

      <section class="card">
        <h2>کاربران</h2>
        <table>
          <thead>
            <tr><th>نام</th><th>نقش</th><th></th></tr>
          </thead>
          <tbody>
            <tr v-for="u in users" :key="u.id">
              <td>{{ u.username }}</td>
              <td>{{ roleLabel(u.role) }}</td>
              <td class="actions">
                <button type="button" class="secondary" @click="resetPassword(u.id)">رمز</button>
                <button type="button" class="danger" @click="removeUser(u.id)">حذف</button>
              </td>
            </tr>
          </tbody>
        </table>
        <div class="toolbar">
          <input v-model="username" placeholder="نام کاربری" />
          <input v-model="password" type="password" placeholder="رمز" />
          <select v-model.number="role">
            <option :value="1">گزارش‌گیر</option>
            <option :value="4">مدیر</option>
            <option :value="5">ابرمیر</option>
          </select>
          <button type="button" @click="addUser">افزودن</button>
        </div>
      </section>

      <section class="card">
        <h2>همگام‌سازی ابری (گوشی ↔ سرور)</h2>
        <p v-if="sync">
          وضعیت:
          {{ sync.configured ? 'پیکربندی شده' : 'پیکربندی نشده' }}
          — صف در انتظار: {{ sync.pendingOutbox }}
          — آخرین ارسال: {{ sync.lastPushMs || '—' }}
          — آخرین دریافت: {{ sync.lastPullMs || '—' }}
        </p>
        <div class="toolbar">
          <label class="grow">
            آدرس پایه (مثلاً https://startechgroup.ir/crm-php)
            <input v-model="syncUrl" />
          </label>
          <label class="grow">
            توکن دستگاه
            <input v-model="syncToken" type="password" placeholder="در صورت تغییر وارد کنید" />
          </label>
          <button type="button" class="secondary" @click="saveSync">ذخیره</button>
          <button type="button" @click="syncNow">همگام‌سازی الآن</button>
        </div>
      </section>
    </main>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-top: 0.75rem;
  align-items: end;
}
.grow {
  flex: 1 1 220px;
}
table {
  width: 100%;
  border-collapse: collapse;
}
th, td {
  text-align: right;
  padding: 0.4rem;
  border-bottom: 1px solid #e5e7eb;
}
.actions {
  display: flex;
  gap: 0.35rem;
}
.error {
  color: #b91c1c;
}
.danger {
  background: #b91c1c;
  color: #fff;
}
</style>
