package de.hyprnode.slimy.platform

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.hyprnode.slimy.SlimyApplication
import de.hyprnode.slimy.core.GameStore
import de.hyprnode.slimy.model.Reaction
import de.hyprnode.slimy.model.Slime
import de.hyprnode.slimy.model.SlimeSprites
import de.hyprnode.slimy.model.SlimyCalendar
import de.hyprnode.slimy.model.addingTimeInterval
import de.hyprnode.slimy.model.timeIntervalSince
import de.hyprnode.slimy.online.FriendsStore
import de.hyprnode.slimy.platform.island.IslandService
import de.hyprnode.slimy.platform.island.IslandSystem
import de.hyprnode.slimy.platform.island.IslandTuning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.random.Random

/**
 * An alert that comes with an update: the notification pops up with a sound
 * (heads-up). ActivityKit's `AlertConfiguration`.
 */
data class AlertConfiguration(val title: String, val body: String)

/**
 * Starts, updates and revives the slime outside the app: the island (a black
 * overlay around the front camera, see `platform/island`) when Slimy may
 * show it over other apps, and the slime notification, Android's
 * counterpart of the Live Activity, which is also the island's foreground
 * notification. The Android port of App/Core/IslandController.swift.
 *
 * Policy: there is only ever one slime notification. It shows your slime
 * solo, with up to three friends beside it, and switches to a shared moment
 * (reunion, hug, gift …) for a limited time before going back to solo.
 * Alerts go through `Notifier.mayInterrupt`, so quiet hours, the
 * notification switches and the daily limit apply to them as well.
 *
 * Android has no 8-hour limit and lets an app post at any time, so where
 * iOS renews the activity, this brings the notification back when it is
 * missing and [keepAlive] is on: when the app opens, after a button in the
 * notification or a widget, on "Summon Slime", after a restart and in the
 * background refresh. Swiping it away (possible from Android 14 on) is
 * respected until then.
 *
 * The island runs in `IslandService`, a foreground service Android only
 * lets start from the foreground or from moments it allows (a restart, a
 * notification button, a shortcut). Where it may not start, or Slimy isn't
 * allowed over other apps, the slime quietly stays in the notification.
 *
 * One instance lives in `SlimyApplication.island` (`LocalIsland` in Compose).
 * [keepAlive], [animated], [showIsland] and [tuning] are settings: saved
 * when they change, and a change of [animated] syncs the notification. All
 * work runs on the main thread, like the `@MainActor` original.
 */
