package ph.financewallet.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import ph.financewallet.data.db.entity.AllocationEntryEntity
import ph.financewallet.data.db.entity.AllocationGroupEntity
import ph.financewallet.data.db.entity.AppMetaEntity
import ph.financewallet.data.db.entity.AuditEventEntity
import ph.financewallet.data.db.entity.BudgetAccountEntity
import ph.financewallet.data.db.entity.BudgetCategoryEntity
import ph.financewallet.data.db.entity.BudgetEntity
import ph.financewallet.data.db.entity.BudgetPeriodEntity
import ph.financewallet.data.db.entity.CategoryEntity
import ph.financewallet.data.db.entity.EntryEntity
import ph.financewallet.data.db.entity.FinancialAccountEntity
import ph.financewallet.data.db.entity.GoalAccountEntity
import ph.financewallet.data.db.entity.LedgerAccountEntity
import ph.financewallet.data.db.entity.ReconciliationEntity
import ph.financewallet.data.db.entity.SavingsGoalEntity
import ph.financewallet.data.db.entity.TxnEntity
import ph.financewallet.data.db.entity.TxnRevisionEntity

@Database(
    version = FinanceDatabase.SCHEMA_VERSION,
    exportSchema = true,
    entities = [
        LedgerAccountEntity::class,
        FinancialAccountEntity::class,
        CategoryEntity::class,
        TxnEntity::class,
        EntryEntity::class,
        TxnRevisionEntity::class,
        AuditEventEntity::class,
        AppMetaEntity::class,
        // v2
        ReconciliationEntity::class,
        BudgetEntity::class,
        BudgetCategoryEntity::class,
        BudgetAccountEntity::class,
        BudgetPeriodEntity::class,
        SavingsGoalEntity::class,
        GoalAccountEntity::class,
        AllocationGroupEntity::class,
        AllocationEntryEntity::class,
    ],
)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun integrityDao(): IntegrityDao

    abstract fun ledgerDao(): ph.financewallet.data.ledger.LedgerDao

    abstract fun budgetDao(): ph.financewallet.data.budget.BudgetDao

    companion object {
        const val SCHEMA_VERSION: Int = 2
        const val FILE_NAME: String = "finance.db"

        /**
         * Builds the database (production opens it through [DatabaseOpener], which adds the pre-migration backup and
         * post-migration integrity check). Production passes a SQLCipher [SupportSQLiteOpenHelper.Factory]
         * ([EncryptedDatabaseFactory]); tests may pass null for an in-memory, unencrypted database.
         *
         * Destructive migration fallback is deliberately never enabled (docs/11-database-schema.md §6).
         */
        fun build(
            context: Context,
            openHelperFactory: SupportSQLiteOpenHelper.Factory?,
            inMemory: Boolean = false,
            name: String = FILE_NAME,
        ): FinanceDatabase {
            val builder =
                if (inMemory) {
                    Room.inMemoryDatabaseBuilder(context, FinanceDatabase::class.java)
                } else {
                    Room.databaseBuilder(context, FinanceDatabase::class.java, name)
                }
            if (openHelperFactory != null) builder.openHelperFactory(openHelperFactory)
            return builder
                .addMigrations(*Migrations.ALL)
                .addCallback(SchemaCallback)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        }
    }

    internal object SchemaCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            SchemaConstraints.allStatements.forEach(db::execSQL)
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            // Foreign keys are per-connection in SQLite (docs/11-database-schema.md §1).
            db.execSQL("PRAGMA foreign_keys = ON")
        }
    }
}
