# Coding Standards

| Status | Phase 2 v1 (2026-11-05). Enforced from Phase 3 by tooling where possible |
|---|---|

## 1. Language & style
- Kotlin (current stable), JVM target 17. ktlint (official style) + detekt + Android Lint; CI fails on violations.
- Explicit API mode in `:domain` (`explicitApi()`), so every public declaration is intentional.
- No `!!` in production code (detekt `UnsafeCallOnNullableType`). Prefer sealed results over exceptions for expected failures.

## 2. Module boundaries
| Module | May depend on | Must not |
|---|---|---|
| `:domain` | Kotlin stdlib only (+ test libs) | Android, Room, coroutines-android, I/O, `System.currentTimeMillis()`, `LocalDate.now()` |
| `:importers` | `:domain`, CSV/XLSX/PDF libs | Room, Android UI |
| `:data` | `:domain`, `:importers`, Room, SQLCipher, AndroidX | Compose |
| `:app` | all | business rules in ViewModels or composables |

Checked by Gradle dependency declarations, plus an architecture test (Konsist or an equivalent import check) in CI.

## 3. Money rules (NFR-01)
1. Money is `Money(minor: Long, currency)` everywhere outside the UI text field.
2. `Double` and `Float` are **forbidden** in `:domain`, `:data`, and `:importers` money paths. A custom detekt rule flags any `Double`/`Float` type,
   literal, or `toDouble()` in those modules. Exceptions need a `@Suppress` with a written reason (chart rendering only, in `:app`).
3. `BigDecimal` is allowed only inside `RateMath`, `MoneyParser`, and importer amount parsing. Always use an explicit scale and `RoundingMode`.
   Never use `BigDecimal(double)`.
4. Ratios use `Allocation.split`. A hand-written percentage loop is a review blocker.
5. Overflow-checked arithmetic (`Math.addExact` and friends) only.

## 4. Time & ids
`Clock` and `IdGenerator` are injected. Store `LocalDate` as ISO text and `Instant` as epoch ms. The zone comes from user settings and is
never read implicitly from `ZoneId.systemDefault()` in domain code.

## 5. Persistence
- All writes go through use cases inside `withTransaction { }`. DAOs are `internal` to `:data`.
- No `fallbackToDestructiveMigration`. Every migration has a MigrationTestHelper test.
- Queries returning lists are paged (`PagingSource`) or bounded with `LIMIT`. A DAO method returning an unbounded `List<Txn>` is forbidden.

## 6. Errors, logging, privacy
- No financial values, descriptions, PINs, or passphrases in logs, exceptions, or analytics (there are no analytics).
- User-facing errors are mapped from `DomainError` codes to string resources. Raw exception messages are never shown.
- Every screen handles Loading / Empty / Error / Content (UX-05).

## 7. Testing
| Layer | Requirement |
|---|---|
| `:domain` | ≥ 95 % line coverage (Kover). Every EX-n from 10-domain-model.md is a named test. Property tests for I-1, ledger identity, split sum, period contiguity |
| `:importers` | Fixture-based tests per profile; malformed-input tests; property test: parse(format(rows)) = rows |
| `:data` | Robolectric + in-memory SQLCipher/Room; IC-1..8 after every use-case test; crash-injection (abort mid-transaction) tests |
| `:app` | Compose UI tests for critical flows; Maestro E2E journeys on a device |
A test is reported as passed only when it has been executed. CI artifacts are the evidence.

## 8. Strings & i18n
All user-visible text lives in `strings.xml` (Q-13). Plurals use `plurals`. Money and date formatting goes through the app formatters, never string concatenation.

## 9. Git
Branch from `main`. Use Conventional Commits (`feat:`, `fix:`, `docs:` …). Reference requirement IDs in commit bodies (e.g., `Refs: TXN-04`).
Small PRs, each with tests and a PROGRESS.md update.
