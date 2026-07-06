<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import moment from 'moment-jalaali'
import { firstDayWeekOffset, formatJalaliDayValue } from '@/utils/jalali'

moment.loadPersian({ dialect: 'persian-modern' })

const POPOVER_WIDTH = 300

const props = defineProps<{
  modelValue: string
  placeholder?: string
  clearable?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const open = ref(false)
const rootEl = ref<HTMLElement | null>(null)
const popoverEl = ref<HTMLElement | null>(null)
const viewMonth = ref(moment())
const popoverStyle = ref<{ top: string; left: string; width: string }>({
  top: '0px',
  left: '0px',
  width: `${POPOVER_WIDTH}px`,
})

const weekdays = ['ش', 'ی', 'د', 'س', 'چ', 'پ', 'ج']

const monthTitle = computed(() => viewMonth.value.format('jMMMM jYYYY'))

const calendarDays = computed(() => {
  const year = viewMonth.value.jYear()
  const monthIndex = viewMonth.value.jMonth()
  const daysInMonth = moment.jDaysInMonth(year, monthIndex)
  const startOffset = firstDayWeekOffset(year, monthIndex)
  const cells: Array<{ label: string; value: string | null; selected: boolean }> = []

  for (let i = 0; i < startOffset; i++) {
    cells.push({ label: '', value: null, selected: false })
  }

  for (let day = 1; day <= daysInMonth; day++) {
    const value = formatJalaliDayValue(year, monthIndex, day)
    cells.push({
      label: String(day),
      value,
      selected: value === props.modelValue,
    })
  }

  return cells
})

function syncViewMonth() {
  if (props.modelValue) {
    const parsed = moment(props.modelValue, 'jYYYY/jMM/jDD', true)
    if (parsed.isValid()) {
      viewMonth.value = parsed.clone().startOf('jMonth')
    }
  }
}

function updatePopoverPosition() {
  if (!rootEl.value) return
  const rect = rootEl.value.getBoundingClientRect()
  let left = rect.left
  const maxLeft = window.innerWidth - POPOVER_WIDTH - 8
  if (left > maxLeft) left = Math.max(8, maxLeft)
  if (left < 8) left = 8
  popoverStyle.value = {
    top: `${rect.bottom + 4}px`,
    left: `${left}px`,
    width: `${POPOVER_WIDTH}px`,
  }
}

function toggleOpen() {
  if (!open.value) {
    syncViewMonth()
    updatePopoverPosition()
  }
  open.value = !open.value
}

function selectDay(value: string) {
  emit('update:modelValue', value)
  open.value = false
}

function clearValue() {
  emit('update:modelValue', '')
  open.value = false
}

function prevMonth() {
  viewMonth.value = viewMonth.value.clone().subtract(1, 'jMonth')
}

function nextMonth() {
  viewMonth.value = viewMonth.value.clone().add(1, 'jMonth')
}

function onDocumentMouseDown(event: MouseEvent) {
  if (!open.value) return
  const target = event.target as Node
  if (rootEl.value?.contains(target)) return
  if (popoverEl.value?.contains(target)) return
  open.value = false
}

function onViewportChange() {
  if (open.value) {
    updatePopoverPosition()
  }
}

watch(
  () => props.modelValue,
  () => {
    syncViewMonth()
  },
)

onMounted(() => {
  syncViewMonth()
  document.addEventListener('mousedown', onDocumentMouseDown)
  window.addEventListener('resize', onViewportChange)
  window.addEventListener('scroll', onViewportChange, true)
})

onUnmounted(() => {
  document.removeEventListener('mousedown', onDocumentMouseDown)
  window.removeEventListener('resize', onViewportChange)
  window.removeEventListener('scroll', onViewportChange, true)
})
</script>

<template>
  <div ref="rootEl" class="jalali-date-picker">
    <div class="jalali-date-picker-input-wrap">
      <input
        class="jalali-picker-input"
        type="text"
        readonly
        :placeholder="placeholder"
        :value="modelValue"
        @click="toggleOpen"
      />
      <button
        v-if="clearable !== false && modelValue"
        type="button"
        class="jalali-date-picker-clear"
        aria-label="پاک کردن تاریخ"
        @click.stop="clearValue"
      >
        ×
      </button>
    </div>

    <Teleport to="body">
      <div
        v-if="open"
        ref="popoverEl"
        class="jalali-date-picker-popover jalali-date-picker-popover--fixed"
        :style="popoverStyle"
        @mousedown.stop
      >
        <div class="jalali-date-picker-header">
          <button type="button" class="jalali-date-picker-nav" @click="prevMonth">‹</button>
          <span class="jalali-date-picker-title">{{ monthTitle }}</span>
          <button type="button" class="jalali-date-picker-nav" @click="nextMonth">›</button>
        </div>
        <div class="jalali-date-picker-weekdays">
          <span v-for="day in weekdays" :key="day">{{ day }}</span>
        </div>
        <div class="jalali-date-picker-grid">
          <template v-for="(cell, index) in calendarDays" :key="`${cell.value ?? 'empty'}-${index}`">
            <span v-if="!cell.value" class="jalali-date-picker-empty" aria-hidden="true" />
            <button
              v-else
              type="button"
              class="jalali-date-picker-day"
              :class="{ 'is-selected': cell.selected }"
              @click="selectDay(cell.value!)"
            >
              {{ cell.label }}
            </button>
          </template>
        </div>
      </div>
    </Teleport>
  </div>
</template>
