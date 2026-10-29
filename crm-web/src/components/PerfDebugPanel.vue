<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { debugAddIndex, debugKillStuck, fetchDebugDb, type DebugDbStatus } from '@/api/client'
import {
  clearPerfDebug,
  formatMs,
  isPerfDebugEnabled,
  pageNotes,
  perfEntries,
} from '@/utils/perfDebug'

const enabled = ref(false)
const expanded = ref(false)
const buildTag = import.meta.env.VITE_CRM_SURFACE === 'remote' ? 'remote+perf' : 'lan'

onMounted(() => {
  enabled.value = isPerfDebugEnabled()
})
const selectedId = ref<string | null>(null)

const selected = computed(() => perfEntries.value.find((e) => e.id === selectedId.value) ?? null)

function timeLabel(at: number): string {
  return new Date(at).toLocaleTimeString('fa-IR', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

function slowClass(ms: number): string {
  if (ms >= 5000) return 'slow-critical'
  if (ms >= 1000) return 'slow-warn'
  return ''
}

const db = ref<DebugDbStatus | null>(null)
const dbBusy = ref(false)
const dbMessage = ref('')

async function runDb(action: 'status' | 'kill' | 'index') {
  if (dbBusy.value) return
  dbBusy.value = true
  dbMessage.value = action === 'index' ? 'Building index… (can take 1–3 min)' : 'Loading…'
  try {
    if (action === 'kill') {
      const r = await debugKillStuck(30)
      dbMessage.value = `Killed ${r.killed.length} stuck queries` + (r.failed.length ? `, ${r.failed.length} failed: ${r.failed.map((f) => f.error).join('; ')}` : '')
    } else if (action === 'index') {
      const r = await debugAddIndex()
      dbMessage.value = !r.name
        ? 'All indexes already present'
        : r.ok
          ? `Built ${r.name} in ${formatMs(r.ms ?? 0)} — ${r.remaining} remaining`
          : `Failed ${r.name}: ${r.error}`
    }
    db.value = await fetchDebugDb()
    if (action === 'status') dbMessage.value = ''
  } catch (e) {
    dbMessage.value = `Error: ${e instanceof Error ? e.message : String(e)}`
  } finally {
    dbBusy.value = false
  }
}
</script>

<template>
  <div v-if="enabled" class="perf-debug" :class="{ collapsed: !expanded }">
    <header class="perf-debug-header" @click="expanded = !expanded">
      <strong>CRM Perf Debug</strong>
      <span class="perf-debug-hint">{{ buildTag }} · ?debug=0 to hide</span>
      <button type="button" class="perf-debug-clear" @click.stop="clearPerfDebug()">Clear</button>
      <span class="perf-debug-toggle">{{ expanded ? '−' : '+' }}</span>
    </header>

    <div v-if="expanded" class="perf-debug-body">
      <section v-if="pageNotes.length" class="perf-section">
        <h3>Page</h3>
        <ul class="perf-notes">
          <li v-for="(note, i) in pageNotes" :key="`${note.at}-${i}`">
            <span class="perf-time">{{ timeLabel(note.at) }}</span>
            {{ note.text }}
          </li>
        </ul>
      </section>

      <section class="perf-section perf-db">
        <h3>Database</h3>
        <div class="perf-db-actions">
          <button type="button" :disabled="dbBusy" @click="runDb('status')">DB status</button>
          <button type="button" :disabled="dbBusy" @click="runDb('kill')">Kill stuck queries (&gt;30s)</button>
          <button type="button" :disabled="dbBusy" @click="runDb('index')">Build next missing index</button>
          <span v-if="dbMessage" class="perf-db-msg">{{ dbMessage }}</span>
        </div>
        <template v-if="db">
          <p class="perf-meta">
            MySQL {{ db.mysql }}
            <template v-if="db.table">
              · {{ db.table.name }} {{ db.table.engine }} ~{{ db.table.approxRows.toLocaleString() }} rows ·
              data {{ db.table.dataMb }}MB · index {{ db.table.indexMb }}MB
            </template>
            · cache {{ db.cacheDir }} · schema cached {{ db.schemaCached ? 'yes' : 'no' }}
            · finish_request {{ db.finishRequest ?? '?' }}
          </p>
          <p v-if="db.autoIndex" class="perf-meta">
            Auto index build:
            <template v-if="db.autoIndex.done">all done</template>
            <template v-else-if="db.autoIndex.building">building in background…</template>
            <template v-else-if="db.autoIndex.failed">
              <span class="perf-ix missing">failed {{ db.autoIndex.failed.name }}: {{ db.autoIndex.failed.error }}</span>
            </template>
            <template v-else-if="db.finishRequest === 'none'">off (host can't finish requests early — use the button)</template>
            <template v-else>waiting for next request</template>
          </p>
          <p class="perf-meta">
            Indexes:
            <span
              v-for="ix in db.indexes"
              :key="ix.name"
              :class="['perf-ix', ix.present ? 'ok' : ix.applicable ? 'missing' : 'na']"
            >{{ ix.name }} {{ ix.present ? '✓' : ix.applicable ? '✗' : 'n/a' }}<template
                v-if="ix.present && ix.coveredBy && ix.coveredBy !== ix.name"
              > (via {{ ix.coveredBy }})</template></span>
          </p>
          <table class="perf-proc">
            <thead>
              <tr><th>id</th><th>cmd</th><th>sec</th><th>state</th><th>query</th></tr>
            </thead>
            <tbody>
              <tr
                v-for="p in db.processes"
                :key="p.id"
                :class="slowClass(p.command === 'Query' ? p.timeSec * 1000 : 0)"
              >
                <td>{{ p.id }}{{ p.self ? ' (me)' : '' }}</td>
                <td>{{ p.command }}</td>
                <td>{{ p.timeSec }}</td>
                <td>{{ p.state || '—' }}</td>
                <td class="perf-detail-cell">{{ p.info || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </template>
      </section>

      <section class="perf-section perf-grid">
        <div class="perf-list">
          <h3>API calls ({{ perfEntries.length }})</h3>
          <ul>
            <li
              v-for="entry in perfEntries"
              :key="entry.id"
              :class="['perf-entry', slowClass(entry.clientMs), { active: entry.id === selectedId }]"
              @click="selectedId = entry.id"
            >
              <span class="perf-time">{{ timeLabel(entry.at) }}</span>
              <span class="perf-path">{{ entry.method }} {{ entry.path }}</span>
              <span class="perf-ms" :class="slowClass(entry.clientMs)">
                client {{ formatMs(entry.clientMs) }}
              </span>
              <span v-if="entry.server" class="perf-ms" :class="slowClass(entry.server.totalMs)">
                server {{ formatMs(entry.server.totalMs) }}
              </span>
              <span v-if="entry.error" class="perf-error">{{ entry.error }}</span>
            </li>
            <li v-if="perfEntries.length === 0" class="perf-empty">No API calls yet</li>
          </ul>
        </div>

        <div v-if="selected" class="perf-detail">
          <h3>Server steps — {{ selected.path }}</h3>
          <p class="perf-meta">
            client {{ formatMs(selected.clientMs) }} · server
            {{ selected.server ? formatMs(selected.server.totalMs) : '—' }}
            <template v-if="selected.server?.memoryMb"> · mem {{ selected.server.memoryMb }}MB</template>
            <template v-if="selected.server?.php"> · PHP {{ selected.server.php }}</template>
          </p>
          <table v-if="selected.server?.steps?.length">
            <thead>
              <tr>
                <th>Step</th>
                <th>Δ ms</th>
                <th>Σ ms</th>
                <th>Detail</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(step, i) in selected.server.steps"
                :key="i"
                :class="slowClass(step.ms)"
              >
                <td>{{ step.label }}</td>
                <td :class="slowClass(step.ms)">{{ formatMs(step.ms) }}</td>
                <td>{{ formatMs(step.cumulativeMs) }}</td>
                <td class="perf-detail-cell">{{ step.detail ?? '—' }}</td>
              </tr>
            </tbody>
          </table>
          <p v-else class="perf-empty">No server _debug block (enable X-Crm-Debug)</p>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.perf-debug {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 99999;
  max-height: 45vh;
  background: #1a1a2e;
  color: #e8e8e8;
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  box-shadow: 0 -4px 24px rgba(0, 0, 0, 0.35);
  direction: ltr;
  text-align: left;
}

.perf-debug.collapsed {
  max-height: none;
}

.perf-debug-header {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.4rem 0.75rem;
  background: #16213e;
  cursor: pointer;
  user-select: none;
}

.perf-debug-hint {
  color: #7ec8e3;
  font-size: 10px;
}

.perf-debug-clear {
  margin-left: auto;
  padding: 0.15rem 0.5rem;
  border: 1px solid #555;
  border-radius: 4px;
  background: #0f3460;
  color: #fff;
  font: inherit;
  cursor: pointer;
}

.perf-debug-body {
  overflow: auto;
  max-height: calc(45vh - 32px);
  padding: 0.5rem 0.75rem 0.75rem;
}

.perf-section h3 {
  margin: 0 0 0.35rem;
  font-size: 11px;
  color: #7ec8e3;
  text-transform: uppercase;
}

.perf-notes {
  margin: 0 0 0.75rem;
  padding: 0;
  list-style: none;
}

.perf-notes li {
  padding: 0.15rem 0;
  border-bottom: 1px solid #2a2a4a;
}

.perf-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.75rem;
}

@media (max-width: 900px) {
  .perf-grid {
    grid-template-columns: 1fr;
  }
}

.perf-list ul {
  margin: 0;
  padding: 0;
  list-style: none;
  max-height: 200px;
  overflow: auto;
}

.perf-entry {
  padding: 0.25rem 0.35rem;
  border-bottom: 1px solid #2a2a4a;
  cursor: pointer;
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem 0.6rem;
  align-items: baseline;
}

.perf-entry:hover,
.perf-entry.active {
  background: #0f3460;
}

.perf-time {
  color: #888;
  flex: 0 0 auto;
}

.perf-path {
  flex: 1 1 120px;
  word-break: break-all;
}

.perf-ms {
  color: #a8e6cf;
  flex: 0 0 auto;
}

.perf-ms.slow-warn {
  color: #ffd93d;
}

.perf-ms.slow-critical,
.perf-entry.slow-critical .perf-path,
tr.slow-critical td {
  color: #ff6b6b;
  font-weight: 700;
}

.perf-error {
  color: #ff6b6b;
  width: 100%;
}

.perf-empty {
  color: #666;
  padding: 0.5rem 0;
}

.perf-detail table {
  width: 100%;
  border-collapse: collapse;
  font-size: 10px;
}

.perf-detail th,
.perf-detail td {
  border: 1px solid #333;
  padding: 0.2rem 0.35rem;
  text-align: left;
}

.perf-detail th {
  background: #16213e;
}

.perf-meta {
  margin: 0 0 0.35rem;
  color: #aaa;
}

.perf-detail-cell {
  color: #ccc;
  max-width: 140px;
  word-break: break-word;
}

.perf-db {
  margin-bottom: 0.75rem;
}

.perf-db-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
  align-items: center;
  margin-bottom: 0.35rem;
}

.perf-db-actions button {
  padding: 0.2rem 0.55rem;
  border: 1px solid #555;
  border-radius: 4px;
  background: #0f3460;
  color: #fff;
  font: inherit;
  cursor: pointer;
}

.perf-db-actions button:disabled {
  opacity: 0.5;
  cursor: wait;
}

.perf-db-msg {
  color: #ffd93d;
}

.perf-ix {
  margin-right: 0.6rem;
}

.perf-ix.ok {
  color: #a8e6cf;
}

.perf-ix.missing {
  color: #ff6b6b;
  font-weight: 700;
}

.perf-ix.na {
  color: #666;
}

.perf-proc {
  width: 100%;
  border-collapse: collapse;
  font-size: 10px;
}

.perf-proc th,
.perf-proc td {
  border: 1px solid #333;
  padding: 0.2rem 0.35rem;
  text-align: left;
}

.perf-proc .perf-detail-cell {
  max-width: 480px;
}
</style>
