<script setup lang="ts">
import { useRoute } from 'vue-router'

const route = useRoute()

const links = [
  { to: '/', label: 'داشبورد' },
  { to: '/serials', label: 'تولید سریال' },
  { to: '/reports', label: 'گزارش‌ها' },
  { to: '/communications', label: 'ارتباطات' },
  { to: '/mass-sms', label: 'ارسال پیام انبوه' },
  { to: '/bots', label: 'ربات‌ها', matchPrefix: true },
  { to: '/messages', label: 'تنظیمات پیام های ارسالی' },
  { to: '/settings', label: 'تنظیمات' },
]

function isActive(link: { to: string; matchPrefix?: boolean }) {
  if (link.matchPrefix) {
    return route.path === link.to || route.path.startsWith(`${link.to}/`)
  }
  return route.path === link.to
}
</script>

<template>
  <nav class="nav">
    <div class="brand">CRM درگاه</div>
    <div class="links">
      <router-link
        v-for="link in links"
        :key="link.to"
        :to="link.to"
        :class="{ active: isActive(link) }"
      >
        {{ link.label }}
      </router-link>
    </div>
  </nav>
</template>
