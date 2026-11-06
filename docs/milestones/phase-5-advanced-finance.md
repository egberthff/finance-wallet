# Milestone Plan: Phase 5, Advanced Finance Features

| Status | **Implemented (2026-11-06)**: steps 5.1–5.5 with JVM/Robolectric evidence for AC-ADV-1..8 (PROGRESS.md). Device-only checks (notification delivery, performance, Maestro journey 10) reserved for Phase 8. |
|---|---|

## 1. Objectives
Build the advanced features on the verified Phase 4 engine: debts and liabilities, assets and net worth (with history),
bills, subscriptions and expected income with reminders, and the financial reports with CSV export. Every figure is
computed by deterministic domain code and cross-checked against an independent recompute.

## 2. Scope (in build order)
| Step | Deliverable | Reqs |
|---|---|---|
| 5.1 | Schema v3 migration: `debt`, `debt_schedule_item`, `asset`, `asset_valuation`, `recurring_rule`, `occurrence`, with CHECK triggers, MigrationTestHelper test, pre-migration backup path | LOC-04 |
| 5.2 | `:domain`: recurrence calculator (weekly … yearly, every N days, semi-monthly; closed-form), debt math (outstanding, equal-installment schedule, remaining), net worth with valuations, report calculator and date ranges (week/month/quarter/year/custom/budget period) | BILL-01, DEBT-01, NW-01/02, RPT-01..03 |
| 5.3 | `:data` use cases: UC-11/12 (create debt, disbursement, payment split P/I/F linked to schedule), UC-22/23/24 (rules, occurrence window, mark paid by creating or linking a transaction, skip, opt-in auto-post), UC-25 (assets, valuations), report and net-worth query services, CSV export (UC-30) | DEBT-01..03, BILL-01/02, INC-02/03, AST-01, NW-01/02, RPT-01..04 |
| 5.4 | Local reminders for due bills (inexact daily AlarmManager check, local notifications only, no network). **Changed from WorkManager, see §4** | BILL-03 |
| 5.5 | UI: Debts, Bills & subscriptions, Assets, Net worth (with history), Reports (ranges, tables and simple charts, drill-down to transactions), CSV export via the system file picker; dashboard "upcoming bills"; Phase 4 gaps: leftovers-to-reassign list and transaction edit | UX, RPT-03 |

Out of scope: lender interest calculation (domain §7: interest is user-entered per payment), automatic investment valuation
(AST-01), PDF report export (P1, later), any network access.

## 3. Technical approach
Same discipline as Phase 4: domain first (pure, JVM-tested, ≥ 95 % coverage), then persistence (one transaction per use case,
`validatedWrite`, idempotency keys, revisions/audit), then UI on top of tested ViewModels. Charts are drawn with Compose Canvas
(no chart library, so no new dependency and no network-capable code). Reports read the money ledger only, never budgets (BUD-04).

## 4. Dependencies
Phase 4 engine (done). **No new library.** WorkManager 2.12.0 was evaluated (2026-11-06) and not used: its manifest requests ACCESS_NETWORK_STATE, which ADR-0010 forbids and our manifest strips (behaviour with the permission removed is only verifiable on a device), and it keeps its own unencrypted database. An inexact daily `AlarmManager` alarm plus a boot receiver meets BILL-03 (best-effort) with platform APIs only.
Notification delivery timing can only be checked on a device: deferred to Phase 8 with the other device checks (owner decision).

## 5. Risks
| Risk | Mitigation |
|---|---|
| Recurrence edge cases (31st, Feb 29, semi-monthly) | Closed-form calculator reusing the budget-period clamping rules; 10-year property tests |
| A bill marked paid without a real payment | Database trigger: PAID ⇔ linked transaction; occurrences only change through UC-23 |
| Report totals drifting from the ledger | Every report total cross-checked against an independent recompute on random data |
| Duplicate auto-posted transactions | Idempotency key `recurring:{rule}:{dueDate}` on the transaction |
| Reminder delivery depends on the OS (Doze, OEM battery savers) | Documented as best-effort (BILL-03); in-app "upcoming" list is the source of truth |
| CSV export writes plaintext financial data | Explicit warning before export; user picks the location (SAF); audit event (UC-30) |

## 6. Acceptance criteria (AC-ADV)
1. **Debts:** a payment reduces the liability by the principal only; interest and fees are spending; a disbursement is never income;
   outstanding = −balance (EX tests).
2. **Schedules:** equal-installment schedules sum exactly to the total (remainder on the last item); remaining installments = unpaid items.
3. **Bills:** an occurrence is PAID only with a linked transaction (enforced by trigger and tested); materializing the window twice
   creates nothing new; auto-post is idempotent per (rule, due date) and opt-in only.
4. **Recurrence:** for every frequency and day 1–31, 10 years of dates are strictly increasing, one per period, with correct clamping (property test).
5. **Net worth:** NW = Σ included account balances + Σ latest valuations; transfers never change it; month-end history equals an independent recompute.
6. **Reports:** on random ledgers, every report figure (income, spending, by category with subcategory roll-up, by account, cash flow,
   budget vs actual) equals an independent recompute; transfers, savings moves, adjustments and refunds are classified as in domain §3.4;
   ranges cover week, month, quarter, year, custom and budget period.
7. **Export:** CSV amounts are exact decimal text (no floating point), fields are escaped, and re-parsing the file gives the same rows.
8. `gradlew check assembleDebug` passes; IC-1..IC-8 hold after every use-case test; no INTERNET permission.

Device-only (Phase 8): notification delivery, report-screen performance on the reference device, Maestro journey 10.

## 7. Deliverables
Code and tests for the steps above, schema doc notes for v3, updated PROGRESS.md and feature matrix.
