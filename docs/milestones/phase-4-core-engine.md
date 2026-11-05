# Milestone Plan: Phase 4, Core Financial Engine

| Status | **Implemented (2026-11-05)**: steps 4.1–4.9 done with JVM/Robolectric evidence (PROGRESS.md). Device-only acceptance (performance targets, Maestro journeys) reserved for Phase 8 final testing by the owner. |
|---|---|

## 1. Objectives
Implement and verify the financial core: ledger, accounts, transactions, transfers, categories, budgets, and savings
allocations. Every AC-CORE criterion must pass before any Phase 5 feature is built on top of it.

## 2. Scope (in build order)
| Step | Deliverable | Reqs |
|---|---|---|
| 4.1 | `:domain` ledger: account classes, transaction types, entry templates (domain §3.3), invariants I-1..I-4, validation errors | TXN-01..05, 08, 12 |
| 4.2 | `:domain` reports core: income/spending/adjustment classification, balances (current/pending/projected) | RPT-03, ACC-05 |
| 4.3 | `:domain` allocation ledger: budget funding, allocate/reallocate, goals, linked-account cap | BUD-05/06, SAV-01..04 |
| 4.4 | Property test: 10,000 random operation sequences preserve the ledger identity (AC-CORE-2) | NFR-01 |
| 4.5 | `:data` repositories + use cases UC-01..10, UC-13, UC-14, UC-16..21: single-transaction writes, revisions, idempotency | ACC, TXN-06/07/09 |
| 4.6 | Schema migrations v2+ (budgets, periods, goals, allocations), each with MigrationTestHelper tests | LOC-04 |
| 4.7 | Crash-injection test: abort mid-write leaves all or nothing (AC-CORE-9) | NFR-10 |
| 4.8 | UI: accounts, fast manual entry (TXN-13), transaction list (paged), transfers, categories, budgets, goals, reconciliation | UX, TXN-13 |
| 4.9 | Default PH category seed (skippable at onboarding) | CAT-02 |

## 3. Technical approach
Domain first (pure, JVM-tested), then persistence, then UI. Every EX-n from 10-domain-model.md becomes a named test.
Each step is committed only after `gradlew check` passes.

## 4. Dependencies
Phase 3 foundation (done except the device-only checks). The physical device is needed for performance targets and Maestro E2E.
Both are deferred and tracked as follow-ups.

## 5. Risks
| Risk | Mitigation |
|---|---|
| Report query performance unknown without a device | Benchmark harness in JVM tests now; device numbers when the phone is connected |
| Scope size | Strict build order; UI only after the engine passes its gate |

## 6. Acceptance criteria
AC-CORE-1..9 (PRD §4) pass as automated tests; `:domain` coverage ≥ 95 %; IC-1..IC-8 hold after every use-case test;
Maestro journeys 1–9 **when a device is available**.

## 7. Deliverables
Code + tests for the steps above, updated PROGRESS.md, and a schema doc per migration.
