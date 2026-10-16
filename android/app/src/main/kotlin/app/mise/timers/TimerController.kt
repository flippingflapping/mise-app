package app.mise.timers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import app.mise.core.model.Recipe
import app.mise.core.model.Step
import app.mise.core.timer.KitchenTimer
import app.mise.core.timer.TimerSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place UI and receivers change timers: updates [TimerStore], then makes sure the
 * foreground service is running and the backstop alarms match the running timers.
 */
@Singleton
class TimerController @Inject constructor(
    @ApplicationContext private val context: Context,
    val store: TimerStore,
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun start(recipe: Recipe, step: Step, spec: TimerSpec): KitchenTimer {
        val t = store.start(recipe.id, recipe.title, step.id, step.title, spec)
        changed()
        return t
    }

    fun pause(id: String) = apply(id) { store.pause(it) }
    fun resume(id: String) = apply(id) { store.resume(it) }
    fun addMinute(id: String) = apply(id) { store.addMinute(it) }
    fun toMax(id: String) = apply(id) { store.toMax(it) }
    fun dismiss(id: String) = apply(id) { store.dismiss(it) }

    private inline fun apply(id: String, f: (String) -> Unit) {
        val before = store.get(id)
        f(id)
        TimerNotifications.cancelAlert(context, id)
        if (before != null) syncAlarm(before.id)
        changed()
    }

    /** Call after any change, and on app start (restores alarms and service after process death). */
    fun changed() {
        store.timers.value.forEach { syncAlarm(it.id) }
        if (store.hasActive) startService()
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, TimerService::class.java))
        } catch (e: IllegalStateException) {
            // Background start not allowed right now (e.g. ForegroundServiceStartNotAllowedException);
            // the exact alarms still ring on time, and the service starts next time the app is open.
        }
    }

    private fun pendingAlarm(id: String, create: Boolean): PendingIntent? = PendingIntent.getBroadcast(
        context,
        id.hashCode(),
        Intent(context, TimerReceiver::class.java).setAction(TimerReceiver.ACTION_ALARM).putExtra(TimerReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE,
    )

    private fun syncAlarm(id: String) {
        val t = store.get(id)
        val at = t?.takeIf { it.phase == KitchenTimer.Phase.RUNNING }?.endsAtMillis
        if (at == null) {
            pendingAlarm(id, create = false)?.let { alarms.cancel(it); it.cancel() }
            return
        }
        val pi = pendingAlarm(id, create = true) ?: return
        val exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        try {
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    val exactAlarmsAllowed: Boolean
        get() = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
}
