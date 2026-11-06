# ADR-0012: No cloud features; on-device insights instead of an external AI assistant

- **Status:** **Accepted** (2026-11-06). Owner decision at the start of Phase 7
- **Supersedes:** [ADR-0005](0005-backend.md) (optional backend), [ADR-0007](0007-sync-model.md) (sync model)
- **Requirements affected:** BAK-02 and SYNC-01/02 (removed), AI-01..04 (replaced), AC-SYNC (removed); BAK-01, PRIV-01 unchanged

## Context
Phase 7 was planned as encrypted backup, optional cloud backup, multi-device sync and an optional AI assistant. Cloud backup,
sync and an external AI provider all need the INTERNET permission (excluded by ADR-0010), a server that the owner would host and
pay for (ADR-0005), and a privacy assessment for a data processor. The owner was asked and decided (2026-11-06):
- **Drop cloud features:** no cloud backup and no multi-device sync, now or as a planned phase.
- **AI:** on-device insights only, with no AI provider and no network.

## Decision
1. The app stays fully offline. ADR-0010 (no INTERNET permission) remains in force for every release, not only the MVP.
2. Backup is the encrypted `.fwbak` file of ADR-0011, created and restored through the Storage Access Framework. Moving to a new
   phone is done by restoring a backup file. The user decides where the file goes (local storage, USB, or a cloud drive of their
   choice through the system picker); the app itself never uploads anything.
3. No server is built. ADR-0005 and ADR-0007 are superseded. The sync-ready columns (`version`, `hlc`, `updated_by_device`)
   stay, because they are used for optimistic locking and audit and cost nothing.
4. "Financial insights" are computed by deterministic code on the phone from the user's own records: spending changes against
   the previous period, unusual transactions, the largest categories, bills due soon against free money, and an estimated monthly
   saving capacity. Estimates are labeled as estimates. Nothing is sent anywhere. Insights never create, change or delete records.

## Alternatives considered
- Build the Ktor + PostgreSQL backend now (owner declined: hosting cost, operations, privacy review).
- Keep cloud features as a future phase (owner chose to remove them from scope).
- External AI with consent through a server proxy (owner declined; depends on the server).

## Consequences
- **Pro:** The strongest privacy claim holds permanently: the app cannot transmit financial data. Simpler Play Data-safety answers.
- **Pro:** No operating cost and no server attack surface.
- **Con:** No automatic off-device backup; users must keep their own backup files (the app reminds them, R-10).
- **Con:** No natural-language questions; insights answer a fixed set of questions.
- Removed from the PRD: BAK-02, SYNC-01, SYNC-02, AC-SYNC. AI-01..04 are replaced by INS-01..03.
