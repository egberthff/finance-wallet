package ph.financewallet.domain.text

import java.text.Normalizer

/**
 * Case- and accent-insensitive text for matching (docs/11-database-schema.md §1 `*_norm` columns): NFKD, combining marks
 * removed, lower-case, whitespace collapsed. Used by the ledger for stored `*_norm` columns and by statement import for
 * fingerprints and duplicate matching, so both always agree.
 */
public object TextNormalizer {
    private val marks = Regex("\\p{M}+")
    private val spaces = Regex("\\s+")

    public fun normalize(text: String): String =
        Normalizer
            .normalize(text, Normalizer.Form.NFKD)
            .replace(marks, "")
            .lowercase()
            .replace(spaces, " ")
            .trim()
}
