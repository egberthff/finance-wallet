package ph.financewallet.data.backup

/** Why a backup passphrase was refused (docs/14-backup-format.md §6). */
enum class PassphraseProblem { TOO_SHORT, TOO_COMMON, TOO_REPETITIVE, MISMATCH }

/**
 * Backup passphrase rules: at least [MIN_LENGTH] characters, not one of the 10,000 most common passwords (SecLists
 * `10k-most-common.txt`, MIT License, bundled as a resource), and not a single repeated character. The app cannot recover a
 * forgotten passphrase, so the UI also states that before the first backup.
 */
object PassphrasePolicy {
    const val MIN_LENGTH = 10

    /**
     * Absolute path on purpose: R8 renames and repackages this class in release builds, so a path relative to the
     * class's package is not found and typing a backup passphrase crashed the release app (found on the emulator,
     * 2026-11-06). The resource itself keeps its original path in the APK.
     */
    internal const val RESOURCE = "/ph/financewallet/data/backup/common-passwords.txt"
    private const val STRONG_LENGTH = 16
    private const val GOOD_LENGTH = 12
    private const val MANY_CLASSES = 3

    /** Lower-cased common passwords; loaded once from the bundled resource. */
    private val common: Set<String> by lazy {
        val stream = checkNotNull(PassphrasePolicy::class.java.getResourceAsStream(RESOURCE)) { "missing $RESOURCE" }
        stream.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet() }
    }

    val commonCount: Int get() = common.size

    /** The first rule [passphrase] breaks, or null when it is acceptable. [confirmation] must match exactly. */
    fun check(
        passphrase: CharArray,
        confirmation: CharArray? = null,
    ): PassphraseProblem? {
        val text = String(passphrase)
        return when {
            passphrase.size < MIN_LENGTH -> PassphraseProblem.TOO_SHORT
            text.toSet().size == 1 -> PassphraseProblem.TOO_REPETITIVE
            isCommon(text) -> PassphraseProblem.TOO_COMMON
            confirmation != null && !confirmation.contentEquals(passphrase) -> PassphraseProblem.MISMATCH
            else -> null
        }
    }

    /**
     * Common as typed, or a common password padded with digits and symbols ("password123", "Iloveyou2024!"): the
     * 10k list has few entries of 10+ characters, and padding a common word is the most frequent way people reach the
     * minimum length. The core must keep [MIN_CORE] characters so short cores ("ab" + digits) are not over-matched.
     */
    private fun isCommon(text: String): Boolean {
        val lower = text.lowercase()
        if (lower in common) return true
        val core = lower.trim { !it.isLetter() }
        return core.length >= MIN_CORE && core != lower && core in common
    }

    private const val MIN_CORE = 4

    /**
     * Strength for the meter, 0 (refused) to 4. Only a guide: length counts most; mixing letter case, digits and symbols
     * adds one step. It never replaces [check].
     */
    fun strength(passphrase: CharArray): Int {
        if (check(passphrase) != null) return 0
        val classes =
            listOf<(Char) -> Boolean>(Char::isLowerCase, Char::isUpperCase, Char::isDigit, { c -> !c.isLetterOrDigit() })
                .count { test -> passphrase.any(test) }
        val base =
            when {
                passphrase.size >= STRONG_LENGTH -> 3
                passphrase.size >= GOOD_LENGTH -> 2
                else -> 1
            }
        return (base + if (classes >= MANY_CLASSES) 1 else 0).coerceAtMost(MAX_STRENGTH)
    }

    const val MAX_STRENGTH = 4
}
