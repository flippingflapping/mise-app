package app.mise.timers

import android.content.Context
import app.mise.core.timer.KitchenTimer
import app.mise.core.timer.TimerSpec
import app.mise.di.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * All kitchen timers, persisted to a file on every change so the tray survives process death.
 * Pure state transitions live in [KitchenTimer]; side effects (service, alarms) in [TimerController].
 */
@Singleton
class TimerStore @Inject constructor(
    @ApplicationContext context: Context,
    private val clock: Clock,
) {
    private val file = File(context.filesDir, "timers.json")
    private val serializer = ListSerializer(KitchenTimer.serializer())
    private val json = Json { ignoreUnknownKeys = true }
    private val _timers = MutableStateFlow(load())
    val timers: StateFlow<List<KitchenTimer>> = _timers.asStateFlow()

    private fun load(): List<KitchenTimer> =
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())

    private fun save(list: List<KitchenTimer>) {
        runCatching {
            val tmp = File(file.parentFile, "timers.json.tmp")
            tmp.writeText(json.encodeToString(serializer, list))
            tmp.renameTo(file)
        }
    }

    @Synchronized
    private fun update(transform: (List<KitchenTimer>) -> List<KitchenTimer>): List<KitchenTimer> {
        val next = transform(_timers.value)
        if (next != _timers.value) {
            _timers.value = next
            save(next)
        }
        return next
    }

    private fun updateOne(id: String, f: (KitchenTimer, Long) -> KitchenTimer) {
        val now = clock.now()
        update { list -> list.map { if (it.id == id) f(it, now) else it } }
    }

    fun start(recipeId: String, recipeTitle: String, stepId: String, label: String, spec: TimerSpec): KitchenTimer {
        val t = KitchenTimer.start(UUID.randomUUID().toString(), recipeId, recipeTitle, stepId, label, spec, clock.now())
        update { it + t }
        return t
    }

    fun pause(id: String) = updateOne(id) { t, now -> t.pause(now) }
    fun resume(id: String) = updateOne(id) { t, now -> t.resume(now) }
    fun addMinute(id: String) = updateOne(id) { t, now -> t.addMinute(now) }
    fun toMax(id: String) = updateOne(id) { t, now -> t.toMax(now) }
    fun dismiss(id: String) = update { list -> list.filterNot { it.id == id } }

    fun get(id: String): KitchenTimer? = _timers.value.firstOrNull { it.id == id }

    /** Advance every timer; returns the alerts that just became due (each fires exactly once). */
    @Synchronized
    fun tick(now: Long = clock.now()): List<Pair<KitchenTimer, KitchenTimer.Alert>> {
        val alerts = mutableListOf<Pair<KitchenTimer, KitchenTimer.Alert>>()
        update { list ->
            list.map { t ->
                val (next, alert) = t.tick(now)
                if (alert != null) alerts += next to alert
                next
            }
        }
        return alerts
    }

    val hasActive: Boolean
        get() = _timers.value.any { it.phase == KitchenTimer.Phase.RUNNING || it.phase == KitchenTimer.Phase.PAUSED }
}
