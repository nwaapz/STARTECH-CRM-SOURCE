# Remote Vue panel — deploy to `public_html/crm`

## What you get
- Same CRM UI as LAN (Vue), `surface=remote` (no WhatsApp nav, no PIN)
- PHP APIs: login, sync stats, serials list/edit, dashboard chart, serial-groups summary
- Bots (Rubika/Telegram/Bale): connect, menus, webhooks/cron, communications, club bot tunnels
- Product photo verification via bots → CRM «تأیید عکس» queue
- Mass-SMS / lottery / WhatsApp campaigns still stubbed (501 on write)

## Upload (cPanel)

Build on your PC first:

```bash
cd crm-web
npm install
npm run build:remote
```

Then upload **into** `public_html/crm/` (replace contents):

| From repo | To host |
|-----------|---------|
| `crm-php/host/*` (index.html, assets/, .htaccess) | `public_html/crm/` |
| `crm-php/api/` | `public_html/crm/api/` |
| `crm-php/config/` | `public_html/crm/config/` |

Final tree:

```text
public_html/crm/
  index.html
  assets/
  .htaccess
  api/
    index.php
    bootstrap.php
    .htaccess
    sync/push.php
    sync/pull.php
  config/
    database.php
```

## Config

1. Edit `config/database.php` — MySQL user/pass/db for this host.
2. Open `https://YOUR-DOMAIN/crm/` → login `admin` / `admin123` if users table was empty (auto-created).
3. If you already have users with **plaintext** passwords (old panel), those still work.
4. Test API: `https://YOUR-DOMAIN/crm/api/ping` → `CRM OK`

## Note about `/crm/new`

You can delete `crm/new` after moving `api` + `config` to `/crm` as above. Sync URL for the phone becomes `https://YOUR-DOMAIN/crm` (endpoints under `/crm/api/sync/`).

## Bots (Rubika / Telegram / Bale)

Remote runs bots on the server (independent of the Android phone):

1. Upload updated `crm-php/api/` (including `bots/`, `webhooks/`, `bot_*.php`, `comms_*.php`, `photo_*.php`) and `crm-php/cron/`.
   Also upload `crm-php/storage/` (writable by PHP for photo files).
2. In the CRM UI open روبیکا / تلگرام / بله → paste bot token → Connect.
3. **Telegram, Bale & Rubika** all register an **HTTPS webhook** on Connect (instant — no 1‑minute cron):
   - `https://YOUR-DOMAIN/crm/api/webhooks/telegram.php?secret=…`
   - `https://YOUR-DOMAIN/crm/api/webhooks/bale.php?secret=…`
   - `https://YOUR-DOMAIN/crm/api/webhooks/rubika.php?secret=…` (`updateBotEndpoints`)
   - Host must serve HTTPS publicly.
   - **Reconnect** each bot once after uploading so the endpoint is registered.
   - **Important:** do not keep the same Bale/Telegram token connected on the LAN phone
     app at the same time — phone `getUpdates` clears the webhook.
   - Optional backup poller only if webhook fails: `cron/bot_poll_rubika.php` or
     `api/cron_poll_bots.php?key=…`
4. Club auto / lottery cron (separate from bots):
   ```bash
   */5 * * * * /usr/bin/php /path/to/crm-php/cron/club_auto_tick.php >> /path/to/logs/club_auto.log 2>&1
   ```
5. **ارتباطات** lists SMS + bot messages. Club auto rules honor Rubika/Telegram/Bale toggles when the phone is paired.
6. **تأیید عکس**: enable «تأیید عکس» on a serial group at generation. After registration, bot asks for a product photo (or menu «ارسال عکس محصول»). Photos land in CRM تأیید عکس for approve/reject (score awarded on approve).
7. **قرعه‌کشی**: seller pool from `old_serials` (not Mcode). Upload `lottery_*.php` with the rest of `api/`.

### Smoke test
- Connect each bot; status shows `active` + username
- `/start` + share contact → reply with menu
- Register a seller serial (city only) and an end-user serial
- Open ارتباطات → see inbound/outbound thread
- Enable Rubika on a club rule → run-now → only paired phones get the bot message once
- Generate a group with image verification → register via bot → send photo → see it in تأیید عکس → approve
- Seller serials with score > 0 appear in قرعه‌کشی → draw animation + confetti → confirm zeros scores
