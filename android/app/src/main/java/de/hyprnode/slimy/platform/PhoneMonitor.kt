package de.hyprnode.slimy.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.hyprnode.slimy.SlimyApplication
import de.hyprnode.slimy.core.GameStore
import de.hyprnode.slimy.model.JsonDate
import de.hyprnode.slimy.model.PhoneEvent
import de.hyprnode.slimy.model.SleepSchedule
import de.hyprnode.slimy.model.SlimyCalendar
import de.hyprnode.slimy.model.SlimyJson
import de.hyprnode.slimy.model.timeIntervalSince
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Notices what the phone does (battery, charger, Battery Saver, Wi-Fi, mobile
 * data, offline, morning and night) and lets the slime react. The Android
 * port of App/Core/PhoneMonitor.swift: sticky `ACTION_BATTERY_CHANGED`,
 * power connected/disconnected, power save mode, `ConnectivityManager`
 * callbacks, and periodic plus charging WorkManager checks. Same PhoneEvent
 * rules as on iOS.
 *
 * While the app process runs, changes are seen live (the receivers stay
 * registered once [start] has run). Otherwise the phone's state is compared
 * with the last one seen every time the app runs: the background refresh,
 * a check while charging, the notification's and widgets' buttons. Things
 * that happened and un-happened in between can't be seen.
 *
 * One instance lives in `SlimyApplication.phoneMonitor`.
 */
class PhoneMonitor(context: Context, private val store: GameStore, private val island: IslandController) {
    private val context: Context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("slimy", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()

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

    /** The network as the phone reports it. */
    data class Net(val online: Boolean, val wifi: Boolean, val cellular: Boolean)

    /** "Reacts to your phone" is on and the slime has hatched. */
    val enabled: Boolean
        get() {
            val state = store.state.value
            return state.settings.liveEvents && state.hatched
        }

    private var started = false
    private var ticking = false

    /**
     * Right after the app opens, the first readings only set the baseline:
     * the network state arrives a moment later and would otherwise look
     * like "Wi-Fi just connected".
     */
    @Volatile
    private var settleUntil: Instant = Instant.EPOCH

    /** The network as the live callback last saw it, once it held for a few seconds. */
    @Volatile
    private var currentNet: Net? = null

    /** The battery broadcast fires for every change of voltage or temperature; only these count. */
    private var lastBatteryKey: String? = null

    private val timer = object : Runnable {
        override fun run() {
            if (!ticking) return
            check()
            main.postDelayed(this, 60_000L)
        }
    }

    /** Network changes are only checked once the network has held for a few seconds. */
    private val networkSettled = Runnable {
        currentNet = readNetwork(nullMeansOffline = true)
        check()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                val key = "${intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)}/" +
                    "${intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)}/" +
                    "${intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)}"
                if (key == lastBatteryKey) return
                lastBatteryKey = key
            }
            check()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = networkChanged()
        override fun onLost(network: Network) = networkChanged()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = networkChanged()
    }

    private fun networkChanged() {
        main.removeCallbacks(networkSettled)
        main.postDelayed(networkSettled, 8_000L)
    }

    private var last: Snapshot?
        get() = prefs.getString(keySnapshot, null)?.let { json ->
            runCatching { SlimyJson.storage.decodeFromString(Snapshot.serializer(), json) }.getOrNull()
        }
        set(value) {
            val edit = prefs.edit()
            if (value == null) {
                edit.remove(keySnapshot)
            } else {
                edit.putString(keySnapshot, SlimyJson.storage.encodeToString(Snapshot.serializer(), value))
            }
            edit.apply()
        }

    /** When each event last made the slime react (seconds since 1970), kept across launches. */
    private var lastFired: Map<String, Long>
        get() = prefs.getString(keyFired, null)?.let { json ->
            runCatching { SlimyJson.storage.decodeFromString(firedSerializer, json) }.getOrNull()
        } ?: emptyMap()
        set(value) {
            prefs.edit().putString(keyFired, SlimyJson.storage.encodeToString(firedSerializer, value)).apply()
        }

    /** Starts live watching (called when the app comes to the front). */
    fun start() {
        main.post {
            settleUntil = Instant.now().plusSeconds(15)
            if (!started) {
                started = true
                register()
            }
            startTimer()
            check()
        }
    }

    /** The app went to the background: the minute timer stops, the receivers stay while the process lives. */
    fun stop() {
        main.post {
            ticking = false
            main.removeCallbacks(timer)
        }
    }

