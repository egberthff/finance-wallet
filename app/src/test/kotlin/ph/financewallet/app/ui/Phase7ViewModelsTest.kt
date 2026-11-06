package ph.financewallet.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ph.financewallet.app.AppContainer
import ph.financewallet.app.AppServices
import ph.financewallet.app.R
import ph.financewallet.app.ui.backup.BackupViewModel
import ph.financewallet.app.ui.backup.DeleteAllViewModel
import ph.financewallet.app.ui.backup.InsightsViewModel
import ph.financewallet.app.ui.home.HomeViewModel
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.ledger.NewAccount
import ph.financewallet.data.ledger.TxnHeader
import ph.financewallet.domain.common.getOrThrow
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.ledger.FinancialAccountType
import ph.financewallet.domain.ledger.Split
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Backup & restore, export, delete-all and insights as their screens drive them (Phase 7, AC-BAK-*). */
@RunWith(RobolectricTestRunner::class)
class Phase7ViewModelsTest {
    private lateinit var db: FinanceDatabase
    private lateinit var s: AppServices
    private val clock = Clock.fixed(Instant.parse("2026-11-20T03:00:00Z"), ZoneOffset.UTC)
    private lateinit var bpi: String
    private lateinit var food: String
    private val pass = "kalabaw-sa-bukid-42"
    private var safetyCopies = 0

    private fun p(minor: Long) = Money(minor, CurrencyCode.PHP)

    @Before
    fun setUp() =
        runBlocking<Unit> {
            db = FinanceDatabase.build(ApplicationProvider.getApplicationContext(), openHelperFactory = null, inMemory = true)
            s = AppServices.create(db, clock, safetyCopy = { safetyCopies++ })
            bpi =
                s.ledger
                    .createAccount(NewAccount("BPI", FinancialAccountType.BANK, CurrencyCode.PHP, LocalDate.of(2026, 10, 1), p(1_000_000)))
                    .getOrThrow()
            food = s.ledger.createCategory("Food", AccountClass.EXPENSE).getOrThrow()
            spend("lunch", 25_000)
        }

    @After
    fun tearDown() = db.close()

    private suspend fun spend(
        key: String,
        amount: Long,
    ) = s.ledger.record(TxnHeader(LocalDate.of(2026, 11, 10), key)) { t, _ -> t.expense(bpi, listOf(Split(food, p(amount)))) }.getOrThrow()

    private suspend fun txnCount() =
        s.transactions
            .page(limit = 100)
            .items.size

    @Test
    fun `passphrase rules are shown before the file picker opens`() {
        val vm = BackupViewModel(s)
        assertEquals(R.string.backup_pass_too_short, vm.passphraseProblem("short", "short"))
        assertEquals(R.string.backup_pass_too_common, vm.passphraseProblem("1234567890", "1234567890"))
        assertEquals(R.string.backup_pass_repetitive, vm.passphraseProblem("aaaaaaaaaaaa", "aaaaaaaaaaaa"))
        assertEquals(R.string.backup_pass_mismatch, vm.passphraseProblem(pass, pass + "x"))
        assertNull(vm.passphraseProblem(pass, pass))
        assertEquals(0, vm.strength("short"))
        assertTrue(vm.strength(pass) >= 2)
    }

