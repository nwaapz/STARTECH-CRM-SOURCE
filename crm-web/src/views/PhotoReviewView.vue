<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import NavBar from '@/components/NavBar.vue'
import {
  approveSerialPhoto,
  fetchSerialPhotoBlobUrl,
  fetchSerialPhotoSubmissions,
  rejectSerialPhoto,
} from '@/api/client'
import type { SerialPhotoSubmission } from '@/api/types'
import { formatDateTimeFa } from '@/utils/formatCommunication'
import { formatPhoneForDisplay } from '@/utils/formatPhone'

const REJECT_REASONS = [
  { code: 'unclear', label: 'واضح نیست' },
  { code: 'not_related', label: 'مربوط نیست' },
  { code: 'wrong_serial', label: 'شماره اشتباه' },
  { code: 'other', label: 'سایر' },
] as const

const statusFilter = ref<'pending' | 'approved' | 'rejected' | ''>('pending')
const items = ref<SerialPhotoSubmission[]>([])
const total = ref(0)
const page = ref(1)
const limit = 20
const loading = ref(false)
const error = ref('')
const actionId = ref<number | null>(null)
const blobUrls = ref<Record<number, string>>({})

/** Zoom lightbox */
const zoomOpen = ref(false)
const zoomSrc = ref('')
const zoomAlt = ref('')
const zoomScale = ref(1)
const zoomX = ref(0)
const zoomY = ref(0)
const dragging = ref(false)
let dragStartX = 0
let dragStartY = 0
let dragOriginX = 0
let dragOriginY = 0

/** Custom reject ("other") modal */
const otherRejectOpen = ref(false)
const otherRejectItem = ref<SerialPhotoSubmission | null>(null)
const otherRejectNote = ref('')

const zoomTransform = computed(
  () => `translate(${zoomX.value}px, ${zoomY.value}px) scale(${zoomScale.value})`,
)

function channelLabel(channel: string): string {
  switch (channel) {
    case 'rubika':
      return 'روبیکا'
    case 'telegram':
      return 'تلگرام'
    case 'bale':
      return 'بله'
    default:
      return channel
  }
}

function statusLabel(status: string): string {
  switch (status) {
    case 'pending':
      return 'در انتظار'
    case 'approved':
      return 'تأیید شده'
    case 'rejected':
      return 'رد شده'
    default:
      return status
  }
}

function reasonLabel(note?: string | null): string {
  if (!note) return '—'
  const parts = note.split('|').map((s) => s.trim()).filter(Boolean)
  const code = parts[0]
  const custom = parts.slice(1).join(' | ')
  const label = REJECT_REASONS.find((r) => r.code === code)?.label ?? note
  if (custom) return `${label}: ${custom}`
  return label
}

function formatWhen(ts: number): string {
  const { date, time } = formatDateTimeFa(ts)
  return `${date} ${time}`
}

function revokeBlobs() {
  Object.values(blobUrls.value).forEach((url) => URL.revokeObjectURL(url))
  blobUrls.value = {}
}

async function loadImages(list: SerialPhotoSubmission[]) {
  revokeBlobs()
  const next: Record<number, string> = {}
  await Promise.all(
    list.map(async (item) => {
      try {
        next[item.id] = await fetchSerialPhotoBlobUrl(item.id)
      } catch {
        /* ignore single image failure */
      }
    }),
  )
  blobUrls.value = next
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const response = await fetchSerialPhotoSubmissions({
      status: statusFilter.value || undefined,
      page: page.value,
      limit,
    })
    items.value = response.items
    total.value = response.total
    await loadImages(response.items)
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در بارگذاری'
  } finally {
    loading.value = false
  }
}

async function approve(item: SerialPhotoSubmission) {
  actionId.value = item.id
  error.value = ''
  try {
    await approveSerialPhoto(item.id)
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در تأیید'
  } finally {
    actionId.value = null
  }
}

async function reject(item: SerialPhotoSubmission, reasonCode: string, note?: string) {
  actionId.value = item.id
  error.value = ''
  try {
    await rejectSerialPhoto(item.id, reasonCode, note)
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در رد'
  } finally {
    actionId.value = null
  }
}

