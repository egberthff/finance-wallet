package ph.financewallet.domain.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TextNormalizerTest {
    @Test
    fun `accents, case and spacing are normalized`() {
        assertEquals("pinas pag-ibig cafe", TextNormalizer.normalize("  Piñas\tPAG-IBIG   Café "))
        assertEquals("", TextNormalizer.normalize("   "))
        assertEquals("fi 2", TextNormalizer.normalize("ﬁ ²")) // NFKD compatibility forms
    }
}
