package ph.financewallet.data.db

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Migration policy tests (docs/11-database-schema.md §6, LOC-04): every migration runs from a fixture database at the
 * old version with realistic data, the result must equal a fresh install (Room schema validation + same triggers),
 * data must survive, and IC-1..IC-8 must pass.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    // Absolute path: Room's test driver compares the requested path with the configured name verbatim.
    private val testDb = context.getDatabasePath("migration-test.db").absolutePath

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FinanceDatabase::class.java)

    @After
    fun cleanUp() {
        context.deleteDatabase(testDb)
    }

    @Test
    fun `migration 1 to 2 keeps v1 data and matches the fresh-install schema`() =
        runTest {
            helper.createDatabase(testDb, 1).use { v1 -> V1Fixture.populate(v1) }

            val v2 = helper.runMigrationsAndValidate(testDb, 2, true, Migrations.MIGRATION_1_2)
            assertEquals(V1Fixture.TXNS, count(v2, "txn"))
            assertEquals(V1Fixture.ENTRIES, count(v2, "entry"))
            assertEquals(V1Fixture.LEDGER_ACCOUNTS, count(v2, "ledger_account"))
            assertEquals(freshInstallTriggers(), triggers(v2))
            v2.close()

            val db = FinanceDatabase.build(context, openHelperFactory = null, name = testDb)
            try {
                val report = IntegrityChecker(db).run()
                assertTrue("integrity after migration: ${report.failures}", report.isHealthy)
            } finally {
                db.close()
            }
        }

    @Test
    fun `v2 constraint triggers are active after migration`() {
        helper.createDatabase(testDb, 1).use { v1 -> V1Fixture.populate(v1) }
        val v2 = helper.runMigrationsAndValidate(testDb, 2, true, Migrations.MIGRATION_1_2)
        v2.use { db ->
            db.execSQL(
                "INSERT INTO savings_goal(id, name, currency, target_minor, target_date, status, notes, icon, color_argb, " +
                    "created_at, version, updated_at, updated_by_device, hlc) " +
                    "VALUES ('g1', 'Laptop', 'PHP', 5000000, NULL, 'ACTIVE', NULL, 'laptop', 0, 0, 1, 0, 'dev', 'h')",
            )
            assertRejected("CK_GOAL_TARGET") {
                db.execSQL(
                    "INSERT INTO savings_goal(id, name, currency, target_minor, target_date, status, notes, icon, color_argb, " +
                        "created_at, version, updated_at, updated_by_device, hlc) " +
                        "VALUES ('g2', 'Bad', 'PHP', 0, NULL, 'ACTIVE', NULL, 'x', 0, 0, 1, 0, 'dev', 'h')",
                )
            }
            // v1 triggers still in force after the migration.
            assertRejected("CK_ENTRY_AMOUNT_NONZERO") {
                db.execSQL(
                    "INSERT INTO entry(id, txn_id, line_no, ledger_account_id, amount_minor, currency) " +
                        "VALUES ('ez', 't1', 9, 'a1', 0, 'PHP')",
                )
            }
        }
    }

    private fun freshInstallTriggers(): List<String> {
        val fresh = FinanceDatabase.build(context, openHelperFactory = null, inMemory = true)
        return try {
            triggers(fresh.openHelper.writableDatabase)
        } finally {
            fresh.close()
        }
    }

    private fun triggers(db: SupportSQLiteDatabase): List<String> =
        db.query("SELECT name || ':' || sql FROM sqlite_master WHERE type = 'trigger' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    private fun count(
        db: SupportSQLiteDatabase,
        table: String,
    ): Int =
        db.query("SELECT COUNT(*) FROM $table").use { c ->
            c.moveToFirst()
            c.getInt(0)
        }

    private fun assertRejected(
        code: String,
        block: () -> Unit,
    ) {
        try {
            block()
            fail("expected $code")
        } catch (e: SQLiteException) {
            assertTrue("expected $code but was ${e.message}", e.message.orEmpty().contains(code))
        }
    }
}

/** A realistic schema-v1 database: system accounts, two real accounts, an opening balance, income, expense, transfer. */
internal object V1Fixture {
    const val LEDGER_ACCOUNTS = 5
    const val TXNS = 4
    const val ENTRIES = 8

    private const val SYNC = "1, 0, 'dev', 'h'"

    fun populate(db: SupportSQLiteDatabase) {
        SchemaConstraints.v1Statements.forEach(db::execSQL) // a real v1 install has its triggers
        account(db, "sys:equity:opening", "EQUITY", null)
        account(db, "c-salary", "INCOME", null)
        account(db, "c-food", "EXPENSE", null)
        account(db, "a1", "ASSET", "PHP")
        account(db, "a2", "ASSET", "PHP")
        listOf("a1" to "EWALLET", "a2" to "BANK").forEach { (id, type) ->
            db.execSQL(
                "INSERT INTO financial_account(ledger_account_id, type, institution, account_number_last4, opening_date, " +
                    "include_in_net_worth, spendable, credit_limit_minor, default_import_profile_id, last_imported_at, " +
                    "reconcile_reminder_days, sort_order, version, updated_at, updated_by_device, hlc) " +
                    "VALUES ('$id', '$type', NULL, NULL, '2026-11-01', 1, 1, NULL, NULL, NULL, NULL, 0, $SYNC)",
            )
        }
        txn(db, "t1", "OPENING_BALANCE", "a1" to 1_000_000, "sys:equity:opening" to -1_000_000)
        txn(db, "t2", "INCOME", "a1" to 3_000_000, "c-salary" to -3_000_000)
        txn(db, "t3", "EXPENSE", "c-food" to 45_050, "a1" to -45_050)
        txn(db, "t4", "TRANSFER", "a2" to 200_000, "a1" to -200_000)
    }

    fun unbalanced(db: SupportSQLiteDatabase) = txn(db, "bad", "TRANSFER", "a2" to 1, "a1" to -2)

    private fun account(
        db: SupportSQLiteDatabase,
        id: String,
        cls: String,
        currency: String?,
    ) = db.execSQL(
        "INSERT INTO ledger_account(id, class, name, currency, system_key, archived_at, created_at, version, updated_at, " +
            "updated_by_device, hlc) VALUES ('$id', '$cls', '$id', ${currency?.let { "'$it'" } ?: "NULL"}, " +
            "${if (id.startsWith("sys:")) "'${id.removePrefix("sys:")}'" else "NULL"}, NULL, 0, $SYNC)",
    )

    private fun txn(
        db: SupportSQLiteDatabase,
        id: String,
        type: String,
        vararg entries: Pair<String, Long>,
    ) {
        db.execSQL(
            "INSERT INTO txn(id, type, status, txn_date, description, description_norm, source, idempotency_key, conflict_state, " +
                "created_at, version, updated_at, updated_by_device, hlc) " +
                "VALUES ('$id', '$type', 'POSTED', '2026-11-05', '', '', 'MANUAL', 'k-$id', 'NONE', 0, $SYNC)",
        )
        entries.forEachIndexed { i, (account, amount) ->
            db.execSQL(
                "INSERT INTO entry(id, txn_id, line_no, ledger_account_id, amount_minor, currency) " +
                    "VALUES ('$id-$i', '$id', $i, '$account', $amount, 'PHP')",
            )
        }
    }
}
