# Milestone Plan: Phase 7, Encrypted Backup, Privacy Controls and On-Device Insights

| Status | **Implemented and tested on the JVM; awaiting owner review** (approved "proceed to Phase 7", 2026-11-06; scope ADR-0012). AC-BAK 1–5, 7, 8 met by executed tests; AC-BAK 6 met for files (Robolectric), Keystore deletion and restart to be confirmed on device in Phase 8 |
|---|---|

## 1. Objectives
Make the user's data safe to keep and to move: an encrypted backup file that restores on any phone, full data export and full
deletion (PRIV-01), and deterministic on-device insights in place of an AI assistant. Cloud backup and multi-device sync are
**out of scope** (ADR-0012, owner decision).

## 2. Scope (in build order)
| Step | Deliverable | Reqs |
|---|---|---|
| 7.1 | Scope decision recorded: ADR-0012, CLAUDE.md amendment, PRD (BAK-02/SYNC removed, AI → INS-01..03), ADR-0005/0007 superseded | — |
| 7.2 | `.fwbak` format (docs/14-backup-format.md): header, Argon2id (BouncyCastle 1.86) with PBKDF2 fallback id, chunked AES-256-GCM streams with index/final-bound AAD, safe header bounds; passphrase policy (≥ 10 characters, not in the bundled 10k common-password list, strength meter) | BAK-01, R-11 |
| 7.3 | Backup service: logical JSONL payload of every user table with per-table and ledger checksums; inspect (full verification, no writes); replace-only restore in **one database transaction** after verification, with a pre-restore copy; plaintext JSON export of all data; delete all data (UC-31) | BAK-01, PRIV-01, UC-29/30/31 |
| 7.4 | On-device insights (domain + query service): category changes vs. previous period, unusual transactions, top categories, bills due soon vs. free money, estimated saving capacity | INS-01..03 |
| 7.5 | UI: Backup & restore (system picker, passphrase with meter, unrecoverable-passphrase warning, restore summary and confirmation), Privacy (export all, delete all with PIN), Insights, Home backup reminder (R-10) | BAK-01, PRIV-01, INS, R-10 |

## 3. Decisions and deviations (to approve with this plan)
- **Restore replaces data inside the existing encrypted database in one SQLite transaction** instead of building a second
  database file and swapping files plus keys (docs/14-backup-format.md §4 steps 4 and 7). One transaction is atomic by
  construction (a crash or a failed check leaves the old data), whereas a two-file swap needs its own crash journal. Consequence:
  the database key is not rotated on restore (it never left the Keystore-wrapped key file). The pre-restore safety copy is kept.
- **Older backups are restored by inserting their rows directly.** Migrations 1→4 are additive (new tables only), so rows from
  any earlier schema fit the current one. A future non-additive migration must add a row transformer for older backups; the
  restore refuses a backup whose schema version it does not know how to read.
- **Common-password list:** SecLists `10k-most-common.txt` (MIT License, Daniel Miessler), bundled as an app resource.

## 4. Dependencies
BouncyCastle `bcprov-jdk18on` 1.86 (Argon2 only; +31 KB measured in docs/spikes/apk-size.md). No network access is added.

## 5. Risks
| Risk | Mitigation |
|---|---|
| A damaged or tampered backup replaces good data | Full decryption and checksum verification before any change; then the restore itself re-verifies and runs IC-1..8 and ledger checksums inside the transaction, rolling back on any mismatch |
| Hostile backup headers (huge KDF memory) | Header bounds checked before any allocation |
| Plaintext on disk | Streaming encryption to the user-chosen file only; no temporary files |
| Forgotten passphrase | Stated before the first backup; cannot be recovered by design |
| Argon2id 64 MiB on small devices | Fallback 32 MiB/t=4 recorded in the header; timing measured on the reference device in Phase 8 |
| Delete-all removes data by mistake | Double confirmation plus PIN |

## 6. Acceptance criteria (AC-BAK)
1. Backup then restore into a fresh database reproduces every row of every table and the same balances; IC-1..8 pass.
2. A wrong passphrase, any flipped byte, a truncated file, reordered or duplicated chunks and appended data are all rejected, and
   existing data is untouched.
3. Hostile headers (unknown version, KDF parameters beyond bounds) are rejected before key derivation.
4. A failure during restore (fault injection) leaves the previous data intact.
5. Passphrases under 10 characters or in the common list are rejected.
6. Delete-all removes the database, its key, the PIN and local copies, and the app starts fresh.
7. Every insight is reproducible from the ledger and labeled as an estimate where it is one (tested).
8. `gradlew check assembleDebug` passes; no INTERNET permission.

Device-only (Phase 8): Argon2id time and memory on the reference device, SAF behaviour with cloud-drive providers.

## 7. Deliverables
Code and tests for the steps above, updated docs (backup format notes, security, PROGRESS.md, third-party notices).

## 8. Implementation notes (7.5)
- **Screens:** More → Insights, Backup & restore, Privacy & data; Home shows a backup reminder when transactions exist and there
  is no backup or the last one is older than 30 days (R-10).
- **Backup:** the passphrase is checked before the system "save as" picker opens; the user must tick "a forgotten passphrase
  cannot be recovered". A failed write deletes the half-written document. Restore: pick file → passphrase → full verification
  (nothing changes) → summary (date made, accounts, transactions, date range) → "Replace all current data". A database copy is
  taken just before the replace (`AppContainer.safetyCopy`: WAL checkpoint, then the encrypted file into `premigration/`, last 2
  kept).
- **Delete all (UC-31):** acknowledgement checkbox plus the app PIN (`AppLockController.confirmPin`, which shares the unlock
  lockout counter). The wipe closes the database and deletes the database files (incl. `-wal`/`-shm`), everything in
  `noBackupFilesDir` (database key file, PIN files, local copies), `filesDir` and `cacheDir`, deletes both Keystore keys
  (`fw_db_wrap_v1`, `fw_pin_v1`) and the reminder alarm, then restarts the process so the app begins at PIN setup.
- **Known limitation:** Compose text fields hold the passphrase and PIN as `String`, which cannot be wiped from memory; the
  data layer wipes its own `CharArray` copies. The window is limited to while the screen is open.