function onRejectClick(item: SerialPhotoSubmission, reasonCode: string) {
  if (reasonCode === 'other') {
    otherRejectItem.value = item
    otherRejectNote.value = ''
    otherRejectOpen.value = true
    return
  }
  void reject(item, reasonCode)
}

function closeOtherReject() {
  if (actionId.value != null) return
  otherRejectOpen.value = false
  otherRejectItem.value = null
  otherRejectNote.value = ''
}

async function confirmOtherReject() {
  const item = otherRejectItem.value
  const note = otherRejectNote.value.trim()
  if (!item) return
  if (!note) {
    error.value = 'لطفاً پیام سفارشی برای کاربر را وارد کنید'
    return
  }
  await reject(item, 'other', note)
  otherRejectOpen.value = false
  otherRejectItem.value = null
  otherRejectNote.value = ''
}

function openZoom(item: SerialPhotoSubmission) {
  const src = blobUrls.value[item.id]
  if (!src) return
  zoomSrc.value = src
  zoomAlt.value = item.serial
  zoomScale.value = 1
  zoomX.value = 0
  zoomY.value = 0
  zoomOpen.value = true
}

function closeZoom() {
  zoomOpen.value = false
  zoomSrc.value = ''
  zoomAlt.value = ''
  zoomScale.value = 1
  zoomX.value = 0
  zoomY.value = 0
  dragging.value = false
}

function clampZoom(scale: number): number {
  return Math.min(5, Math.max(1, scale))
}

function zoomBy(delta: number) {
  const next = clampZoom(zoomScale.value + delta)
  zoomScale.value = next
  if (next <= 1) {
    zoomX.value = 0
    zoomY.value = 0
  }
}

function onZoomWheel(e: WheelEvent) {
  e.preventDefault()
  zoomBy(e.deltaY < 0 ? 0.25 : -0.25)
}

function onZoomPointerDown(e: PointerEvent) {
  if (zoomScale.value <= 1) return
  dragging.value = true
  dragStartX = e.clientX
  dragStartY = e.clientY
  dragOriginX = zoomX.value
  dragOriginY = zoomY.value
  ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
}

function onZoomPointerMove(e: PointerEvent) {
  if (!dragging.value) return
  zoomX.value = dragOriginX + (e.clientX - dragStartX)
  zoomY.value = dragOriginY + (e.clientY - dragStartY)
}

function onZoomPointerUp(e: PointerEvent) {
  dragging.value = false
  try {
    ;(e.currentTarget as HTMLElement).releasePointerCapture(e.pointerId)
  } catch {
    /* already released */
  }
}

function onZoomDblClick() {
  if (zoomScale.value > 1) {
    zoomScale.value = 1
    zoomX.value = 0
    zoomY.value = 0
  } else {
    zoomScale.value = 2.5
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key !== 'Escape') return
  if (zoomOpen.value) {
    closeZoom()
    return
  }
  if (otherRejectOpen.value) closeOtherReject()
}

watch(statusFilter, () => {
  page.value = 1
  void load()
})

