package app.mise.timers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.mise.data.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/** Exact-alarm backstop and notification actions. */
@AndroidEntryPoint
class TimerReceiver : BroadcastReceiver() {
    @Inject lateinit var controller: TimerController
    @Inject lateinit var settings: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        when (intent.action) {
            ACTION_ALARM -> {
                val alerts = controller.store.tick()
                if (alerts.isNotEmpty()) {
                    val sound = runBlocking { settings.current().timerSound }
                    alerts.forEach { (t, a) -> TimerNotifications.alert(context, t, a, sound) }
                }
                controller.changed()
            }
            ACTION_ADD_MINUTE -> controller.addMinute(id)
            ACTION_TO_MAX -> controller.toMax(id)
            ACTION_PAUSE -> controller.pause(id)
            ACTION_RESUME -> controller.resume(id)
            ACTION_DISMISS -> controller.dismiss(id)
            ACTION_SILENCE -> Unit // swiped away: keep the timer in the tray, just stop ringing
        }
    }

    companion object {
        const val EXTRA_ID = "timer_id"
        const val ACTION_ALARM = "app.mise.timer.ALARM"
        const val ACTION_ADD_MINUTE = "app.mise.timer.ADD_MINUTE"
        const val ACTION_TO_MAX = "app.mise.timer.TO_MAX"
        const val ACTION_PAUSE = "app.mise.timer.PAUSE"
        const val ACTION_RESUME = "app.mise.timer.RESUME"
        const val ACTION_DISMISS = "app.mise.timer.DISMISS"
        const val ACTION_SILENCE = "app.mise.timer.SILENCE"
    }
}
