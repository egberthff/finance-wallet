# Development and Testing Guide

| Status | v1, 2026-11-06 |
|---|---|

## 1. Toolchain
See [19-environment-setup.md](19-environment-setup.md) for installation. Required versions: Temurin JDK 21, Android SDK
platform `android-37.0`, build-tools `36.0.0`, Gradle via the wrapper (9.8.2).

**Use an OpenJDK build (Temurin), not Oracle JDK.** Oracle's JDK refuses JCE providers that Oracle has not signed, so
every test that touches BouncyCastle (backup crypto) fails with `SecurityException` at `JarVerifier`/`ProviderVerifier`.

### Machine-specific workarounds (do not commit)
| Symptom | Cause | Fix |
|---|---|---|
| `Failed to find Build Tools revision 36.0.0` and SDK download errors `Unable to tunnel through proxy` | `~/.gradle/gradle.properties` sets `systemProp.https.proxyHost=` (empty) | Install SDK packages with the SDK manager or by hand (checksums from `repository2-3.xml`), or remove the empty proxy lines |
| `AAPT2 ... Daemon startup failed` | Windows blocks the aapt2 binary Gradle downloads into its cache | `-Pandroid.aapt2FromMavenOverride=<sdk>/build-tools/36.0.0/aapt2.exe` |
| Platform installed by hand is not found | No `package.xml` in `platforms/android-37.0` | Install through `sdkmanager`, or add the `package.xml` the SDK manager writes |

## 2. Everyday commands
```
gradlew check                # everything below; must pass before any commit
gradlew :domain:test         # domain engine only (JDK only)
gradlew :app:ktlintFormat    # fix formatting (any module: :<module>:ktlintFormat)
gradlew assembleDebug        # debug APK: app/build/outputs/apk/debug/app-debug.apk
```
`check` runs: compile with warnings as errors, unit tests (JUnit 5 + Kotest in `:domain`/`:importers`, JUnit 4 +
Robolectric in `:data`/`:app`), detekt, ktlint, Android Lint, Kover coverage limits, the money-safety rule
(`config/money-safety`), the test-discovery guard, the no-network-permission manifest check (ADR-0010) and the
FLAG_SECURE resource check.

## 3. Test levels
| Level | Where | What it proves |
|---|---|---|
| Domain unit + property | `domain/src/test` | money, ledger invariants, periods, allocations, recurrence, reports (thousands of random cases) |
| Import parsing | `importers/src/test` | CSV/XLSX readers, normalisation, fingerprints, zip-bomb/DTD guards |
| Data (Robolectric) | `data/src/test` | services over a real Room database, migrations 1→4, integrity checks after every write, backup crypto, fault injection |
| App (Robolectric) | `app/src/test` | ViewModels, lock controller, reminders, crash log, Compose entry screen, the end-to-end journey |
| Emulator | manual, see §4 | the real APK on Android, including the release build with R8 |

Latest counts: see [PROGRESS.md](../PROGRESS.md).

## 4. Emulator testing (no USB debugging needed)
Emulators run with Windows Hypervisor Platform (WHPX). Existing AVDs: `Pixel_7_Edited_API_33`, `Pixel_7_Pro_Edited_API_30`.
```
emulator -avd Pixel_7_Edited_API_33 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 exec-out screencap -p > shot.png
```
- **Debug builds allow screenshots** (`app/src/debug/res/values/bools.xml` turns FLAG_SECURE off). Release builds never
  do; `checkSecureWindow` fails the build if FLAG_SECURE is disabled outside `src/debug`.
- In a release build, screenshots are black (that is check S3). Drive the UI through `uiautomator dump` instead: every
  PIN key has a content description, so tests can tap keys by label.
- `adb shell input text` does **not** type reliably into the delete-all PIN field (a number-password field): the PIN check
  fails although the PIN is right. Tap the on-screen keypad instead. Real users are not affected (verified on the
  emulator with keypad taps).
- Reminders: `adb root` (google_apis images only) and
  `adb shell am broadcast -a ph.financewallet.app.action.CHECK_BILLS -n ph.financemanager.app/ph.financewallet.app.reminders.ReminderAlarmReceiver`
  runs the daily bill check immediately.
- **Always test the release build before distributing.** R8 renames classes; on 2026-11-06 a resource loaded by a
  class-relative path crashed the release app while every debug and JVM test passed.

## 5. Rules that tests enforce
- Money is `Long` minor units; the money-safety rule rejects `Double`/`Float` money (ADR-0003).
- JUnit 5 tests must return `Unit` (`runBlocking<Unit>`), or JUnit skips them silently; the test-discovery guard fails
  the build otherwise.
- Every write goes through `validatedWrite`, which runs the integrity checks and rolls back on failure.
- A function that returns `null` for success must not be combined with `?:` for an error
  (`choice?.let { write() } ?: error` reports success as an error; see `BudgetsViewModel.allocateTo`).
