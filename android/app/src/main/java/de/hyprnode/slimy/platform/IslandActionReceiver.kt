package de.hyprnode.slimy.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.hyprnode.slimy.SlimyApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The slime notification's buttons: Feed, Play, Pet, Bath and Sleep look
 * after the slime without opening the app (the port of the Live Activity
 * intents in Shared/Activity/SlimeIntents.swift). Every press is also a
 * chance to notice what the phone did meanwhile. Play in the notification
 * itself opens the arcade instead; the Play broadcast is a quick round for
 * watches.
 */
class IslandActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? SlimyApplication ?: return
        val action = intent.action ?: return
        if (action == ACTION_DISMISSED) {
            app.island.noteDismissed()
            return
        }
        val pending = goAsync()
        scope.launch {
            try {
                perform(app, action)
            } finally {
                pending.finish()
            }
            // The reaction plays for a few seconds; settling doesn't hold the broadcast.
            app.island.settle()
        }
    }

    companion object {
        const val ACTION_FEED = "de.hyprnode.slimy.action.FEED"
        const val ACTION_PLAY = "de.hyprnode.slimy.action.PLAY"
        const val ACTION_PET = "de.hyprnode.slimy.action.PET"
        const val ACTION_BATH = "de.hyprnode.slimy.action.BATH"
        const val ACTION_SLEEP = "de.hyprnode.slimy.action.SLEEP"
        const val ACTION_SUMMON = "de.hyprnode.slimy.action.SUMMON"
        const val ACTION_DISMISSED = "de.hyprnode.slimy.action.DISMISSED"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        /** A distinct request code per action, so their PendingIntents don't replace each other. */
        fun requestCode(action: String): Int = when (action) {
            ACTION_FEED -> 1
            ACTION_PLAY -> 2
            ACTION_PET -> 3
            ACTION_BATH -> 4
            ACTION_SLEEP -> 5
            ACTION_SUMMON -> 6
            ACTION_DISMISSED -> 7
            else -> 0
        }

        /**
         * Does what a button, widget or shortcut asked. Returns the slime's
         * answer (the outcome's line), if it has one.
         */
        suspend fun perform(app: SlimyApplication, action: String): String? {
            val line = withContext(Dispatchers.Main) {
                val store = app.store
                if (!store.state.value.hatched) return@withContext null
                when (action) {
                    ACTION_FEED -> store.quickFeed().line
                    ACTION_PLAY -> {
                        store.quickPlay()
                        null
                    }
                    ACTION_PET -> store.pet().line
                    ACTION_BATH -> store.bath().line
                    ACTION_SLEEP -> store.toggleSleep().line
                    ACTION_SUMMON -> {
                        app.island.ensureRunning(restart = true)
                        null
                    }
                    else -> null
                }
            }
            app.phoneMonitor.checkInBackground()
            return line?.ifEmpty { null }
        }
    }
}
