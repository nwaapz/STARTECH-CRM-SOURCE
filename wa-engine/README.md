# wa-engine — WhatsApp Web automation engine

PC-side companion for the SMS Gateway Android app. It logs into WhatsApp Web once (QR scan),
then pulls broadcast jobs (message + recipient list) from the phone over the hotspot LAN API,
searches each contact and sends the message, and reports progress back to the phone.

Playwright/Selenium can't run on Android, so the automation runs here on the PC. The phone
stays the source of truth: it resolves the "registered clients" audience, stores campaigns and
progress, and hosts the editable WhatsApp Web selectors.

## Requirements

- Node.js 18 or newer
- The PC connected to the phone's hotspot
- The LAN CRM server enabled in the phone app (note the 6-digit PIN shown there)

## Setup

```bash
cd wa-engine
npm install
npm run install-browser   # downloads the Chromium build Playwright uses
```

Create `config.json` (copy `config.example.json`):

```json
{
  "baseUrl": "",
  "port": 8765,
  "pin": "123456",
  "pollIntervalMs": 4000,
  "batchLimit": 5,
  "headless": false,
  "userDataDir": ".wa-profile",
  "proxyServer": ""
}
```

- `baseUrl` — **leave empty (`""`) to auto-detect the phone** (recommended). Over a hotspot the
  phone's IP changes every session, so the engine finds it automatically via the PC's default
  gateway and confirms it with `/api/ping`. Only set this to a fixed URL (e.g.
  `http://192.168.43.1:8765`) if you have a stable address and want to skip detection.
- `port` — the CRM port used for auto-detection (default `8765`).
- `pin` — the 6-digit LAN PIN from the phone app.
- `headless` — keep `false` for the first run so you can scan the QR. You can set it to `true`
  later; the login persists in `userDataDir`.
- `proxyServer` — optional. A proxy for the WhatsApp browser **only** (not the whole PC), e.g.
  `http://127.0.0.1:10808` or `socks5://127.0.0.1:10808`. Leave empty (`""`) for a direct
  connection. See "Using a VPN/proxy for WhatsApp only" below.

Config values can also be provided via env vars: `WA_BASE_URL`, `WA_PORT`, `WA_PIN`, `WA_POLL_MS`,
`WA_BATCH_LIMIT`, `WA_HEADLESS`, `WA_USER_DATA_DIR`, `WA_PROXY_SERVER`.

### Auto-detection (no more IP hunting)

When the PC is on the phone's hotspot, the phone **is** the PC's default gateway. With an empty
`baseUrl`, the engine reads the gateway from the OS routing table (plus a few subnet fallbacks),
probes each candidate's `/api/ping`, and locks onto the phone automatically. If the connection
drops (e.g. the phone's IP changes), it re-detects on the next poll. This is why you no longer
need to copy the address the phone shows — which can even be the wrong (carrier) IP.

## Using a VPN/proxy for WhatsApp only (keeps the phone hotspot working)

If WhatsApp Web needs a VPN but a system-wide VPN/TUN breaks access to the phone on the
hotspot LAN (`192.168.x.x`), run the proxy client (e.g. v2rayN/Xray) in **local proxy mode**
and point only this engine's browser at it:

1. In v2rayN: turn **TUN mode OFF** and **system proxy OFF/Clear** (so the PC network and the
   phone hotspot are untouched). Keep the local **Mixed Port** (e.g. `10808`) and an active server.
2. Set `"proxyServer": "http://127.0.0.1:10808"` in `config.json` (match your Mixed Port).
3. Confirm the phone CRM URL still loads in a browser while v2ray is running.
4. `npm start` — WhatsApp Web opens through the proxy, while the phone stays reachable on the hotspot.

## Run

```bash
npm start
```

On first run a Chromium window opens WhatsApp Web — scan the QR with your phone's WhatsApp.
The login is stored in `userDataDir` (`.wa-profile` by default), so you only scan once.

While running, the engine:

1. sends a heartbeat to the phone (so the dashboard shows engine online + login state),
2. fetches the current selectors from the phone (`/api/whatsapp/config`),
3. claims a batch of pending recipients (`/api/whatsapp/next-batch`),
4. for each recipient opens the chat (by phone number, falling back to the saved
   `client<id>` contact name), types the message and sends it,
5. reports each result back (`/api/whatsapp/report`) so progress updates live.

Create/start campaigns from the phone dashboard's WhatsApp page.

## When WhatsApp Web changes its layout

If WhatsApp Web updates and sending breaks, you do NOT redeploy this engine. Open the phone
dashboard's WhatsApp page and edit the selectors (search box, search result, message input,
send button). The engine picks up the new selectors on its next poll.

Selectors are standard CSS selectors. To find the current one, right-click the element in the
Chromium window → Inspect → Copy → Copy selector, then paste it into the dashboard.

## Notes

- WhatsApp Web automation is unofficial and rate-sensitive. Keep a sensible per-contact delay
  (`sendDelaySeconds` in the dashboard) to reduce ban risk.
- Only numbers that are valid Iranian mobiles and normalize cleanly to `98XXXXXXXXXX` are
  included; others are skipped when the campaign is built.
