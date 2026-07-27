<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  JALALI_MONTH_NAMES,
  currentJalaliYear,
  jalaliDaysInMonth,
  parseJalaliParts,
} from '@/utils/jalali'

const props = defineProps<{
  modelValue: string
  clearable?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const year = ref<number | null>(null)
const month = ref<number | null>(null)
const day = ref<number | null>(null)
const syncing = ref(false)

const monthOptions = JALALI_MONTH_NAMES.map((name, index) => ({
  value: index + 1,
  label: name,
}))

const yearOptions = computed(() => {
  const current = currentJalaliYear()
  const years: number[] = []
  for (let y = current + 1; y >= current - 20; y--) {
    years.push(y)
  }
  if (year.value && !years.includes(year.value)) {
    years.push(year.value)
    years.sort((a, b) => b - a)
  }
  return years
})

const dayOptions = computed(() => {
  const y = year.value ?? currentJalaliYear()
  const m = month.value ?? 1
  const count = jalaliDaysInMonth(y, m)
  return Array.from({ length: count }, (_, i) => i + 1)
})

function composeDate(y: number | null, m: number | null, d: number | null): string {
  if (!y || !m || !d) return ''
  return `${y}/${String(m).padStart(2, '0')}/${String(d).padStart(2, '0')}`
}

function applyFromModel(value: string) {
  syncing.value = true
  const parts = parseJalaliParts(value)
  year.value = parts?.year ?? null
  month.value = parts?.month ?? null
  day.value = parts?.day ?? null
  syncing.value = false
}

watch(
  () => props.modelValue,
  (value) => {
    const composed = composeDate(year.value, month.value, day.value)
    if (value === composed) return
    applyFromModel(value)
  },
  { immediate: true },
)

watch([year, month], () => {
  if (day.value == null) return
  const maxDay = jalaliDaysInMonth(year.value ?? currentJalaliYear(), month.value ?? 1)
  if (day.value > maxDay) {
    day.value = maxDay
  }
})

watch([year, month, day], () => {
  if (syncing.value) return
  emit('update:modelValue', composeDate(year.value, month.value, day.value))
})

function parseSelectNumber(event: Event): number | null {
  const raw = (event.target as HTMLSelectElement).value
  if (!raw) return null
  const num = Number.parseInt(raw, 10)
  return Number.isFinite(num) ? num : null
}

function onYearChange(event: Event) {
  year.value = parseSelectNumber(event)
}

function onMonthChange(event: Event) {
  month.value = parseSelectNumber(event)
}

function onDayChange(event: Event) {
  day.value = parseSelectNumber(event)
}

function clear() {
  year.value = null
  month.value = null
  day.value = null
}
</script>

<template>
  <div class="jalali-date-selects">
    <div class="date-selects-row">
      <select :value="year ?? ''" aria-label="سال" @change="onYearChange">
        <option value="">سال</option>
        <option v-for="y in yearOptions" :key="y" :value="y">{{ y }}</option>
      </select>
      <select :value="month ?? ''" aria-label="ماه" @change="onMonthChange">
        <option value="">ماه</option>
        <option v-for="m in monthOptions" :key="m.value" :value="m.value">
          {{ m.label }}
        </option>
      </select>
      <select :value="day ?? ''" aria-label="روز" @change="onDayChange">
        <option value="">روز</option>
        <option v-for="d in dayOptions" :key="d" :value="d">{{ d }}</option>
      </select>
    </div>
    <button
      v-if="clearable && modelValue"
      type="button"
      class="clear-btn"
      title="پاک کردن"
      @click="clear"
    >
      پاک کردن
    </button>
  </div>
</template>

<style scoped>
.jalali-date-selects {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}

.date-selects-row {
  display: grid;
  grid-template-columns: 1.1fr 1.4fr 1fr;
  gap: 0.4rem;
}

.date-selects-row select {
  width: 100%;
  min-width: 0;
}

.clear-btn {
  align-self: flex-start;
  padding: 0.2rem 0.45rem;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #1565c0;
  font: inherit;
  font-size: 0.82rem;
  cursor: pointer;
  text-decoration: underline;
}

.clear-btn:hover {
  color: #0d47a1;
}
</style>
