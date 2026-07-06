# Android Data Call Application Specification

## Goal

Create an Android application that identifies designated phone numbers as data-call endpoints and automatically exchanges compressed data over a voice call using an audio modem protocol.

## Core Concept

When a call is made to or received from a configured number:

- Treat the call as a data session.
- Automatically start sender/receiver logic.
- Exchange a handshake.
- Transfer compressed payloads.
- Verify integrity.
- Report transfer statistics.

## Data Endpoint Registry

Example:

```json
{
  "02112345678": "Branch A",
  "09121234567": "Warehouse",
  "09127654321": "Backup Node"
}
```

Incoming calls from registered numbers should launch data mode.

## Handshake

Sender transmits:

DATA_CALL_V1

Receiver replies:

READY

Transfer begins only after successful handshake.

## Data Types

| Type | Purpose |
|--------|----------|
| 100 | CRM Sync |
| 101 | Inventory Update |
| 102 | Warranty Data |
| 103 | Image Transfer |
| 104 | Database Backup |

Example header:

```json
{
  "type": 103,
  "size": 5120,
  "packets": 40
}
```

## Technology

- Kotlin
- Android SDK 26+
- MVVM
- Kotlin Coroutines
- AudioRecord
- AudioTrack
- Reed-Solomon error correction

## Image Transfer

Before transmission:

- Convert to grayscale
- Resize to 160x160 max
- JPEG quality 10%
- Target size 5–20 KB

## Packet Structure

| Field | Size |
|---------|---------|
| Sync | 2 bytes |
| Packet ID | 2 bytes |
| Total Packets | 2 bytes |
| Payload Length | 2 bytes |
| Payload | 128 bytes |
| CRC32 | 4 bytes |

## Modulation

FSK:

- 1200 Hz = 0
- 2200 Hz = 1

Configurable bitrate.

## Metrics

Track:

- Transfer duration
- Bitrate
- Packet loss
- CRC failures
- Reconstruction success

## Testing

Include:

- Loopback mode
- Sender screen
- Receiver screen
- Logs and diagnostics

## Deliverables

1. Android Studio project
2. Kotlin source
3. MVVM architecture
4. Data endpoint registry
5. Handshake protocol
6. Image transfer pipeline
7. FSK modem implementation
8. Testing tools
9. README
