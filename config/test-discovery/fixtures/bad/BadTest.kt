// Deliberately broken fixture: JUnit 5 silently skips @Test methods that return a value. Never compiled.
class BadTest {
    @Test
    fun `property test that never runs`() =
        runBlocking {
            checkAll(Arb.int()) { }
        }
}
