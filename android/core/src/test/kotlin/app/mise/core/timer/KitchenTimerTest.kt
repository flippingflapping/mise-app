package app.mise.core.timer

import app.mise.core.Fixtures
import app.mise.core.model.Step
import app.mise.core.table
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class KitchenTimerTest {
    private fun timer(min: Int, max: Int?, now: Long = 0) =
        KitchenTimer.start("t1", "r", "Recipe", "s2", "Brown the lamb", TimerSpec(min, max), now)

    @Test fun simpleTimerFinishes() {
        val t = timer(60, null)
        assertThat(t.remaining(30_000)).isEqualTo(30_000)
        assertThat(t.tick(59_999).second).isNull()
        val (done, alert) = t.tick(60_000)
        assertThat(alert).isEqualTo(KitchenTimer.Alert.DONE)
        assertThat(done.phase).isEqualTo(KitchenTimer.Phase.DONE)
        assertThat(done.tick(70_000).second).isNull() // alerts once
    }

    @Test fun rangedTimerChecksAtMinThenRunsToMax() {
        val t = timer(300, 420)
        val (check, a1) = t.tick(300_000)
        assertThat(a1).isEqualTo(KitchenTimer.Alert.CHECK_NOW)
        assertThat(check.phase).isEqualTo(KitchenTimer.Phase.CHECK)
        val toMax = check.toMax(310_000)
        assertThat(toMax.remaining(310_000)).isEqualTo(120_000)
        val (done, a2) = toMax.tick(430_000)
        assertThat(a2).isEqualTo(KitchenTimer.Alert.DONE)
        assertThat(done.phase).isEqualTo(KitchenTimer.Phase.DONE)
    }

    @Test fun plusOneMinute() {
        val check = timer(300, 420).tick(300_000).first
        val extra = check.addMinute(305_000)
        assertThat(extra.phase).isEqualTo(KitchenTimer.Phase.RUNNING)
        assertThat(extra.remaining(305_000)).isEqualTo(60_000)
        assertThat(extra.tick(365_000).second).isEqualTo(KitchenTimer.Alert.DONE)
        // while running it just extends
        assertThat(timer(60, null).addMinute(0).remaining(0)).isEqualTo(120_000)
        // after done it restarts a minute
        assertThat(timer(60, null).tick(60_000).first.addMinute(61_000).remaining(61_000)).isEqualTo(60_000)
    }

    @Test fun pauseAndResume() {
        val paused = timer(60, null).pause(20_000)
        assertThat(paused.phase).isEqualTo(KitchenTimer.Phase.PAUSED)
        assertThat(paused.remaining(1_000_000)).isEqualTo(40_000)
        assertThat(paused.tick(1_000_000).second).isNull()
        val resumed = paused.resume(100_000)
        assertThat(resumed.remaining(100_000)).isEqualTo(40_000)
        assertThat(resumed.tick(140_000).second).isEqualTo(KitchenTimer.Alert.DONE)
    }

    @Test fun toMaxOnlyFromCheck() {
        val t = timer(60, null)
        assertThat(t.toMax(0)).isEqualTo(t)
    }

    @Test fun serializesForPersistence() {
        val t = timer(300, 420, now = 1234)
        val json = Json.encodeToString(KitchenTimer.serializer(), t)
        assertThat(Json.decodeFromString(KitchenTimer.serializer(), json)).isEqualTo(t)
    }

    @Test fun stepTimingPolicy() {
        val r = Fixtures.lambCurry
        assertThat(StepTimers.timing(r.steps[0], r)).isEqualTo(StepTiming.AdvancePrep(listOf("Marinate the lamb overnight")))
        assertThat(StepTimers.timing(r.steps[1], r)).isEqualTo(StepTiming.Countdown(TimerSpec(300, 420), "Brown the lamb"))
        assertThat(StepTimers.timing(r.steps[2], r)).isEqualTo(StepTiming.None)
        val long = Step(id = "x", title = "Rest", content = "Leave to rest.", timerSeconds = 5 * 3600)
        assertThat(StepTimers.timing(long, r)).isInstanceOf(StepTiming.AdvancePrep::class.java)
        val bad = Step(id = "y", title = "Odd", content = "Cook.", timerSeconds = 60, timerMaxSeconds = 30)
        assertThat(StepTimers.timing(bad, r)).isEqualTo(StepTiming.Countdown(TimerSpec(60, null), "Odd"))
    }

    @Test fun formatting() = table(
        listOf(
            TimerSpec(300, 420) to "5–7 min",
            TimerSpec(60, null) to "1 min",
            TimerSpec(45, null) to "45 s",
            TimerSpec(90, 120) to "1 min 30 s–2 min",
            TimerSpec(900, 1200) to "15–20 min",
            TimerSpec(5400, null) to "1 h 30 min",
            TimerSpec(3600, 5400) to "1 h–1 h 30 min",
        ),
        { it.first.toString() },
    ) { (spec, exp) -> exp to StepTimers.formatRange(spec) }

    @Test fun clock() {
        assertThat(StepTimers.clock(245_000)).isEqualTo("4:05")
        assertThat(StepTimers.clock(244_001)).isEqualTo("4:05")
        assertThat(StepTimers.clock(3_729_000)).isEqualTo("1:02:09")
        assertThat(StepTimers.clock(0)).isEqualTo("0:00")
    }
}
