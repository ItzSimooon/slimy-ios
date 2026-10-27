package de.hyprnode.slimy.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.hyprnode.slimy.MainActivity
import de.hyprnode.slimy.R
import de.hyprnode.slimy.SlimyApplication
import de.hyprnode.slimy.model.Brand
import de.hyprnode.slimy.model.Dialogue
import de.hyprnode.slimy.model.GameState
import de.hyprnode.slimy.model.LineContext
import de.hyprnode.slimy.model.Need
import de.hyprnode.slimy.model.NotificationPrefs
import de.hyprnode.slimy.model.SlimyCalendar
import de.hyprnode.slimy.model.addingTimeInterval
import de.hyprnode.slimy.model.streakInfo
import de.hyprnode.slimy.model.timeIntervalSince
import de.hyprnode.slimy.ui.EXTRA_OPEN
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max

/**
 * Notifications and everything else that interrupts: few and useful. Every
 * category can be switched off, nothing arrives in quiet hours, and at most
 * [dailyLimit] interruptions reach you per day. Slime-notification alerts ask
 * [mayInterrupt] too, so they share the same rules and the same budget. The
 * Android port of App/Core/Notifier.swift: one notification channel per
 * category plus the slime notification's own two, reminders scheduled as
 * WorkManager one-time work.
 *
 * [install] is called once from `SlimyApplication.onCreate`.
 */
object Notifier {
    /** One notification channel each. */
    enum class Category {
        needs, daily, friends, gifts, world;

        /** The channel's id. */
        val channel: String get() = name

        /** The channel's name in the system settings. */
        val title: String
            get() = when (this) {
                needs -> "Needs"
                daily -> "Daily gift and challenges"
                friends -> "Friends"
                gifts -> "Gifts"
                world -> "Thinking of you"
            }

        val detail: String
            get() = when (this) {
                needs -> "When your slime gets hungry, bored, sticky or lonely."
                daily -> "A morning note when today's gift and challenges are ready."
                friends -> "Friends saying hi, hugs, challenges and caring together."
                gifts -> "Gifts from friends."
                world -> "Now and then, your slime just thinks of you."
            }

        /** The place in the app a notification of this kind opens. */
        val place: String
            get() = when (this) {
                friends, gifts -> "friends"
                needs, daily, world -> "home"
            }

        companion object {
            val allCases: List<Category> get() = entries
        }
    }

    const val dailyLimit = 4

    /** The slime notification itself: silent, ongoing. */
    const val slimeChannel = "slime"

    /** The slime notification popping up with news (a heads-up with a sound). */
    const val alertChannel = "slime.alerts"

    /** WorkManager names of the scheduled reminders. */
    private val scheduledIDs = listOf("slimy.need", "slimy.daily", "slimy.thinking")

    private const val tag = "SlimyNotifier"

    @Volatile
    private var appContext: Context? = null

    /** What is scheduled right now, so an unchanged plan isn't sent to WorkManager again on every tap. */
    private val scheduled = mutableMapOf<String, Reminder>()
    private var primed = false

    private val nextID = AtomicInteger(1000)

