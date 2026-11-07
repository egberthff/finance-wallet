package ph.financewallet.app.ui

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import ph.financewallet.domain.common.getOrThrow
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
        compose.onNodeWithContentDescription("Category, Choose a category").performScrollTo().performClick()
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

    @Test
    fun `the picker finds a subcategory by search and saves with it`() {
        val groceries = runBlocking { s.categories.create("Groceries", AccountClass.EXPENSE, foodId()).getOrThrow() }
        runBlocking { s.categories.create("Transport", AccountClass.EXPENSE) }
        val vm = EntryViewModel(s)
        runBlocking { vm.loadOptions() }
        var done = 0
        compose.setContent { FinanceTheme { EntryScreen(vm, onDone = { done++ }) } }

        compose.onNodeWithTag("amount").performTextInput("99")
        compose.onNodeWithContentDescription("Category, Choose a category").performScrollTo().performClick()
        compose.onNodeWithText("Search categories").performTextInput("GROC")
        compose.onNodeWithText("Food › Groceries").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { vm.form.value.categoryId == groceries }
        compose.onNodeWithTag("save").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { done == 1 }

        val expense =
            runBlocking {
                s.transactions
                    .page()
                    .items
                    .single { it.type.name == "EXPENSE" }
            }
        assertEquals(listOf("Groceries"), expense.categoryNames)
    }

    @Test
    fun `a category created inside the picker is selected and used`() {
        val vm = EntryViewModel(s)
        runBlocking { vm.loadOptions() }
        var done = 0
        compose.setContent { FinanceTheme { EntryScreen(vm, onDone = { done++ }) } }

        compose.onNodeWithTag("amount").performTextInput("150")
        compose.onNodeWithContentDescription("Category, Choose a category").performScrollTo().performClick()
        compose.onNodeWithText("Search categories").performTextInput("Pets")
        compose.onNodeWithText("New category “Pets”").performClick()
        compose.onNode(hasText("Save") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { vm.form.value.categoryId != null }
        compose.onNodeWithTag("save").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { done == 1 }

        val expense =
            runBlocking {
                s.transactions
                    .page()
                    .items
                    .single { it.type.name == "EXPENSE" }
            }
        assertEquals(listOf("Pets"), expense.categoryNames)
    }

    private fun foodId(): String =
        runBlocking {
            s.categories
                .categories()
                .single { it.name == "Food" }
                .id
        }
}
