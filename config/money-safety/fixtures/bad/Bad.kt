// Deliberately violating fixture for the money-safety rule self-test. Never compiled.
package fixture

class Bad {
    val rate: Double = 0.12
    val f = 1.5f
    val e = 1e3
    fun convert(minor: Long) = minor.toDouble() / 100
    var ratio: Float = 0F
}
