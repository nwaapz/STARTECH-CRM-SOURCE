# Payamesh — Feature Guide

Detailed inventory of product features for **Gate-Way-Guarantee** (برند: **Payamesh** / SMS Gateway).

The phone is the system of record. A browser CRM is served from the phone over Wi‑Fi hotspot. WhatsApp broadcasts are executed by a PC companion (`wa-engine`) that talks to the same phone API.

| Surface | Role |
|--------|------|
| **Android app** (`and_studio_proj`) | SMS gateway, bots, serial DB, LAN HTTP server, campaign execution |
| **CRM web** (`crm-web`) | Ops UI embedded in the APK, served at `http://<phone-ip>:8765` |
| **wa-engine** | Playwright WhatsApp Web automation on Windows/Mac |

API contract: [`shared/api/openapi.yaml`](shared/api/openapi.yaml).

---

## Table of contents

1. [Architecture overview](#1-architecture-overview)
2. [Android app](#2-android-app)
3. [CRM web dashboard](#3-crm-web-dashboard)
4. [WhatsApp PC engine (`wa-engine`)](#4-whatsapp-pc-engine-wa-engine)
5. [Cross-cutting capabilities](#5-cross-cutting-capabilities)
6. [Related / legacy modules](#6-related--legacy-modules)

---

## 1. Architecture overview

### Verification modes

| Mode | Behavior |
|------|----------|
| **Remote** | Incoming serial checks go to `Gateway.php` on a server; reply SMS from PHP response |
| **Internal (LAN CRM)** | Serials live in Room DB on the phone; CRM and bots use the same DB. Required when LAN CRM is enabled |

### Typical LAN flow

```
Customer SMS / Rubika / Telegram
        ↓
  SmsProcessingEngine (phone)
        ↓
  Room DB + reply on same channel
        ↑
PC browser ←→ Ktor LAN API (PIN) ←→ same Room DB
        ↑
wa-engine (WhatsApp Web) ←→ /api/whatsapp/*
```

### Runtime

- **Foreground service** keeps the gateway alive with screen off.
- **Boot receiver** can restart the gateway after reboot (when enabled in prefs).
- Persistent notification shows gateway status and, when CRM is on, the LAN URL.

---

## 2. Android app

Native Kotlin / Jetpack Compose app. Bottom navigation (RTL): Gateway, Serial generation, Outbound messages, Rubika, Telegram, Settings.

### 2.1 Shell and security

| Feature | Description |
|---------|-------------|
| Splash | Branded launch screen |
| Login | Local password gate before the main UI |
| Permissions | SMS receive/send, notifications, contacts (for WhatsApp `client<id>` naming), battery optimization guidance |
| Dual-SIM | Choose which subscription handles inbound SMS and replies |

### 2.2 SMS gateway

| Feature | Description |
|---------|-------------|
| Start / stop gateway | Starts foreground service, SMS receiver, optional LAN server and club scheduler |
| Inbound SMS | High-priority `SMS_RECEIVED`; SIM filter; short dedup window. Device SIM mode processes only Iranian mobile senders (`09…` / `+989…`); bank/gov/provider short codes are ignored. Mellipayamak mode only accepts configured panel short codes with an Iranian mobile in the forwarded body |
| Reply SMS | Reply on the same SIM; status logged (sent / gateway error / failed) |
| Communication log | Recent inbound/outbound traffic visible on the Gateway tab |

### 2.3 Warranty registration (SMS)

Shared engine: `SmsProcessingEngine` (also used by Rubika and Telegram).

#### Processing modes

| Mode | Behavior |
|------|----------|
| **SERIAL_ONLY** | Message body is treated as a serial (or simple serial parse) |
| **COMBINED_PATTERN** | Single message with serial + city + km (configurable separator / pattern) |
| **STEP_BY_STEP** | Multi-SMS conversation: collect fields in order (or combined second step) |

#### Step flow options

- **SEQUENTIAL** — ask for each field in turn
- **COMBINED_SECOND** — after serial, accept city+km in one follow-up message

#### Other registration behavior

- Session timeout for incomplete step flows
- City / province and kilometer fields
- Incomplete-registration completion and completion reminders
- Error cases: serial not found, already used
- Optional post-registration **bot invite SMS** (Rubika / Telegram), once per phone
- Jalali date/time stored for registrations

### 2.4 Outbound SMS message templates (on phone)

Editable catalogs for customer-facing replies:

- Registration success / failure
- Step prompts (ask for city, km, etc.)
- Combined-pattern format errors
- Incomplete registration / completion reminders
- Placeholders such as `@1`, `@2` (and related token substitution)

Editable on the phone UI and mirrored in CRM **تنظیمات پیام های ارسالی**.

### 2.5 Serial / warranty groups (on phone)

#### Generation UI (Compose)

On-device UI currently focuses on **range** generation:

- Prefix, start/end numbers, zero-padding
- Description, club **score**
- Category: `end_user_client` or `seller_to_end_user`
- Warranty: months and/or km
- Optional **image verification required** (score awarded only after photo approve)
- Duplicate-range warnings, DB stats

Full generation engine (used by phone + LAN API) also supports:

| Method | Description |
|--------|-------------|
| **Range** | Contiguous numeric serials with prefix/padding |
| **Complex** | Random letter + digit patterns |
| **Excel import** | Bulk import of serial strings |

#### Group management

- Edit description, score, category, warranty, image-verify flag
- Extend range (by count or target end)
- Excel export of group serials
- Delete group
- List serials in a group

### 2.6 Photo verification (product images)

When a serial group has image verification enabled:

1. Customer sends a product photo via Rubika or Telegram bot menu
2. Submission is stored on device and queued for admin review
3. CRM **تأیید عکس** approves (awards club score) or rejects (with reason)
4. Images are served from the phone via LAN API

### 2.7 Rubika bot

| Feature | Description |
|---------|-------------|
| Connect / disconnect | Bot token; status; polling |
| Serial registration | Same processing engine as SMS; phone share / pairing to contacts |
| Toggle serial handling | Enable/disable warranty registration on this channel |
| Invite SMS | Custom invite text; once-per-phone tracking |
| Reply keyboard menu | Toggleable items (see below) |
| Live message list | Recent bot traffic on the Rubika tab |
| Product photos | Download and queue for CRM review |

### 2.8 Telegram bot

Same capability set as Rubika, with Telegram BotFather token, separate settings/store, and independent connect/disconnect/polling.

### 2.9 Bot menu (Rubika & Telegram)

Reply-keyboard self-service (each item can be enabled/disabled per channel):

| Menu item | Label |
|-----------|--------|
| Register warranty | ثبت گارانتی |
| Warranty status | وضعیت گارانتی |
| Send product photo | ارسال عکس محصول |
| Invite friends | دعوت دوستان |
| Support | پشتیبانی |
| Help | راهنما |

### 2.10 LAN CRM HTTP server

| Feature | Description |
|---------|-------------|
| Ktor server | Serves REST API + static Vue CRM from `assets/crm/` |
| Auth | Bearer token = 6-digit LAN PIN |
| Port | Default `8765` (configurable) |
| Host / IP | Auto-detect or host override; QR code for URL |
| Diagnostics | Startup checks and optional trace log |
| Live sync | `GET /api/sync` — stats + change events for the dashboard |

**Major API areas** (see OpenAPI for full detail):

- Health / ping
- Sync
- Serials (batch, complex-batch, import-batch), serial-groups CRUD/extend/list
- Serial search/update, completion reminder, registration errors
- Communications inbox / thread
- SMS processing settings & message templates
- Rubika / Telegram status, connect, settings, messages
- Mass SMS campaigns (preview, CRUD, pause/resume)
- WhatsApp config, engine heartbeat/status, campaigns, next-batch, report
- Club auto-messages (CRUD, run-now, send history)
- Serial photo submissions (list, image, approve, reject)

### 2.11 Mass SMS (executed on the phone)

Bulk SMS via the phone’s SIM:

- Audiences: **registered**, **error**, or **all** (resolver-based)
- Filters: province/city, serial groups, Jalali date range
- Preview recipient count before create
- Configurable delay between sends
- Pause / resume / delete campaigns
- Progress tracked per recipient

### 2.12 WhatsApp campaigns (orchestrated on the phone)

The phone builds audiences and stores progress; the PC engine sends:

- Same audience filters as mass SMS
- Phone numbers normalized to international form (`98XXXXXXXXXX`, safe against `+98` duplication)
- Optional device contacts named `client<rowId>` to help WhatsApp search
- Engine heartbeat: online + WhatsApp Web logged-in state for the CRM badge
- Editable WhatsApp Web selectors and open mode (`search` / `link`) stored on the phone
- Batches claimed via `next-batch`; results via `report`

### 2.13 Club auto-messages (executed on the phone)

Scheduled outreach to club members:

- Offset from registration date (**months** and/or **days**)
- Daily send time
- Channels: SMS, Rubika, Telegram (per rule)
- Optional serial-group scope
- Message body with `{سریال}` / `{serial}` placeholders
- Permanent per-rule / per-phone / per-channel dedup (never re-send the same rule on that channel)
- **Run now** from CRM (still respects dedup)
- Catch-up when the gateway was offline (eligible if registration is on or before the offset date)

### 2.14 Settings (phone)

- Verification mode (remote vs internal)
- LAN CRM enable, PIN, port, host override, QR, traces
- SMS processing mode configuration
- App version display
- Bot / invite related prefs (via Rubika/Telegram settings and CRM)

---

## 3. CRM web dashboard

Vue 3 SPA built into the APK. Opened from any browser on the hotspot LAN. Auth: 6-digit PIN.

Nav brand: **CRM Payamesh**.

### 3.1 Login (`/login`)

- Enter LAN PIN
- Optional server ping check
- Redirects to dashboard when authenticated

### 3.2 Dashboard (`/` — داشبورد)

- Live sync polling (~1s) with toast notifications
- Stats: total / unused / registered serials
- Recent sync events
- Clients with failed registrations
- Recent successful registrations

### 3.3 Serial production (`/serials` — تولید سریال)

Create and manage serial groups (fuller than the Compose range-only UI):

| Tab | Capability |
|-----|------------|
| Range | Prefix + numeric range + padding |
| Complex | Random alphanumeric patterns |
| Excel import | Parse spreadsheet and import serials |

Per group:

- Category, score, warranty (months / km), image verification flag
- Edit metadata, extend range, export Excel, delete
- Duplicate-range warnings

### 3.4 Reports (`/reports` — گزارش‌ها)

Search and manage **registered** serials:

- Jalali date range
- Free-text search
- Channel filters (SMS / Rubika / Telegram)
- Category filter
- Province / city
- Multi-select serial groups
- Edit row (modal)
- Send completion reminder
- CSV export

### 3.5 Communications (`/communications` — ارتباطات)

Unified inbox across channels:

- Paginated communication log
- Filters (direction, phone, etc.)
- Conversation thread modal
- Polling for updates

### 3.6 Customer club (`/customers` — باشگاه مشتریان)

- Aggregated customers (registrations, city/km, Rubika/Telegram pairing)
- Create / edit / enable club auto-message rules
- Offset months/days, daily time, channels, group scope, message body
- Run rule now
- Per-rule send history

### 3.7 Photo review (`/photo-review` — تأیید عکس)

Admin QA for product photos:

- Filter pending / approved / rejected
- View image from phone
- Approve → award club score
- Reject with reason codes

### 3.8 Mass SMS (`/mass-sms` — ارسال پیام انبوه)

- Audience + location / group / date filters
- Preview count
- Delay between messages
- Campaign list with pause / resume / delete and live progress

### 3.9 WhatsApp (`/whatsapp` — ارسال واتساپ)

- Engine status badge (online / waiting for QR / logged in)
- Campaign create with same audience model as mass SMS
- Pause / resume / delete; progress
- **Editable WhatsApp Web settings:**
  - Open mode: search vs deep link (`web.whatsapp.com/send?phone=…`)
  - Search phone first, then contact name
  - Delay between contacts; per-contact timeout
  - CSS selectors: search box, search result, message input, send button  
  - Changes apply on the engine’s next poll (no engine redeploy)

### 3.10 Bots (`/bots` — ربات‌ها)

Tabs for Rubika and Telegram:

- Connect / disconnect
- Serial registration & invite toggles
- Invite text
- Menu item toggles
- Live recent messages

Legacy paths `/rubika` and `/telegram` redirect here.

### 3.11 Outbound message templates (`/messages` — تنظیمات پیام های ارسالی)

- Edit SMS reply templates by category
- Live preview with sample tokens
- Save / reset

### 3.12 Settings (`/settings` — تنظیمات)

SMS processing configuration:

- Mode (serial-only / combined / step-by-step)
- Separator and combined pattern
- Step field order (drag)
- Session timeout

---

## 4. WhatsApp PC engine (`wa-engine`)

Node.js + Playwright companion. Cannot run on Android; the phone remains campaign authority.

### 4.1 Setup essentials

- Node 18+
- PC on phone hotspot
- LAN CRM enabled; PIN in `config.json`
- Optional Chromium via Playwright; optional `proxyServer` for WhatsApp-only VPN (leaves hotspot LAN direct)

### 4.2 Features

| Feature | Description |
|---------|-------------|
| **Auto phone discovery** | Empty `baseUrl` → detect default gateway + subnet `.1` fallbacks; probe `/api/ping`; re-detect if CRM drops |
| **Persistent login** | Chromium profile in `userDataDir` (`.wa-profile`); QR scan once |
| **Heartbeat** | Reports online + logged-in to CRM |
| **Job loop** | Fetch remote selectors/config → claim `next-batch` → send → incremental `report` |
| **Open chat: search** | Type phone (`98…`) then optional `client<id>` into WhatsApp search; click result |
| **Open chat: link** | Navigate to `https://web.whatsapp.com/send?phone=…`; wait for compose box |
| **Remote selectors** | All CSS selectors loaded from phone each poll |
| **Send fallbacks** | If send button missing, press Enter |
| **Per-contact delay** | From CRM `sendDelaySeconds` |
| **Proxy** | Playwright browser only uses `proxyServer` (e.g. v2rayN mixed port) so hotspot CRM stays reachable |

See [`wa-engine/README.md`](wa-engine/README.md) for install and VPN notes.

---

## 5. Cross-cutting capabilities

1. **Multi-channel warranty registration** — SMS, Rubika, Telegram share one processing engine and internal DB.
2. **Serial lifecycle** — generate (range / complex / import) → register → report → optional photo score → club outreach.
3. **Warranty rules** — months and/or km per serial group; customer category for reporting.
4. **Club scoring** — score per group; gated by photo approval when required.
5. **LAN-first CRM** — no PC install for day-to-day ops; PIN **or** username/password auth; live sync; Admin Setup + lottery on CRM.
6. **Outbound marketing** — mass SMS (SIM), WhatsApp (PC engine), scheduled club rules (SMS + bots).
7. **Ops tooling** — communications log, failed-registration clients, completion reminders, editable templates, dual-SIM, PDF/CSV report export, full-Jalali-month daily chart.
8. **Jalali calendar** — dates throughout DB, reports, and filters (`YYYY/MM/DD` Latin on the wire).
9. **Iran geography** — province/city data for filters and registration.
10. **Phone normalization** — national ↔ international (`98…`) with `+98` dedupe safety for WhatsApp.
11. **Cloud buffer sync** — dual persistent change buffers (phone `sync_outbox` + remote `crm_sync_buffer`). Only buffered changes sync: registrations, report edits, serial generation (+ group metadata). CSV import is not synced. Exchange via `/api/sync/buffer` + `/buffer/apply` + `/buffer/ack`. Same serial in both buffers with different data → conflict popup on Android (local vs remote); the LAN web "Send to server" button simply holds back conflicting serials (left pending) since conflicts can only be resolved on the phone. **No background/periodic sync** — pull and push are two independent, one-directional actions:
    - **"Get from server"** (Android Settings card + LAN CRM web Admin Setup) — backfills the server's most recently registered serials (count-based, most recent 5000 by id, so it covers a gap of any age via `POST /api/sync/buffer/backfill`) then pulls/applies/acks the remote buffer. This is the only action that ever writes remote data locally or resolves conflicts.
    - **"Send to server"** (same two places) — uploads everything pending in the local outbox. Does a cheap remote-buffer fetch purely to skip serials that are also pending remotely (left pending, no dialog) so a push can't silently undo a pending remote change; never applies or acks remote data.
    - **Automatic, silent, event-driven triggers** (no periodic timer): the LAN CRM web app fires a cheap pull (no backfill) once per page load, and the Android app fires a debounced push ~1.5s after any local registration/edit. Both fail silently if the server isn't reachable/configured — the change just stays pending until someone taps the matching button.

### UI asymmetry notes

| Capability | Where configured | Where executed |
|------------|------------------|----------------|
| Complex / Excel serial create | CRM (full) | Phone DB via API |
| Range serial create | Phone UI + CRM | Phone |
| Mass SMS / WhatsApp / Club / Photo review | Primarily CRM | Phone service (+ wa-engine for WA) |
| WhatsApp selectors & campaigns | CRM | Phone store + wa-engine |
| Bot connect & menus | Phone tabs + CRM | Phone pollers |

---

## 6. Related / legacy modules

Not part of the core “درگاه CRM” product surface, but present in the repo:

| Module | Role |
|--------|------|
| `crm-php/` | Remote hybrid CRM API + install (Mode A additive / B greenfield / C foreign migrate); sync bridge |
| `Gateway.php` + PHP helpers | Remote verification server for non-LAN mode (legacy) |
| `sms_gateway/` | Older Flutter SMS → PHP gateway (superseded by native app) |
| `CCP/` | Separate “Data Call” product (image over voice/FSK); not warranty CRM |
| Bundled JDK under repo | Build toolchain only |

---

## Quick start map

| Goal | What to use |
|------|-------------|
| Register warranties from SMS | Start gateway; set SMS processing mode; internal or remote verify |
| Operate from a PC browser | Enable LAN CRM; join hotspot; open PIN-protected CRM |
| Create many serials | CRM → تولید سریال (range / complex / Excel) |
| Talk to customers on bots | Connect Rubika/Telegram; enable menu + serial |
| Bulk SMS | CRM → ارسال پیام انبوه |
| Bulk WhatsApp | CRM → ارسال واتساپ + run `wa-engine` on PC |
| Club follow-ups | CRM → باشگاه مشتریان → auto-message rules |
| Photo-gated scores | Enable image verify on group; bot photo; CRM تأیید عکس |

---

*This document reflects the feature set implemented in the repository as of the current codebase. For API shapes and request/response fields, prefer [`shared/api/openapi.yaml`](shared/api/openapi.yaml).*
