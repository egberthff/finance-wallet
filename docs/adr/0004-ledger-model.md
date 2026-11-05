# ADR-0004: Double-entry money ledger plus separate allocation ledger

- **Status:** **Accepted** (2026-11-05). Approved by owner with Phase 1
- **Requirements:** TXN-04/05/12, SAV-02/03, BUD-04/06, NW-01, AC-CORE-1..3,7

## Context
Transfers, savings allocations, refunds, card payments and overlapping budgets are the main sources of double counting in PFM apps.

## Decision
Every transaction is made of zero-sum entries across real accounts and internal nominal accounts (income:*, expense:*, equity:*). Spending is defined as the sum of expense:* entries. A separate allocation ledger records budget and savings-goal earmarks, and net worth reads only the money ledger. Balances are always derived from entries (a cached summary is allowed only if it is verified against recompute).

## Alternatives considered
Single-entry signed amounts with a type flag (simpler, but every report must special-case transfers and savings, which is error-prone).

## Consequences
- **Pro:** Invariants checkable mechanically.
- **Pro:** Transfers cannot leak into spending.
- **Con:** Slightly more complex schema; the UI hides double-entry from users.
