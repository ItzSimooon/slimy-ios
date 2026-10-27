package de.hyprnode.slimy.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import de.hyprnode.slimy.SlimyApplication
import de.hyprnode.slimy.SlimyRoot
import de.hyprnode.slimy.core.LocalChatStore
import de.hyprnode.slimy.core.LocalDailyStore
import de.hyprnode.slimy.core.LocalFriendsStore
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.core.LocalIsland

/**
 * The intent extra (a String) that asks the app to open a place, sent by
 * notifications, widgets, shortcuts and the slime notification's actions.
 * Allowed values ([EXTRA_OPEN_VALUES]): "home", "arcade", "bath", "chat",
 * "friends", "care", "kitchen", "shop", "crates", "journal", "settings".
 * `slimy://<value>` links open the same places; invite links
 * (slimy://f/CODE, https://slime.hyprnode.de/f/CODE) open "friends".
 */
const val EXTRA_OPEN = "slimy.open"

/** The values [EXTRA_OPEN] may carry; anything else is ignored. */
val EXTRA_OPEN_VALUES: Set<String> = setOf(
    "home", "arcade", "bath", "chat", "friends", "care", "kitchen", "shop", "crates", "journal", "settings",
)

/**
 * The app's UI root, called by `MainActivity`: provides every store
 * (`LocalGameStore`, `LocalDailyStore`, `LocalFriendsStore`,
 * `LocalChatStore`, `LocalIsland`) and shows the app.
 *
 * [openRequest] is one of [EXTRA_OPEN_VALUES] (or null): the place an intent
 * asked for. Once it has been opened, [onOpenHandled] clears it, so the same
 * request can come again.
 */
// Navigation is implemented by the navigation worker; for now this shows the home screen.
@Composable
fun AppRoot(openRequest: String?, onOpenHandled: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as SlimyApplication
    val handled by rememberUpdatedState(onOpenHandled)
    CompositionLocalProvider(
        LocalGameStore provides app.store,
        LocalDailyStore provides app.dailyStore,
        LocalFriendsStore provides app.friends,
        LocalChatStore provides app.chat,
        LocalIsland provides app.island,
    ) {
        SlimyRoot(app.store, app.dailyStore)
    }
    LaunchedEffect(openRequest) {
        // Home is all there is so far: every request lands there.
        if (openRequest != null) handled()
    }
}
