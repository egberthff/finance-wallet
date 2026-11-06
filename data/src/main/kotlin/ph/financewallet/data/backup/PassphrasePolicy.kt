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
    private const val RESOURCE = "common-passwords.txt"
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
            text.lowercase() in common -> PassphraseProblem.TOO_COMMON
            confirmation != null && !confirmation.contentEquals(passphrase) -> PassphraseProblem.MISMATCH
            else -> null
        }
    }

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
