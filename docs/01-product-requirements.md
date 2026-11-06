# Product Requirements Document (PRD)

| Field | Value |
|---|---|
| Status | **Draft v0.2 — Phase 1, pending owner approval** |
| Change log | v0.2 (2026-11-05): owner scope change. **Android only** (iOS dropped); **no direct bank/e-wallet API integrations**: data enters by manual entry or file import only. See [ADR-0009](adr/0009-scope-android-only.md), [ADR-0006](adr/0006-integration-strategy.md). |
| Date | 2026-11-05 |
| Source | `CLAUDE.md` (project system instructions) |
| Related | [Open questions](02-open-questions.md) · [Feasibility](03-technical-feasibility.md) · [Risks](04-risk-register.md) · [Traceability matrix](../PROGRESS.md) |

Every requirement has a stable ID. Code, tests, and ADRs reference these IDs. A requirement's
status is tracked only in [PROGRESS.md](../PROGRESS.md), not here.

Priority: **P0** = required for first public release (MVP) · **P1** = required for v1.0 · **P2** = later / conditional.

---

## 1. Product summary

A local-first personal finance app for individuals in the Philippines, for **Android**. It brings
accounts, transactions, budgets, savings, debts, bills, net worth, and reports together in one place.
It works fully offline. Data enters only by **manual entry** or **file import** (CSV, Excel, PDF statements).
Cloud backup, multi-device sync, and AI are **optional**.

### 1.1 In scope (initial release line)
Individual users; Android phones; PHP default currency; English UI; Asia/Manila timezone; manual entry and
statement/file import.

### 1.2 Explicitly out of scope (owner decision 2026-11-05 unless noted)
- **iOS** (and any non-Android platform).
- **Direct bank/e-wallet API integrations and aggregators**, including connection, consent, provider sync, and background provider sync.
- Shared/household/collaborative accounts.
- Executing payments or moving real money.
- Retrieving live investment valuations (investments are user-entered).
- Screen scraping or credential-based login automation for any institution.
- Web or desktop clients.

### 1.3 Core domain definitions (normative)

These definitions resolve ambiguity in the source instructions. All reports and dashboards must use them.

| Term | Definition |
|---|---|
| **Account balance (calculated)** | Opening balance + sum of all *posted* ledger entries for the account. Deterministic. |
| **Statement balance** | Closing balance taken from an imported statement or typed by the user during reconciliation, with its "as of" date. Never mixed with calculated balance. (There are no live institution balances, because there is no direct integration.) |
| **Reconciled balance** | Calculated balance as of the latest reconciliation point the user confirmed. |
| **Pending** | Transactions with status `pending`. Shown separately and excluded from posted balance. |
| **Spending (expense total)** | Sum of `expense` transactions minus linked `refund`s in a period. Excludes transfers, savings movements, balance adjustments, debt principal movements. |
| **Income total** | Sum of `income` transactions. Excludes transfers, refunds, debt disbursements, savings withdrawals. |
| **Savings allocation (virtual)** | Earmarking money for a goal. It changes the *allocation ledger* and does not move money between accounts. Never an expense. |
| **Savings transfer (actual)** | A transfer into or out of an account flagged as a savings account. A money ledger transfer, never an expense. |
| **Available to budget** | Money assigned to a budget pool that has not been allocated to categories or savings. This is a budget concept, separate from account balance. |
| **Net worth** | Σ asset balances (accounts with positive nature + asset records) − Σ liability balances (credit cards, loans, debts). Internal transfers net to zero. |

---

## 2. Functional requirements

### 2.1 Accounts (ACC)
| ID | Requirement | Pri |
|---|---|---|
| ACC-01 | Create accounts of types: cash, bank, e-wallet, savings, credit card, loan, other. | P0 |
| ACC-02 | Fields: name, institution (optional), type, currency, opening balance + date, status, settings, default import profile, last-import timestamp. | P0 |
| ACC-03 | Edit and archive accounts. Archiving hides the account from entry pickers but keeps its history and its effect on reports. | P0 |
| ACC-04 | Hard delete is allowed only for accounts with zero transactions. Otherwise the app offers archive. | P0 |
| ACC-05 | Display calculated, statement, pending, and reconciled balances distinctly, with "as of" dates. | P0 |
| ACC-06 | Reconciliation: user enters a statement balance, app shows the difference, and user may create a `balance_adjustment` transaction to resolve it. | P0 |
| ACC-07 | "Last reconciled / last imported" indicator per account, with an optional reminder when an account has not been reconciled for a configurable period (default 30 days). | P1 |

