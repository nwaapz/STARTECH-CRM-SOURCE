<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import type { LotteryParticipant } from '@/api/client'
import { fireLotteryConfettiBurst } from '@/utils/lotteryConfetti'
import { ensureLotteryAudioContext, playLotteryTickSound } from '@/utils/lotterySfx'

const props = defineProps<{
  running: boolean
  winnerPhone: string | null
  participants: LotteryParticipant[]
}>()

const emit = defineEmits<{
  complete: []
  cancel: []
}>()

const PHONE_DIGIT_COUNT = 11
const MASKED_INDICES = new Set([4, 5, 6])
const GROUP_GAP_AFTER = new Set([3, 6])
const TOTAL_DURATION_MS = 30000
const CYCLE_INTERVAL_MS = 300
const COUNTDOWN_START = 30

const digits = ref<string[]>(Array.from({ length: PHONE_DIGIT_COUNT }, () => '–'))
const masked = ref<boolean[]>(Array.from({ length: PHONE_DIGIT_COUNT }, () => false))
const locked = ref(false)
const cycling = ref(false)
const countdown = ref(COUNTDOWN_START)
const statusText = ref('آماده قرعه‌کشی')

const slots = computed(() =>
  Array.from({ length: PHONE_DIGIT_COUNT }, (_, i) => ({
    index: i,
    gapBefore: GROUP_GAP_AFTER.has(i),
  })),
)

let timeouts: number[] = []
let intervals: number[] = []
let cancelled = false

function clearTimers() {
  for (const id of timeouts) clearTimeout(id)
  for (const id of intervals) clearInterval(id)
  timeouts = []
  intervals = []
}

function normalizePhoneDigits(phone: string): string[] {
  let raw = String(phone || '').replace(/\D/g, '')
  if (raw.length > PHONE_DIGIT_COUNT) {
    raw = raw.slice(-PHONE_DIGIT_COUNT)
  }
  while (raw.length < PHONE_DIGIT_COUNT) {
    raw = '0' + raw
  }
  return raw.split('')
}

function displayPhone(phone: string, maskMiddle: boolean) {
  const next = normalizePhoneDigits(phone)
  digits.value = next.map((d, i) => (maskMiddle && MASKED_INDICES.has(i) ? '*' : d))
  masked.value = next.map((_, i) => maskMiddle && MASKED_INDICES.has(i))
}

function pickRandomPhone(): string {
  const phones = props.participants.map((p) => p.phone).filter((p) => p.replace(/\D/g, '').length > 0)
  if (phones.length === 0) {
    return props.winnerPhone || '09000000000'
  }
  return phones[Math.floor(Math.random() * phones.length)]!
}

function stop(emitCancel = false) {
  if (cancelled) return
  cancelled = true
  clearTimers()
  cycling.value = false
  if (emitCancel) emit('cancel')
}

function startAnimation() {
  stop(false)
  cancelled = false
  locked.value = false
  cycling.value = true
  countdown.value = COUNTDOWN_START
  statusText.value = 'در حال قرعه‌کشی…'
  ensureLotteryAudioContext()

  const winner = props.winnerPhone
  if (!winner) {
    stop(false)
    return
  }

  displayPhone(pickRandomPhone(), false)
  playLotteryTickSound()

  const countdownId = window.setInterval(() => {
    if (cancelled) return
    countdown.value = Math.max(0, countdown.value - 1)
    if (countdown.value <= 0) {
      clearInterval(countdownId)
      intervals = intervals.filter((id) => id !== countdownId)
    }
  }, 1000)
  intervals.push(countdownId)

  const cycleId = window.setInterval(() => {
    if (cancelled) return
    displayPhone(pickRandomPhone(), false)
    cycling.value = true
    playLotteryTickSound()
    requestAnimationFrame(() => {
      cycling.value = false
    })
  }, CYCLE_INTERVAL_MS)
  intervals.push(cycleId)

  const doneId = window.setTimeout(() => {
    if (cancelled) return
    clearTimers()
    locked.value = true
    cycling.value = false
    countdown.value = 0
    statusText.value = 'برنده مشخص شد!'
    const winnerDigits = normalizePhoneDigits(winner)
    digits.value = winnerDigits.map((d, i) => (MASKED_INDICES.has(i) ? '*' : d))
    masked.value = winnerDigits.map((_, i) => MASKED_INDICES.has(i))
    fireLotteryConfettiBurst()
    emit('complete')
  }, TOTAL_DURATION_MS)
  timeouts.push(doneId)
}

watch(
  () => [props.running, props.winnerPhone] as const,
  ([running, winner]) => {
    if (running && winner) {
      startAnimation()
    } else if (!running) {
      stop(false)
    }
  },
)

onUnmounted(() => {
  stop(false)
})

defineExpose({ stop })
</script>

<template>
  <div class="lottery-stage">
    <p class="lottery-status">{{ statusText }}</p>
    <p class="lottery-countdown" :class="{ done: countdown === 0 && locked }">
      {{ countdown.toLocaleString('fa-IR') }}
    </p>
    <div class="lottery-digit-reel" dir="ltr" aria-live="polite">
      <template v-for="slot in slots" :key="slot.index">
        <span v-if="slot.gapBefore" class="lottery-digit-group-gap" aria-hidden="true" />
        <div
          class="lottery-digit-box"
          :class="{
            'is-masked': masked[slot.index],
            'is-locked': locked,
            'is-cycling': cycling && !locked,
          }"
        >
          {{ digits[slot.index] }}
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.lottery-stage {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.75rem;
  padding: 1rem 0.5rem;
}

.lottery-status {
  margin: 0;
  font-size: 0.95rem;
  color: #546e7a;
}

.lottery-countdown {
  margin: 0;
  font-size: 2rem;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: #1a1a1a;
  line-height: 1;
}

.lottery-countdown.done {
  color: #2e7d32;
}

.lottery-digit-reel {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  align-items: center;
  gap: 0.35rem;
  max-width: 100%;
}

.lottery-digit-group-gap {
  width: 0.55rem;
  flex-shrink: 0;
}

.lottery-digit-box {
  width: 2rem;
  height: 2.6rem;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #cfd8dc;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.06);
  font-family: ui-monospace, 'Cascadia Code', 'Segoe UI Mono', monospace;
  font-size: 1.35rem;
  font-weight: 700;
  color: #1a1a1a;
  transition: transform 0.12s ease, background 0.12s ease, border-color 0.12s ease;
}

.lottery-digit-box.is-cycling {
  transform: translateY(-2px);
  border-color: #90a4ae;
  background: #f5f7f8;
}

.lottery-digit-box.is-masked {
  color: #90a4ae;
}

.lottery-digit-box.is-locked {
  border-color: #2e7d32;
  background: #e8f5e9;
  animation: lottery-lock-pop 0.45s ease;
}

@keyframes lottery-lock-pop {
  0% {
    transform: scale(0.92);
  }
  60% {
    transform: scale(1.06);
  }
  100% {
    transform: scale(1);
  }
}

@media (max-width: 520px) {
  .lottery-digit-box {
    width: 1.55rem;
    height: 2.1rem;
    font-size: 1.05rem;
  }
  .lottery-digit-group-gap {
    width: 0.35rem;
  }
}
</style>
