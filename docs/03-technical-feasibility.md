# Technical Feasibility Report

| Status | Phase 1 deliverable, revision 2 |
|---|---|
| Date | 2026-11-05 |

> **Scope change (2026-11-05, owner):** iOS is dropped (ADR-0009) and direct API integration is dropped (ADR-0006).
> Data enters only by manual entry or file import. §3 is kept as the research record behind that decision.
> iOS-related constraints in §1 no longer apply.

Each claim is labeled **[Verified]** (observed directly or confirmed from an official source),
**[Reported]** (secondary source, not confirmed by the provider), or **[Unverified]** (no evidence found, must be confirmed before relying on it).

---

## 1. Development environment inventory

Observed by running commands on the development machine on 2026-11-05. **[Verified]**

| Item | Finding | Consequence |
|---|---|---|
| OS | Windows 10 Pro 10.0.19045 | **iOS apps cannot be built or simulated locally.** iOS needs macOS (Xcode). |
| CPU / RAM | Intel i5-12500T (12 threads) / 8 GB | An Android emulator will run but tightly alongside IDE + Metro/Gradle. A physical device is preferred. |
| Free disk | 26 GB free of 236 GB (90 % used) | Android Studio + SDK + system image + Gradle caches need ~15–20 GB. **This is tight.** |
| Node.js / npm | v22.12.0 / 10.9.0; npm registry reachable | JS/TS toolchain available now |
| Python | 3.13 | Available for tooling/scripts |
| Git | 2.47.1; repo initialized, **no commits**, branch `master` | Need initial commit + `main` branch |
| Java / JDK | **Not installed** | Required for any native Android build (all frameworks) |
| Android SDK / adb | **Not installed** | Required for local Android builds and device debugging |
| Flutter / Dart, Kotlin, Gradle | **Not installed** | — |
| Docker, PostgreSQL, sqlite3 CLI | **Not installed** | Backend local dev needs either Docker or a native Postgres install |
| GitHub CLI | Not installed | No remote configured; CI provider undecided |
| Internet | Reachable (npm registry, pub.dev) | Package installs and cloud builds possible |

**What requires setup (rev. 2, native Kotlin stack):**
- **JDK 17+** (~0.3 GB). This alone is enough to build and test the pure-Kotlin `:domain` engine, the highest-risk part of the system.
- **Android SDK** command-line tools + one platform + build tools (~5–8 GB without an emulator image) for app builds.
  Use a physical device over USB instead of an emulator, given the RAM and disk limits.
- Docker or PostgreSQL only if the optional Phase 7 backend is built.

---

## 2. Feature feasibility

| Area | Feasibility | Notes |
|---|---|---|
| Accounts, transactions, transfers, categories | **High** | Standard ledger modeling; fully local |
| Custom budget periods & multiple budgets | **High** | Pure date arithmetic in a fixed timezone; fully unit-testable |
| Savings (virtual + actual) | **High** | Needs a separate allocation ledger to avoid double counting (see architecture) |
| Debts, bills, net worth, reports | **High** | Derived from the ledger; performance needs indexes and pre-aggregation for large histories |
| Encrypted local DB | **High** | Room + SQLCipher, DB key wrapped by an Android Keystore key |
| CSV / XLSX import | **High** | Deterministic parsing plus user mapping |
| PDF statement import | **Medium–Low** | Text-layer PDFs are parseable per known layout. Scanned PDFs would require OCR (unreliable, out of scope). Each bank layout needs real sample statements and changes without notice. Some PH statements are password-protected PDFs, so the user must supply the password locally and it is never stored. |
| Local encrypted backup/restore | **High** | Encrypted export file with a passphrase-derived key (Argon2id/scrypt) |
| E2E-encrypted cloud backup | **Medium–High** | Server stores opaque blobs only |
| Multi-device sync with conflict handling | **Medium** | The hardest engineering item. E2E encryption means the server cannot merge, so merging happens on the client and must be deterministic. Plan for dedicated design and testing time. |
| Bill reminders / background work | **High** | Android WorkManager/AlarmManager; timing best-effort under Doze/OEM battery policies |
| Bank/e-wallet integrations | **Out of scope** (owner decision) | §3 documents why direct access was not available anyway |
| Fast manual entry | **High** | Pure UX work; the main adoption lever now that there are no feeds (R-19) |
| AI assistant | **Medium** | Technically simple via a server-side proxy. Privacy and consent design is the real work. It must stay optional. |

---

## 3. Philippine financial integrations: research findings

Research performed 2026-11-05 via web search. **None of these findings amounts to verified access.** Verified access
means credentials issued to this project and a successful sandbox call, and no provider has reached that point.

### 3.1 Regulatory context
- **BSP Circular No. 1122 (June 2021)** established the Open Finance Framework, which is consent-based,
  industry-led, BSP-supervised, and implemented in tiers. **[Reported]** by BusinessWorld and Inquirer; the BSP's own circular text should be read directly.
- An **Open Finance PH Pilot** launched 2023-06-21 with IFC support, starting with payments and savings accounts. **[Reported]**, with BSP-hosted pilot description and FAQ annexes present at bsp.gov.ph (Annex G/H).
- A July 2025 report says the BSP **adjusted its implementation timeline** and ran an "OFxPERA" pilot. A third-party
  tracker cites a 2027 compliance date. **[Reported / conflicting]**. No evidence was found that participation is
  mandatory for all institutions or that third-party PFM apps currently have general access.
