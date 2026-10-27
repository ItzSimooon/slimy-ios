package de.hyprnode.slimy

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import de.hyprnode.slimy.core.DailyStore
import de.hyprnode.slimy.core.Feedback
import de.hyprnode.slimy.core.GameStore
import de.hyprnode.slimy.model.SlimeSprites
import de.hyprnode.slimy.online.ChatStore
import de.hyprnode.slimy.online.FriendsStore
import de.hyprnode.slimy.platform.BackgroundRefresh
import de.hyprnode.slimy.platform.IslandController
import de.hyprnode.slimy.platform.Notifier
import de.hyprnode.slimy.platform.PhoneMonitor
import de.hyprnode.slimy.platform.PlatformHooks
import de.hyprnode.slimy.platform.SlimeWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-wide setup: the stores live here so the activity, widgets, workers
 * and notification actions all share one game.
 *
 * The clock follows the iPhone app: coming to the front greets you
 * (`arrive`), refreshes today's plan, starts the music, plans reminders,
 * watches the phone, brings the slime notification up and checks on
 * friends; while in front the world ticks once a minute; going to the
 * background stops the music, settles the notification, asks for a check
 * while charging and writes the save to disk.
 */
class SlimyApplication : Application() {
    /** The one game. Also reachable in Compose as `LocalGameStore.current`. */
    lateinit var store: GameStore
        private set

    /** Today's plan. Also reachable in Compose as `LocalDailyStore.current`. */
    lateinit var dailyStore: DailyStore
        private set

    /** Friends, challenges and caring together. Also `LocalFriendsStore.current`. Created on first use. */
    val friends: FriendsStore by lazy { FriendsStore(this, store) }

    /** Talking to the slime. Also `LocalChatStore.current`. Created on first use. */
    val chat: ChatStore by lazy { ChatStore(this, store, friends) }

    /** The slime notification (the Dynamic Island counterpart). Also `LocalIsland.current`. Created on first use. */
    val island: IslandController by lazy { IslandController(this, store, friends) }

    /** Reactions to the phone (battery, charger, network). Created on first use. */
    val phoneMonitor: PhoneMonitor by lazy { PhoneMonitor(this, store, island) }

    /** Whether the app is on screen right now. */
    @Volatile
    var isInForeground: Boolean = false
        private set

    /** For work that outlives a screen (on the main thread). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val main = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            if (!isInForeground) return
            store.tick()
            main.postDelayed(this, tickInterval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        installSprites()
        store = GameStore(this)
        dailyStore = DailyStore(this, store)
        Feedback.install(this) { store.state.value.settings }
        Notifier.install(this)
        // The slime notification, reminders and widgets follow every change.
        store.addHooks(PlatformHooks(this))
        BackgroundRefresh.schedule(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = becameActive()
            override fun onStop(owner: LifecycleOwner) = enteredBackground()
        })
    }

    /** The pixel art, from the same sprites.json as the iPhone app. */
    private fun installSprites() {
        try {
            SlimeSprites.install(SlimeSprites.parse(assets.open("sprites.json")))
        } catch (e: Exception) {
            Log.e("Slimy", "Couldn't load sprites.json", e)
        }
    }

    private fun becameActive() {
        isInForeground = true
        store.arrive()
        dailyStore.refresh()
        Feedback.shared.updateMusic(active = true)
        Notifier.reschedule(store.state.value)
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, tickInterval)
        phoneMonitor.start()
        // While Slimy is on screen, a slime cared for together syncs with the partner's phone.
        friends.startCarePolling()
        SlimeWidgets.reloadAll(this)
        scope.launch {
            if (store.state.value.hatched) island.ensureRunning()
            runCatching { friends.refresh() }.onFailure { Log.w("Slimy", "Friends refresh failed", it) }
            dailyStore.refresh()
            island.maybeMeetup()
        }
    }

    private fun enteredBackground() {
        isInForeground = false
        main.removeCallbacks(ticker)
        Feedback.shared.updateMusic(active = false)
        // Stops syncing the shared slime.
        friends.stopCarePolling()
        phoneMonitor.stop()
        PhoneMonitor.schedulePowerCheck(this)
        BackgroundRefresh.schedule(this)
        store.flush()
        // The notification stops moving and settles on the slime's still pose.
        scope.launch { island.settle() }
    }

    private companion object {
        /** How often the world moves on while the app is in front. */
        const val tickInterval = 60_000L
    }
}
