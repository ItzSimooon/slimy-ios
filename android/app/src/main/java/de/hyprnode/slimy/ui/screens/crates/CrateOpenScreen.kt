package de.hyprnode.slimy.ui.screens.crates

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.art.PixelItem
import de.hyprnode.slimy.art.referenceSeconds
import de.hyprnode.slimy.art.rememberFrameClock
import de.hyprnode.slimy.art.rememberReduceMotion
import de.hyprnode.slimy.core.Feedback
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.model.Catalog
import de.hyprnode.slimy.model.CrateKind
import de.hyprnode.slimy.model.CrateReward
import de.hyprnode.slimy.model.ItemKind
import de.hyprnode.slimy.model.Rarity
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.ui.kit.AppButton
import de.hyprnode.slimy.ui.kit.AppButtonKind
import de.hyprnode.slimy.ui.kit.HUDButton
import de.hyprnode.slimy.ui.kit.Theme
import de.hyprnode.slimy.ui.screens.common.rememberAnnouncer
import de.hyprnode.slimy.ui.screens.common.swiftSpring
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Where an opening is. */
enum class OpenPhase {
    /** Waiting for taps. */
    closed,

    /** Cracked; the reveal is on its way. */
    cracking,

    /** The legendary build-up. */
    charging,

    /** Lid off, the reward rising. */
    open,

    /** The reward is shown with its words and buttons. */
    revealed,

    /** There was no crate of this kind to open. */
    empty,
}

private const val stageHeight = 330f
private const val crateSize = 168f

/** Where the risen reward sits, from the middle of the stage (the spark layer's centre), in dp. */
private val rewardCentre = Offset(0f, -45f)

/**
 * Opening a crate, full screen. The crate wobbles; every tap knocks its lid
 * a little looser until it cracks, bursts in the colour of what's inside,
 * and the reward rises out. The legendary reward gets a longer moment of its
 * own: the crate rattles, the screen flashes and the axolotl appears. With
 * reduced motion nothing shakes, flies or flashes: the reward fades in
 * (App/UI/Screens/CrateOpenView.swift).
 */
