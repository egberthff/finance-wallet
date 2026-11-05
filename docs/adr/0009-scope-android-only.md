# ADR-0009: Android is the only target platform

- **Status:** **Accepted** (2026-11-05). Owner decision
- **Requirements:** NFR-09, PRD 1.2

## Context
The original instructions targeted Android and iOS. iOS cannot be built on the Windows development machine and would need cloud macOS builds, an Apple account, and duplicate QA. On 2026-11-05 the owner dropped iOS.

## Decision
Target Android phones only (provisional minSdk 26). Remove iOS from requirements, testing, release and tooling. Keep the financial domain free of Android dependencies so a future platform is not structurally blocked.

## Alternatives considered
Keep iOS (higher cost and an extra toolchain). Defer iOS but keep cross-platform tooling (pays the cross-platform cost with no current benefit).

## Consequences
- **Pro:** Lower cost and complexity; enables native Kotlin (ADR-0001 rev. 2).
- **Con:** iOS users unsupported.
