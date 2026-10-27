package de.hyprnode.slimy.platform

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import de.hyprnode.slimy.MainActivity
import de.hyprnode.slimy.SlimyApplication
import de.hyprnode.slimy.ui.EXTRA_OPEN
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The invisible trampoline behind the launcher shortcuts that don't open the
 * app: Feed and Summon Slime (App Shortcuts on iOS). Does the job, says what
 * happened in a toast, and is gone before it is seen. Summon brings the
 * island back when it is on and allowed, else the notification. Without a
 * slime yet, or with notifications off and no island, it opens the app.
 */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SlimyApplication
        val action = intent?.action
        if (!app.store.state.value.hatched) {
            open("home")
        } else {
            when (action) {
                IslandActionReceiver.ACTION_FEED -> scope.launch {
                    val line = IslandActionReceiver.perform(app, action)
                    line?.let { Toast.makeText(app, it, Toast.LENGTH_SHORT).show() }
                    app.island.settle()
                }
                IslandActionReceiver.ACTION_SUMMON -> {
                    if (app.island.wantsOverlay) {
                        // Started here, while this activity is in front: Android allows the island's service now.
                        app.island.startOverlayNow()
                        scope.launch {
                            IslandActionReceiver.perform(app, action)
                            Toast.makeText(app, "${app.store.slime.name} is in the island.", Toast.LENGTH_SHORT).show()
                        }
                    } else if (!Notifier.authorized() || !Notifier.isChannelOn(Notifier.slimeChannel)) {
                        Toast.makeText(app, "Notifications are off for Slimy.", Toast.LENGTH_SHORT).show()
                        open("settings")
                    } else {
                        scope.launch {
                            IslandActionReceiver.perform(app, action)
                            Toast.makeText(app, "${app.store.slime.name} is in your notifications.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                else -> open("home")
            }
        }
        finish()
    }

    private fun open(place: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_OPEN, place)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
