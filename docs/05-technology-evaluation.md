# Technology Evaluation

| Status | **Revision 2 (2026-11-05), Android-only re-evaluation, pending owner approval** |
|---|---|
| Change | Owner dropped iOS and direct integrations. Revision 1 recommended React Native mainly because it could reach iOS from Windows and shared TS types with an integration broker. Both reasons no longer apply, so the framework was re-scored. |

Library versions checked on 2026-11-05 against Maven Central / Google Maven: `net.zetetic:sqlcipher-android` 4.20.0,
`androidx.room:room-runtime` 2.8.5, Compose BOM 2026.09.00, `androidx.work:work-runtime` 2.12.0,
`androidx.biometric:biometric` 1.4.0-beta01 (latest is a beta; the stable 1.1.x line also exists, to check in Phase 3),
`io.kotest:kotest-property` 6.2.6, `org.apache.commons:commons-csv` 1.14.1, `org.dhatim:fastexcel-reader` 0.20.2,
`com.tom-roush:pdfbox-android` 2.0.27.0. Checked against npm (rev. 1): `expo` 58.0.23, `react-native` 0.88.1.
**Existence and version are verified; fitness on Android (size, performance) is not, and will be tested in Phase 3 spikes.**

Scores 1–5 (5 = best for *this* project), weighted by the CLAUDE.md priority order.

---

## 1. Mobile framework (Android only)

| Criterion (weight) | **Native Kotlin + Jetpack Compose** | React Native + Expo (TS) | Flutter (Dart) |
|---|---|---|---|
| Runtime performance (2) | 5 | 4 | 5 |
| Memory footprint on low-end devices (2) | **5.** No extra runtime | 3. JS runtime overhead | 4. Engine overhead |
| APK size (1) | **5.** Typically smallest | 3. Hermes + RN libs | 3. Engine |
| Local DB & encryption (3) | **5.** Room + SQLCipher (`sqlcipher-android`), compile-time-checked SQL, migration test helper | 5. expo-sqlite/op-sqlite + SQLCipher | 5. Drift + SQLCipher |
| Background work / notifications (2) | **5.** WorkManager, AlarmManager, notifications directly | 4. Wrappers | 4. Plugins |
| Platform security APIs (3) | **5.** Keystore, BiometricPrompt, `FLAG_SECURE`, Storage Access Framework, all first-party | 4. Via modules | 4. Via plugins |
| Testability of financial core (3) | **5.** Pure Kotlin/JVM module, JUnit 5 + Kotest property tests; runs with only a JDK (no Android SDK) | 5. Pure TS on Node, runs today | 4. Needs Flutter SDK |
| Toolchain fit on this machine (2) | 3. Needs JDK (small) + Android SDK (needed by *every* option for Android builds) | 4. Node present; still needs JDK + SDK for builds, or cloud | 3. Needs Flutter + JDK + SDK |
| Ecosystem maturity & long-term support (2) | **5.** Google's primary Android stack | 5 | 5 |
| Maintainability (2) | 5. One platform, one language, standard architecture guidance | 4. JS ↔ native boundary, upgrade churn | 4 |
| Future iOS option (1) | 4. Domain module can become Kotlin Multiplatform later without a rewrite | 5 | 5 |
| **Weighted total** | **≈ 4.9** | ≈ 4.2 | ≈ 4.2 |

### Recommendation: **Native Android, Kotlin + Jetpack Compose** (replaces revision 1's React Native proposal)

With iOS gone, a cross-platform framework only adds a runtime layer, extra memory, and a bigger APK, with nothing gained in return.
Native Kotlin is best on the top-weighted criteria:
- Best low-end device performance, memory, and size (NFR-02..07).
- First-party access to Keystore, biometrics, `FLAG_SECURE`, Storage Access Framework, and WorkManager.
- Compile-time-verified SQL and migration testing through Room.

The financial engine stays a **pure Kotlin JVM module**, so it can be tested with only a JDK. Later it can become a Kotlin
Multiplatform module if iOS is ever reconsidered.