@Composable
fun CrateOpenScreen(kind: CrateKind, onDone: () -> Unit) {
    val store = LocalGameStore.current
    val s by store.state.collectAsStateWithLifecycle()
    val reduceMotion = rememberReduceMotion()
    val announce = rememberAnnouncer()
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    // The kind being opened now; "Open another" can move on to a crate that came out of this one.
    var current by remember { mutableStateOf(kind) }
    var phase by remember { mutableStateOf(OpenPhase.closed) }
    var taps by remember { mutableIntStateOf(0) }
    var tapsNeeded by remember { mutableIntStateOf(Random.nextInt(3, 6)) }
    var knocks by remember { mutableIntStateOf(0) }
    val shake = remember { Animatable(0f) }
    val squash = remember { Animatable(1f) }
    val flash = remember { Animatable(0f) }
    var bursts by remember { mutableStateOf(listOf<SparkBurst>()) }
    var reward by remember { mutableStateOf<CrateReward?>(null) }
    // Crates that came along with the opening besides the reward itself and its bonus crate.
    var extras by remember { mutableStateOf(listOf<CrateKind>()) }
    var rise by remember { mutableStateOf(false) }

    fun count(k: CrateKind): Int = store.state.value.crates[k] ?: 0

    LaunchedEffect(Unit) {
        if (reward == null && count(current) == 0) phase = OpenPhase.empty
    }

    val isOpen = phase == OpenPhase.open || phase == OpenPhase.revealed
    val moving = !reduceMotion && (phase == OpenPhase.closed || phase == OpenPhase.charging)
    val clock by rememberFrameClock(running = moving, maxFps = 30)

    val liftTarget = when (phase) {
        OpenPhase.closed -> taps.toFloat() / maxOf(1, tapsNeeded)
        OpenPhase.cracking, OpenPhase.charging -> 1f
        else -> 0f
    }
    val lift by animateFloatAsState(liftTarget, if (reduceMotion) tween(0) else swiftSpring(0.25, 0.5), label = "lift")
    val tilt = if (taps > 0 && (phase == OpenPhase.closed || phase == OpenPhase.cracking || phase == OpenPhase.charging)) {
        (if (knocks % 2 == 0) 1f else -1f) * lift * 7f
    } else {
        0f
    }
    val riseProgress by animateFloatAsState(
        if (rise) 1f else 0f,
        when {
            reduceMotion -> tween(if (reward?.rarity == Rarity.legendary) 900 else 500)
            reward?.rarity == Rarity.legendary -> swiftSpring(1.1, 0.62)
            else -> swiftSpring(0.55, 0.68)
        },
        label = "rise",
    )
    // With reduced motion the reward is already in place and only fades in.
    val position = if (reduceMotion) 1f else riseProgress
    val openScale by animateFloatAsState(if (isOpen && !reduceMotion) 0.8f else 1f, swiftSpring(0.4, 0.62), label = "openScale")

    val topShare = CrateArt.topShare(current)
    // The top of the closed crate and the open crate's mouth, from the middle of the stage.
    val crateTop = Offset(0f, stageHeight / 2 - crateSize * (1 - topShare))
    val mouth = Offset(0f, stageHeight / 2 - 0.8f * crateSize * (1 - topShare) + 12)

    fun addBurst(burst: SparkBurst) {
        if (reduceMotion) return
        bursts = bursts + burst
        scope.launch {
            delay(((burst.life + 0.1) * 1000).toLong())
            bursts = bursts.filter { it.id != burst.id }
        }
    }

    /** A knock felt in the hand, firmer with every tap and through the legendary rattle. */
    fun thump(intensity: Double) {
        if (!store.state.value.settings.haptics) return
        if (intensity >= 0.8) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        } else {
            Feedback.shared.tapHaptic()
        }
    }

    /** A shake and a squash, like a knock on the lid. */
    fun knock(strength: Double) {
        knocks += 1
        if (reduceMotion) return
        val springy = spring<Float>(dampingRatio = 0.24f, stiffness = 280f)
        scope.launch {
            shake.snapTo(((if (knocks % 2 == 0) 1 else -1) * 8 * strength).toFloat())
            shake.animateTo(0f, springy)
        }
        scope.launch {
            squash.snapTo(1f - 0.1f * min(strength, 2.0).toFloat())
            squash.animateTo(1f, springy)
        }
    }

    fun sparkColors(rarity: Rarity): List<Color> {
        val tint = rarity.displayColor
        return when (rarity) {
            Rarity.common, Rarity.rare -> listOf(tint, tint.copy(alpha = 0.6f))
            Rarity.epic -> listOf(tint, tint.copy(alpha = 0.6f), Color(0xFFFFD166))
            Rarity.legendary -> listOf(tint, Color(Skin.axolotl.body), Color(Skin.axolotl.shade), Color(0xFFFFD166))
        }
    }

    fun finish() {
        phase = OpenPhase.revealed
        reward?.let { announce(CrateOpening.spoken(it, extras)) }
    }

    suspend fun reveal(rarity: Rarity) {
        Feedback.shared.play(Sound.treasure)
        if (reduceMotion) {
            phase = OpenPhase.open
            rise = true
            delay(500)
            finish()
            return
        }
        knock(1.6)
        delay(120)
        val level = Rarity.allCases.indexOf(rarity).coerceAtLeast(0)
        addBurst(SparkBurst(colors = sparkColors(rarity), count = 22 + 10 * level, speed = 440f, size = 7f, life = 1.15, origin = mouth, spread = 0.55))
        phase = OpenPhase.open
        delay(160)
        Feedback.shared.play(Sound.whoosh)
        rise = true
        delay(420)
        if (rarity >= Rarity.epic) {
            Feedback.shared.play(Sound.success)
            addBurst(SparkBurst(colors = sparkColors(rarity), count = 20, speed = 300f, size = 6f, life = 1.0, gravity = 420f, origin = rewardCentre, spread = 1.0))
        }
        finish()
    }

    /** The legendary moment: a long rattle, a flash, a big burst, and the axolotl rising slowly. */
    suspend fun revealLegendary() {
        phase = OpenPhase.charging
        if (reduceMotion) {
            delay(1_400)
            Feedback.shared.play(Sound.levelUp)
            phase = OpenPhase.open
            rise = true
            delay(900)
            finish()
            return
        }
        for (beat in 0 until 6) {
            Feedback.shared.play(Sound.dig)
            thump(0.45 + beat * 0.11)
            knock(1 + beat * 0.25)
            addBurst(
                SparkBurst(
                    colors = CrateArt.colors(current), count = 6 + beat, speed = 240f + beat * 30f,
                    size = 6f, life = 0.6, gravity = 900f, origin = crateTop, spread = 0.6,
                ),
            )
            delay((330 - beat * 30).toLong())
        }
        Feedback.shared.play(Sound.levelUp)
        flash.animateTo(0.4f, tween(100))
        // The lid goes while the screen is washed in colour.
        phase = OpenPhase.open
        addBurst(SparkBurst(colors = sparkColors(Rarity.legendary), count = 64, speed = 620f, size = 8f, life = 1.7, gravity = 640f, origin = mouth, spread = 0.75))
        scope.launch { flash.animateTo(0f, tween(900)) }
        delay(300)
        Feedback.shared.play(Sound.whoosh)
        rise = true
        delay(850)
        Feedback.shared.play(Sound.treasure)
        addBurst(SparkBurst(colors = sparkColors(Rarity.legendary), count = 36, speed = 380f, size = 7f, life = 1.3, gravity = 380f, origin = rewardCentre, spread = 1.0))
        delay(450)
        finish()
    }

    fun crack() {
        val before = store.state.value.crates
        // The store plays its "nope" when there was nothing to open.
        val got = store.openCrate(current)
        if (got == null) {
            phase = OpenPhase.empty
            return
        }
        phase = OpenPhase.cracking
        extras = CrateOpening.extraCrates(before, store.state.value.crates, current, got)
        reward = got
        scope.launch { if (got.rarity == Rarity.legendary) revealLegendary() else reveal(got.rarity) }
    }

    fun tapCrate() {
        if (phase != OpenPhase.closed || count(current) == 0) return
        taps += 1
        // Every tap knocks a little harder than the last.
        thump(0.35 + 0.15 * taps)
        if (taps >= tapsNeeded) {
            crack()
            return
        }
        Feedback.shared.play(Sound.dig)
        knock(1 + 0.2 * taps)
        addBurst(SparkBurst(colors = CrateArt.colors(current), count = 7, speed = 260f, size = 6f, life = 0.6, gravity = 900f, origin = crateTop, spread = 0.55))
    }

    fun openNext(next: CrateKind) {
        current = next
        reward = null
        extras = emptyList()
        rise = false
        taps = 0
        knocks = 0
        tapsNeeded = Random.nextInt(3, 6)
        bursts = emptyList()
        phase = OpenPhase.closed
    }

    fun wear(skin: Skin) {
        store.wear(skin)
        Feedback.shared.play(Sound.success)
        announce("${store.state.value.slime.name} is wearing it now.")
    }

    // Back works wherever the close or done buttons would; not while the crate bursts open.
    BackHandler(enabled = true) {
        if (phase == OpenPhase.closed || phase == OpenPhase.revealed || phase == OpenPhase.empty) onDone()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Theme.backdrop),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .safeDrawingPadding()
                .padding(start = 24.dp, end = 24.dp, top = 52.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            // MARK: Stage
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(stageHeight.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                val shown = reward
                if (shown != null && isOpen) {
                    // A flat disc of the rarity's colour behind the reward.
                    Box(
                        Modifier
                            .offset(y = (-88).dp)
                            .size(240.dp)
                            .graphicsLayer {
                                val sc = 0.3f + 0.7f * position
                                scaleX = sc
                                scaleY = sc
                                alpha = riseProgress
                            }
                            .clip(CircleShape)
                            .background(shown.rarity.displayColor.copy(alpha = if (shown.rarity == Rarity.legendary) 0.26f else 0.16f))
                            .clearAndSetSemantics { },
                    )
                }

                val t = referenceSeconds(clock)
                Box(
                    Modifier
                        .size(width = (crateSize * 1.2f).dp, height = crateSize.dp)
                        .then(
                            if (phase == OpenPhase.closed) {
                                Modifier.semantics {
                                    contentDescription = CrateCopy.name(current)
                                    if (taps > 0) stateDescription = "$taps of $tapsNeeded taps"
                                }
                            } else {
                                Modifier.clearAndSetSemantics { }
                            },
                        )
                        .clickable(
                            enabled = phase == OpenPhase.closed,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClickLabel = "Crack it open",
                        ) { tapCrate() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    CrateSprite(
                        kind = current,
                        cropped = false,
                        lift = lift,
                        tilt = tilt,
                        open = isOpen,
                        modifier = Modifier
                            .size(crateSize.dp)
                            .graphicsLayer {
                                transformOrigin = TransformOrigin(0.5f, 1f)
                                scaleX = (2f - squash.value) * openScale
                                scaleY = squash.value * openScale
                                rotationZ = (if (moving) CrateOpening.wobble(t, phase) else 0.0).toFloat() + shake.value
                                translationX = if (moving && phase == OpenPhase.charging) sin(t * 57).toFloat() * 2.5f * density else 0f
                                alpha = if (phase == OpenPhase.empty) 0.35f else 1f
                            },
                    )
                }

                if (shown != null && isOpen) {
                    CrateRewardArt(
                        reward = shown,
                        slime = s.slime,
                        size = if (shown.rarity == Rarity.legendary) 124.dp else 112.dp,
                        modifier = Modifier
                            .offset { IntOffset(0, ((-50f - 100f * position) * density).toInt()) }
                            .graphicsLayer {
                                transformOrigin = TransformOrigin(0.5f, 1f)
                                val sc = 0.25f + 0.75f * position
                                scaleX = sc
                                scaleY = sc
                                alpha = riseProgress
                            }
                            .clearAndSetSemantics { },
                    )
                }

                // Wider than the stage, so sparks can fly past its edges.
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                    SparkLayer(bursts, Modifier.requiredSize(720.dp, 900.dp))
                }
            }

            // MARK: Words
            Box(
                Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .heightIn(min = 150.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                AnimatedContent(
                    targetState = CaptionKey(phase, reward?.let { if (phase == OpenPhase.revealed) it else null }),
                    transitionSpec = {
                        if (reduceMotion || targetState.phase != OpenPhase.revealed) {
                            fadeIn(tween(250)) togetherWith fadeOut(tween(150))
                        } else {
                            (fadeIn(tween(300)) + slideInVertically { it / 3 }) togetherWith fadeOut(tween(150))
                        }
                    },
                    label = "caption",
                ) { key ->
                    Caption(key.phase, current, taps, tapsNeeded, count(current), key.reward, extras)
                }
            }

            // MARK: Buttons
            Column(
                Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .heightIn(min = 170.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (phase) {
                    OpenPhase.revealed -> {
                        val skin = CrateOpening.offeredSkin(reward) { s.slime.isUnlocked(it) }
                        if (skin != null) {
                            if (s.slime.skin == skin) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Theme.accent, modifier = Modifier.size(18.dp))
                                    Text(
                                        "${s.slime.name} is wearing it",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Theme.accent,
                                    )
                                }
                            } else {
                                AppButton(title = "Use this colour", kind = AppButtonKind.secondary) { wear(skin) }
                            }
                        }
                        val next = CrateOpening.nextCrate(current, reward, extras, ::count)
                        if (next != null) {
                            AppButton(
                                title = if (next == current) "Open another" else "Open the ${CrateCopy.name(next).lowercase()}",
                                systemIcon = Icons.Outlined.Inventory2,
                            ) { openNext(next) }
                            AppButton(title = "Done", kind = AppButtonKind.secondary, action = onDone)
                        } else {
                            AppButton(title = "Done", action = onDone)
                        }
                    }
                    OpenPhase.empty -> AppButton(title = "Done", action = onDone)
                    else -> Spacer(Modifier.height(1.dp))
                }
            }
        }

        // A soft wash of the legendary colour, not a white blast.
        if (flash.value > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Rarity.legendary.displayColor.copy(alpha = flash.value))
                    .clearAndSetSemantics { },
            )
        }

        if (phase == OpenPhase.closed) {
            HUDButton(
                icon = Icons.Filled.Close,
                label = "Close, keeps the crate for later",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 16.dp, top = 8.dp),
                action = onDone,
            )
        }
    }
}