onMounted(() => {
  void load()
  window.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  revokeBlobs()
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div>
    <NavBar />
    <div class="page">
      <div class="card">
        <h1>تأیید عکس محصول</h1>
        <p class="hint">
          پس از تأیید یا رد، پیام مناسب برای کاربر در روبیکا/تلگرام/بله ارسال می‌شود. در رد، دلیل را
          انتخاب کنید؛ به کاربر گفته می‌شود در صورت نیاز با پشتیبانی تماس بگیرد. برای «سایر» می‌توانید
          پیام سفارشی بنویسید. روی تصویر کلیک کنید تا بزرگ‌نمایی شود.
        </p>
        <div class="filters">
          <label>
            وضعیت
            <select v-model="statusFilter">
              <option value="pending">در انتظار</option>
              <option value="approved">تأیید شده</option>
              <option value="rejected">رد شده</option>
              <option value="">همه</option>
            </select>
          </label>
          <button class="secondary" :disabled="loading" @click="load">بروزرسانی</button>
        </div>
        <p v-if="error" class="error">{{ error }}</p>
        <p v-if="loading" class="hint">در حال بارگذاری…</p>
        <p v-else-if="items.length === 0" class="hint">موردی یافت نشد.</p>
        <div v-else class="photo-grid">
          <article v-for="item in items" :key="item.id" class="photo-card">
            <button
              v-if="blobUrls[item.id]"
              type="button"
              class="photo-frame photo-frame-btn"
              :aria-label="`بزرگ‌نمایی عکس ${item.serial}`"
              @click="openZoom(item)"
            >
              <img :src="blobUrls[item.id]" :alt="item.serial" />
            </button>
            <div v-else class="photo-frame">
              <p class="hint">بدون تصویر</p>
            </div>
            <div class="photo-meta">
              <p><strong>سریال:</strong> {{ item.serial }}</p>
              <p><strong>گروه:</strong> {{ item.groupLabel ?? '—' }}</p>
              <p><strong>شماره:</strong> {{ formatPhoneForDisplay(item.phone) }}</p>
              <p><strong>کانال:</strong> {{ channelLabel(item.channel) }}</p>
              <p><strong>وضعیت:</strong> {{ statusLabel(item.status) }}</p>
              <p><strong>امتیاز گروه:</strong> {{ item.score.toLocaleString('fa-IR') }}</p>
              <p v-if="item.status === 'approved'">
                <strong>امتیاز داده‌شده:</strong>
                {{ item.scoreAwarded.toLocaleString('fa-IR') }}
              </p>
              <p v-if="item.status === 'rejected'">
                <strong>دلیل رد:</strong> {{ reasonLabel(item.reviewNote) }}
              </p>
              <p class="hint">ارسال: {{ formatWhen(item.createdAt) }}</p>
            </div>
            <div v-if="item.status === 'pending'" class="actions-col">
              <button
                class="primary"
                :disabled="actionId === item.id"
                @click="approve(item)"
              >
                تأیید و اعطای امتیاز
              </button>
              <p class="hint reject-label">رد با دلیل:</p>
              <div class="reject-grid">
                <button
                  v-for="reason in REJECT_REASONS"
                  :key="reason.code"
                  type="button"
                  class="danger reject-btn"
                  :disabled="actionId === item.id"
                  @click="onRejectClick(item, reason.code)"
                >
                  {{ reason.label }}
                </button>
              </div>
            </div>
          </article>
        </div>
        <div class="actions">
          <button class="secondary" :disabled="page <= 1 || loading" @click="page--; load()">
            قبلی
          </button>
          <span class="hint">
            صفحه {{ page.toLocaleString('fa-IR') }} —
            {{ total.toLocaleString('fa-IR') }} مورد
          </span>
          <button
            class="secondary"
            :disabled="page * limit >= total || loading"
            @click="page++; load()"
          >
            بعدی
          </button>
        </div>
      </div>
    </div>

    <!-- Image zoom lightbox -->
    <Teleport to="body">
      <div
        v-if="zoomOpen"
        class="modal-overlay zoom-overlay"
        role="dialog"
        aria-modal="true"
        aria-label="بزرگ‌نمایی تصویر"
        @click="closeZoom"
      >
        <div class="zoom-toolbar" @click.stop>
          <button type="button" class="secondary" @click="zoomBy(-0.5)">−</button>
          <span class="hint">{{ Math.round(zoomScale * 100) }}٪</span>
          <button type="button" class="secondary" @click="zoomBy(0.5)">+</button>
          <button type="button" class="secondary" @click="zoomScale = 1; zoomX = 0; zoomY = 0">
            بازنشانی
          </button>
          <button type="button" class="modal-close" aria-label="بستن" @click="closeZoom">×</button>
        </div>
        <div
          class="zoom-stage"
          :class="{ dragging, zoomed: zoomScale > 1 }"
          @click.stop
          @wheel="onZoomWheel"
          @pointerdown="onZoomPointerDown"
          @pointermove="onZoomPointerMove"
          @pointerup="onZoomPointerUp"
          @pointercancel="onZoomPointerUp"
          @dblclick.prevent="onZoomDblClick"
        >
          <img
            :src="zoomSrc"
            :alt="zoomAlt"
            class="zoom-img"
            :style="{ transform: zoomTransform }"
            draggable="false"
          />
        </div>
        <p class="zoom-hint hint" @click.stop>
          اسکرول برای زوم · دابل‌کلیک برای زوم/بازنشانی · کشیدن برای جابه‌جایی
        </p>
      </div>
    </Teleport>

    <!-- Custom reject reason modal -->
    <Teleport to="body">
      <div
        v-if="otherRejectOpen"
        class="modal-overlay"
        @click="closeOtherReject"
      >
        <div
          class="modal-panel other-reject-panel"
          role="dialog"
          aria-modal="true"
          @click.stop
        >
          <div class="modal-header">
            <h2>رد با دلیل سایر</h2>
            <button
              type="button"
              class="modal-close"
              aria-label="بستن"
              :disabled="actionId != null"
              @click="closeOtherReject"
            >
              ×
            </button>
          </div>
          <div class="modal-body">
            <p class="hint">
              پیام سفارشی برای کاربر
              <template v-if="otherRejectItem">
                (سریال {{ otherRejectItem.serial }})
              </template>
              را بنویسید. این متن در پیام رد ارسال می‌شود.
            </p>
            <textarea
              v-model="otherRejectNote"
              class="other-reject-textarea"
              rows="5"
              maxlength="500"
              placeholder="مثال: عکس از زاویه نامناسب گرفته شده؛ لطفاً از روبرو عکس بگیرید."
              :disabled="actionId != null"
            />
          </div>
          <div class="modal-actions">
            <button
              type="button"
              class="secondary"
              :disabled="actionId != null"
              @click="closeOtherReject"
            >
              انصراف
            </button>
            <button
              type="button"
              class="danger"
              :disabled="actionId != null || !otherRejectNote.trim()"
              @click="confirmOtherReject"
            >
              {{ actionId != null ? 'در حال رد…' : 'رد و ارسال پیام' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 1rem;
  align-items: flex-end;
  margin-bottom: 1rem;
}

.photo-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(16rem, 1fr));
  gap: 1rem;
}

.photo-card {
  border: 1px solid #e0e0e0;
  border-radius: 10px;
  padding: 0.75rem;
  background: #fafafa;
}

.photo-frame {
  aspect-ratio: 1;
  background: #eceff1;
  border-radius: 8px;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 0.75rem;
}

.photo-frame-btn {
  width: 100%;
  padding: 0;
  border: none;
  cursor: zoom-in;
}

.photo-frame-btn:hover {
  outline: 2px solid #1976d2;
  outline-offset: 1px;
}

.photo-frame img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  pointer-events: none;
}

