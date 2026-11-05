package ph.financewallet.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
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
import java.io.File

/** Pre-migration backup and post-migration integrity gate (docs/11-database-schema.md §6.3–6.4, LOC-04, R-10). */
@RunWith(RobolectricTestRunner::class)
class DatabaseOpenerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    // Absolute path: Room's test driver compares the requested path with the configured name verbatim.
    private val name = context.getDatabasePath("opener-test.db").absolutePath
    private var clock = 1_000L
    private val opener = DatabaseOpener(context, openHelperFactory = null, fileName = name, clockMillis = { clock++ })

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FinanceDatabase::class.java)

    @After
    fun cleanUp() {
        context.deleteDatabase(name)
        File(context.noBackupFilesDir, "premigration").deleteRecursively()
    }

    private fun createV1(extra: (SupportSQLiteDatabase) -> Unit = {}) =
        helper.createDatabase(name, 1).use { db ->
            V1Fixture.populate(db)
            extra(db)
        }

    private fun userVersionOnDisk(): Int {
        val config =
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(name)
                .callback(
                    object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit

                        override fun onDowngrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                ).build()
        val h = FrameworkSQLiteOpenHelperFactory().create(config)
        return try {
            h.readableDatabase.version
        } finally {
            h.close()
        }
    }

    @Test
    fun `a fresh install creates no backup`() =
        runTest {
            opener.open().close()
            assertEquals(emptyList<File>(), opener.backups.list())
        }

    @Test
    fun `the schema probe reads the stored version without changing the file`() {
        createV1()
        assertEquals(1, opener.storedVersion())
        assertEquals(1, opener.storedVersion())
        assertEquals("file must still be v1", 1, userVersionOnDisk())
    }

    @Test
    fun `an old database is backed up, then migrated, and its data kept`() =
        runTest {
            createV1()
            val db = opener.open()
            try {
                assertEquals(FinanceDatabase.SCHEMA_VERSION, db.openHelper.readableDatabase.version)
                assertEquals(V1Fixture.TXNS, db.ledgerDao().let { dao -> listOf("t1", "t2", "t3", "t4").count { dao.txn(it) != null } })
            } finally {
                db.close()
            }
            val backups = opener.backups.list()
            assertEquals(1, backups.size)
            assertTrue(backups.single().name.endsWith("-v1.db"))
            assertTrue(backups.single().length() > 0)
        }

    @Test
    fun `reopening a migrated database takes no further backup`() =
        runTest {
            createV1()
            opener.open().close()
            opener.open().close()
            assertEquals(1, opener.backups.list().size)
        }

    @Test
    fun `failed post-migration integrity puts the old file back and reports`() =
        runTest {
            createV1 { V1Fixture.unbalanced(it) }
            try {
                opener.open()
                fail("expected MigrationIntegrityException")
            } catch (e: MigrationIntegrityException) {
                assertEquals(1, e.fromVersion)
                assertTrue(e.report.failures.containsKey("IC-1"))
            }
            assertEquals("pre-migration file restored", 1, userVersionOnDisk())
        }

    @Test
    fun `only the newest two backups are kept`() {
        createV1()
        val dbFile = context.getDatabasePath(name)
        val created = (1..4).map { opener.backups.create(dbFile, 1, 1_000L + it) }
        assertEquals(created.takeLast(2).map { it.name }, opener.backups.list().map { it.name })
    }
}
