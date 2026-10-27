package de.hyprnode.slimy.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.hyprnode.slimy.SlimyApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * After a restart (or an app update) the slime notification is gone and
 * reminders need planning again: brings the notification back if "Always
 * bring it back" is on, reschedules, and keeps the background runs going.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {}
            else -> return
        }
        val app = context.applicationContext as? SlimyApplication ?: return
        val pending = goAsync()
        scope.launch {
            try {
                BackgroundRefresh.schedule(app)
                PhoneMonitor.schedulePowerCheck(app)
                val store = app.store
                if (store.state.value.hatched) {
                    store.tick()
                    app.island.ensureRunning()
                }
                Notifier.reschedule(store.state.value)
                SlimeWidgets.reloadAll(app)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
