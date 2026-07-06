# SMS Gateway (Native Android)

Native Android SMS gateway app. Forwards incoming SMS to your PHP endpoint (or local Room DB) and sends the reply back to the sender. Includes a **browser CRM** served over phone hotspot LAN — no PC install required.

## Build web CRM (before APK if you changed `crm-web/`)

```bat
cd /d D:\Gate-Way-Guarantee\crm-web
npm install
npm run build
```

This writes static files to `app\src\main\assets\crm\`. See [`../crm-web/README.md`](../crm-web/README.md).

## Build in Android Studio

1. Open `and_studio_proj` in Android Studio
2. **File → Sync Project with Gradle Files** (wait for it to finish)
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**

If sync fails with "Plugin com.android.application was not found":
- Use a VPN (Google Maven may be blocked in some regions)
- Or retry sync — Aliyun mirrors are configured in `settings.gradle.kts`
- **File → Invalidate Caches → Restart** if sync keeps failing

Or from CMD:

```bat
set JAVA_HOME=D:\Gate-Way-Guarantee\OpenJDK17U-jdk_x64_windows_hotspot_17.0.19_10\jdk-17.0.19+10
cd /d D:\Gate-Way-Guarantee\and_studio_proj
gradlew.bat assembleRelease
```

APK output: `app\build\outputs\apk\release\app-release.apk`

## Install

1. Copy APK to your gateway phone
2. Enable install from unknown sources
3. Install the APK

## First run

1. Open **Sms Gateway**
2. Tap **Request permissions** (SMS + notifications)
3. Choose verification mode in **Settings** (remote server or internal DB)
4. Open **Battery optimization settings** and disable optimization for this app
5. Tap **Start gateway** — persistent notification should appear

## LAN browser CRM (hotspot)

1. Start the gateway service on the phone
2. **Settings → CRM شبکه محلی** → enable LAN CRM (sets internal DB mode)
3. Turn on phone **hotspot**
4. On PC/tablet: connect to phone Wi‑Fi, open the URL shown in settings (e.g. `http://192.168.43.1:8765`)
5. Enter the PIN shown on the phone — no app install on PC

CRM features: live dashboard (1s polling + toasts), bulk serial creation, Jalali date-range reports, CSV export.

API spec: [`../shared/api/openapi.yaml`](../shared/api/openapi.yaml)

## How it works

**Remote mode:**
```
Incoming SMS → SmsReceiver → POST Gateway.php → send reply SMS
```

**Internal / LAN mode:**
```
Incoming SMS → SmsReceiver → Room DB → send reply SMS
PC browser → Ktor HTTP API → same Room DB
```

Foreground service keeps the app alive when the screen is off.

## Server (remote mode)

Deploy [`Gateway.php`](../Gateway.php) to your server.

**Request:** `{ "from": "+98912...", "message": "text" }`  
**Response:** `{ "status": "success", "reply": "text to send back" }`
