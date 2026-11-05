# Risk Register

| Status | Living document, revision 2 (2026-11-05, after scope change) |
|---|---|

Likelihood (L) and Impact (I): 1 (low) – 5 (high). Score = L × I. **≥ 15 = critical.**

## Open risks

| ID | Risk | L | I | Score | Mitigation | Owner / trigger |
|---|---|---|---|---|---|---|
| R-19 | **Data-entry burden.** With no automatic feeds, users abandon the app if manual entry or import is slow or error-prone. | 4 | 4 | **16** | TXN-13 fast entry (≤ 3 taps, templates, autocomplete, quick-add shortcut); saved import profiles; usability tests on entry flow in Phase 4; reconciliation reminders instead of "sync" | Phase 4/6 |
| R-03 | Incorrectly parsed statement data imported | 4 | 4 | **16** | Mandatory preview + confirm; per-row validation; fixture tests from real redacted statements; batch rollback; PDF only for verified layouts; detection fails closed | Phase 6 |
| R-02 | Double counting (transfers, savings, refunds, card payments, overlapping budgets, **imported rows that match manual entries**) | 3 | 5 | **15** | Double-entry + allocation ledgers; property tests; fuzzy duplicate matching against manual entries with user resolution | Phase 4/6 gate |
| R-04 | Multi-device sync corrupts balances/history | 3 | 5 | **15** | No balance sync; HLC op log; financial conflicts surfaced; interruption tests | Phase 7 gate |
| R-07 | Regulatory non-compliance (RA 10173 / NPC; Play data-safety declarations; cross-border transfer if cloud/AI used). Lower than rev. 1 because no bank data processors are involved. | 2 | 5 | 10 | Local-first, no network by default; data minimization; compliance assessment; **legal review before public launch**; no compliance claims without it | Owner + counsel, before Phase 9 |
| R-16 | Statement formats change without notice | 4 | 3 | 12 | Versioned parser profiles; generic manual mapping fallback; user can report a failed format | Phase 6+ |
| R-20 | Statement availability: some institutions may only provide PDF, password-protected PDF, or limited history windows (GCash in-app history covers about 90 days per its help center). | 4 | 3 | 12 | Support CSV/XLSX/PDF; password prompt in memory; guide users to export regularly; never OCR scanned images | Phase 6 |
| R-05 | Rounding errors | 2 | 5 | 10 | Long minor units with exact arithmetic; basis points; largest remainder; detekt rule | Phase 4 |
| R-10 | Data loss (crash, failed migration, uninstall, lost phone) | 2 | 5 | 10 | Single-transaction writes; WAL; pre-migration backup; MigrationTestHelper; prominent local backup reminders | Phase 3+ |
| R-11 | Encryption key loss (Keystore invalidated on device reset/new phone) makes the DB unreadable | 2 | 5 | 10 | Encrypted backup with a separate passphrase; documented recovery; never claim recovery without backup | Phase 3/7 |
| R-09 | Dev machine constraints: 26 GB free disk, 8 GB RAM, no JDK/Android SDK | 4 | 3 | 12 | JDK first (domain work needs no SDK); SDK command-line tools only, no emulator; physical device; free disk space; Gradle cache limits | Owner (Q-20), Phase 3 |
| R-13 | Date/timezone bugs in budget periods | 3 | 4 | 12 | `java.time` with explicit zone; exhaustive period tests over 10+ years | Phase 4 |
| R-14 | Performance on low-end Android devices | 2 | 3 | 6 | Native Kotlin; Paging 3; SQL aggregates; Baseline Profiles; profiling gate | Phase 3 spike, Phase 8 |
| R-21 | Import library fitness on Android (fastexcel XML stack, PdfBox-Android size) unproven | 3 | 3 | 9 | Phase 3/6 spike measuring APK delta and parse accuracy; fall back to CSV-only plus guidance for a format that fails | Phase 6 entry |
| R-15 | AI sends sensitive data externally or gives wrong numbers | 3 | 4 | 12 | Off by default; consent; aggregates only; deterministic numbers; read-only AI | Phase 7 |
| R-17 | Dependency/supply-chain vulnerabilities | 3 | 4 | 12 | Minimal deps; Gradle dependency verification; Dependabot; review | Ongoing |
| R-18 | Scope creep | 3 | 3 | 9 | P0/P1/P2; milestone gates; PROGRESS.md | Ongoing |
| R-22 | User expectation mismatch: users expect "connect my GCash" | 3 | 3 | 9 | Honest onboarding and store copy (INT-06); import guides per institution | Phase 9 |

## Closed / retired (2026-11-05)

| ID | Risk | Reason |
|---|---|---|
| R-01 | No authorized access to PH institution APIs | Retired. Direct integrations dropped by owner (ADR-0006); limitation accepted as product scope |
| R-06 | Aggregator relies on credential sharing/RPA | Retired. No aggregators used |
| R-08 | iOS cannot be built on Windows | Retired. iOS dropped (ADR-0009) |
| R-12 | Background provider-sync unreliability | Retired. No provider sync; device sync (Phase 7) is on-open/manual |
