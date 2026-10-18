package app.mise

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.mise.timers.TimerController
import app.mise.timers.TimerNotifications
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltAndroidApp
class MiseApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var timers: TimerController
    @Inject lateinit var feastReminders: app.mise.plans.FeastReminders
    @Inject @app.mise.di.ApplicationScope lateinit var appScope: kotlinx.coroutines.CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        TimerNotifications.createChannels(this)
        // After process death: re-arm alarms and restart the service for timers still running.
        timers.changed()
        appScope.launch { feastReminders.rescheduleAll() }
    }
}
