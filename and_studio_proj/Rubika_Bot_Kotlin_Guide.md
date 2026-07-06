# Rubika Bot Integration Guide (Kotlin App)

## Overview

This guide explains how to:

1. Verify a Rubika bot token
2. Receive updates using polling (`getUpdates`)
3. Store and use `next_offset_id`
4. Continue polling from the last offset
5. Send messages to users
6. Integrate the flow into a Kotlin Android application

---

## 1. Verify Bot Token

Endpoint:

```text
POST https://botapi.rubika.ir/v3/{TOKEN}/getMe
```

Example:

```bash
curl -X POST https://botapi.rubika.ir/v3/YOUR_TOKEN/getMe
```

Successful response:

```json
{
  "status": "OK",
  "data": {
    "bot": {
      "bot_id": "...",
      "bot_title": "My Bot",
      "username": "MyBot"
    }
  }
}
```

Verification rule:

- HTTP request succeeds
- `status == "OK"`
- Bot information is returned

---

## 2. Receive Updates (Polling)

Endpoint:

```text
POST https://botapi.rubika.ir/v3/{TOKEN}/getUpdates
```

Request:

```json
{
  "limit": 10
}
```

Example:

```bash
curl -X POST https://botapi.rubika.ir/v3/YOUR_TOKEN/getUpdates \
-H "Content-Type: application/json" \
-d "{\"limit\":10}"
```

Typical response:

```json
{
  "status": "OK",
  "data": {
    "updates": [
      {
        "type": "NewMessage",
        "chat_id": "CHAT_ID",
        "new_message": {
          "message_id": "MESSAGE_ID",
          "text": "سلام"
        }
      }
    ],
    "next_offset_id": "OFFSET_ID"
  }
}
```

---

## 3. Important Concepts

### chat_id

Used when sending messages.

Example:

```json
{
  "chat_id": "b0BPOc0pCM0a1efdca1467369ecb5e8c"
}
```

### message_id

Unique identifier of a message.

Example:

```json
{
  "message_id": "1816987389793727458"
}
```

### next_offset_id

Cursor used to continue reading updates.

Example:

```json
{
  "next_offset_id": "6a46bb9369874594ff16c4f1"
}
```

Important:

`next_offset_id` is NOT a message id.

Store it after every successful polling request.

---

## 4. Continue From Last Offset

After processing updates:

```text
Save next_offset_id
```

Example:

```text
6a46bb9369874594ff16c4f1
```

Next request:

```json
{
  "offset_id": "6a46bb9369874594ff16c4f1",
  "limit": 10
}
```

This returns only newer updates.

Recommended storage:

- SharedPreferences
- SQLite
- Room Database

---

## 5. Polling Loop Strategy

Pseudo-code:

```text
Load saved offset

Loop forever:

    Call getUpdates(offset)

    Process all updates

    Save next_offset_id

    Wait 3-5 seconds
```

Recommended interval:

```text
3 to 5 seconds
```

This avoids unnecessary requests while remaining responsive.

---

## 6. Send Message

Endpoint:

```text
POST https://botapi.rubika.ir/v3/{TOKEN}/sendMessage
```

Request:

```json
{
  "chat_id": "CHAT_ID",
  "text": "Hello from Kotlin"
}
```

Example:

```bash
curl -X POST https://botapi.rubika.ir/v3/YOUR_TOKEN/sendMessage \
-H "Content-Type: application/json" \
-d "{\"chat_id\":\"CHAT_ID\",\"text\":\"Hello\"}"
```

Expected response:

```json
{
  "status": "OK"
}
```

---

## 7. Recommended Kotlin Architecture

```text
Rubika API
      ↑↓
Polling Service
      ↓
Repository
      ↓
Room Database
      ↓
CRM UI
```

Components:

- Retrofit / OkHttp
- Room Database
- WorkManager or Foreground Service
- Repository Pattern

---

## 8. Startup Procedure

On application startup:

1. Load saved offset
2. Start polling service
3. Call getUpdates()
4. Process messages
5. Save next_offset_id
6. Continue polling

---

## 9. Message Processing Flow

```text
NewMessage
     ↓
Extract chat_id
     ↓
Extract sender_id
     ↓
Extract text
     ↓
Store in database
     ↓
Business logic
     ↓
sendMessage()
```

---

## 10. Example CRM Use Cases

- Product registration
- Warranty activation
- Warranty lookup
- Support tickets
- Customer surveys
- Service reminders
- Broadcast notifications

---

## 11. Reliability Recommendations

Always:

- Persist next_offset_id immediately
- Log API failures
- Retry network errors
- Avoid processing the same update twice
- Store incoming messages before business logic

This guarantees recovery after application restart or device reboot.
