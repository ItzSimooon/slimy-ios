package de.hyprnode.slimy.ui.screens.care

import androidx.compose.runtime.Composable
import de.hyprnode.slimy.ui.kit.SheetScaffold

/** Caring for one slime together: codes, joining, the partner's activity (App/UI/Screens/CareTogetherView.swift). */
// Implemented by the online/platform worker.
@Composable
fun CareTogetherScreen(onClose: () -> Unit) {
    SheetScaffold(title = "Care Together", onDone = onClose) { _ -> }
}
