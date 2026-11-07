# Project Progress & Feature Implementation Matrix

Single source of truth for implementation status (CLAUDE.md §20, §23).
Statuses: **Planned · In progress · Implemented · Tested · Verified · Blocked · Unavailable · Out of scope**.
Test statuses: **Not written · Written · Executed–Passed · Executed–Failed · Not executed**.

_Last updated: 2026-11-07_

## Scope (owner decisions 2026-11-05)
- **Android only.** iOS dropped ([ADR-0009](docs/adr/0009-scope-android-only.md)).
- **No direct API integrations.** Data enters by manual entry and file import only ([ADR-0006](docs/adr/0006-integration-strategy.md)).

## Phase status

| Phase | Status | Notes |
|---|---|---|
| 1. Discovery & validation | **Approved by owner (2026-11-05)** | Native Kotlin stack accepted; open-question defaults adopted |
| 2. Architecture | **Approved by owner (2026-11-05)** | [milestone plan](docs/milestones/phase-2-architecture.md) |
| 3. Foundation | **Complete except device-only checks** (criterion 6, SQLCipher perf); owner reserved phone testing for final testing (Phase 8) | [milestone plan](docs/milestones/phase-3-foundation.md) |
| 4. Core financial engine | **Steps 4.1–4.9 implemented and tested on the JVM**; device-only criteria (performance, Maestro journeys 1–9) deferred to Phase 8 by owner. Awaiting owner review before Phase 5 | [milestone plan](docs/milestones/phase-4-core-engine.md) |
| 5. Advanced finance | **Steps 5.1–5.5 implemented and tested on the JVM** (2026-11-06); device-only checks reserved for Phase 8. Awaiting owner review before Phase 6 | [milestone plan](docs/milestones/phase-5-advanced-finance.md) |
| 6. Statement import | **Steps 6.1–6.5 implemented and tested on the JVM** (2026-11-06): generic CSV/XLSX; PDF refused until a verified layout exists; device checks reserved for Phase 8. Awaiting owner review before Phase 7 | [milestone plan](docs/milestones/phase-6-statement-import.md) |
| 7. Backup, privacy, insights | **Steps 7.1–7.5 implemented and tested on the JVM** (2026-11-06): encrypted .fwbak backup and verified restore, export, delete all, on-device insights; cloud/sync and AI dropped (ADR-0012); device checks reserved for Phase 8. Awaiting owner review before Phase 8 | [milestone plan](docs/milestones/phase-7-backup-privacy-insights.md) |
| 8. QA & release candidate | **Complete on emulators** (2026-11-07): checklist A–G on Android 13 (debug + release) and Android 11 (release); 8 defects found and fixed (D1–D8, incl. a release-only crash); 1.0.0-rc1 signed APK + AAB, 16 KB aligned. Physical phone run outstanding (USB debugging unavailable) | [QA report](docs/milestones/phase-8-quality-assurance.md#7-qa-report), [test state](docs/qa/test-state.md) |
| 9. Production prep | **In progress**: deployment, backup/recovery, known-limitations, dev/testing guides and privacy-notice draft written; app icon, crash-log export, key-loss recovery implemented. Owner actions left: legal review, Play Console listing, closed testing | [deployment guide](docs/22-deployment-guide.md) |

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

Test totals (latest full `check`): **291 executed, 291 passed, 0 failed, 0 skipped** (`:domain` 117, `:importers` 20, `:data` 104, `:app` 50), 2026-11-07 on Temurin 21.

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
- UI redesign 2026-11-06: full colour scheme (no library defaults), icon set (Material path data), launcher icon, hero card, grouped More screen, back bar on sub-screens, edge-to-edge. Screenshots from the emulator were used to review it.

## Feature matrix

Phase 3 code exists (see above). Feature rows below reflect it.

| Req group | Requirements | Phase | Impl. status | Files | Tests | Issues / limitations |
|---|---|---|---|---|---|---|
| Ledger engine (domain) | TXN-01..05, 08, 12; RPT-03; NW-01; ACC-05 (calc) | 4.1–4.4 | **Implemented + tested (domain only)**: account classes, 11 transaction-type templates with validation codes, invariants I-1..I-3, calculator (balances current/pending/projected, income/spending/adjustment/opening/FX classification, spending by category, net worth) | domain/src/main/.../ledger, common | Executed–Passed: EntryTemplatesTest 15, LedgerCalculatorTest 8, DomainResultTest 4, LedgerPropertyTest (10,000 sequences / 58,387 ops) | Not yet persisted (data layer = step 4.5); allocation ledger = step 4.3 |
| Ledger persistence & use cases | ACC-01/02, TXN-02, 05..09, UC-01, 05..10, 14 (min.) | 4.5 | **Implemented + tested (JVM/Robolectric)**: LedgerService (system accounts, create account + opening balance, create category, record/edit/delete with revisions, idempotency, refund/reversal context, dependent-edit/delete guards), SQL balances/pending/summary | data/src/main/.../ledger | Executed–Passed: LedgerServiceTest 12, incl. AC-CORE-8 (EX-10), AC-CORE-9 (fault injection at 2 checkpoints), and SQL vs domain-calculator cross-check over 300 random ops; IC checks after every test | Archive/edit account (UC-02/03/04), reconciliation (UC-13), UI pending |
| Schema v2 migration + pre-migration backup | LOC-04, R-10 | 4.6 | **Implemented + tested (Robolectric)**: Migration 1→2 (reconciliation, budgets, periods, goals, allocation ledger + CHECK triggers), IC-5, DatabaseOpener (version probe, file backup keeping 2, post-migration IC gate with restore), `validatedWrite` (rollback on any failed check) | data/src/main/.../db | Executed–Passed: MigrationTest 2 (MigrationTestHelper validation, data kept, triggers equal fresh install, IC healthy), DatabaseOpenerTest 6, ValidatedWriteTest 4 | Backup is a raw (SQLCipher-encrypted) file copy until the Phase 7 backup format; encrypted path verified on device in Phase 8 |
| Budgets, periods, allocations, goals (use cases) | BUD-01..07, SAV-01..04, UC-16..21 | 4.5/4.6 | **Implemented + tested (Robolectric)**: BudgetService (create/edit/archive, rule change with stored periods + bridging period, lazy periods, lazy close, rollover CARRY/overspend/SWEEP/EXPIRE/MANUAL, reassign list), AllocationService (fund, allocate, %, defaults, reallocate; idempotent), GoalService (create/edit/link with cap, contribute, confirmed withdraw/transfer, earmark, transfer+allocation in one write, free money), RolloverPlanner (domain) | data/src/main/.../budget, domain/.../allocation/Rollover.kt | Executed–Passed: BudgetServicesTest 18 (incl. EX-11, EX-15/AC-CORE-3, EX-16, EX-17, atomic save-to-account rollback), RolloverTest 14; IC-1..8 healthy after every test | UI pending (4.8); budget-vs-actual report in Phase 5 |
| Account management, reconciliation, categories, PH seed | ACC-03/04/06, CAT-01/02/04, UC-02/03/04/13/14, step 4.9 | 4.5/4.9 | **Implemented + tested (Robolectric)**: AccountService (edit incl. opening balance via revisions, currency lock, archive/unarchive with acknowledgement, delete only without history/links, reconcile with optional BALANCE_ADJUSTMENT, latest reconciled balance, audit events), CategoryService (sibling-unique names, one level, edit, archive/unarchive, reorder, idempotent default PH seed of 21 groups) | data/src/main/.../account | Executed–Passed: AccountAndCategoryTest 10 | UC-04 narrower than spec (see 12-use-cases.md notes); UI pending |
| Phase 4 screens (4.8) | UX-02/05, TXN-13, ACC, BUD, SAV, CAT | 4.8 | **Implemented + tested (Robolectric; not yet run on a device)**: Home (account money, owed, net worth, free money, calendar-month income/spending, each captioned and never added together), Accounts (calculated vs pending vs reconciled, add, reconcile, archive/restore), Transactions (keyset-paged list, delete with history), fast entry (expense/income/transfer, remembered account/category, one idempotency key per entry), Budgets (monthly budget from today, allocate, add funds, save to goal, close period), Goals (free money, set aside, release with confirmation), Categories (add, archive, suggested PH seed). DB opens after unlock with loading/error states | app/src/main/.../ui, AppServices.kt, TransactionQueries.kt | Executed–Passed: ScreenViewModelsTest 6, EntryScreenUiTest 1 (Compose UI test drives the real entry screen), TransactionQueriesTest 2 | **Device run, accessibility scan and Maestro journeys deferred to Phase 8 (owner).** No icons yet (text-only navigation); editing a transaction/budget from the UI and the reassign-leftover list are service-only for now |
| Phase 5 domain | BILL-01, DEBT-01, NW-01/02, RPT-01..03 | 5.2 | **Implemented + tested**: RecurrenceCalculator (weekly…yearly, semi-monthly, every N days, month-end clamping), DebtMath (equal schedules summing exactly), ReportRanges, ReportCalculator (roll-up, spending by paying account, cash flow), NetWorthCalculator (valuations, month ends), MoneyFormatter.plain | domain/.../recurring, debt, report | Executed–Passed: RecurrenceTest 8 (10-year property for every day 1–31), Phase5DomainTest 11 (1,000 random ledgers: cash-flow net = Δ asset balances; account spending = total spending); domain coverage 98.2 % | — |
| Schema v3 | LOC-04 | 5.1 | **Implemented + tested**: migration 2→3 (debt, debt_schedule_item, asset, asset_valuation, recurring_rule, occurrence) with triggers incl. PAID ⇔ linked txn, auto-post only for templates, occurrence reference check on txn | data/.../db | Executed–Passed: MigrationTest 3 (1→2→3 validated by MigrationTestHelper) | — |
| Debts, assets, recurring, reports, CSV (use cases) | DEBT-01..03, AST-01, BILL-01/02, INC-02/03, NW-01/02, RPT-01..04, UC-11/12/22..25/30 | 5.3 | **Implemented + tested (Robolectric)**: DebtService (create with disbursement or existing balance, schedule, P/I/F payments linked to installments, PAID_OFF), AssetService (valuations, soft delete), RecurringService (window materialization, mark paid by new or linked txn, skip, revert on deleted txn, opt-in auto-post, reminders, 12-month costs), ReportService (income vs expense by range, by category with roll-up, by account, cash flow, net worth + month-end history), CsvExporter (exact decimals, RFC 4180, formula-injection guard, audit) | data/src/main/.../advanced | Executed–Passed: Phase5ServicesTest 8, ReportCrossCheckTest 1 (300 random use-case operations, every report figure equals an independent recompute) | Reminders (5.4) and UI (5.5) pending |
| Bill reminders | BILL-03 | 5.4 | **Implemented + tested (Robolectric; delivery on a device not yet verified)**: daily inexact alarm (08:00 Manila) re-armed on app start and boot, background check (materialize, opt-in auto-post, reminder windows), notification with VISIBILITY_PRIVATE and a lock-screen version without names or amounts, POST_NOTIFICATIONS respected | app/.../reminders, AndroidManifest.xml | Executed–Passed: BillRemindersTest 4 | Real delivery timing (Doze/OEM) → Phase 8 device testing; permission prompt UI in 5.5 |
| Phase 5 screens (5.5) + Phase 4 gaps | UX, RPT-01..04, DEBT, BILL, AST, NW, TXN-06, BUD-07 | 5.5 | **Implemented + tested (Robolectric; not yet run on a device)**: Debts (create with disbursement or existing balance, monthly plan, P/I/F payment), Bills & subscriptions (rules start in the future, mark paid/received with real transaction, skip, 12-month costs, notification permission prompt), Assets (user values, one per day), Reports (week/month/quarter/year with previous/next, income vs spending, cash in/out, adjustments shown separately, categories with roll-up and drill-down, by account, net worth parts + month-end chart with text description, CSV export via system picker after a plaintext warning), Home "bills due soon", More menu; transaction edit (expense/income/transfer, revisioned) and leftovers-to-reassign on budget cards | app/src/main/.../ui/advanced, ui/budgets, ui/transactions | Executed–Passed: Phase5ViewModelsTest 6 (found and fixed: same-day asset values disagreed between list and net worth) | Device run, accessibility scan, Maestro journey 10 → Phase 8. Edit of split/other transaction types, sweep-to-goal from the leftover list, debt status editing: service-only |
| Import parsing and normalization (`:importers`) | IMP-01/02/03/06/07, TXN-09, AC-IMP-2/5 | 6.1/6.2 | **Implemented + tested (JVM)**: new pure-JVM module; file guard (size, content-based format, OLE/.xls refused, PDF refused until a layout is verified), encoding detection (BOM, strict UTF-8, Windows-1252), in-house RFC 4180 CSV reader with delimiter sniffing, streaming XLSX reader (shared/inline strings, exact numbers, 1900/1904 dates, cached formulas, zip-bomb and DTD guards), mapping suggestion (debit/credit, signed, amount+type), date detection that never guesses day vs month, exact amounts, per-row rejection codes, running-balance check, fingerprints | importers/src/main | Executed–Passed: ReadersTest 11 (incl. CSV round-trip property ×500, zip bomb, DTD), NormalizerTest 9 (incl. AC-IMP-5 CSV = XLSX, overlapping-statement fingerprints); coverage 99.1 % | Real institution layouts need samples (Q-11) |
| Schema v4 + import use cases | IMP-01..10, CAT-03, UC-15/26/27/28, AC-IMP-1..4 | 6.3/6.4 | **Implemented + tested (Robolectric)**: migration 3→4 (import_profile/batch/row, txn_fingerprint, category_rule, reference triggers); ImportService preview (exact duplicates by fingerprint, probable duplicates by amount/±3 days/similarity or reference, one match per transaction, suggestions from rules then 12-month history), commit in one transaction with account-version check (stale preview refused, double commit impossible), decisions import/transfer/link/skip/import-anyway, pending rows, rollback with IMPORT_ROLLBACK revisions and fingerprint removal, edited-after-import confirmation, history, saved profiles matched by exact header signature; CategoryRuleService | data/src/main/.../imports | Executed–Passed: ImportServiceTest 10 (AC-IMP-1..4, XLSX through the Android XML parser), MigrationTest 4 | Reconciliation hand-off and UI in 6.5 |
| Import screens (6.5) | IMP-04/05/07/10, CAT-03 | 6.5 | **Implemented + tested (Robolectric; not yet run on a device)**: choose account and file (system picker, reads at most the size limit), mapping with column pickers and an explicit date-format choice when day/month is ambiguous, optional saved profile, preview with counts, in/out totals, already-imported and running-balance warnings, per-row import/skip/link/import-anyway and category, commit, reconcile against the statement closing balance, import history with rollback (edited-row warning), categorization rules screen | app/src/main/.../ui/imports | Executed–Passed: ImportViewModelsTest 3 (found and fixed: an ambiguous date format discarded the recognized columns) | Share-sheet (ACTION_SEND/VIEW) entry not built yet; device run in Phase 8 |
| Savings & allocation ledger (domain) | SAV-01..04, BUD-05/06 | 4.3 | **Implemented + tested (domain only)**: buckets, fund/allocate/percent/reallocate, goal contribute/withdraw/transfer/direct earmark with confirmation + before/after snapshot, linked-account cap (shared-account groups), funding modes, available-to-budget, progress, free money | domain/src/main/.../allocation | Executed–Passed: allocation.AllocationTest 11 incl. property (3,000 sequences) — EX-15, EX-16, EX-17 | Persistence + UI pending (4.5–4.8); rollover is Phase 5 |
| PDF import | IMP-03, IMP-08 | 6 | **Deferred** (needs verified layouts, Q-11) | — | Not written | Verified layouts only; PdfBox-Android fit unproven (R-21) |
| Direct integrations | INT-01, 03, 04, 05 | — | **Out of scope** | — | — | ADR-0006 |
| Integration constraints | INT-02, INT-06 | all | In force (policy) | — | n/a | — |
| Local-first & encrypted DB | LOC-01..04 | 3 | **In progress**: schema v1, triggers, integrity checks, SQLCipher factory, Keystore-wrapped key implemented | data/src/main/... | Executed–Passed (11, JVM/Robolectric); encryption on device **Not executed** | Pre-migration backup not yet implemented (needs the first migration and the backup format) |
| Local backup | BAK-01 | 7 | **Implemented + tested (JVM/Robolectric)**: `.fwbak` (Argon2id + chunked AES-256-GCM), verified replace-only restore in one transaction with pre-restore copy, Backup & restore screen, Home reminder | data/.../backup, app/.../ui/backup | Executed–Passed: BackupCryptoTest 7, BackupServiceTest 5, Phase7ViewModelsTest (backup/restore/damaged files) | Argon2id timing and SAF with cloud-drive providers on device: Phase 8 |
| Cloud backup / sync | BAK-02, SYNC-01..02 | — | **Out of scope** | — | — | ADR-0012 |
| Security | SEC-01..05 | 3+ | **In progress**: app lock (PIN PBKDF2 210k + Keystore-wrapped hash, backoff, biometric, auto-lock 60 s), FLAG_SECURE, no-network manifest check, backup exclusion | app/src/main/.../security, AndroidManifest.xml | Executed–Passed (13 JVM); on-device **Not executed** | Biometric opt-out setting pending |
| Privacy | PRIV-01..03 | 7, 9 | **PRIV-01 implemented + tested**: plaintext export of all data (with warning), delete all data (acknowledgement + PIN; database, keys, PIN, local copies, alarm) | app/.../ui/backup, FinanceApp.kt | Executed–Passed: Phase7ViewModelsTest (export, PIN gate, wipe of files), AppLockControllerTest (confirmPin lockout) | Keystore deletion and restart verified on device in Phase 8; privacy notice and legal review (R-07) in Phase 9 |
| AI assistant | AI-01..04 | — | **Out of scope**: replaced by on-device insights | — | — | ADR-0012 |
| On-device insights | INS-01..03 | 7 | **Implemented + tested**: month flow, top categories, increases vs. same days last month, unusual expenses, bills vs. free money, saving-capacity estimate (labelled); read-only | domain/.../insight, data/.../insight, app/.../ui/backup | Executed–Passed: InsightRulesTest 4, InsightsServiceTest 2, Phase7ViewModelsTest | — |
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
| 2026-11-06 | **Owner:** no cloud backup, no multi-device sync, no external AI; on-device insights instead | ADR-0012 (Accepted) |
| 2026-11-06 | Import: in-house RFC 4180 CSV reader instead of Commons CSV (avoids commons-io/codec at runtime); no PDF layout until verified with real samples; XmlPullParser compile-only, kxml2 for tests | phase-6 plan §3 |
| 2026-11-06 | TextNormalizer moved to `:domain` so ledger `*_norm` columns and import fingerprints share one implementation | domain/text |
| 2026-11-06 | An asset has one value per day: a new value for the same date supersedes the earlier one (soft-deleted, kept) | AssetService.addValuation |
| 2026-11-06 | Bill reminders use an inexact daily AlarmManager alarm instead of WorkManager (no new dependency, no ACCESS_NETWORK_STATE, no extra unencrypted DB) | phase-5 plan §4 |
| 2026-11-05 | **Owner:** physical-phone checks reserved for final testing (Phase 8) | memory/PROGRESS |
| 2026-11-05 | Fixed latent partial-commit risk: all writes go through `validatedWrite` (rolls back on a failed check) | data/db/ValidatedWrite.kt |
| 2026-11-05 | Pre-migration backup is an interim raw copy of the encrypted DB file until the Phase 7 format exists | 11-database-schema.md Phase 4 notes |
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
| 2026-11-06 | New dev PC: Temurin 21 (Oracle JDK rejects BouncyCastle as a JCE provider), SDK packages installed by hand, aapt2 override | docs/20 §1 |
| 2026-11-06 | Emulators (WHPX) replace the phone for device testing; debug builds allow screenshots, release keeps FLAG_SECURE (build check) | docs/20 §4 |
| 2026-11-06 | Key loss: "start over" sets unreadable data aside, never deletes; local crash reports without messages | docs/16 §6a |
| 2026-11-07 | Release candidate 1.0.0-rc1 (versionCode 2); 0.9.0-beta withdrawn (release-only backup crash D6) | docs/qa/test-state.md |
