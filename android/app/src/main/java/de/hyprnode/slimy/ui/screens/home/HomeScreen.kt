package de.hyprnode.slimy.ui.screens.home

import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.art.AnimatedSlime
import de.hyprnode.slimy.art.ArtView
import de.hyprnode.slimy.art.HabitatScene
import de.hyprnode.slimy.art.LiveSlimeView
import de.hyprnode.slimy.art.PixelItem
import de.hyprnode.slimy.art.SlimeMoodWord
import de.hyprnode.slimy.art.rememberClock
import de.hyprnode.slimy.art.rememberReduceMotion
import de.hyprnode.slimy.core.Feedback
import de.hyprnode.slimy.core.GameStore
import de.hyprnode.slimy.core.LocalDailyStore
import de.hyprnode.slimy.core.LocalFriendsStore
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.model.Accessories
import de.hyprnode.slimy.model.CrateKind
import de.hyprnode.slimy.model.GameState
import de.hyprnode.slimy.model.Need
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.model.isBirthday
import de.hyprnode.slimy.model.line
import de.hyprnode.slimy.model.streakInfo
import de.hyprnode.slimy.model.timeIntervalSince
import de.hyprnode.slimy.model.trimmedSwift
import de.hyprnode.slimy.platform.FriendSlime
import de.hyprnode.slimy.ui.Destination
import de.hyprnode.slimy.ui.kit.CoinCounter
import de.hyprnode.slimy.ui.kit.HUDButton
import de.hyprnode.slimy.ui.kit.Meter
import de.hyprnode.slimy.ui.kit.NeedRing
import de.hyprnode.slimy.ui.kit.SpeechBox
import de.hyprnode.slimy.ui.kit.Theme
import de.hyprnode.slimy.ui.kit.TileButton
import de.hyprnode.slimy.ui.screens.common.rememberIsResumed
import de.hyprnode.slimy.ui.screens.crates.CrateSprite
import kotlinx.coroutines.delay
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The slime's home: its habitat, the slime and what it's saying, and around
 * them the HUD (name, level and mood, coins, friends, chat, settings), who
 * it's cared for with, today's pills, crates to open and the needs dock.
 * While you watch, the slime talks to itself every few seconds
 * (App/UI/World/HomeView.swift).
 */
@Composable
fun HomeScreen(open: (Destination) -> Unit) {
    val store = LocalGameStore.current
    val friends = LocalFriendsStore.current
    val s by store.state.collectAsStateWithLifecycle()
    val visitor by friends.visitor.collectAsStateWithLifecycle()
    val reduceMotion = rememberReduceMotion()
    val resumed = rememberIsResumed()
    val now = Instant.now()

    ChatterLoop(enabled = resumed && s.settings.chatty, store = store)

    var homeTop by remember { mutableFloatStateOf(0f) }
    // Where the dock begins, so the slime never stands behind it.
    var dockTop by remember { mutableFloatStateOf(Float.POSITIVE_INFINITY) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { homeTop = it.positionInRoot().y },
    ) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        // The same grid HabitatScene draws on, so the slime stands on its floor.
        val scale = HabitatScene.scale(widthPx, density.density)
        val rows = max(1, ceil(heightPx / scale).toInt())
        val floorRow = HabitatScene.floorRow(rows)
        val feet = HomeLayout.feet(floorRow, scale, dockTop, with(density) { 6.dp.toPx() })
        val slimeHeight = min(maxWidth * 0.42f, 190.dp)
        val bubbleWidth = min(maxWidth * 0.72f, 320.dp)

        HabitatScene(
            habitat = s.inventory.habitat,
            furniture = s.inventory.furniture,
            date = now,
            modifier = Modifier.fillMaxSize(),
            animate = !reduceMotion && resumed,
            party = s.isBirthday(now),
        )

        // The slime on the floor, the bubble on top of it whatever its length.
        Box(
            Modifier
                .fillMaxWidth()
                .height(with(density) { feet.toDp() }),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier.wrapContentHeight(Alignment.Bottom, unbounded = true),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SlimeSpeech(s, bubbleWidth, reduceMotion)
                // The bubble above already shows what it says; no toast on top.
                LiveSlimeView(height = slimeHeight, showsToast = false)
            }
        }

        val visit = visitor
        if (visit != null && visit.until.isAfter(now)) {
            Visitor(
                visit.friend,
                slimeHeight,
                Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(
                            (constraints.maxWidth * 0.82f - placeable.width / 2f).roundToInt(),
                            (feet - placeable.height).roundToInt(),
                        )
                    }
                },
            )
        }

        StatusBarBackdrop()

        // Large text grows the HUD only so far, so the slime keeps its room.
        CompositionLocalProvider(LocalDensity provides Density(density.density, minOf(density.fontScale, 1.3f))) {
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column {
                    TopBar(s, open)
                    PartnerChip(s) { open(Destination.care) }
                }
                Pills(s, open)
                Spacer(Modifier.weight(1f))
                Dock(
                    s,
                    open,
                    store,
                    Modifier.onGloballyPositioned { coords: LayoutCoordinates ->
                        dockTop = coords.positionInRoot().y - homeTop
                    },
                )
            }
        }
    }
}

