package ph.financewallet.data.db

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Runs against an in-memory, unencrypted Room database with the same schema and triggers as production.
 * Encryption itself (SQLCipher native library) can only be verified on a device (AC-SEC-1).
 */
@RunWith(RobolectricTestRunner::class)
class SchemaAndIntegrityTest {
    private lateinit var db: FinanceDatabase
    private lateinit var sql: SupportSQLiteDatabase

    private val sync = "1, 0, 'dev', 'h'"

    @Before
    fun setUp() {
        db = FinanceDatabase.build(ApplicationProvider.getApplicationContext(), openHelperFactory = null, inMemory = true)
        sql = db.openHelper.writableDatabase
    }

    @After
    fun tearDown() = db.close()

    private fun account(
        id: String,
        cls: String = "ASSET",
        currency: String? = "PHP",
    ) = sql.execSQL(
        "INSERT INTO ledger_account(id, class, name, currency, system_key, archived_at, created_at, " +
            "version, updated_at, updated_by_device, hlc) " +
            "VALUES ('$id', '$cls', 'Acct $id', ${currency?.let { "'$it'" } ?: "NULL"}, NULL, NULL, 0, $sync)",
    )

    private fun txn(
        id: String,
        type: String = "TRANSFER",
        key: String = id,
        refundOf: String? = null,
        deleted: Boolean = false,
    ) = sql.execSQL(
        "INSERT INTO txn(id, type, status, txn_date, description, description_norm, source, idempotency_key, refund_of_txn_id, " +
            "conflict_state, created_at, deleted_at, version, updated_at, updated_by_device, hlc) VALUES " +
            "('$id', '$type', 'POSTED', '2026-11-05', '', '', 'MANUAL', '$key', ${refundOf?.let { "'$it'" } ?: "NULL"}, " +
            "'NONE', 0, ${if (deleted) "1" else "NULL"}, $sync)",
    )

    private fun entry(
        id: String,
        txn: String,
        line: Int,
        account: String,
        amount: String,
        currency: String = "PHP",
    ) = sql.execSQL(
        "INSERT INTO entry(id, txn_id, line_no, ledger_account_id, amount_minor, currency) " +
            "VALUES ('$id', '$txn', $line, '$account', $amount, '$currency')",
    )

    private fun assertRejected(
        expectedCode: String,
        block: () -> Unit,
    ) {
        try {
            block()
            fail("expected rejection with $expectedCode")
        } catch (e: SQLiteException) {
            assertTrue("expected $expectedCode but was: ${e.message}", e.message.orEmpty().contains(expectedCode))
        }
    }

    @Test
    fun `balanced transfer passes all integrity checks`() =
        runTest {
            account("gcash")
            account("bpi")
            txn("t1")
            entry("e1", "t1", 0, "gcash", "-200000")
            entry("e2", "t1", 1, "bpi", "200000")
            val report = IntegrityChecker(db).run()
            assertTrue(report.failures.toString(), report.isHealthy)
        }

    @Test
    fun `IC-1 and IC-3 detect unbalanced and single-entry transactions`() =
        runTest {
            account("gcash")
            account("bpi")
            txn("t1")
            entry("e1", "t1", 0, "gcash", "-200000")
            entry("e2", "t1", 1, "bpi", "199999")
            txn("t2")
            entry("e3", "t2", 0, "gcash", "5")
            val failures = IntegrityChecker(db).run().failures
            assertEquals(setOf("IC-1", "IC-3"), failures.keys)
            assertEquals(2, failures.getValue("IC-1").size)
        }

    @Test
    fun `IC-2 detects entry currency different from real account currency`() =
        runTest {
            account("gcash")
            account("food", cls = "EXPENSE", currency = null)
            txn("t1")
            entry("e1", "t1", 0, "gcash", "-100", currency = "USD")
            entry("e2", "t1", 1, "food", "100", currency = "USD")
            assertEquals(setOf("IC-2"), IntegrityChecker(db).run().failures.keys)
        }

