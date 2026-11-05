# ADR-0011: Logical, chunked AES-256-GCM backup with Argon2id

- **Status:** **Accepted** (2026-11-05). Approved by owner with Phase 2
- **Requirements:** BAK-01, LOC-04, R-10, R-11

## Context
Backups must survive device loss (so they cannot depend on the Keystore), must never expose plaintext on disk, and must be restorable by future app versions.

## Decision
Use the `.fwbak` format in docs/14-backup-format.md: a logical JSONL payload (gzip), streamed through AES-256-GCM in 1 MiB
chunks with index- and final-flag-bound AAD. The key is derived from a user passphrase with **Argon2id (64 MiB, t = 3, p = 1)**, using
BouncyCastle's `Argon2BytesGenerator` (`bcprov-jdk18on`, 1.86 at time of writing). If the Phase 3 size spike shows unacceptable APK growth
after R8, use **PBKDF2-HMAC-SHA256 at 600,000 iterations** (platform crypto) instead. The header records the KDF id so either format stays readable.
Restore is replace-only, after full verification.

## Alternatives considered
Raw SQLCipher DB copy with a passphrase (`sqlcipher_export`): simpler, but tied to the schema version and the SQLCipher format, and it gives no logical integrity check.
Zip with a password: weak or poorly specified crypto.

## Consequences
- **Pro:** Portable, versionable, verifiable.
- **Con:** Restore is slower than a file copy (acceptable for ≤ 100k transactions; to be measured).
- **Con:** A forgotten passphrase is unrecoverable by design.