/** Where the slime's feet go: on the floor a little way in, nearer the back if the dock would cover them. */
object HomeLayout {
    fun feet(floorRow: Int, scale: Float, dockTop: Float, gap: Float): Float {
        val onFloor = (floorRow + 12) * scale
        val backOfFloor = (floorRow + 3) * scale
        return max(backOfFloor, minOf(onFloor, dockTop - gap))
    }

    /** "Level 3 · cheerful", or just "Level 3". */
    fun levelText(level: Int, mood: String?): String = if (mood != null) "Level $level · $mood" else "Level $level"

    fun cratesText(count: Int): String = if (count == 1) "1 crate to open" else "$count crates to open"

    /** Crates of every kind waiting to be opened. */
    fun crateCount(s: GameState): Int = s.crates.values.sumOf { max(0, it) }

    /** The best kind of crate waiting, for the pill's icon. */
    fun bestCrate(s: GameState): CrateKind = CrateKind.allCases.lastOrNull { (s.crates[it] ?: 0) > 0 } ?: CrateKind.wooden

    /**
     * Small talk is allowed: no celebration on top, and a line it just said
     * (thanks for food, a pet) stays up long enough to read.
     */
    fun mayChatter(celebrating: Boolean, saidAt: Instant?, now: Instant): Boolean {
        if (celebrating) return false
        if (saidAt != null && now.timeIntervalSince(saidAt) < 6) return false
        return true
    }
}

/**
 * Every 8 to 16 seconds it most likely says something, only while home is in
 * front and it's allowed to (Settings, Talks on its own). Restarts whenever
 * home leaves the front, so a line never lands right after coming back.
 */
@Composable
private fun ChatterLoop(enabled: Boolean, store: GameStore) {
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(8_000, 16_001))
            if (Random.nextDouble() >= 0.75) continue
            if (!HomeLayout.mayChatter(store.celebrations.value.isNotEmpty(), store.state.value.slime.say?.at, Instant.now())) continue
            store.chatter()
        }
    }
}

/** What the slime says, looked at every second so a line that ran out goes away. */
@Composable
private fun SlimeSpeech(s: GameState, maxWidth: Dp, reduceMotion: Boolean) {
    val clock by rememberClock(1_000L)
    val line = s.line(Instant.ofEpochMilli(clock))
    val context = LocalContext.current
    val talkBack = remember { context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true }
    if (reduceMotion || talkBack) {
        // With TalkBack the bubble stays the same element and only its words change, so focus stays on it.
        SpeechBox(text = line, modifier = Modifier.widthIn(max = maxWidth))
    } else {
        // A soft cross-fade from one thought to the next.
        Crossfade(targetState = line, animationSpec = tween(350), label = "speech") { shown ->
            SpeechBox(text = shown, modifier = Modifier.widthIn(max = maxWidth))
        }
    }
}

/**
 * A bar behind the status bar, so the clock and battery stay readable over
 * the pixel world in light and dark mode.
 */
@Composable
fun StatusBarBackdrop(modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f))
            .clearAndSetSemantics { },
    )
}

