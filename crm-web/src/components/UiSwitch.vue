<script setup lang="ts">
defineProps<{
  modelValue: boolean
  label: string
  description?: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  change: [value: boolean]
}>()

function onChange(event: Event) {
  const checked = (event.target as HTMLInputElement).checked
  emit('update:modelValue', checked)
  emit('change', checked)
}
</script>

<template>
  <label class="ui-switch" :class="{ disabled, 'has-description': !!description }">
    <span class="ui-switch-text">
      <span class="ui-switch-label">{{ label }}</span>
      <span v-if="description" class="ui-switch-description">{{ description }}</span>
    </span>
    <span class="ui-switch-control">
      <input
        type="checkbox"
        class="ui-switch-input"
        :checked="modelValue"
        :disabled="disabled"
        @change="onChange"
      />
      <span class="ui-switch-track" aria-hidden="true">
        <span class="ui-switch-thumb" />
      </span>
    </span>
  </label>
</template>

<style scoped>
.ui-switch {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  margin-top: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  user-select: none;
}

.ui-switch.has-description {
  align-items: flex-start;
  margin-top: 0;
}

.ui-switch.disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.ui-switch-text {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  min-width: 0;
}

.ui-switch-label {
  line-height: 1.4;
}

.ui-switch-description {
  font-weight: 400;
  font-size: 0.82rem;
  line-height: 1.45;
  color: #607d8b;
}

.ui-switch-control {
  position: relative;
  flex-shrink: 0;
  width: 44px;
  height: 26px;
  margin-top: 0.1rem;
}

.ui-switch-input {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  margin: 0;
  opacity: 0;
  cursor: inherit;
  z-index: 1;
}

.ui-switch-track {
  display: block;
  width: 44px;
  height: 26px;
  border-radius: 999px;
  background: #cfd8dc;
  transition: background 0.18s ease;
  box-shadow: inset 0 0 0 1px rgba(0, 0, 0, 0.04);
}

.ui-switch-thumb {
  position: absolute;
  top: 3px;
  right: 3px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.2);
  transition: transform 0.18s ease;
}

.ui-switch-input:checked + .ui-switch-track {
  background: #2e7d32;
}

.ui-switch-input:checked + .ui-switch-track .ui-switch-thumb {
  transform: translateX(-18px);
}

.ui-switch-input:focus-visible + .ui-switch-track {
  outline: 2px solid #81c784;
  outline-offset: 2px;
}
</style>