Cost: a JDK must be installed before any code runs (Node alone was enough for revision 1's domain-first start). Every
option needs a JDK and the Android SDK to build an Android app anyway.

## 2. Local database
**SQLite + SQLCipher through Room** (`androidx.room` + `net.zetetic:sqlcipher-android` via `SupportOpenHelperFactory`).
- ACID transactions (`@Transaction` / `withTransaction`), WAL, indexes, FTS4 for text search (Room annotations support FTS3/4).
- Schema exported to JSON per version. Every migration is tested with `MigrationTestHelper` against fixture databases.
- Raw `@RawQuery`/SQL is allowed for report aggregates.
- Rejected: Realm (vendor deprecated Atlas Device Sync), SQLDelight (good, but Room's migration tooling and Android integration fit better here), DataStore (settings only).

## 3. Money representation
- Persist as SQLite INTEGER **minor units**. Precision comes from an ISO 4217 table.
- Domain type: `@JvmInline value class` wrappers / `data class Money(val minor: Long, val currency: CurrencyCode)`.
  All arithmetic goes through `Math.addExact`/`multiplyExact`, so overflow throws instead of wrapping. The Long range is ±9.2 × 10¹⁸ centavos.
- Interest and percentage math: integer basis points. Where an intermediate result needs a fraction, use `BigDecimal` with an
  explicit `RoundingMode.HALF_EVEN` and scale, then convert back to minor units at one place.
- Splits: largest-remainder rounding with a deterministic tie-break.
- **`Double`/`Float` are forbidden in money code**, enforced by a detekt custom rule + review.

## 4. File import libraries (to validate in a Phase 3/6 spike)
| Format | Candidate | Concern to verify |
|---|---|---|
| CSV | Apache Commons CSV 1.14.1 | Small; encodings (UTF-8/BOM, Windows-1252) |
| XLSX | **In-house streaming reader on Android `XmlPullParser`** (spike 2026-11-05) | fastexcel-reader 0.20.2 **fails R8 on Android** (needs `javax.xml.stream`). Apache POI rejected for size. See docs/spikes/apk-size.md |
| XLS (legacy) | No lightweight option identified | May remain unsupported. If so, users re-save as XLSX/CSV and the app says so plainly. |
| PDF (text layer) | PdfBox-Android 2.0.27.0 | Measured **+6.0 MB** release APK (needs `-dontwarn com.gemalto.jp2.**`); extraction accuracy per layout still to verify |
| Scanned PDF / images | — | **Out of scope** (OCR unreliable for financial data) |

## 5. Backend (optional services only)
Without integrations, the backend is needed only for: optional account auth, **E2E-encrypted cloud backup**, **multi-device
sync relay**, and the optional **AI proxy**. None of it is needed for MVP.

| Option | Verdict |
|---|---|
| **Kotlin + Ktor + PostgreSQL + S3-compatible storage** | **Recommended.** Shares `kotlinx.serialization` contracts with the app in one Gradle build, one language. |
| Supabase (managed) | Acceptable alternative if the owner prefers managed ops. |
| Node/Fastify | Rev. 1 choice, now weaker since the app is no longer TypeScript. |
| Firebase | Rejected (LWW document sync conflicts with SYNC-02). |

## 6. Tooling
| Concern | Choice |
|---|---|
| Build | Gradle (Kotlin DSL) + version catalog; multi-module |
| Modules (initial) | `:domain` (pure Kotlin/JVM), `:data` (Room, repositories, use cases), `:importers` (parsers, pure Kotlin where possible), `:app` (Compose UI). Split further only when justified. |
| DI | Hilt |
| Async | Kotlin coroutines + Flow |
| UI | Jetpack Compose + Material 3, dynamic color off by default (brand palette), light/dark |
| Lists | `LazyColumn` + Paging 3 (Room PagingSource) for transaction history |
| Charts | Vico (Compose-native) to evaluate, or custom Canvas charts if size/perf demands |
| Dates | `java.time` (API 26+ native, no desugaring needed at minSdk 26) |
| Unit / property tests | JUnit 5 + Kotest property testing |
| DB / Android tests | Robolectric + Room in-memory + `MigrationTestHelper`; instrumented tests on device |
| UI tests | Compose UI test |
| E2E | Maestro (Android) |
| Static analysis | detekt (incl. money-safety rule), ktlint, Android Lint |
| Security scanning | Gradle dependency verification, OWASP dependency-check or GitHub Dependabot, gitleaks |
| CI | GitHub Actions (assumed) |

## 7. Deliberately not chosen
- No cross-platform framework (no remaining benefit after the iOS drop).
- No CRDT library for financial data; deterministic domain merge instead.
- No GraphQL or microservices.
