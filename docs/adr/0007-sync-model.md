# ADR-0007: E2E-encrypted operation log with deterministic client-side merge

- **Status:** **Superseded by [ADR-0012](0012-no-cloud-on-device-insights.md)** (2026-11-06): cloud features dropped by the owner. Previously Accepted (2026-11-05).
- **Requirements:** SYNC-01/02, AC-SYNC, R-04

## Context
Optional multi-device sync (between the user's Android devices) must not use blind last-write-wins on financial data, and the server should not see plaintext.

## Decision
Devices append HLC-ordered operations, encrypted with a user data key that is wrapped per device. The server relays and checkpoints only. Merge rules: creates never conflict (UUIDv7); non-financial fields use field-level LWW; concurrent changes to financial fields create a user-visible conflict; tombstones win, with the edit kept for review. Balances are never synced and are always recomputed. Backup and device sync are separate subsystems.

## Alternatives considered
CRDT libraries (opaque merge semantics for money). Server-side merge (requires plaintext). Whole-DB snapshot sync (bandwidth, conflict loss).

## Consequences
- **Pro:** Integrity preserved; a server breach exposes no financial data.
- **Con:** Conflict UI needed; key recovery depends on the backup passphrase.
