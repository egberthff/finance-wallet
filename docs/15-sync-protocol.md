# Device Sync Protocol (DRAFT, for Phase 7)

| Status | **Draft.** Implemented only if the owner approves cloud features (Q-01). Only the schema fields it needs exist from v1 |
|---|---|
| Decision | ADR-0007 |
| Requirements | SYNC-01/02, AC-SYNC-1..4 |

## 1. Model
- Every user-data row carries `version`, `updated_at`, `updated_by_device`, `hlc` (schema §1).
- **HLC** format: `{physicalMillis:013d}-{logical:05d}-{deviceId}`. It compares lexicographically, and the device id breaks ties deterministically.
- Each committed use case appends one **operation** per changed row to `sync_outbox` (added in the Phase 7 migration):
  `{opId, table, rowId, kind: UPSERT|TOMBSTONE, baseVersion, newVersion, hlc, fields (changed only), deviceId}`.
- Operations from one use case share a `changeSetId` and are applied atomically on other devices.

## 2. Transport (server is a blind relay)
- Change sets are serialized, compressed, and encrypted with **K_data** (per-user, random 256-bit). K_data is wrapped per device with that device's public key (X25519).
  A new device is authorized by an existing device, either by scanning a QR code with a one-time code or from the backup passphrase.
- The server assigns a monotonically increasing `seq` per user and stores only `{seq, deviceId, size, ciphertext}`.
- Each device tracks `lastSeqApplied`. Pull uses `GET since=seq`, push uses `POST` change sets (idempotent by changeSetId).

## 3. Merge rules (deterministic, run identically on every device)

| Situation | Rule |
|---|---|
| Create (new id) | Apply; ids are UUIDv7, so creates never collide |
| Update, `baseVersion` = local version | Fast-forward |
| Concurrent updates (neither saw the other) on **non-financial fields** (names, notes, icons, colors, sort order, descriptions) | Field-level LWW by HLC |
| Concurrent updates touching **financial fields**: txn {type, status, txn_date, entries, reverses/refund links}, allocation entries, opening balances, debt terms | **Conflict**: both versions kept (`conflict_state = CONFLICTED`, `sync_conflict` row with both snapshots). Balances use the last common version until the user picks one. The user's resolution is a new UPDATE with both versions as its base |
| Tombstone vs concurrent update | Tombstone wins; the update snapshot is stored in `sync_conflict` for review ("deleted on device A while edited on device B") |
| Duplicate business events (same statement imported on two devices) | Fingerprint uniqueness: the later-applied txn with an already-present `(account, fingerprint)` is converted to a conflict of type DUPLICATE_IMPORT, so the user keeps one |
| Unique-constraint collision (e.g., category name) | Rename the later one with a suffix " (2)" and notify |

Invariants after merge: IC-1..IC-8 must pass. A change set that would violate them is quarantined (not applied) and reported, never forced.

## 4. Recovery
Interrupted sync is safe because change sets are atomic and idempotent and `lastSeqApplied` advances only after a local commit.
A device offline for a long time catches up from its seq. If the server compacts history, the device must restore from a snapshot first (Phase 7 design).

## 5. Open items (Phase 7)
Server-side compaction and snapshotting; device revocation and K_data rotation; conflict UI design; bandwidth limits.
