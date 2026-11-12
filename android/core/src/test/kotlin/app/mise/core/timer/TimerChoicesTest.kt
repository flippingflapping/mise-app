package app.mise.core.timer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimerChoicesTest {
    @Test fun aRangeOffersBothEndsAndThreeStopsBetween() {
        assertThat(StepTimers.choices(TimerSpec(4 * 60, 6 * 60))).containsExactly(240, 270, 300, 330, 360).inOrder()
    }

    @Test fun shortRangesRoundToFiveSeconds() {
        // 45–60 s: quarter steps are 48.75, 52.5, 56.25 s
        assertThat(StepTimers.choices(TimerSpec(45, 60))).containsExactly(45, 50, 55, 60).inOrder()
    }

    @Test fun longRangesRoundToWholeMinutes() {
        assertThat(StepTimers.choices(TimerSpec(60 * 60, 90 * 60))).containsExactly(3600, 4080, 4500, 4980, 5400).inOrder()
    }

    @Test fun aFixedTimerHasOneLength() {
        assertThat(StepTimers.choices(TimerSpec(300, null))).containsExactly(300)
        assertThat(StepTimers.choices(TimerSpec(300, 300))).containsExactly(300)
    }

    @Test fun aPickedLengthStillRunsOnToTheEnd() {
        val spec = TimerSpec(240, 360)
        assertThat(StepTimers.chosen(spec, 300)).isEqualTo(TimerSpec(300, 360))
        assertThat(StepTimers.chosen(spec, 240)).isEqualTo(spec) // the default is today's behaviour
        assertThat(StepTimers.chosen(spec, 360)).isEqualTo(TimerSpec(360, null)) // the end is a plain countdown
    }
}
