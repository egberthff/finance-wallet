# Known Limitations (1.0.0-rc1)

| Status | v1, 2026-11-06 |
|---|---|

## Scope (by owner decision)
- Android only (ADR-0009). No iOS app.
- No bank, e-wallet or aggregator connections (ADR-0006). Data enters by manual entry or CSV/XLSX import.
- No cloud backup, multi-device sync or external AI (ADR-0012). Insights are computed on the phone.

## Product
- **PDF statements are not imported.** GCash and BPI statements are password-protected PDFs; layouts can only be built and
  verified with real, redacted samples (Q-11). Use CSV/XLSX exports or manual entry.
- No bank-specific import profiles yet; the generic CSV/XLSX importer with saved column mappings is the supported path.
- Import from the share sheet (opening a file from another app) is not available; use More → Import a statement.
- Editing from the UI covers expense, income and transfer; other transaction types (splits, debt payments) are edited
  through their own screens or by deleting and re-entering.
- Sweeping a closed budget's leftover straight into a goal from the leftover list is not in the UI.
- Reports' "Money in" counts opening balances (it equals the change in account balances); this can surprise users in
  the first month.
- Single currency (PHP) on Home and in reports; accounts in other currencies are not converted.
- No biometric opt-out setting (biometric unlock is offered whenever the phone supports it); no FLAG_SECURE toggle
  (screenshots are always blocked in release builds).

## Security
- The PIN lockout uses the phone's clock. Someone with the unlocked phone who changes the clock can shorten the waits
  between PIN guesses. Each guess still costs a deliberate PBKDF2 computation, and the database stays encrypted.
- The database key is not bound to the PIN (the app lock is a UI gate over a Keystore-protected key), so that bill
  reminders can run in the background (security architecture §3). A rooted phone weakens these protections.

## Verification still open
- **Physical phone testing:** all device checks so far ran on emulators (Android 13 and Android 11); USB debugging was
  not available. Vendor-specific behaviour (battery savers delaying reminders, OEM keystores) is unverified.
- **Android 8–10:** the oldest supported versions (minSdk 26) were not run.
- **Performance with 50,000+ transactions** (NFR-02/03) is not measured on a device.
- **Legal review** of the privacy notice and Data Privacy Act obligations is pending (R-07).
- Google Drive / other cloud providers through the file picker: not tested.
