package de.hyprnode.slimy.platform

import android.content.Context
import android.util.Log
import de.hyprnode.slimy.core.GameStore
import de.hyprnode.slimy.online.FriendsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * An alert that comes with an update: the notification pops up with a sound
 * (heads-up). ActivityKit's `AlertConfiguration`.
 */
data class AlertConfiguration(val title: String, val body: String)

/**
 * Starts, updates and revives the slime notification: the ongoing
 * notification that is Android's counterpart of the Dynamic Island Live
 * Activity. The Android port of App/Core/IslandController.swift.
 *
 * Policy: there is only ever one slime notification. It shows your slime
 * solo, with up to three friends beside it, and switches to a shared moment
 * (reunion, hug, gift …) for a limited time before going back to solo.
 * Alerts go through `Notifier.mayInterrupt`, so quiet hours, the
 * notification switches and the daily limit apply to them as well.
 *
 * One instance lives in `SlimyApplication.island` (`LocalIsland` in Compose).
 * [keepAlive] and [animated] are settings: saved when they change, and a
 * change of [animated] syncs the notification.
 */
// Implemented by the online/platform worker.
class IslandController(context: Context, private val store: GameStore, private val friends: FriendsStore) {
    private val context: Context = context.applicationContext

    /** Where this device shows the slime outside the app. */
    enum class Presence {
        /** An ongoing notification: in the status bar, the notification shade and on the lock screen. */
        notification,

        /** Notifications are off for Slimy, so the slime stays in the app. */
        unavailable;

        /** The place, for headings. */
        val title: String
            get() = when (this) {
                notification -> "Slime notification"
                unavailable -> "Slime notification"
            }

        /** One sentence about where the slime appears outside the app. */
        val explanation: String
            get() = when (this) {
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
    val keepAlive = MutableStateFlow(true)

    /** The slime moves in the notification. Default on. */
    val animated = MutableStateFlow(true)

    /** Where this device shows the slime outside the app. */
    val presence: Presence get() = Presence.notification

    fun refreshStatus() {}

    /** Lifecycle events go to logcat (debug), so the notification can be followed without any UI. */
    fun note(line: String) {
        Log.d("SlimyIsland", line)
    }

    // MARK: Lifecycle

    /** Shows a fresh slime notification (replacing any old one). */
    suspend fun start() {}

    /** Takes the slime notification down and turns [keepAlive] off. */
    suspend fun stop() {}

    /** Makes sure the slime is out there; [restart] turns [keepAlive] on and renews it ("Summon Slime"). */
    suspend fun ensureRunning(restart: Boolean = false) {}

    // MARK: Updates

    /**
     * Updates the notification. An [alert] pops it up with a sound, but only
     * while the app isn't on screen and only if `Notifier` allows an
     * interruption of [category] right now; otherwise the same update arrives
     * silently.
     */
    suspend fun sync(alert: AlertConfiguration? = null, category: Notifier.Category? = null) {}

    /** Shows a shared moment with a friend for a while, then goes back to solo. */
    suspend fun show(moment: SharedMoment, friendID: String?, alert: String? = null) {}

    /**
     * Now and then a friend's slime drops by and the two meet in the
     * notification. At most every two hours and five times a day.
     */
    suspend fun maybeMeetup(now: Instant = Instant.now()) {}

    /**
     * For notification actions and live reactions: waits until the reaction
     * has played, then settles the notification, and renews an old one.
     */
    suspend fun settle() {}
}
