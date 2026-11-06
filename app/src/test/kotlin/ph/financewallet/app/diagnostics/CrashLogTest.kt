package ph.financewallet.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class CrashLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var clock = 1_000L

    private fun log(dir: File = tmp.newFolder("crash")) = CrashLog(dir, appVersion = "0.9.1", device = "test", now = { clock++ })

    @Test
    fun `report has types and frames of the cause chain but never messages`() {
        val secret = "GCash 09171234567 balance 12,345.67"
        val error = IllegalStateException(secret, java.io.IOException("Salary ₱30,000 for Juan"))
        val text = CrashLog.format(error, 5L, "0.9.1", "Pixel")
        assertTrue(text.contains("java.lang.IllegalStateException"))
        assertTrue(text.contains("Caused by: java.io.IOException"))
        assertTrue(text.contains("at ph.financewallet.app.diagnostics.CrashLogTest"))
        assertFalse(text.contains("09171234567"))
        assertFalse(text.contains("12,345.67"))
        assertFalse(text.contains("Juan"))
    }

    @Test
    fun `keeps only the newest reports and exports them newest first`() {
        val log = log()
        repeat(CrashLog.MAX_REPORTS + 2) { log.record(RuntimeException("x$it")) }
        val reports = log.reports()
        assertEquals(CrashLog.MAX_REPORTS, reports.size)
        val out = ByteArrayOutputStream()
        assertEquals(CrashLog.MAX_REPORTS, log.exportTo(out))
        val exported = out.toString(Charsets.UTF_8.name())
        assertTrue(exported.indexOf("time_ms=1006") < exported.indexOf("time_ms=1002"))
        assertFalse(exported.contains("time_ms=1000"))
        log.clear()
        assertTrue(log.reports().isEmpty())
    }

    @Test
    fun `recording into an unusable folder does not throw`() {
        val file = tmp.newFile("not-a-folder")
        CrashLog(file, "0.9.1", "test").record(RuntimeException())
    }

    @Test
    fun `installed handler records and then delegates to the previous handler`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var delegated: Throwable? = null
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, e -> delegated = e }
            val log = log()
            log.install()
            val boom = IllegalArgumentException("secret")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)
            assertEquals(boom, delegated)
            assertEquals(1, log.reports().size)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
