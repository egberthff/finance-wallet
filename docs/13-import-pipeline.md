# Statement Import Pipeline

| Status | Phase 2 draft v1 (2026-11-05) |
|---|---|
| Requirements | IMP-01..10, TXN-09, AC-IMP-1..5 |
| Modules | `:importers` (pure parsing/normalization, JVM-testable), `:data` (UC-26..28) |

## 1. Stages

```
1 Select   → 2 Guard   → 3 Detect  → 4 Parse   → 5 Normalize → 6 Validate
→ 7 Match duplicates → 8 Categorize → 9 Preview & confirm → 10 Commit → 11 Reconcile (optional)
```
Stages 2–8 are pure functions of (file bytes, profile, account, existing-data snapshot), and **nothing is written** before
stage 10. The preview session lives in memory (ViewModel-scoped) and is discarded when the user cancels or the process dies.

## 2. Select & guard (IMP-07)
- The file is opened with `ACTION_OPEN_DOCUMENT` (Storage Access Framework) or received through `ACTION_SEND`/`ACTION_VIEW`. No storage permission is requested.
- Limits (reject with a clear message):

| Limit | Value |
|---|---|
| File size | ≤ 20 MB |
| CSV rows | ≤ 50,000 |
| XLSX: zip entries / total uncompressed size / compression ratio | ≤ 200 / ≤ 100 MB / ≤ 100:1 (zip-bomb guard) |
| XLSX XML | DTDs and external entities disabled |
| PDF pages | ≤ 300 |
| Parse time | 60 s wall clock, cancellable |

- SHA-256 of the file is computed. If a COMMITTED batch with the same hash exists for this account, the user is told "this
  exact file was imported on {date}" and may continue (rows would all be duplicates anyway).

## 3. Detect
- `Detector` tries built-in **verified** profiles first, then the user's saved profiles.
  - CSV/XLSX: header-row signature, i.e. a required set of normalized column names in order, plus optional marker cells.
  - PDF: text markers on page 1 (institution name, statement title, column header line).
- No match → generic mapping (§4.3). Detection **fails closed**: a partial match is reported as no match, never as a guess.

## 4. Parse
### 4.1 CSV
Apache Commons CSV. Encoding is detected from the BOM, then trial-decoded as UTF-8; on failure Windows-1252 is used, and the
encoding is shown in the preview. The delimiter is sniffed from `, ; \t |` using consistency across the first 20 rows.
Quoted newlines are supported.

### 4.2 XLSX
fastexcel-reader in streaming mode, with the sheet chosen by the user (default: the first non-empty one). Cell types: numbers
are read as their **exact decimal string**, and Excel serial dates are converted with the 1900 date system (1904 if the workbook
flag is set). Formulas use the cached value; a formula without a cached value rejects the row.
`.xls` (BIFF) is not supported unless a Phase 3 spike finds a reliable, small library. The user is told to re-save as XLSX or CSV.

### 4.3 Column mapping (generic or saved profile)
Mappable target fields: `date` (required), `posting_date`, `description` (required), `merchant`, `reference_no`, and amount as
**one** of:
- (a) `amount` signed column (sign convention: debit negative / debit positive, chosen),
- (b) separate `debit` and `credit` columns,
- (c) `amount` + `type` column with a value→direction map.

Optional `balance` (running balance, used for validation §6) and `status`.
Format settings: date pattern(s) (e.g., `MM/dd/yyyy`, `dd/MM/yyyy`, `yyyy-MM-dd`, `MMM d, yyyy`), decimal and grouping
separators, currency symbol stripping, header row index, rows to skip at top/bottom.
**Ambiguous dates** (e.g., `03/04/2026` when both day-first and month-first parse for every row) require the user to choose. The app never assumes.
A mapping can be saved as a user `import_profile` (builtin = 0).

### 4.4 PDF (P1; verified layouts only)
PdfBox-Android text extraction with position-aware line grouping, then a layout-specific row grammar per built-in profile.
- Password-protected PDFs: the password is prompted, held in a `CharArray`, and wiped after open (IMP-08). It is never logged or stored.
- Scanned (image-only) PDFs are rejected: "This PDF has no text layer and can't be read reliably."
- A PDF profile is **only** shipped after the verification checklist in [integrations/README.md](integrations/README.md) passes.

## 5. Normalize & fingerprint
Each parsed row becomes:
```kotlin
data class NormalizedRow(
  val rowNo: Int, val txnDate: LocalDate, val postingDate: LocalDate?,
  val amount: Money,            // signed: + money in, − money out (account perspective)
  val description: String, val descriptionNorm: String,
  val merchant: String?, val referenceNo: String?, val runningBalance: Money?, val pending: Boolean)
```
Amount parsing is exact: `BigDecimal(text)` with scale ≤ the currency's minor digits, otherwise the row is rejected (`AMOUNT_PRECISION`).