### 2.2 Transactions (TXN)
| ID | Requirement | Pri |
|---|---|---|
| TXN-01 | Types: income, expense, transfer, savings_contribution, savings_withdrawal, refund, reversal, balance_adjustment, debt_payment, debt_disbursement. | P0 |
| TXN-02 | Fields: id, account, type, amount, currency, transaction date, posting date, category, subcategory, description, merchant/source, notes, attachments, reference number, status, source, created_at, updated_at. | P0 |
| TXN-03 | Sources: manual, statement_import, system. The source field is an extensible enum, so a future integration source would not need a schema redesign. | P0 |
| TXN-04 | A transfer creates balanced entries on two accounts, and it is never counted as income or expense. | P0 |
| TXN-05 | A refund can link to an original expense. A linked refund reduces that category's spending and is not counted as income. An unlinked refund needs a category and still reduces spending. | P0 |
| TXN-06 | Edits create an audit revision (old value, new value, timestamp, device). Financial history is never silently overwritten. | P0 |
| TXN-07 | Deleting a posted transaction is a soft delete (tombstone + audit). Reports exclude it, and the audit trail keeps it. | P0 |
| TXN-08 | Reversal transactions reference the transaction they reverse and net it to zero. | P0 |
| TXN-09 | Duplicate prevention: idempotency key per creation; statement reference-number uniqueness per account where the statement provides one; row fingerprint (account + date + amount + normalized description) per import; fuzzy match (amount + date ±N days + similar description) flagged for user resolution, including imported rows that match manually entered transactions. | P0 |
| TXN-10 | Search and filter by date range, account, category, type, amount range, text. Results are paginated. | P0 |
| TXN-11 | Attachments (receipt images) stored locally, compressed, size-capped. | P1 |
| TXN-12 | Credit card purchases increase the card liability. A card payment is a transfer from the asset account to the card. | P0 |
| TXN-13 | Fast manual entry, since it is a primary data path: a recurring-style expense can be recorded in ≤ 3 taps after typing the amount (remembered last account/category, favorites/templates, merchant autocomplete from history), and the entry screen opens in ≤ 300 ms. An Android home-screen quick-add shortcut is provided. | P0 |

### 2.3 Categories (CAT)
| ID | Requirement | Pri |
|---|---|---|
| CAT-01 | Unlimited user categories plus one level of subcategory. Create, edit, archive, reorder, icon, color. | P0 |
| CAT-02 | Sensible PH default category set, editable, and the user can skip it at onboarding. | P0 |
| CAT-03 | User-defined categorization rules (match on merchant/description/amount → category). Rules suggest a category or apply automatically, as the user configures. | P1 |
| CAT-04 | Archived categories remain on historical transactions and in reports. | P0 |

