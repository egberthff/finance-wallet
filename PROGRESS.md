# Project Progress & Feature Implementation Matrix

Single source of truth for implementation status (CLAUDE.md §20, §23).
Statuses: **Planned · In progress · Implemented · Tested · Verified · Blocked · Unavailable · Out of scope**.
Test statuses: **Not written · Written · Executed–Passed · Executed–Failed · Not executed**.

_Last updated: 2026-11-05_

## Scope (owner decisions 2026-11-05)
- **Android only.** iOS dropped ([ADR-0009](docs/adr/0009-scope-android-only.md)).
- **No direct API integrations.** Data enters by manual entry and file import only ([ADR-0006](docs/adr/0006-integration-strategy.md)).

## Phase status

| Phase | Status | Notes |
|---|---|---|
| 1. Discovery & validation | **Approved by owner (2026-11-05)** | Native Kotlin stack accepted; open-question defaults adopted |
| 2. Architecture | **Approved by owner (2026-11-05)** | [milestone plan](docs/milestones/phase-2-architecture.md) |
| 3. Foundation | **Complete except device-only checks** (criterion 6, SQLCipher perf); deferred by owner, follow-up next session | [milestone plan](docs/milestones/phase-3-foundation.md) |
| 4. Core financial engine | **In progress** (started 2026-11-05, owner: "proceed all") | [milestone plan](docs/milestones/phase-4-core-engine.md) |
| 5–9 | Planned | — |

## Phase 1 deliverables

| Deliverable | File | Status |
|---|---|---|
| Product requirements | [docs/01-product-requirements.md](docs/01-product-requirements.md) | Draft v0.2 |
| Open questions | [docs/02-open-questions.md](docs/02-open-questions.md) | Rev. 2, awaiting answers |
| Technical feasibility | [docs/03-technical-feasibility.md](docs/03-technical-feasibility.md) | Rev. 2 |
| Risk register | [docs/04-risk-register.md](docs/04-risk-register.md) | Rev. 2 |
| Technology evaluation | [docs/05-technology-evaluation.md](docs/05-technology-evaluation.md) | Rev. 2, proposed |
| Architecture overview | [docs/06-architecture-overview.md](docs/06-architecture-overview.md) | Rev. 2, proposed |
| Roadmap | [docs/07-roadmap.md](docs/07-roadmap.md) | Rev. 2, proposed |
| ADRs 0001–0009 | [docs/adr/](docs/adr/README.md) | All Accepted |
| Import support register | [docs/integrations/README.md](docs/integrations/README.md) | Rev. 2 |

## Phase 2 deliverables

| Deliverable | File | Status |
|---|---|---|
| Domain model & financial rules (EX-1..17) | [docs/10-domain-model.md](docs/10-domain-model.md) | Draft v1 |
| Database schema / ERD / migration policy | [docs/11-database-schema.md](docs/11-database-schema.md) | Draft v1; DDL parsed in SQLite 3.45.3 (29 tables, all FK targets exist, constraint smoke test passed) |
| Use-case catalogue + P0 traceability | [docs/12-use-cases.md](docs/12-use-cases.md) | Draft v1 |
| Import pipeline | [docs/13-import-pipeline.md](docs/13-import-pipeline.md) | Draft v1 |
| Backup format | [docs/14-backup-format.md](docs/14-backup-format.md) | Draft v1 |
| Sync protocol | [docs/15-sync-protocol.md](docs/15-sync-protocol.md) | Draft (Phase 7) |
| Security architecture | [docs/16-security-architecture.md](docs/16-security-architecture.md) | Draft v1 |
| Backend API | [docs/17-api-spec.md](docs/17-api-spec.md) | Draft (Phase 7) |
| Coding standards | [docs/18-coding-standards.md](docs/18-coding-standards.md) | v1 |
| ADR-0010, ADR-0011 | [docs/adr/](docs/adr/README.md) | Accepted |

Design-validation evidence (throwaway scripts, not product tests): the period and split algorithms were checked in Python against
EX-1..4, EX-11, EX-12, EX-16, and gave 0 contiguity/containment violations across 31 anchor days × 10 years. Phase 4 Kotlin tests
are still **Not written**.

## Phase 3 progress (2026-11-05): evidence from `gradlew check assembleDebug` on the dev machine