**Fingerprint** (idempotent re-import, TXN-09):
```
key        = accountId | txnDate | amountMinor | descriptionNorm
ordinal    = 1-based index of this row among rows in the same file with the same key (file order)
fingerprint = SHA-256( "v1" | key | ordinal )   (hex)
```
If `referenceNo` is present and the profile marks it as unique, the fingerprint is instead `SHA-256("v1ref" | accountId | referenceNo)`.

Assumption **A-IMP-1**: statements contain whole days, so two overlapping statements list the same rows of a shared day in the
same relative order and produce the same ordinals. Where that fails (partial days at a statement edge), stage 7's fuzzy match
still flags the row for the user. Phase 6 tests this with overlapping fixture files.

## 6. Validate (IMP-06)
Per row, rejected with a code and message (the row is excluded and shown):
`DATE_UNPARSEABLE`, `DATE_AMBIGUOUS` (until resolved), `DATE_OUT_OF_RANGE` (before 1990-01-01 or more than 1 year in the future),
`AMOUNT_UNPARSEABLE`, `AMOUNT_PRECISION`, `AMOUNT_ZERO`, `DEBIT_AND_CREDIT_BOTH_SET`, `DESCRIPTION_EMPTY`, `CURRENCY_MISMATCH`.
File-level checks (warnings, shown before the row list):
- **Running-balance continuity:** if a balance column exists, for consecutive rows `bal[i] = bal[i−1] + amt[i]` (respecting the file's
  order direction). A break warns "rows may be missing or misread around row N".
- **Statement totals:** if the profile extracts totals or a closing balance, compare with Σ parsed amounts.

## 7. Duplicate matching (AC-IMP-1, AC-IMP-4)
Each valid row gets exactly one classification:

| Class | Rule | Default action |
|---|---|---|
| `EXACT_DUPLICATE` | fingerprint exists in `txn_fingerprint` for this account | Skip (not changeable, except "import anyway as new", which needs confirmation and creates a new fingerprint ordinal) |
| `PROBABLE_DUPLICATE` | a non-deleted txn on this account with the same amount, \|date diff\| ≤ 3 days, and description similarity ≥ 0.6 (token Jaccard on normalized text) **or** same reference no., that is not already linked to a fingerprint | The user chooses: *link* (mark existing txn as matched; no new txn; fingerprint attached to the existing txn), *import as new*, or *skip* |
| `NEW` | otherwise | Import |

The probable-match search runs over a bounded window (account entries in [minDate − 3, maxDate + 3]) with `ix_entry_account`.
A candidate existing transaction can be matched to at most one row per batch, choosing the closest date, then the highest similarity.

## 8. Categorize
For NEW rows: categorization rules (domain §10) in priority order, then **merchant history** (the most frequent category for the
same normalized merchant/description on this account in the last 12 months), then `uncategorized`. The source of each
suggestion is shown in the preview. Transaction type is inferred from the sign (+ INCOME, − EXPENSE), and the user can change a row to TRANSFER (choosing the counter-account).

## 9. Preview & confirm (IMP-04)
The preview shows counts (new / duplicate / probable / rejected), date range, Σ in, Σ out, and the per-row list with edit
controls (date, description, category, type, include/exclude). Nothing is committed until the user taps **Import N
transactions**. If the user leaves, the session is discarded.

## 10. Commit (UC-27)
One Room transaction:
1. Re-check the account's `version` against the preview session. If it changed, re-run stage 7 and show the preview again.
2. Insert `import_batch` (COMMITTED, counts), `import_row` for every row (outcome, txn id / matched txn id, error code).
3. For each imported row: `txn` (source = STATEMENT_IMPORT, `import_batch_id`), entries, `txn_revision`(CREATE), `txn_fingerprint`.
4. For linked probable duplicates: `txn_fingerprint` → existing txn; `import_row.matched_txn_id`.
5. Update `financial_account.last_imported_at`; write `audit_event(IMPORT_COMMITTED)`.
A failure anywhere rolls back everything (NFR-10).

## 11. Rollback (UC-28, AC-IMP-3)
One transaction: soft-delete every txn created by the batch (revision `IMPORT_ROLLBACK`), delete its fingerprints (including links made to
pre-existing txns), set the batch to ROLLED_BACK, and write an audit event. Before confirming, transactions from the batch that were
edited after import are listed. Balances return to their pre-import values, which is verified by test.

## 12. Reconcile (IMP-10)
If the statement provides a closing balance and end date, offer UC-13 with those values prefilled.

## 13. Built-in profiles roadmap
| Profile | Prerequisite |
|---|---|
| Generic CSV, generic XLSX | none (P0, Phase 6) |
| GCash, Maya, BPI, BDO, UnionBank, GoTyme | ≥ 2 real redacted samples each (Q-11); verification checklist |

Test fixtures are **synthetic files that mimic a verified layout** (no real customer data in the repo). The real samples used for
verification are kept outside the repository, and only their structural description is committed.
