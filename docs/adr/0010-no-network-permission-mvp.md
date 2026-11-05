# ADR-0010: The MVP declares no INTERNET permission

- **Status:** **Accepted** (2026-11-05). Approved by owner with Phase 2
- **Requirements:** LOC-01, PRIV-02, SEC-02, INT-02, T5 in 16-security-architecture.md

## Context
With no integrations (ADR-0006) and cloud features deferred to Phase 7, no MVP feature needs the network. Any network
capability is attack surface and a privacy risk, including from third-party libraries.

## Decision
The MVP release manifest contains **no** `android.permission.INTERNET`. CI checks the merged release manifest and fails if the permission
appears (libraries can add it through manifest merging; it is removed with `tools:node="remove"` and verified).
Consequences:
- no cloud crash reporting (crash logs stay on-device and are shared only by explicit user action, as a file via the share sheet);
- no remote config or analytics;
- the Play Data-safety form can truthfully state that no data is collected or shared.
Phase 7 cloud features, if approved, add the permission through a new ADR.

## Alternatives considered
Allow INTERNET for crash reporting only: better crash visibility, but it weakens the strongest privacy guarantee and requires a data-processing disclosure.

## Consequences
- **Pro:** A verifiable claim that the app cannot transmit financial data.
- **Pro:** Simpler privacy review.
- **Con:** Less production crash visibility; it depends on user-shared logs plus Play Console's Android vitals, which the OS collects without app code.
