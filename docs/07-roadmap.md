# Development Roadmap

| Status | Proposed, revision 2 (2026-11-05): Android only, manual entry + file import only. Each phase starts after owner approval of its plan |
|---|---|

No calendar dates are committed. Durations are re-estimated at each gate from actual progress.

| Phase | Name | Key deliverables | Depends on | Exit gate |
|---|---|---|---|---|
| **1** | Discovery & validation | PRD v0.2, open questions, feasibility, risks, tech evaluation rev. 2, architecture rev. 2, roadmap, ADRs, traceability | — | **Owner approves stack + architecture** |
| **2** | Architecture | ERD, Room schema/DDL, domain model spec, use-case catalogue, import pipeline spec (profiles, fingerprints, duplicate rules), backup file format spec, sync protocol spec, security architecture, final ADRs, coding standards | Phase 1 approval | Owner approves architecture; ledger + import design review |
| **3** | Foundation | Gradle multi-module build + version catalog; detekt (money rule), ktlint, Lint; JUnit 5 + Kotest; CI; Compose app shell, Material 3 theme tokens, light/dark, navigation; Room + SQLCipher + Keystore-wrapped key; migrations + pre-migration backup; app lock (PIN/BiometricPrompt, `FLAG_SECURE`); logging + crash-safe error handling; **spikes:** SQLCipher perf, fastexcel/PdfBox-Android size and fit | JDK + Android SDK (Q-20); physical device (Q-16) | AC-SEC; app runs on a real device; CI green; spike results documented |
| **4** | Core financial engine | `:domain` (Money, ledger, invariants, periods, allocations, savings), then repositories + use cases, then UI: accounts, **fast manual entry (TXN-13)**, transactions, transfers, categories, budgets, savings, reconciliation | Phase 3 | **AC-CORE** (all 9); domain coverage ≥ 95 %; Maestro journeys 1–9; entry-flow usability check |
| **5** | Advanced finance | Rollover; debts + payment split; assets; bills/subscriptions + local reminders (WorkManager/AlarmManager); net worth + history; reports + CSV export; dashboard | Phase 4 | Report figures cross-checked against independent recompute; journey 10 |
| **6** | File import | Generic CSV + XLSX import with mapping, saved profiles, duplicate detection (incl. vs manual entries), preview/confirm, batch rollback, post-import reconciliation; institution profiles from real samples; PDF parsers for verified layouts (incl. password-protected) | Sample statements (Q-11) for institution profiles | **AC-IMP** (all 5); every institution profile has passing fixture tests |
| **7** | Backup, sync, AI (optional) | Local encrypted backup/restore via SAF (**pulled forward to MVP, see below**); Ktor backend: auth, devices, encrypted cloud backup, sync relay; sync engine + conflict UI; optional AI | Q-01, Q-10; hosting decision | **AC-SYNC**; restore verified on a second device; AI-off path fully functional |
| **8** | QA | Functional, integrity, security (OWASP MASVS for Android), performance on reference devices (NFR-02..08), offline, migration, sync chaos tests | Phases 4–7 | Zero open critical/high defects; NFRs measured |
| **9** | Production prep | Signed release AAB, R8/Baseline Profiles, Play Console listing, data-safety form, privacy notice, legal review, on-device crash log export (no network, ADR-0010), Android vitals review, migration strategy, deployment & recovery guides | Q-03, counsel, Play account | Definition of Done (CLAUDE.md §24) |

## MVP cut (first public release, Android)
All **P0** requirements: offline accounts / fast manual entry / transactions / transfers / categories, custom-period multiple
budgets, virtual savings, core reports, **CSV + XLSX import**, encrypted DB, app lock, **local encrypted backup/restore (BAK-01)**,
data export. Not in MVP: cloud backup, sync, AI, PDF import (P1).

Because there is no cloud copy by default, local backup (BAK-01) is built in Phase 5–6 rather than waiting for Phase 7.

## Critical path
`Phase 2 ledger design → :domain + invariant tests → Room data layer + use cases → entry UI → import pipeline`.
`:domain` needs only a JDK, so it can start as soon as Q-20 is approved, in parallel with Android SDK setup.
