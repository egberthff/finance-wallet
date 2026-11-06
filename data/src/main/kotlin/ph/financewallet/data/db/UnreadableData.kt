package ph.financewallet.data.db

import java.io.File
import java.io.IOException

/**
 * Recovery after the database key can no longer be unwrapped (R-11, e.g. the Keystore key was invalidated).
 *
 * Nothing is deleted: the encrypted database files and the wrapped key move together into a dated folder in
 * app-private storage, so a later recovery stays possible if the Keystore failure was temporary. The app then starts
 * with a new, empty database, into which the user restores a `.fwbak` backup (docs/14-backup-format.md).
 */
object UnreadableData {
    const val FOLDER = "unreadable"

    /**
     * Moves every existing file in [files] into `targetRoot/unreadable/<nowMillis>/`. Returns that folder, or null if
     * none of the files existed. Throws [IOException] if a file could not be moved; files already moved stay moved,
     * and nothing is ever deleted without a copy.
     */
    fun setAside(
        files: List<File>,
        targetRoot: File,
        nowMillis: Long,
    ): File? {
        val present = files.filter { it.exists() }
        if (present.isEmpty()) return null
        val dir = File(File(targetRoot, FOLDER), nowMillis.toString())
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.name}")
        present.forEach { moveInto(it, dir) }
        return dir
    }

    private fun moveInto(
        file: File,
        dir: File,
    ) {
        val target = File(dir, file.name)
        if (file.renameTo(target)) return
        // Different filesystems or a busy rename: copy first, delete the original only after the copy succeeded.
        file.copyTo(target, overwrite = false)
        if (!file.delete()) throw IOException("copied but could not remove ${file.name}")
    }
}
