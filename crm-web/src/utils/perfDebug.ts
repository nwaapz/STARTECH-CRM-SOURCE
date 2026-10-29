import { ref } from 'vue'

export interface DebugStep {
  label: string
  ms: number
  cumulativeMs: number
  detail?: string
}

export interface ServerDebug {
  path: string
  method: string
  totalMs: number
  steps: DebugStep[]
  php?: string
  memoryMb?: number
}

export interface PerfEntry {
  id: string
  at: number
  method: string
  path: string
  clientMs: number
  status: number
  server?: ServerDebug
  error?: string
}

export interface PageNote {
  at: number
  text: string
}

const STORAGE_KEY = 'crm_perf_debug'

export const perfEntries = ref<PerfEntry[]>([])
export const pageNotes = ref<PageNote[]>([])

export function isPerfDebugEnabled(): boolean {
  if (typeof window === 'undefined') return false
  const params = new URLSearchParams(window.location.search)
  if (params.get('debug') === '0') {
    sessionStorage.removeItem(STORAGE_KEY)
    return false
  }
  if (params.get('debug') === '1') {
    sessionStorage.setItem(STORAGE_KEY, '1')
    return true
  }
  // Remote host build: on by default (opt out with ?debug=0).
  if (import.meta.env.VITE_CRM_SURFACE === 'remote') return true
  return sessionStorage.getItem(STORAGE_KEY) === '1'
}

export function recordPerfEntry(entry: Omit<PerfEntry, 'id' | 'at'>): void {
  if (!isPerfDebugEnabled()) return
  perfEntries.value = [
    { ...entry, id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`, at: Date.now() },
    ...perfEntries.value,
  ].slice(0, 100)
}

export function perfPageNote(text: string): void {
  if (!isPerfDebugEnabled()) return
  pageNotes.value = [{ at: Date.now(), text }, ...pageNotes.value].slice(0, 50)
}

export function clearPerfDebug(): void {
  perfEntries.value = []
  pageNotes.value = []
}

export function formatMs(ms: number): string {
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)}s`
  return `${Math.round(ms)}ms`
}