### 2.4 Budgets (BUD)
| ID | Requirement | Pri |
|---|---|---|
| BUD-01 | Period types: weekly, biweekly, every N days, monthly (anchored on any day 1–31), **semi-monthly** (two days, e.g. 15th & end of month, matching common PH paydays; added in Phase 2), custom start/end. | P0 |
| BUD-02 | Period computation handles year transitions, leap years, and short months. Anchor day 29–31 clamps to the month's last day. Example: a monthly budget anchored on the 5th covers Nov 5 – Dec 4. | P0 |
| BUD-03 | Multiple independent budgets, each with its own period, categories, allocations, and rollover rules. | P0 |
| BUD-04 | Overlap rule: within one budget, a transaction is attributed to exactly one period (by transaction date in Asia/Manila or the user's timezone). Across budgets, one transaction may count in several budgets because each budget is an independent view. **Global reports never sum budgets together**, which prevents double counting. | P0 |
| BUD-05 | Allocation modes: fixed amount, percentage of budget income, manual; allocation adjustments mid-period with history. | P0 |
| BUD-06 | Income pool → categories / savings / unallocated, with "available to budget" displayed. | P0 |
| BUD-07 | Rollover per category: carry over, expire, sweep to a savings goal, manual reassign. Overspending carries as a negative amount when the user enables that. | P1 |
| BUD-08 | Budget vs actual per category and per period. | P0 |

### 2.5 Savings (SAV)
| ID | Requirement | Pri |
|---|---|---|
| SAV-01 | Goals: name, target, target date, allocated amount, contribution/withdrawal history, progress, notes, status. | P0 |
| SAV-02 | Allocating ₱X to a goal reduces "available to budget" by X and increases goal allocation by X. It leaves expense totals unchanged and is recorded as an allocation entry. | P0 |
| SAV-03 | A goal may be linked to one or more real accounts. Allocations are capped at the linked account balances, and net worth counts only the account balances, never the allocations on top of them. | P1 |
| SAV-04 | Withdrawals and reallocations require explicit confirmation and record previous/new allocation, amount, source, destination, date, reason, related transaction. | P0 |

### 2.6 Income & recurring (INC)
| ID | Requirement | Pri |
|---|---|---|
| INC-01 | Multiple income sources (salary, freelance, business, bonus, other). | P0 |
| INC-02 | Expected vs actual income tracked separately. Expected income never affects balances. | P1 |
| INC-03 | Recurring transaction templates generate *suggested* entries for the user to confirm. They never auto-post silently unless the user enables auto-post for a manual-source template. | P1 |

### 2.7 Debts, assets, net worth (DEBT / AST / NW)
| ID | Requirement | Pri |
|---|---|---|
| DEBT-01 | Debt types: personal loan, credit card, installment, borrowed money, other. Fields: original amount, outstanding, interest rate, schedule, due date, payment history, remaining installments, status. | P1 |
| DEBT-02 | A debt payment splits into principal (reduces the liability) and interest/fees (counted as expense). | P1 |
| DEBT-03 | A debt disbursement increases cash and the liability. It is not income. | P1 |
| AST-01 | User-entered asset records (investments, property, other) with manual valuation history. No automatic valuation claims. | P1 |
| NW-01 | Net worth = assets − liabilities, with the components shown separately. | P1 |
| NW-02 | Historical net worth from ledger history plus asset valuation snapshots. | P1 |

### 2.8 Bills & subscriptions (BILL)
| ID | Requirement | Pri |
|---|---|---|
| BILL-01 | Recurring obligations: amount, frequency, due date, account, category, reminder, status, payment history. | P1 |
| BILL-02 | A due bill is never marked paid automatically. Marking it paid creates or links a real transaction. | P1 |
| BILL-03 | Local notifications for reminders (best-effort; OS may delay). | P1 |

### 2.9 Reports (RPT)
| ID | Requirement | Pri |
|---|---|---|
| RPT-01 | Reports: income vs expense, spending by category, by account, budget vs actual, savings progress, cash flow, debt overview, recurring expenses, net worth, historical performance. | P0 (first 5) / P1 |
| RPT-02 | Ranges: weekly, monthly, quarterly, yearly, custom, and "budget period" (respects custom cycles). | P0 |
| RPT-03 | Every figure is computed by the deterministic domain layer, and the report screen can show which transactions make up each total. | P0 |
| RPT-04 | Export to CSV (and PDF summary in P1). | P1 |

### 2.10 Statement / file import (IMP): the only non-manual data path
| ID | Requirement | Pri |
|---|---|---|
| IMP-01 | CSV import with column mapping, saved mapping profiles per institution. | P0 |
| IMP-02 | Excel (.xlsx, and .xls where a reliable parser exists) import with sheet/column mapping. | P0 |
| IMP-03 | PDF import only for formats with a verified, tested parser. Each parser declares the statement layouts it supports, and unsupported PDFs are rejected with a clear message. Raised from P2 because file import is now the only automated path, and some institutions may deliver statements only as PDF (the GCash emailed history format is **unverified**). The layouts must be confirmed from real samples. | P1 |
| IMP-04 | Workflow: select → detect format → parse → preview → map → duplicate check → categorize → confirm → import → import history. Nothing is written before the user confirms. | P0 |
| IMP-05 | Each import batch is recorded and can be rolled back as a unit (soft-delete with audit). | P0 |
| IMP-06 | Parse errors are shown per row, and invalid rows are excluded rather than imported partially. | P0 |
| IMP-07 | Files are opened through the Android Storage Access Framework (share sheet / file picker), with no broad storage permission. They are processed on-device only and never uploaded. | P0 |
| IMP-08 | Password-protected PDF statements: the user types the password, which is used in memory only and never stored. | P1 |
| IMP-09 | Re-importing an overlapping date range creates no duplicates (see TXN-09). The import summary shows new / duplicate / rejected counts. | P0 |
| IMP-10 | Optional reconciliation after import: if the statement has a closing balance, compare it with the calculated balance and offer ACC-06 adjustment. | P1 |

### 2.11 External integrations (INT): **removed by owner decision, 2026-11-05**
Direct bank/e-wallet APIs and aggregators are out of scope (ADR-0006). These constraints remain in force:

| ID | Requirement | Pri |
|---|---|---|
| INT-02 | The app never asks for, stores, or automates bank/e-wallet credentials or OTPs, and never scrapes institution apps or websites. | P0 (constraint) |
| INT-06 | The UI and store listing must not imply automatic bank syncing. Accounts are described as "updated by manual entry or import". | P0 (constraint) |

INT-01, INT-03, INT-04, and INT-05 are withdrawn.

### 2.12 Local-first, backup, sync (LOC / BAK / SYNC)
| ID | Requirement | Pri |
|---|---|---|
| LOC-01 | All core features work fully offline with no account sign-up ("local-only mode"). | P0 |
| LOC-02 | Encrypted local database. The key is held in the OS keystore (Android Keystore; DB key wrapped by a Keystore key). | P0 |
| LOC-03 | Every multi-row financial write is atomic (single DB transaction). | P0 |
| LOC-04 | Versioned, forward-only migrations with pre-migration automatic backup. | P0 |
| BAK-01 | Manual encrypted export/backup file (user-held passphrase) and restore. | P0 |
| ~~BAK-02~~ | ~~Optional cloud backup, end-to-end encrypted.~~ **Removed 2026-11-06 (ADR-0012).** | — |
| ~~SYNC-01~~ | ~~Optional multi-device sync.~~ **Removed 2026-11-06 (ADR-0012).** Moving to a new phone = restore a backup file. | — |
| ~~SYNC-02~~ | ~~Sync conflict handling.~~ **Removed 2026-11-06 (ADR-0012).** | — |

### 2.13 Security & privacy (SEC / PRIV)
| ID | Requirement | Pri |
|---|---|---|
| SEC-01 | App lock: PIN plus optional biometrics (AndroidX BiometricPrompt), with auto-lock timeout and `FLAG_SECURE` hiding content in recents and screenshots (user-toggleable). | P0 |
| SEC-02 | No secrets in the repo. AI provider secrets live only server-side. | P0 |
| SEC-03 | TLS 1.2+ for all network traffic (Network Security Config: no cleartext). Certificate pinning for the app's own backend. | P1 |
| SEC-04 | Audit log of financial mutations and security events (local). | P0 |
| SEC-05 | Dependency vulnerability scanning in CI. | P0 |
| PRIV-01 | Data export (full JSON/CSV) and full data deletion (local + cloud). | P0 / P1 |
| PRIV-02 | Data minimization: no analytics SDKs collecting financial data, and crash reports scrubbed of financial values. | P0 |
| PRIV-03 | Privacy notice aligned with RA 10173. Legal review is required before public release (see risk R-07). | P0 |

### 2.14 AI assistant (AI)
| ID | Requirement | Pri |
|---|---|---|
| ~~AI-01~~ | Replaced by INS-01..03 (ADR-0012). | — |
| ~~AI-02~~ | Replaced by INS-01..03 (ADR-0012). | — |
| ~~AI-03~~ | Replaced by INS-01..03 (ADR-0012). | — |
| ~~AI-04~~ | Replaced by INS-01..03 (ADR-0012). | — |
| INS-01 | On-device insights computed by deterministic code from the user's records: spending change vs. the previous period by category, unusual transactions, largest categories, bills due soon vs. free money, estimated saving capacity. No network. | P1 |
| INS-02 | Every insight shows the figures it is based on; estimates and projections are labeled as estimates. | P1 |
| INS-03 | Insights never create, modify or delete records. | P1 |

### 2.15 UX (UX)
| ID | Requirement | Pri |
|---|---|---|
| UX-01 | Light and dark themes; design tokens; reusable components. | P0 |
| UX-02 | Bottom navigation (initial: Home, Transactions, Budgets, Accounts, More), to be validated with usability tests. | P0 |
| UX-03 | Dashboard shows account balances, available to budget, income, expenses, savings allocated, budget utilization, upcoming bills, recent transactions, goals, net worth. **Each is a separate figure, and figures of different kinds are never combined into one total.** | P0 |
| UX-04 | Accessibility: touch targets ≥ 48dp, dynamic font scaling to 200% without truncating money values, WCAG AA contrast, screen-reader labels on amounts. | P0 |
| UX-05 | Every data screen has loading, empty, and error states. | P0 |
| UX-06 | PHP formatting: `₱1,234.56`; negative `−₱1,234.56`; dates `Nov 5, 2026`; timezone Asia/Manila by default. | P0 |

---

## 3. Non-functional requirements

| ID | Requirement | Target | How measured |
|---|---|---|---|
| NFR-01 | Monetary precision | Integer minor units (centavos), **no floating point** anywhere in money paths | Lint rule + property-based tests |
| NFR-02 | Cold start (reference mid-range Android, 4 GB RAM) | ≤ 2.0 s to interactive dashboard with 50k transactions | Release build, profiled, median of 10 runs |
| NFR-03 | Transaction list | 60 fps scroll with 100k transactions; first page ≤ 150 ms | Perf test on device |
| NFR-04 | Monthly report query | ≤ 200 ms with 100k transactions | Automated benchmark on DB layer |
| NFR-05 | Write latency | Create transaction ≤ 50 ms (DB commit) | Benchmark |
| NFR-06 | Memory | ≤ 200 MB RSS steady state on Android | Android Studio profiler |
| NFR-07 | Install size | Android APK/AAB download ≤ 40 MB | Store build report |
| NFR-08 | Battery/data | No background work more often than every 6 h by default; sync payloads incremental | Code review + OS battery stats |
| NFR-09 | Platform | **Android only**: Android 8.0 (API 26)+ *(provisional, see Q-12)*; phones; portrait-first, landscape supported | CI + device matrix (API 26, 30, latest) |
| NFR-10 | Durability | Zero data loss on kill during write (verified by crash-injection tests) | Integration tests |
| NFR-11 | Test coverage | Domain/financial layer ≥ 95% line and 100% of listed integrity scenarios | CI coverage report |

---

## 4. Acceptance criteria (milestone-level)

Each criterion is testable. The test ID column shows where its test will live once written.

### AC-CORE: Core financial engine (Phase 4 exit)
1. A ₱2,000.00 transfer GCash→BPI changes the two balances by −2,000.00 and +2,000.00. Income, expense, and net worth are unchanged. *(test: `domain/transfer.spec`)*
2. Sum of all account balances = Σ opening balances + Σ income − Σ expense + Σ refunds + Σ adjustments + Σ debt disbursement − Σ debt principal paid (ledger invariant), verified by property-based test over 10,000 random operation sequences.
3. Allocating ₱5,000 to a savings goal reduces available-to-budget by exactly ₱5,000, increases the goal by ₱5,000, and leaves expense totals unchanged.
4. Splitting ₱100.00 across 3 categories by percentage (33.33/33.33/33.34 %) produces centavo amounts that sum to exactly ₱100.00 (largest-remainder rounding, deterministic order).
5. Budget anchored on day 31: periods are Jan 31–Feb 27, Feb 28–Mar 30 … (and Feb 29 in leap years), with no gaps or overlaps across 10 years of generated periods.
6. A transaction dated 2026-12-04T23:59 Asia/Manila belongs to the Nov 5–Dec 4 period. One dated 2026-12-05T00:00 belongs to the next period.
7. A refund linked to an expense reduces that category's spend, and the income total does not change.
8. Editing a transaction produces an audit revision, and reports reflect the new value only.
9. Killing the process mid-way through a multi-entry write leaves either all entries or none (crash-injection test).

### AC-IMP: Statement import (Phase 6 exit)
1. Importing the same CSV twice creates zero new transactions on the second run, with every row flagged as a duplicate.
2. A malformed amount row is rejected with its row number. Valid rows are importable and the invalid row is never partially written.
3. Rolling back an import batch restores all balances to their pre-import values.
4. Importing a statement whose rows overlap with manually entered transactions flags the overlaps as possible duplicates, and nothing is merged without user confirmation.
5. CSV and XLSX exports of the same statement produce identical normalized transactions.

### AC-SEC: Security baseline (Phase 3 exit)
1. Database file is unreadable without the keystore-held key (verified by opening it with a stock SQLite build).
2. With `FLAG_SECURE` enabled, the recents screen and screenshots show no financial data.
3. Repository secret scan passes in CI.

### ~~AC-SYNC: Sync~~ (removed 2026-11-06, ADR-0012; kept for history)
1. Two devices offline each add 100 transactions, then sync. Both end with 200 transactions and identical balances.
2. Concurrent edits to the same transaction's amount produce a visible conflict, not a silent overwrite.
3. Server database contains no plaintext financial fields (verified by inspection test).
4. Interrupting sync at a random point and resuming yields the same final state as an uninterrupted sync.

---

## 5. Assumptions (explicit)

| ID | Assumption | Impact if wrong |
|---|---|---|
| A-01 | Users accept manual entry plus statement import as the **only** data paths (owner decision). Fast manual entry is therefore a first-class UX goal. | Product positioning, onboarding |
| A-02 | The product is free in local-only mode, and cloud features may later carry a cost. | Backend scope |
| A-03 | Single-currency per account; multi-currency totals require a user-entered or provider FX rate and are labeled as converted. | Data model already supports currency per account |
| A-04 | One subcategory level is sufficient. | Category model |
| A-05 | The owner will provide a Google Play developer account and at least one physical Android test device. | Release / device testing blocked |
