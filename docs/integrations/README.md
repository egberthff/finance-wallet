# Integration Status Register

**Direct bank/e-wallet/aggregator integrations are out of scope** (owner decision 2026-11-05, [ADR-0006](../adr/0006-integration-strategy.md)).
The app makes **no** connection to any financial institution. This register now tracks **file-import support** per institution.

A profile may be marked **Verified** only after parser fixture tests pass against **real (redacted) sample files** from that
institution. A profile without real samples stays **Not started**, and users fall back to generic CSV/XLSX column mapping.

_Last reviewed: 2026-11-05_

| Institution | Export formats offered (to confirm with real samples) | Import profile status | Notes |
|---|---|---|---|
| GCash | Emailed transaction-history request (format unverified); in-app history ≈ 90 days per GCash Help | Not started; needs samples | [GCash Help](https://help.gcash.com/hc/en-us/articles/360034155433) |
| Maya | Unverified | Not started; needs samples | — |
| BDO | Unverified | Not started; needs samples | — |
| BPI | Unverified | Not started; needs samples | — |
| UnionBank | Unverified | Not started; needs samples | — |
| GoTyme | Unverified | Not started; needs samples | — |
| Generic CSV / XLSX | Any | Planned (Phase 6, P0) | Manual column mapping; saved as a user profile |

## Import profile verification checklist
1. At least 2 real redacted sample files per layout (ideally different months, including year boundary).
2. Detection signature (headers/text markers) that rejects non-matching files.
3. Field mapping: date (+ timezone assumption), amount sign convention, debit/credit columns, reference number, balance column.
4. Encoding / delimiter / date format / thousands separator documented.
5. Fixture tests: every row parsed, totals match the statement's own totals or closing balance.
6. Known limitations documented (e.g., history depth, password protection, multi-currency).
7. Layout version recorded so that format changes are detected.
