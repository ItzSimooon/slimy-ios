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
import de.hyprnode.slimy.ui.AppRoot
import de.hyprnode.slimy.ui.EXTRA_OPEN
import de.hyprnode.slimy.ui.EXTRA_OPEN_VALUES
import de.hyprnode.slimy.ui.theme.SlimyTheme

/**
 * The one activity: the app's UI ([AppRoot]) and the places intents ask it to
 * open. Coming to the front and going to the background (App/SlimyApp.swift)
 * is handled once for the whole process in `SlimyApplication`.
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
