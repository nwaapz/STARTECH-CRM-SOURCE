<script setup lang="ts">
import NavBar from '@/components/NavBar.vue'

const botTabs = [
  { to: '/bots/rubika', label: 'روبیکا' },
  { to: '/bots/telegram', label: 'تلگرام' },
]
</script>

<template>
  <NavBar />
  <nav class="bot-tabs">
    <router-link
      v-for="tab in botTabs"
      :key="tab.to"
      :to="tab.to"
      class="bot-tab"
    >
      {{ tab.label }}
    </router-link>
  </nav>
  <router-view />
</template>

<style scoped>
.bot-tabs {
  display: flex;
  gap: 0.5rem;
  padding: 0.75rem 1rem 0;
  border-bottom: 1px solid #e0e0e0;
  background: #fafafa;
}

.bot-tab {
  padding: 0.5rem 1rem;
  border-radius: 8px 8px 0 0;
  color: #555;
  text-decoration: none;
  font-weight: 500;
  border: 1px solid transparent;
  border-bottom: none;
  margin-bottom: -1px;
}

.bot-tab.router-link-active {
  color: #0d47a1;
  background: #fff;
  border-color: #e0e0e0;
}
</style>
