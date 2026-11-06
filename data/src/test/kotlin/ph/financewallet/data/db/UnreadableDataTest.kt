package ph.financewallet.data.db

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** R-11: an unreadable database and its wrapped key are moved aside together and never deleted. */
class UnreadableDataTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `moves present files with their contents and skips missing ones`() {
        val dbDir = tmp.newFolder("databases")
        val keep = tmp.newFolder("no_backup")
        val db = File(dbDir, "finance.db").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val wal = File(dbDir, "finance.db-wal").apply { writeBytes(byteArrayOf(4)) }
        val shm = File(dbDir, "finance.db-shm") // never created
        val key = File(keep, "db.key.enc").apply { writeBytes(byteArrayOf(9, 9)) }

        val dir = UnreadableData.setAside(listOf(db, wal, shm, key), keep, nowMillis = 42L)

        checkNotNull(dir)
        assertEquals(File(File(keep, UnreadableData.FOLDER), "42"), dir)
        assertFalse(db.exists() || wal.exists() || key.exists())
        assertArrayEquals(byteArrayOf(1, 2, 3), File(dir, "finance.db").readBytes())
        assertArrayEquals(byteArrayOf(4), File(dir, "finance.db-wal").readBytes())
        assertArrayEquals(byteArrayOf(9, 9), File(dir, "db.key.enc").readBytes())
        assertFalse(File(dir, "finance.db-shm").exists())
    }

    @Test
    fun `nothing to move returns null and creates nothing`() {
        val keep = tmp.newFolder("no_backup")
        assertNull(UnreadableData.setAside(listOf(File(keep, "missing")), keep, nowMillis = 1L))
        assertFalse(File(keep, UnreadableData.FOLDER).exists())
    }

    @Test
    fun `a second recovery keeps the first one`() {
        val keep = tmp.newFolder("no_backup")
        val a = File(keep, "db.key.enc").apply { writeText("first") }
        val first = checkNotNull(UnreadableData.setAside(listOf(a), keep, nowMillis = 1L))
        val b = File(keep, "db.key.enc").apply { writeText("second") }
        val second = checkNotNull(UnreadableData.setAside(listOf(b), keep, nowMillis = 2L))
        assertEquals("first", File(first, "db.key.enc").readText())
        assertEquals("second", File(second, "db.key.enc").readText())
    }
}
