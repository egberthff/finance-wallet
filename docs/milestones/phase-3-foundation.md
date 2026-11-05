# Milestone Plan: Phase 3, Foundation

| Status | **In progress** (started 2026-11-05; Phase 2 approved) |
|---|---|

## 1. Objectives
Create a working, tested, secure skeleton that Phase 4's financial engine can be built into without restructuring.

## 2. Scope
| Item | Reqs / ADR |
|---|---|
| Toolchain: Temurin JDK 21, Android SDK command-line tools, platform + build tools (no emulator image) | Q-20 |
| Gradle multi-module build (`:domain`, `:importers`, `:data`, `:app`), version catalog, wrapper | ADR-0008 |
| Static analysis: ktlint, detekt with the custom **money-safety rule**, Android Lint | 18-coding-standards §3 |
| Test stack: JUnit 5 + Kotest property tests (`:domain`), Robolectric/Room tests (`:data`), Kover coverage | ADR-0008 |
| `:domain` foundation: `Money`, `CurrencyCode`/ISO table, `Allocation.split`, `RateMath`, `MoneyParser`, period calculator, `IdGenerator` (UUIDv7), `Clock` usage | ADR-0003, domain §1, §5.1 |
| `:data` foundation: Room DB v1 schema, SQLCipher with a Keystore-wrapped key, integrity checks IC-1..IC-8, schema export + migration test harness | ADR-0002, schema doc |
| `:app` shell: Compose + Material 3 theme tokens (light/dark), bottom navigation, Loading/Empty/Error components, app lock (PIN + BiometricPrompt), `FLAG_SECURE`, no-INTERNET manifest check | UX-01/02/05, SEC-01, ADR-0010 |
| CI workflow file (GitHub Actions) | SEC-05 |
| Spikes: SQLCipher query benchmark; APK-size impact of fastexcel-reader, PdfBox-Android, BouncyCastle | R-21, ADR-0011 |

Out of scope: account and transaction features (Phase 4).

## 3. Technical approach
Domain-first. `:domain` is built and tested on the JVM before any Android module. Android modules follow once the SDK is installed.
Each step is verified by running the build and tests on this machine.

## 4. Dependencies
- JDK + SDK install (approved).
- **A physical Android device is needed for AC-SEC-1/2 and on-device checks.** Until then, those criteria are marked *Not executed*.
- CI execution needs a GitHub remote (not configured). The workflow file is written now and runs once a remote exists.

## 5. Risks
Disk space (26 GB free before install); first Gradle builds download about 1–2 GB of dependencies. Library compatibility with AGP 9.5 and Kotlin 2.4 is unverified until built.

## 6. Acceptance criteria
1. `gradlew build` succeeds locally: compile, unit tests, ktlint, detekt, Lint.
2. `:domain` tests pass: EX-1..4, EX-11..14, EX-16, plus properties (split sum, period contiguity), with coverage ≥ 95 % for implemented code.
3. The detekt money rule fails a deliberately bad fixture and passes clean code.
4. Room schema v1 is exported and matches the reference table list. IC-1..IC-8 run in a Robolectric test.
5. A debug APK builds, and the merged release manifest has no INTERNET permission (automated check).
6. On-device: the app launches, the lock works, FLAG_SECURE is active, and the DB file is unreadable without the key (AC-SEC-1/2). **Requires the device.**
7. Spike results are recorded in `docs/spikes/`.

## 7. Deliverables
Source tree, build config, tests, CI workflow, `docs/19-environment-setup.md`, `docs/spikes/*`, and an updated PROGRESS.md.