private val hudFill: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)

// MARK: Top

@Composable
private fun TopBar(s: GameState, open: (Destination) -> Unit) {
    val friends = LocalFriendsStore.current
    val news by friends.hasNews.collectAsStateWithLifecycle()
    Row(
        Modifier.padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Coins and buttons keep their size; the name gives way.
        Box(Modifier.weight(1f)) {
            NameCapsule(s) {
                Feedback.shared.play(Sound.tap)
                open(Destination.journal)
            }
        }
        CoinCounter(coins = s.wallet.coins, floating = true)
        HUDButton(Icons.Filled.People, "Friends", badge = news) { open(Destination.friends) }
        HUDButton(Icons.Filled.ChatBubble, "Talk") { open(Destination.chat) }
        HUDButton(Icons.Filled.Settings, "Settings") { open(Destination.settings) }
    }
}

@Composable
private fun NameCapsule(s: GameState, onClick: () -> Unit) {
    val slime = s.slime
    val mood = SlimeMoodWord.word(slime)
    val levelStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
    Column(
        Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(hudFill)
            .semantics {
                contentDescription = "${slime.name}, level ${slime.level}, ${SlimeMoodWord.phrase(slime)}"
                stateDescription = "${(slime.levelProgress * 100).roundToInt()} percent to the next level"
            }
            .clickable(role = Role.Button, onClickLabel = "Open the journal", onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            slime.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Theme.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics { },
        )
        LevelLine(slime.level, mood, slime.levelProgress, levelStyle)
    }
}

/** The mood goes first when room runs short, then the bar. */
@Composable
private fun LevelLine(level: Int, mood: String, progress: Double, style: TextStyle) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.clearAndSetSemantics { }) {
        val density = LocalDensity.current
        val room = constraints.maxWidth
        val meter = with(density) { (56.dp + 6.dp).roundToPx() }
        val withMood = measurer.measure(HomeLayout.levelText(level, mood), style).size.width
        val plain = measurer.measure(HomeLayout.levelText(level, null), style).size.width
        val (text, bar) = when {
            withMood + meter <= room -> HomeLayout.levelText(level, mood) to true
            withMood <= room -> HomeLayout.levelText(level, mood) to false
            plain + meter <= room -> HomeLayout.levelText(level, null) to true
            else -> HomeLayout.levelText(level, null) to false
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, style = style, color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (bar) Meter(value = progress, height = 4.dp, modifier = Modifier.width(56.dp))
        }
    }
}