private data class CaptionKey(val phase: OpenPhase, val reward: CrateReward?)

@Composable
private fun Caption(
    phase: OpenPhase,
    current: CrateKind,
    taps: Int,
    tapsNeeded: Int,
    left: Int,
    reward: CrateReward?,
    extras: List<CrateKind>,
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val title = MaterialTheme.typography.headlineSmall
        val body = MaterialTheme.typography.bodyLarge
        when (phase) {
            OpenPhase.closed, OpenPhase.cracking -> {
                Text(CrateCopy.name(current), style = title, fontWeight = FontWeight.Bold, color = Theme.ink,
                    textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                Text(CrateOpening.tapHint(phase, taps, tapsNeeded), style = body, color = Theme.inkSoft, textAlign = TextAlign.Center)
                if (phase == OpenPhase.closed && left > 1) {
                    Text("$left on the shelf", style = MaterialTheme.typography.bodySmall, color = Theme.inkSoft, textAlign = TextAlign.Center)
                }
            }
            OpenPhase.charging -> {
                Text("Something special is inside", style = title, fontWeight = FontWeight.Bold, color = Theme.ink, textAlign = TextAlign.Center)
                Text("Hold on…", style = body, color = Theme.inkSoft, textAlign = TextAlign.Center)
            }
            OpenPhase.open -> Spacer(Modifier.height(1.dp))
            OpenPhase.revealed -> if (reward != null) RewardText(reward, extras)
            OpenPhase.empty -> {
                Text("Nothing left to open", style = title, fontWeight = FontWeight.Bold, color = Theme.ink, textAlign = TextAlign.Center)
                Text(
                    "Crates come with the seventh daily gift, every fifth level, great arcade rounds and some achievements, or for coins in the shop.",
                    style = body,
                    color = Theme.inkSoft,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun RewardText(reward: CrateReward, extras: List<CrateKind>) {
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RarityBadge(reward.rarity)
        Text(
            reward.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Theme.ink,
            textAlign = TextAlign.Center,
        )
        val converted = CrateOpening.convertedText(reward)
        val detail = CrateOpening.detail(reward)
        if (converted != null) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(Theme.smallRadius))
                    .background(Theme.well)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PixelItem("coin", Modifier.size(18.dp))
                Text(converted, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Theme.ink)
            }
        } else if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodyLarge, color = Theme.inkSoft, textAlign = TextAlign.Center)
        }
        CrateOpening.extrasText(extras)?.let { extra ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.Inventory2, contentDescription = null, tint = Theme.ink, modifier = Modifier.size(18.dp))
                Text(extra, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Theme.ink)
            }
        }
    }
}

