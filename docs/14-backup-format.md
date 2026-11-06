# Backup File Format (`.fwbak`, format version 1)

| Status | Phase 2 draft v1 (2026-11-05). Decision record: ADR-0011 |
|---|---|
| Requirements | BAK-01, LOC-04 (pre-migration backup), R-10, R-11 |

> **Implementation notes (Phase 7, 2026-11-06)** — `data/.../backup/`
> 1. Header and chunk format exactly as §2 (big-endian). A full-length chunk is final only if the file ends right after it, so
>    truncation at a chunk boundary, reordering, duplication and appended data all fail authentication. Header bounds: Argon2id
>    m 8–256 MiB, t ≤ 10, p ≤ 4; PBKDF2 100k–5M iterations; chunk 64 KiB–8 MiB.
> 2. Payload values are typed strings (`"i:<integer>"`, `"s:<text>"`, `null`); the manifest lists each table's columns. Per-table
>    SHA-256 is over the exact row lines. The ledger checksum covers every real account's balance (live transactions) and every goal's
>    allocation.
> 3. **Restore deviates from §4 steps 4 and 7** (approved with the Phase 7 plan): after a full verification pass, data is replaced inside the
>    existing database in **one transaction** (append-only triggers dropped and recreated inside it, foreign keys deferred), then balances,
>    allocations and IC-1..IC-8 are checked before commit. No second database file, no file swap, no key rotation. A copy of the current
>    database is taken first. The restoring device keeps its own `device_id`.
> 4. Backups from an older schema are accepted while all migrations are additive (1→4 are); a backup from a newer schema is refused.
> 5. The pre-migration copies (§5 "automatic local") remain the raw SQLCipher file copy of Phase 4 (encrypted by the Keystore-wrapped
>    database key), not this format.

## 1. Goals
- Restorable on **another device** or after a factory reset. It does not depend on the Android Keystore.
- Confidential and tamper-evident without the passphrase.
- Never writes plaintext financial data to disk, not even temporarily.
- Restorable by **newer** app versions: the payload is logical, not a raw DB file, and goes through migrations.
- Verifiable: integrity is checked before any existing data is replaced.

## 2. Layout
```
+--------------------------------------------------------------+
| Header (plaintext, authenticated as AAD)                      |
|   magic        4 B   "FWBK"                                   |
|   formatVer    u16   1                                        |
|   kdfId        u8    1 = Argon2id, 2 = PBKDF2-HMAC-SHA256     |
|   kdfParams    var   Argon2id: m KiB u32, t u32, p u8         |
|                      PBKDF2: iterations u32                   |
|   salt         16 B  random                                   |
|   noncePrefix  8 B   random                                   |
|   chunkSize    u32   1 MiB                                    |
|   createdAt    i64   epoch ms                                 |
|   appVersion   u32   versionCode                              |
|   schemaVer    u32   DB schema version of the source          |
+--------------------------------------------------------------+
| Chunk 0..n-1: AES-256-GCM(ciphertext ‖ 16 B tag)              |
|   nonce = noncePrefix(8) ‖ chunkIndex(u32 BE)                 |
|   AAD   = SHA-256(header) ‖ chunkIndex ‖ isFinal(u8)          |
+--------------------------------------------------------------+
```
- Key = KDF(passphrase UTF-8 NFC, salt) → 32 bytes.
  - **Default Argon2id** m = 65,536 KiB (64 MiB), t = 3, p = 1. On devices where the 64 MiB allocation fails, it falls back to m = 32,768, t = 4, recorded in the header.
  - **Fallback PBKDF2-HMAC-SHA256** at 600,000 iterations (platform `SecretKeyFactory`), used only if the Argon2 implementation is rejected for size in Phase 3 (ADR-0011).
- The `isFinal` flag in the AAD prevents truncation: the reader requires exactly one final chunk, and it must be the last.
- Chunk reordering is prevented by the chunk index in the nonce and AAD.

## 3. Payload (plaintext before encryption, gzip-compressed stream)
JSON Lines, one record per line:
```
{"t":"manifest","formatVer":1,"schemaVer":1,"deviceId":"…","createdAt":…,"tables":{"txn":1234,"entry":2901,…}}
{"t":"row","table":"ledger_account","v":{…column:value…}}
… (all user-data tables, parents before children, in a fixed order)
{"t":"checksums","perTableSha256":{…},"ledger":{"<accountId>":{"PHP":"123456"}}, "allocations":{"<goalId>":"500000"}}
```
- Money values are serialized as JSON **strings** of integers (no floats in JSON parsers).
- Attachments (when present) are appended as `{"t":"blob","attachmentId":…,"b64":…}` records, chunked base64.
- `ledger` checksums are the per-account balances at backup time. Restore recomputes and compares them (§4 step 5).

## 4. Restore (UC-29)
1. The user picks a file via SAF and enters the passphrase.
2. Read and validate the header (magic, version ≤ supported, KDF params within safe bounds, so a hostile file cannot force huge memory).
3. Stream decrypt chunk by chunk. **Any** tag failure aborts: "Wrong passphrase or damaged file".
4. Build a **new** SQLCipher database file (new random key) at schema version `schemaVer`, insert rows, then run migrations up to current.
5. Run integrity checks IC-1..IC-8 and compare recomputed balances and goal allocations with the `checksums` record. Any mismatch aborts.
6. Show a summary (counts, date range, backup date), and the user confirms **Replace all current data**.
7. Write an automatic backup of the current database (pre-restore), then swap the files atomically (rename in the same directory), rotate the Keystore-wrapped key, and restart the data layer.
8. Write `audit_event(RESTORE)`.
Restore is **replace-only**. Merging a backup into existing data is not supported, because it would bypass the duplicate and conflict rules.

## 5. Where backups go
- **User-initiated:** any SAF location (Downloads, Google Drive via SAF provider, USB). The user controls the copy.
- **Automatic local (pre-migration, pre-restore, optional weekly):** app-private storage, last 2 kept, encrypted with a
  **device key** (random 32-byte key wrapped by the Keystore), *not* the user passphrase. These protect against bad migrations, not device loss.
- **Optional reminders:** if no user-initiated backup exists in N days (default 30), the dashboard shows a reminder (R-10).

## 6. Passphrase rules
At least 10 characters, rejected if it appears in a bundled list of the 10k most common passwords, with a strength meter.
The app **cannot recover a forgotten passphrase**, and the UI says so before the first backup.
