package ph.financewallet.app.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import ph.financewallet.app.AppServices
import ph.financewallet.data.advanced.RuleKind
import ph.financewallet.data.advanced.RuleSpec
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.ledger.NewAccount
import ph.financewallet.domain.common.getOrThrow
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.ledger.FinancialAccountType
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import ph.financewallet.domain.recurring.Recurrence
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** BILL-03 reminders: what is due, the notification content and privacy, and the daily alarm. */
@RunWith(RobolectricTestRunner::class)
class BillRemindersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: FinanceDatabase
    private lateinit var s: AppServices
    private val clock = Clock.fixed(Instant.parse("2026-11-13T03:00:00Z"), ZoneOffset.UTC) // Nov 13, Manila
    private val today = LocalDate.of(2026, 11, 13)

    private fun p(minor: Long) = Money(minor, CurrencyCode.PHP)

    @Before
    fun setUp() =
        runBlocking<Unit> {
            db = FinanceDatabase.build(context, openHelperFactory = null, inMemory = true)
            s = AppServices.create(db, clock)
            val gcash =
                s.ledger
                    .createAccount(
                        NewAccount("GCash", FinancialAccountType.EWALLET, CurrencyCode.PHP, today, p(0)),
                    ).getOrThrow()
            val bills = s.categories.create("Bills", AccountClass.EXPENSE).getOrThrow()
            s.recurring
                .createRule(
                    RuleSpec(
                        RuleKind.BILL,
                        "Internet",
                        p(169_900),
                        Recurrence.Monthly(LocalDate.of(2026, 11, 1), 15),
                        accountId = gcash,
                        categoryId = bills,
                        reminderDaysBefore = 3,
                    ),
                ).getOrThrow()
            s.recurring
                .createRule(
                    RuleSpec(
                        RuleKind.BILL,
                        "Rent",
                        p(1_200_000),
                        Recurrence.Monthly(LocalDate.of(2026, 11, 1), 30),
                        accountId = gcash,
                        reminderDaysBefore = 2,
                    ),
                ).getOrThrow()
            s.recurring
                .createRule(
                    RuleSpec(RuleKind.SUBSCRIPTION, "No reminder", p(100), Recurrence.Monthly(LocalDate.of(2026, 11, 1), 14)),
                ).getOrThrow()
        }

    @After
    fun tearDown() = db.close()

    @Test
    fun `only rules with a started reminder window are due`() =
        runBlocking<Unit> {
            val due = BillReminders.check(s)
            assertEquals(listOf("Internet"), due.map { it.name }) // Nov 15 − 3 days ≤ Nov 13; rent's window starts Nov 28
            val (title, body) = BillReminders.message(context, due, today)
            assertEquals("1 bill due soon", title)
            assertEquals("Internet (2026-11-15)", body)
        }

    @Test
    fun `a notification is posted with a private lock-screen version`() =
        runBlocking<Unit> {
            shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            BillReminders.notify(context, BillReminders.check(s), today)
            val manager = context.getSystemService(NotificationManager::class.java)
            val posted = shadowOf(manager).allNotifications.single()
            assertEquals(NotificationCompat.VISIBILITY_PRIVATE, posted.visibility)
            assertNotNull(posted.publicVersion)
            assertEquals("You have bills to check", posted.publicVersion.extras.getString("android.title"))
            assertTrue(
                posted.extras
                    .getString("android.text")
                    .orEmpty()
                    .contains("Internet"),
            )
            assertNotNull(manager.getNotificationChannel(BillReminders.CHANNEL_ID))
        }

    @Test
    fun `nothing is posted without notification permission or when nothing is due`() =
        runBlocking<Unit> {
            shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            BillReminders.notify(context, BillReminders.check(s), today)
            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            BillReminders.notify(context, emptyList(), today)
            assertEquals(0, shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.size)
        }

    @Test
    fun `the daily check is scheduled as an inexact repeating alarm`() {
        BillReminders.schedule(context)
        val alarm = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.single()
        assertEquals(AlarmManager.INTERVAL_DAY, alarm.getIntervalMs())
        assertEquals(AlarmManager.RTC, alarm.getType())
    }
}
