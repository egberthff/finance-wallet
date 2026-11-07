package ph.financewallet.data.ledger

import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.validatedWrite
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.ledger.AccountClass
import java.time.LocalDate

/**
 * What fast entry (TXN-13) remembers between entries: the last account and the last category per kind, stored in
 * `app_meta` after each successful *new* entry (not after edits), and the categories used most in the last
 * [RECENT_DAYS] days for the picker's "Recent" section. Preferences only: they never change financial data.
 */
class EntryPreferences(
    private val db: FinanceDatabase,
) {
    private val dao get() = db.accountDao()

    suspend fun lastAccount(): String? = dao.meta(KEY_ACCOUNT)

    suspend fun lastCategory(accountClass: AccountClass): String? = dao.meta(KEY_CATEGORY + accountClass.name)

    suspend fun remember(
        accountId: String,
        categoryClass: AccountClass?,
        categoryId: String?,
    ): DomainResult<Unit> =
        db.validatedWrite {
            dao.setMeta(KEY_ACCOUNT, accountId)
            if (categoryClass != null && categoryId != null) dao.setMeta(KEY_CATEGORY + categoryClass.name, categoryId)
        }

    suspend fun recentCategories(
        accountClass: AccountClass,
        today: LocalDate,
        limit: Int = RECENT_LIMIT,
    ): List<String> = dao.recentCategories(accountClass.name, today.minusDays(RECENT_DAYS).toString(), limit)

    companion object {
        const val RECENT_DAYS = 60L
        const val RECENT_LIMIT = 5
        private const val KEY_ACCOUNT = "entry:last_account"
        private const val KEY_CATEGORY = "entry:last_category:"
    }
}
