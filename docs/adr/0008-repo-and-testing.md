# ADR-0008: Single Gradle multi-module build; JUnit 5 + Kotest properties; Maestro E2E

- **Status:** **Accepted** (2026-11-05). Approved by owner with Phase 1
- **Requirements:** QA section, NFR-11

## Context
Testing is mandatory, and financial integrity needs property-based testing.

## Decision
One Gradle (Kotlin DSL) build with a version catalog and modules :domain, :data, :importers, :app (later :server). Domain tests use JUnit 5 + Kotest property testing. Data tests use Robolectric + in-memory Room + MigrationTestHelper. UI uses Compose UI tests, and E2E uses Maestro on a physical Android device. Static analysis is detekt (with a money-safety rule), ktlint and Android Lint. CI is GitHub Actions (assumed) with a coverage gate (Kover), dependency scanning and gitleaks.

## Alternatives considered
More granular feature modules (unneeded at this size). Espresso-only E2E (Maestro is simpler for user journeys).

## Consequences
- **Pro:** Standard Android tooling.
- **Con:** Gradle builds need several GB of disk on a machine with 26 GB free (R-09).
