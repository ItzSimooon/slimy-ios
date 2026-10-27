package de.hyprnode.slimy.platform

import android.content.Context
import de.hyprnode.slimy.core.GameStore
import de.hyprnode.slimy.model.JsonDate
import de.hyprnode.slimy.model.PhoneEvent
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Notices what the phone does (battery, charger, Battery Saver, Wi-Fi, mobile
 * data, offline, morning and night) and lets the slime react. The Android
 * port of App/Core/PhoneMonitor.swift: sticky `ACTION_BATTERY_CHANGED`,
 * power connected/disconnected, power save mode, `ConnectivityManager`
 * callbacks, and periodic plus charging WorkManager checks. Same PhoneEvent
 * rules as on iOS.
 *
 * One instance lives in `SlimyApplication.phoneMonitor`.
 */
// Implemented by the online/platform worker.
class PhoneMonitor(context: Context, private val store: GameStore, private val island: IslandController) {
    private val context: Context = context.applicationContext

    /** What the phone looked like the last time the app checked. */
    @Serializable
    data class Snapshot(
        /** 0...1, or -1 unknown. */
        val battery: Float,
        val charging: Boolean,
        val full: Boolean,
        val lowPower: Boolean,
        val online: Boolean,
        val wifi: Boolean,
        val cellular: Boolean,
        /** False when the network state wasn't known yet. Never compared against. */
        val netKnown: Boolean,
        val at: JsonDate,
    )

    /** "Reacts to your phone" is on and the slime has hatched. */
    val enabled: Boolean
        get() {
            val state = store.state.value
            return state.settings.liveEvents && state.hatched
        }

    /** Starts live watching (called when the app comes to the front). */
    fun start() {}

    /** For background runs (workers, notification actions): waits briefly for the network state, then checks. */
    suspend fun checkInBackground() {}

    /** Compares the phone with the last snapshot and reacts to what changed. */
    fun check(now: Instant = Instant.now()) {}

    companion object {
        /** The unique WorkManager name of the check while charging. */
        const val powerTaskID = "de.hyprnode.slimy.power"

        /** Asks WorkManager for a run while the phone charges, which notices the charger and updates the notification. */
        fun schedulePowerCheck(context: Context) {}
    }
}

/** Which reaction wins when several changes turn up in one check. */
val PhoneEvent.priority: Int
    get() = when (this) {
        PhoneEvent.batteryCritical -> 9
        PhoneEvent.batteryLow -> 8
        PhoneEvent.offline -> 7
        PhoneEvent.chargerConnected, PhoneEvent.chargerDisconnected -> 6
        PhoneEvent.batteryFull -> 5
        PhoneEvent.goodMorning, PhoneEvent.goodNight -> 4
        PhoneEvent.wifiConnected, PhoneEvent.backOnline, PhoneEvent.cellular, PhoneEvent.wifiDisconnected -> 3
        PhoneEvent.lowPowerOn, PhoneEvent.lowPowerOff -> 2
        PhoneEvent.airplaneOn, PhoneEvent.airplaneOff, PhoneEvent.alarm, PhoneEvent.newHour -> 1
    }
