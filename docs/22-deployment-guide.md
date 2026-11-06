# Deployment Guide (Google Play)

| Status | v1, 2026-11-06. Release candidate 1.0.0-rc1 (versionCode 2) built and verified; not yet uploaded |
|---|---|

## 1. Identity
| Item | Value | Note |
|---|---|---|
| App name | Finance Manager | |
| Application id | `ph.financemanager.app` | **Permanent once published** |
| Signing certificate | `CN=Finance Manager, O=Finance Manager, C=PH`, SHA-256 `d418ae50552cc9de5569538bd979bd55dd918e64712c7e426e1f5ec2b9555e2d` | Upload key; see §3 |
| minSdk / targetSdk / compileSdk | 26 / 36 / 37 | targetSdk 36 meets the current Play target-API rule; raise yearly |

## 2. Build a release
```
gradlew check
gradlew :app:assembleRelease :app:bundleRelease -Pfm.signing=<path>/finance-manager-signing.properties
```
- The properties file lives **outside the repository** (git ignores `*signing*.properties`, `*.jks`). Its `storeFile`
  may be an absolute path; if that path does not exist, a keystore with the same file name next to the properties file
  is used (keys moved between machines).
- Packaging a release **fails** without a signing configuration (no accidental unsigned builds).
- Outputs: `app/build/outputs/bundle/release/app-release.aab` (upload this), `app/build/outputs/apk/release/app-release.apk`
  (side-loading and testing), `app/build/outputs/mapping/release/mapping.txt`.

## 3. Verify before every distribution
```
apksigner verify --verbose --print-certs app-release.apk        # v2 verified, certificate as in §1
aapt2 dump badging app-release.apk                             # id, version, permissions (no INTERNET)
zipalign -c -P 16 -v 4 app-release.apk                          # 16 KB zip alignment of .so files
java config/release/ElfAlign.java app-release.apk                            # LOAD segments >= 16 KB (Play requirement)
```
Then install the **release** APK on an emulator and run the checklist in [qa/device-test-checklist.md](qa/device-test-checklist.md),
at least sections A, B (S2, S3), C1, F2 and F5. Results for 1.0.0-rc1: [phase 8 QA report](milestones/phase-8-quality-assurance.md#7-qa-report).

## 4. Keys
- Enroll in **Play App Signing**: Google holds the app-signing key; the key above becomes the upload key. A lost upload
  key can then be reset through Play support; without enrollment, a lost key means the app can never be updated.
- Keep two offline copies of the keystore and its passwords (for example an encrypted USB drive and a password manager).
  The copy in `Downloads/finance-wallet-keys` on the development PC is not a backup.

## 5. Archive per release
Store with each uploaded version: the AAB, `mapping.txt` (needed to read crash reports, which contain R8-renamed
class names), the verification output from §3, and the filled checklist.

## 6. Play Console (owner actions)
| Step | Status | Input |
|---|---|---|
| Developer account (individual or organisation) | Owner | Organisation accounts need a D-U-N-S number |
| Store listing | Owner | Short/long description, screenshots from a **debug** build (release blocks screenshots), feature graphic, icon 512 px |
| Privacy policy URL | Owner | Host [21-privacy-notice.md](21-privacy-notice.md) after legal review |
| Data safety form | Ready | See §7 |
| Content rating questionnaire | Owner | Finance utility, no user-generated content |
| Target audience | Owner | 18+ |
| Financial features declaration | Owner | Personal finance tracker; no loans, payments or investment services |
| Closed testing (required for new personal accounts: 12+ testers for 14 days before production) | Owner | Upload the AAB to a closed track first |

## 7. Data safety form answers (from the code, ADR-0010/0012)
- **Does the app collect or share user data?** No. The app has no internet permission; nothing leaves the phone unless
  the user saves a file through the system file picker (backup, export, crash reports), which Play does not count as
  collection by the developer.
- **Is data encrypted in transit?** Not applicable (no transmission).
- **Can users request deletion?** Yes: Privacy & data → Delete all data removes every record, the PIN and the keys.
- Permissions to explain if asked: `USE_BIOMETRIC` (optional unlock), `POST_NOTIFICATIONS` (bill reminders),
  `RECEIVE_BOOT_COMPLETED` (re-arm reminders after restart).

## 8. Updating
- Raise `versionCode` for every upload. Database migrations run automatically after a pre-migration copy and an
  integrity check; a failed check restores the copy (docs/11 §6).
- Test the update path: install the previous release, add data, install the new release over it, confirm the data.
