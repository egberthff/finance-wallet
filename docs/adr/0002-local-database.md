# ADR-0002: Room on SQLite with SQLCipher encryption

- **Status:** **Accepted** (2026-11-05). Approved by owner with Phase 1
- **Requirements:** LOC-01..04, SEC-01, NFR-03/04/10

## Context
Financial records need ACID writes, SQL aggregation for reports, tested migrations, and encryption at rest on low-end devices.

## Decision
Use Room (2.8.x) with `net.zetetic:sqlcipher-android` (4.20.x) through `SupportOpenHelperFactory`, in WAL mode. A random 256-bit DB key is wrapped by an Android Keystore key. Schemas are exported per version, and every migration is tested with MigrationTestHelper. Raw SQL is allowed for report aggregates.

## Alternatives considered
SQLDelight (viable; Room chosen for migration tooling and Android integration). Realm (vendor deprecated Atlas Device Sync). DataStore (settings only).

## Consequences
- **Pro:** Compile-time-checked queries, mature migrations.
- **Con:** SQLCipher adds APK size and CPU cost, to be measured in a Phase 3 benchmark.
