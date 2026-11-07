# System Analysis Findings & Actionable Blueprint

| | |
|---|---|
| Date | 2026-11-07 (first analysis, re-analysis and implementation of blueprints C and C2, consolidated into this one file; it replaces the former `ANALYSIS2.md`) |
| Baseline | First analysis: branch `release/1.0.0-rc1` at `e5b8fb4` plus the working tree of that time. Status columns: `7920a48` **plus the uncommitted blueprint C and C2 work**. Line numbers in the *Evidence* columns refer to the first analysis and may have shifted; the category screens now live in `ui/categories/`. |
| Scope | Category management, transaction entry/editing, and the primary user flows that depend on them (budgets, bills, rules, accounts, reports) |
| Constraints respected | CLAUDE.md scope amendments (Android only, no network, no cloud, no external AI), ADR-0004 ledger model (revisions, no silent history loss), ADR-0012, PROGRESS.md matrix, `docs/24-known-limitations.md` |
| Method | Static reading of the UI → view model → service → DAO path for each feature; every finding cites the code it comes from. Findings marked *(verify)* are inferences that need a test or device check to confirm. The status of implemented items is backed by the test runs below. **Nothing has been run on an emulator or a device.** |

### Status at a glance

| Blueprint | State |
|---|---|
| **C**: category editing (rename, icon, colour, reorder) | **Done and tested**; device check and commit still open |
| **C2**: follow-ups found in the re-analysis (N-1..N-6, C-5 remainder) | **Done and tested** |
| **A**: archive, restore, name reuse (P0) | Step 1 (move to `ui/categories/`) done; archive now asks for confirmation. **Restore, undo, archived list, usage check: open. Top priority.** |
| **B**: edit transactions that use archived categories/accounts (P0) | Not started; **needs owner approval** |
| D, E, F, G, H, I | Not started |
| J | Android Lint now runs clean on the new code; the other items are open |

### Verification evidence (executed 2026-11-07, Temurin 21)

`gradlew check` (ktlint, detekt, Android Lint, unit tests and coverage for every module) → **BUILD SUCCESSFUL**. Two earlier runs failed on detekt (`LongMethod` and `CyclomaticComplexMethod` in the new composables); the functions were split and the final run passed.

| Suite | Tests | Result |
|---|---|---|
| `CategoriesViewModelTest` (new) | 7 | Executed, passed: a rename appears on old transactions and in report totals (names come from a join); name errors map to messages; reorder stays within siblings and saves only on Done; finishing without moves writes nothing; a reorder draft survives a reload and can be cancelled; new categories keep the chosen icon and colour; the colour offer shows for an old seed and disappears once applied |
| `CategoryIconsTest` (new) | 6 | Executed, passed: seed keys mapped, glyphs parse, unknown key falls back, every seed colour is in the palette, palette contrast ≥ 3:1 on the light and dark card surfaces |
| `AccountAndCategoryTest` | 13 (3 new) | Executed, passed: seeds get their group colour; suggested colours apply once and only to seeded rows still in the default colour; dismissing the offer changes nothing |
| `TransactionQueriesTest` | 3 (1 new) | Executed, passed: two "Other" subcategories give two ids and one name |
| All suites | app 63, data 108, domain 117, importers 20 | **308 tests, 0 failures, 0 errors** |

Not executed: an emulator run of the new dialogs and reorder mode, and a TalkBack check.

### Severity key
- **P0**: a user can reach a dead end, or a flow fails in a way the user cannot understand or recover from.
- **P1**: a capability exists in the services but cannot be reached from the UI, or a common task is markedly harder than it should be.
- **P2**: polish, consistency, or a smaller correctness/UX issue.

---

## 📊 Core Feature Usability Assessment

### 1. Category Management (UC-14, CAT-01..04)

