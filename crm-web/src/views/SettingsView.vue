<script setup lang="ts">
import { onMounted, ref } from 'vue'
import NavBar from '@/components/NavBar.vue'
import { fetchSmsProcessingSettings, updateSmsProcessingSettings } from '@/api/client'
import type { SmsField, SmsProcessingMode, SmsProcessingSettings, StepFlowType } from '@/api/types'

const FIELD_LABELS: Record<SmsField, string> = {
  SERIAL: 'سریال',
  CITY: 'شهر',
  KM: 'کیلومتر',
}

const COMBINED_MODE_EXAMPLE = 'مثال: M100 تبریز 3350'

const loading = ref(true)
const saving = ref(false)
const error = ref('')
const success = ref('')
const config = ref<SmsProcessingSettings>({
  mode: 'SERIAL_ONLY',
  separator: '#',
  combinedPattern: ['SERIAL', 'KM', 'CITY'],
  stepFieldOrder: ['SERIAL', 'CITY', 'KM'],
  stepFlowType: 'SEQUENTIAL',
  sessionTimeoutMinutes: 30,
})

const dragIndex = ref<number | null>(null)

function fieldLabel(field: SmsField): string {
  return FIELD_LABELS[field]
}

function normalizeForSave(settings: SmsProcessingSettings): SmsProcessingSettings {
  if (settings.mode === 'COMBINED_PATTERN') {
    return {
      ...settings,
      combinedPattern: ['SERIAL', 'KM', 'CITY'],
    }
  }
  return settings
}

async function loadSettings() {
  loading.value = true
  error.value = ''
  try {
    config.value = await fetchSmsProcessingSettings()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در بارگذاری تنظیمات'
  } finally {
    loading.value = false
  }
}

async function saveSettings() {
  saving.value = true
  error.value = ''
  success.value = ''
  try {
    config.value = await updateSmsProcessingSettings(normalizeForSave(config.value))
    success.value = 'تنظیمات ذخیره شد.'
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'خطا در ذخیره تنظیمات'
  } finally {
    saving.value = false
  }
}

function setMode(mode: SmsProcessingMode) {
  config.value.mode = mode
}

function setStepFlowType(flowType: StepFlowType) {
  config.value.stepFlowType = flowType
}

function moveStepField(fromIndex: number, toIndex: number) {
  if (fromIndex === toIndex) return
  if (fromIndex < 1 || toIndex < 1) return
  const list = [...config.value.stepFieldOrder]
  if (toIndex < 0 || toIndex >= list.length) return
  const [item] = list.splice(fromIndex, 1)
  list.splice(toIndex, 0, item)
  config.value.stepFieldOrder = list
}

function onDragStart(index: number, event: DragEvent) {
  if (index < 1) {
    event.preventDefault()
    return
  }
  dragIndex.value = index
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', String(index))
  }
}

function onDragOver(event: DragEvent) {
  event.preventDefault()
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = 'move'
  }
}

function onDrop(targetIndex: number) {
  if (dragIndex.value === null) return
  if (targetIndex < 1) return
  moveStepField(dragIndex.value, targetIndex)
  dragIndex.value = null
}

function onDragEnd() {
  dragIndex.value = null
}

onMounted(loadSettings)
</script>

