package ph.financewallet.data.budget

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import ph.financewallet.data.db.entity.AllocationEntryEntity
import ph.financewallet.data.db.entity.AllocationGroupEntity
import ph.financewallet.data.db.entity.BudgetAccountEntity
import ph.financewallet.data.db.entity.BudgetCategoryEntity
import ph.financewallet.data.db.entity.BudgetEntity
import ph.financewallet.data.db.entity.BudgetPeriodEntity
import ph.financewallet.data.db.entity.GoalAccountEntity
import ph.financewallet.data.db.entity.SavingsGoalEntity

/**
 * Budget, period, goal and allocation-ledger access (docs/11-database-schema.md §3.4). Called only from use cases,
 * inside a database transaction. Allocation sums ignore soft-deleted groups.
 * TooManyFunctions is suppressed for the same reason as LedgerDao: one function per SQL statement.
 */
@Suppress("TooManyFunctions")
@Dao
interface BudgetDao {
    // ---- budgets -----------------------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBudget(row: BudgetEntity)

    @Update
    suspend fun updateBudget(row: BudgetEntity)

    @Query("SELECT * FROM budget WHERE id = :id")
    suspend fun budget(id: String): BudgetEntity?

    @Query("SELECT * FROM budget WHERE archived_at IS NOT NULL ORDER BY archived_at DESC, name")
    suspend fun archivedBudgets(): List<BudgetEntity>

    @Query("SELECT * FROM budget WHERE archived_at IS NULL ORDER BY name")
    suspend fun activeBudgets(): List<BudgetEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBudgetCategories(rows: List<BudgetCategoryEntity>)

    @Query("DELETE FROM budget_category WHERE budget_id = :budgetId")
    suspend fun deleteBudgetCategories(budgetId: String)

    @Query("SELECT * FROM budget_category WHERE budget_id = :budgetId ORDER BY sort_order, category_id")
    suspend fun budgetCategories(budgetId: String): List<BudgetCategoryEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBudgetAccounts(rows: List<BudgetAccountEntity>)

    @Query("DELETE FROM budget_account WHERE budget_id = :budgetId")
    suspend fun deleteBudgetAccounts(budgetId: String)

    @Query("SELECT ledger_account_id FROM budget_account WHERE budget_id = :budgetId ORDER BY ledger_account_id")
    suspend fun budgetAccountIds(budgetId: String): List<String>

    // ---- periods -----------------------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPeriod(row: BudgetPeriodEntity)

    @Update
    suspend fun updatePeriod(row: BudgetPeriodEntity)

    @Query("SELECT * FROM budget_period WHERE id = :id")
    suspend fun period(id: String): BudgetPeriodEntity?

    @Query("SELECT * FROM budget_period WHERE budget_id = :budgetId AND start_date <= :date AND end_date >= :date")
    suspend fun periodContaining(
        budgetId: String,
        date: String,
    ): BudgetPeriodEntity?

    @Query("SELECT * FROM budget_period WHERE budget_id = :budgetId ORDER BY start_date")
    suspend fun periods(budgetId: String): List<BudgetPeriodEntity>

    @Query("SELECT * FROM budget_period WHERE budget_id = :budgetId AND closed_at IS NULL AND end_date < :date ORDER BY start_date")
    suspend fun openPeriodsEndingBefore(
        budgetId: String,
        date: String,
    ): List<BudgetPeriodEntity>

    // ---- goals -------------------------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGoal(row: SavingsGoalEntity)

    @Update
    suspend fun updateGoal(row: SavingsGoalEntity)

    @Query("SELECT * FROM savings_goal WHERE id = :id")
    suspend fun goal(id: String): SavingsGoalEntity?

    @Query("SELECT * FROM savings_goal ORDER BY created_at, id")
    suspend fun goals(): List<SavingsGoalEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGoalAccounts(rows: List<GoalAccountEntity>)

    @Query("DELETE FROM goal_account WHERE goal_id = :goalId")
    suspend fun deleteGoalAccounts(goalId: String)

    @Query("SELECT * FROM goal_account")
    suspend fun allGoalAccounts(): List<GoalAccountEntity>

    // ---- allocation ledger -------------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGroup(row: AllocationGroupEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAllocationEntries(rows: List<AllocationEntryEntity>)

    @Query("SELECT * FROM allocation_group WHERE idempotency_key = :key")
    suspend fun groupByKey(key: String): AllocationGroupEntity?

    @Query("SELECT * FROM allocation_group WHERE id = :id")
    suspend fun group(id: String): AllocationGroupEntity?

    @Update
    suspend fun updateGroup(row: AllocationGroupEntity)

    /** Live groups whose idempotency key starts with [keyPrefix] (e.g. a period's rollover and leftover moves). */
    @Query(
        """SELECT * FROM allocation_group
           WHERE deleted_at IS NULL AND substr(idempotency_key, 1, length(:keyPrefix)) = :keyPrefix
           ORDER BY created_at, id""",
    )
    suspend fun liveGroupsWithKeyPrefix(keyPrefix: String): List<AllocationGroupEntity>

    @Query("SELECT * FROM allocation_entry WHERE group_id = :groupId ORDER BY id")
    suspend fun allocationEntries(groupId: String): List<AllocationEntryEntity>

    @Query(
        """SELECT COALESCE(SUM(e.amount_minor), 0) FROM allocation_entry e JOIN allocation_group g ON g.id = e.group_id
           WHERE g.deleted_at IS NULL AND e.bucket = 'UNALLOCATED' AND e.budget_period_id = :periodId AND g.kind <> 'FUND'""",
    )
    suspend fun unallocatedMovements(periodId: String): Long