**Where it lives**
- Service: [CategoryService.kt](data/src/main/kotlin/ph/financewallet/data/account/CategoryService.kt): `create`, `edit`, `archive`, `unarchive`, `reorder`, `seedDefaults`
- DAO: [AccountDao.kt:101-132](data/src/main/kotlin/ph/financewallet/data/account/AccountDao.kt#L101-L132)
- UI: `CategoriesViewModel` / `CategoriesScreen` / `AddCategoryDialog` are inside [HomeScreens.kt:367-510](app/src/main/kotlin/ph/financewallet/app/ui/home/HomeScreens.kt#L367-L510), not in their own feature package

**What works well**
- The service layer is solid: names are unique among siblings, case-insensitively; there is only one level of subcategories; archiving keeps history; a parent cannot be archived while it has live children; the PH seed is idempotent and never renames the user's own categories. `AccountAndCategoryTest` covers these rules.
- Category names are joined from `ledger_account.name` at query time, so a rename would show up across history, reports, and budgets automatically, with no data migration needed.

**What the user actually experiences (friction points)**

| # | Severity | Finding | Evidence | Status (2026-11-07) |
|---|---|---|---|---|
| C-1 | **P0** | **Archive is a dead end.** Archiving takes one tap, with no confirmation and no undo. Archived categories are never listed in the UI, and there is no unarchive action, although `CategoryService.unarchive` exists. | [HomeScreens.kt:463](app/src/main/kotlin/ph/financewallet/app/ui/home/HomeScreens.kt#L463), [HomeScreens.kt:380](app/src/main/kotlin/ph/financewallet/app/ui/home/HomeScreens.kt#L380) calls `categories()` with `includeArchived = false` | **Partly eased, still open.** Archive is no longer on the row: open the category, tap Archive, then confirm a dialog that names it. There is still no undo, no archived list and no restore (blueprint A). |
| C-2 | **P0** | **An archived name stays reserved.** `siblingsNamed` has no `archived_at` filter, although its KDoc says "Live sibling". If the user archives "Food" by mistake and then tries to add "Food" again, they get *"A category with that name already exists here"*, but the category is nowhere to be seen and cannot be restored from the UI. | [AccountDao.kt:112-123](data/src/main/kotlin/ph/financewallet/data/account/AccountDao.kt#L112-L123) | **Open** (blueprint A). |
| C-3 | **P1** | **No rename, icon, or colour editing in the UI.** `CategoryService.edit` exists but is never called from `app/`. | `grep categories.edit app/` → none | **Fixed and tested** (blueprint C): `EditCategoryDialog`. |
| C-4 | **P1** | **No reordering in the UI.** `CategoryService.reorder` exists but is never called. | same | **Fixed and tested** (blueprint C): reorder mode. |
| C-5 | **P1** | **Icon and colour are stored but never shown.** Every row uses `AppIcons.Label` with an income or expense tint, so the seed's icons (`restaurant`, `commute`, …) and `color_argb` are invisible. | [HomeScreens.kt:461](app/src/main/kotlin/ph/financewallet/app/ui/home/HomeScreens.kt#L461) | **Fixed and tested** (C, C2): category list, report rows, transaction rows and Home recent rows; seeds now carry colours, and old seeds get a one-time offer. The picker follows in blueprint D. |
| C-6 | **P1** | **Category archive has no impact check.** Archiving a category that is used by an active budget line, a bill or recurring rule, or an import rule gives no warning. Later, *Mark paid* on that bill or an auto-post fails with `CATEGORY_ARCHIVED` *(verify: `RecurringService.recordFor` builds through `EntryTemplates`, which rejects archived categories)*. | [EntryTemplates.kt:287-294](domain/src/main/kotlin/ph/financewallet/domain/ledger/EntryTemplates.kt#L287-L294) | **Open** (blueprint A: usage counts in the archive dialog). |
| C-7 | P2 | **Some service error codes have no message.** `ALREADY_ARCHIVED`, `NOT_ARCHIVED`, `PARENT_ARCHIVED`, `ICON_REQUIRED`, and `ORDER_MISMATCH` are missing from `ERROR_TEXT`, so the user sees the generic error. | [Errors.kt:40-73](app/src/main/kotlin/ph/financewallet/app/ui/common/Errors.kt#L40-L73) | **Open** (blueprint A). |
| C-8 | P2 | **Errors can render off-screen.** The archive error is a `LazyColumn` item near the top of the list. If the user taps *Archive* on a parent near the bottom (`CATEGORY_HAS_CHILDREN`), nothing visibly happens. | [HomeScreens.kt:425](app/src/main/kotlin/ph/financewallet/app/ui/home/HomeScreens.kt#L425) | **Partly fixed.** Edit and archive errors appear in the dialog. Seed, reorder and colour-offer errors still appear as a list item at the top (blueprint J). |
| C-9 | P2 | **The suggested-category seed is offered only while the list is empty.** After the user creates one category, the PH starter set can no longer be reached. | [HomeScreens.kt:415](app/src/main/kotlin/ph/financewallet/app/ui/home/HomeScreens.kt#L415) | **Open** (blueprint A). |
| C-10 | P2 | **Category changes write no audit event.** `AccountService` writes audit events; `CategoryService` writes none, so renames and archives leave no trace (CLAUDE.md §16 asks for audit logging). | [AccountService.kt:280](data/src/main/kotlin/ph/financewallet/data/account/AccountService.kt#L280) vs CategoryService | **Open, and more important now** that rename and reorder are reachable from the UI (blueprint A). |
| C-11 | P2 | **Case-insensitive matching is ASCII-only.** SQLite `lower()` folds ASCII only, so "Piñata" and "PIÑATA" count as different names. This matters for Filipino and Spanish names. | [AccountDao.kt:115](data/src/main/kotlin/ph/financewallet/data/account/AccountDao.kt#L115) | **Open** (blueprint J). |
| C-12 | P2 | **The add dialog's parent picker is a single horizontal chip row.** With the seed in place, that is 13 expense parents in one row, so the selected parent can scroll out of view. | [Common.kt:107-119](app/src/main/kotlin/ph/financewallet/app/ui/common/Common.kt#L107-L119) | **Open.** The add dialog now scrolls, but the parent row is still horizontal chips (blueprint J). |

**Findings from the re-analysis of the blueprint C code** (all P2; all fixed by blueprint C2)

| # | Finding | Fix (C2) |
|---|---|---|
| N-1 | Reorder changes could be lost without a prompt: `reload()` reset the draft, so re-entering or recreating the screen discarded it, and Back discarded it silently. | `reload()` keeps the draft (drops rows that are gone, appends new ones). Back with a changed order asks *Save the new order?* with Save, Discard and Cancel; Back with no change just leaves reorder mode. Tested. |
| N-2 | Archive in the edit dialog dropped unsaved edits and asked nothing. | Archive opens a confirmation that names the category and warns when edits made in the dialog will not be saved. |
| N-3 | All seeded categories shared one colour (slate). | Each `DefaultCategory` has a palette colour (income greens/teals, expenses spread out); subcategories take their group's colour. Seeds made before this get a one-time *Apply suggested colours* offer that changes only seeded rows still in the default colour, never a colour the user chose; *Not now* hides it for good. Tested. |
| N-4 | The `categoryIds` KDoc said "same order" as `categoryNames`, which is wrong when names repeat across parents. | KDoc corrected; `TransactionQueriesTest` covers two same-named subcategories. |
| N-5 | New categories could not be styled when created. | `AddCategoryDialog` has the icon and colour picker (shared `StylePicker`); choosing a parent copies the parent's icon and colour. Tested. |
| N-6 | The category screens still lived in `HomeScreens.kt`. | Moved to `ui/categories/CategoriesScreen.kt` and `CategoriesViewModel.kt` (also step 1 of blueprint A). |

### 2. Transaction Entry & Editing (TXN-06/13, UC-05..10)

> **Status (2026-11-07):** all findings T-1..T-9 below are still open. The only change in this area is visual: rows titled by their category now show the category's icon. T-1 now affects more users, because categories are easier to reach and therefore more likely to be archived.

**Where it lives**
- [TransactionsViewModels.kt](app/src/main/kotlin/ph/financewallet/app/ui/transactions/TransactionsViewModels.kt)
- [TransactionsScreens.kt](app/src/main/kotlin/ph/financewallet/app/ui/transactions/TransactionsScreens.kt)
- [LedgerService.kt](data/src/main/kotlin/ph/financewallet/data/ledger/LedgerService.kt)

**What works well**
- Each form has one idempotency key, so a double tap on Save stores the transaction once.
- Edits are revisioned and guarded by a stale-version check.
- The date picker is UTC-safe.
- The list is keyset-paged.
- Changing the entry type clears both the category and any stale error.

| # | Severity | Finding | Evidence |
|---|---|---|---|
| T-1 | **P0** | **A transaction cannot be edited once its category or account is archived**, even when the user changes only the amount, date, or description. The edit rebuilds the draft through `EntryTemplates`, which rejects archived categories and accounts. The archived category is also left out of the chip options, so the form shows no category selected and then fails with *"That category is archived."* The only way out is delete and re-enter, which loses the link to the revision history. | [EntryTemplates.kt:277-294](domain/src/main/kotlin/ph/financewallet/domain/ledger/EntryTemplates.kt#L277-L294), [LedgerService.kt:142](data/src/main/kotlin/ph/financewallet/data/ledger/LedgerService.kt#L142), [TransactionsViewModels.kt:117](app/src/main/kotlin/ph/financewallet/app/ui/transactions/TransactionsViewModels.kt#L117) |
| T-2 | **P1** | **The category picker does not scale.** All categories appear as one horizontal `LazyRow` of chips: about 37 expense chips with the seed, and no search, grouping, or recents. On edit, the preselected chip is usually off-screen. | [TransactionsScreens.kt:300-302](app/src/main/kotlin/ph/financewallet/app/ui/transactions/TransactionsScreens.kt#L300-L302), [Common.kt:114](app/src/main/kotlin/ph/financewallet/app/ui/common/Common.kt#L114) |
| T-3 | **P1** | **"Remembered account/category" is not implemented, although PROGRESS.md claims it.** The account defaults to the *first* account, and the category to none. A new `EntryViewModel` is created on each navigation, so nothing carries over between entries. | [TransactionsViewModels.kt:127](app/src/main/kotlin/ph/financewallet/app/ui/transactions/TransactionsViewModels.kt#L127); PROGRESS.md row "Phase 4 screens (4.8)" |
| T-4 | **P1** | **No inline way to create a category from the entry form.** With no categories, the form says *"Add some under More › Categories"*, which forces the user to abandon the entry they started. | [TransactionsScreens.kt:301](app/src/main/kotlin/ph/financewallet/app/ui/transactions/TransactionsScreens.kt#L301) |
| T-5 | **P1** | **No transaction detail view.** Tapping a row opens a dialog whose primary, red action is **Delete**, with Edit as the secondary button. The user cannot see the category, notes, reference number, source, revision history, or linked refunds. `LedgerDao.revisions` exists but is not shown anywhere. | [TransactionsScreens.kt:82](app/src/main/kotlin/ph/financewallet/app/ui/transactions/TransactionsScreens.kt#L82), [TransactionsScreens.kt:142-174](app/src/main/kotlin/ph/financewallet/app/ui/transactions/TransactionsScreens.kt#L142-L174), [LedgerDao.kt:99](data/src/main/kotlin/ph/financewallet/data/ledger/LedgerDao.kt#L99) |
| T-6 | **P1** | **Refunds and reversals cannot be recorded from the UI.** `LedgerService.refundOriginal` and the REFUND and REVERSAL templates exist, but the entry form offers only Expense, Income, and Transfer. CLAUDE.md §7 requires refunds to be linked to their original transactions. | [LedgerService.kt:442](data/src/main/kotlin/ph/financewallet/data/ledger/LedgerService.kt#L442) |
| T-7 | **P1** | **The transaction list has no filter or search**, although `TransactionQueries.page` already accepts `accountId` and a `TxnFilter` (category and dates). Only the Reports drill-down uses them. | [TransactionQueries.kt:36-76](data/src/main/kotlin/ph/financewallet/data/ledger/TransactionQueries.kt#L36-L76), [TransactionsViewModels.kt:51-54](app/src/main/kotlin/ph/financewallet/app/ui/transactions/TransactionsViewModels.kt#L51-L54) |
| T-8 | P2 | **Notes, merchant, and reference number cannot be entered.** The form has a description field only. `TxnHeader` supports all of them, and `edit` preserves them. | [TransactionsScreens.kt:213-219](app/src/main/kotlin/ph/financewallet/app/ui/transactions/TransactionsScreens.kt#L213-L219) |
| T-9 | P2 | **The Save button is never disabled while saving.** The idempotency key prevents a duplicate record, but a second tap can show an error after the screen has already closed. | [TransactionsScreens.kt:221-229](app/src/main/kotlin/ph/financewallet/app/ui/transactions/TransactionsScreens.kt#L221-L229) |

### 3. State Propagation & Reactivity (cross-cutting)

> **Status (2026-11-07):** R-1..R-3 still open. Transactions, Reports and Home now also load the category list (archived included) on every reload to get icons; that is cheap at realistic category counts.

- **Every screen pulls its data; nothing observes the database.** No `Flow<>` is returned by any DAO or service (`grep "Flow<" data/src/main` returns nothing). Each screen reloads in `LaunchedEffect(Unit)` when it enters composition, and after its own writes. Because of this, a change made on one screen appears on another only when that screen re-enters composition. In the current navigation that usually happens, so the data does not go stale in practice. However:
  - **R-1 (P1): Edit and delete lose the user's place.** After an edit or delete, [TransactionsViewModel.reload()](app/src/main/kotlin/ph/financewallet/app/ui/transactions/TransactionsViewModels.kt#L51-L54) replaces all loaded pages with page 1. A user fixing a transaction from three pages back is thrown back to the top.
  - **R-2 (P2): Background changes do not appear on an open screen.** Changes from bill-reminder auto-posting, or from an open screen behind a dialog, show up only after leaving and returning.
  - **R-3 (P2): No in-flight state.** Screens show a spinner only on the first load. Mutations have no in-flight indicator, and there are no snackbars or undo.
- **Optimistic UI is not needed.** Writes go to a local SQLCipher database in single-digit milliseconds, so the right pattern is *observe the database and disable controls while writing*, not optimistic updates. Optimistic updates would add rollback complexity with no user benefit.

### 4. Other primary features: service capability vs. UI reachability (orphan workflows)

| Feature | In the service | In the UI | Severity |
|---|---|---|---|
| Account edit (name, institution, opening balance) | `AccountService.edit` ([AccountService.kt:65](data/src/main/kotlin/ph/financewallet/data/account/AccountService.kt#L65)) | **No.** [AccountsViewModel](app/src/main/kotlin/ph/financewallet/app/ui/accounts/AccountsViewModel.kt) offers create, archive, unarchive, reconcile | **P1** |
| Account delete (no history) | `AccountService.delete` | No | P2 |
| Budget period types | `PeriodRule.Weekly / Biweekly / EveryNDays / SemiMonthly / CustomOnce` ([PeriodRule.kt](domain/src/main/kotlin/ph/financewallet/domain/budget/PeriodRule.kt)) | **Monthly only** (`createMonthly`, [BudgetsViewModels.kt:104](app/src/main/kotlin/ph/financewallet/app/ui/budgets/BudgetsViewModels.kt#L104)). CLAUDE.md §8 calls budgeting "one of the strongest features" | **P1** |
| Budget edit, rule change, archive | `editBudget`, `changeRule`, `archiveBudget` | No | **P1** |
| Per-category rollover, fixed/percent allocation, sweep goal | `BudgetCategorySpec` | No (create dialog sends only `BudgetCategorySpec(it)`) | P1 |
| Bill/rule edit, pause, cancel | `RecurringService.editRule`, `setStatus` | No | P1 |
| Goal edit, link accounts, withdraw, transfer | `GoalService.editGoal / linkAccounts / withdraw / transfer` | Create, earmark, release only | P1 |
| Debt status (e.g. written off) | `DebtService.setStatus` | No (known limitation) | P2 |
| Category edit, unarchive, reorder | see C-1..C-4 | Edit and reorder: **yes** (blueprint C). Unarchive: **no** | **P0** (unarchive) |

**Picker ambiguity (P2):** Subcategory names are unique only *under their parent*, but the budget, bill, and rule pickers label categories by bare name ([BudgetsViewModels.kt:80](app/src/main/kotlin/ph/financewallet/app/ui/budgets/BudgetsViewModels.kt#L80), [AdvancedViewModels.kt:172-173](app/src/main/kotlin/ph/financewallet/app/ui/advanced/AdvancedViewModels.kt#L172-L173), [ImportViewModel.kt:351](app/src/main/kotlin/ph/financewallet/app/ui/imports/ImportViewModel.kt#L351)). Only the entry form uses "Parent › Child". If a user creates "Other" under both Food and Bills, those pickers show two identical chips.

---

## 🧩 Required Functional Enhancements

Listed in recommended implementation order. Each item keeps the ledger rules intact: no silent history loss, revisions on transaction changes, and all writes through `validatedWrite`.

1. **Top priority now.** **Recoverable category lifecycle** (fixes C-1, C-2, C-6, C-7, C-8, C-10): an archive confirmation that states the impact (counts of budgets, bills, and rules using the category); an "Archived" section with **Restore**; when an add hits an archived name, offer *"Restore the archived 'Food'?"* instead of the bare name-taken error; audit events; messages for every error code; errors shown as snackbars or dialogs, not list items.
2. **Edits that leave the category or account unchanged must not be blocked by archive state** (fixes T-1). This is a domain rule change: an archived category or account stays valid **for an existing transaction that already used it**, but is still rejected for a new posting or a newly selected target. This fits CAT-04 ("archived categories stay on history and cannot receive new transactions"). Because it changes a validation rule, it needs **owner sign-off** (CLAUDE.md §22: product requirement change).
3. ✅ **Done (blueprints C and C2).** **Category editing UI** (fixes C-3, C-4, C-5): rename, icon, and colour editing, plus reordering with move up/down buttons (accessible; no drag library needed); render the stored icon and colour everywhere a category appears.
4. **A scalable shared category picker** (fixes T-2, T-4, C-12, picker ambiguity): one `CategoryPicker` component, opened as a bottom sheet from a single field: search box, recents first, grouped by parent, "Parent › Child" labels, an inline **+ New category** action, and archived categories shown read-only when editing a transaction that already uses one. Use it in Entry, Budgets, Bills, Rules, and Import.
5. **Remembered entry defaults** (fixes T-3 and corrects the PROGRESS.md claim): persist the last-used account, plus the last-used category per kind, in the `meta` table.
6. **Reactive data layer, kept small** (fixes R-1, R-2): an app-wide `dataChanges: Flow<Set<String>>` from Room's `InvalidationTracker`; view models re-query when their tables change; the transaction list refreshes in place without discarding loaded pages.
7. **Transaction detail and refund flow** (fixes T-5, T-6, T-8): a detail screen with all header fields, the category path, source, revision history, refunds, and actions (Edit, Record refund, Delete); notes and reference number on the entry form.
8. **Transaction filters** (fixes T-7): account, category, and date-range chips wired to the existing `TxnFilter`; text search only after an index decision (see blueprint F).
9. **Surface the orphaned service capabilities** (section 4): account edit; budget period types, edit, and archive; per-category budget settings; bill edit, pause, and cancel; goal edit and withdraw.
10. **Mutation feedback** (fixes T-9, R-3): disable Save while a write is in flight; show a snackbar with Undo for archive (Undo calls `unarchive`).

---

## 🛠️ Actionable Claude Code Implementation Blueprints

> Conventions for every blueprint: JVM/Robolectric tests next to the existing suites; strings in `strings.xml` only; Detekt, ktlint, and Lint must still pass (`gradlew check`); after each blueprint, update [PROGRESS.md](PROGRESS.md) and, where relevant, [docs/24-known-limitations.md](docs/24-known-limitations.md). Record each test as written, executed, passed, or failed (CLAUDE.md §23).

### A. Category lifecycle: archive, restore, and name reuse (P0): **top priority**

**Files:** `data/.../account/CategoryService.kt`, `data/.../account/AccountDao.kt`, `app/.../ui/categories/CategoriesScreen.kt` + `CategoriesViewModel.kt` + `CategoryEditing.kt`, `app/.../ui/common/Errors.kt`, `res/values/strings.xml`

- [x] Move `CategoriesState`, `CategoriesViewModel`, `CategoriesScreen`, `CategoryGroup`, `AddCategoryDialog`, and `ordered()` out of `HomeScreens.kt` into `ui/categories/`; update imports in `AppNavigation.kt:250`. This is a pure move and must not change behaviour. *(Done with C2 / N-6.)*
- [x] `AccountDao`: add `suspend fun archivedSiblingNamed(accountClass, parentId, name): String?`, which returns the id of an **archived** sibling with the same normalized name. *(Done differently: name clashes are now checked in Kotlin with Unicode case folding (J), so `CategoryService.archivedNamed` does this lookup and the SQL `siblingsNamed` query was removed.)*
- [x] `AccountDao`: add `suspend fun categoryUsage(id: String): CategoryUsage`, which counts live budget-category lines, active recurring rules, enabled category rules, and transactions for the category and its children. Confirm the table and column names in `data/schemas/.../4.json` before writing the SQL.
- [x] `CategoryService.create`: if a name clash is with an *archived* sibling, return a new error code `CATEGORY_NAME_ARCHIVED` whose field value carries the archived id. A clash with a live sibling keeps `CATEGORY_NAME_TAKEN`. Leave the uniqueness rule unchanged: archived names stay reserved, so that a restore can never create a duplicate.
- [x] Fix the KDoc on `siblingsNamed` ("Live sibling" → "Any sibling, archived included"). *(Moot: `siblingsNamed` was removed; see above.)*
- [x] `CategoryService`: write an `audit_event` for create, edit, archive, unarchive, reorder, seed, and the suggested-colour action, reusing the `AccountService.audit` pattern (extract a shared helper if both need it). Record the old and new name, icon and colour on edit; rename and reorder are now reachable from the UI.
- [x] `CategoriesViewModel`: load with `includeArchived = true` and expose `live` and `archived` lists; add `unarchive(id)`, `usage(id)`, and `restoreInsteadOfCreate(archivedId)`.
- [x] `CategoriesScreen`: add a collapsible **"Archived (n)"** section with a **Restore** action per row. A subcategory whose parent is archived shows the hint "Restore *Parent* first", mapped from `PARENT_ARCHIVED`.
- [x] Archive confirmation dialog: *"Archive 'Food'? It stays on 214 past transactions. 1 budget and 2 bills use it; they will need a new category."* Disable Archive with an explanation when the category has live children, instead of failing after the tap. *(A basic confirmation that names the category and warns about unsaved edits already exists in `EditCategoryDialog` (N-2). Extend it with the usage counts and the live-children check.)*
- [x] Add dialog: on `CATEGORY_NAME_ARCHIVED`, show *"An archived category named 'Food' exists. Restore it?"* with Restore and Cancel buttons.
- [x] Replace the in-list `ErrorLine` with a `SnackbarHost` (add one to `ListScreen` in `ui/common/Layout.kt`, or to the screen's Scaffold); archive success shows **Undo**, which calls `unarchive`. The edit dialog closes on a successful archive, so the screen-level host (not the dialog) shows *"Archived Name. Undo"*.
- [x] `Errors.kt`: map `ALREADY_ARCHIVED`, `NOT_ARCHIVED`, `PARENT_ARCHIVED`, `ICON_REQUIRED`, `ORDER_MISMATCH`, and `CATEGORY_NAME_ARCHIVED`; add the strings.
- [x] Show the "Add suggested categories" action always (as an overflow or secondary button); `seedDefaults` already skips existing names. Note: it is guarded by `seed:categories:v1`, so a second run is a no-op. Decide whether to keep a single run or allow re-running for archived and missing ones, and document the decision. *(Decision 2026-11-07, recorded in docs/12-use-cases.md UC-14: the action is always offered and adds only missing starter categories; existing names, archived included, are skipped.)*
- [x] Tests (`AccountAndCategoryTest`): archived-name clash returns `CATEGORY_NAME_ARCHIVED`; restore-then-create still clashes as taken; `categoryUsage` counts; audit rows written for each operation.
- [x] Tests (new `CategoriesViewModelTest` or extend `ScreenViewModelsTest`): archive → appears in archived → unarchive → live again; undo path.

**Expected behaviour:** archiving can never leave a user stuck: every archived category is visible and restorable, and re-adding a name offers the restore.

### B. Editing transactions that use archived categories or accounts (P0, needs owner approval)

**Files:** `domain/.../ledger/EntryTemplates.kt`, `data/.../ledger/LedgerService.kt`, `app/.../ui/transactions/TransactionsViewModels.kt`, `docs/10-domain-model.md`, `docs/12-use-cases.md`

- [x] **Get owner approval first:** "An archived category or account remains valid on an edit of a transaction that already references it; it still cannot be newly selected." Record the decision in `docs/12-use-cases.md` (UC-09 notes) and `docs/10-domain-model.md`. *(Owner instruction 2026-11-07: "do it all" after being told this needed approval. Recorded in docs/10-domain-model.md §3.6 and docs/12-use-cases.md UC-09.)*
- [x] `EntryTemplates`: add an optional `allowArchived: Set<String>` constructor parameter (default empty); `realAccount()` and `category()` skip the archived check for ids in that set.
- [x] `LedgerService.edit`: build with `EntryTemplates(directory, allowArchived = before.map { it.ledgerAccountId }.toSet())`.
- [x] `EntryViewModel.loadExisting`: if the transaction's category or account is archived, add it to `options` as a read-only option labelled "(archived)", so the selection is visible.
- [x] Tests: `EntryTemplatesTest`: an archived id is allowed only when it is in `allowArchived`. `LedgerServiceTest`: edit the amount of a transaction whose category was archived → success plus a revision; change it *to* a different archived category → `CATEGORY_ARCHIVED`. Same pair of tests for an archived account.
- [x] Re-run `LedgerPropertyTest` and `ReportCrossCheckTest` to confirm no invariant regressions. *(Both ran in the full `gradlew check`, see the verification table.)*

**Expected behaviour:** a user can always correct the amount, date, or description of an old transaction; archive only stops *new* use.

### C. Category editing UI: rename, icon, colour, reorder (P1): **done**

**Files:** `app/.../ui/categories/*`, **new** `app/.../ui/theme/CategoryIcons.kt`, `app/.../ui/theme/AppIcons.kt`, `strings.xml`

- [x] `CategoryIcons.kt`: map each stored icon key (`restaurant`, `commute`, `receipt`, `home`, `health`, `account_balance`, `family`, `school`, `shopping`, `spa`, `movie`, `gift`, `fee`, `work`, `laptop`, `store`, `savings`, `add`, `category`) to an `ImageVector`, with a fallback to `AppIcons.Label`. Use only icons already available in the project's icon set; check `AppIcons.kt` before adding a dependency.
- [x] A fixed palette of 10–12 colours with sufficient contrast in light and dark mode (check against `Theme.kt` tokens); store them as ARGB in `color_argb`.
- [x] `CategoryIcon(row: CategoryRow)` composable that renders the stored icon and colour; use it in the category list, the picker (blueprint D), the report category rows, and the transaction rows when no description is set. *(Done for the list, report rows, transaction rows, and (with C2) the Home "recent" rows. The picker does not exist yet; it picks this up in blueprint D.)*
- [x] `EditCategoryDialog`: name field (pre-filled), icon grid, colour swatches; the Save button calls `CategoryService.edit`; inline errors for `NAME_LENGTH` and `CATEGORY_NAME_TAKEN`.
- [x] Tapping a category row opens the edit dialog. Archive moves into that dialog or an overflow menu, so it is no longer a bare one-tap button on the row.
- [x] Reorder mode: a "Reorder" action toggles ↑/↓ icon buttons per row (48dp targets, content descriptions "Move Food up"), scoped to siblings; on exit, call `CategoryService.reorder(class, parentId, ids)`.
- [x] Tests: VM rename propagates. Check that `TransactionQueries.page()` returns the new name for old transactions, because names come from a join.

- [ ] Device check of the edit dialog, the add dialog, and reorder mode (including Back while reordering), with TalkBack, on the emulator (Phase 8 rules); record the result in `docs/qa/test-state.md`. *(Partly done 2026-11-07: edit dialog, archive confirmation and Undo passed on the API 33 emulator (debug and release); reorder with Back and TalkBack not yet run. See docs/qa/test-state.md §1a.)*
- [ ] Commit the C and C2 work. It is uncommitted and sits on `release/1.0.0-rc1`; **owner decision:** does it belong on the release branch or on a `feature/` branch off `main`?

**Expected behaviour:** a rename shows up instantly in history, reports, and budgets with no data rewrite; icons and colours make the lists scannable.

### C2. Follow-ups to blueprint C (P2): **done**

**Files:** `app/.../ui/categories/CategoriesScreen.kt` (new), `CategoriesViewModel.kt` (new), `CategoryEditing.kt`, `ui/home/HomeScreens.kt`, `ui/transactions/TransactionsScreens.kt`, `ui/navigation/AppNavigation.kt`, `data/.../account/CategoryService.kt`, `data/.../ledger/TransactionQueries.kt`, `strings.xml`

- [x] **N-6:** Move the category screen, view model, dialogs and helpers into `ui/categories/`; update `AppNavigation.kt` and the test imports.
- [x] **N-1:** `reload()` keeps an existing reorder draft (drops rows that are gone, appends new ones); a `BackHandler` while reordering asks *Save the new order?* (Save, Discard, Cancel) when the order changed, and otherwise just leaves reorder mode. Test: the draft survives `reload()`, and `cancelReorder()` saves nothing.
- [x] **N-2:** Archive in `EditCategoryDialog` opens a confirmation that names the category and says when edits made in the dialog will not be saved. *(Usage counts follow in blueprint A.)*
- [x] **N-5:** `StylePicker` (now `internal`) in `AddCategoryDialog`; choosing a parent copies its icon and colour; `CategoriesViewModel.create` passes both to `CategoryService.create`. Test: a new category keeps the chosen icon and colour.
- [x] **N-3:** Each `DefaultCategory` has a palette colour; subcategories share their group's colour. A one-time *Apply suggested colours* offer (`CategoryService.suggestedColors` / `applySuggestedColors` / `dismissSuggestedColors`, guarded by meta key `seed:category-colors:v1`) changes only rows with `is_default_seed = 1` and the default colour. Tests: seeds get colours; a colour the user chose and a user-made category are untouched; the offer works once; *Not now* changes nothing. *(The audit event for this action is part of A's audit item.)*
- [x] **N-4:** `categoryIds` KDoc corrected ("distinct ids; not aligned with `categoryNames`"); `TransactionQueriesTest` case with two "Other" subcategories under different parents.
- [x] **C-5 remainder:** Home `RecentCard` uses `CategoryIcon` with the same rule as `TransactionRow` (shared `categoryStyleFor`); `HomeViewModel.load` loads `categoryStyles`.

### D. Shared, scalable `CategoryPicker` (P1)

**Files:** **new** `app/.../ui/common/CategoryPicker.kt`, **new** `app/.../ui/common/CategoryOptions.kt`; consumers: `ui/transactions/TransactionsScreens.kt` + `TransactionsViewModels.kt`, `ui/budgets/BudgetsScreens.kt` + `BudgetsViewModels.kt`, `ui/advanced/AdvancedScreens.kt` + `AdvancedViewModels.kt` (bills), `ui/imports/ImportScreens.kt` + `ImportViewModel.kt` (preview rows, rules)

- [x] `CategoryOptions.kt`: one function `categoryOptions(rows, cls, includeIds = emptySet())` that returns `CategoryOption(id, name, parentName, path = "Parent › Child", icon, color, archived)`. Use it everywhere; it replaces the four ad-hoc `Option(it.id, it.name)` mappings and fixes the duplicate-label ambiguity. *(Lives in `ui/categories/CategoryOptions.kt`, next to the picker, to avoid a common → categories dependency.)*
- [x] `CategoryPicker`: a read-only field showing the selected path and icon; a tap opens a `ModalBottomSheet` with a search field (`TextNormalizer.normalize` for case- and accent-insensitive matching), a "Recent" section (blueprint E), the categories grouped under their parents, and a **+ New category** row.
- [x] The **+ New category** row opens the add dialog prefilled with the search text and the current class; on success, select the new id and close the sheet. This removes the "go to More › Categories" dead end (T-4).
- [x] Reuse `CategoryIcon` for option rows and the shared `StylePicker` for the inline "+ New category" step (both exist since C/C2).
- [x] Budget create uses a multi-select variant (checkbox rows grouped by parent) instead of the `FlowRow` of chips.
- [x] Remove `entry_no_categories` usages, or reword the string to point to the inline action. *(Reworded; used only by the budget checklist now.)*
- [x] Tests: a Compose UI test in the style of `EntryScreenUiTest`: open the picker, search "groc", select it, save; then create a category inline from the picker and save the transaction with it.

**Expected behaviour:** a category can be picked in about two taps with 40+ categories, the selection is always visible, and a category can be created without leaving the entry.

### E. Remembered entry defaults (P1)

**Files:** `data/.../account/AccountDao.kt` (existing `meta`/`setMeta`), **new** `data/.../ledger/EntryPreferences.kt` (or a small addition to `AppServices.kt`), `app/.../ui/transactions/TransactionsViewModels.kt`, `PROGRESS.md`

- [x] Store `entry:last_account`, `entry:last_category:EXPENSE`, and `entry:last_category:INCOME` in the meta table after each successful *new* save (not after edits).
- [x] `EntryViewModel.loadOptions`: default the account and category to the remembered ids when they are still live, otherwise fall back to the first account and no category.
- [x] The picker's "Recent" section: the 5 most-used categories of the last 60 days. Add a DAO query over `txn_entry` joined with `txn` by date, using the existing `ix_txn_date` index; measure it on a 50k-row fixture before shipping. *(Measured: 98 ms on 50,000 transactions on the JVM; the query plan uses `ix_txn_date` (EntryPreferencesTest).)*
- [x] Correct the PROGRESS.md "Phase 4 screens (4.8)" row, which currently claims "remembered account/category" ahead of the implementation.
- [x] Tests: save an expense with category X, open a new `EntryViewModel`, and expect category X and the same account to be pre-selected; archive X and expect no preselection.

### F. Reactive data and list position (P1)

**Files:** `app/.../AppServices.kt`, `data/.../db/FinanceDatabase.kt`, `ui/transactions/TransactionsViewModels.kt`, `ui/home/HomeScreens.kt`, `ui/accounts/AccountsViewModel.kt`, `ui/budgets/BudgetsViewModels.kt`, `ui/categories/*`

- [x] Before coding, check the `InvalidationTracker` flow API available in Room 2.8.5 (`createFlow(vararg tables)`) in the Room release notes or source. Do not assume the signature. *(Verified with javap on room-runtime-android 2.8.5: `createFlow(vararg String, boolean emitInitialState = true): Flow<Set<String>>`.)*
- [x] `AppServices`: expose `fun changes(vararg tables: String): Flow<Unit>`, backed by the tracker and debounced by about 100 ms.
- [x] View models collect `changes(...)` for their tables in `init` and call `reload()`. Remove the `LaunchedEffect(Unit) { vm.refresh() }` reloads where this replaces them, so the same data is not loaded twice.
- [x] `TransactionsViewModel.reload()`: reload the same *number* of already-loaded rows (`limit = items.size.coerceAtLeast(PAGE)`), keep the cursor, and keep the `LazyListState` (hoist it into the VM or `rememberSaveable`), so an edit or delete does not jump the user to the top.
- [x] Tests: Robolectric. A write through `LedgerService` while `TransactionsViewModel` is active emits a new state without an explicit refresh; after `loadMore()` and then an edit of a row on page 2, the item count is unchanged.

**Expected behaviour:** a change anywhere (entry, import, auto-posted bill) appears on any open screen; the user keeps their place.

### G. Transaction detail, refunds, and richer entry (P1)

**Files:** **new** `app/.../ui/transactions/TransactionDetailScreen.kt` + `TransactionDetailViewModel.kt`, `ui/navigation/AppNavigation.kt` (route `txn/{id}`), `ui/transactions/TransactionsScreens.kt`, `TransactionsViewModels.kt`, `data/.../ledger/LedgerService.kt` (read API for revisions), `strings.xml`

- [x] `LedgerService`: add a read-only `history(txnId): List<RevisionSummary>` (wrapping `LedgerDao.revisions`) and `refundsOf(txnId)` (wrapping `refundedAgainst`).
- [x] Detail screen: amount, type, date and posting date, account(s), category path with icon, description, merchant, notes, reference, source (manual, import batch, system), status, created and modified times, revision list, linked refunds or reversal.
- [x] Actions: **Edit** (existing route), **Record refund** (only for expenses; amount ≤ the amount not yet refunded; uses `refundOriginal`), **Delete** (confirmation dialog stating that a revision is kept). Delete is no longer the default action when a row is tapped.
- [x] Row tap → detail. Remove the current Delete/Edit dialog.
- [x] Entry form: a collapsible "More details" section with Notes, Merchant, and Reference fields mapped to `TxnHeader`.
- [x] Disable Save while a save is in progress (`saving` flag in the VM) and show a progress indicator on the button.
- [x] Tests: refund linked to its original; a refund above the remaining amount is rejected with a mapped message; the detail screen shows the revision after an edit; notes survive an edit.

### H. Transaction list filters (P1)

**Files:** `ui/transactions/TransactionsViewModels.kt`, `TransactionsScreens.kt`, `data/.../ledger/TransactionQueries.kt` (read-only changes, if any)

- [x] `TransactionsState.filter: TxnFilter` plus `accountId`; a filter bar with Account, Category (`CategoryPicker`), and Date range (`DateRangePicker`) chips and a "Clear" chip.
- [x] Pass the filters into `s.transactions.page(accountId, filter = …)`; the keyset paging stays unchanged.
- [x] Deep-link entry points: Accounts row → transactions for that account; category row → transactions for that category.
- [x] Free-text search: **do not add it** until an index strategy is decided (FTS4 versus normalized-prefix column, since SQLCipher supports FTS). Write a short ADR if it is pursued, because it changes the schema (v5 migration plus a `MigrationTest`). *(Followed: not added. Recorded in docs/24-known-limitations.md.)*
- [x] Tests: filtering by an account, a category (including subcategories), and a date range returns the same rows as the Reports drill-down for the same inputs.

### I. Surface orphaned service capabilities (P1)

**Files:** `ui/accounts/AccountsScreen.kt` + `AccountsViewModel.kt`, `ui/budgets/BudgetsScreens.kt` + `BudgetsViewModels.kt`, `ui/advanced/AdvancedScreens.kt` + `AdvancedViewModels.kt`, `strings.xml`

- [x] **Account edit:** an edit dialog (name, institution, opening balance) → `AccountService.edit`. Show that changing the opening balance creates a revision; the currency stays locked.
- [x] **Budget period types:** the create dialog gets a period selector: Monthly (start day), Semi-monthly, Weekly, Every 2 weeks, Every N days, Custom once (start/end). Map each to the existing `PeriodRule` variants; reuse `PeriodCalculator` for a "Next 3 periods" preview line so the user sees the dates before saving.
- [x] **Budget edit and archive:** edit the name, categories, and income mode via `editBudget`; change the period rule via `changeRule`, showing `ruleChangeEffectiveDate`; archive via `archiveBudget` with a confirmation.
- [x] **Per-category budget settings:** in the budget edit screen, per category: allocation mode (none, fixed, percent), default amount, rollover policy, carry-negative toggle, and sweep goal.
- [x] **Bills:** edit the rule (`editRule`); pause, resume, or end it (`setStatus`).
- [x] **Goals:** edit (`editGoal`), link accounts (`linkAccounts`), withdraw with an explicit confirmation and reason (CLAUDE.md §9), transfer between goals.
- [x] Update `docs/24-known-limitations.md` and remove each item once it is shipped.
- [x] Tests: one VM test per new action in `Phase5ViewModelsTest` and `ScreenViewModelsTest`, plus a `PeriodCalculator`-backed preview test for each period type, including a year boundary and 29 February. *(Tests are in the new `OrphanActionsViewModelTest` and `PeriodFormTest` rather than the older suites.)*

### J. Consistency and polish (P2)

- [x] Show all mutation errors and successes through one `SnackbarHost` per screen instead of `ErrorLine` list items (C-8, R-3). *(Categories uses a snackbar host with Undo; elsewhere every remaining `ErrorLine` sits inside a dialog or directly above the button that caused it, so it is always on screen.)*
- [x] Category names: compare using `TextNormalizer` (Unicode case folding) in Kotlin inside `checkName`, or add a normalized-name column in a later migration; test "Piñata" vs "PIÑATA" (C-11). *(Done with `Normalizer` NFC + `lowercase(Locale.ROOT)`, not `TextNormalizer`: that strips accents and would wrongly treat "Año" and "Ano" as one name. Tested.)*
- [x] Replace the remaining horizontal `ChoiceChips` used for long lists (accounts and categories) with the picker, or with a wrapping `FlowRow` when there are 8 or fewer options (C-12). *(`ChoiceChips` now wraps (`FlowRow`) everywhere; categories use the picker.)*
- [x] Accessibility pass on the new components: content descriptions, 48dp targets, TalkBack order. Add the checks to `docs/qa/device-test-checklist.md`. *(Code side done: content descriptions, 48 dp targets, selection state announced. Checks I1–I4 added to the device checklist; a TalkBack run is part of the device verification below.)*
- [ ] Device verification (Phase 8 rules): run each new flow on the emulator per `docs/qa/test-state.md`, and record the results there and in PROGRESS.md. *(Partly done 2026-11-07: H1, H2, H5, H8, H9, H13 passed on API 33 debug; release (R8) smoke passed; one defect found and fixed (green category lines on the detail screen). Remaining checks listed in docs/qa/test-state.md §1a.)*
- [x] Run Android Lint on the new composables. *(It runs as part of `gradlew check`, which passed on 2026-11-07.)*

---

### Scorecard (2026-11-07)

| Area | Findings | Fixed | Partly fixed | Open |
|---|---|---|---|---|
| Categories (C-1..C-12) | 12 | 3 (C-3, C-4, C-5) | 2 (C-1, C-8) | 7 |
| Re-analysis of the C code (N-1..N-6) | 6 | 6 | 0 | 0 |
| Transactions (T-1..T-9) | 9 | 0 | 0 | 9 |
| Reactivity (R-1..R-3) | 3 | 0 | 0 | 3 |
| Service features with no UI (section 4) | 9 rows | 0 | 1 (categories: edit and reorder yes, unarchive no) | 8 |

**Both P0 items (C-1/C-2 and T-1) are still open.** Editing is now easier, which makes archiving more likely and raises the cost of having no restore. Do blueprint A next, and ask the owner about blueprint B's rule change at the same time.

---

### Out of scope / deliberately not recommended
- **Optimistic UI updates:** local writes are fast and validated; optimistic state would only add rollback paths.
- **Drag-and-drop reorder library:** the up/down buttons are accessible and add no dependency.
- **AI category suggestions:** excluded by ADR-0012. The existing deterministic rules and history-based suggestions stay the mechanism, and could later be offered on manual entry, not only on import.
