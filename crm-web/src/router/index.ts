import { createRouter, createWebHistory } from 'vue-router'
import { getPin } from '@/api/client'
import DashboardView from '@/views/DashboardView.vue'
import SerialsView from '@/views/SerialsView.vue'
import ReportsView from '@/views/ReportsView.vue'
import LoginView from '@/views/LoginView.vue'
import SettingsView from '@/views/SettingsView.vue'
import MessagesView from '@/views/MessagesView.vue'
import CommunicationView from '@/views/CommunicationView.vue'
import MassSmsView from '@/views/MassSmsView.vue'
import CustomersView from '@/views/CustomersView.vue'
import BotsView from '@/views/BotsView.vue'
import RubikaView from '@/views/RubikaView.vue'
import TelegramView from '@/views/TelegramView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView },
    { path: '/', name: 'dashboard', component: DashboardView },
    { path: '/serials', name: 'serials', component: SerialsView },
    { path: '/reports', name: 'reports', component: ReportsView },
    { path: '/communications', name: 'communications', component: CommunicationView },
    { path: '/customers', name: 'customers', component: CustomersView },
    { path: '/mass-sms', name: 'mass-sms', component: MassSmsView },
    {
      path: '/bots',
      component: BotsView,
      redirect: '/bots/rubika',
      children: [
        { path: 'rubika', name: 'bots-rubika', component: RubikaView },
        { path: 'telegram', name: 'bots-telegram', component: TelegramView },
      ],
    },
    { path: '/rubika', redirect: '/bots/rubika' },
    { path: '/telegram', redirect: '/bots/telegram' },
    { path: '/settings', name: 'settings', component: SettingsView },
    { path: '/messages', name: 'messages', component: MessagesView },
  ],
})

router.beforeEach(async (to) => {
  if (to.name === 'login') return true
  if (!getPin()) return { name: 'login' }
  return true
})

export default router
