# Data Call (CCP)

Android app that sends compressed images over a **voice phone call** using an FSK audio modem — no internet required.

## Features

- **Unified app** with Sender and Receiver modes
- **Image capture** (camera) or gallery upload
- **Compression pipeline**: grayscale, max 160×160, JPEG 5–20 KB, saved to `files/compressed/` before send
- **Auto-dial** on Send; handshake retries after call answered
- **Receiver listening mode** — any incoming call treated as data transfer
- **Preview data tone** before calling (verify speaker output)
- **FSK modem** at 1200/2200 Hz, 600 bps call mode, acoustic speaker-to-mic path
- **Packet protocol** with CRC32 and retransmit-on-fail
- **Diagnostics / loopback** screen to validate modem without a real call

## Requirements

- Android 8.0+ (API 26)
- Two phones with SIM cards (for real-call testing)
- Permissions: `RECORD_AUDIO`, `CALL_PHONE`, `READ_PHONE_STATE`, `CAMERA`, `READ_MEDIA_IMAGES`, `POST_NOTIFICATIONS`

## Build

Open the `CCP` folder in Android Studio and run **Build > Make Project**.

## How audio transfer works

Regular Android apps **cannot inject digital audio directly into the cellular uplink** on all devices. This app uses **acoustic coupling**:

1. Sender plays FSK tones through the **speaker** (mic muted during transmit)
2. Tones travel through the air to the other phone's **microphone**
3. Receiver records from mic and decodes

You **should** hear buzzing/beeping on the sender when using **Preview data tone**. The other phone only receives data if speakerphone works and the room is quiet.

## How to test

### Step 0: Preview tone (sender phone, no call)

1. Open app → **Sender Mode**
2. Tap **Preview data tone**
3. You should hear buzzing/beeping from the speaker
4. If silent: raise media volume, disable silent mode, retry

### Step 1: Loopback (single device, no call)

1. Open app → **Diagnostics / Loopback**
2. Tap **Run loopback test** — expect **PASS**

### Step 2: Real call (two devices)

**Receiver phone (do this first):**

1. Open app → **Receiver Mode**
2. Tap **Start listening** (before the call arrives)
3. When sender calls, **answer** and use **speakerphone**
4. Keep phone flat, not against your ear; minimize background noise

**Sender phone:**

1. Open app → **Sender Mode**
2. Tap **Preview data tone** to confirm audio works
3. Enter receiver number, compress an image
4. Tap **Send (auto-dial)**
5. After receiver answers, app waits 3s then retries handshake up to 15 times
6. Watch logs: `Handshake attempt 1/15...`

### Tips for reliable transfer

- Receiver must tap **Start listening** before the call
- Both phones: **speakerphone**, quiet room, phones not pressed to ear
- Sender mic is **muted during transmit** to reduce noise
- Use **Preview data tone** first on sender
- Real-call mode uses **600 bps** (slower, more robust over phone codec)

## Architecture

| Package | Role |
|---------|------|
| `image/` | ImageCompressor |
| `protocol/` | Handshake, packets, CRC32 |
| `modem/` | FSK modulator/demodulator, AudioIo |
| `call/` | CallAudioRouter, auto-dial, phone state, foreground service |
| `session/` | TransferSession orchestration |
| `data/` | Room endpoint registry |
| `ui/` | Compose screens |

## Protocol summary

1. Sender retries `DATA_CALL_V1` every ~2s (up to 15 attempts)
2. Receiver replies `READY`
3. Sender sends JSON header `{ "type": 103, "size": N, "packets": M }`
4. Sender sends packets: `[Sync][Id][Total][Len][Payload≤128B][CRC32]`
5. Receiver NACKs missing packets; reconstructs JPEG on completion

## Known limitations

- Cannot mute remote caller voice at network level (carrier controls downlink)
- Cannot guarantee digital-only injection into PSTN on all devices
- Acoustic path quality varies by device, carrier, and noise
- Receiver must **manually answer** the call
- Some devices may not route `STREAM_VOICE_CALL` — app falls back to speaker playback

## Unit tests

```bash
cd CCP
./gradlew test
```

Tests cover `PacketCodec`, `Crc32`, FSK modem round-trip, and phone normalization.
