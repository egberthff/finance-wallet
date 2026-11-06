# Milestone Plan: Phase 8, Quality Assurance and Release Candidate

| Status | **In progress** (owner: "proceed to Phase 8", 2026-11-06) |
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
| 8.4 | Virtual device: emulator (API 36 x86_64), install the release APK, smoke-test launch, PIN, DB open (SQLCipher native), backup KDF timing | emulator logs |
| 8.5 | Dependency audit (known vulnerabilities of the pinned versions), security checklist against docs/16 | report in this file |
| 8.6 | QA report: what passed, what failed, what stays unverified | §7 |

## 4. Virtual device (emulator)
This PC (i5-12500T, 8 GB RAM) has hardware virtualization enabled in firmware, but **no hypervisor is active** and the
session has no administrator rights. The Android emulator needs Windows Hypervisor Platform (WHPX) or the Android Emulator
Hypervisor Driver (AEHD) to run x86_64 images; both need administrator rights and WHPX needs a restart. The emulator and
the API 36 system image are installed under `%USERPROFILE%\dev\android-sdk` so that only this step remains for the owner.

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
Filled in as checks are executed.