- **Conclusion:** Open finance in the Philippines is **not yet a general-access regime** for a new PFM app. Access is
  through pilot participation, aggregator contracts, or bilateral partnerships, and each of those requires a legal entity and commercial agreements.

### 3.2 Per-institution status

| Institution | Public consumer-data API for third-party PFM? | Evidence | Status |
|---|---|---|---|
| **GCash** (Mynt) | None found | GCash Help Center documents in-app history (recent 90 days) and **emailed transaction-history requests** for older ranges. Business export is CSV from a portal. Public developer material found relates to *payment acceptance* (e.g., via Alipay+/acquirers), not account data. | **Unavailable.** Fallback: user-requested statement import. |
| **Maya** | None found | Search returned no account-information API for consumers. Maya's known developer offerings are merchant payment APIs (not verified in this research). | **Unavailable / Unverified.** Fallback: statement import. |
| **BDO** | None found | No aggregator coverage found in research. | **Unavailable.** Fallback: statement import. |
| **BPI** | Not public | Listed as a partner on Brankas materials. **[Reported]** | **Requires aggregator contract** |
| **UnionBank** | Partner developer portal exists (developer.unionbankph.com) with balance/transaction-inquiry style APIs historically reported | 2019 press reports, community library. **[Reported]**. Current access terms are unknown and likely require partnership/onboarding. | **Requires partnership; Unverified** |
| **GoTyme** | None found | No research hits. | **Unavailable / Unverified** |

### 3.3 Aggregators

| Aggregator | Finding | Open concerns |
|---|---|---|
| **Brankas** | Describes itself as an open finance provider; its FAQ (fetched) lists Direct, Disburse, Payment Infrastructure, and an Open Finance Suite. The FAQ claims NPC registration, ISO 27001, PCI-DSS. A 2022 report says it was the first approved financial API service provider in the open finance pilot. Sandbox access is via sign-up/contact. **[Reported]** | **The user-authentication method for data retrieval is not documented on public pages.** If it relies on the end-user entering bank credentials into an aggregator UI (credential-based/RPA), it may conflict with the "never automate credential-based logins" rule and must be assessed before use. Pricing, coverage, and contract terms unknown. |
| **Finverse** | Referenced alongside Brankas in a 2022 white paper on **RPA-based APIs** for PH open finance. **[Reported]** | RPA-based (bot logs in with user credentials) access would **violate project rules**. Not usable unless a consent/token-based path is confirmed. |
| Others | A tracker lists ~13 aggregators with PH coverage; the page was rate-limited (HTTP 429) and could not be read. | To research in Phase 6. |

### 3.4 Integration decision (**Accepted**, ADR-0006, 2026-11-05)
The owner dropped direct API integration. The app has no bank, e-wallet, or aggregator connection. Data enters by
**manual entry** or **on-device file import** (CSV, XLSX, PDF for verified layouts). No provider interface, broker, or token
handling is built. The transaction `source` field stays extensible, so the decision is reversible without a schema redesign.

---

## 4. Feasibility verdict

The revised product (**Android only, manual entry + file import**) is **feasible** as a local-first finance app with
budgeting, savings, debts, bills, reports, encrypted backup, and optional E2E-encrypted sync. The scope change removes the
two lowest-feasibility items (direct integrations, iOS from Windows). It also simplifies regulatory exposure, because no
third-party bank data processors are involved.

The main new feasibility dependency is **real statement samples** (Q-11). Institution-specific import profiles cannot be
built or verified without them, although generic CSV/XLSX import with manual mapping can.

### Sources
- [BSP Open Finance Pilot Description (Annex G)](https://www.bsp.gov.ph/Pages/InclusiveFinance/Open%20Finance/Annex%20G.pdf)
- [BSP Open Finance Pilot FAQ (Annex H)](https://www.bsp.gov.ph/Pages/InclusiveFinance/Open%20Finance/Annex%20H.pdf)
- [IFC: Launch of Open Finance PH Pilot (2023)](https://www.ifc.org/en/pressroom/2023/ifc-and-philippine-central-bank-pave-the-way-for-banking-transformation-with-launch-of-open-finance-ph-pilot)
- [Manila Bulletin: Open finance pilot now live (2023)](https://mb.com.ph/2023/11/1/article-1307)
- [Manila Bulletin: BSP's PERA push (2025-07-03)](https://mb.com.ph/2025/07/03/bsps-pera-push-seen-advancing-open-finance-in-philippines)
- [Inquirer: BSP rolls out open finance framework](https://business.inquirer.net/338311/bsp-rolls-out-open-finance-framework/amp)
- [Open Banking Tracker: Philippines Open Finance](https://openbankingtracker.com/regulation/philippines-open-finance)
- [GCash Help: Request transaction history](https://help.gcash.com/hc/en-us/articles/360034155433)
- [GCash Help: GCash for Business transaction history](https://help.gcash.com/hc/en-us/articles/48457463083545)
- [Brankas FAQ](https://brankas.com/faqs)
- [UnionBank API portal report (2019)](https://www.lionheartv.net/2019/09/unionbank-builds-philippine-bankings-largest-api-developer-portal-in-just-3-years)
- [Manila Bulletin: The potential of open finance (2022)](https://mb.com.ph/2022/08/16/the-potential-of-open-finance)
