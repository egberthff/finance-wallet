# ADR-0006: No direct integrations: data enters by manual entry and file import only

- **Status:** **Accepted** (2026-11-05). Owner decision
- **Requirements:** IMP-01..10, INT-02, INT-06, TXN-13, R-01

## Context
Research (docs/03-technical-feasibility.md section 3) found no public consumer-data API for GCash, Maya, BDO, BPI or GoTyme, and any other route needs commercial agreements. On 2026-11-05 the owner dropped direct API integration.

## Decision
The app has no bank/e-wallet or aggregator connections. Data paths are (1) fast manual entry and (2) on-device file import (CSV, XLSX, and PDF for verified layouts), with preview, duplicate detection, confirmation and batch rollback. The transaction `source` enum stays extensible, so this decision could be revisited later without a schema redesign, but no provider code is written.

## Alternatives considered
Aggregator/partner integrations (blocked by access, contracts, and RPA concerns). Screen scraping (forbidden).

## Consequences
- **Pro:** No third-party data processors, smaller regulatory surface, no network needed for core use.
- **Con:** More user effort, so manual-entry speed (TXN-13) and import quality become critical.
- **Con:** Product messaging must not imply automatic syncing (INT-06).
