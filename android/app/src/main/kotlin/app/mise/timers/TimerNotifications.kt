package app.mise.timers

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.mise.MainActivity
import app.mise.R
import app.mise.core.timer.KitchenTimer
import app.mise.core.timer.StepTimers

object TimerNotifications {
    const val CHANNEL_RUNNING = "timers_running"
    const val CHANNEL_ALARM = "timers_alarm"
    const val CHANNEL_ALARM_SILENT = "timers_alarm_silent"
    const val ONGOING_ID = 1
    private val VIBRATION = longArrayOf(0, 600, 300, 600, 300, 600)

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val running = NotificationChannel(
            CHANNEL_RUNNING, context.getString(R.string.channel_running), NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.channel_running_desc); setShowBadge(false) }
        val alarm = NotificationChannel(
            CHANNEL_ALARM, context.getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_alarm_desc)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            enableVibration(true)
            vibrationPattern = VIBRATION
            setBypassDnd(false)
        }
        val silent = NotificationChannel(
            CHANNEL_ALARM_SILENT, context.getString(R.string.channel_alarm_silent), NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_alarm_silent_desc)
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = VIBRATION
        }
        nm.createNotificationChannels(listOf(running, alarm, silent))
    }

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun openCooking(context: Context, recipeId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_COOK_RECIPE, recipeId)
        return PendingIntent.getActivity(
            context, recipeId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun action(context: Context, timerId: String, action: String, label: String): NotificationCompat.Action {
        val pi = PendingIntent.getBroadcast(
            context,
            (timerId + action).hashCode(),
            Intent(context, TimerReceiver::class.java).setAction(action).putExtra(TimerReceiver.EXTRA_ID, timerId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(0, label, pi).build()
    }

    fun alertId(timerId: String) = 1000 + (timerId.hashCode() and 0x7fff)

    /** The ongoing notification the foreground service shows while timers run. */
    fun ongoing(context: Context, timers: List<KitchenTimer>, now: Long): Notification {
        val active = timers.filter { it.phase == KitchenTimer.Phase.RUNNING || it.phase == KitchenTimer.Phase.PAUSED }
        val soonest = active.filter { it.phase == KitchenTimer.Phase.RUNNING }.minByOrNull { it.endsAtMillis ?: Long.MAX_VALUE }
        val title = when (active.size) {
            0 -> "Timers"
            1 -> active[0].label
            else -> "${active.size} timers running"
        }
        val lines = active.map { t ->
            val left = StepTimers.clock(t.remaining(now))
            "${t.label} — " + if (t.phase == KitchenTimer.Phase.PAUSED) "paused at $left" else "$left left"
        }
        val b = NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull() ?: "")
            .setStyle(NotificationCompat.InboxStyle().also { s -> lines.forEach(s::addLine) })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openCooking(context, (soonest ?: active.firstOrNull())?.recipeId))
        if (soonest?.endsAtMillis != null) {
            b.setWhen(soonest.endsAtMillis!!).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        }
        if (active.size == 1) {
            val t = active[0]
            if (t.phase == KitchenTimer.Phase.RUNNING) b.addAction(action(context, t.id, TimerReceiver.ACTION_PAUSE, "Pause"))
            else b.addAction(action(context, t.id, TimerReceiver.ACTION_RESUME, "Resume"))
            b.addAction(action(context, t.id, TimerReceiver.ACTION_ADD_MINUTE, "+1 min"))
            b.addAction(action(context, t.id, TimerReceiver.ACTION_DISMISS, "Cancel"))
        }
        return b.build()
    }

    /** Ring: "check now" at the lower bound of a range, or "done". */
    fun alert(context: Context, timer: KitchenTimer, alert: KitchenTimer.Alert, sound: Boolean) {
        if (!canPost(context)) return
        val check = alert == KitchenTimer.Alert.CHECK_NOW
        val title = if (check) "Check now: ${timer.label}" else "Done: ${timer.label}"
        val text = if (check) {
            val extra = (timer.maxSeconds ?: timer.minSeconds) - timer.minSeconds
            "${timer.recipeTitle} — up to ${StepTimers.formatDuration(extra)} more if it needs it"
        } else timer.recipeTitle
        val b = NotificationCompat.Builder(context, if (sound) CHANNEL_ALARM else CHANNEL_ALARM_SILENT)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setContentIntent(openCooking(context, timer.recipeId))
            .setDeleteIntent(
                PendingIntent.getBroadcast(
                    context, (timer.id + "del").hashCode(),
                    Intent(context, TimerReceiver::class.java).setAction(TimerReceiver.ACTION_SILENCE)
                        .putExtra(TimerReceiver.EXTRA_ID, timer.id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(action(context, timer.id, TimerReceiver.ACTION_ADD_MINUTE, "+1 min"))
        if (check) b.addAction(action(context, timer.id, TimerReceiver.ACTION_TO_MAX, "To max"))
        b.addAction(action(context, timer.id, TimerReceiver.ACTION_DISMISS, "Done"))
        val n = b.build().apply { flags = flags or Notification.FLAG_INSISTENT } // keep ringing until handled
        try {
            NotificationManagerCompat.from(context).notify(alertId(timer.id), n)
        } catch (_: SecurityException) {
            // Notification permission revoked between the check and the call.
        }
    }

    fun cancelAlert(context: Context, timerId: String) =
        NotificationManagerCompat.from(context).cancel(alertId(timerId))
}
