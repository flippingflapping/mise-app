package app.mise.timers

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mise.core.timer.KitchenTimer
import app.mise.core.timer.TimerSpec
import app.mise.di.Clock
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimerStoreTest {
    private var now = 10_000L
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun timersSurviveProcessDeathAndAlertOnce() {
        val store = TimerStore(context, Clock { now })
        val t = store.start("r", "Curry", "s2", "Brown the lamb", TimerSpec(300, 420))
        store.start("r", "Curry", "s5", "Soften the onion", TimerSpec(360, 480))
        assertThat(store.hasActive).isTrue()

        val reborn = TimerStore(context, Clock { now }) // new process: reads the file
        assertThat(reborn.timers.value.map { it.label }).containsExactly("Brown the lamb", "Soften the onion").inOrder()

        now += 300_000
        val alerts = reborn.tick()
        assertThat(alerts.map { it.first.label to it.second }).containsExactly("Brown the lamb" to KitchenTimer.Alert.CHECK_NOW)
        assertThat(reborn.tick()).isEmpty()
        reborn.toMax(t.id)
        assertThat(reborn.get(t.id)!!.remaining(now)).isEqualTo(120_000)
        reborn.dismiss(t.id)
        assertThat(reborn.timers.value).hasSize(1)
    }
}