/** The opening's rules and words, kept apart from the view so they can be tested. */
object CrateOpening {
    /**
     * The idle wobble in degrees: a little jiggle every couple of seconds, or
     * a rattle while something legendary is about to come out.
     */
    fun wobble(t: Double, phase: OpenPhase): Double {
        if (phase == OpenPhase.charging) return sin(t * 46) * 7
        val cycle = ((t % 2.4) + 2.4) % 2.4
        if (cycle >= 0.7) return 0.0
        val p = cycle / 0.7
        return sin(p * PI * 5) * 4 * (1 - p)
    }

    fun tapHint(phase: OpenPhase, taps: Int, tapsNeeded: Int): String {
        if (phase == OpenPhase.cracking) return "Here it comes!"
        if (taps == 0) return "Tap the crate to crack it open."
        val left = tapsNeeded - taps
        return if (left == 1) "One more tap!" else "$left more taps."
    }

    /** "Already yours, so you get 40 coins instead." Null unless it was converted. */
    fun convertedText(reward: CrateReward): String? = if (reward.convertedCoins != null) reward.detail else null

    /** What the reward means and where to find it, unless it was converted. */
    fun detail(reward: CrateReward): String? {
        if (reward.convertedCoins != null) return null
        return when (val kind = reward.kind) {
            is CrateReward.Kind.coins -> "Added to your coins."
            is CrateReward.Kind.food -> "Free servings, waiting in the kitchen."
            is CrateReward.Kind.item ->
                if (Catalog.item(kind.id)?.kind == ItemKind.furniture) {
                    "New furniture. Place it in the room from the shop."
                } else {
                    "Something new to wear. Try it on in the shop."
                }
            is CrateReward.Kind.skin ->
                if (kind.skin.isSecret) "The secret colour, found only in crates." else "A new colour. Pick it in the shop under Colours."
            is CrateReward.Kind.crate -> "Another crate to open."
        }
    }

