package de.hyprnode.slimy.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.hyprnode.slimy.SlimyApplication
import de.hyprnode.slimy.model.Nudge
import de.hyprnode.slimy.model.nudge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * About every 15 minutes (WorkManager's shortest period; Android decides the
 * real pace): age the slime, check the phone, friends and what a care
 * partner did, update the slime notification (bringing it back if "Always
 * bring it back" is on and it is missing), and let the slime say once that
 * it needs something. The port of `BackgroundRefresh` in App/SlimyApp.swift.
 */
object BackgroundRefresh {
    const val id = "de.hyprnode.slimy.refresh"

    /** Keeps the periodic run scheduled; an existing schedule is left as it is. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).build()
        try {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(id, ExistingPeriodicWorkPolicy.KEEP, request)
        } catch (e: IllegalStateException) {
            Log.w("SlimyRefresh", "WorkManager isn't ready", e)
        }
    }

    suspend fun run(app: SlimyApplication) = withContext(Dispatchers.Main) {
        PhoneMonitor.schedulePowerCheck(app)
        val store = app.store
        if (!store.state.value.hatched) return@withContext
        store.tick()
        app.phoneMonitor.checkInBackground()
        if (isOnline(app)) {
            runCatching { app.friends.refresh() }.onFailure { Log.w("SlimyRefresh", "Friends refresh failed", it) }
            if (store.isSharing) {
                runCatching { app.friends.syncCare() }.onFailure { Log.w("SlimyRefresh", "Care sync failed", it) }
            }
        }
        var nudge: Nudge? = null
        store.update { nudge = it.nudge(Instant.now()) }
        val island = app.island
        // "Always bring it back": the notification returns if it went missing.
        island.ensureRunning()
        island.maybeMeetup()
        val said = nudge
        if (said != null) {
            // Speaks up with a sound only if need reminders are on, outside
            // quiet hours and within the day's limit; otherwise the line just
            // shows in the notification.
            island.sync(AlertConfiguration(store.slime.name, said.line), Notifier.Category.needs)
        } else {
            island.sync()
        }
        Notifier.reschedule(store.state.value)
    }

    private fun isOnline(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return try {
            val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork ?: return false) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: SecurityException) {
            false
        }
    }
}

/** The periodic background run ([BackgroundRefresh]). */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? SlimyApplication ?: return Result.success()
        BackgroundRefresh.run(app)
        return Result.success()
    }
}
