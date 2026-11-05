# Open Questions & Ambiguous Requirements

| Status | Phase 1, revision 2 (2026-11-05, after the Android-only / no-integration scope change) |
|---|---|

Each open question has a **proposed default**. If the owner does not answer, work proceeds on that default and the default
is recorded as an assumption. **Blocking** questions stop the named phase until they are answered.

## Open

| ID | Question | Why it matters | Proposed default | Blocking? |
|---|---|---|---|---|
| Q-01 | Is a cloud account wanted at all, or is local-only the whole product? | Determines whether Phase 7 backend exists | Local-only first; optional cloud backup/sync in Phase 7 | No |
| Q-02 | Business model (free, freemium, subscription)? | Backend cost, store setup | Free local app | Phase 9 |
| Q-03 | Who is the legal entity / Personal Information Controller for the app? | Privacy notice, NPC registration assessment, Play Console developer identity | Unknown | **Phase 9** |
| Q-05 | Multi-currency: which currencies at launch; FX rate source? | Conversion correctness | PHP in UI; per-account currency stored; FX = user-entered rate | No |
| Q-06 | Credit card spending counted at purchase or at payment? | Avoids double counting | **At purchase**; card payment = transfer | No |
| Q-07 | Can one transaction count in several overlapping budgets? | Double-counting risk | Yes per budget; never summed across budgets | No |
| Q-08 | Default rollover for new categories? | Budget math | Expire unless enabled | No |
| Q-09 | Carry overspending into the next period? | Budget math | Not carried unless enabled | No |
| Q-10 | AI: which provider, and is sending aggregated data externally acceptable? | Privacy, cross-border transfer | Deferred to Phase 7; opt-in only | Phase 7 |
| Q-11 | Which institutions' statement files are priority, and can the owner supply **real, redacted samples** (CSV/XLSX/PDF)? | Parsers cannot be built or verified without real files. This is now the only automated data path. | GCash, Maya, BPI, BDO, UnionBank first | **Phase 6 (institution profiles)**. Generic CSV/XLSX mapping works without samples |
| Q-12 | Minimum Android version? | Library choices, test matrix | Android 8.0 (API 26); `java.time` native | No |
| Q-13 | Filipino/Tagalog localization planned? | String externalization | All strings in resources from day one; English at launch | No |
| Q-16 | Physical Android test device(s) available? Which model/RAM? | Emulator is heavy on this machine (8 GB RAM, 26 GB free disk). NFR targets need a reference device. | Owner provides one mid-range device (≈4 GB RAM) for USB debugging | **Phase 3** |
| Q-17 | Retention for soft-deleted records and audit logs? | Storage, privacy | Audit kept for the record's lifetime; tombstones purged 90 days after sync convergence | No |
| Q-18 | "Delete my data" also deletes cloud backups immediately? | PRIV-01 | Yes | No |
| Q-19 | Default branch name (`master` locally, `main` named as main)? | Workflow | Use `main` at first commit | No |
| Q-20 | May the development machine get a JDK 17+ and the Android SDK installed (~10–15 GB; 26 GB free)? | Native Android cannot be built or tested otherwise | Install JDK first (domain tests); Android SDK command-line tools + one platform, no emulator image | **Phase 3** |
| Q-21 | Keep imported statement files as attachments (encrypted) or discard after import? | Storage, privacy | Discard by default; optional "keep file with this import" | No |

## Closed

| ID | Question | Resolution (2026-11-05) |
|---|---|---|
| Q-04 | Pursue aggregator/bank partnership? | **No.** Direct integrations dropped (ADR-0006) |
| Q-14 | Budget for iOS builds / Apple account? | **N/A.** iOS dropped (ADR-0009) |
| Q-15 | How to build/test iOS from Windows? | **N/A.** iOS dropped (ADR-0009) |
