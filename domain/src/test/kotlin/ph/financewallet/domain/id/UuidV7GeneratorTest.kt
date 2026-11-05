package ph.financewallet.domain.id

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class UuidV7GeneratorTest {
    private val fixedInstant = Instant.parse("2026-11-05T02:00:00Z")
    private val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    @Test
    fun `ids are RFC 9562 version 7 with the clock timestamp`() {
        val id = UuidV7Generator(fixedClock).newId()
        val uuid = UUID.fromString(id)
        assertEquals(7, uuid.version())
        assertEquals(2, uuid.variant())
        assertEquals(fixedInstant.toEpochMilli(), uuid.mostSignificantBits ushr 16)
        assertEquals(id.lowercase(), id)
        assertEquals(36, id.length)
    }

    @Test
    fun `ids are unique and strictly increasing even within one millisecond`() {
        val generator = UuidV7Generator(fixedClock, SecureRandom())
        val ids = List(20_000) { generator.newId() } // > 4096 forces counter overflow handling
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(ids.sorted(), ids)
    }

    @Test
    fun `clock going backwards does not break ordering`() {
        var now = fixedInstant.toEpochMilli()
        val clock =
            object : Clock() {
                override fun getZone() = ZoneOffset.UTC

                override fun withZone(zone: java.time.ZoneId?) = this

                override fun instant(): Instant = Instant.ofEpochMilli(now)
            }
        val generator = UuidV7Generator(clock)
        val a = generator.newId()
        now -= 5_000
        val b = generator.newId()
        assertTrue(b > a)
    }
}
