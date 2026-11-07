# Milestone Plan: Phase 8, Quality Assurance and Release Candidate

| Status | **Complete on emulators** (2026-11-07); physical-phone run outstanding (USB debugging unavailable) |
|---|---|

## 1. Objectives
Show, with executed evidence, that the app is correct and safe on Android. Produce a signed release-candidate APK. Close or
honestly record every device-only check deferred from Phases 3–7.

## 2. Owner instructions for this phase (2026-11-06)
- Final app identity: **"Finance Manager"**. Android requires a package-style id without spaces, so the application id is
  `ph.financemanager.app` (permanent once published). The Kotlin packages keep `ph.financewallet.*`; they are internal only.
- Look on the internet for the bank statement samples the importer needs (Q-11). Findings: §5.
- Test on a phone virtually if possible (Android emulator). Findings: §4.
- Build an APK.

## 3. Scope
| Step | Deliverable | Evidence |
|---|---|---|
| 8.1 | App identity: name, application id, version 0.9.0-beta | merged manifest, APK badging |
| 8.2 | End-to-end journey test (§19 journeys 1–11, 13) with integrity checks | `EndToEndJourneyTest` |
| 8.3 | Release build: R8 shrinking, signing key outside the repository, `apksigner verify`, permission audit | APK, verification output |
| 8.4 | Owner's phone test of the release APK (checklist A–G: launch, lock, money features, reminders, import, backup/restore/reinstall, delete all) | filled checklist |
| 8.5 | Dependency audit (known vulnerabilities of the pinned versions), security checklist against docs/16 | report in this file |
| 8.6 | QA report: what passed, what failed, what stays unverified | §7 |

## 4. Virtual device (emulator)
**Update 2026-11-06:** development moved to a PC where Windows Hypervisor Platform is active. Two existing AVDs were used:
`Pixel_7_Edited_API_33` (Android 13, 1080×2400) and `Pixel_7_Pro_Edited_API_30` (Android 11, 1440×3120). USB debugging on
the owner's phone was not available, so all device checks below ran on these emulators. Original note for the first PC:

The first PC (i5-12500T, 8 GB RAM) had hardware virtualization enabled in firmware, but **no hypervisor was active** and
the session had no administrator rights. The Android emulator needs Windows Hypervisor Platform (WHPX) or the Android Emulator
Hypervisor Driver (AEHD) to run x86_64 images; both need administrator rights and WHPX needs a restart. The emulator and
the API 36 system image were being installed when the owner decided (2026-11-06) to drop the emulator. The download was
stopped and its partial files removed. The owner tests the release APK on a real phone with
[docs/qa/device-test-checklist.md](../qa/device-test-checklist.md); results are recorded in §7.

## 5. Bank statement samples (Q-11), research 2026-11-06
- No official sample statements or published export column layouts were found for GCash, Maya, BPI or BDO.
- **GCash (personal):** the app emails the transaction history as a **password-protected PDF**; the password is the account
  holder's last name plus the last 4 digits of the mobile number (help.gcash.com article 360034155433). CSV export exists
  only on the GCash for Business portal.
- **BPI:** statements (e-SOA and credit card eStatements) are **password-protected PDFs** sent by email or downloaded in the
  app (bpi.com.ph credit card e-statements page).
- **Maya:** CSV export and PDF statements are documented only for Maya Business.
- Statements that other people posted on document-sharing sites are real people's financial records. They are not used,
  and building on them would breach the Data Privacy Act principles this project follows.
- Consequence: the most useful next importer for Philippine users is **PDF with a user-entered PDF password**, and its
  layouts can only be verified with the owner's own redacted files. Until then, the generic CSV/XLSX importer with
  saved column profiles is the supported path. Q-11 stays open.

## 6. Risks
| Risk | Mitigation |
|---|---|
| R8 removes classes used by reflection (Room, SQLCipher JNI, BouncyCastle) | Keep rules; release APK smoke test on a device or emulator before any distribution |
| Signing key loss means no updates under the same id | Key and password file stored outside the repository; owner must keep an offline copy |
| No accelerated emulator | Owner enables WHPX, or tests on a physical phone |

## 7. QA report
| Check | Status | Evidence |
|---|---|---|
| JVM/Robolectric suite (domain, importers, data, app) incl. EndToEndJourneyTest | Executed–Passed | `gradlew check`, 2026-11-07: 291 tests (domain 117, importers 20, data 104, app 50), 0 failed, 0 skipped; Lint 0 errors |
| Release 1.0.0-rc1 (versionCode 2): R8, signed v2, id `ph.financemanager.app`, no network permissions, APK 10.6 MB / AAB 10.0 MB | Executed–Passed | apksigner verify (cert SHA-256 `d418ae50…5e2d`), aapt2 badging |
| 16 KB page size (Play rule for Android 15+ targets) | Executed–Passed | `zipalign -c -P 16` OK; `config/release/ElfAlign.java`: every `.so` LOAD segment aligned to 16384 |
| Dependency audit: 132 release runtime artifacts against OSV | Executed: 0 advisories | api.osv.dev querybatch, 2026-11-06 (dependencies unchanged since) |
| Checklist A–G on emulator, debug build (Android 13) | Executed–Passed after fixes | see §7.1 |
| Checklist A, S2–S5, C1, F2, F5 on emulator, **release** build (Android 13 + 11) | Executed–Passed after fix | see §7.1 |
| Physical phone | Not executed | USB debugging not available to the owner |
| NFR-02/03 with 50k+ transactions; Android 8.0–10 | Not executed | known limitation (docs/24) |

