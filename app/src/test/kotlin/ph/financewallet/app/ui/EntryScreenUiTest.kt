package ph.financewallet.app.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ph.financewallet.app.AppServices
import ph.financewallet.app.ui.theme.FinanceTheme
import ph.financewallet.app.ui.transactions.EntryScreen
import ph.financewallet.app.ui.transactions.EntryViewModel
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.ledger.NewAccount
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.ledger.FinancialAccountType
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.time.LocalDate

/** Drives the real fast-entry composable (TXN-13): type an amount, pick a category, save, and the expense is stored. */
@RunWith(RobolectricTestRunner::class)
class EntryScreenUiTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: FinanceDatabase
    private lateinit var s: AppServices

    @Before
    fun setUp() =
        runBlocking<Unit> {
            db = FinanceDatabase.build(ApplicationProvider.getApplicationContext(), openHelperFactory = null, inMemory = true)
            s = AppServices.create(db)
            s.ledger.createAccount(
                NewAccount(
                    "GCash",
                    FinancialAccountType.EWALLET,
                    CurrencyCode.PHP,
                    LocalDate.of(2026, 1, 1),
                    Money(100_000, CurrencyCode.PHP),
                ),
            )
            s.categories.create("Food", AccountClass.EXPENSE)
        }

    @After
    fun tearDown() = db.close()

    @Test
    fun `typing an amount, choosing a category and saving records the expense once`() {
        val vm = EntryViewModel(s)
        runBlocking { vm.loadOptions() }
        var done = 0
        compose.setContent { FinanceTheme { EntryScreen(vm, onDone = { done++ }) } }

        compose.onNodeWithTag("amount").performTextInput("250.75")
        compose.onNodeWithText("Food").performClick()
        compose.onNodeWithTag("save").performScrollTo().performClick()
        runCatching { compose.waitUntil(timeoutMillis = 10_000) { done == 1 } }.onFailure {
            println("DIAG form=" + vm.form.value)
            println("DIAG tree=" + compose.onRoot().printToString())
            throw it
        }

        val account =
            runBlocking {
                s.accounts
                    .accounts()
                    .single()
                    .id
            }
        val page = runBlocking { s.transactions.page(accountId = account) }
        val expenses = page.items.filter { it.type.name == "EXPENSE" }
        assertEquals(listOf(Money(-25_075, CurrencyCode.PHP)), expenses.map { it.amount })
        assertEquals(listOf("Food"), expenses.single().categoryNames)
    }
}
