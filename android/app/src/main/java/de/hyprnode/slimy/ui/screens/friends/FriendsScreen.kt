package de.hyprnode.slimy.ui.screens.friends

import androidx.compose.runtime.Composable
import de.hyprnode.slimy.ui.kit.SheetScaffold

/** Friends: requests, the friend list, challenges, the leaderboard (App/UI/Screens/FriendsView.swift). */
// Implemented by the online/platform worker.
@Composable
fun FriendsScreen(onClose: () -> Unit) {
    SheetScaffold(title = "Friends", onDone = onClose) { _ -> }
}

/** Going online: name, Terms and Privacy, consent (App/UI/Screens/GoOnlineView.swift). [onDone] closes it, online or not. */
// Implemented by the online/platform worker.
@Composable
fun GoOnlineScreen(onDone: () -> Unit) {
    SheetScaffold(title = "Go Online", onDone = onDone) { _ -> }
}
