package ph.financewallet.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Post-migration integrity checks failed; the pre-migration database file has been put back. */
class MigrationIntegrityException(
    val fromVersion: Int,
    val report: IntegrityReport,
) : IllegalStateException("Integrity checks failed after migrating from v$fromVersion: ${report.failures.keys}")

/**
 * Opens the database file following the migration policy (docs/11-database-schema.md §6):
 *
 * 1. Probe the stored schema version without changing anything.
 * 2. If it is older than [FinanceDatabase.SCHEMA_VERSION], copy the database file to app-private storage first
 *    (keeping the last [PreMigrationBackups.KEEP]).
 * 3. Open with Room, which runs the forward-only migrations in one transaction (a failing migration leaves the file
 *    at its old version).
 * 4. After a migration, run IC-1..IC-8. On failure, close, put the pre-migration copy back and throw
 *    [MigrationIntegrityException], so the app never continues on a half-valid database.
 *
 * Interim backup format (until the Phase 7 backup format exists): the copy is the raw database file. In production
 * that file is SQLCipher-encrypted with the Keystore-wrapped database key, so the copy is encrypted at rest too.
 */
class DatabaseOpener(
    private val context: Context,
    private val openHelperFactory: SupportSQLiteOpenHelper.Factory?,
    private val fileName: String = FinanceDatabase.FILE_NAME,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val dbFile: File get() = context.getDatabasePath(fileName)
    val backups = PreMigrationBackups(File(context.noBackupFilesDir, "premigration"))

    suspend fun open(): FinanceDatabase {
        val stored = if (dbFile.exists()) storedVersion() else null
        val backup =
            if (stored != null && stored < FinanceDatabase.SCHEMA_VERSION) backups.create(dbFile, stored, clockMillis()) else null
        val db = FinanceDatabase.build(context, openHelperFactory, name = fileName)
        db.openHelper.writableDatabase // forces open + migration now, not on first query
        if (backup != null) {
            val report = IntegrityChecker(db).run()
            if (!report.isHealthy) {
                db.close()
                restore(backup)
                throw MigrationIntegrityException(checkNotNull(stored), report)
            }
        }
        return db
    }

    /**
     * Reads `user_version` by opening with a callback that aborts before any upgrade work. SQLite runs the upgrade
     * callback inside a transaction, so the abort rolls back and the file is unchanged.
     */
    internal fun storedVersion(): Int {
        var found: Int? = null
        val callback =
            object : SupportSQLiteOpenHelper.Callback(FinanceDatabase.SCHEMA_VERSION) {
                override fun onCreate(db: SupportSQLiteDatabase) = abort(0)

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = abort(oldVersion)

                override fun onDowngrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = abort(oldVersion)

                private fun abort(version: Int): Nothing {
                    found = version
                    throw ProbeAbort()
                }
            }
        val config =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(fileName)
                .callback(callback)
                .build()
        val helper = (openHelperFactory ?: FrameworkSQLiteOpenHelperFactory()).create(config)
        try {
            helper.writableDatabase
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            // Open helpers wrap callback exceptions differently; only our own abort is expected here.
            if (found == null) throw e
        } finally {
            helper.close()
        }
        return found ?: FinanceDatabase.SCHEMA_VERSION
    }

    private fun restore(backup: File) {
        listOf("-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
        val tmp = File(dbFile.path + ".restore")
        Files.copy(backup.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.move(tmp.toPath(), dbFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        File(backup.path + "-wal").takeIf { it.exists() }?.let {
            Files.copy(it.toPath(), File(dbFile.path + "-wal").toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private class ProbeAbort : RuntimeException("schema probe", null, false, false)
}

/** Raw copies of the database file taken before migrations; newest [KEEP] are kept. */
class PreMigrationBackups(
    private val dir: File,
) {
    fun list(): List<File> = dir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(".db") }.orEmpty().sortedBy { it.name }

    /** A copy taken outside migrations (e.g. before a restore); same rotation of the last [KEEP] copies. */
    fun createCopy(
        dbFile: File,
        schemaVersion: Int,
        nowMillis: Long,
    ): File = create(dbFile, schemaVersion, nowMillis)

    internal fun create(
        dbFile: File,
        fromVersion: Int,
        nowMillis: Long,
    ): File {
        check(dir.isDirectory || dir.mkdirs()) { "cannot create $dir" }
        val target = File(dir, "%s%013d-v%d.db".format(PREFIX, nowMillis, fromVersion))
        val tmp = File(dir, target.name + ".tmp")
        Files.copy(dbFile.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
        File(dbFile.path + "-wal").takeIf { it.exists() }?.let {
            Files.copy(it.toPath(), File(dir, target.name + "-wal").toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        list().dropLast(KEEP).forEach { old ->
            old.delete()
            File(old.path + "-wal").delete()
        }
        return target
    }

    companion object {
        const val KEEP = 2
        private const val PREFIX = "finance-"
    }
}