<template>
  <div>
    <NavBar />
    <div class="page">
      <div class="card">
        <h1>تنظیمات پردازش پیامک</h1>
        <p class="hint">نحوه دریافت سریال، شهر و کیلومتر از مشتری را تنظیم کنید.</p>

        <p v-if="loading" class="hint">در حال بارگذاری...</p>
        <template v-else>
          <section class="section">
            <h2>حالت پردازش</h2>
            <label class="radio-row">
              <input
                type="radio"
                name="mode"
                :checked="config.mode === 'SERIAL_ONLY'"
                @change="setMode('SERIAL_ONLY')"
              />
              <span>
                <strong>حالت ۱: فقط سریال</strong>
                <small>کل پیامک به عنوان شماره سریال بررسی می‌شود.</small>
              </span>
            </label>
            <label class="radio-row">
              <input
                type="radio"
                name="mode"
                :checked="config.mode === 'COMBINED_PATTERN'"
                @change="setMode('COMBINED_PATTERN')"
              />
              <span>
                <strong>حالت ۲: ترکیبی (یک پیامک)</strong>
                <small>ترتیب ارسال مهم نیست. عدد = کیلومتر، متن = شهر، سریال باید در پایگاه موجود باشد.</small>
              </span>
            </label>
            <p v-if="config.mode === 'COMBINED_PATTERN'" class="hint">{{ COMBINED_MODE_EXAMPLE }}</p>
            <label class="radio-row">
              <input
                type="radio"
                name="mode"
                :checked="config.mode === 'STEP_BY_STEP'"
                @change="setMode('STEP_BY_STEP')"
              />
              <span>
                <strong>حالت ۳: مرحله‌ای</strong>
                <small>ابتدا سریال، سپس سایر اطلاعات در پیام‌های بعدی.</small>
              </span>
            </label>
          </section>

          <section v-if="config.mode === 'STEP_BY_STEP'" class="section">
            <h2>نوع جریان مرحله‌ای</h2>
            <label class="radio-row">
              <input
                type="radio"
                name="stepFlow"
                :checked="config.stepFlowType === 'SEQUENTIAL'"
                @change="setStepFlowType('SEQUENTIAL')"
              />
              <span>
                <strong>پرسش جداگانه</strong>
                <small>پس از سریال، هر فیلد در یک پیام جدا پرسیده می‌شود.</small>
              </span>
            </label>
            <label class="radio-row">
              <input
                type="radio"
                name="stepFlow"
                :checked="config.stepFlowType === 'COMBINED_SECOND'"
                @change="setStepFlowType('COMBINED_SECOND')"
              />
              <span>
                <strong>ترکیبی در پیام دوم</strong>
                <small>پس از سریال، شهر و کیلومتر در یک پیام ارسال می‌شوند. عدد = کیلومتر، متن = شهر (بدون جداکننده).</small>
              </span>
            </label>

            <template v-if="config.stepFlowType === 'SEQUENTIAL'">
              <h2>ترتیب مراحل</h2>
              <p class="hint">ترتیب پرسش پس از سریال. برای تغییر، بکشید یا از دکمه‌های بالا/پایین استفاده کنید.</p>
              <div class="pattern-list">
                <div
                  v-for="(field, index) in config.stepFieldOrder"
                  :key="`${field}-${index}`"
                  class="pattern-item"
                  :class="{ locked: index === 0 && field === 'SERIAL' }"
                  :draggable="index > 0"
                  @dragstart="onDragStart(index, $event)"
                  @dragover="onDragOver"
                  @drop="onDrop(index)"
                  @dragend="onDragEnd"
                >
                  <span v-if="index > 0" class="drag-handle">☰</span>
                  <span class="field-label">{{ fieldLabel(field) }}</span>
                  <div v-if="index > 0" class="move-buttons">
                    <button
                      type="button"
                      class="move-btn"
                      :disabled="index <= 1"
                      @click="moveStepField(index, index - 1)"
                    >
                      ↑
                    </button>
                    <button
                      type="button"
                      class="move-btn"
                      :disabled="index === config.stepFieldOrder.length - 1"
                      @click="moveStepField(index, index + 1)"
                    >
                      ↓
                    </button>
                  </div>
                </div>
              </div>
            </template>
            <p v-else class="hint">
              مثال پیام دوم: تبریز 3350 — عدد کیلومتر و نام شهر به‌صورت خودکار تشخیص داده می‌شود.
            </p>

            <label class="timeout-field">
              زمان انقضای گفتگو (دقیقه)
              <input v-model.number="config.sessionTimeoutMinutes" type="number" min="1" max="1440" />
            </label>
          </section>

          <p v-if="error" class="error">{{ error }}</p>
          <p v-if="success" class="success">{{ success }}</p>
          <div class="actions">
            <button class="primary" :disabled="saving" @click="saveSettings">
              {{ saving ? 'در حال ذخیره...' : 'ذخیره تنظیمات' }}
            </button>
            <button class="secondary" :disabled="loading" @click="loadSettings">بازنشانی</button>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.radio-row {
  display: flex;
  align-items: flex-start;
  gap: 0.65rem;
  margin-bottom: 0.75rem;
  font-weight: 400;
}

.radio-row input {
  margin-top: 0.25rem;
}

.radio-row strong {
  display: block;
}

.radio-row small {
  display: block;
  color: #666;
  font-weight: 400;
}

.timeout-field {
  display: block;
  margin-top: 1rem;
}

.timeout-field input {
  display: block;
  margin-top: 0.35rem;
  width: 100%;
  max-width: 12rem;
}

.pattern-list {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin: 0.75rem 0;
}

.pattern-item {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  background: #f5f7fa;
  border: 1px solid #cfd8dc;
  border-radius: 8px;
  padding: 0.65rem 0.85rem;
  cursor: grab;
}

.pattern-item.locked {
  cursor: default;
  opacity: 0.85;
}

.field-label {
  flex: 1;
}

.drag-handle {
  color: #607d8b;
  user-select: none;
}

.move-buttons {
  display: flex;
  gap: 0.35rem;
}

.move-btn {
  background: #eceff1;
  color: #263238;
  min-width: 2rem;
  padding: 0.35rem 0.5rem;
}

.move-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
</style>
