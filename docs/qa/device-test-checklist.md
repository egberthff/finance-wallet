# Phone Test Checklist: Finance Manager 1.0.0-rc1

APK: `dist/FinanceManager-1.0.0-rc1.apk` (SHA-256 `becef1cbedb89f94ade08d23c61b8a0c56f8e5a92ff0552ac0cc9700dfd74de1`). Do not use 0.9.0-beta: making a backup crashes it (defect D6). Emulator results: [test-state.md](test-state.md).
Use made-up records only; do not enter real account numbers. For each check write **Pass**, **Fail** (with what you saw) or
**Skipped**. A screenshot or short description is enough for a failure. Screenshots inside the app are blocked on purpose
(check S3), so describe the screen or photograph it with another phone.

**Phone details:** model ______ · Android version ______ · RAM ______

## A. Install and first start (release build risk: if A fails, stop and report)
| # | Steps | Expected | Result |
|---|---|---|---|
| A1 | Install the APK (allow "Install unknown apps") | Installs; app is named "Finance Manager" | |
| A2 | Open the app | PIN setup screen appears within a few seconds, no crash | |
| A3 | Enter a 6-digit PIN, then a different one to confirm | "PINs did not match" message; asked again | |
| A4 | Set the PIN properly | Home screen opens (empty state, no error screen) | |
| A5 | Settings → Apps → Finance Manager → Permissions | No "Network"/"Internet" access listed | |

## B. Security
| # | Steps | Expected | Result |
|---|---|---|---|
| S1 | Close the app fully (swipe from recents), reopen | Asks for the PIN | |
| S2 | Enter a wrong PIN 6 times | After the 6th, input is blocked for about 30 seconds; the correct PIN is refused until then | |
| S3 | In the app, open the recents screen; try a screenshot | Recents preview is blank; screenshot is blocked or black | |
| S4 | Leave the app in the background for over 1 minute, return | Asks for the PIN again | |
| S5 | Under 1 minute in the background, return | No PIN asked | |
| S6 | If the phone has fingerprint/face unlock: use it on the lock screen | Unlocks; after the S2 lockout it does not unlock until the wait is over | |

## C. Core money features (Phases 4–5)
| # | Steps | Expected | Result |
|---|---|---|---|
| C1 | Accounts: add GCash (e-wallet, opening ₱2,000) and BPI (bank, opening ₱10,000) | Both listed with those balances | |
| C2 | More → Categories → add suggested categories | Philippine starter list appears | |
| C3 | Add income ₱30,000 Salary to BPI | BPI ₱40,000; Home income ₱30,000 | |
| C4 | Add expense ₱450.50 Food from GCash | GCash ₱1,549.50; Home spending ₱450.50 | |
| C5 | Transfer ₱2,000 BPI → GCash | Balances move; **spending and income unchanged** | |
| C6 | Edit the expense to ₱500; check history | Amount updated; balance follows | |
| C7 | Budgets: monthly budget starting on the 5th; allocate ₱8,000 to Food | Period shows e.g. Nov 5 – Dec 4; Food ₱8,000 | |
| C8 | Goals: "Emergency fund" ₱50,000; contribute ₱5,000; withdraw ₱1,000 (confirm) | Goal ₱4,000; spending unchanged | |
| C9 | Bills: add a bill due tomorrow | Shows on Home under upcoming bills | |
| C10 | Reports: this month | Totals match C3–C8; transfers and savings not counted as spending | |
| C11 | Debts and Assets: add one of each | Net worth on Home changes accordingly | |

## D. Notifications (Phase 5)
| # | Steps | Expected | Result |
|---|---|---|---|
| D1 | On Android 13+: when asked, allow notifications | Prompt appears once | |
| D2 | With the bill from C9, wait until the next morning (check runs around 08:00, may be late) | Reminder notification appears | |
| D3 | Restart the phone; next morning | Reminders still arrive | |

## E. Statement import (Phase 6)
| # | Steps | Expected | Result |
|---|---|---|---|
| E1 | Put a small CSV on the phone (Date, Description, Amount columns). More → Import, pick it | Column mapping screen; dates not guessed when ambiguous | |
| E2 | Preview → import | Rows added to the chosen account | |
| E3 | Import the same file again | Rows marked as duplicates; nothing added twice | |
| E4 | Import History → undo the import | Imported rows removed; balance back | |
| E5 | Try a PDF | Clear message that PDF is not supported yet | |
| E6 | Try an Excel (.xlsx) file | Works like CSV | |

## F. Backup, restore, privacy (Phase 7). Most important
| # | Steps | Expected | Result |
|---|---|---|---|
| F1 | More → Backup & restore: try passphrase "password123" | Refused (common password) | |
| F2 | Make a backup with a long passphrase; save to Downloads. **Note how many seconds it takes** | Saved message; file `finance-manager-YYYY-MM-DD.fwbak` in Downloads | seconds: |
| F3 | Home after F2 | Backup reminder card gone | |
| F4 | Add one more expense. Restore the backup with a **wrong** passphrase | "Wrong passphrase…"; nothing changed | |
| F5 | Restore with the right passphrase | Summary shown; after "Replace all current data" the extra expense is gone, balances as at backup | |
| F6 | Privacy & data → Export all data | Warning first; a readable `.jsonl` file is saved | |
| F7 | Uninstall the app, reinstall the APK, set a new PIN, restore the F2 backup | All records come back. **This proves a lost phone can be recovered** | |
| F8 | Privacy & data → Delete all data: wrong PIN, then right PIN | Wrong PIN deletes nothing; right PIN → app restarts at PIN setup with no data | |
| F9 | Optional: save a backup to Google Drive from the picker, then restore from there | Works, or note the error | |

## G. General
| # | Steps | Expected | Result |
|---|---|---|---|
| G1 | Switch phone to dark mode | App readable in dark mode | |
| G2 | Rotate the phone on a few screens | No crash; entered text kept | |
| G3 | Largest font size in phone settings | Text readable, buttons reachable | |
| G4 | Airplane mode on, use the app | Everything works (the app never needs internet) | |
| G5 | Anything confusing, slow or wrong | Free notes | |

## Not covered by this checklist
- NFR-02/03 timing with 50,000+ transactions needs a test build with generated data and a USB connection (adb). It will be
  done separately if you connect the phone.
- Android 8.0 (the oldest supported version) needs an older phone or an emulator.
