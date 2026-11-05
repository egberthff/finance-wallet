package ph.financewallet.domain.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DomainResultTest {
    private val ok: DomainResult<Int> = DomainResult.Ok(2)
    private val err: DomainResult<Int> = DomainResult.Err(DomainError.Validation("amount", "AMOUNT_ZERO"))

    @Test
    fun `map and flatMap transform Ok and propagate Err`() {
        assertEquals(DomainResult.Ok(4), ok.map { it * 2 })
        assertEquals(err, err.map { it * 2 })
        assertEquals(DomainResult.Ok("2"), ok.flatMap { DomainResult.Ok(it.toString()) })
        assertEquals(err, err.flatMap { DomainResult.Ok(it.toString()) })
    }

    @Test
    fun `getOrThrow and errorOrNull`() {
        assertEquals(2, ok.getOrThrow())
        assertThrows<IllegalStateException> { err.getOrThrow() }
        assertNull(ok.errorOrNull())
        assertEquals("AMOUNT_ZERO", err.errorOrNull()?.code)
    }

    @Test
    fun `validating converts each failure kind and binds nested results`() {
        assertEquals("AMOUNT_ZERO", validating { ensure(false, "amount", "AMOUNT_ZERO") }.errorOrNull()?.code)
        assertEquals(DomainError.Invariant("UNBALANCED", "x"), validating { invariant(false, "UNBALANCED") { "x" } }.errorOrNull())
        assertEquals(DomainError.NotFound("account", "a1"), validating { notFound("account", "a1") }.errorOrNull())
        assertEquals("NOT_FOUND", DomainError.NotFound("account", "a1").code)
        assertEquals(DomainResult.Ok(3), validating { ok.bind() + 1 })
        assertEquals(err, validating { err.bind() + 1 })
        assertEquals(DomainResult.Ok(Unit), validating { invariant(true, "X") { "never" } })
    }

    @Test
    fun `unexpected exceptions are not swallowed`() {
        assertThrows<ArithmeticException> { validating { Math.addExact(Long.MAX_VALUE, 1L) } }
    }
}
