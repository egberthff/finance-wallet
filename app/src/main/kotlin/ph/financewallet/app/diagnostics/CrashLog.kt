package ph.financewallet.app.diagnostics

import java.io.File
import java.io.OutputStream

/**
 * On-device crash reports (roadmap Phase 9; the app has no network, ADR-0010, so nothing is ever sent anywhere).
 *
 * Privacy: a report holds the exception types and code locations only. Exception messages are left out on purpose:
 * they can contain amounts, names, file names or SQL values. The user decides whether to save a report through the
 * system file picker (Privacy & data) and whom to give it to. Reports live in app-private, no-backup storage and are
 * removed by "Delete all data".
 */
class CrashLog(
    private val dir: File,
    private val appVersion: String,
    private val device: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Writes one report and keeps only the newest [MAX_REPORTS]. Never throws: it runs while the app is crashing. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun record(error: Throwable) {
        try {
            if (!dir.isDirectory) dir.mkdirs()
            val t = now()
            File(dir, "$PREFIX$t$SUFFIX").writeText(format(error, t, appVersion, device))
            reports().drop(MAX_REPORTS).forEach { it.delete() }
        } catch (ignored: Exception) {
            // Recording must never hide the original crash.
        }
    }

    /** Newest first. */
    fun reports(): List<File> =
        dir
            .listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
            .orEmpty()
            .sortedByDescending {
                it.name
                    .removePrefix(PREFIX)
                    .removeSuffix(SUFFIX)
                    .toLongOrNull() ?: 0L
            }

    /** Writes every report, newest first, to [out] (closed afterwards). Returns how many were written. */
    fun exportTo(out: OutputStream): Int {
        val all = reports()
        out.bufferedWriter().use { w ->
            all.forEachIndexed { i, f ->
                if (i > 0) w.write("\n")
                w.write(f.readText())
            }
        }
        return all.size
    }

    fun clear() {
        reports().forEach { it.delete() }
    }

    /** Records uncaught exceptions, then hands them to the previous handler (the system still shows its crash UI). */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(error)
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val MAX_REPORTS = 5
        private const val PREFIX = "crash-"
        private const val SUFFIX = ".txt"
        private const val MAX_FRAMES = 40
        private const val MAX_CAUSES = 5

        /** Exception types and stack frames of the whole cause chain; never messages. */
        fun format(
            error: Throwable,
            atMillis: Long,
            appVersion: String,
            device: String,
        ): String =
            buildString {
                appendLine("Finance Manager crash report")
                appendLine("time_ms=$atMillis app=$appVersion device=$device")
                var current: Throwable? = error
                var depth = 0
                while (current != null && depth < MAX_CAUSES) {
                    appendLine((if (depth == 0) "" else "Caused by: ") + current.javaClass.name)
                    current.stackTrace.take(MAX_FRAMES).forEach { appendLine("    at $it") }
                    current = current.cause?.takeIf { it !== current }
                    depth++
                }
            }
    }
}
