# Milestone Plan: Phase 6, Statement Import

| Status | **Implemented (2026-11-06)**: steps 6.1–6.5 with JVM/Robolectric evidence for AC-IMP-1..5 and criteria 6–8 (PROGRESS.md). Not built: share-sheet entry (ACTION_SEND/VIEW). Device checks reserved for Phase 8. |
|---|---|

## 1. Objectives
Let users bring bank and e-wallet history in from files, safely: generic CSV and XLSX import with column mapping and saved
profiles, an exact preview before anything is written, duplicate protection on re-import and against manual entries,
per-batch rollback, import history, categorization rules, and optional reconciliation against the statement's closing balance.
Design: [13-import-pipeline.md](../13-import-pipeline.md). Requirements IMP-01..10, CAT-03, AC-IMP-1..5.

## 2. Scope (in build order)
| Step | Deliverable | Reqs |
|---|---|---|
| 6.1 | New pure-JVM module `:importers`: file guard (size, rows, zip-bomb limits), encoding detection, RFC 4180 CSV reader with delimiter sniffing, streaming XLSX reader (shared strings, inline strings, numbers as exact decimal text, 1900/1904 dates, cached formula values), PDF detection that rejects unsupported layouts | IMP-01/02/03/07 |
| 6.2 | `:importers` mapping and normalization: header-based mapping suggestion, three amount modes (signed, debit/credit, amount + type), date patterns with ambiguity detection, exact amount parsing, row validation codes, running-balance continuity, fingerprints | IMP-01/06, TXN-09 |
| 6.3 | Schema v4: `import_profile`, `import_batch`, `import_row`, `txn_fingerprint`, `category_rule`, triggers (incl. the deferred references `txn.import_batch_id`, `reconciliation.import_batch_id`) | LOC-04 |
| 6.4 | `:data` use cases: UC-26 preview (no writes: exact and probable duplicates, categorization by rules then merchant history), UC-27 commit (one transaction, account-version check), UC-28 rollback, import history, saved profiles, UC-15 categorization rules, IMP-10 reconciliation hand-off | IMP-04/05/09/10, CAT-03 |
| 6.5 | UI: file picker (SAF), account choice, mapping screen with ambiguous-date choice, preview with counts and per-row decisions, import history with rollback, rules screen | IMP-04/07 |

## 3. Decisions and deviations (to approve with this plan)
- **No PDF layouts ship in Phase 6.** IMP-03 allows PDF import only for layouts verified against real redacted samples, and none
  exist yet (Q-11). Every PDF is detected and rejected with a clear message and a suggestion to export CSV/XLSX. PdfBox
  (+6 MB, docs/spikes/apk-size.md) is not added until a first layout is verified.
- **In-house CSV reader instead of Apache Commons CSV.** Commons CSV 1.14.1 needs commons-io and commons-codec at runtime
  (about 0.9 MB, with Java APIs that are risky on minSdk 26). RFC 4180 is a small grammar; the in-house reader is covered by
  edge-case and round-trip property tests against the app's own CSV writer. Same reasoning as the in-house XLSX reader.
- **XML parsing** uses the platform `XmlPullParser` on Android (DTD processing off). JVM tests use kxml2, the implementation
  Android itself is based on; neither is packaged into the APK (`compileOnly` / test only).
- **Built-in institution profiles** (GCash, Maya, BPI, BDO, UnionBank, GoTyme) stay "Not started" until samples arrive. Users map
  columns once and save the mapping as their own profile.
- **Attachments** (TXN-02 field) remain out of this phase.

## 4. Dependencies
Phase 5 (done). Real redacted sample statements for built-in profiles (open question Q-11) — not blocking; generic mapping covers all CSV/XLSX files.

## 5. Risks
| Risk | Mitigation |
|---|---|
| Misread amounts or dates import wrong money | Exact decimal parsing, no rounding; ambiguous dates require a user choice; per-row rejection codes; preview before commit; running-balance check |
| Duplicates on re-import or against manual entries | Fingerprints (exact) and a bounded fuzzy match (probable) that never merges without the user |
| Malicious files (zip bombs, XXE, huge rows) | Guard limits measured while streaming, DTDs disabled, cancellable parse with a time limit |
| A bad import pollutes the ledger | Whole-batch rollback in one transaction, verified to restore balances |
| Statement layout changes | Saved profiles record the header signature; a changed file fails detection closed and falls back to manual mapping |

## 6. Acceptance criteria
AC-IMP-1..5 (PRD) as automated tests, plus:
6. File guard limits and XLSX zip-bomb/XXE protections rejected by tests with crafted files.
7. Ambiguous day/month dates are never guessed.
8. `gradlew check assembleDebug` passes; `:importers` line coverage ≥ 95 %; IC-1..IC-8 hold after every import test.

Device-only (Phase 8): SAF picker and share-sheet behaviour, large-file performance on the reference device.

## 7. Deliverables
Code and tests for the steps above, schema v4 notes, updated integrations register, PROGRESS.md.
