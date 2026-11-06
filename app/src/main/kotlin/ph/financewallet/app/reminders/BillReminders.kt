package ph.financewallet.app.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ph.financewallet.app.AppServices
import ph.financewallet.app.FinanceApp
import ph.financewallet.app.MainActivity
import ph.financewallet.app.R
import ph.financewallet.data.advanced.OccurrenceState
import ph.financewallet.data.advanced.UpcomingItem
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Bill reminders (BILL-03). Delivery is best-effort: the daily alarm is inexact, so Android may delay it (Doze, battery
 * savers). The Bills screen is always the source of truth. Everything runs on the device; no network is involved.
 */
object BillReminders {
    const val CHANNEL_ID = "bills"
    private const val NOTIFICATION_ID = 1001
    private const val ALARM_REQUEST = 7001
    private const val CHECK_HOUR = 8
    private const val MAX_NAMED = 3

    /** Materializes occurrences, runs opt-in auto-post, and returns unpaid items whose reminder window has started. */
    suspend fun check(services: AppServices): List<UpcomingItem> {
        services.recurring.materialize()
        services.recurring.autoPost()
        return services.recurring.remindersDue()
    }

    /**
     * Title and body for [items]. The lock-screen version (see [notify]) never shows names or amounts; this full text
     * appears only after the phone is unlocked (VISIBILITY_PRIVATE).
     */
    fun message(
        context: Context,
        items: List<UpcomingItem>,
        today: LocalDate,
    ): Pair<String, String> {
        val overdue = items.count { it.state == OccurrenceState.OVERDUE || it.dueDate.isBefore(today) }
        val title =
            if (overdue > 0) {
                context.resources.getQuantityString(R.plurals.reminder_title_overdue, overdue, overdue)
            } else {
                context.resources.getQuantityString(R.plurals.reminder_title_due, items.size, items.size)
            }
        val named =
            items
                .sortedBy {
                    it.dueDate
                }.take(MAX_NAMED)
                .joinToString(", ") { context.getString(R.string.reminder_item, it.name, it.dueDate.toString()) }
        val more = items.size - MAX_NAMED
        val body = if (more > 0) context.resources.getQuantityString(R.plurals.reminder_more, more, named, more) else named
        return title to body
    }

    fun notify(
        context: Context,
        items: List<UpcomingItem>,
        today: LocalDate,
    ) {
        if (items.isEmpty() || !canNotify(context)) return
        ensureChannel(context)
        val (title, body) = message(context, items, today)
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val public =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(context.getString(R.string.reminder_public_title))
                .build()
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        // Permission was checked by canNotify(); the call is still guarded because the user can revoke it at any time.
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call: nothing to show.
        }
    }

    fun canNotify(context: Context): Boolean =
        (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Inexact daily alarm around 08:00 Manila time; re-armed on every app start and after reboot. */
    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val now = ZonedDateTime.now(ZoneId.of("Asia/Manila"))
        var first = now.toLocalDate().atTime(CHECK_HOUR, 0).atZone(now.zone)
        if (!first.isAfter(now)) first = first.plusDays(1)
        alarms.setInexactRepeating(AlarmManager.RTC, first.toInstant().toEpochMilli(), AlarmManager.INTERVAL_DAY, alarmIntent(context))
    }

    /** Stops the daily check (used by delete-all-data). */
    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context))
        NotificationManagerCompat.from(context).cancelAll()
    }

    private fun alarmIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST,
            Intent(context, ReminderAlarmReceiver::class.java).setAction(ReminderAlarmReceiver.ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.reminder_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.reminder_channel_description)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                },
            )
        }
    }
}

/** Fired by the daily alarm (not exported: only this app's PendingIntent can reach it). */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        val app = context.applicationContext as FinanceApp
        scope.launch {
            try {
                val services = AppServices.create(app.container.database())
                BillReminders.notify(context, BillReminders.check(services), services.ctx.today())
            } catch (
                // A background check must never crash the app; the Bills screen still shows everything (BILL-03).
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                android.util.Log.w("FinanceWallet", "bill reminder check failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "ph.financewallet.app.action.CHECK_BILLS"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/** Re-arms the daily alarm after a reboot (alarms do not survive one). Only acts on the system's BOOT_COMPLETED. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) BillReminders.schedule(context)
    }
}