.photo-meta p {
  margin: 0.2rem 0;
  font-size: 0.9rem;
}

.actions-col {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin-top: 0.5rem;
}

.reject-label {
  margin: 0.25rem 0 0;
}

.reject-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.4rem;
}

.reject-btn {
  font-size: 0.85rem;
  padding: 0.4rem 0.5rem;
}

.danger {
  background: #c62828;
  color: #fff;
  border: none;
}

.zoom-overlay {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  padding: 1rem;
  background: rgba(0, 0, 0, 0.88);
}

.zoom-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
  z-index: 2;
}

.zoom-toolbar .hint {
  color: #eee;
  min-width: 3rem;
  text-align: center;
}

.zoom-toolbar .modal-close {
  color: #fff;
  font-size: 1.5rem;
  margin-inline-start: 0.5rem;
}

.zoom-stage {
  flex: 1;
  width: min(96vw, 900px);
  height: min(75vh, 700px);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  touch-action: none;
  user-select: none;
  cursor: zoom-in;
}

.zoom-stage.zoomed {
  cursor: grab;
}

.zoom-stage.dragging {
  cursor: grabbing;
}

.zoom-img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  transform-origin: center center;
  transition: transform 0.05s linear;
  pointer-events: none;
}

.zoom-hint {
  color: #bbb;
  margin: 0;
  text-align: center;
  font-size: 0.85rem;
}

.other-reject-panel {
  width: min(28rem, 94vw);
}

.other-reject-textarea {
  width: 100%;
  margin-top: 0.75rem;
  padding: 0.65rem 0.75rem;
  border: 1px solid #cfd8dc;
  border-radius: 8px;
  font: inherit;
  resize: vertical;
  min-height: 6rem;
  box-sizing: border-box;
}

.other-reject-textarea:focus {
  outline: 2px solid #1976d2;
  outline-offset: 1px;
}
</style>
