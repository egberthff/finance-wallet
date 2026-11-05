package ph.financewallet.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import ph.financewallet.data.db.entity.AppMetaEntity
import ph.financewallet.data.db.entity.AuditEventEntity
import ph.financewallet.data.db.entity.CategoryEntity
import ph.financewallet.data.db.entity.EntryEntity
import ph.financewallet.data.db.entity.FinancialAccountEntity
import ph.financewallet.data.db.entity.LedgerAccountEntity
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
    ],
)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun integrityDao(): IntegrityDao

    companion object {
        const val SCHEMA_VERSION: Int = 1
        const val FILE_NAME: String = "finance.db"

        /**
         * Builds the database. Production passes a SQLCipher [SupportSQLiteOpenHelper.Factory]
         * ([EncryptedDatabaseFactory]); tests may pass null for an in-memory, unencrypted database.
         *
         * Destructive migration fallback is deliberately never enabled (docs/11-database-schema.md §6).
         */
        fun build(
            context: Context,
            openHelperFactory: SupportSQLiteOpenHelper.Factory?,
            inMemory: Boolean = false,
        ): FinanceDatabase {
            val builder =
                if (inMemory) {
                    Room.inMemoryDatabaseBuilder(context, FinanceDatabase::class.java)
                } else {
                    Room.databaseBuilder(context, FinanceDatabase::class.java, FILE_NAME)
                }
            if (openHelperFactory != null) builder.openHelperFactory(openHelperFactory)
            return builder
                .addCallback(SchemaCallback)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        }
    }

    internal object SchemaCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            SchemaConstraints.v1Statements.forEach(db::execSQL)
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            // Foreign keys are per-connection in SQLite (docs/11-database-schema.md §1).
            db.execSQL("PRAGMA foreign_keys = ON")
        }
    }
}
