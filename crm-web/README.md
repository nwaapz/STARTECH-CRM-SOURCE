# Gateway LAN CRM (Web)

Browser CRM served from the Android SMS gateway over phone hotspot LAN. No installation required on PC — open the URL shown in the phone app settings.

## Build web assets

```bash
cd crm-web
npm install
npm run build
```

Output is written to `and_studio_proj/app/src/main/assets/crm/`.

## Build Android APK

After building the web assets:

```bat
set JAVA_HOME=D:\Gate-Way-Guarantee\OpenJDK17U-jdk_x64_windows_hotspot_17.0.19_10\jdk-17.0.19+10
cd /d D:\Gate-Way-Guarantee\and_studio_proj
gradlew.bat assembleRelease
```

## End-user setup

1. On the phone: start the SMS gateway service.
2. Settings → enable **CRM شبکه محلی (هات‌اسپات)**.
3. Turn on phone hotspot.
4. On PC: connect to the phone Wi‑Fi.
5. Open the CRM URL from phone settings (e.g. `http://192.168.43.1:8765`).
6. Enter the PIN shown on the phone.

## API

See [`../shared/api/openapi.yaml`](../shared/api/openapi.yaml).

- `GET /api/health` — no auth
- `GET /api/sync?since=` — stats + change events (polled every 1s on dashboard)
- `POST /api/serials/batch` — bulk create serials
- `GET /api/serials` — filtered reports

## Local dev (optional)

Run Vite dev server with API proxy to a phone on LAN:

```bash
cd crm-web
npm run dev
```

For production, the phone serves both API and static files from the same origin.

## Test checklist

- [ ] `GET /api/health` works from PC browser on hotspot
- [ ] Wrong PIN returns 401
- [ ] Batch create from web updates phone DB stats
- [ ] SMS registration (INTERNAL mode) appears on dashboard within ~1s + toast
- [ ] Reports filter by Jalali date range
- [ ] CSV export downloads
- [ ] LAN server stops when gateway service stops
