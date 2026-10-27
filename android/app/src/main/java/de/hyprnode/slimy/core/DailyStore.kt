package de.hyprnode.slimy.core

import android.content.Context
import android.content.SharedPreferences
import de.hyprnode.slimy.model.DailyPlan
import de.hyprnode.slimy.model.DailyPlanner
import de.hyprnode.slimy.model.ProgressCount
import de.hyprnode.slimy.model.SlimyJson
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.model.claimQuest
import de.hyprnode.slimy.model.questProgress
import de.hyprnode.slimy.model.startQuest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * Today's plan: a new game, a quest and a short story. DailyPlanner makes it
 * on the phone from the date, so everyone gets the same game on the same day
 * and nothing has to be fetched. A plan never changes during its day.
 */
class DailyStore internal constructor(
    private val prefs: SharedPreferences?,
    private val store: GameStore,
) {
    constructor(context: Context, store: GameStore) : this(
        context.applicationContext.getSharedPreferences("slimy", Context.MODE_PRIVATE),
        store,
    )

    private val _plan = MutableStateFlow(initialPlan())

    /** Today's plan (the one made when the day began). */
    val plan: StateFlow<DailyPlan> = _plan.asStateFlow()

    private fun initialPlan(): DailyPlan {
        val today = DailyPlanner.dayKey(Instant.now())
        // A plan saved earlier today stays, even if an older app version made it differently.
        val saved = prefs?.getString(key, null)?.let { json ->
            runCatching { SlimyJson.storage.decodeFromString(DailyPlan.serializer(), json) }.getOrNull()
        }
        return if (saved != null && saved.day == today) saved.sanitized() else DailyPlanner.plan(today)
    }

    val progress: ProgressCount get() = store.state.value.questProgress(_plan.value)
    val questDone: Boolean get() = progress.value >= progress.target

    val questClaimed: Boolean
        get() {
            val quest = store.state.value.progress.quest
            return quest?.claimed == true && quest.day == _plan.value.day
        }

    val playedToday: Boolean
        get() {
            val quest = store.state.value.progress.quest
            return (quest?.best ?: 0) > 0 && quest?.day == _plan.value.day
        }

    /** Rolls over to the new day's plan and starts counting its quest. */
    fun refresh() {
        val today = DailyPlanner.dayKey(Instant.now())
        if (_plan.value.day != today) {
            _plan.value = DailyPlanner.plan(today)
            save()
        }
        val current = _plan.value
        store.update { it.startQuest(current) }
    }

    /** Pays the quest. `apply` already shows its line as a toast. */
    fun claimQuest() {
        val plan = _plan.value
        store.apply(sound = Sound.coin) { it.claimQuest(plan, Instant.now()) }
    }

    private fun save() {
        val json = runCatching { SlimyJson.storage.encodeToString(DailyPlan.serializer(), _plan.value) }.getOrNull() ?: return
        prefs?.edit()?.putString(key, json)?.apply()
    }

    private companion object {
        const val key = "slimy.daily.plan"
    }
}
