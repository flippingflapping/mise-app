package app.mise.timers

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import app.mise.core.timer.KitchenTimer
import app.mise.data.settings.SettingsRepository
import app.mise.di.Clock
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Keeps kitchen timers alive while the app is backgrounded or the screen is locked: owns the
 * ongoing notification and raises alerts on time. Stops itself when no timer is running or paused.
 * Exact alarms ([TimerController]) back it up if the process is killed or the CPU sleeps.
 */
@AndroidEntryPoint
class TimerService : Service() {
    @Inject lateinit var store: TimerStore
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var clock: Clock

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TimerNotifications.createChannels(this)
        val notification = TimerNotifications.ongoing(this, store.timers.value, clock.now())
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, TimerNotifications.ONGOING_ID, notification, type)
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_STICKY
    }

    private suspend fun run() {
        while (true) {
            val now = clock.now()
            val sound = settings.current().timerSound
            for ((timer, alert) in store.tick(now)) TimerNotifications.alert(this, timer, alert, sound)
            val timers = store.timers.value
            if (!store.hasActive) break
            if (TimerNotifications.canPost(this)) {
                try {
                    NotificationManagerCompat.from(this)
                        .notify(TimerNotifications.ONGOING_ID, TimerNotifications.ongoing(this, timers, now))
                } catch (_: SecurityException) {
                }
            }
            val nextEnd = timers.filter { it.phase == KitchenTimer.Phase.RUNNING }.mapNotNull { it.endsAtMillis }.minOrNull()
            val wait = ((nextEnd ?: (now + 60_000)) - now).coerceIn(50, 60_000)
            // Wake at the next deadline, or as soon as the timers change.
            withTimeoutOrNull(wait) { store.timers.drop(1).first() }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
