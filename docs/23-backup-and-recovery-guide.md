# Backup and Recovery Guide

| Status | v1, 2026-11-06. Every procedure below was executed on an emulator (Android 13) with 1.0.0-rc1 / debug builds |
|---|---|

Format details: [14-backup-format.md](14-backup-format.md). Records exist only on the phone (ADR-0012), so a backup file
is the **only** way to recover from a lost, broken or reset phone.

## 1. For users
### Make a backup
More → Backup & restore → passphrase (at least 10 characters, not a common password; "password123" and similar are
refused) twice → tick "I understand…" → Choose where to save. Save it somewhere that is not this phone: a computer, a
memory card, or a cloud drive. Home shows a reminder when there is no backup or the last one is older than 30 days.

### Restore (same phone or new phone)
Install the app → set a PIN → More → Backup & restore → Choose a backup file → passphrase → Check the file → read the
summary (date, accounts, transactions) → Replace all current data. The file is checked completely before anything is
changed; a wrong passphrase or damaged file changes nothing. Records added after the backup was made are not in it.

### Forgotten PIN
There is no PIN reset (by design). Uninstall the app, reinstall, set a new PIN and restore your latest backup.

### "Your data could not be opened"
- **Try again** first.
- If the phone can no longer unlock the database key (possible after some security updates or lock-screen changes), the
  screen also offers **Start over and restore a backup**. The unreadable data is moved aside on the phone (never
  deleted) in case it can be recovered later; the app restarts empty; then restore your latest backup.

## 2. Verified procedures (evidence: phase 8 QA report)
| Procedure | Result |
|---|---|
| Backup → add a transaction → restore with wrong passphrase | Refused, nothing changed |
| Restore with right passphrase | Transaction added after the backup removed; balances as at backup |
| Uninstall → reinstall → new PIN → restore | All accounts, transactions, goals and assets back |
| Backup made by a debug build restored by the release build | Identical balances |
| Delete all data with wrong PIN, then right PIN | Wrong PIN deletes nothing; right PIN removes database, keys, PIN; backup file in Downloads untouched |
| Backup file content | Starts with `FWBK` header; no account names, categories or descriptions readable |

## 3. For maintainers
- Before a restore, the app copies the current encrypted database to app-private storage (last 2 kept).
- Before a schema migration, the same copy is made; a failed post-migration integrity check restores it.
- Unreadable data moved aside by "Start over" is in `no_backup/unreadable/<timestamp>/` (database files and the wrapped
  key, together). It can only be opened if the Android Keystore key that wrapped it still exists.
- Crash reports: `no_backup/crash/`, at most 5, exported by the user. Decode class names with the release's
  `mapping.txt` (deployment guide §5).
