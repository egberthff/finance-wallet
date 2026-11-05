// Clean fixture: mentions of Double or 1.5 inside comments must not be flagged.
/* Block comment with Float and 3.14 and toDouble() */
package fixture

class Good {
    val label = "Double check 1.5 and 2e3 in a string"
    val raw = """multi-line string with 0.5 and Float"""
    val ch = '.'
    val minor: Long = 12_345L
    val version = listOf(1, 2, 3)
    val days = 1..31
    val halfOpen = 0 until 10
    fun add(a: Long, b: Long): Long = Math.addExact(a, b)
}