    private fun startTimer() {
        if (ticking) return
        ticking = true
        main.postDelayed(timer, 60_000L)
    }

    private fun register() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            // significantTimeChange: the clock, the time zone or the day changed.
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        try {
            connectivity?.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Too many callbacks or no permission: the network is then only read when checking.
        }
        currentNet = readNetwork(nullMeansOffline = true)
    }

    /** For background runs (workers, notification actions): reads the network fresh, then checks. */
    suspend fun checkInBackground() {
        withContext(Dispatchers.Default) {
            if (!appInFront()) currentNet = readNetwork(nullMeansOffline = false)
        }
        withContext(Dispatchers.Main) { check() }
    }

    /** Compares the phone with the last snapshot and reacts to what changed. */
    fun check(now: Instant = Instant.now()) {
        val toFire: List<PhoneEvent>
        synchronized(lock) {
            val snapshot = read(now)
            val previous = last
            last = snapshot
            if (!enabled) return
            val background = !appInFront()
            val calendar = SlimyCalendar.current
            toFire = events(
                previous = previous,
                snapshot = snapshot,
                settling = now < settleUntil,
                background = background,
                schedule = store.state.value.settings.sleep,
                now = now,
                calendar = calendar,
                firedToday = { firedToday(it, now, calendar) },
            )
        }
        for (event in toFire) fire(event, now)
    }

    // MARK: Reading

    private fun read(now: Instant): Snapshot {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val fraction = if (level >= 0 && scale > 0) level.toFloat() / scale else -1f
        val full = status == BatteryManager.BATTERY_STATUS_FULL || (plugged != 0 && level >= 0 && level == scale)
        val charging = plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING || full
        val power = context.getSystemService(PowerManager::class.java)
        val net = currentNet
        val previous = if (net == null) last else null
        return Snapshot(
            battery = fraction,
            charging = charging,
            full = full,
            lowPower = power?.isPowerSaveMode == true,
            online = net?.online ?: (previous?.online ?: false),
            wifi = net?.wifi ?: (previous?.wifi ?: false),
            cellular = net?.cellular ?: (previous?.cellular ?: false),
            netKnown = net != null,
            at = now,
        )
    }

    /**
     * The network right now. In the background a missing network may only
     * mean the app's access is paused (Doze, Data Saver), so it reads as
     * unknown there unless [nullMeansOffline].
     */
    private fun readNetwork(nullMeansOffline: Boolean): Net? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return try {
            val network = connectivity.activeNetwork
                ?: return if (nullMeansOffline || appInFront()) Net(online = false, wifi = false, cellular = false) else null
            val caps = connectivity.getNetworkCapabilities(network) ?: return null
            Net(
                online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            )
        } catch (e: SecurityException) {
            null
        }
    }

    // MARK: Reacting

    private fun firedToday(event: PhoneEvent, now: Instant, calendar: SlimyCalendar): Boolean {
        val at = lastFired[event.name] ?: return false
        return calendar.localDate(Instant.ofEpochSecond(at)) == calendar.localDate(now)
    }

    private fun fire(event: PhoneEvent, now: Instant) {
        synchronized(lock) {
            // A flapping connection or a wobbly charger must not spam the slime.
            val fired = lastFired.toMutableMap()
            val at = fired[event.name]
            if (at != null && now.epochSecond - at < 10 * 60) return
            fired[event.name] = now.epochSecond
            lastFired = fired.filterValues { now.epochSecond - it < 3 * 86400 }
        }
        val said = store.handle(event)
        val slime = store.slime
        // Only news that matters pops up with a sound, and never while the
        // slime sleeps (a battery that fills up overnight stays quiet). The
        // island adds quiet hours and the daily limit.
        val alert = if (event.alerts && !slime.isAsleep) AlertConfiguration(slime.name, said) else null
        val background = !appInFront()
        scope.launch {
            island.sync(alert)
            if (!background) island.settle()
        }
    }

    private fun appInFront(): Boolean = (context as? SlimyApplication)?.isInForeground == true

    companion object {
        /** The unique WorkManager name of the check while charging. */
        const val powerTaskID = "de.hyprnode.slimy.power"

        private const val keySnapshot = "slimy.phone.snapshot"
        private const val keyFired = "slimy.phone.fired"
        private val firedSerializer = MapSerializer(String.serializer(), Long.serializer())

        /** Asks WorkManager for a run while the phone charges, which notices the charger and updates the notification. */
        fun schedulePowerCheck(context: Context) {
            val request = OneTimeWorkRequestBuilder<PowerCheckWorker>()
                .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()
            try {
                WorkManager.getInstance(context).enqueueUniqueWork(powerTaskID, ExistingWorkPolicy.KEEP, request)
            } catch (e: IllegalStateException) {
                // WorkManager isn't set up (tests, a broken install): nothing to schedule.
            }
        }

        // MARK: The rules (pure, so they can be tested)

        /**
         * What changed between two readings, the two that matter most first.
         * Battery thresholds are crossings, so they are news whenever seen.
         * Charger and power mode changes count while you watch, or in a
         * background run (that is the reaction to charging); not hours later
         * when you open the app. Network changes only count when seen live, a
         * few minutes apart.
         */
        fun events(
            previous: Snapshot?,
            snapshot: Snapshot,
            settling: Boolean,
            background: Boolean,
            schedule: SleepSchedule,
            now: Instant,
            calendar: SlimyCalendar,
            firedToday: (PhoneEvent) -> Boolean,
        ): List<PhoneEvent> {
            val events = mutableListOf<PhoneEvent>()
            if (previous != null && !settling) {
                val age = now.timeIntervalSince(previous.at)
                events += powerEvents(previous, snapshot, includeState = background || age < 30 * 60)
                if (previous.netKnown && snapshot.netKnown && age < 5 * 60) {
                    events += networkEvents(previous, snapshot)
                }
            }
            events += clockEvents(previous?.at, now, schedule, foreground = !background, calendar, firedToday)
            // A long gap can turn up several changes at once; react to the two
            // that matter most, in order.
            return events.sortedByDescending { it.priority }.take(2)
        }

        fun powerEvents(old: Snapshot, new: Snapshot, includeState: Boolean): List<PhoneEvent> {
            val out = mutableListOf<PhoneEvent>()
            if (includeState) {
                if (!old.charging && new.charging) out += PhoneEvent.chargerConnected
                if (old.charging && !new.charging) out += PhoneEvent.chargerDisconnected
                if (!old.full && new.full) out += PhoneEvent.batteryFull
                if (!old.lowPower && new.lowPower) out += PhoneEvent.lowPowerOn
                if (old.lowPower && !new.lowPower) out += PhoneEvent.lowPowerOff
            }
            if (old.battery >= 0 && new.battery >= 0 && !new.charging) {
                if (old.battery >= 0.05f && new.battery < 0.05f) {
                    out += PhoneEvent.batteryCritical
                } else if (old.battery >= 0.20f && new.battery < 0.20f) {
                    out += PhoneEvent.batteryLow
                }
            }
            return out
        }

        fun networkEvents(old: Snapshot, new: Snapshot): List<PhoneEvent> {
            if (old.online && !new.online) return listOf(PhoneEvent.offline)
            if (!old.online && new.online) return listOf(if (new.wifi) PhoneEvent.wifiConnected else PhoneEvent.backOnline)
            if (!old.wifi && new.wifi) return listOf(PhoneEvent.wifiConnected)
            if (old.wifi && !new.wifi && new.cellular) return listOf(PhoneEvent.cellular)
            return emptyList()
        }

        /**
         * Morning and night follow the slime's sleep schedule; a new hour only
         * counts while you're watching.
         */
        fun clockEvents(
            since: Instant?,
            now: Instant,
            schedule: SleepSchedule,
            foreground: Boolean,
            calendar: SlimyCalendar,
            firedToday: (PhoneEvent) -> Boolean,
        ): List<PhoneEvent> {
            val hour = calendar.hour(now)
            val out = mutableListOf<PhoneEvent>()
            if (hour >= schedule.wake && hour < schedule.wake + 3 && !firedToday(PhoneEvent.goodMorning)) {
                out += PhoneEvent.goodMorning
            }
            if (hour == schedule.bedtime && !firedToday(PhoneEvent.goodNight)) {
                out += PhoneEvent.goodNight
            }
            if (since != null && foreground && calendar.hour(since) != hour && now.timeIntervalSince(since) < 3600) {
                out += PhoneEvent.newHour
            }
            return out
        }
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

/**
 * Runs while the phone charges (WorkManager's charging constraint): the
 * Android side of iOS's BGProcessingTask. Ages the slime, notices the
 * charger and updates the notification.
 */
class PowerCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? SlimyApplication ?: return Result.success()
        if (!app.store.state.value.hatched) return Result.success()
        withContext(Dispatchers.Main) { app.store.tick() }
        app.phoneMonitor.checkInBackground()
        app.island.ensureRunning()
        return Result.success()
    }
}
