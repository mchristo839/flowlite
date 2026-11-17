# FlowLite sync API

Both clients (Android and Linux) follow this contract. The server is PocketBase 0.40.5 at `https://sync.example.com`. It stores text only. Audio never leaves the device.

## Auth

One user account. Self registration is off.

```
POST /api/collections/users/auth-with-password
{"identity": "<email>", "password": "<password>"}
-> 200 {"token": "<jwt>", "record": {"id": "<user id>", ...}}
```

- Send the token as `Authorization: <token>` (no `Bearer` prefix needed, PocketBase accepts both).
- Tokens last 365 days. Call `POST /api/collections/users/auth-refresh` with the token once per day to extend it.
- Store the token and the user id securely (Android: EncryptedSharedPreferences, Linux: the app settings file with 0600 permissions). Do not store the password.
- On `401`, stop syncing and show "Sign in again" in Settings. Dictation keeps working.

## Collections

Every record has these fields:

| Field | Type | Notes |
|-------|------|-------|
| `id` | text, 36 chars | Lowercase UUID made by the device. The server never generates ids. |
| `owner` | relation | The user id. Required on create. Rules allow access only when `owner = auth user`. |
| `updated_at` | integer | Device clock, Unix milliseconds. Drives conflict resolution. |
| `deleted` | bool | Soft delete. Hard `DELETE` is blocked. |
| `synced_at` | autodate | Server time of the last write, format `2026-10-12 10:33:11.123Z`. Read only. Used as the pull cursor. |

### transcripts

`text`, `raw_text`, `device` (`android` or `linux`), `app_name`, `engine`, `model`, `language`, `duration_ms`, `transcribe_ms`, `created_at` (Unix ms). Id is a random UUID v4.

### dictionary

`word`, `replacement` (empty means "recognise this spelling", set means "replace word with replacement"). The id is deterministic so the same word added on two devices becomes one record:

```
id = uuid5(NAMESPACE_URL, "flowlite:dictionary:" + lowercase(trim(word)))
```

### settings

`key`, `value_json`. Shared keys only:

| key | value | Meaning |
|-----|-------|---------|
| `language` | `"auto"` or an ISO code such as `"en"`, `"el"` | Transcription language |
| `remove_filler_words` | `true` / `false` | Cleanup rule |
| `history_days` | integer, `0` = keep forever | Each client soft-deletes transcripts older than this and blanks their text |

Device settings such as hotkeys, bubble position and engine choice stay local. Id is `uuid5(NAMESPACE_URL, "flowlite:settings:" + key)`.

`NAMESPACE_URL` is the RFC 4122 namespace `6ba7b811-9dad-11d1-80b4-00c04fd430c8`.

## Sync rules

1. **Local first.** Every write goes to the local database, then into a sync queue (the set of local records with `dirty = true`). Dictation never waits for the network.
2. **Push** each dirty record as a full snapshot:
   - `PATCH /api/collections/{c}/records/{id}` with all fields.
   - `404` means the record is new: `POST /api/collections/{c}/records` with the same body plus `id` and `owner`.
   - `200` or `409` clears `dirty`. `409` means the server holds a newer `updated_at`. The next pull brings it down.
   - `400` on a single record: log it and clear `dirty` so one bad record does not block the queue.
   - Network error or `5xx`: keep `dirty`, retry with backoff 5 s, 10 s, 20 s ... capped at 5 min.
3. **Pull** per collection after the push:
   ```
   GET /api/collections/{c}/records?filter=(synced_at>="<cursor - 2 s>")&sort=synced_at,id&perPage=500&page=N&skipTotal=1
   ```
   Keep paging until a page returns fewer than 500 items. The 2 second overlap catches writes that committed out of order. Applying a record twice is harmless.
4. **Apply** each pulled record: if the local copy is missing, or the remote `updated_at` is greater than the local one, write the remote copy locally with `dirty = false`. Otherwise keep the local copy (it is already queued or identical).
5. Save the cursor as the largest `synced_at` seen, per collection. The first sync uses an empty cursor (`synced_at>=""` returns everything).
6. **Conflicts** resolve by latest `updated_at` (last write wins). The server enforces this too: a `PATCH` with an older `updated_at` returns `409`.
7. **Deletes** set `deleted = true` and bump `updated_at`. Clients hide deleted records and never remove them from the server.
8. **When:** on app start, after each dictation, every 5 minutes, and on "Sync now".

## Example

```
curl -s -X PATCH https://sync.example.com/api/collections/transcripts/records/0d5c... \
  -H "Authorization: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"text":"Hello there.","raw_text":"um hello there","device":"android","updated_at":1791800000000,"created_at":1791800000000,"deleted":false}'
```

`sync-server/test_contract.py` exercises every rule above against a live server.
