package de.hyprnode.slimy.platform

import android.content.Context
import androidx.activity.ComponentActivity
import de.hyprnode.slimy.model.GameState
import de.hyprnode.slimy.model.Need
import de.hyprnode.slimy.model.NotificationPrefs
import de.hyprnode.slimy.model.SlimyCalendar
import de.hyprnode.slimy.model.addingTimeInterval
import java.time.Instant

/**
 * Notifications and everything else that interrupts: few and useful. Every
 * category can be switched off, nothing arrives in quiet hours, and at most
 * [dailyLimit] interruptions reach you per day. Slime-notification alerts ask
 * [mayInterrupt] too, so they share the same rules and the same budget. The
 * Android port of App/Core/Notifier.swift (channels, WorkManager scheduling).
 *
 * [install] is called once from `SlimyApplication.onCreate`.
 */
// Implemented by the online/platform worker.
object Notifier {
    /** One notification channel each. */
    enum class Category {
        needs, daily, friends, gifts, world;

        companion object {
            val allCases: List<Category> get() = entries
        }
    }

    const val dailyLimit = 4

    @Volatile
    private var appContext: Context? = null

    /** Keeps the app context and sets up the notification channels. */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Asks once, at a moment where it makes sense (after hatching, from a
     * button), and notes that it asked (`settings.notifications.asked`).
     * Needs an [activity] to show the system prompt on Android 13 and later.
     * Returns whether notifications are allowed.
     */
    suspend fun requestPermission(activity: ComponentActivity? = null): Boolean = false

    /** Whether Slimy may post notifications. */
    suspend fun isAuthorized(): Boolean = false

    /**
     * Replaces every scheduled notification with what fits the state now.
     * Called after every change, so nothing stale is ever left behind.
     */
    fun reschedule(state: GameState, now: Instant = Instant.now(), calendar: SlimyCalendar = SlimyCalendar.current) {}

    /** Something happened right now (a gift arrived, a friend wants to play). */
    fun post(
        category: Category,
        title: String,
        body: String,
        state: GameState,
        now: Instant = Instant.now(),
        calendar: SlimyCalendar = SlimyCalendar.current,
    ) {}

    /**
     * Whether something may interrupt right now: its category is switched on
     * (null for things with a switch of their own, like phone reactions), it
     * isn't quiet hours, and today's budget isn't used up. A yes uses one
     * interruption from the budget.
     */
    fun mayInterrupt(
        category: Category?,
        state: GameState,
        now: Instant = Instant.now(),
        calendar: SlimyCalendar = SlimyCalendar.current,
    ): Boolean = false

    fun isOn(category: Category, prefs: NotificationPrefs): Boolean = when (category) {
        Category.needs -> prefs.needs
        Category.daily -> prefs.daily
        Category.friends -> prefs.friends
        Category.gifts -> prefs.gifts
        Category.world -> prefs.world
    }

    // MARK: Helpers

    /** When the most urgent need will fall below 25, if within 36 hours. */
    fun nextNeed(state: GameState, now: Instant): Pair<Need, Instant>? {
        if (state.slime.isAsleep) return null
        var best: Pair<Need, Double>? = null
        for (need in Need.allCases) {
            if (need == Need.energy) continue
            val rate = need.baseDecay(asleep = false) * state.slime.personality.decayFactor(need)
            val hours = (state.slime.needs[need] - 25) / rate
            if (!(hours > 0.25 && hours < 36)) continue
            if (best == null || hours < best.second) best = need to hours
        }
        val found = best ?: return null
        return found.first to now.addingTimeInterval(found.second * 3600)
    }

    /** The date, or the end of quiet hours if it falls into them. */
    fun outsideQuietHours(date: Instant, prefs: NotificationPrefs, calendar: SlimyCalendar): Instant {
        if (!prefs.isQuiet(calendar.hour(date))) return date
        val local = calendar.local(date)
        var next = local.toLocalDate().atTime(prefs.quietUntil.coerceIn(0, 23), 0).atZone(calendar.zone)
        if (!next.isAfter(local)) next = next.plusDays(1)
        return next.toInstant()
    }
}
