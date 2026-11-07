# Use-Case Catalogue & Requirement Traceability

| Status | Phase 2 draft v1 (2026-11-05) |
|---|---|

Use cases are the **only** code allowed to write to the database (06-architecture-overview.md §3). Each one:
1. validates input (field-level errors returned to the UI);
2. builds the domain result in `:domain` and checks invariants;
3. writes everything (rows, revisions, audit, outbox when Phase 7 exists) in **one** Room transaction;
4. is idempotent where a user can double-submit, via an `idempotencyKey` generated when the form opens.

## 1. Catalogue

| ID | Use case | Key validations | Writes | Reqs |
|---|---|---|---|---|
| UC-01 | CreateAccount | name 1–80; type; currency ISO; opening balance ≥ 0 for assets (≤ 0 meaning owed for liabilities, entered as positive "amount owed") | ledger_account, financial_account, OPENING_BALANCE txn (if ≠ 0) | ACC-01/02 |
| UC-02 | EditAccount | currency immutable once the account has non-opening entries | financial_account, ledger_account, audit | ACC-03 |
| UC-03 | ArchiveAccount / Unarchive | warns if balance ≠ 0 or there are active recurring rules | archived_at, audit | ACC-03 |
| UC-04 | DeleteAccount | allowed only if no entries except the opening balance and no imports; otherwise offer archive | hard delete (incl. opening txn), audit | ACC-04 |
| UC-05 | RecordIncome / RecordExpense | amount > 0; split parts sum = amount; categories of the right class; account active | txn, entries, revision(CREATE), fingerprint (manual: none) | TXN-01/02, INC-01 |
| UC-06 | RecordTransfer (incl. savings contribution/withdrawal, cross-currency) | distinct accounts; currencies; both amounts for FX | txn, entries, revision | TXN-04, SAV-03 |
| UC-07 | RecordRefund | linked original: EXPENSE, Σ refunds ≤ original | txn, entries, revision | TXN-05 |
| UC-08 | ReverseTransaction | original POSTED, not reversed, not a reversal | txn (REVERSAL), entries, revision | TXN-08 |
| UC-09 | EditTransaction | type-specific; dependent refunds/reversals block amount/currency change | txn, entries (replaced), revision(UPDATE), version++ | TXN-06 |
| UC-10 | DeleteTransaction / Restore | confirm; blocked if it is the target of a reversal (delete the reversal first) | deleted_at, revision | TXN-07 |
| UC-11 | RecordDebtPayment | P+I+F > 0; liability linked to a debt; optional schedule item | txn, entries, schedule link | DEBT-02 |
| UC-12 | RecordDebtDisbursement / CreateDebt | original > 0 | ledger_account, financial_account, debt, txn | DEBT-01/03 |
| UC-13 | Reconcile | as-of date ≤ today; statement balance | reconciliation, optional BALANCE_ADJUSTMENT txn | ACC-06, IMP-10 |
| UC-14 | Manage categories (create/edit/archive/reorder/subcategory) | unique name among siblings; one level; archived kept on history | ledger_account, category | CAT-01/02/04 |
| UC-15 | Manage categorization rules | pattern 1–120 chars | category_rule | CAT-03 |
| UC-16 | CreateBudget / EditBudget | period params per kind; changes to period rules apply from the next period | budget, budget_category, budget_account | BUD-01..03 |
| UC-17 | EnsurePeriods (internal) | lazy creation, idempotent by (budget, start_date) | budget_period | BUD-02 |
| UC-18 | Allocate / Reallocate / Fund | period open; % via split; snapshot recorded | allocation_group + entries | BUD-05/06 |
| UC-19 | ClosePeriod (rollover) | idempotent per (period, category) | allocation_group(ROLLOVER/SWEEP), closed_at | BUD-07 |
| UC-20 | CreateGoal / EditGoal / LinkGoalAccounts | target > 0; linked accounts are real ASSETs | savings_goal, goal_account | SAV-01/03 |
| UC-21 | ContributeToGoal / WithdrawFromGoal / TransferBetweenGoals | linked-cap rule (EX-17); withdraw/transfer require `confirmed=true` | allocation_group (+ optional linked transfer txn) | SAV-02/04 |
| UC-22 | Manage recurring rules; GenerateOccurrences (internal) | frequency params | recurring_rule, occurrence | BILL-01, INC-02/03 |
| UC-23 | MarkOccurrencePaid (create or link txn) / Skip | linked txn amount may differ from the rule's amount | occurrence, txn | BILL-02 |
| UC-24 | AutoPostRecurring (internal, on app start/worker) | autoPost, manual-source template, idempotent by occurrence | txn | INC-03 |
| UC-25 | Manage assets & valuations | value ≥ 0 | asset, asset_valuation | AST-01 |
| UC-26 | PreviewImport (no writes) | file limits; detection; parsing; duplicate classification | none (in-memory session) | IMP-01..09 |
| UC-27 | CommitImport | session still valid (account unchanged since preview: `version` check) | import_batch, import_row, txns, entries, fingerprints, revisions, audit | IMP-04/05/09 |
| UC-28 | RollbackImport | batch COMMITTED; transactions from it that were edited afterwards are listed for confirmation | txns deleted_at, revisions(IMPORT_ROLLBACK), batch status, fingerprints removed | IMP-05, AC-IMP-3 |
| UC-29 | CreateBackup / RestoreBackup | passphrase strength; restore: auth tag + integrity checks before swap | backup file via SAF / DB swap, audit | BAK-01 |
| UC-30 | ExportData (CSV/JSON) | user warning: plaintext | file via SAF, audit | PRIV-01, RPT-04 |
| UC-31 | DeleteAllData | double confirmation + PIN | DB + key + attachments wiped, app reset | PRIV-01 |
| UC-32 | SetupAppLock / Unlock / ChangePin | PIN 6 digits; backoff on failures | DataStore (hash), audit | SEC-01 |