    fun extrasText(extras: List<CrateKind>): String? {
        if (extras.isEmpty()) return null
        val names = CrateKind.allCases.mapNotNull { k ->
            val n = extras.count { it == k }
            if (n == 0) return@mapNotNull null
            val name = CrateCopy.name(k).lowercase()
            if (n == 1) "a $name" else "$n ${name}s"
        }
        return "You also got ${names.joinToString(" and ")}."
    }

    fun spoken(reward: CrateReward, extras: List<CrateKind>): String {
        val parts = mutableListOf("${reward.rarity.title}: ${reward.title}.")
        val converted = convertedText(reward)
        val detail = detail(reward)
        if (converted != null) parts.add(converted) else if (detail != null) parts.add(detail)
        extrasText(extras)?.let { parts.add(it) }
        return parts.joinToString(" ")
    }

    /** A colour that came out of the crate and can be worn right away. */
    fun offeredSkin(reward: CrateReward?, isUnlocked: (Skin) -> Boolean): Skin? {
        reward ?: return null
        val found = when {
            reward.rarity == Rarity.legendary -> Skin.axolotl
            reward.kind is CrateReward.Kind.skin -> (reward.kind as CrateReward.Kind.skin).skin
            else -> null
        } ?: return null
        return if (isUnlocked(found)) found else null
    }

