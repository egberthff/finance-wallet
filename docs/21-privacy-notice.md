# Privacy Notice: Finance Manager (draft for legal review)

| Status | **Draft, not yet reviewed by counsel.** Do not publish before review under RA 10173 (Data Privacy Act of 2012) and NPC issuances (risk R-07). Facts below are verified against the code of 1.0.0-rc1 |
|---|---|

**Effective date:** [to be set on publication] · **Controller contact:** [owner name, address and email to be filled in]

## What the app does with your information
Finance Manager helps you record and understand your own money: accounts, transactions, budgets, savings goals, bills,
debts and assets that **you enter or import yourself**.

## Where your information is kept
- **Only on your phone.** The app has no internet permission and cannot send anything anywhere. There are no accounts,
  no servers, no analytics, no advertising and no tracking.
- Your records are stored in an **encrypted database** (SQLCipher, 256-bit key protected by Android Keystore).
- The app is protected by a 6-digit PIN and, if you choose, your phone's fingerprint or face unlock. The app does not
  receive your fingerprint or face data; Android only tells it whether unlocking succeeded.
- Android's own cloud backup and device-to-device transfer are switched off for this app, so your records are not copied
  to Google servers by the system.

## Files you choose to create
You decide whether to create these files and where they are saved, using Android's file picker:
- **Backup file (`.fwbak`)**: all records, encrypted with a passphrase you choose. Without the passphrase, nobody can read
  it, including us. If you forget the passphrase, the backup cannot be opened.
- **Data export (`.jsonl`) and CSV export**: readable copies of your records. Anyone who obtains these files can read them;
  the app warns you before creating them.
- **Crash reports (`.txt`)**: error types and code locations from times the app closed unexpectedly. They contain no
  amounts, names or other records. They are created on your phone and leave it only if you save and share them.

If you save a file to a cloud folder (for example Google Drive) through the file picker, that provider's privacy terms
apply to the file.

## Statement import
When you import a bank or e-wallet statement, the file is read on your phone. The app never asks for or stores bank or
e-wallet passwords, OTPs or login details, and does not connect to any bank.

## Permissions
| Permission | Why |
|---|---|
| Biometric | Optional fingerprint or face unlock |
| Notifications | Reminders before bills are due (you can refuse; the app works without it) |
| Run at startup | Re-schedule bill reminders after the phone restarts |

## Your rights and choices
Under the Data Privacy Act you have the right to be informed, to access, to object, to erasure or blocking, to data
portability, to rectification and to lodge a complaint with the National Privacy Commission. Because your records are
only on your phone, you exercise most of these rights directly in the app:
- **Access and portability:** Privacy & data → Export all data.
- **Rectification:** edit any record in the app.
- **Erasure:** Privacy & data → Delete all data (removes every record, your PIN and the encryption keys from the phone).
  Uninstalling the app also removes the data. Backup or export files you saved elsewhere are not touched; delete them
  yourself.

## Children
The app is intended for adults (18+).

## Changes
We will describe changes in the app's update notes and update the effective date above.

## Contact
[Owner contact details]. You may also contact the National Privacy Commission (privacy.gov.ph).
