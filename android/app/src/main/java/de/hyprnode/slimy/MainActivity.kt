package de.hyprnode.slimy

import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.art.HabitatScene
import de.hyprnode.slimy.art.LiveSlimeView
import de.hyprnode.slimy.art.PixelEgg
import de.hyprnode.slimy.art.rememberReduceMotion
import de.hyprnode.slimy.core.DailyStore
import de.hyprnode.slimy.core.Feedback
import de.hyprnode.slimy.core.GameStore
import de.hyprnode.slimy.core.LocalDailyStore
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.core.Toast
import de.hyprnode.slimy.model.GameState
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.model.isBirthday
import de.hyprnode.slimy.model.line
import de.hyprnode.slimy.model.timeIntervalSince
import de.hyprnode.slimy.ui.kit.AppButton
import de.hyprnode.slimy.ui.kit.Card
import de.hyprnode.slimy.ui.kit.ItemIcon
import de.hyprnode.slimy.ui.kit.SpeechBox
import de.hyprnode.slimy.ui.AppRoot
import de.hyprnode.slimy.ui.EXTRA_OPEN
import de.hyprnode.slimy.ui.EXTRA_OPEN_VALUES
import de.hyprnode.slimy.ui.kit.ToastBanner
import de.hyprnode.slimy.ui.theme.SlimyTheme
import kotlinx.coroutines.delay
import java.time.Instant
import kotlin.math.ceil
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    /** The place an intent asked to open ([EXTRA_OPEN] or a link), until `AppRoot` has opened it. */
    private var openRequest by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity already handled its intent.
        if (savedInstanceState == null) openRequest = openRequest(intent)
        setContent {
            SlimyTheme {
                AppRoot(openRequest, onOpenHandled = { openRequest = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequest(intent)?.let { openRequest = it }
    }

    /**
     * What an intent asks to open: its [EXTRA_OPEN] value, "friends" for an
     * invite link (the code waits in `FriendsStore.pendingInviteCode`), or
     * the place a `slimy://<place>` link names.
     */
    private fun openRequest(intent: Intent?): String? {
        if (intent == null) return null
        intent.getStringExtra(EXTRA_OPEN)?.let { if (it in EXTRA_OPEN_VALUES) return it }
        val link = intent.data ?: return null
        val app = application as SlimyApplication
        app.store.tick()
        if (app.friends.openInvite(link)) return "friends"
        if (link.scheme == "slimy") link.host?.lowercase()?.takeIf { it in EXTRA_OPEN_VALUES }?.let { return it }
        return null
    }
}

/** The app's root: the stores for everything below, the slime at home, toasts and celebrations on top. */
@Composable
fun SlimyRoot(store: GameStore, daily: DailyStore) {
    CompositionLocalProvider(LocalGameStore provides store, LocalDailyStore provides daily) {
        val state by store.state.collectAsStateWithLifecycle()
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (state.hatched) SlimeHome(state) else EggHome(store)
            CelebrationLayer(store)
            ToastLayer(store)
        }
    }
}

/** The slime in its habitat, standing on the floor, saying what it says. It chats while you watch. */
@Composable
private fun SlimeHome(state: GameState) {
    val store = LocalGameStore.current
    val reduceMotion = rememberReduceMotion()
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle().value
        .isAtLeast(Lifecycle.State.RESUMED)
    val now = Instant.now()
    val line = state.line(now)
    ChatterLoop(enabled = resumed && state.settings.chatty)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        // The same grid HabitatScene draws on, so the slime stands on its floor.
        val scale = HabitatScene.scale(widthPx, density.density)
        val rows = maxOf(1, ceil(heightPx / scale).toInt())
        val floorRow = HabitatScene.floorRow(rows)
        val feet = with(density) { ((floorRow + 12) * scale).toDp() }
        val slimeHeight = min(maxWidth * 0.42f, 190.dp)
        // LiveSlimeView is 10% taller than the slime, standing on its bottom edge.
        val slimeFrame = slimeHeight * 1.1f
        val bubbleWidth = min(maxWidth * 0.72f, 320.dp)

        HabitatScene(
            habitat = state.inventory.habitat,
            furniture = state.inventory.furniture,
            date = now,
            modifier = Modifier.fillMaxSize(),
            animate = !reduceMotion && resumed,
            party = state.isBirthday(now),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, (feet - slimeFrame).roundToPx() - 160.dp.roundToPx()) },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom,
        ) {
            Box(Modifier.height(160.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                SpeechBox(text = line, modifier = Modifier.widthIn(max = bubbleWidth))
            }
            // The bubble above already shows what it says; no toast on top.
            LiveSlimeView(height = slimeHeight, showsToast = false)
        }
    }
}

/**
 * Small talk: every 8 to 16 seconds it most likely says something, but not
 * over a celebration and not right after its last line.
 */
@Composable
private fun ChatterLoop(enabled: Boolean) {
    val store = LocalGameStore.current
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(8_000, 16_001))
            if (Random.nextDouble() >= 0.75) continue
            if (store.celebrations.value.isNotEmpty()) continue
            val said = store.state.value.slime.say?.at
            if (said != null && Instant.now().timeIntervalSince(said) < 6) continue
            store.chatter()
        }
    }
}

/** Before hatching: the egg. A few taps and it hatches. */
@Composable
private fun EggHome(store: GameStore) {
    var cracks by remember { mutableIntStateOf(0) }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PixelEgg(
            cracks = cracks,
            modifier = Modifier
                .size(width = 144.dp, height = 180.dp)
                .semantics { contentDescription = "Egg" }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "Tap the egg",
                ) {
                    cracks += 1
                    if (cracks >= tapsToHatch) {
                        store.hatch("Blob", Skin.lime)
                    } else {
                        Feedback.shared.play(Sound.tap)
                        Feedback.shared.tapHaptic()
                    }
                },
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "Tap the egg",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
    }
}

private const val tapsToHatch = 3

/** The toast, top centre, long enough to read; TalkBack reads it out (the banner is a live region). */
@Composable
private fun ToastLayer(store: GameStore) {
    val toast by store.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val current by rememberUpdatedState(toast)
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = toast != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            val shown: Toast? = current
            if (shown != null) ToastBanner(toast = shown, modifier = Modifier.widthIn(max = 520.dp))
        }
    }
    LaunchedEffect(toast?.id) {
        val shown = toast ?: return@LaunchedEffect
        val talkBack = context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        val reading = shown.text.length * 0.06
        val seconds = if (talkBack) 5.0 else reading.coerceIn(2.4, 5.0)
        delay((seconds * 1000).toLong())
        store.dismissToast(shown)
    }
}

/** Level-ups, achievements and other moments, one card at a time. */
@Composable
private fun CelebrationLayer(store: GameStore) {
    val celebrations by store.celebrations.collectAsStateWithLifecycle()
    val first = celebrations.firstOrNull() ?: return
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f))
            .safeDrawingPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(modifier = Modifier.widthIn(max = 420.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                ItemIcon(first.icon, size = 64.dp)
                Spacer(Modifier.height(12.dp))
                Text(
                    first.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    first.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                AppButton(title = "Continue", modifier = Modifier.width(200.dp)) { store.dismissCelebration(first.id) }
            }
        }
    }
}