    /** Keeps the app context and sets up the notification channels. */
    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        val channels = mutableListOf<NotificationChannel>()
        channels += NotificationChannel(slimeChannel, "Slime notification", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Your slime in the notifications and on the lock screen, with buttons to look after it."
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        channels += NotificationChannel(alertChannel, "Slime pops up", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Your slime pops up with a sound when it has news: a low battery, good morning, a friend."
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        for (category in Category.allCases) {
            val importance = when (category) {
                Category.friends, Category.gifts -> NotificationManager.IMPORTANCE_HIGH
                else -> NotificationManager.IMPORTANCE_DEFAULT
            }
            channels += NotificationChannel(category.channel, category.title, importance).apply {
                description = category.detail
            }
        }
        manager.createNotificationChannels(channels)
    }

    /**
     * Asks once, at a moment where it makes sense (after hatching, from a
     * button), and notes that it asked (`settings.notifications.asked`).
     * Needs an [activity] to show the system prompt on Android 13 and later.
     * Returns whether notifications are allowed.
     */
    suspend fun requestPermission(activity: ComponentActivity? = null): Boolean {
        val context = appContext ?: activity?.applicationContext ?: return false
        if (Build.VERSION.SDK_INT >= 33 && activity != null && !hasPostPermission(context)) {
            askForPostPermission(activity)
        }
        val app = context as? SlimyApplication
        app?.store?.update { it.settings.notifications.asked = true }
        val granted = isAuthorized()
        if (granted && app != null) {
            reschedule(app.store.state.value)
            // The slime notification couldn't show without the permission; now it can.
            if (app.store.state.value.hatched) app.island.ensureRunning()
        }
        return granted
    }

    /** Whether Slimy may post notifications. */
    suspend fun isAuthorized(): Boolean = authorized()

    /** [isAuthorized] for code that can't suspend. */
    fun authorized(): Boolean {
        val context = appContext ?: return false
        if (!hasPostPermission(context)) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Whether one channel (a [Category]'s, [slimeChannel] or [alertChannel]) is switched on. */
    fun isChannelOn(channel: String): Boolean {
        val context = appContext ?: return false
        val c = NotificationManagerCompat.from(context).getNotificationChannel(channel) ?: return true
        return c.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** The system screen with Slimy's notification settings (or one channel's). */
    fun settingsIntent(context: Context, channel: String? = null): Intent {
        val intent = if (channel != null) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, channel)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Replaces every scheduled notification with what fits the state now.
     * Called after every change, so nothing stale is ever left behind.
     */
    fun reschedule(state: GameState, now: Instant = Instant.now(), calendar: SlimyCalendar = SlimyCalendar.current) {
        val context = appContext ?: return
        val plan = plan(state, now, calendar).associateBy { it.id }
        val work = try {
            WorkManager.getInstance(context)
        } catch (e: IllegalStateException) {
            Log.w(tag, "WorkManager isn't ready", e)
            return
        }
        synchronized(scheduled) {
            // The first plan after launch also clears what an earlier run left behind.
            val first = !primed
            primed = true
            for (id in scheduledIDs) {
                val wanted = plan[id]
                val old = scheduled[id]
                if (wanted == null) {
                    if (old != null || first) work.cancelUniqueWork(id)
                    scheduled.remove(id)
                    continue
                }
                // The same reminder at about the same time is left alone.
                if (old != null && old.key == wanted.key && abs(old.at.timeIntervalSince(wanted.at)) < 5 * 60) continue
                val delay = wanted.at.timeIntervalSince(now).toLong().coerceAtLeast(0)
                val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                    .setInitialDelay(delay, TimeUnit.SECONDS)
                    .setInputData(
                        workDataOf(
                            ReminderWorker.keyID to wanted.id,
                            ReminderWorker.keyCategory to wanted.category.name,
                            ReminderWorker.keyTitle to wanted.title,
                            ReminderWorker.keyBody to wanted.body,
                        ),
                    )
                    .addTag(ReminderWorker.workTag)
                    .build()
                work.enqueueUniqueWork(id, ExistingWorkPolicy.REPLACE, request)
                scheduled[id] = wanted
            }
        }
    }

    /** A reminder [reschedule] plans. */
    data class Reminder(
        val id: String,
        val category: Category,
        val title: String,
        val body: String,
        val at: Instant,
        /** What it is about; a reminder with the same key at about the same time isn't replaced. */
        val key: String,
    )

    /** The reminders that fit [state] now: the need running low, the morning note, a thought now and then. */
    fun plan(state: GameState, now: Instant = Instant.now(), calendar: SlimyCalendar = SlimyCalendar.current): List<Reminder> {
        if (!state.hatched) return emptyList()
        val prefs = state.settings.notifications
        val out = mutableListOf<Reminder>()

        if (prefs.needs) {
            nextNeed(state, now)?.let { (need, at) ->
                val line = Dialogue.fill(
                    Dialogue.pick(Dialogue.category(need), state, at, calendar).text,
                    LineContext(game = state.slime.favoriteGame.title),
                    state,
                )
                out += Reminder(
                    "slimy.need", Category.needs, state.slime.name, line,
                    outsideQuietHours(at, prefs, calendar), key = "need.${need.name}",
                )
            }
        }

        if (prefs.daily) {
            val morning = nextMorning(now, prefs, calendar)
            // Mentions the gift only if it will still be unopened by then.
            val giftWaiting = state.streakInfo(morning, calendar).canClaim
            val name = state.slime.name
            out += Reminder(
                "slimy.daily", Category.daily,
                if (giftWaiting) "A gift is waiting" else "A new day in Slimy",
                if (giftWaiting) {
                    "$name has today's gift and three new challenges for you."
                } else {
                    "New challenges are waiting for you and $name."
                },
                morning, key = if (giftWaiting) "daily.gift" else "daily.new",
            )
        }

        if (prefs.world) {
            val later = calendar.addingDays(2, now)
            out += Reminder(
                "slimy.thinking", Category.world, state.slime.name, "i thought about you today. just because.",
                outsideQuietHours(later, prefs, calendar), key = "thinking",
            )
        }
        // Only what is more than a minute away is worth scheduling.
        return out.filter { it.at.timeIntervalSince(now) > 60 }
    }

    /** The next half past eight in the morning (or half past the end of quiet hours, if later). */
    fun nextMorning(now: Instant, prefs: NotificationPrefs, calendar: SlimyCalendar): Instant {
        val hour = max(prefs.quietUntil, 8).coerceIn(0, 23)
        val local = calendar.local(now)
        var next = local.toLocalDate().atTime(hour, 30).atZone(calendar.zone)
        if (!next.isAfter(local)) next = next.plusDays(1)
        return next.toInstant()
    }

    /** Something happened right now (a gift arrived, a friend wants to play). */
    fun post(
        category: Category,
        title: String,
        body: String,
        state: GameState,
        now: Instant = Instant.now(),
        calendar: SlimyCalendar = SlimyCalendar.current,
    ) {
        if (!mayInterrupt(category, state, now, calendar)) return
        show(category, title, body, state)
    }

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
    ): Boolean {
        val prefs = state.settings.notifications
        if (category != null && !isOn(category, prefs)) return false
        if (prefs.isQuiet(calendar.hour(now))) return false
        val context = appContext ?: return false
        return takeSlot(budgetPrefs(context), now, calendar)
    }

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

    /** Shows a notification of [category] now, without asking the budget (reminders and [post]). */
    @SuppressLint("MissingPermission")
    internal fun show(category: Category, title: String, body: String, state: GameState?, id: Int = nextID.incrementAndGet()) {
        val context = appContext ?: return
        if (!authorized()) return
        val open = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN, category.place)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val content = PendingIntent.getActivity(
            context, 200 + category.ordinal, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, category.channel)
            .setSmallIcon(R.drawable.ic_stat_slime)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(content)
            .setGroup("slimy.${category.name}")
            .setCategory(
                when (category) {
                    Category.friends, Category.gifts -> NotificationCompat.CATEGORY_SOCIAL
                    else -> NotificationCompat.CATEGORY_REMINDER
                },
            )
            .setPriority(
                when (category) {
                    Category.friends, Category.gifts -> NotificationCompat.PRIORITY_HIGH
                    else -> NotificationCompat.PRIORITY_DEFAULT
                },
            )
        state?.let { builder.setColor(Brand.accent(it.slime.skin).toInt()) }
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (e: SecurityException) {
            Log.w(tag, "Not allowed to post", e)
        }
    }

    private fun hasPostPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** The system prompt for POST_NOTIFICATIONS (Android 13 and later). */
    private suspend fun askForPostPermission(activity: ComponentActivity): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            var launcher: ActivityResultLauncher<String>? = null
            launcher = activity.activityResultRegistry.register(
                "slimy.notifications.${UUID.randomUUID()}",
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                launcher?.unregister()
                if (cont.isActive) cont.resume(granted)
            }
            cont.invokeOnCancellation { launcher?.unregister() }
            try {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (e: Exception) {
                launcher.unregister()
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    // MARK: Daily budget

    private const val budgetDayKey = "slimy.interruptions.day"
    private const val budgetUsedKey = "slimy.interruptions.used"

    private fun budgetPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences("slimy", Context.MODE_PRIVATE)

    /**
     * Counts today's interruptions so a busy day stays calm. Two keys that
     * are overwritten each day, so nothing piles up.
     */
    internal fun takeSlot(prefs: SharedPreferences, now: Instant, calendar: SlimyCalendar): Boolean = synchronized(this) {
        val day = dayKey(now, calendar)
        if (prefs.getString(budgetDayKey, null) != day) {
            prefs.edit().putString(budgetDayKey, day).putInt(budgetUsedKey, 0).apply()
        }
        val used = prefs.getInt(budgetUsedKey, 0)
        if (used >= dailyLimit) return false
        prefs.edit().putInt(budgetUsedKey, used + 1).apply()
        return true
    }

    internal fun dayKey(date: Instant, calendar: SlimyCalendar): String {
        val d = calendar.localDate(date)
        return "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)
    }
}

/**
 * Posts one scheduled reminder (`Notifier.reschedule`). If it comes due
 * during quiet hours or after its kind was switched off, it stays silent and
 * the plan is made again.
 */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? SlimyApplication ?: return Result.success()
        val category = inputData.getString(keyCategory)?.let { raw -> Notifier.Category.entries.firstOrNull { it.name == raw } }
            ?: return Result.success()
        val title = inputData.getString(keyTitle) ?: return Result.success()
        val body = inputData.getString(keyBody) ?: return Result.success()
        withContext(Dispatchers.Main) {
            val state = app.store.state.value
            val now = Instant.now()
            val prefs = state.settings.notifications
            if (state.hatched && Notifier.isOn(category, prefs) && !prefs.isQuiet(SlimyCalendar.current.hour(now))) {
                Notifier.show(category, title, body, state, id = notificationID(inputData.getString(keyID)))
            }
            // Plans the next ones from the slime as it is now.
            app.store.tick(now)
            Notifier.reschedule(app.store.state.value, now)
        }
        return Result.success()
    }

    private fun notificationID(id: String?): Int = when (id) {
        "slimy.need" -> 101
        "slimy.daily" -> 102
        else -> 103
    }

    companion object {
        const val workTag = "slimy.reminder"
        const val keyID = "id"
        const val keyCategory = "category"
        const val keyTitle = "title"
        const val keyBody = "body"
    }
}
