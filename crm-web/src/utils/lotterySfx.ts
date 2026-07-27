/** Web Audio SFX for lottery draw — ported from old CRM lottery-draw-animation.js */

let audioCtx: AudioContext | null = null

export function ensureLotteryAudioContext(): AudioContext | null {
  try {
    const Ctx = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext
    if (!audioCtx) {
      audioCtx = new Ctx()
    }
    if (audioCtx.state === 'suspended') {
      void audioCtx.resume()
    }
    return audioCtx
  } catch {
    return null
  }
}

export function playLotteryTickSound(): void {
  const ctx = ensureLotteryAudioContext()
  if (!ctx) return
  const now = ctx.currentTime
  const osc = ctx.createOscillator()
  const gain = ctx.createGain()
  osc.connect(gain)
  gain.connect(ctx.destination)
  osc.type = 'square'
  osc.frequency.setValueAtTime(920, now)
  gain.gain.setValueAtTime(0.045, now)
  gain.gain.exponentialRampToValueAtTime(0.001, now + 0.06)
  osc.start(now)
  osc.stop(now + 0.06)
}

export function playLotteryConfettiSound(): void {
  const ctx = ensureLotteryAudioContext()
  if (!ctx) return

  const now = ctx.currentTime
  const masterGain = ctx.createGain()
  masterGain.connect(ctx.destination)
  masterGain.gain.setValueAtTime(0.28, now)
  masterGain.gain.exponentialRampToValueAtTime(0.001, now + 1.6)

  const fanfare: Array<{ freq: number; delay: number; type: OscillatorType; peak: number }> = [
    { freq: 523.25, delay: 0, type: 'triangle', peak: 0.32 },
    { freq: 659.25, delay: 0.09, type: 'triangle', peak: 0.34 },
    { freq: 783.99, delay: 0.18, type: 'triangle', peak: 0.36 },
    { freq: 1046.5, delay: 0.27, type: 'sine', peak: 0.38 },
    { freq: 1318.51, delay: 0.36, type: 'sine', peak: 0.4 },
  ]

  for (const { freq, delay, type, peak } of fanfare) {
    const start = now + delay
    const osc = ctx.createOscillator()
    const gain = ctx.createGain()
    osc.type = type
    osc.frequency.setValueAtTime(freq, start)
    gain.gain.setValueAtTime(0.001, start)
    gain.gain.linearRampToValueAtTime(peak, start + 0.04)
    gain.gain.exponentialRampToValueAtTime(0.001, start + 0.42)
    osc.connect(gain)
    gain.connect(masterGain)
    osc.start(start)
    osc.stop(start + 0.45)
  }

  ;[1046.5, 1318.51, 1567.98, 2093].forEach((freq, index) => {
    const start = now + 0.52 + index * 0.02
    const osc = ctx.createOscillator()
    const gain = ctx.createGain()
    osc.type = 'sine'
    osc.frequency.setValueAtTime(freq, start)
    gain.gain.setValueAtTime(0.001, start)
    gain.gain.linearRampToValueAtTime(0.1, start + 0.05)
    gain.gain.exponentialRampToValueAtTime(0.001, start + 1.1)
    osc.connect(gain)
    gain.connect(masterGain)
    osc.start(start)
    osc.stop(start + 1.15)
  })

  const bufferSize = Math.floor(ctx.sampleRate * 0.18)
  const buffer = ctx.createBuffer(1, bufferSize, ctx.sampleRate)
  const data = buffer.getChannelData(0)
  for (let i = 0; i < bufferSize; i++) {
    data[i] = (Math.random() * 2 - 1) * (1 - i / bufferSize)
  }
  const noise = ctx.createBufferSource()
  noise.buffer = buffer
  const noiseFilter = ctx.createBiquadFilter()
  noiseFilter.type = 'bandpass'
  noiseFilter.frequency.setValueAtTime(5200, now + 0.4)
  noiseFilter.Q.value = 0.8
  const noiseGain = ctx.createGain()
  noiseGain.gain.setValueAtTime(0.001, now + 0.4)
  noiseGain.gain.linearRampToValueAtTime(0.12, now + 0.43)
  noiseGain.gain.exponentialRampToValueAtTime(0.001, now + 0.75)
  noise.connect(noiseFilter)
  noiseFilter.connect(noiseGain)
  noiseGain.connect(masterGain)
  noise.start(now + 0.4)
  noise.stop(now + 0.78)
}
