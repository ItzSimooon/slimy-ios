package de.hyprnode.slimy.core

import androidx.compose.runtime.staticCompositionLocalOf
import de.hyprnode.slimy.online.ChatStore
import de.hyprnode.slimy.online.FriendsStore
import de.hyprnode.slimy.platform.IslandController

/** The app's one `GameStore`, provided at the root of the UI (the counterpart of `@EnvironmentObject var store`). */
val LocalGameStore = staticCompositionLocalOf<GameStore> {
    error("No GameStore provided. Wrap the UI in CompositionLocalProvider(LocalGameStore provides store).")
}

/** Today's plan, provided next to the store. */
val LocalDailyStore = staticCompositionLocalOf<DailyStore> {
    error("No DailyStore provided. Wrap the UI in CompositionLocalProvider(LocalDailyStore provides daily).")
}

/** Friends, challenges and caring together (`@EnvironmentObject var friends: FriendsStore`), provided by `AppRoot`. */
val LocalFriendsStore = staticCompositionLocalOf<FriendsStore> {
    error("No FriendsStore provided. Wrap the UI in CompositionLocalProvider(LocalFriendsStore provides friends).")
}

/** Talking to the slime (`ChatStore.shared`), provided by `AppRoot`. */
val LocalChatStore = staticCompositionLocalOf<ChatStore> {
    error("No ChatStore provided. Wrap the UI in CompositionLocalProvider(LocalChatStore provides chat).")
}

/** The slime notification (`IslandController.shared`), provided by `AppRoot`. */
val LocalIsland = staticCompositionLocalOf<IslandController> {
    error("No IslandController provided. Wrap the UI in CompositionLocalProvider(LocalIsland provides island).")
}
