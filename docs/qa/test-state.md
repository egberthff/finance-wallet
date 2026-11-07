# Testing State and Handover (for the next run)

| Saved | 2026-11-07, after release candidate 1.0.0-rc1 |
|---|---|

## 1. Where things stand
- **Build:** `gradlew check` passes. 291 tests (domain 117, importers 20, data 104, app 50), 0 failed, 0 skipped. Lint has
  0 errors; 2 accepted warnings (OldTargetApi: targetSdk 36; ObsoleteSdkInt: the `mipmap-anydpi-v26` folder, required for
  the adaptive icon to link).
- **Release candidate:** `dist/` (git-ignored, archive it):

  | File | SHA-256 |
  |---|---|
  | `FinanceManager-1.0.0-rc1.apk` (10.6 MB, side-load/testing) | `7b35bdf1cc39e143fd3ba114aabbcbbc44f607ef71242620589e94821ac2b954` |
  | `FinanceManager-1.0.0-rc1.aab` (10.0 MB, Play upload) | `fd7d2081cccdd3216d8e20471b3173fca2d83443d394f933b16805d3d371bd23` |
  | `FinanceManager-1.0.0-rc1-mapping.txt` (decode crash reports) | `7c43d7b3d9b00b3d5fd90215acc0f97e9102eb5b951525cf86a4fe7c00654667` |

  Signed v2 with certificate SHA-256 `d418ae50552cc9de5569538bd979bd55dd918e64712c7e426e1f5ec2b9555e2d`; 16 KB aligned;
  no network permission.
- **Device testing:** checklist A–G run on emulators (Android 13 debug + release, Android 11 release). Results and the 8
  defects found and fixed: [phase 8 QA report §7.1](../milestones/phase-8-quality-assurance.md#71-emulator-results-and-defects-found-2026-11-0607).

## 2. Not done yet (next run)
| Item | How |
|---|---|
| Physical phone run of [device-test-checklist.md](device-test-checklist.md) with the rc1 APK | Side-load `dist/FinanceManager-1.0.0-rc1.apk`. On 2026-11-07 the phone `9585030154004ZK` appeared as an authorized adb device; it was **not** used without the owner's consent |
| Android 8–10 | Create an API 26 AVD (`system-images;android-26;google_apis;x86`) |
| NFR-02/03 with 50,000+ transactions | Needs a generated-data test build |
| Google Drive via the file picker (F9) | Sign in to Google on an emulator image with Play services |
| Legal review of [docs/21-privacy-notice.md](../21-privacy-notice.md) | Owner + counsel |
| Play Console steps | [deployment guide §6](../22-deployment-guide.md) |

## 3. Re-creating the test environment
1. Toolchain: Temurin 21 in `%USERPROFILE%\dev\jdk-21`; SDK in `%LOCALAPPDATA%\Android\Sdk` with `platforms/android-37.0`
   and `build-tools/36.0.0` (both installed by hand; see dev guide §1 for the proxy and aapt2 workarounds);
   `local.properties` with `sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk`.
2. Build: `bash tools/emulator/gradle.sh <logname> check` (sets JAVA_HOME and the aapt2 override; log in `build/`).
   Release: add `:app:assembleRelease :app:bundleRelease -Pfm.signing=C:/Users/SherwinBush/Downloads/finance-wallet-keys/finance-manager-signing.properties`.
3. Emulator: `emulator -avd Pixel_7_Edited_API_33 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect`
   (also `Pixel_7_Pro_Edited_API_30`). When a phone is attached, use `SER=emulator-5554` with the scripts.
4. UI driver: `bash tools/emulator/ui.sh start|unlock|shot <name>|dump|tap "<text>"|tapexact "<text>"|tapdesc "<desc>"|tapxy X Y|type "<text>"|scroll`.
   `unlock` enters PIN 123456. Screenshots go to `build/emulator-shots/`. Coordinates in past runs are for 1080×2400.
5. Import sample: `adb push tools/emulator/sample-import.csv /sdcard/Download/`.

## 4. Test data and credentials used (made-up, emulator only)
| What | Value |
|---|---|
| PINs | 123456 (API 33 debug), 135790 (API 33 release), 975310 (API 30 release) |
| Backup passphrases | `Mango-Jeepney-Sunset-42` (debug backup), `Release-Check-Bayanihan-7` (release backup) |
| Expected balances in the debug backup | BPI ₱38,000.00, GCash ₱3,449.50, net worth ₱61,449.50 |

The API 33 emulator state after the UI design pass: release build freshly installed (PIN 123456, no data; restore the debug backup from `/sdcard/Download` to get data back). The API 30 emulator:
release build with one account (Maya ₱1,500.00).

## 5. Gotchas learned
- Release builds block screenshots; use `ui.sh dump` / content descriptions.
- `adb input text` does not type correctly into the delete-all PIN field; tap the on-screen keypad.
- `uiautomator dump` can be stale while the keyboard is open; take a screenshot (debug) or wait and dump again.
- Always repeat at least C1, F2 and F5 on the **release** build: R8-only defects (D6) do not show in debug or JVM tests.
