package de.hyprnode.slimy

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import de.hyprnode.slimy.platform.Notifier
import de.hyprnode.slimy.platform.PhoneMonitor
import de.hyprnode.slimy.ui.AppRoot
import de.hyprnode.slimy.ui.EXTRA_OPEN
import de.hyprnode.slimy.ui.EXTRA_OPEN_VALUES
import de.hyprnode.slimy.ui.theme.SlimyTheme
import kotlinx.coroutines.launch

/**
 * The one activity: the app's UI ([AppRoot]) and the work the iPhone app
 * does when its scene becomes active or goes to the background
 * (App/SlimyApp.swift). `SlimyApplication` already greets the slime, ticks
 * the world, refreshes today's plan and plays the music; this adds the parts
 * that belong to the app being on screen: phone reactions, the slime
 * notification, friends and caring together.
 */
class MainActivity : ComponentActivity() {
    /** The place an intent asked to open ([EXTRA_OPEN] or a link), until `AppRoot` has opened it. */
    private var openRequest by mutableStateOf<String?>(null)

    private val app: SlimyApplication get() = application as SlimyApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity already handled its intent.
        if (savedInstanceState == null) openRequest = openRequest(intent)
        setContent {
            SlimyTheme {
                AppRoot(openRequest, onOpenHandled = { openRequest = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequest(intent)?.let { openRequest = it }
    }

    override fun onStart() {
        super.onStart()
        becameActive()
    }

    override fun onStop() {
        super.onStop()
        // Stops syncing the shared slime and sends what changed.
        app.friends.enterBackground()
        PhoneMonitor.schedulePowerCheck(this)
    }

    /** The app is on screen: the moment to renew the slime notification and catch up with friends. */
    private fun becameActive() {
        val store = app.store
        Notifier.reschedule(store.state.value)
        app.phoneMonitor.start()
        // While Slimy is on screen, a slime cared for together syncs with the partner's phone every 20 seconds.
        app.friends.startCarePolling()
        lifecycleScope.launch {
            if (store.state.value.hatched) app.island.ensureRunning()
            app.friends.refresh()
            app.island.maybeMeetup()
        }
    }

    /**
     * What an intent asks to open: its [EXTRA_OPEN] value, "friends" for an
     * invite link (the code waits in `FriendsStore.pendingInviteCode`), or
     * the place a `slimy://<place>` link names.
     */
    private fun openRequest(intent: Intent?): String? {
        if (intent == null) return null
        intent.getStringExtra(EXTRA_OPEN)?.let { if (it in EXTRA_OPEN_VALUES) return it }
        val link = intent.data ?: return null
        app.store.tick()
        if (app.friends.openInvite(link)) return "friends"
        if (link.scheme == "slimy") link.host?.lowercase()?.takeIf { it in EXTRA_OPEN_VALUES }?.let { return it }
        return null
    }
}
