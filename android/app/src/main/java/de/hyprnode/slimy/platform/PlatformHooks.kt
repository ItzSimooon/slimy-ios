package de.hyprnode.slimy.platform

import de.hyprnode.slimy.SlimyApplication
import de.hyprnode.slimy.core.GameStore
import de.hyprnode.slimy.model.GameState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * What the game tells the rest of the phone after a change: the slime
 * notification updates (or goes away with the slime), reminders are planned
 * again and the widgets redraw. Registered in `SlimyApplication.onCreate`.
 */
class PlatformHooks(private val app: SlimyApplication) : GameStore.Hooks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun syncIsland(state: GameState) {
        scope.launch { app.island.sync() }
    }

    override fun stopIsland() {
        scope.launch { app.island.stop() }
    }

    override fun reschedule(state: GameState) {
        Notifier.reschedule(state)
    }

    override fun reloadWidgets(state: GameState) {
        SlimeWidgets.reloadAll(app)
    }
}