**Implementation notes (Phase 4, 2026-11-05)**
- **UC-04 DeleteAccount is narrower than specified:** permanent delete is allowed only for an account that never had any
  transaction, *including* an opening balance, and is not linked to a budget, goal or reconciliation. The opening-balance
  transaction has append-only revisions (TXN-06) that reference it, so hard-deleting it would destroy audit history. Such an
  account returns `ACCOUNT_HAS_HISTORY` and the UI offers archive (UC-03), which hides it and blocks new transactions.
- **UC-02:** the opening balance is edited through its SYSTEM transaction (create / edit / soft-delete, each with a revision).
  Currency can change only while the opening balance is the account's only history (`CURRENCY_LOCKED` otherwise).
- **UC-03:** archiving an account with a non-zero balance requires an explicit acknowledgement (`BALANCE_NOT_ZERO`).
- **UC-09:** an edit may keep an archived account or category that the transaction already uses (owner decision 2026-11-07,
  docs/10-domain-model.md §3.6); the entry form shows it labelled "(archived)". Archived ones cannot be newly chosen.
- **UC-13:** statement balances are entered as the user sees them (liabilities: positive amount owed); the stored
  difference is in ledger sign convention. Adjustments are BALANCE_ADJUSTMENT on the as-of date, never income or spending.
- **UC-14:** names are unique among siblings with Unicode case folding ("PIÑATA" = "Piñata"; accents are kept, so
  "Año" ≠ "Ano"); archived siblings keep their name reserved, so a restore can never create a duplicate. Adding a name that
  belongs to an archived sibling returns `CATEGORY_NAME_ARCHIVED` and the UI offers to restore it. A parent can be archived
  only after its live subcategories, and a subcategory can be restored only after its parent. Archiving asks for
  confirmation and shows what uses the category (budgets, bills and recurring items, import rules, past transactions);
  it can be undone. Every create, edit, archive, restore, reorder, seed and colour change writes an `audit_event`.
  *Decision 2026-11-07:* "Add suggested categories" can be run at any time and adds only the starter categories whose name
  is missing among their siblings (live or archived), so it never duplicates or renames the user's categories; a renamed
  starter category (e.g. "Food" → "Meals") is treated as missing.
- **UC-16/19 undo (2026-11-07):** the Budgets tab lists open budgets and closed ones apart, each with a status label
  (Open · N days left, Period closed, Archived). An archived budget can be restored as it was. A period closed before its
  end ("Close period" asks first) can be reopened while it lasts: the rollover, sweep and leftover moves written for it
  are soft-deleted (kept, key freed so it can be closed again). Reopening is refused when the period has ended, a later
  period is closed, or money a move added to a category or goal has since been used (`MOVED_MONEY_USED`).

Read-side queries (balances, reports, dashboard) are **query services**, not use cases. They are read-only and run in `:data`,
with calculations in `:domain` (`ReportCalculator`, `BudgetCalculator`, `NetWorthCalculator`).

## 2. Requirement traceability (P0)

| Req | Covered by |
|---|---|
| ACC-01..06 | UC-01..04, UC-13; domain §2, §4 |
| TXN-01..10, 12 | UC-05..10; domain §3; schema §3.2 |
| TXN-13 (fast entry) | UC-05 + UI spec (Phase 3/4); templates = RECURRING_TXN rules without schedule or favorites (Phase 4 design) |
| CAT-01, 02, 04 | UC-14 |
| BUD-01..06, 08 | UC-16..18; domain §5 |
| SAV-01, 02, 04 | UC-20, UC-21; domain §6 |
| INC-01 | UC-05 |
| RPT-01 (first 5), 02, 03 | Query services; domain §3.4; RPT-03 drill-down uses the same predicates as the totals |
| IMP-01, 02, 04..07, 09 | UC-26..28; 13-import-pipeline.md |
| INT-02, INT-06 | No network code; store copy review (Phase 9) |
| LOC-01..04 | Whole design; schema §6 |
| BAK-01 | UC-29; 14-backup-format.md |
| SEC-01, 02, 04, 05 | UC-32; 16-security-architecture.md |
| PRIV-01..03 | UC-30, UC-31; 16-security-architecture.md §6 |
| UX-01..06 | Phase 3 design system (not an architecture artifact) |
| NFR-01 | ADR-0003; 18-coding-standards.md §3 |
| NFR-02..08, 10, 11 | Phase 3 spikes/benchmarks; schema §4; crash-injection tests (schema §6, UC transaction rule) |

Every P0 requirement maps to at least one entity, rule, or use case (Phase 2 exit criterion 1).
