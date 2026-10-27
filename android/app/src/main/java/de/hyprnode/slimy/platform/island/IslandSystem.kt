package de.hyprnode.slimy.platform.island

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.WindowInsets
import de.hyprnode.slimy.ui.screens.common.startSafely

/** What Android allows the island, and the way to the system page that allows it. */
object IslandSystem {
    /**
     * Whether this phone lets apps show things over other apps at all. Android
     * Go phones (low-RAM devices from Android 10 on) don't.
     */
    fun overlaySupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val manager = context.getSystemService(ActivityManager::class.java)
            if (manager?.isLowRamDevice == true) return false
        }
        return true
    }

    /**
     * Whether Slimy may show the island over other apps ("Display over other
     * apps" / "Appear on top"). Android 8.0 sometimes answers false right
     * after it was allowed; the app-ops check catches that.
     */
    fun canDrawOverlays(context: Context): Boolean {
        if (Settings.canDrawOverlays(context)) return true
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O_MR1) return false
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        val mode = runCatching {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, Process.myUid(), context.packageName)
        }.getOrDefault(AppOpsManager.MODE_ERRORED)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** The system page where the person allows Slimy over other apps. */
    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    /** Opens that page (or the app's details if a phone has no such page). */
    fun openPermissionPage(context: Context) {
        if (!startSafely(context, permissionIntent(context))) {
            startSafely(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                || startSafely(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }
    }

    /**
     * Calls [onChange] when the person allows or takes back "over other apps"
     * in the system settings. Returns the way to stop listening.
     */
    fun watchPermission(context: Context, onChange: () -> Unit): () -> Unit {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return {}
        val listener = AppOpsManager.OnOpChangedListener { op, pkg ->
            if (pkg == context.packageName && op == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW) onChange()
        }
        return try {
            ops.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, context.packageName, listener)
            ({ runCatching { ops.stopWatchingMode(listener) } })
        } catch (e: Exception) {
            {}
        }
    }

    /**
     * The screen as an overlay window covering all of it sees it: size, the
     * status bar's height and the camera cutouts, from that window's insets.
     */
    fun screen(context: Context, insets: WindowInsets?, width: Int, height: Int): IslandScreen {
        val density = context.resources.displayMetrics.density
        var statusBar = 0
        val cutouts = mutableListOf<PxRect>()
        if (insets != null) {
            statusBar = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
            } else {
                @Suppress("DEPRECATION")
                insets.stableInsetTop
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                insets.displayCutout?.boundingRects?.forEach { r ->
                    cutouts += PxRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
                }
            }
        }
        if (statusBar <= 0) statusBar = statusBarHeight(context)
        return IslandScreen(width, height, density, statusBar, cutouts)
    }

    /**
     * Whether the status bar is on screen. A full-screen video or game hides
     * it, and the island goes with it.
     */
    fun statusBarVisible(insets: WindowInsets?): Boolean {
        if (insets == null) return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.isVisible(WindowInsets.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetTop > 0 || insets.stableInsetTop == 0
        }
    }

    /** The status bar's usual height, from the system's resources. */
    fun statusBarHeight(context: Context): Int {
        @Suppress("DiscouragedApi", "InternalInsetResource")
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else (24 * context.resources.displayMetrics.density).toInt()
    }
}
