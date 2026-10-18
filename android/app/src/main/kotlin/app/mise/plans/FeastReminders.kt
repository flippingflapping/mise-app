package app.mise.plans

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.app.NotificationManagerCompat
import app.mise.MainActivity
import app.mise.R
import app.mise.data.repo.PlansRepository
import app.mise.di.Clock
import app.mise.timers.TimerNotifications
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Time to: Brown the lamb (Lamb curry)" notifications at the start of each feast task.
 * Alarms are re-armed whenever the plan changes (task ticked off, serve time moved) and on app start.
 */
@Singleton
class FeastReminders @Inject constructor(
    @ApplicationContext private val context: Context,
    private val plans: PlansRepository,
    private val clock: Clock,
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val prefs = context.getSharedPreferences("feast_reminders", Context.MODE_PRIVATE)

    fun createChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Feast reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When it's time to start each part of a planned meal"
            },
        )
    }

    /** Cancel and re-create this feast's reminders from its current plan. */
    suspend fun reschedule(feastId: Long) {
        cancel(feastId)
        val (feast, plan) = plans.planNow(feastId) ?: return
        if (!feast.remind) return
        val now = clock.now()
        val keys = mutableSetOf<String>()
        for (task in plan.tasks.filter { it.startMillis > now }) {
            val code = "$feastId|${task.id}"
            val intent = Intent(context, FeastReminderReceiver::class.java)
                .putExtra(EXTRA_FEAST, feastId)
                .putExtra(EXTRA_TITLE, task.title)
                .putExtra(EXTRA_RECIPE, task.recipeTitle)
                .putExtra(EXTRA_AT, task.startMillis)
            val pi = PendingIntent.getBroadcast(context, code.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
            try {
                if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.startMillis, pi)
                else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.startMillis, pi)
            } catch (_: SecurityException) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.startMillis, pi)
            }
            keys += code
        }
        prefs.edit { putStringSet(feastId.toString(), keys) }
    }

    fun cancel(feastId: Long) {
        for (code in prefs.getStringSet(feastId.toString(), emptySet()).orEmpty()) {
            PendingIntent.getBroadcast(
                context, code.hashCode(), Intent(context, FeastReminderReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )?.let { alarms.cancel(it); it.cancel() }
        }
        prefs.edit { remove(feastId.toString()) }
    }

    suspend fun rescheduleAll() {
        createChannel()
        plans.feastsWithReminders().forEach { reschedule(it.id) }
    }

    companion object {
        const val CHANNEL = "feast_reminders"
        const val EXTRA_FEAST = "feast_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_RECIPE = "recipe"
        const val EXTRA_AT = "at"
    }
}

@AndroidEntryPoint
class FeastReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!TimerNotifications.canPost(context)) return
        val feastId = intent.getLongExtra(FeastReminders.EXTRA_FEAST, -1)
        val title = intent.getStringExtra(FeastReminders.EXTRA_TITLE) ?: return
        val recipe = intent.getStringExtra(FeastReminders.EXTRA_RECIPE).orEmpty()
        val at = intent.getLongExtra(FeastReminders.EXTRA_AT, System.currentTimeMillis())
        val open = PendingIntent.getActivity(
            context, feastId.toInt(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_FEAST, feastId)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, FeastReminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle("Time to: $title")
            .setContentText("$recipe · ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))}")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(("$feastId|$title|$at").hashCode(), n)
        } catch (_: SecurityException) {
        }
    }
}
