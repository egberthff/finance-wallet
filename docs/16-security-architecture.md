# Security Architecture & Threat Model

| Status | Phase 2 draft v1 (2026-11-05) |
|---|---|
| Baseline | OWASP MASVS v2 categories (STORAGE, CRYPTO, AUTH, NETWORK, PLATFORM, CODE, RESILIENCE, PRIVACY) |
| Decisions | ADR-0002, ADR-0010 (no network in MVP), ADR-0011 (backup crypto) |

## 1. Assets
Financial records (transactions, balances, notes, attachments), imported statement contents (transient), app PIN, backup passphrase, DB key.

## 2. Threats and controls

| # | Threat | Control | MASVS |
|---|---|---|---|
| T1 | Lost/stolen **locked** phone | Android FBE + SQLCipher DB with Keystore-wrapped key; no plaintext caches; attachments AES-GCM-encrypted | STORAGE |
| T2 | Lost/stolen **unlocked** phone, or a family member picks it up | App lock (PIN/biometric), auto-lock after 1 min in background (configurable 0–15 min), `FLAG_SECURE` hides recents | AUTH, PLATFORM |
| T3 | Other apps reading data | App-private storage only; no exported components except the launcher and the import intent filter; no ContentProvider; SAF for files | PLATFORM |
| T4 | Uncontrolled cloud copy via Android backup | `android:allowBackup="false"`, `dataExtractionRules` exclude all domains (API 31+), `fullBackupContent` excludes all (API ≤ 30) | STORAGE |
| T5 | Data exfiltration over network (bug, malicious dependency) | **MVP declares no `INTERNET` permission** (ADR-0010). Verified in CI by a manifest check on the merged release manifest | NETWORK, PRIVACY |
| T6 | Malicious import file (zip bomb, XXE, huge PDF, parser exploit) | Limits and guards in 13-import-pipeline.md §2; parsing in a cancellable background coroutine; XML entities disabled | CODE |
| T7 | CSV/formula injection in exports opened in spreadsheets | Prefix cells beginning with `= + − @ \t \r` with `'` | CODE |
| T8 | Backup file theft | AES-256-GCM, Argon2id-derived key, tamper-evident chunks (14-backup-format.md) | CRYPTO |
| T9 | Brute-forcing the app PIN | PIN hashed with PBKDF2-HMAC-SHA256 (210,000 iterations, 16 B salt), and the hash is **additionally encrypted with a non-exportable Keystore key** (`fw_pin_v1`). A 6-digit PIN has only 10⁶ values, so a copied hash file alone must not allow offline guessing. Comparison in constant time. Exponential backoff after 5 failures (30 s, doubling to 1 h max), with the counter persisted. No auto-wipe by default (data loss risk); optional user setting | AUTH |
| T10 | Sensitive data in logs/crash reports | Release builds use a no-op log tree; a lint rule forbids logging `Money`/`Txn` objects; crash logs stay on-device, are scrubbed, and are shared only by explicit user action | PRIVACY, CODE |
| T11 | Screenshot / screen recording / screen-reader leakage | `FLAG_SECURE` on by default (user can disable); amounts in notifications hidden on the lock screen (`VISIBILITY_PRIVATE`) | PLATFORM |
| T12 | Supply chain | Minimal dependencies; Gradle dependency verification (checksums + signatures); Dependabot; new dependencies need review against this table | CODE |
| T13 | Rooted / compromised device | **Out of protection scope.** Best-effort only: the app warns that security cannot be guaranteed and does not block use. No anti-tamper claims | RESILIENCE |
| T14 | Tapjacking on confirmation dialogs | `filterTouchesWhenObscured` on destructive and confirm actions | PLATFORM |

## 3. Key management
```
Android Keystore (TEE/StrongBox if available)
  └─ K_wrap: AES-256-GCM, non-exportable, alias "fw_db_wrap_v1"
        └─ wraps K_db (32 random bytes) → stored as ciphertext in app-private file `db.key.enc`
              └─ SQLCipher PRAGMA key (raw key form, not passphrase-derived)
  └─ K_att: AES-256-GCM, alias "fw_att_v1" (attachments)
  └─ K_dev: AES-256-GCM, alias "fw_dev_backup_v1" (automatic local backups)
```
- Keys are **not** bound to user authentication (`setUserAuthenticationRequired(false)`). The app lock is an app-level gate.
  Reason: bill reminders and auto-post must work in the background, and auth-bound keys are invalidated when biometrics change,
  which would make data unreadable (R-11). This trade-off is accepted and documented. An "auth-bound key" mode may be offered later as an advanced option.
- Key invalidation or Keystore loss means the DB cannot be opened. The app explains this and offers restore from a user backup. It never silently creates an empty DB over the old one; the old file is preserved.

## 4. Authentication (SEC-01)
- 6-digit PIN required to enable the lock. Biometric unlock (BiometricPrompt, `BIOMETRIC_STRONG`) is optional, with the PIN as fallback.
- Lock triggers: cold start, background longer than the timeout, manual lock.
- PIN change requires the current PIN. A forgotten PIN offers **reset by wiping** or **restore from backup**. There is no backdoor.

## 5. Network (ADR-0010)
MVP: no `INTERNET` permission at all, so no third-party SDK can transmit data. Phase 7 (if the owner approves the cloud features)
adds it, together with Network Security Config (TLS only, no user CAs in release), certificate pinning to the app's own backend
with a backup pin, and E2E encryption of payloads.

## 6. Privacy (PRIV-01..03, RA 10173 considerations)
- Data minimization: no account, name, email, phone, or location collected in MVP. Account numbers are stored only as optional last 4 digits.
- Data stays on the device unless the user exports or backs it up.
- Export (UC-30) and full deletion (UC-31) are user-controlled.
- Google Play **Data safety** draft (to confirm in Phase 9): "No data collected, no data shared". This is only valid while ADR-0010 holds.
- **Legal review still required** before public release (R-07). This document makes no compliance claim.

## 7. Verification plan (Phase 8)
MASVS-mapped checklist; manual review of the merged manifest; `adb backup`/device-transfer test showing no data extracted;
DB file opened with stock sqlite3 shows "file is not a database" (AC-SEC-1); recents and screenshot test (AC-SEC-2);
fuzzing of CSV/XLSX parsers with malformed inputs; secret scan in CI (AC-SEC-3).
