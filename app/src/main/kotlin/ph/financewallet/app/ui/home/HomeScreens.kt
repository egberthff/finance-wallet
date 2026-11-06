package ph.financewallet.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ph.financewallet.app.AppServices
import ph.financewallet.app.R
import ph.financewallet.app.ui.common.AmountText
import ph.financewallet.app.ui.common.ChoiceChips
import ph.financewallet.app.ui.common.ErrorLine
import ph.financewallet.app.ui.common.FigureCard
import ph.financewallet.app.ui.common.LabeledRow
import ph.financewallet.app.ui.common.SectionTitle
import ph.financewallet.app.ui.common.errorRes
import ph.financewallet.app.ui.components.LoadingState
import ph.financewallet.app.ui.theme.LocalSpacing
import ph.financewallet.data.account.CategoryRow
import ph.financewallet.data.ledger.TransactionListItem
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money

/**
 * Dashboard figures (PRD §17). Each is a different concept with its own caption, and none is added to another:
 * account money, amounts owed, net worth, free money (after unlinked goal earmarks), and this month's income
 * and spending (transfers and savings moves excluded by the ledger classification).
 */
data class HomeState(
    val loading: Boolean = true,
    val inAccounts: Money = Money.zero(CurrencyCode.PHP),
    val owed: Money = Money.zero(CurrencyCode.PHP),
    val netWorth: Money = Money.zero(CurrencyCode.PHP),
    val freeMoney: Money = Money.zero(CurrencyCode.PHP),
    val monthIncome: Money = Money.zero(CurrencyCode.PHP),
    val monthSpending: Money = Money.zero(CurrencyCode.PHP),
    val recent: List<TransactionListItem> = emptyList(),
    val hasAccounts: Boolean = false,
    /** Unpaid bills due in the next 7 days or overdue (never counted as paid until a transaction is linked). */
    val upcomingBills: List<ph.financewallet.data.advanced.UpcomingItem> = emptyList(),
)

class HomeViewModel(
    private val s: AppServices,
) : ViewModel() {
    private val mutable = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = mutable

    fun refresh() {
        viewModelScope.launch { mutable.value = load() }
    }

    /** PHP figures (initial market). Accounts in other currencies are not converted or mixed in. */
    suspend fun load(): HomeState {
        val php = CurrencyCode.PHP
        val today = s.ctx.today()
        val accounts = s.accounts.accounts().filter { it.currency == php.code }
        val balances = accounts.map { it to s.ledger.balance(it.id, today) }
        val assets = balances.filter { it.first.accountClass == AccountClass.ASSET.name }.map { it.second }
        val liabilities = balances.filter { it.first.accountClass == AccountClass.LIABILITY.name }.map { it.second }
        val inNetWorth = balances.filter { it.first.includeInNetWorth }.map { it.second }
        val month = s.ledger.summary(today.withDayOfMonth(1), today)[php]
        return HomeState(
            loading = false,
            inAccounts = Money.sum(assets, php),
            owed = -Money.sum(liabilities, php),
            netWorth = Money.sum(inNetWorth, php),
            freeMoney = s.goals.freeMoney(php),
            monthIncome = month?.income ?: Money.zero(php),
            monthSpending = month?.spending ?: Money.zero(php),
            recent = s.transactions.page(limit = RECENT).items,
            hasAccounts = accounts.isNotEmpty(),
            upcomingBills =
                s.recurring
                    .upcoming(today.minusDays(BILLS_BACK), today.plusDays(BILLS_AHEAD))
                    .filter { it.kind != ph.financewallet.data.advanced.RuleKind.EXPECTED_INCOME }
                    .filter {
                        it.state !in
                            setOf(
                                ph.financewallet.data.advanced.OccurrenceState.PAID,
                                ph.financewallet.data.advanced.OccurrenceState.SKIPPED,
                            )
                    },
        )
    }

    private companion object {
        const val RECENT = 5
        const val BILLS_BACK = 31L
        const val BILLS_AHEAD = 7L
    }
}

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onAddTransaction: () -> Unit,
    onBills: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    if (state.loading) {
        LoadingState(stringResource(R.string.loading))
        return
    }
    val spacing = LocalSpacing.current
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        item { SectionTitle(stringResource(R.string.home_title)) }
        if (!state.hasAccounts) item { Text(stringResource(R.string.home_no_accounts)) }
        item { FigureCard(stringResource(R.string.home_in_accounts), state.inAccounts, stringResource(R.string.home_in_accounts_caption)) }
        item { FigureCard(stringResource(R.string.home_free_money), state.freeMoney, stringResource(R.string.home_free_money_caption)) }
        item { FigureCard(stringResource(R.string.home_owed), state.owed, stringResource(R.string.home_owed_caption)) }
        item { FigureCard(stringResource(R.string.home_net_worth), state.netWorth, stringResource(R.string.home_net_worth_caption)) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    Text(stringResource(R.string.home_this_month), style = MaterialTheme.typography.labelLarge)
                    LabeledRow(stringResource(R.string.home_income)) { AmountText(state.monthIncome) }
                    LabeledRow(stringResource(R.string.home_spending)) { AmountText(-state.monthSpending) }
                    Text(stringResource(R.string.home_month_caption), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Button(onClick = onAddTransaction, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.txn_add)) } }
        if (state.upcomingBills.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.home_upcoming_bills)) }
            items(state.upcomingBills, key = { "b" + it.occurrenceId }) { b ->
                LabeledRow("${b.name} · ${b.dueDate}") { AmountText(-b.amount) }
            }
            item { TextButton(onClick = onBills) { Text(stringResource(R.string.bills_title)) } }
        }
        if (state.recent.isNotEmpty()) item { SectionTitle(stringResource(R.string.home_recent)) }
        items(state.recent, key = { it.id }) { t ->
            LabeledRow(t.description.ifBlank { t.categoryNames.joinToString(", ").ifBlank { t.accountName } }) { AmountText(t.amount) }
        }
    }
}

