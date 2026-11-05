package ph.financewallet.data.db

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ph.financewallet.domain.common.DomainError
import ph.financewallet.domain.common.DomainResult

@RunWith(RobolectricTestRunner::class)
class ValidatedWriteTest {
    private lateinit var db: FinanceDatabase

    @Before
    fun setUp() {
        db = FinanceDatabase.build(ApplicationProvider.getApplicationContext(), openHelperFactory = null, inMemory = true)
    }

    @After
    fun tearDown() = db.close()

    private fun rowCount(): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM app_meta").use { c ->
            c.moveToFirst()
            c.getInt(0)
        }

    private fun insert(key: String) = db.openHelper.writableDatabase.execSQL("INSERT INTO app_meta(key, value) VALUES ('$key', 'v')")

    @Test
    fun `a failed check after a write rolls the write back`() =
        runTest {
            val result =
                db.validatedWrite {
                    insert("written-before-failure")
                    ensure(false, "field", "SOME_CODE")
                }
            assertEquals(DomainResult.Err(DomainError.Validation("field", "SOME_CODE")), result)
            assertEquals(0, rowCount())
        }

    @Test
    fun `a successful block commits`() =
        runTest {
            val result =
                db.validatedWrite {
                    insert("a")
                    "done"
                }
            assertEquals(DomainResult.Ok("done"), result)
            assertEquals(1, rowCount())
        }

    @Test
    fun `an inner failure bound by the outer block rolls back both`() =
        runTest {
            val result =
                db.validatedWrite {
                    insert("outer")
                    db
                        .validatedWrite {
                            insert("inner")
                            fail("inner", "INNER_FAILED")
                        }.bind()
                }
            assertEquals("INNER_FAILED", (result as DomainResult.Err).error.code)
            assertEquals(0, rowCount())
        }

    @Test
    fun `a committed inner write is kept when the outer block succeeds`() =
        runTest {
            db.validatedWrite {
                db.validatedWrite { insert("inner") }.bind()
                insert("outer")
            }
            assertEquals(2, rowCount())
            assertEquals(listOf("inner", "outer"), keys())
        }

    private fun keys(): List<String> =
        db.openHelper.readableDatabase.query("SELECT key FROM app_meta ORDER BY key").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
}
