package ph.financewallet.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ph.financewallet.app.ui.theme.AppIcons
import ph.financewallet.app.ui.theme.CategoryIcons
import ph.financewallet.data.account.CategoryService
import ph.financewallet.data.account.DefaultCategories
import kotlin.math.pow

/** The category icon map and colour palette (ANALYSIS.md blueprint C). */
class CategoryIconsTest {
    @Test
    fun `every stored seed key and the default key has its own glyph`() {
        val keys = DefaultCategories.ALL.map { it.icon }.toSet() + CategoryService.DEFAULT_ICON
        keys.forEach { key -> assertNotSame("no glyph for $key", AppIcons.Label, CategoryIcons.icon(key)) }
    }

    @Test
    fun `every offered glyph parses and keys are unique`() {
        CategoryIcons.ICONS.forEach { assertTrue(it.icon().root.size > 0) }
        assertEquals(
            CategoryIcons.ICONS.size,
            CategoryIcons.ICONS
                .map { it.key }
                .toSet()
                .size,
        )
    }

    @Test
    fun `every seed colour is a palette colour`() {
        val palette = CategoryIcons.PALETTE.map { it.argb }.toSet()
        DefaultCategories.ALL.forEach { assertTrue("${it.name} colour not in palette", it.colorArgb in palette) }
    }

    @Test
    fun `an unknown key falls back to the label glyph`() {
        assertSame(AppIcons.Label, CategoryIcons.icon("from-a-newer-version"))
    }

    @Test
    fun `palette has 10 to 12 distinct opaque colours starting with the default`() {
        val colours = CategoryIcons.PALETTE.map { it.argb }
        assertTrue(colours.size in 10..12)
        assertEquals(colours.size, colours.toSet().size)
        assertEquals(CategoryService.DEFAULT_COLOR, colours.first())
        colours.forEach { assertEquals("opaque", 0xFFL, it ushr ALPHA_SHIFT) }
    }

    @Test
    fun `each colour keeps 3 to 1 contrast on the card surface in light and dark themes`() {
        CategoryIcons.PALETTE.forEach { c ->
            val light = contrast(c.argb, LIGHT_CARD)
            val dark = contrast(c.argb, DARK_CARD)
            assertTrue("%08X on light: %.2f".format(c.argb, light), light >= MIN_CONTRAST)
            assertTrue("%08X on dark: %.2f".format(c.argb, dark), dark >= MIN_CONTRAST)
        }
    }

    private fun contrast(
        a: Long,
        b: Long,
    ): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + FLARE) / (lo + FLARE)
    }

    /** WCAG 2.x relative luminance. */
    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val v = ((argb shr shift) and 0xFF) / 255.0
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private companion object {
        const val ALPHA_SHIFT = 24

        // surfaceContainerLow in Theme.kt (the SurfaceCard colour): white in light, #151C25 in dark.
        const val LIGHT_CARD = 0xFFFFFFFFL
        const val DARK_CARD = 0xFF151C25L
        const val MIN_CONTRAST = 3.0
        const val FLARE = 0.05
    }
}