data class CategoriesState(
    val loading: Boolean = true,
    val categories: List<CategoryRow> = emptyList(),
)

/** UC-14 screen state plus the optional default seed (CAT-02). */
class CategoriesViewModel(
    private val s: AppServices,
) : ViewModel() {
    private val mutable = MutableStateFlow(CategoriesState())
    val state: StateFlow<CategoriesState> = mutable

    fun refresh() {
        viewModelScope.launch { mutable.value = CategoriesState(false, s.categories.categories()) }
    }

    suspend fun create(
        name: String,
        cls: AccountClass,
        parentId: String?,
    ): Int? =
        s.categories
            .create(name, cls, parentId)
            .errorRes()
            .also { if (it == null) refresh() }

    suspend fun archive(id: String): Int? =
        s.categories
            .archive(id)
            .errorRes()
            .also { if (it == null) refresh() }

    suspend fun seed(): Int? =
        s.categories
            .seedDefaults()
            .errorRes()
            .also { if (it == null) refresh() }
}

@Composable
fun CategoriesScreen(vm: CategoriesViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }
    val spacing = LocalSpacing.current
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                SectionTitle(stringResource(R.string.categories_title))
                Button(onClick = { adding = true }) { Text(stringResource(R.string.categories_add)) }
                if (state.categories.isEmpty() && !state.loading) {
                    Text(stringResource(R.string.categories_seed_explain), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { scope.launch { error = vm.seed() } }) { Text(stringResource(R.string.categories_seed)) }
                }
                ErrorLine(error)
            }
        }
        val byId = state.categories.associateBy { it.id }
        listOf(AccountClass.INCOME, AccountClass.EXPENSE).forEach { cls ->
            item { SectionTitle(stringResource(if (cls == AccountClass.INCOME) R.string.kind_income else R.string.kind_expense)) }
            items(state.categories.filter { it.accountClass == cls.name }, key = { it.id }) { c ->
                LabeledRow(c.parentId?.let { "  " + byId[it]?.name + " › " + c.name } ?: c.name) {
                    TextButton(onClick = { scope.launch { error = vm.archive(c.id) } }) { Text(stringResource(R.string.action_archive)) }
                }
            }
        }
    }
    if (adding) AddCategoryDialog(vm, state.categories, onDismiss = { adding = false })
}

@Composable
private fun AddCategoryDialog(
    vm: CategoriesViewModel,
    existing: List<CategoryRow>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var cls by remember { mutableStateOf(AccountClass.EXPENSE) }
    var parent by remember { mutableStateOf<CategoryRow?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.categories_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) }, singleLine = true)
                ChoiceChips(listOf(AccountClass.EXPENSE, AccountClass.INCOME), cls, {
                    stringResource(if (it == AccountClass.INCOME) R.string.kind_income else R.string.kind_expense)
                }, {
                    cls = it
                    parent = null
                })
                Text(stringResource(R.string.categories_parent_optional), style = MaterialTheme.typography.bodySmall)
                ChoiceChips(existing.filter { it.accountClass == cls.name && it.parentId == null }, parent, { it.name }, {
                    parent = if (parent == it) null else it
                })
                ErrorLine(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    error = vm.create(name, cls, parent?.id)
                    if (error == null) onDismiss()
                }
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun MoreScreen(
    onGoals: () -> Unit,
    onCategories: () -> Unit,
    onDebts: () -> Unit,
    onBills: () -> Unit,
    onAssets: () -> Unit,
    onReports: () -> Unit,
    onImport: () -> Unit,
    onImportHistory: () -> Unit,
    onRules: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(Modifier.fillMaxSize().padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        SectionTitle(stringResource(R.string.nav_more))
        listOf(
            R.string.import_title to onImport,
            R.string.reports_title to onReports,
            R.string.bills_title to onBills,
            R.string.debts_title to onDebts,
            R.string.goals_title to onGoals,
            R.string.assets_title to onAssets,
            R.string.categories_title to onCategories,
            R.string.rules_title to onRules,
            R.string.import_history_title to onImportHistory,
        ).forEach { (label, action) ->
            OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth()) { Text(stringResource(label)) }
        }
        Text(stringResource(R.string.more_not_built), style = MaterialTheme.typography.bodySmall)
    }
}
