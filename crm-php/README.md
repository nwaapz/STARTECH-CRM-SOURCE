# Payamesh hybrid CRM (PHP remote) — client package

Sellable package = **Android LAN CRM app** + **this PHP remote** (same serial model: one inventory + seller/client category).

## Remote Vue panel (browser)

See **[DEPLOY-HOST.md](DEPLOY-HOST.md)** — build with `npm run build:remote` in `crm-web`, upload `host/` + `api/` + `config/` to `public_html/crm`.

## Serial model (aligned with LAN)

| Field | Meaning |
|-------|---------|
| All serials | Prefer single table **`old_serials`** |
| `category` | `end_user_client` (client) or `seller_to_end_user` (seller) — same as LAN serial groups |
| `score` | Optional points copied from legacy `Mcode` (not a separate “lottery flag”) |

Legacy `Mcode` / `new_serials` are not dropped; after unify they are backup / leftovers.

S440000–S450000 are **seller** rows (`seller_to_end_user`), not a special dual-table mirror.

## Install modes

| Mode | When | Commands |
|------|------|----------|
| **A** Current host (e.g. startech) | Tables already exist | 1) backup DB 2) `php install/alter_additive.sql.php` 3) `php install/unify_serials_seller.php --dry-run` then without `--dry-run` |
| **B** New client, empty MySQL | Greenfield | `php install/install.php --user=... --pass=... --db=payamesh_crm` |
| **C** Client has foreign columns | Map & copy | Edit `install/mapping.example.json` → `php install/migrate_from_foreign.php mapping.json` |

Credentials for Mode A: [`config/database.php`](config/database.php).

### Unify script (Mode A after alter)

```bash
php install/unify_serials_seller.php --dry-run
php install/unify_serials_seller.php
# optional: also copy leftover new_serials into old_serials
php install/unify_serials_seller.php --merge-new-serials
```

### Toggle seller/client on a series (like LAN)

```bash
php install/set_serial_category.php --category=seller_to_end_user --prefix=M
php install/set_serial_category.php --category=seller_to_end_user --s-from=440000 --s-to=450000
php install/set_serial_category.php --category=end_user_client --s-from=1 --s-to=1000
```

## Phone sync (dual-buffer)

1. Set `$PAYAMESH_SYNC_TOKEN` in `config/database.php` (or `crm_sync_meta.device_token`).
2. App / LAN Admin Setup: Cloud sync URL = base folder that contains `api/`, paste token.
3. Endpoints (device token):
   - `GET /api/sync/buffer` — list remote pending change-buffer entries.
   - `POST /api/sync/buffer/apply` — apply phone buffer entries onto remote MySQL.
   - `POST /api/sync/buffer/ack` — mark remote buffer entries cleared after phone apply.
   - `POST /api/sync/buffer/backfill` — re-enqueue the most recently registered serials (count-based, up to 5000) as pending so a phone/LAN web that's behind (e.g. after loading a stale CSV/backup) can pull them. Called as the first step of "Get from server", not on its own.
4. Buffered: registrations, report edits, serial generation (+ groups). **Not buffered:** CSV import.
5. Same serial in both pending buffers with different data → conflict popup on the phone (pick local or remote); the LAN web "Send to server" action just holds back conflicting serials as pending (no popup) since resolution only happens on the phone.
6. **No periodic/background sync.** Pull ("Get from server") and push ("Send to server") are separate one-directional buttons on both the Android app and the LAN CRM web Admin Setup page. Two silent, event-driven auto-triggers exist: the LAN web app does a cheap pull (no backfill) once per page load, and the Android app does a debounced push shortly after any local registration/edit — both no-op quietly if the server isn't reachable, leaving the change pending for the next manual tap.
7. Group ID mapping: phone `serial_groups.syncOriginGroupId` ↔ MySQL `serial_groups.id`; MySQL `sync_origin_lan_id` ↔ phone local group id.

Legacy `/api/sync/push`, `/pull`, `/manifest` return **410 Gone**.

## Dates

Wire format: Jalali `YYYY/MM/DD` (Latin digits). Legacy `time` kept; `date_jalali` is additive.
