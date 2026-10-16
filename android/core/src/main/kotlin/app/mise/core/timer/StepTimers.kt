package app.mise.core.timer

import app.mise.core.model.Recipe
import app.mise.core.model.Step

/** What a step's timer button offers. */
data class TimerSpec(val minSeconds: Int, val maxSeconds: Int?) {
    val isRange: Boolean get() = maxSeconds != null && maxSeconds > minSeconds
}

/** How a step relates to timing in cooking mode. */
sealed interface StepTiming {
    data class Countdown(val spec: TimerSpec, val label: String) : StepTiming
    /** Overnight or multi-hour waits: no countdown; point at the advance-prep banner. */
    data class AdvancePrep(val notes: List<String>) : StepTiming
    data object None : StepTiming
}

object StepTimers {
    /** Waits longer than this are planning, not timing. */
    const val LONG_WAIT_SECONDS = 3 * 60 * 60

    private val LONG_WAIT_WORDS = Regex(
        """\b(overnight|the day before|the night before|ahead of time|for at least \d+ hours?|for \d+(–|-| to )?\d* hours)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun timing(step: Step, recipe: Recipe): StepTiming {
        val secs = step.timerSeconds
        val isLong = (secs != null && secs > LONG_WAIT_SECONDS) ||
            LONG_WAIT_WORDS.containsMatchIn(step.content) || LONG_WAIT_WORDS.containsMatchIn(step.title)
        return when {
            isLong -> StepTiming.AdvancePrep(recipe.advancePrep)
            secs != null && secs > 0 -> StepTiming.Countdown(
                TimerSpec(secs, step.timerMaxSeconds?.takeIf { it > secs }),
                step.title,
            )
            else -> StepTiming.None
        }
    }

    /** "5–7 min", "1 min", "45 s", "1 h 30 min", "90 s–2 min". */
    fun formatRange(spec: TimerSpec): String {
        val max = spec.maxSeconds?.takeIf { spec.isRange } ?: return formatDuration(spec.minSeconds)
        val bothMinutes = spec.minSeconds % 60 == 0 && max % 60 == 0 && max < 3600
        return if (bothMinutes) "${spec.minSeconds / 60}–${max / 60} min"
        else "${formatDuration(spec.minSeconds)}–${formatDuration(max)}"
    }

    fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 && m > 0 -> "$h h $m min"
            h > 0 -> "$h h"
            m > 0 && s > 0 -> "$m min $s s"
            m > 0 -> "$m min"
            else -> "$s s"
        }
    }

    /** Countdown clock text: "4:05", "1:02:09". */
    fun clock(millis: Long): String {
        val total = ((millis + 999) / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
