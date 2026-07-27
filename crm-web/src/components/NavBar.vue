<script setup lang="ts">
import { useRoute } from 'vue-router'
import { computed } from 'vue'
import { showWhatsAppNav } from '@/config/surface'

const route = useRoute()

const allLinks = [
  { to: '/', label: 'داشبورد' },
  { to: '/serials', label: 'تولید سریال' },
  { to: '/reports', label: 'گزارش‌ها' },
  { to: '/communications', label: 'ارتباطات' },
  { to: '/customers', label: 'باشگاه مشتریان' },
  { to: '/referral', label: 'دعوت پلکانی' },
  { to: '/photo-review', label: 'تأیید عکس' },
  { to: '/lottery', label: 'قرعه‌کشی' },
  { to: '/mass-sms', label: 'ارسال پیام انبوه' },
  { to: '/whatsapp', label: 'ارسال واتساپ', lanOnly: true as boolean | undefined },
  { to: '/bots', label: 'ربات‌ها', matchPrefix: true as boolean | undefined },
  { to: '/messages', label: 'تنظیمات پیام' },
  { to: '/settings', label: 'تنظیمات' },
  { to: '/admin-setup', label: 'راه‌اندازی ادمین' },
]

const links = computed(() =>
  allLinks.filter((link) => !(link.lanOnly && !showWhatsAppNav())),
)

function isActive(link: { to: string; matchPrefix?: boolean }) {
  if (link.matchPrefix) {
    return route.path === link.to || route.path.startsWith(`${link.to}/`)
  }
  return route.path === link.to
}
</script>

<template>
  <header class="nav">
    <div class="nav-top">
      <div class="brand">CRM Payamesh</div>
    </div>
    <nav class="nav-tabs" aria-label="منوی اصلی">
      <router-link
        v-for="link in links"
        :key="link.to"
        :to="link.to"
        class="nav-tab"
        :class="{ active: isActive(link) }"
      >
        <span class="nav-tab-label">{{ link.label }}</span>
      </router-link>
    </nav>
  </header>
</template>