    @Query(
        """SELECT COALESCE(SUM(e.amount_minor), 0) FROM allocation_entry e JOIN allocation_group g ON g.id = e.group_id
           WHERE g.deleted_at IS NULL AND e.bucket = 'UNALLOCATED' AND e.budget_period_id = :periodId AND g.kind = 'FUND'""",
    )
    suspend fun manualFunds(periodId: String): Long

    @Query(
        """SELECT COALESCE(SUM(e.amount_minor), 0) FROM allocation_entry e JOIN allocation_group g ON g.id = e.group_id
           WHERE g.deleted_at IS NULL AND e.bucket = 'CATEGORY' AND e.budget_period_id = :periodId AND e.category_id = :categoryId""",
    )
    suspend fun categoryAllocated(
        periodId: String,
        categoryId: String,
    ): Long

    @Query(
        """SELECT COALESCE(SUM(e.amount_minor), 0) FROM allocation_entry e JOIN allocation_group g ON g.id = e.group_id
           WHERE g.deleted_at IS NULL AND e.bucket = 'GOAL' AND e.goal_id = :goalId""",
    )
    suspend fun goalAllocated(goalId: String): Long

    /** Allocation still held by [categoryId] in the budget's open periods (blocks removing the category). */
    @Query(
        """SELECT COALESCE(SUM(e.amount_minor), 0) FROM allocation_entry e JOIN allocation_group g ON g.id = e.group_id
           JOIN budget_period p ON p.id = e.budget_period_id
           WHERE g.deleted_at IS NULL AND e.bucket = 'CATEGORY' AND e.category_id = :categoryId
             AND p.budget_id = :budgetId AND p.closed_at IS NULL""",
    )
    suspend fun openCategoryAllocation(
        budgetId: String,
        categoryId: String,
    ): Long

    /** Live allocation groups of a budget's periods or of goals, newest first (history screens). */
    @Query(
        """SELECT DISTINCT g.* FROM allocation_group g JOIN allocation_entry e ON e.group_id = g.id
           WHERE g.deleted_at IS NULL AND e.goal_id = :goalId ORDER BY g.created_at DESC, g.id DESC""",
    )
    suspend fun goalHistory(goalId: String): List<AllocationGroupEntity>

    // ---- money-ledger figures in a budget's scope (docs/10-domain-model.md §5.2) ---------------------------------

    /**
     * Spending on [categoryId] and its subcategories in [start]..[end]: posted, not deleted, in [currency]; with an
     * account filter, only transactions touching one of the budget's accounts. Only the category entries count.
     */
    @Query(
        """SELECT COALESCE(SUM(e.amount_minor), 0) FROM entry e
           JOIN txn t ON t.id = e.txn_id
           JOIN category c ON c.ledger_account_id = e.ledger_account_id
           WHERE (c.ledger_account_id = :categoryId OR c.parent_id = :categoryId)
             AND t.status = 'POSTED' AND t.deleted_at IS NULL AND t.txn_date BETWEEN :start AND :end AND e.currency = :currency
             AND (:accountFilter = 0 OR EXISTS (SELECT 1 FROM entry e2 JOIN budget_account ba
                  ON ba.ledger_account_id = e2.ledger_account_id AND ba.budget_id = :budgetId WHERE e2.txn_id = t.id))""",
    )
    suspend fun categorySpent(
        budgetId: String,
        categoryId: String,
        start: String,
        end: String,
        currency: String,
        accountFilter: Boolean,
    ): Long

    /** Income (credit, so negated) on all INCOME-class accounts in the period and scope. */
    @Query(
        """SELECT COALESCE(-SUM(e.amount_minor), 0) FROM entry e
           JOIN txn t ON t.id = e.txn_id
           JOIN ledger_account a ON a.id = e.ledger_account_id AND a.class = 'INCOME'
           WHERE t.status = 'POSTED' AND t.deleted_at IS NULL AND t.txn_date BETWEEN :start AND :end AND e.currency = :currency
             AND (:accountFilter = 0 OR EXISTS (SELECT 1 FROM entry e2 JOIN budget_account ba
                  ON ba.ledger_account_id = e2.ledger_account_id AND ba.budget_id = :budgetId WHERE e2.txn_id = t.id))""",
    )
    suspend fun allIncome(
        budgetId: String,
        start: String,
        end: String,
        currency: String,
        accountFilter: Boolean,
    ): Long

    /** Income on the budget's own INCOME categories (and their subcategories). */
    @Query(
        """SELECT COALESCE(-SUM(e.amount_minor), 0) FROM entry e
           JOIN txn t ON t.id = e.txn_id
           JOIN category c ON c.ledger_account_id = e.ledger_account_id
           JOIN ledger_account a ON a.id = c.ledger_account_id AND a.class = 'INCOME'
           WHERE (c.ledger_account_id IN (SELECT category_id FROM budget_category WHERE budget_id = :budgetId)
                  OR c.parent_id IN (SELECT category_id FROM budget_category WHERE budget_id = :budgetId))
             AND t.status = 'POSTED' AND t.deleted_at IS NULL AND t.txn_date BETWEEN :start AND :end AND e.currency = :currency
             AND (:accountFilter = 0 OR EXISTS (SELECT 1 FROM entry e2 JOIN budget_account ba
                  ON ba.ledger_account_id = e2.ledger_account_id AND ba.budget_id = :budgetId WHERE e2.txn_id = t.id))""",
    )
    suspend fun budgetIncome(
        budgetId: String,
        start: String,
        end: String,
        currency: String,
        accountFilter: Boolean,
    ): Long
}
