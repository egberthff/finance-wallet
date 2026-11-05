# Architecture Decision Records

| ADR | Title | Status |
|---|---|---|
| [0001](0001-mobile-framework.md) | Native Android with Kotlin and Jetpack Compose | Accepted |
| [0002](0002-local-database.md) | Room on SQLite with SQLCipher encryption | Accepted |
| [0003](0003-money-representation.md) | Integer minor units in Long with exact arithmetic | Accepted |
| [0004](0004-ledger-model.md) | Double-entry money ledger plus separate allocation ledger | Accepted |
| [0005](0005-backend.md) | Optional backend for backup, sync and AI only: Ktor + PostgreSQL | Accepted |
| [0006](0006-integration-strategy.md) | No direct integrations: data enters by manual entry and file import only | Accepted |
| [0007](0007-sync-model.md) | E2E-encrypted operation log with deterministic client-side merge | Accepted |
| [0008](0008-repo-and-testing.md) | Single Gradle multi-module build; JUnit 5 + Kotest properties; Maestro E2E | Accepted |
| [0009](0009-scope-android-only.md) | Android is the only target platform | Accepted |
| [0010](0010-no-network-permission-mvp.md) | The MVP declares no INTERNET permission | Accepted |
| [0011](0011-backup-format-and-kdf.md) | Logical, chunked AES-256-GCM backup with Argon2id | Accepted |

Statuses: Proposed -> Accepted -> Superseded by ADR-XXXX. Changing an Accepted ADR requires a new ADR.
Revision 1 (React Native/TypeScript stack) was proposed and never accepted. It was replaced in place by revision 2 after the 2026-11-05 scope change.