| # | Acceptance criterion ([plan](docs/milestones/phase-3-foundation.md)) | Status | Evidence |
|---|---|---|---|
| 1 | `gradlew build/check` passes: compile, unit tests, static analysis, Lint | **Met** | `check assembleDebug` BUILD SUCCESSFUL incl. detekt 2.0.0-alpha.6 + ktlint 1.8.0 on all modules (no baselines; deviations documented in config/detekt/detekt.yml); warnings-as-errors; Lint 0 errors, 2 accepted warnings (OldTargetApi, MissingApplicationIcon) |
| 2 | `:domain` tests incl. EX-1..4, 11..14, 16 + properties; coverage ≥ 95 % | **Met (corrected 2026-11-05)** | See correction below. Now all property tests execute; `:domain` line coverage 98.2 % (koverVerify passed) |
| 3 | Money-safety rule fails a bad fixture, passes clean code | **Met** | `moneySafetySelfTest`: 7 violations detected; `moneySafetyCleanFixture` and `moneySafety` (domain/data/importers) passed |
| 4 | Room schema v1 exported; IC checks run in Robolectric | **Met** | `data/schemas/.../1.json` exported; 8 Robolectric DB tests passed; `SchemaConformanceTest` checks exported columns == reference DDL (verified to fail on a deliberate doc mismatch; doc is a declared test input) |
| 5 | Debug APK builds; merged manifest has no INTERNET | **Met** | `app-debug.apk` built (21.5 MB, debug/unminified); `checkDebugNoNetworkPermission` and `checkReleaseNoNetworkPermission` passed |
| 6 | On device: launch, lock, FLAG_SECURE, DB unreadable without key (AC-SEC-1/2) | **Not executed** | No Android device connected yet (Q-16) |
| 7 | Spikes recorded (SQLCipher perf; APK impact of fastexcel/PdfBox/BouncyCastle) | **Partially met** | [docs/spikes/apk-size.md](docs/spikes/apk-size.md): baseline release 9.36 MB; BouncyCastle +31 KB; PdfBox +6.0 MB; fastexcel fails R8 → in-house XLSX reader. **SQLCipher perf needs device** |

Test totals (latest full `check`): **92 executed, 92 passed, 0 failed, 0 skipped** (`:domain` 67, `:data` 12, `:app` 13).

> **Correction (2026-11-05).** Earlier reports in this file (59 and 60 tests, "properties passed") were wrong for 4 property
> tests: `AllocationTest` ×2, `MoneyParserFormatterTest` ×1, and `PeriodCalculatorTest` (fixed-length). They were written as
> `fun t() = runBlocking { checkAll(...) }`, which returns a value, and **JUnit 5 silently skips value-returning `@Test`
> methods**. They had never executed. The period-contiguity property tests (plain loops) did execute. Fix: `runBlocking<Unit>`.
> After the fix all of them run and pass. Prevention: a new `testDiscovery` build check fails on this pattern, with a self-test fixture.
Not covered by automated tests yet: Compose UI (LockScreen, navigation), `AndroidKeystoreKeyWrapper` and SQLCipher encryption (device-only), and BiometricPrompt.

Known issues:
- A Gradle deprecation warning (`Configuration.setVisible`) originates in a third-party plugin, not in project build scripts. Harmless until Gradle 11.
- Robolectric needs `--add-exports java.base/jdk.internal.access` on JDK 21 (test JVM only).
- The CI workflow is written but has never run (no remote).

## Feature matrix

Phase 3 code exists (see above). Feature rows below reflect it.

| Req group | Requirements | Phase | Impl. status | Files | Tests | Issues / limitations |
|---|---|---|---|---|---|---|
| Ledger engine (domain) | TXN-01..05, 08, 12; RPT-03; NW-01; ACC-05 (calc) | 4.1–4.4 | **Implemented + tested (domain only)**: account classes, 11 transaction-type templates with validation codes, invariants I-1..I-3, calculator (balances current/pending/projected, income/spending/adjustment/opening/FX classification, spending by category, net worth) | domain/src/main/.../ledger, common | Executed–Passed: EntryTemplatesTest 15, LedgerCalculatorTest 8, DomainResultTest 4, LedgerPropertyTest (10,000 sequences / 58,387 ops) | Not yet persisted (data layer = step 4.5); allocation ledger = step 4.3 |
| Accounts | ACC-01..07 | 4 | Planned | — | Not written | — |
| Transactions + fast entry | TXN-01..13 | 4 | Planned | — | Not written | TXN-13 critical for adoption (R-19) |
| Categories | CAT-01..04 | 4 | Planned | — | Not written | — |
| Budgets | BUD-01..06, 08 | 4 | Planned | — | Not written | — |
| Budget rollover | BUD-07 | 5 | Planned | — | Not written | — |
| Savings | SAV-01..04 | 4 | Planned | — | Not written | — |
| Income & recurring | INC-01..03 | 4–5 | Planned | — | Not written | — |
| Debts / assets / net worth | DEBT-01..03, AST-01, NW-01..02 | 5 | Planned | — | Not written | — |
| Bills & subscriptions | BILL-01..03 | 5 | Planned | — | Not written | Reminders best-effort (Doze/OEM) |
| Reports | RPT-01..04 | 5 | Planned | — | Not written | — |
| CSV / XLSX import | IMP-01, 02, 04..07, 09, 10 | 6 | Planned | — | Not written | Institution profiles need real samples (Q-11) |
| PDF import | IMP-03, IMP-08 | 6 | Planned | — | Not written | Verified layouts only; PdfBox-Android fit unproven (R-21) |
| Direct integrations | INT-01, 03, 04, 05 | — | **Out of scope** | — | — | ADR-0006 |
| Integration constraints | INT-02, INT-06 | all | In force (policy) | — | n/a | — |
| Local-first & encrypted DB | LOC-01..04 | 3 | **In progress**: schema v1, triggers, integrity checks, SQLCipher factory, Keystore-wrapped key implemented | data/src/main/... | Executed–Passed (11, JVM/Robolectric); encryption on device **Not executed** | Pre-migration backup not yet implemented (needs the first migration and the backup format) |
| Local backup | BAK-01 | 5–6 (MVP) | Planned | — | Not written | — |
| Cloud backup / sync | BAK-02, SYNC-01..02 | 7 | Planned | — | Not written | Optional; R-04 |
| Security | SEC-01..05 | 3+ | **In progress**: app lock (PIN PBKDF2 210k + Keystore-wrapped hash, backoff, biometric, auto-lock 60 s), FLAG_SECURE, no-network manifest check, backup exclusion | app/src/main/.../security, AndroidManifest.xml | Executed–Passed (13 JVM); on-device **Not executed** | Biometric opt-out setting pending |
| Privacy | PRIV-01..03 | 3, 9 | Planned | — | Not written | Legal review required (R-07) |
| AI assistant | AI-01..04 | 7 | Planned | — | Not written | Q-10 |
| UX / design system | UX-01..06 | 3–5 | **In progress**: theme tokens (light/dark), state components, bottom navigation shell; money formatter (UX-06) in domain | app/src/main/.../ui | Formatter tests passed; UI tests Not written | Icons and app icon pending design; sections show honest "not available yet" states |
| iOS | — | — | **Out of scope** | — | — | ADR-0009 |

