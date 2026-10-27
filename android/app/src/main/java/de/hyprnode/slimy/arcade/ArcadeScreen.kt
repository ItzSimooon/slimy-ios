package de.hyprnode.slimy.arcade

import androidx.compose.runtime.Composable
import de.hyprnode.slimy.ui.kit.SheetScaffold

/** The arcade: today's game and the game list (App/Arcade/ArcadeView.swift). */
// Implemented by the arcade worker.
@Composable
fun ArcadeScreen(onClose: () -> Unit) {
    SheetScaffold(title = "Arcade", onDone = onClose) { _ -> }
}

/** Bath time (App/UI/Screens/BathView.swift). */
// Implemented by the arcade worker.
@Composable
fun BathScreen(onClose: () -> Unit) {
    SheetScaffold(title = "Bath", onDone = onClose) { _ -> }
}