    @Test
    fun `IC-4 detects two live opening balances on one account`() =
        runTest {
            account("gcash")
            account("eq", cls = "EQUITY", currency = null)
            for (i in 1..2) {
                txn("o$i", type = "OPENING_BALANCE")
                entry("o${i}a", "o$i", 0, "gcash", "100")
                entry("o${i}b", "o$i", 1, "eq", "-100")
            }
            assertEquals(setOf("IC-4"), IntegrityChecker(db).run().failures.keys)
        }

    @Test
    fun `IC-7 detects refunds exceeding the original expense`() =
        runTest {
            account("gcash")
            account("food", cls = "EXPENSE", currency = null)
            txn("x", type = "EXPENSE")
            entry("x1", "x", 0, "gcash", "-50000")
            entry("x2", "x", 1, "food", "50000")
            txn("r1", type = "REFUND", refundOf = "x")
            entry("r1a", "r1", 0, "gcash", "30000")
            entry("r1b", "r1", 1, "food", "-30000")
            assertTrue(IntegrityChecker(db).run().isHealthy)
            txn("r2", type = "REFUND", refundOf = "x")
            entry("r2a", "r2", 0, "gcash", "30000")
            entry("r2b", "r2", 1, "food", "-30000")
            assertEquals(setOf("IC-7"), IntegrityChecker(db).run().failures.keys)
        }

    @Test
    fun `triggers reject invalid values on insert and update`() {
        account("gcash")
        account("bpi")
        txn("t1")
        assertRejected("CK_ENTRY_AMOUNT_NONZERO") { entry("e0", "t1", 0, "gcash", "0") }
        assertRejected("CK_ENTRY_AMOUNT_INTEGER") { entry("e0", "t1", 0, "gcash", "1.5") }
        assertRejected("CK_ENTRY_LINE") { entry("e0", "t1", -1, "gcash", "1") }
        entry("e1", "t1", 0, "gcash", "-1")
        assertRejected("CK_ENTRY_AMOUNT_NONZERO") { sql.execSQL("UPDATE entry SET amount_minor = 0 WHERE id = 'e1'") }
        assertRejected("CK_LEDGER_CLASS") { account("x", cls = "BOGUS") }
        assertRejected("CK_LEDGER_REAL_NEEDS_CURRENCY") { account("y", currency = null) }
        assertRejected("CK_LEDGER_CURRENCY") { account("z", currency = "PESO") }
        assertRejected("CK_TXN_TYPE") { txn("bad", type = "GIFT") }
        assertRejected("CK_TXN_STATUS") { sql.execSQL("UPDATE txn SET status = 'DONE' WHERE id = 't1'") }
        assertRejected("CK_TXN_DATE") { sql.execSQL("UPDATE txn SET txn_date = '11/05/2026' WHERE id = 't1'") }
    }

    @Test
    fun `idempotency key and foreign keys are enforced`() {
        account("gcash")
        txn("t1", key = "k1")
        try {
            txn("t2", key = "k1")
            fail("duplicate idempotency key accepted")
        } catch (_: SQLiteConstraintException) {
        }
        try {
            entry("e1", "t1", 0, "missing-account", "5")
            fail("entry referencing missing account accepted")
        } catch (_: SQLiteConstraintException) {
        }
    }

    @Test
    fun `revision and audit tables are append-only`() {
        account("gcash")
        txn("t1")
        sql.execSQL(
            "INSERT INTO txn_revision(id, txn_id, revision_no, change_kind, device_id, created_at) " +
                "VALUES ('r1', 't1', 1, 'CREATE', 'dev', 0)",
        )
        sql.execSQL("INSERT INTO audit_event(id, kind, device_id, created_at) VALUES ('a1', 'APP_UNLOCK_FAILED', 'dev', 0)")
        assertRejected("APPEND_ONLY") { sql.execSQL("UPDATE txn_revision SET reason = 'x' WHERE id = 'r1'") }
        assertRejected("APPEND_ONLY") { sql.execSQL("DELETE FROM txn_revision") }
        assertRejected("APPEND_ONLY") { sql.execSQL("DELETE FROM audit_event") }
        assertRejected("CK_REV_NO") {
            sql.execSQL(
                "INSERT INTO txn_revision(id, txn_id, revision_no, change_kind, device_id, created_at) " +
                    "VALUES ('r0', 't1', 0, 'CREATE', 'dev', 0)",
            )
        }
    }
}
