package de.hyprnode.slimy.platform.island

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import de.hyprnode.slimy.SlimyApplication
import de.hyprnode.slimy.art.rememberClock
import de.hyprnode.slimy.core.Feedback
import de.hyprnode.slimy.model.Need
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.platform.IslandActionReceiver
import de.hyprnode.slimy.platform.SlimeNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The island's windows, run by `IslandService`.
 *
 * Two overlay windows (`TYPE_APPLICATION_OVERLAY`):
 * - a tiny helper that is invisible (alpha 0) and never touchable. It only
 *   listens to window insets: where the camera cutout is, how tall the
 *   status bar is, and whether a full-screen app has hidden it. It covers
 *   nothing another app shows, so no app sees its touches as obscured;
 * - the island itself, a ComposeView in a window exactly as big as the
 *   compact pill (plus a sliver below the status bar so a tap lands), so
 *   touches anywhere else go to the app below. It grows to the expanded
 *   panel's size only while open.
 *
 * The island shows while the screen is on and unlocked, the phone is
 * upright, the status bar is visible and Slimy itself isn't on screen
 * (the Dynamic Island hides an app's own Live Activity too), unless the
 * position screen asks for a preview. Hidden means removed: nothing draws
 * or animates then.
 */
internal class IslandOverlay(private val base: Context, private val app: SlimyApplication) {
    private val context: Context = windowContext(base)
    private val wm: WindowManager = context.getSystemService(WindowManager::class.java)
    private val controller get() = app.island
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owner = OverlayOwner()
    private val tracker = IslandNewsTracker()

    // What decides whether the island shows.
    private val screen = MutableStateFlow<IslandScreen?>(null)
    private val statusBarShown = MutableStateFlow(true)
    private val interactive = MutableStateFlow(true)
    private val unlocked = MutableStateFlow(true)

    // What the island's composition reads.
    private var mode by mutableStateOf(IslandMode.compact)
    private var placement by mutableStateOf<IslandPlacement?>(null)
    private var window by mutableStateOf(PxRect(0f, 0f, 1f, 1f))

    private var windowMode = IslandMode.compact
    private var helper: View? = null
    private var frame: FrameLayout? = null
    private var attached = false

    /** Everything says the island should show; it does as soon as there is a placement. */
    private var wantShown = false
    private var started = false
    private var collapseJob: Job? = null
    private var receiver: BroadcastReceiver? = null

    private val params = WindowManager.LayoutParams(
        1, 1,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        title = "Slimy island"
        windowAnimations = 0
        overlayWindowDefaults()
    }

    fun start() {
        if (started) return
        started = true
        readPower()
        registerScreenReceiver()
        addHelper()

        // Placement follows the screen and the person's fine-tuning.
        scope.launch {
            combine(screen.filterNotNull(), controller.tuning) { s, t ->
                IslandPlacement.of(s, t, IslandMetrics.expandedBody, IslandMetrics.menuBody)
            }.distinctUntilChanged().collect { p ->
                placement = p
                if (attached) applyWindow(windowMode) else if (wantShown) showIsland()
            }
        }

        // Shown or hidden.
        val appInFront = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
            .map { it.isAtLeast(Lifecycle.State.STARTED) }
            .distinctUntilChanged()
        val hatched = app.store.state.map { it.hatched }.distinctUntilChanged()
        // Why the island is hidden, in words for logcat; null when it may show.
        val device = combine(screen, statusBarShown, interactive, unlocked) { s, bar, on, open ->
            when {
                s == null -> "screen not measured yet"
                !s.isPortrait -> "landscape"
                !bar -> "a full-screen app hides the status bar"
                !on -> "screen off"
                !open -> "phone locked"
                else -> null
            }
        }
        val wanted = combine(controller.showIsland, controller.previewing, appInFront, hatched) { show, preview, front, hatch ->
            when {
                !show -> "turned off in Settings"
                !hatch -> "no slime yet"
                front && !preview -> "Slimy is open (the island steps aside in its own app)"
                else -> null
            }
        }
        scope.launch {
            combine(device, wanted) { a, b -> b ?: a }.distinctUntilChanged().collect { hiddenBecause ->
                val show = hiddenBecause == null
                note(if (show) "Island shown" else "Island hidden: $hiddenBecause")
                wantShown = show
                if (show) showIsland() else hideIsland()
            }
        }

        // News that would pop up as a notification opens the island instead.
        scope.launch {
            controller.alerts.collect {
                if (attached && mode == IslandMode.compact) {
                    Feedback.shared.play(Sound.pop)
                    open(IslandMode.expanded, IslandMetrics.alertMillis)
                }
            }
        }
    }

    fun stop() {
        hideIsland()
        helper?.let { runCatching { wm.removeViewImmediate(it) } }
        helper = null
        receiver?.let { runCatching { base.unregisterReceiver(it) } }
        receiver = null
        owner.destroy()
        scope.cancel()
        started = false
    }

    /** The phone turned or changed size: the helper's insets bring the new screen. */
    fun configurationChanged() {
        helper?.requestApplyInsets()
        helper?.post { readScreen(helper?.rootWindowInsets) }
    }

    /** Whether the island is on screen right now. */
    val isShowing: Boolean get() = attached

    // MARK: Windows

    /**
     * The helper never covers what other apps show: from Android 11 it is a
     * single pixel (the window's insets say whether the status bar is
     * visible; the screen and its cutout come from the window metrics).
     * Before that it is a strip as tall as the status bar, which the status
     * bar covers anyway, so it can read the cutout from its insets.
     */
    private fun addHelper() {
        val view = View(context)
        view.setOnApplyWindowInsetsListener { v, insets ->
            readScreen(insets)
            v.onApplyWindowInsets(insets)
        }
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        if (!modern) {
            @Suppress("DEPRECATION")
            view.setOnSystemUiVisibilityChangeListener { flags ->
                statusBarShown.value = flags and View.SYSTEM_UI_FLAG_FULLSCREEN == 0
            }
        }
        val p = WindowManager.LayoutParams(
            if (modern) 1 else WindowManager.LayoutParams.MATCH_PARENT,
            if (modern) 1 else IslandSystem.statusBarHeight(context).coerceAtLeast(1),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            title = "Slimy island insets"
            // Invisible and untouchable: touches pass straight through it.
            alpha = 0f
            overlayWindowDefaults()
        }
        try {
            wm.addView(view, p)
            helper = view
        } catch (e: Exception) {
            note("Helper window refused: $e")
            controller.overlayFailed("Android didn't let Slimy show the island.")
        }
    }

    private fun readScreen(insets: android.view.WindowInsets?) {
        if (insets == null) return
        val (w, h) = screenSize()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // The whole display's insets: its cutout and status bar, in screen pixels.
            screen.value = IslandSystem.screen(context, wm.currentWindowMetrics.windowInsets, w, h)
            statusBarShown.value = IslandSystem.statusBarVisible(insets)
        } else {
            screen.value = IslandSystem.screen(context, insets, w, h)
        }
    }

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p.x to p.y
        }

    private fun showIsland() {
        if (attached || placement == null) return
        if (!IslandSystem.canDrawOverlays(context)) {
            note("Island not shown: \"Display over other apps\" is off for Slimy")
            controller.overlayFailed("Slimy isn't allowed over other apps anymore.")
            return
        }
        val view = frame ?: createIslandView().also { frame = it }
        mode = IslandMode.compact
        applyWindow(IslandMode.compact, update = false)
        try {
            wm.addView(view, params)
            attached = true
            owner.resume()
            controller.overlayShowing(true)
        } catch (e: Exception) {
            note("Island window refused: $e")
            controller.overlayFailed("Android didn't let Slimy show the island.")
        }
    }

    private fun hideIsland() {
        collapseJob?.cancel()
        if (!attached) return
        mode = IslandMode.compact
        owner.stop()
        frame?.let { runCatching { wm.removeViewImmediate(it) } }
        attached = false
        controller.overlayShowing(false)
    }

    /** Sizes the island's window for [m]; the composition draws relative to [window]. */
    private fun applyWindow(m: IslandMode, update: Boolean = true) {
        val p = placement ?: return
        windowMode = m
        val r = p.window(m)
        params.x = r.left.toInt()
        params.y = r.top.toInt()
        params.width = r.width.toInt().coerceAtLeast(1)
        params.height = r.height.toInt().coerceAtLeast(1)
        window = r
        if (update && attached) frame?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    private fun createIslandView(): FrameLayout {
        val root = TouchFrame(context) { event ->
            when (event.actionMasked) {
                // A touch anywhere else on the screen closes an open island (and still reaches its app).
                MotionEvent.ACTION_OUTSIDE -> {
                    if (mode != IslandMode.compact) collapse()
                    true
                }
                MotionEvent.ACTION_DOWN -> {
                    if (mode != IslandMode.compact) scheduleCollapse(IslandMetrics.autoCollapseMillis)
                    false
                }
                else -> false
            }
        }
        root.setViewTreeLifecycleOwner(owner)
        root.setViewTreeSavedStateRegistryOwner(owner)
        root.setViewTreeViewModelStoreOwner(owner)
        root.clipChildren = false
        val compose = ComposeView(context).apply {
            // Not plain Content(): inside apply it would be ComposeView.Content() itself, an endless loop.
            setContent { IslandContent() }
        }
        root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return root
    }

    @Composable
    private fun IslandContent() {
        val game by app.store.state.collectAsState()
        val moment by controller.moment.collectAsState()
        val animated by controller.animated.collectAsState()
        val clock by rememberClock(1000L)
        var news by remember { mutableStateOf<IslandNews?>(null) }
        LaunchedEffect(game) { news = tracker.update(game, Instant.now()) }
        val now = Instant.ofEpochMilli(clock)
        val friends = remember(game, moment) { controller.islandFriends() }
        val m = moment?.takeIf { it.until > now }
        val scene = IslandScene.of(game, now, friends, m?.kind, m?.friend, animated, news)
        val p = placement ?: return
        IslandTextScale {
            IslandView(
                scene = scene,
                placement = p,
                mode = mode,
                window = window,
                animated = animated,
                actions = actions,
            )
        }
    }

    // MARK: Open and close

    private val actions = IslandActions(
        onTap = {
            Feedback.shared.tapHaptic()
            open(IslandMode.expanded, IslandMetrics.autoCollapseMillis)
        },
        onLongPress = {
            Feedback.shared.tapHaptic()
            open(IslandMode.menu, IslandMetrics.autoCollapseMillis)
        },
        onCollapse = { collapse() },
        onCare = { need -> care(need) },
        onOpenApp = {
            collapse()
            openApp("home")
        },
        onHide = {
            collapse()
            Toast.makeText(app, "Island hidden. Turn it back on in Slimy's Settings.", Toast.LENGTH_LONG).show()
            controller.setShowIsland(false)
        },
        onSettled = { settled ->
            // Shrinks back to the pill's window only once the close animation is over.
            if (settled == IslandMode.compact && mode == IslandMode.compact && windowMode != IslandMode.compact) {
                applyWindow(IslandMode.compact)
            }
        },
    )

    private fun open(m: IslandMode, closeAfter: Long) {
        if (!attached) return
        applyWindow(m)
        mode = m
        scheduleCollapse(closeAfter)
    }

    private fun collapse() {
        collapseJob?.cancel()
        mode = IslandMode.compact
    }

    private fun scheduleCollapse(after: Long) {
        collapseJob?.cancel()
        collapseJob = scope.launch {
            delay(after)
            collapse()
        }
    }

    /** A need's button: looks after the slime right here, like the notification's buttons. Play opens the arcade. */
    private fun care(need: Need) {
        Feedback.shared.tapHaptic()
        scheduleCollapse(IslandMetrics.autoCollapseMillis)
        val action = when (need) {
            Need.hunger -> IslandActionReceiver.ACTION_FEED
            Need.`fun` -> {
                collapse()
                openApp("arcade")
                return
            }
            Need.energy -> IslandActionReceiver.ACTION_SLEEP
            Need.clean -> IslandActionReceiver.ACTION_BATH
            Need.love -> IslandActionReceiver.ACTION_PET
        }
        scope.launch {
            IslandActionReceiver.perform(app, action)
            controller.settle()
        }
    }

    private fun openApp(place: String) {
        val intent = SlimeNotification.openApp(app, place, 20 + place.length)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // The island was just touched and is on screen: allowed to bring Slimy up.
                val options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                intent.send(context, 0, null, null, null, null, options.toBundle())
            } else {
                intent.send()
            }
        } catch (e: Exception) {
            note("Couldn't open Slimy: $e")
        }
    }

    // MARK: Screen on, off and locked

    private fun readPower() {
        val power = base.getSystemService(PowerManager::class.java)
        val keyguard = base.getSystemService(KeyguardManager::class.java)
        interactive.value = power?.isInteractive ?: true
        unlocked.value = keyguard?.isKeyguardLocked != true
    }

    private fun registerScreenReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        interactive.value = false
                        unlocked.value = false
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        interactive.value = true
                        unlocked.value = true
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        readPower()
                        // The keyguard may settle a moment after the screen comes on.
                        scope.launch {
                            delay(600)
                            readPower()
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(base, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r
    }

    private fun note(line: String) {
        Log.i("SlimyIsland", line)
    }

    /** A frame that sees every touch first, for ACTION_OUTSIDE and the auto-close timer. */
    private class TouchFrame(context: Context, private val onEvent: (MotionEvent) -> Boolean) : FrameLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (onEvent(ev)) return true
            return super.dispatchTouchEvent(ev)
        }
    }

    companion object {
        /**
         * The context overlay windows are made with: from Android 11 a window
         * context on the default display (a service has no display of its own).
         */
        fun windowContext(base: Context): Context {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return base
            return try {
                val display = base.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                base.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } catch (e: Exception) {
                base
            }
        }
    }
}

/** Overlay windows may cover the camera cutout and aren't pushed below the system bars. */
private fun WindowManager.LayoutParams.overlayWindowDefaults() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
}
