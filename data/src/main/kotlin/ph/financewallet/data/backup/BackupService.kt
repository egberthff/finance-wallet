package ph.financewallet.data.backup

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.IntegrityChecker
import ph.financewallet.data.db.entity.AuditEventEntity
import ph.financewallet.data.ledger.FaultInjector
import ph.financewallet.data.ledger.WriteContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.security.MessageDigest
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** What a verified backup contains, shown before the user confirms "Replace all current data" (§4 step 6). */
data class BackupSummary(
    val createdAt: Long,
    val schemaVersion: Int,
    val appVersion: Int,
    val rowCounts: Map<String, Int>,
    val firstTxnDate: LocalDate?,
    val lastTxnDate: LocalDate?,
) {
    val transactions: Int get() = rowCounts["txn"] ?: 0
    val accounts: Int get() = rowCounts["financial_account"] ?: 0
}

/**
 * Encrypted backup and restore (UC-29, BAK-01, docs/14-backup-format.md) and plaintext export (UC-30, PRIV-01).
 *
 * Payload: gzip-compressed JSON Lines: a manifest (column lists per table), one line per row, and a checksums line with
 * per-table SHA-256 of the row lines, every real account's balance and every goal's allocation. Values are typed text
 * (`"i:123"`, `"s:text"`, `null`), so integers never pass through a floating-point JSON number.
 *
 * Restore is replace-only and happens in one database transaction (docs/milestones/phase-7…md §3): the file is fully
 * verified first ([inspect]); then all tables are emptied and refilled, balances and allocations are recomputed and compared
 * with the checksums, and IC-1..IC-8 run; any mismatch or failure rolls everything back, leaving the previous data.
 */