### 7.1 Emulator results and defects found (2026-11-06/07)
| # | Check | Result |
|---|---|---|
| A1–A5 | Install, PIN setup, permissions | Pass. No network permission in `dumpsys package` |
| S1, S4, S5 | Lock after restart / >60 s background; none <60 s | Pass (Android 11, release) |
| S2 | 6 wrong PINs → 30 s lockout, right PIN refused during it | Pass (release); counter resets after unlock |
| S3 | Screenshots blocked | Pass (release screenshot entirely black) |
| C1–C5 | Accounts, PH categories, income, expense, transfer | Pass: balances 38,000.00 / 3,549.50; transfer not counted as income or spending |
| C7 | Budget from the 5th, allocate 8,000 to Food | Pass after fix **D1** |
| C8 | Goal 50,000; set aside 5,000; release 1,000 with confirmation | Pass (goal 4,000; release refused without the tick) |
| C9 | Bill due tomorrow on Home | Pass |
| C10 | Reports this month | Pass (income 30,000; spending 450.50) |
| C11 | Asset changes net worth | Pass after fix **D2** |
| D1–D2 | Notification permission prompt; reminder delivered | Pass (reminder fired through the real receiver: "1 bill due soon") |
| E1–E4 | CSV mapping, import, re-import duplicates, undo | Pass after fixes **D3, D4** |
| F1 | "password123" refused | Pass after fix **D5** |
| F2–F5 | Backup (≈10 s), reminder gone, wrong passphrase changes nothing, restore | Pass, debug and release (release passes after fix **D6**) |
| F7 | Uninstall → reinstall → new PIN → restore | Pass (all records back) |
| F8 | Delete all: wrong PIN refuses; right PIN wipes DB, keys, PIN | Pass (enter the PIN with the keypad; see dev guide §4 on `adb input text`) |
| G1 | Dark mode | Pass |
| — | Debug backup restored by release build | Pass (identical balances) |

Defects found on the emulator and fixed (each with a regression test where the logic is testable on the JVM):
| ID | Defect | Fix |
|---|---|---|
| D1 | Budget "Allocate" and "Save to goal" showed "Choose a category" **after a successful save** (`x?.let{write()} ?: error` turned success into an error) | `BudgetsViewModel.allocateTo/saveToGoalChoice`; test in `ScreenViewModelsTest` |
| D2 | Home net worth ignored user-entered assets, so Home and Reports disagreed | Home uses `ReportService.netWorth`; test in `Phase5ViewModelsTest` |
| D3 | Accounts tab showed stale balances after an import undo or new entry | Accounts refreshes when shown |
| D4 | Import rows offered expense categories for money-in rows | Rows offer only categories that fit their sign |
| D5 | Common words padded with digits ("password123") passed the passphrase rule | Core-word check; tests in `BackupCryptoTest` |
| D6 | **Release only:** typing a backup passphrase crashed the app (`missing common-passwords.txt`): R8 renamed the class that loaded the list by a relative path. The 0.9.0-beta APK had this crash | Absolute resource path; verified on the release build |
| D7 | PIN keypad buttons had no accessibility labels | Each key has a content description |
| D8 | Re-tapping the current tab did not return to its first screen; no tab highlighted on sub-screens | Fixed in `AppNavigation` |
| D9 | Switching tabs and back to More restored its last sub-screen with the **previous** tab highlighted; tapping More again re-opened the sub-screen instead of the More list | The tapped tab becomes the owner at tap time (`AppNavigation`); verified on the emulator |

### 7.2 UI design pass (2026-11-07)
A screen-by-screen review on the emulator found screens below the standard of the main tabs. Changed:
- Sub-screens show their title in the top bar (no empty bar above a second heading).
- Every list screen uses one layout (`ui/common/Layout.kt`): the add action is always the floating button (Debts, Assets,
  Bills, Categories, Goals no longer have a button at the top), empty states are cards with an icon and an explanation.
- Dates read "7 Nov 2026" and ranges "5 Nov – 4 Dec 2026" everywhere (`ui/common/Dates.kt`, `DatesTest`); no ISO dates
  on screen. The entry screen has a date picker instead of a typed YYYY-MM-DD field.
- Transactions are grouped by day (Today, Yesterday, weekday dates). Accounts shows "Money in your accounts" and "You owe"
  as two separate figures (`AccountsState`, tested), never netted.
- Budgets: compact period and "N days left"; each category shows "left" or "over". Goals, Debts and Bills cards have
  icons and status labels (Due today, Overdue, Paid off).
- Reports: period stepper with arrows, category bars proportional to the largest, net-worth chart starts at the first
  month with records and has month labels. Insights and Categories use grouped cards.
- Backup: the strength bar is coloured by strength (the old bar showed a stray dot when empty); destructive confirm
  buttons are red; the whole checkbox line is tappable. Status-bar icons are dark during launch in light mode.
Verified: debug build on Android 13 in light and dark mode (screens and date picker); release build smoke test (tabs,
Reports, Insights, backup passphrase meter, date picker) with no crash.