/** Who this slime is cared for with, under its name while it's shared. Opens Care Together. */
@Composable
private fun PartnerChip(s: GameState, onClick: () -> Unit) {
    if (s.care == null) return
    val partner = (s.care?.partnerName ?: "").trimmedSwift()
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = if (partner.isEmpty()) "Cared for together" else "Cared for together with $partner"
            }
            .clickable(role = Role.Button, onClickLabel = "Open Care Together") {
                Feedback.shared.play(Sound.tap)
                onClick()
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // Transparent margin: 48 dp to tap, 30 dp to see.
        Row(
            Modifier
                .heightIn(min = 30.dp)
                .clip(CircleShape)
                .background(hudFill)
                .padding(horizontal = 12.dp)
                .clearAndSetSemantics { },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(Icons.Filled.People, contentDescription = null, tint = Theme.accent, modifier = Modifier.size(14.dp))
            Text(
                if (partner.isEmpty()) "Cared for together" else "with $partner",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Theme.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// MARK: Pills

/**
 * Today's gift, today's game and crates waiting to be opened, side by side
 * when they fit. Looked at again every minute, so a new day's gift shows up
 * on its own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Pills(s: GameState, open: (Destination) -> Unit) {
    val daily = LocalDailyStore.current
    val plan by daily.plan.collectAsStateWithLifecycle()
    val clock by rememberClock(60_000L)
    val canClaim = s.streakInfo(Instant.ofEpochMilli(clock)).canClaim
    val playedToday = daily.playedToday
    val crates = HomeLayout.crateCount(s)
    if (!canClaim && playedToday && crates == 0) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (canClaim) {
            Pill("Daily Gift", "Daily gift ready", "Open today's gift", { open(Destination.gift) }) {
                ArtView("gift.closed", Modifier.size(18.dp))
            }
        }
        // Points at today's new game until it's been played.
        if (!playedToday) {
            val text = "New today: ${plan.game.title}"
            Pill(text, text, "Open the arcade", { open(Destination.arcade) }) {
                PixelItem("starJelly", Modifier.size(16.dp))
            }
        }
        if (crates > 0) {
            val text = HomeLayout.cratesText(crates)
            Pill(text, text, "Open your crates", { open(Destination.crates) }) {
                CrateSprite(HomeLayout.bestCrate(s), Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun Pill(text: String, spoken: String, action: String, onClick: () -> Unit, icon: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(hudFill)
            .semantics { contentDescription = spoken }
            .clickable(role = Role.Button, onClickLabel = action) {
                Feedback.shared.play(Sound.tap)
                onClick()
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon()
            Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Theme.ink, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Theme.inkSoft, modifier = Modifier.size(18.dp))
        }
    }
}

// MARK: Visitor

/** A friend's slime that came over for a while. */
@Composable
private fun Visitor(friend: FriendSlime, slimeHeight: Dp, modifier: Modifier = Modifier) {
    Column(
        modifier.clearAndSetSemantics { contentDescription = "${friend.name} is visiting" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            friend.name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Theme.ink,
            maxLines = 1,
            modifier = Modifier
                .clip(CircleShape)
                .background(hudFill)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
        AnimatedSlime(
            stage = friend.stage,
            anim = SpriteAnim.idle,
            anchor = Instant.EPOCH,
            skin = friend.skin,
            height = slimeHeight * 0.55f,
            accessories = friend.equipped ?: Accessories(),
            mirrored = true,
            label = friend.name,
        )
    }
}

// MARK: Bottom

@Composable
private fun Dock(s: GameState, open: (Destination) -> Unit, store: GameStore, modifier: Modifier = Modifier) {
    val slime = s.slime
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(hudFill)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row {
            for (need in Need.allCases) {
                val value = slime.needs[need]
                val wished = slime.wish == need
                Column(
                    Modifier
                        .weight(1f)
                        .clearAndSetSemantics {
                            contentDescription = need.title
                            stateDescription = "${value.toInt()} percent${if (wished) ", needed right now" else ""}"
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    NeedRing(need, value)
                    Text(
                        need.title,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (wished) Theme.ink else Theme.inkSoft,
                        maxLines = 1,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionKey("Feed", "apple", Need.hunger, slime.wish == Need.hunger, Modifier.weight(1f)) { open(Destination.kitchen) }
            ActionKey("Play", "ball", Need.`fun`, slime.wish == Need.`fun`, Modifier.weight(1f)) { open(Destination.arcade) }
            ActionKey("Bath", "bubbleItem", Need.clean, slime.wish == Need.clean, Modifier.weight(1f)) { open(Destination.bath) }
            ActionKey(if (slime.isAsleep) "Wake" else "Sleep", "starJelly", Need.energy, slime.wish == Need.energy, Modifier.weight(1f)) {
                store.toggleSleep(toast = false)
            }
            ActionKey("Shop", "coin", null, false, Modifier.weight(1f)) { open(Destination.shop) }
        }
    }
}

@Composable
private fun ActionKey(title: String, icon: String, need: Need?, urgent: Boolean, modifier: Modifier = Modifier, action: () -> Unit) {
    val tint = need?.let { Theme.color(it) } ?: Theme.accent
    TileButton(
        onClick = {
            Feedback.shared.play(Sound.tap)
            action()
        },
        fill = if (urgent) tint.copy(alpha = 0.22f) else Theme.well,
        radius = Theme.smallRadius,
        modifier = modifier.semantics {
            contentDescription = title
            if (urgent) stateDescription = "Your slime needs this right now"
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 62.dp)
                .padding(vertical = 8.dp)
                .clearAndSetSemantics { },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
        ) {
            PixelItem(icon, Modifier.size(24.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Theme.ink, maxLines = 1)
        }
        if (urgent) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(tint),
            )
        }
    }
}
