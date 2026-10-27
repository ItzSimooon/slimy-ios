package de.hyprnode.slimy.ui.screens.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.ui.kit.SheetScaffold

/** Talking to the slime, titled with its name (App/UI/Screens/ChatView.swift, ChatConsentView.swift). */
// Implemented by the online/platform worker.
@Composable
fun ChatScreen(onClose: () -> Unit) {
    val state by LocalGameStore.current.state.collectAsStateWithLifecycle()
    SheetScaffold(title = state.slime.name, onDone = onClose, inline = true) { _ -> }
}