    @Test
    fun `backup, wrong passphrase changes nothing, restore replaces newer records`() =
        runBlocking<Unit> {
            val vm = BackupViewModel(s)
            val home = HomeViewModel(s)
            assertTrue("no backup yet while transactions exist", home.load().backupDue)
            val file = ByteArrayOutputStream()
            assertNull(vm.create({ file }, pass, pass))
            assertNotNull(vm.state.value.lastBackupAt)
            assertTrue((vm.state.value.createdRows ?: 0) > 0)
            assertFalse("reminder clears after a backup", home.load().backupDue)

            val atBackup = txnCount() // opening balance + lunch
            spend("dinner", 80_000) // made after the backup
            assertEquals(atBackup + 1, txnCount())
            val bytes = file.toByteArray()
            val open = { ByteArrayInputStream(bytes) }
            assertEquals(R.string.backup_err_passphrase, vm.inspect(open, "wrong-passphrase-1"))
            assertNull(vm.state.value.pending)
            assertEquals(atBackup + 1, txnCount())

            assertNull(vm.inspect(open, pass))
            assertEquals(
                atBackup,
                vm.state.value.pending
                    ?.transactions,
            )
            assertEquals(0, safetyCopies)
            assertNull(vm.restore(open, pass))
            assertEquals(1, safetyCopies)
            assertNull(vm.state.value.pending)
            assertNotNull(vm.state.value.restored)
            assertEquals("the dinner recorded after the backup is gone", atBackup, txnCount())
        }

    @Test
    fun `damaged or foreign files are refused with a reason`() =
        runBlocking<Unit> {
            val vm = BackupViewModel(s)
            val before = txnCount()
            val notBackup = "Date,Amount\n".toByteArray()
            assertEquals(R.string.backup_err_not_backup, vm.inspect({ ByteArrayInputStream(notBackup) }, pass))
            val file = ByteArrayOutputStream()
            vm.create({ file }, pass, pass)
            val damaged = file.toByteArray().also { it[it.size - 40] = (it[it.size - 40].toInt() xor 1).toByte() }
            assertEquals(R.string.backup_err_passphrase, vm.inspect({ ByteArrayInputStream(damaged) }, pass))
            assertEquals(R.string.backup_err_read, vm.inspect({ null }, pass))
            assertEquals(R.string.backup_err_write, vm.create({ null }, pass, pass))
            assertEquals(before, txnCount())
        }

    @Test
    fun `plaintext export writes every record`() =
        runBlocking<Unit> {
            val out = ByteArrayOutputStream()
            val rows = BackupViewModel(s).exportPlaintext { out }
            assertNotNull(rows)
            val text = out.toString(Charsets.UTF_8.name())
            assertTrue(text.contains("\"t\":\"manifest\"") && text.contains("BPI"))
            assertNull(BackupViewModel(s).exportPlaintext { null })
        }

    @Test
    fun `delete all needs the right PIN`() =
        runBlocking<Unit> {
            var wiped = 0
            val vm = DeleteAllViewModel(confirmPin = { pin -> String(pin) == "135790" }, wipe = { wiped++ })
            assertFalse(vm.deleteAll("000000"))
            assertEquals(0, wiped)
            assertTrue(vm.deleteAll("135790"))
            assertEquals(1, wiped)
            assertFalse(vm.busy.value)
        }

    @Test
    fun `wipe removes the database, keys, PIN and local copies`() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val dbFile = app.getDatabasePath(FinanceDatabase.FILE_NAME).apply { parentFile?.mkdirs() }
            dbFile.writeText("x")
            File(dbFile.path + "-wal").writeText("x")
            val noBackup = listOf(File(app.noBackupFilesDir, "db.key.enc"), File(app.noBackupFilesDir, "lock/pin.enc"))
            noBackup.forEach { f ->
                f.parentFile?.mkdirs()
                f.writeText("x")
            }
            File(app.filesDir, "note").writeText("x")
            var keysDestroyed = false
            AppContainer(app, destroyKeys = { keysDestroyed = true }).wipeAll()
            assertFalse(dbFile.exists() || File(dbFile.path + "-wal").exists())
            assertTrue(app.noBackupFilesDir.listFiles().isNullOrEmpty())
            assertTrue(app.filesDir.listFiles().isNullOrEmpty())
            assertTrue(keysDestroyed)
        }

    @Test
    fun `insights load from the ledger`() =
        runBlocking<Unit> {
            val vm = InsightsViewModel(s)
            vm.load()
            val r = checkNotNull(vm.state.value.report)
            assertEquals(listOf(food to p(25_000)), r.topCategories)
            assertEquals("Food", r.categoryNames[food])
        }
}
