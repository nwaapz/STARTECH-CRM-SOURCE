import confetti from 'canvas-confetti'
import { playLotteryConfettiSound } from '@/utils/lotterySfx'

/** Side cannons + center burst — matches old CRM fireConfettiBurst. */
export function fireLotteryConfettiBurst(): void {
  playLotteryConfettiSound()

  const duration = 2500
  const end = Date.now() + duration

  const frame = () => {
    confetti({
      particleCount: 4,
      angle: 60,
      spread: 55,
      origin: { x: 0, y: 0.65 },
      colors: ['#2e7d32', '#1565c0', '#f9a825', '#c62828', '#6a1b9a'],
    })
    confetti({
      particleCount: 4,
      angle: 120,
      spread: 55,
      origin: { x: 1, y: 0.65 },
      colors: ['#2e7d32', '#1565c0', '#f9a825', '#c62828', '#6a1b9a'],
    })
    if (Date.now() < end) {
      requestAnimationFrame(frame)
    }
  }
  frame()

  confetti({
    particleCount: 120,
    spread: 100,
    origin: { y: 0.55 },
    colors: ['#2e7d32', '#1565c0', '#f9a825', '#c62828', '#6a1b9a'],
  })
}
