# SMS Gateway (Flutter Android)

Android SMS gateway app that forwards incoming SMS to your PHP endpoint and sends the server reply back to the sender.

## Prerequisites

- [Flutter SDK](https://docs.flutter.dev/get-started/install) (stable channel)
- Android SDK (via Android Studio)
- A dedicated Android phone with SIM (sideload install)

## Setup

```bash
cd sms_gateway
flutter pub get
```

Ensure `android/local.properties` exists (created automatically by Flutter) with your Android SDK path:

```
sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
flutter.sdk=C:\\path\\to\\flutter
```

## Build release APK

```bash
cd sms_gateway
flutter build apk --release
```

Output: `build/app/outputs/flutter-apk/app-release.apk`

## Install (sideload)

1. Copy `app-release.apk` to the gateway phone.
2. Enable "Install unknown apps" for your file manager.
3. Install the APK.

## First-run configuration

1. Open **SMS Gateway**.
2. Tap **Request permissions** and grant SMS + notifications.
3. Confirm gateway URL (default: `https://startechgroup.ir/gateway/Gateway.php`).
4. Tap **Battery optimization settings** and disable optimization for this app.
5. Tap **Start gateway** — a persistent notification should appear.

## Server setup

Deploy the updated [`Gateway.php`](../Gateway.php) to your server. It expects:

**Request:**
```json
{ "from": "+98912...", "message": "user text" }
```

**Response:**
```json
{ "status": "success", "reply": "text to send back" }
```

## Manual test plan

1. Deploy `Gateway.php` to `startechgroup.ir`.
2. Install release APK on the gateway phone.
3. Start the gateway service and confirm the foreground notification.
4. From another phone, send an SMS to the gateway number.
5. Verify:
   - Entry appears in the app activity log
   - Server `sms_log.txt` records the request
   - Original sender receives the reply SMS
6. Lock the screen and send another SMS — confirm it still works.
7. Reboot the phone — service should auto-start if permissions were granted.

## Architecture

```
Incoming SMS -> telephony receiver -> POST Gateway.php -> send reply SMS
```

Foreground service keeps the process alive when the screen is off (~100 SMS/day).

## Troubleshooting

- **No SMS received when screen off:** Disable battery optimization; keep the service started.
- **Another SMS app steals broadcasts:** This app uses high-priority `SMS_RECEIVED` receiver.
- **Gateway errors in log:** Check server URL, HTTPS connectivity, and `Gateway.php` logs.