class BackupService(
    private val db: FinanceDatabase,
    private val ctx: WriteContext,
    private val appVersion: Int,
    /** Copies the current database before a restore replaces it (app-private, last 2 kept). */
    private val safetyCopy: (suspend () -> Unit)? = null,
    private val faults: FaultInjector = FaultInjector.NONE,
) {
    private val sql: SupportSQLiteDatabase get() = db.openHelper.writableDatabase

    // ---- create -------------------------------------------------------------------------------------------------

    /**
     * Writes an encrypted backup of everything to [out] (a file the user picked). [passphrase] is wiped afterwards.
     * Returns the row counts written.
     */
    suspend fun create(
        out: OutputStream,
        passphrase: CharArray,
        kdf: Kdf = Kdf.Argon2id(),
    ): Map<String, Int> {
        PassphrasePolicy.check(passphrase)?.let { throw IllegalArgumentException("passphrase rejected: $it") }
        val header = BackupCrypto.newHeader(kdf, ctx.nowMillis(), appVersion, FinanceDatabase.SCHEMA_VERSION)
        val key =
            try {
                deriveWithFallback(passphrase, header)
            } finally {
                passphrase.fill('\u0000')
            }
        val counts =
            EncryptingOutputStream(out, key.second, key.first).use { encrypted ->
                GZIPOutputStream(encrypted).use { gz -> gz.bufferedWriter(Charsets.UTF_8).let { w -> writePayload(w).also { w.flush() } } }
            }
        db.withTransaction {
            db.accountDao().setMeta(LAST_BACKUP_KEY, ctx.nowMillis().toString())
            audit("BACKUP_CREATED", "{\"rows\":\"${counts.values.sum()}\"}")
        }
        return counts
    }

    /** Argon2id at 64 MiB, or the documented 32 MiB/t=4 setting where the allocation fails (§2). */
    private fun deriveWithFallback(
        passphrase: CharArray,
        header: BackupHeader,
    ): Pair<BackupHeader, ByteArray> =
        try {
            header to BackupCrypto.deriveKey(passphrase, header)
        } catch (_: OutOfMemoryError) {
            val low = header.copy(kdf = Kdf.ARGON2ID_LOW_MEMORY)
            low to BackupCrypto.deriveKey(passphrase, low)
        }

    /** UC-30: the same payload as plain, readable JSON Lines (the UI warns that the file is not encrypted). */
    suspend fun exportPlaintext(out: Writer): Map<String, Int> {
        val counts = writePayload(out)
        out.flush()
        audit("DATA_EXPORTED", "{\"rows\":\"${counts.values.sum()}\"}")
        return counts
    }

    /** Last user-initiated backup time (epoch ms), for the dashboard reminder (R-10). */
    suspend fun lastBackupAt(): Long? = db.accountDao().meta(LAST_BACKUP_KEY)?.toLongOrNull()

    private fun writePayload(w: Writer): Map<String, Int> {
        val columns = TABLES.associateWith(::columnsOf)
        w.write(
            JSONObject()
                .put("t", "manifest")
                .put("formatVer", 1)
                .put("schemaVer", FinanceDatabase.SCHEMA_VERSION)
                .put("deviceId", ctx.deviceId)
                .put("createdAt", ctx.nowMillis())
                .put("columns", JSONObject().apply { columns.forEach { (t, c) -> put(t, JSONArray(c)) } })
                .toString() + "\n",
        )
        val counts = LinkedHashMap<String, Int>()
        val hashes = JSONObject()
        for (table in TABLES) {
            val digest = MessageDigest.getInstance("SHA-256")
            var n = 0
            sql.query("SELECT * FROM `$table` ${ORDER[table] ?: "ORDER BY rowid"}").use { c ->
                while (c.moveToNext()) {
                    val values = JSONArray()
                    for (i in 0 until c.columnCount) values.put(encode(c, i))
                    val line =
                        JSONObject()
                            .put("t", "row")
                            .put("table", table)
                            .put("v", values)
                            .toString()
                    digest.update(line.toByteArray(Charsets.UTF_8))
                    w.write(line + "\n")
                    n++
                }
            }
            counts[table] = n
            hashes.put(table, hex(digest.digest()))
        }
        w.write(
            JSONObject()
                .put(
                    "t",
                    "checksums",
                ).put("perTableSha256", hashes)
                .put("counts", JSONObject(counts as Map<*, *>))
                .put("ledger", ledgerChecksums())
                .toString() +
                "\n",
        )
        return counts
    }

    private fun encode(
        c: android.database.Cursor,
        i: Int,
    ): Any =
        when (c.getType(i)) {
            android.database.Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
            android.database.Cursor.FIELD_TYPE_INTEGER -> "i:" + c.getLong(i)
            android.database.Cursor.FIELD_TYPE_STRING -> "s:" + c.getString(i)
            else -> throw IllegalStateException("unsupported column type in ${c.getColumnName(i)}")
        }

    /** Per real account and currency: Σ entries of live posted transactions; per goal: Σ live allocations. */
    private fun ledgerChecksums(): JSONObject {
        val balances = JSONObject()
        sql
            .query(
                """SELECT e.ledger_account_id, e.currency, SUM(e.amount_minor) FROM entry e JOIN txn t ON t.id = e.txn_id
                   JOIN ledger_account a ON a.id = e.ledger_account_id
                   WHERE t.deleted_at IS NULL AND a.class IN ('ASSET','LIABILITY') GROUP BY e.ledger_account_id, e.currency
                   ORDER BY e.ledger_account_id, e.currency""",
            ).use { c -> while (c.moveToNext()) balances.put(c.getString(0) + "|" + c.getString(1), c.getLong(2).toString()) }
        val goals = JSONObject()
        sql
            .query(
                """SELECT e.goal_id, SUM(e.amount_minor) FROM allocation_entry e JOIN allocation_group g ON g.id = e.group_id
                   WHERE g.deleted_at IS NULL AND e.goal_id IS NOT NULL GROUP BY e.goal_id ORDER BY e.goal_id""",
            ).use { c -> while (c.moveToNext()) goals.put(c.getString(0), c.getLong(1).toString()) }
        return JSONObject().put("balances", balances).put("goals", goals)
    }

    private fun columnsOf(table: String): List<String> =
        sql.query("PRAGMA table_info(`$table`)").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }

    // ---- inspect and restore ------------------------------------------------------------------------------------

    /** Opens the stream and checks the header; [open] is called again for each pass so nothing is buffered on disk. */
    private fun decrypting(
        open: () -> InputStream,
        passphrase: CharArray,
    ): Pair<BackupHeader, BufferedReader> {
        val input = open()
        val header = BackupHeader.read(input)
        if (header.schemaVersion > FinanceDatabase.SCHEMA_VERSION) fail(BackupError.SCHEMA_TOO_NEW, "v${header.schemaVersion}")
        val key = BackupCrypto.deriveKey(passphrase, header)
        return header to GZIPInputStream(DecryptingInputStream(input, key, header)).bufferedReader(Charsets.UTF_8)
    }

    /**
     * Pass 1: decrypts and verifies the whole file (every chunk tag, counts and per-table hashes) without writing anything.
     * Throws [BackupException] for a wrong passphrase or any damage.
     */
    suspend fun inspect(
        open: () -> InputStream,
        passphrase: CharArray,
    ): BackupSummary {
        val (header, reader) = decrypting(open, passphrase)
        return reader.use { r -> Verifier(columnsByTable = { t -> columnsOf(t) }).run(r, onRow = null).summary(header) }
    }

    /**
     * Pass 2: replaces all data with the backup in one transaction, after a safety copy of the current database.
     * The previous data stays untouched if anything fails. [passphrase] is wiped afterwards.
     */
    suspend fun restore(
        open: () -> InputStream,
        passphrase: CharArray,
    ): BackupSummary {
        try {
            inspect(open, passphrase)
            safetyCopy?.invoke()
            val (header, reader) = decrypting(open, passphrase)
            val summary =
                reader.use { r ->
                    db.withTransaction { replaceAll(r).summary(header) }
                }
            db.withTransaction {
                audit(
                    "RESTORE",
                    "{\"rows\":\"${summary.rowCounts.values.sum()}\",\"createdAt\":\"${summary.createdAt}\"}",
                )
            }
            return summary
        } finally {
            passphrase.fill('\u0000')
        }
    }

    private suspend fun replaceAll(reader: BufferedReader): Verifier {
        val keepDeviceId = db.accountDao().meta(DEVICE_ID_KEY)
        APPEND_ONLY_DELETE_TRIGGERS.forEach { sql.execSQL("DROP TRIGGER IF EXISTS $it") }
        TABLES.asReversed().forEach { sql.execSQL("DELETE FROM `$it`") }
        faults.at("restore_after_delete")
        sql.execSQL("PRAGMA defer_foreign_keys = ON")
        val statements = HashMap<String, androidx.sqlite.db.SupportSQLiteStatement>()
        val verifier =
            Verifier(columnsByTable = ::columnsOf).run(reader) { table, columns, values ->
                if (table == "app_meta" && values.firstOrNull() == "s:$DEVICE_ID_KEY") return@run
                val st =
                    statements.getOrPut(table + columns.joinToString(",")) {
                        sql.compileStatement(
                            "INSERT INTO `$table` (${columns.joinToString(",") { "`$it`" }}) VALUES (${columns.joinToString(",") { "?" }})",
                        )
                    }
                st.clearBindings()
                values.forEachIndexed { i, v ->
                    when {
                        v == null -> st.bindNull(i + 1)
                        v.startsWith("i:") -> st.bindLong(i + 1, v.substring(2).toLong())
                        v.startsWith("s:") -> st.bindString(i + 1, v.substring(2))
                        else -> fail(BackupError.INVALID_CONTENT, "value in $table")
                    }
                }
                st.executeInsert()
            }
        faults.at("restore_after_insert")
        keepDeviceId?.let { db.accountDao().setMeta(DEVICE_ID_KEY, it) }
        restoreTriggers()
        if (ledgerChecksums().toString() != verifier.ledger?.toString()) fail(BackupError.CHECKSUM_MISMATCH, "ledger")
        val report = IntegrityChecker(db).run()
        if (!report.isHealthy) fail(BackupError.CHECKSUM_MISMATCH, "integrity ${report.failures.keys}")
        return verifier
    }

    private fun restoreTriggers() {
        listOf("txn_revision", "audit_event").forEach { t ->
            sql.execSQL("CREATE TRIGGER IF NOT EXISTS trg_${t}_no_delete BEFORE DELETE ON $t BEGIN SELECT RAISE(ABORT, 'APPEND_ONLY'); END")
        }
    }

    private suspend fun audit(
        kind: String,
        detail: String,
    ) = db.accountDao().insertAudit(AuditEventEntity(ctx.ids.newId(), kind, "backup", null, detail, ctx.deviceId, ctx.nowMillis()))

    /**
     * Streams the payload, checking structure, counts and per-table hashes. [onRow] receives every row (restore) or is
     * null (inspect). The checksums line must come last; anything after it, or a missing one, fails.
     */
    private class Verifier(
        private val columnsByTable: (String) -> List<String>,
    ) {
        var manifest: JSONObject? = null
        var ledger: JSONObject? = null
        private val counts = LinkedHashMap<String, Int>()
        private val digests = HashMap<String, MessageDigest>()
        private var firstDate: String? = null
        private var lastDate: String? = null

        fun run(
            reader: BufferedReader,
            onRow: ((String, List<String>, List<String?>) -> Unit)?,
        ): Verifier {
            try {
                val m = JSONObject(reader.readLine() ?: fail(BackupError.INVALID_CONTENT, "empty"))
                if (m.optString("t") != "manifest") fail(BackupError.INVALID_CONTENT, "no manifest")
                manifest = m
                val columns = m.getJSONObject("columns")
                columns.keys().forEach { t ->
                    if (t !in TABLES) fail(BackupError.INVALID_CONTENT, "unknown table $t")
                    val current = columnsByTable(t).toSet()
                    val backup = columns.getJSONArray(t).strings()
                    if (!current.containsAll(backup)) fail(BackupError.INVALID_CONTENT, "columns of $t")
                }
                var line = reader.readLine()
                var checksums: JSONObject? = null
                while (line != null) {
                    if (checksums != null) fail(BackupError.INVALID_CONTENT, "data after checksums")
                    val o = JSONObject(line)
                    when (o.getString("t")) {
                        "row" -> row(o, line, columns, onRow)
                        "checksums" -> checksums = o
                        else -> fail(BackupError.INVALID_CONTENT, "record ${o.getString("t")}")
                    }
                    line = reader.readLine()
                }
                verify(checksums ?: fail(BackupError.CHECKSUM_MISMATCH, "missing checksums"))
            } catch (e: JSONException) {
                fail(BackupError.INVALID_CONTENT, e.message.orEmpty(), e)
            }
            return this
        }

        private fun row(
            o: JSONObject,
            raw: String,
            columns: JSONObject,
            onRow: ((String, List<String>, List<String?>) -> Unit)?,
        ) {
            val table = o.getString("table")
            val cols = columns.optJSONArray(table)?.strings() ?: fail(BackupError.INVALID_CONTENT, "row of $table")
            val v = o.getJSONArray("v")
            if (v.length() != cols.size) fail(BackupError.INVALID_CONTENT, "width of $table")
            digests.getOrPut(table) { MessageDigest.getInstance("SHA-256") }.update(raw.toByteArray(Charsets.UTF_8))
            counts[table] = (counts[table] ?: 0) + 1
            val values = (0 until v.length()).map { if (v.isNull(it)) null else v.getString(it) }
            if (table == "txn") {
                val date = values[cols.indexOf("txn_date")]?.removePrefix("s:")
                if (date != null) {
                    if (firstDate == null || date < checkNotNull(firstDate)) firstDate = date
                    if (lastDate == null || date > checkNotNull(lastDate)) lastDate = date
                }
            }
            onRow?.invoke(table, cols, values)
        }

        private fun verify(checksums: JSONObject) {
            val expectedCounts = checksums.getJSONObject("counts")
            val hashes = checksums.getJSONObject("perTableSha256")
            expectedCounts.keys().forEach { t ->
                if (expectedCounts.getInt(t) != (counts[t] ?: 0)) fail(BackupError.CHECKSUM_MISMATCH, "count of $t")
                val actual = digests[t]?.let { hex(it.digest()) } ?: hex(MessageDigest.getInstance("SHA-256").digest())
                if (hashes.getString(t) != actual) fail(BackupError.CHECKSUM_MISMATCH, "hash of $t")
            }
            if (counts.keys.any { !expectedCounts.has(it) }) fail(BackupError.CHECKSUM_MISMATCH, "unexpected table")
            ledger = checksums.getJSONObject("ledger")
        }

        fun summary(header: BackupHeader) =
            BackupSummary(
                createdAt = header.createdAt,
                schemaVersion = header.schemaVersion,
                appVersion = header.appVersion,
                rowCounts = counts.toMap(),
                firstTxnDate = firstDate?.let(LocalDate::parse),
                lastTxnDate = lastDate?.let(LocalDate::parse),
            )

        private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
    }

    companion object {
        private const val LAST_BACKUP_KEY = "backup:last_at"
        private const val DEVICE_ID_KEY = "device_id"

        /** Every table of schema v4, parents before children (restore inserts in this order). */
        val TABLES: List<String> =
            listOf(
                "ledger_account",
                "financial_account",
                "category",
                "savings_goal",
                "budget",
                "budget_category",
                "budget_account",
                "budget_period",
                "import_profile",
                "import_batch",
                "recurring_rule",
                "occurrence",
                "txn",
                "entry",
                "txn_revision",
                "reconciliation",
                "debt",
                "debt_schedule_item",
                "asset",
                "asset_valuation",
                "allocation_group",
                "allocation_entry",
                "import_row",
                "txn_fingerprint",
                "category_rule",
                "audit_event",
                "app_meta",
            )

        /** Row order inside a table where parents must come first (self-references). */
        private val ORDER = mapOf("category" to "ORDER BY parent_id IS NOT NULL, rowid")

        /** Append-only triggers that would block emptying the tables; recreated inside the same transaction. */
        private val APPEND_ONLY_DELETE_TRIGGERS = listOf("trg_txn_revision_no_delete", "trg_audit_event_no_delete")

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}