## Decision log

| Date | Decision | Ref |
|---|---|---|
| 2026-11-05 | Rev. 1 proposed React Native/TS stack (never accepted) | superseded in place |
| 2026-11-05 | **Owner:** Android only; iOS dropped | ADR-0009 (Accepted) |
| 2026-11-05 | **Owner:** no direct API integrations; manual entry + file import only | ADR-0006 (Accepted) |
| 2026-11-05 | Stack: native Kotlin + Compose, Room + SQLCipher, Long minor units, double-entry + allocation ledgers, optional Ktor backend, Gradle/JUnit5/Kotest/Maestro | ADR-0001..0005, 0007, 0008 (**Accepted** by owner) |
| 2026-11-05 | Open-question defaults adopted as assumptions (Q-05..Q-09, Q-12, Q-13, Q-17..Q-19, Q-21) | 02-open-questions.md |
| 2026-11-05 | Added SEMI_MONTHLY budget period (BUD-01 amendment, PH paydays) | 10-domain-model.md §5.1 |
| 2026-11-05 | Accepted: no INTERNET permission in MVP; Argon2id chunked-GCM backup | ADR-0010, ADR-0011 (**Accepted** by owner) |

## Environment notes (2026-11-05)
Windows 10, Node 22.12 / npm 10.9, Python 3.13, Git 2.47. **Not installed:** JDK, Android SDK, Docker, PostgreSQL, gh.
8 GB RAM; 26 GB free disk. Installed (2026-11-05): JDK 21.0.13, Gradle 9.8.2, Android SDK (platform-tools, android-36, android-37.0, build-tools 36.0.0) under %USERPROFILE%\dev. Disk free after install ≈ 22 GB.

| 2026-11-05 | Owner approved Phase 2, toolchain install (JDK + Android SDK), first commit on `main`; owner to provide test device and statement samples | — |
| 2026-11-05 | Phase 3: Room CHECK constraints realised as abort triggers; schema v1 limited to core ledger tables (others via migrations) | 11-database-schema.md implementation notes |
| 2026-11-05 | Phase 3: manual DI (`AppContainer`) instead of Hilt for now (fewer deps; Hilt/AGP 9.5 compatibility unverified). Revisit if wiring grows | ADR-0008 tooling note |
| 2026-11-05 | Phase 3: toolchain Temurin JDK 21.0.13, Gradle 9.8.2 (wrapper pinned by SHA-256), AGP 9.5.0, Kotlin 2.4.21, SDK platform 36 / build-tools 36.0.0; all downloads SHA-256-verified | 19-environment-setup.md |
| 2026-11-05 | XLSX import: in-house streaming reader on Android XmlPullParser (fastexcel fails R8 on Android) | docs/spikes/apk-size.md |
| 2026-11-05 | Owner: commit continuously; defer phone connection, follow up next session | — |
| 2026-11-05 | Correction: 4 property tests had silently never run (value-returning JUnit 5 tests); fixed + `testDiscovery` guard added | PROGRESS Phase 3 note |