class IslandController(context: Context, private val store: GameStore, private val friends: FriendsStore) {
    private val context: Context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("slimy", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Where this device shows the slime outside the app. */
    enum class Presence {
        /** The island around the front camera, over every app, plus the notification. */
        island,

        /** An ongoing notification: in the status bar, the notification shade and on the lock screen. */
        notification,

        /** Notifications are off for Slimy, so the slime stays in the app. */
        unavailable;

        /** The place, for headings. */
        val title: String
            get() = when (this) {
                island -> "Island"
                notification -> "Slime notification"
                unavailable -> "Slime notification"
            }

        /** One sentence about where the slime appears outside the app. */
        val explanation: String
            get() = when (this) {
                island ->
                    "Your slime lives in a little black island around your front camera, over every app, even when Slimy is closed."
                notification ->
                    "Your slime lives in your notifications and on the lock screen, even when Slimy is closed."
                unavailable ->
                    "Notifications are off for Slimy, so your slime stays here in the app."
            }
    }

    private val _isLive = MutableStateFlow(false)

    /** The slime notification is showing. */
    val isLive: StateFlow<Boolean> = _isLive.asStateFlow()

    /** Why the slime notification couldn't be shown, if it couldn't. */
    val problem = MutableStateFlow<String?>(null)

    /** "Always bring it back": the notification is kept up and renewed. Default on. */
    val keepAlive = MutableStateFlow(prefs.getBoolean(keyKeepAlive, true))

    /** The slime moves in the notification. Default on. */
    val animated = MutableStateFlow(prefs.getBoolean(keyAnimated, true))

    /** Where this device shows the slime outside the app. */
    val presence: Presence
        get() = if (showIsland.value && overlaySupported && overlayPermitted) Presence.island else notificationPresence

    /** Where the notification part stands: showing, or off for Slimy. */
    val notificationPresence: Presence
        get() = if (Notifier.authorized() && Notifier.isChannelOn(Notifier.slimeChannel)) {
            Presence.notification
        } else {
            Presence.unavailable
        }

    // MARK: The island

    /** "Show island": the slime lives in the island while Slimy may show it over other apps. Default on. */
    val showIsland = MutableStateFlow(prefs.getBoolean(keyOverlay, true))

    /** The person's fine-tuning of the island's place and size. */
    val tuning = MutableStateFlow(readTuning())

    /** The island position screen is open: the island shows over Slimy too, as a live preview. */
    val previewing = MutableStateFlow(false)

    private val _overlayRunning = MutableStateFlow(false)

    /** The island's service runs (the island may still be hidden: screen off, landscape, full screen). */
    val overlayRunning: StateFlow<Boolean> = _overlayRunning.asStateFlow()

    private val _overlayShowing = MutableStateFlow(false)

    /** The island is on screen right now. */
    val overlayShowing: StateFlow<Boolean> = _overlayShowing.asStateFlow()

    private val _overlayAllowed = MutableStateFlow(IslandSystem.canDrawOverlays(this.context))

    /** Whether Slimy may show things over other apps, refreshed when that changes. */
    val overlayAllowed: StateFlow<Boolean> = _overlayAllowed.asStateFlow()

    /** Whether this phone lets apps show over other apps at all (Android Go doesn't). */
    val overlaySupported: Boolean get() = IslandSystem.overlaySupported(context)

    /** Whether Slimy may show the island over other apps right now. */
    val overlayPermitted: Boolean
        get() = IslandSystem.canDrawOverlays(context).also { _overlayAllowed.value = it }

    /** The island should be up: switched on, allowed, and there is a slime. */
    val wantsOverlay: Boolean
        get() = showIsland.value && store.state.value.hatched && overlaySupported && overlayPermitted

    private val _alerts = MutableSharedFlow<AlertConfiguration>(extraBufferCapacity = 4)

    /** News that opens the island for a moment (instead of a heads-up while it shows). */
    val alerts: SharedFlow<AlertConfiguration> = _alerts.asSharedFlow()

    /** A shared moment on show, until [until]. */
    data class Moment(val kind: SharedMoment, val friend: String?, val until: Instant)

    private val _moment = MutableStateFlow<Moment?>(null)

    /** The shared moment on show, for the island. */
    val moment: StateFlow<Moment?> = _moment.asStateFlow()

    private var momentNow: Moment?
        get() = _moment.value
        set(value) {
            _moment.value = value
        }

    /** When the island's service was last asked to start, so a burst of requests starts it once. */
    private var overlayRequestedAt = 0L
    private var settleJob: Job? = null
    private var staleJob: Job? = null
    private var animationJob: Job? = null

    /** What the notification shows right now, for the animation's next frames. */
    private var shown: IslandState? = null

    private var startedAt: Instant?
        get() = prefs.getLong(keyStartedAt, 0L).takeIf { it > 0 }?.let { Instant.ofEpochSecond(it) }
        set(value) {
            prefs.edit().putLong(keyStartedAt, value?.epochSecond ?: 0L).apply()
        }

    init {
        scope.launch {
            keepAlive.drop(1).collect { on ->
                prefs.edit().putBoolean(keyKeepAlive, on).apply()
                // Ongoing only while it should be kept: the flag goes with the next post.
                if (isPosted()) sync()
            }
        }
        scope.launch {
            animated.drop(1).collect { on ->
                prefs.edit().putBoolean(keyAnimated, on).apply()
                sync()
            }
        }
        scope.launch {
            showIsland.drop(1).collect { on -> prefs.edit().putBoolean(keyOverlay, on).apply() }
        }
        scope.launch {
            tuning.drop(1).collect { t ->
                val c = t.clamped()
                prefs.edit()
                    .putFloat(keyOffsetX, c.offsetX)
                    .putFloat(keyOffsetY, c.offsetY)
                    .putFloat(keyScale, c.scale)
                    .apply()
            }
        }
        // "Over other apps" switched off in the system settings takes the island down.
        IslandSystem.watchPermission(this.context) {
            scope.launch {
                val allowed = IslandSystem.canDrawOverlays(this@IslandController.context)
                _overlayAllowed.value = allowed
                if (!allowed && _overlayRunning.value) stopOverlay(keepNotification = true)
            }
        }
    }

    private fun readTuning() = IslandTuning(
        prefs.getFloat(keyOffsetX, 0f),
        prefs.getFloat(keyOffsetY, 0f),
        prefs.getFloat(keyScale, 1f),
    ).clamped()

    private fun content(now: Instant = Instant.now()): IslandState {
        momentNow?.let { if (it.until <= now) momentNow = null }
        val m = momentNow
        return IslandState.of(
            store.state.value, now,
            friends = islandFriends(),
            moment = m?.kind, momentFriend = m?.friend,
            animated = animated.value,
        )
    }

    /** Friends beside the slime, most important first. */
    fun islandFriends(): List<FriendSlime> = runCatching { friends.islandFriends }.getOrDefault(emptyList())

    fun refreshStatus() {
        _overlayAllowed.value = IslandSystem.canDrawOverlays(context)
        _isLive.value = isPosted() || _overlayRunning.value
    }

    /** Lifecycle events go to logcat (debug), so the notification can be followed without any UI. */
    fun note(line: String) {
        Log.d("SlimyIsland", line)
    }

    // MARK: Lifecycle

    /**
     * Shows the slime: the island when it is on and allowed (its service
     * posts the notification as well), else a fresh slime notification
     * (replacing any old one).
     */
    suspend fun start() = onMain {
        if (!store.state.value.hatched) return@onMain
        if (wantsOverlay && startOverlay()) {
            startedAt = Instant.now()
            problem.value = null
            note("Island started")
            val state = content()
            if (Notifier.authorized()) post(state, frame = null)
            afterPost(state)
            refreshStatus()
            return@onMain
        }
        if (!Notifier.authorized()) {
            problem.value = "Notifications are turned off for Slimy. Turn them on in Settings under Apps > Slimy > Notifications."
            note("Start cancelled: notifications are off")
            refreshStatus()
            return@onMain
        }
        if (!Notifier.isChannelOn(Notifier.slimeChannel)) {
            problem.value = "The slime notification is turned off. Turn it on in Settings under Apps > Slimy > Notifications."
            note("Start cancelled: the slime channel is off")
            refreshStatus()
            return@onMain
        }
        val state = content()
        if (post(state, frame = null)) {
            startedAt = Instant.now()
            problem.value = null
            note("Started")
            afterPost(state)
        } else {
            problem.value = "Your slime couldn't move to your notifications just now. Open Slimy and try again."
        }
        refreshStatus()
    }

    /** Takes the slime notification down and turns [keepAlive] off. */
    suspend fun stop() = onMain {
        keepAlive.value = false
        prefs.edit().putBoolean(keyKeepAlive, false).apply()
        note("Stopped by you")
        settleJob?.cancel()
        staleJob?.cancel()
        animationJob?.cancel()
        shown = null
        stopOverlay(keepNotification = false)
        NotificationManagerCompat.from(context).cancel(SlimeNotification.id)
        refreshStatus()
    }

    /** Makes sure the slime is out there; [restart] turns [keepAlive] on and renews it ("Summon Slime"). */
    suspend fun ensureRunning(restart: Boolean = false) = onMain {
        if (restart) {
            keepAlive.value = true
            prefs.edit().putBoolean(keyKeepAlive, true).apply()
        }
        if (!keepAlive.value || !store.state.value.hatched) {
            refreshStatus()
            return@onMain
        }
        val island = wantsOverlay
        val running = _overlayRunning.value
        when {
            // The island should be up and isn't (or a restart was asked for).
            island && (!running || restart) -> start()
            // Switched off or no longer allowed: the island goes, the notification stays.
            !island && running -> {
                stopOverlay(keepNotification = true)
                if (!isPosted()) start() else sync()
            }
            restart || (!isPosted() && !running) -> start()
            else -> {
                sync()
                refreshStatus()
            }
        }
    }

    /**
     * Starts the island's service right now, from a moment Android allows it
     * (an activity on screen, such as the Summon Slime shortcut). Returns
     * whether it is on its way.
     */
    fun startOverlayNow(): Boolean {
        if (!wantsOverlay) return false
        keepAlive.value = true
        prefs.edit().putBoolean(keyKeepAlive, true).apply()
        return startOverlay()
    }

    /** "Show island" switched on or off (in Settings, or "Hide island" in its menu). */
    fun setShowIsland(on: Boolean) {
        showIsland.value = on
        prefs.edit().putBoolean(keyOverlay, on).apply()
        scope.launch {
            if (on) {
                ensureRunning(restart = !_overlayRunning.value)
            } else {
                stopOverlay(keepNotification = true)
                // The slime stays in the notification.
                if (keepAlive.value && !isPosted()) start() else sync()
            }
        }
    }

    private fun startOverlay(): Boolean {
        if (_overlayRunning.value && IslandService.current != null) return true
        val now = System.currentTimeMillis()
        if (now - overlayRequestedAt < 3_000) return true
        return try {
            ContextCompat.startForegroundService(context, Intent(context, IslandService::class.java))
            overlayRequestedAt = now
            true
        } catch (e: Exception) {
            // Android 12+: ForegroundServiceStartNotAllowedException from the background.
            note("Island can't start now: $e")
            false
        }
    }

    private fun stopOverlay(keepNotification: Boolean) {
        overlayRequestedAt = 0L
        IslandService.current?.shutDown(keepNotification)
    }

    // MARK: Island service

    /** The notification the island's service shows in the foreground: the slime notification. */
    fun foregroundNotification(): Notification {
        val state = content()
        shown = state
        return SlimeNotification.build(context, state, frame = null, ongoing = true)
    }

    fun overlayStarted() {
        _overlayRunning.value = true
        refreshStatus()
    }

    fun overlayStopped() {
        _overlayRunning.value = false
        _overlayShowing.value = false
        refreshStatus()
        // Off the foreground, the notification goes back to following "Always bring it back".
        scope.launch { if (isPosted()) sync() }
    }

    fun overlayShowing(on: Boolean) {
        _overlayShowing.value = on
    }

    /**
     * The island couldn't start or show ([reason] says why, if it matters to
     * the person). The slime stays in the notification, quietly.
     */
    fun overlayFailed(reason: String?) {
        note("Island failed: ${reason ?: "not allowed to start now"}")
        overlayRequestedAt = 0L
        scope.launch {
            IslandService.current?.shutDown(keepNotification = true)
            _overlayRunning.value = false
            if (keepAlive.value && store.state.value.hatched && !isPosted() && Notifier.authorized()) {
                val state = content()
                if (post(state, frame = null)) afterPost(state)
            }
            refreshStatus()
        }
    }

    /** The person swiped the notification away (Android 14 and later allow that). */
    fun noteDismissed() {
        note("Dismissed")
        scope.launch {
            animationJob?.cancel()
            shown = null
            refreshStatus()
        }
    }

    // MARK: Updates

    /**
     * Updates the notification. An [alert] pops it up with a sound, but only
     * while the app isn't on screen and only if `Notifier` allows an
     * interruption of [category] right now; otherwise the same update arrives
     * silently.
     */
    suspend fun sync(alert: AlertConfiguration? = null, category: Notifier.Category? = null) = onMain {
        val island = _overlayRunning.value
        if (!isPosted() && !island) {
            refreshStatus()
            return@onMain
        }
        val state = content()
        if (Notifier.authorized()) post(state, frame = null) else shown = state
        if (alert != null && !appInFront() && Notifier.mayInterrupt(category, store.state.value)) {
            // While the island is on screen the news opens it, like the Dynamic Island; else a heads-up.
            if (island && _overlayShowing.value) {
                _alerts.tryEmit(alert)
                note("Island alert: ${alert.body}")
            } else {
                postAlert(alert, state)
            }
        }
        afterPost(state)
        refreshStatus()
    }

    /** Shows a shared moment with a friend for a while, then goes back to solo. */
    suspend fun show(moment: SharedMoment, friendID: String?, alert: String? = null) = onMain {
        momentNow = Moment(moment, friendID, Instant.now().addingTimeInterval(moment.duration))
        val name = store.slime.name
        sync(alert = alert?.let { AlertConfiguration(name, it) }, category = Notifier.Category.friends)
        scope.launch {
            delay(((moment.duration + 1) * 1000).toLong())
            sync()
        }
    }

    /**
     * Now and then a friend's slime drops by and the two meet in the
     * notification. At most every two hours and five times a day.
     */
    suspend fun maybeMeetup(now: Instant = Instant.now()) = onMain {
        val friends = runCatching { friends.islandFriends }.getOrDefault(emptyList())
        if (momentNow != null || friends.isEmpty() || !(isPosted() || _overlayRunning.value) || store.slime.isAsleep) return@onMain
        val last = prefs.getLong(keyLastMeetup, 0L).let { if (it == 0L) null else Instant.ofEpochSecond(it) }
        val calendar = SlimyCalendar.current
        val today = last != null && calendar.localDate(last) == calendar.localDate(now)
        val count = if (today) prefs.getInt(keyMeetupsToday, 0) else 0
        val since = last?.let { now.timeIntervalSince(it) } ?: Double.MAX_VALUE
        if (!(since > 2 * 3600 && count < 5 && Random.nextDouble() < 0.5)) return@onMain
        prefs.edit().putLong(keyLastMeetup, now.epochSecond).putInt(keyMeetupsToday, count + 1).apply()
        // Mostly the partner, sometimes another friend.
        val friend = if (Random.nextDouble() < 0.7) friends[0] else friends.random()
        note("Meetup with ${friend.name}")
        show(SharedMoment.meetup, friend.id)
    }

    /**
     * For notification actions and live reactions: waits until the reaction
     * has played, then settles the notification, and brings it back if it
     * is missing while [keepAlive] is on.
     */
    suspend fun settle() = onMain {
        val slime = store.slime
        val now = Instant.now()
        if (slime.activeReaction(now) != Reaction.none) {
            val left = Slime.reactionWindow - now.timeIntervalSince(slime.reactionAt)
            delay(((left.coerceAtLeast(0.0) + 0.3) * 1000).toLong())
            sync()
        }
        bringBackIfMissing()
    }

    private suspend fun bringBackIfMissing() {
        if (!keepAlive.value || !store.state.value.hatched) return
        val islandMissing = wantsOverlay && !_overlayRunning.value
        if (!islandMissing && (isPosted() || _overlayRunning.value)) return
        note("Bringing it back")
        start()
    }

    // MARK: Posting

    /** Whether the slime notification is in the shade right now. */
    private fun isPosted(): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return try {
            manager.activeNotifications.any { it.id == SlimeNotification.id }
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun post(state: IslandState, frame: Int?): Boolean {
        if (!Notifier.authorized()) return false
        return try {
            val notification = SlimeNotification.build(context, state, frame, ongoing = keepAlive.value)
            NotificationManagerCompat.from(context).notify(SlimeNotification.id, notification)
            shown = state
            true
        } catch (e: Exception) {
            note("Post failed: $e")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun postAlert(alert: AlertConfiguration, state: IslandState) {
        if (!Notifier.isChannelOn(Notifier.alertChannel)) return
        try {
            NotificationManagerCompat.from(context).notify(SlimeNotification.alertID, SlimeNotification.alert(context, alert, state))
            note("Alert: ${alert.body}")
        } catch (e: Exception) {
            note("Alert failed: $e")
        }
    }

    /** After every post: settle after the reaction, go back to solo when the moment or the event is over, animate. */
    private fun afterPost(state: IslandState) {
        scheduleSettle()
        scheduleStale(state)
        animate(state)
    }

    /** A reaction plays for a few seconds; one more update returns to the mood. */
    private fun scheduleSettle() {
        settleJob?.cancel()
        val slime = store.slime
        val now = Instant.now()
        if (slime.activeReaction(now) == Reaction.none) return
        val left = Slime.reactionWindow - now.timeIntervalSince(slime.reactionAt)
        settleJob = scope.launch {
            delay(((left.coerceAtLeast(0.0) + 0.3) * 1000).toLong())
            sync()
        }
    }

    /**
     * When a moment's or a phone event's time is up, the notification goes
     * back to the everyday state (iOS does this with the activity's stale date).
     */
    private fun scheduleStale(state: IslandState) {
        staleJob?.cancel()
        val now = Instant.now()
        val ends = listOfNotNull(
            momentNow?.until,
            if (state.event == null) null else store.slime.eventAt.addingTimeInterval(Slime.eventWindow),
        ).minOrNull() ?: return
        val wait = ends.timeIntervalSince(now).coerceAtLeast(0.0)
        staleJob = scope.launch {
            delay(((wait + 1) * 1000).toLong())
            sync()
        }
    }

    /**
     * While the app is in front and [animated] is on, the slime plays a few
     * frames of its animation after each update (one a second, one loop),
     * then rests on its still pose.
     */
    private fun animate(state: IslandState) {
        animationJob?.cancel()
        if (!animated.value || !appInFront()) return
        val frames = SlimeSprites.frames(state.slimeStage, state.anim).size
        if (frames < 2) return
        animationJob = scope.launch {
            val anchor = state.anchor.toEpochMilli()
            for (step in 0 until minOf(frames, maxAnimatedFrames)) {
                delay(1000)
                if (!isActive || shown != state) return@launch
                if (!appInFront() || !animated.value || !isPosted()) break
                post(state, SlimeNotification.frameIndex(state.slimeStage, state.anim, anchor, System.currentTimeMillis()))
            }
            // Back to the still pose, which is what the notification shows while the app is away.
            if (shown == state && isPosted()) post(state, frame = null)
        }
    }

    private fun appInFront(): Boolean = (context as? SlimyApplication)?.isInForeground == true

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    private companion object {
        const val keyKeepAlive = "slimy.island.keepAlive"
        const val keyAnimated = "slimy.island.animated"
        const val keyStartedAt = "slimy.island.startedAt"
        const val keyLastMeetup = "slimy.island.lastMeetup"
        const val keyMeetupsToday = "slimy.island.meetupsToday"
        const val keyOverlay = "slimy.island.overlay"
        const val keyOffsetX = "slimy.island.offsetX"
        const val keyOffsetY = "slimy.island.offsetY"
        const val keyScale = "slimy.island.scale"

        /** How many frames one round of animation shows at most. */
        const val maxAnimatedFrames = 10
    }
}
