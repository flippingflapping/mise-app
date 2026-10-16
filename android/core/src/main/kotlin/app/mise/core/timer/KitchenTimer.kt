package app.mise.core.timer

import kotlinx.serialization.Serializable

/**
 * One running kitchen timer. Pure state + transitions; the Android service owns the clock.
 *
 * Ranged timers ("15–20 min") count to the lower bound, then enter [Phase.CHECK] ("check now"),
 * from which the cook can add a minute, run on to the upper bound, or finish.
 */
@Serializable
data class KitchenTimer(
    val id: String,
    val recipeId: String,
    val recipeTitle: String,
    val stepId: String,
    /** The step title, e.g. "Brown the lamb". */
    val label: String,
    val minSeconds: Int,
    val maxSeconds: Int? = null,
    val phase: Phase,
    val target: Target = Target.MIN,
    /** Wall-clock end of the current countdown (RUNNING only). */
    val endsAtMillis: Long? = null,
    /** Time left when paused (PAUSED only). */
    val remainingMillis: Long? = null,
    /** When the timer last changed phase; for ordering and "how long ago it finished". */
    val changedAtMillis: Long,
) {
    @Serializable
    enum class Phase { RUNNING, PAUSED, CHECK, DONE }

    /** What the current countdown runs to. */
    @Serializable
    enum class Target { MIN, MAX, EXTRA }

    val isRange: Boolean get() = maxSeconds != null && maxSeconds > minSeconds
    val isAlerting: Boolean get() = phase == Phase.CHECK || phase == Phase.DONE

    fun remaining(now: Long): Long = when (phase) {
        Phase.RUNNING -> ((endsAtMillis ?: now) - now).coerceAtLeast(0)
        Phase.PAUSED -> remainingMillis ?: 0
        Phase.CHECK, Phase.DONE -> 0
    }

    /** Advance the clock. Returns the new state and the alert to raise, if any. */
    fun tick(now: Long): Pair<KitchenTimer, Alert?> {
        if (phase != Phase.RUNNING || endsAtMillis == null || now < endsAtMillis) return this to null
        return if (target == Target.MIN && isRange) {
            copy(phase = Phase.CHECK, endsAtMillis = null, changedAtMillis = now) to Alert.CHECK_NOW
        } else {
            copy(phase = Phase.DONE, endsAtMillis = null, changedAtMillis = now) to Alert.DONE
        }
    }

    fun pause(now: Long): KitchenTimer =
        if (phase != Phase.RUNNING) this
        else copy(phase = Phase.PAUSED, remainingMillis = remaining(now), endsAtMillis = null, changedAtMillis = now)

    fun resume(now: Long): KitchenTimer =
        if (phase != Phase.PAUSED) this
        else copy(
            phase = Phase.RUNNING,
            endsAtMillis = now + (remainingMillis ?: 0),
            remainingMillis = null,
            changedAtMillis = now,
        )

    /** "+1 min": works while running (extends) or after an alert (restarts a 1-minute countdown). */
    fun addMinute(now: Long): KitchenTimer = when (phase) {
        Phase.RUNNING -> copy(endsAtMillis = (endsAtMillis ?: now) + 60_000)
        Phase.PAUSED -> copy(remainingMillis = (remainingMillis ?: 0) + 60_000)
        Phase.CHECK, Phase.DONE -> copy(
            phase = Phase.RUNNING, target = Target.EXTRA, endsAtMillis = now + 60_000, changedAtMillis = now,
        )
    }

    /** "To max": from the check-now point, keep going to the upper bound of the range. */
    fun toMax(now: Long): KitchenTimer {
        if (phase != Phase.CHECK || !isRange) return this
        val extra = (maxSeconds!! - minSeconds) * 1000L
        return copy(phase = Phase.RUNNING, target = Target.MAX, endsAtMillis = now + extra, changedAtMillis = now)
    }

    enum class Alert { CHECK_NOW, DONE }

    companion object {
        fun start(
            id: String,
            recipeId: String,
            recipeTitle: String,
            stepId: String,
            label: String,
            spec: TimerSpec,
            now: Long,
        ) = KitchenTimer(
            id = id,
            recipeId = recipeId,
            recipeTitle = recipeTitle,
            stepId = stepId,
            label = label,
            minSeconds = spec.minSeconds,
            maxSeconds = spec.maxSeconds,
            phase = Phase.RUNNING,
            target = Target.MIN,
            endsAtMillis = now + spec.minSeconds * 1000L,
            changedAtMillis = now,
        )
    }
}
