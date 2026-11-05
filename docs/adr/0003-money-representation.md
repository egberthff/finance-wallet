# ADR-0003: Integer minor units in Long with exact arithmetic

- **Status:** **Accepted** (2026-11-05). Approved by owner with Phase 1
- **Requirements:** NFR-01, AC-CORE-4

## Context
CLAUDE.md forbids floating point for money.

## Decision
Persist INTEGER minor units, with precision taken from an ISO 4217 table. The domain type is `Money(minor: Long, currency)`, and all arithmetic uses Math.addExact/multiplyExact so overflow throws. Percentages are integer basis points. Where fractions are unavoidable (interest), use BigDecimal with an explicit scale and HALF_EVEN, converted back at a single boundary. Splits use largest-remainder rounding with a deterministic tie-break. A detekt rule forbids Double/Float in money code.

## Alternatives considered
BigDecimal everywhere (correct, slower, more allocation). Double (forbidden).

## Consequences
- **Pro:** Exact, fast, overflow-safe.
- **Con:** Requires discipline at the BigDecimal boundary, covered by property tests.
