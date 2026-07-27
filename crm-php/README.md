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

## Phone sync

1. Set `$PAYAMESH_SYNC_TOKEN` in `config/database.php` (or `crm_sync_meta.device_token`).
2. App → Admin Setup: Cloud sync URL = base folder that contains `api/`, paste token.
3. Endpoints:
   - `POST /api/sync/push` — outbox events: `serial_upsert`, `serial_batch_upsert`, `group_upsert`, `group_delete` (+ activity/lottery). Last-write-wins on `sync_updated_ms`.
   - `GET /api/sync/pull?since=` — returns `{ cursor, hasMore, serials, groups, deletedGroups }` (unused + registered inventory, groups, delete tombstones).
4. Phone runs background sync ~every 10 minutes when URL/token are configured (Admin “sync now” still works).
5. Group ID mapping: phone `serial_groups.syncOriginGroupId` ↔ MySQL `serial_groups.id`; MySQL `sync_origin_lan_id` ↔ phone local group id.

## Dates

Wire format: Jalali `YYYY/MM/DD` (Latin digits). Legacy `time` kept; `date_jalali` is additive.