    /** Another of the same kind, or else a crate that just came out. */
    fun nextCrate(current: CrateKind, reward: CrateReward?, extras: List<CrateKind>, count: (CrateKind) -> Int): CrateKind? {
        if (count(current) > 0) return current
        val candidates = extras.toMutableList()
        reward?.bonusCrate?.let { candidates.add(0, it) }
        (reward?.kind as? CrateReward.Kind.crate)?.let { candidates.add(0, it.kind) }
        return candidates.firstOrNull { count(it) > 0 }
    }

    /**
     * Crates that arrived with this opening beyond the reward and its bonus
     * crate, like one from an achievement it just completed.
     */
    fun extraCrates(
        before: Map<CrateKind, Int>,
        after: Map<CrateKind, Int>,
        opened: CrateKind,
        reward: CrateReward,
    ): List<CrateKind> {
        val out = mutableListOf<CrateKind>()
        for (k in CrateKind.allCases) {
            var expected = before[k] ?: 0
            if (k == opened) expected -= 1
            if ((reward.kind as? CrateReward.Kind.crate)?.kind == k) expected += 1
            if (reward.bonusCrate == k) expected += 1
            val extra = (after[k] ?: 0) - expected
            repeat(maxOf(0, extra)) { out.add(k) }
        }
        return out
    }
}
