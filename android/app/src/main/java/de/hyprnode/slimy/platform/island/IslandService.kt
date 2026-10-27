package de.hyprnode.slimy.platform.island

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import de.hyprnode.slimy.SlimyApplication
import de.hyprnode.slimy.platform.SlimeNotification

/**
 * Keeps the island on screen while Slimy is closed: a foreground service of
 * the special-use type whose notification is the slime notification itself
 * (one notification, not two). It owns the overlay windows ([IslandOverlay])
 * and nothing else; `IslandController` decides when it runs.
 *
 * Android only lets a foreground service start from the foreground or from
 * moments it allows (after a restart, from a notification button, a
 * shortcut). Where it isn't allowed, `IslandController` keeps the slime in
 * the plain notification instead.
 */
class IslandService : Service() {
    private var overlay: IslandOverlay? = null
    private val app: SlimyApplication get() = application as SlimyApplication

    override fun onCreate() {
        super.onCreate()
        current = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start must go to the foreground first, even one that ends right away.
        if (!goForeground()) {
            app.island.overlayFailed(null)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!app.island.wantsOverlay) {
            shutDown(keepNotification = true)
            return START_NOT_STICKY
        }
        val o = overlay ?: IslandOverlay(this, app).also { overlay = it }
        o.start()
        app.island.overlayStarted()
        return START_STICKY
    }

    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, SlimeNotification.id, app.island.foregroundNotification(), type)
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException (Android 12+, started from the background),
        // a missing permission or type: the notification takes over.
        Log.d("SlimyIsland", "Couldn't go to the foreground: $e")
        false
    }

    /** Takes the island down; the notification stays as a plain one when [keepNotification]. */
    fun shutDown(keepNotification: Boolean) {
        overlay?.stop()
        overlay = null
        ServiceCompat.stopForeground(
            this,
            if (keepNotification) ServiceCompat.STOP_FOREGROUND_DETACH else ServiceCompat.STOP_FOREGROUND_REMOVE,
        )
        stopSelf()
    }

    /** Whether the island's window is on screen right now. */
    val isShowing: Boolean get() = overlay?.isShowing == true

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.configurationChanged()
    }

    override fun onDestroy() {
        overlay?.stop()
        overlay = null
        if (current === this) current = null
        app.island.overlayStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** The running service, if any (same process, main thread only). */
        @Volatile
        internal var current: IslandService? = null
            private set
    }
}
