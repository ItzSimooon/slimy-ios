package de.hyprnode.slimy.platform.island

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hyprnode.slimy.art.PixelIcon
import de.hyprnode.slimy.art.PixelItem
import de.hyprnode.slimy.art.PixelSlime
import de.hyprnode.slimy.art.icon
import de.hyprnode.slimy.art.rememberClock
import de.hyprnode.slimy.art.rememberReduceMotion
import de.hyprnode.slimy.art.tint
import de.hyprnode.slimy.model.Accessories
import de.hyprnode.slimy.model.Brand
import de.hyprnode.slimy.model.EmoteReel
import de.hyprnode.slimy.model.Need
import de.hyprnode.slimy.model.SlimeSprites
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.model.Stage
import de.hyprnode.slimy.ui.kit.Theme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The island's look: a pure black shape around the camera that grows into a
// panel, with the pixel slime living in it. Everything here takes plain
// values (a scene, a placement, a mode), so the overlay service and the
// screenshot tests draw exactly the same thing.

/** Colours inside the island, which is black in light and dark mode. */
object IslandInk {
    val island = Color(0xFF000000)
    val primary = Color(0xFFFFFFFF)
    val secondary = Color(0x99FFFFFF)
    val tile = Color(0x1CFFFFFF)
    val tilePressed = Color(0x33FFFFFF)
    val track = Color(0x29FFFFFF)
    val divider = Color(0x1FFFFFFF)
    val danger = Color(0xFFFF453A)
    val gold = Color(Brand.gold)
}

/** Heights (dp) of the open island's content below the camera strip; the placement sizes the panels with them. */
object IslandMetrics {
    const val expandedBody = 162f
    const val menuBody = 116f

    /** The slime animates at 2.5 frames a second, like the slime in the app. */
    const val frameMillis = 400L

    /** The open island closes by itself after this long without a touch. */
    const val autoCollapseMillis = 6_000L

    /** An alert opens the island for this long. */
    const val alertMillis = 5_000L

    /** The island's text doesn't grow past this (iOS caps the island at the default size too). */
    const val maxFontScale = 1.1f
}

/** What the island asks for when it is touched. Every callback is optional, so previews can leave them out. */
class IslandActions(
    val onTap: () -> Unit = {},
    val onLongPress: () -> Unit = {},
    val onCollapse: () -> Unit = {},
    val onCare: (Need) -> Unit = {},
    val onOpenApp: () -> Unit = {},
    val onHide: () -> Unit = {},
    /** The shape reached [IslandMode] and stopped moving. */
    val onSettled: (IslandMode) -> Unit = {},
)

/**
 * The island inside its window. [window] is where the window sits on the
 * screen; everything is drawn at its place on the screen, so the window can
 * grow and shrink around it without anything moving. [still] jumps straight
 * to [mode] without animating (screenshots and previews).
 */
@Composable
fun IslandView(
    scene: IslandScene,
    placement: IslandPlacement,
    mode: IslandMode,
    window: PxRect,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    still: Boolean = false,
    actions: IslandActions = IslandActions(),
) {
    val reduceMotion = rememberReduceMotion()
    val open = mode != IslandMode.compact
    // Width leads and height follows a moment later: the island stretches like jelly.
    val grow = remember { Animatable(if (still && open) 1f else 0f) }
    val drop = remember { Animatable(if (still && open) 1f else 0f) }
    val appear = remember { Animatable(if (still) 1f else 0f) }
    var shown by remember { mutableStateOf(if (open) mode else IslandMode.expanded) }
    val pull = remember { Animatable(0f) }
    val currentActions by rememberUpdatedState(actions)
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (!still) appear.animateTo(1f, if (reduceMotion) tween(220) else spring(dampingRatio = 0.62f, stiffness = 260f))
    }
    LaunchedEffect(mode) {
        if (open) shown = mode
        val target = if (open) 1f else 0f
        if (still) {
            grow.snapTo(target)
            drop.snapTo(target)
        } else {
            coroutineScope {
                launch { grow.animateTo(target, islandSpec(open, reduceMotion, leading = true)) }
                launch { drop.animateTo(target, islandSpec(open, reduceMotion, leading = false)) }
            }
        }
        pull.snapTo(0f)
        currentActions.onSettled(mode)
    }

    val density = LocalDensity.current
    val pill = placement.pill
    val target = placement.target(shown)
    val gx = grow.value
    val gy = drop.value
    val lift = min(0f, pull.value)
    val rect = PxRect(
        pill.left + (target.left - pill.left) * gx,
        pill.top + (target.top - pill.top) * gy,
        pill.right + (target.right - pill.right) * gx,
        pill.bottom + (target.bottom - pill.bottom) * gy + lift,
    )
    val openness = min(gx, gy).coerceIn(0f, 1f)
    val radius = (placement.pillRadius + (placement.panelRadius - placement.pillRadius) * gy.coerceIn(0f, 1f))
        .coerceAtMost(rect.height / 2).coerceAtLeast(0f)
    val shape: Shape = if (placement.flushTop) {
        RoundedCornerShape(0f, 0f, radius, radius)
    } else {
        RoundedCornerShape(radius)
    }
    val hole = placement.hole
    val origin = TransformOrigin(
        (((hole?.centerX ?: pill.centerX) - rect.left) / rect.width.coerceAtLeast(1f)).coerceIn(0f, 1f),
        (((hole?.centerY ?: pill.centerY) - rect.top) / rect.height.coerceAtLeast(1f)).coerceIn(0f, 1f),
    )

    Box(
        modifier
            .pixelSize(window.width.roundToInt(), window.height.roundToInt())
            // Inside the window but outside the open island: a tap there closes it.
            .then(
                if (open) {
                    Modifier.pointerInput(Unit) { detectTapGestures { currentActions.onCollapse() } }
                } else {
                    Modifier
                },
            ),
    ) {
        // The compact island's touch area reaches below the status bar.
        if (!open) {
            val touch = placement.touch
            Box(
                Modifier
                    .placeAt(touch.left - window.left, touch.top - window.top)
                    .pixelSize(touch.width.roundToInt(), touch.height.roundToInt())
                    .compactGestures(currentActions)
                    .semantics {
                        contentDescription = scene.spoken
                        role = Role.Button
                        onClick(label = "Open the island") {
                            currentActions.onTap()
                            true
                        }
                        onLongClick(label = "Island menu") {
                            currentActions.onLongPress()
                            true
                        }
                    },
            )
        }

        Box(
            Modifier
                .placeAt(rect.left - window.left, rect.top - window.top)
                .pixelSize(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1))
                .graphicsLayer {
                    val a = appear.value
                    alpha = a.coerceIn(0f, 1f)
                    if (!reduceMotion) {
                        scaleX = 0.55f + 0.45f * a
                        scaleY = 0.55f + 0.45f * a
                    }
                    transformOrigin = origin
                }
                .clip(shape)
                .background(IslandInk.island)
                .then(
                    if (open) {
                        Modifier
                            // A tap on the panel itself keeps it open (only outside closes it).
                            .pointerInput(Unit) { detectTapGestures { } }
                            .pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onDragEnd = {
                                        if (pull.value < -28.dp.toPx()) {
                                            currentActions.onCollapse()
                                        } else {
                                            scope.launch { pull.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 500f)) }
                                        }
                                    },
                                    onDragCancel = { scope.launch { pull.animateTo(0f) } },
                                    onVerticalDrag = { change, amount ->
                                        change.consume()
                                        // Follows the finger up (a swipe up closes it) and resists a pull down.
                                        val now = pull.value
                                        val next = (now + amount * if (now + amount > 0) 0.15f else 0.6f)
                                            .coerceIn(-120.dp.toPx(), 12.dp.toPx())
                                        scope.launch { pull.snapTo(next) }
                                    },
                                )
                            }
                    } else {
                        Modifier.compactGestures(currentActions)
                    },
                ),
        ) {
            // Compact content stays where it is on screen and fades as the island opens;
            // in the menu the slime keeps its seat above the rows.
            val compactAlpha = if (shown == IslandMode.menu) 1f else (1f - max(gx, gy) * 2.6f).coerceIn(0f, 1f)
            if (compactAlpha > 0f) {
                CompactContent(
                    scene = scene,
                    placement = placement,
                    animated = animated,
                    modifier = Modifier
                        .placeAt(pill.left - rect.left, pill.top - rect.top)
                        .graphicsLayer { alpha = compactAlpha },
                )
            }
            val contentAlpha = ((openness - 0.35f) / 0.5f).coerceIn(0f, 1f)
            if (contentAlpha > 0f) {
                val inner = Modifier
                    .placeAt(target.left - rect.left, target.top - rect.top)
                    .graphicsLayer {
                        alpha = contentAlpha
                        if (!reduceMotion) {
                            val s = 0.96f + 0.04f * contentAlpha
                            scaleX = s
                            scaleY = s
                            translationY = (1f - contentAlpha) * -6f * density.density
                        }
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
                when (shown) {
                    IslandMode.menu -> MenuContent(placement, currentActions, inner)
                    else -> ExpandedContent(scene, placement, animated, currentActions, inner)
                }
            }
        }
    }
}

/** Opening is springy and physical; closing is quicker and settles without a wobble. Reduce Motion: a short ease. */
private fun islandSpec(opening: Boolean, reduceMotion: Boolean, leading: Boolean): AnimationSpec<Float> = when {
    reduceMotion -> tween(200, easing = FastOutSlowInEasing)
    opening && leading -> spring(dampingRatio = 0.72f, stiffness = 520f, visibilityThreshold = 0.001f)
    opening -> spring(dampingRatio = 0.78f, stiffness = 340f, visibilityThreshold = 0.001f)
    leading -> spring(dampingRatio = 0.9f, stiffness = 620f, visibilityThreshold = 0.001f)
    else -> spring(dampingRatio = 0.95f, stiffness = 760f, visibilityThreshold = 0.001f)
}

private fun Modifier.compactGestures(actions: IslandActions): Modifier = this
    .pointerInput(Unit) {
        detectTapGestures(onTap = { actions.onTap() }, onLongPress = { actions.onLongPress() })
    }
    .pointerInput(Unit) {
        var pulled = 0f
        detectVerticalDragGestures(
            onDragStart = { pulled = 0f },
            onVerticalDrag = { change, amount ->
                pulled += amount
                change.consume()
            },
            // A pull down opens it, like the Dynamic Island.
            onDragEnd = { if (pulled > 16.dp.toPx()) actions.onTap() },
        )
    }

// MARK: - Compact

/** The slime left of the camera and its badge on the right, at their places in the pill. */
@Composable
internal fun CompactContent(scene: IslandScene, placement: IslandPlacement, animated: Boolean, modifier: Modifier = Modifier) {
    val pill = placement.pill
    val s = scene.state
    Box(
        modifier
            .pixelSize(pill.width.roundToInt(), pill.height.roundToInt())
            .clearAndSetSemantics { },
    ) {
        val leading = placement.leading
        IslandSlimeArt(
            stage = s.slimeStage, anim = s.anim, anchor = s.anchor, skin = s.skin, accessories = s.accessories,
            heightPx = placement.slimePx, animated = animated && s.isAnimated,
            modifier = Modifier.placeAt(leading.left - pill.left, leading.bottom - placement.slimePx - pill.top),
        )
        val box = placement.trailing
        when (val badge = scene.badge) {
            is IslandBadge.Friend -> {
                val f = badge.friend
                IslandSlimeArt(
                    stage = f.stage, anim = s.moment?.anim ?: SpriteAnim.of(f.mood), anchor = s.anchor, skin = f.skin,
                    accessories = f.equipped ?: Accessories(), heightPx = placement.slimePx,
                    animated = animated && s.isAnimated, mirrored = true,
                    modifier = Modifier.placeAt(box.left - pill.left, box.bottom - placement.slimePx - pill.top),
                )
            }
            is IslandBadge.Emote -> {
                val size = crisp(pill.height * 0.44f, 9)
                IslandEmote(
                    reel = badge.reel, anchor = s.anchor, sizePx = size, animated = animated && s.isAnimated,
                    modifier = Modifier.placeAt(box.centerX - size / 2f - pill.left, pill.height / 2f - size / 2f),
                )
            }
            is IslandBadge.Item -> {
                val size = crisp(pill.height * 0.54f, 14)
                PixelItem(
                    badge.sprite,
                    Modifier
                        .placeAt(box.centerX - size / 2f - pill.left, pill.height / 2f - size / 2f)
                        .pixelSize(size, size),
                )
            }
        }
    }
}

// MARK: - Expanded

/**
 * The open island: the name and level beside the camera, a bigger slime with
 * what it says in its own pixel voice, and the five needs as buttons.
 */
@Composable
internal fun ExpandedContent(
    scene: IslandScene,
    placement: IslandPlacement,
    animated: Boolean,
    actions: IslandActions,
    modifier: Modifier = Modifier,
) {
    val panel = placement.expanded
    val density = LocalDensity.current
    val s = scene.state
    Box(modifier.pixelSize(panel.width.roundToInt(), panel.height.roundToInt())) {
        CameraStrip(placement, panel) { leadingSlot ->
            if (leadingSlot) {
                Text(
                    scene.name,
                    color = IslandInk.primary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                LevelAndCoins(scene)
            }
        }

        Column(
            Modifier
                .placeAt(0f, placement.expandedBodyTop - panel.top)
                .pixelSize(panel.width.roundToInt(), (panel.bottom - placement.expandedBodyTop).roundToInt())
                .padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(68.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val slimePx = crisp(with(density) { 60.dp.toPx() }, PixelSlimeHeight)
                IslandSlimeArt(
                    stage = s.slimeStage, anim = s.anim, anchor = s.anchor, skin = s.skin, accessories = s.accessories,
                    heightPx = slimePx, animated = animated && s.isAnimated,
                )
                Spacer(Modifier.width(14.dp))
                Column(
                    Modifier
                        .weight(1f)
                        // Level with the slime's body, which sits low in its frame.
                        .padding(top = 10.dp)
                        .semantics(mergeDescendants = true) { },
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        scene.line,
                        modifier = Modifier.semantics { contentDescription = "${scene.name} says: ${scene.line}" },
                        color = IslandInk.primary,
                        style = Theme.voice(18.sp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    scene.caption?.let {
                        Text(
                            it,
                            color = IslandInk.secondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                scene.partner?.let { f ->
                    Spacer(Modifier.width(10.dp))
                    IslandSlimeArt(
                        stage = f.stage, anim = s.moment?.anim ?: SpriteAnim.of(f.mood), anchor = s.anchor, skin = f.skin,
                        accessories = f.equipped ?: Accessories(), heightPx = slimePx,
                        animated = animated && s.isAnimated, mirrored = true,
                        modifier = Modifier.semantics { contentDescription = "${f.name}, ${f.owner}'s slime" },
                    )
                }
            }
            NeedButtons(scene, actions.onCare)
        }
    }
}

/** Level in the slime's own colour, and the coins. */
@Composable
private fun LevelAndCoins(scene: IslandScene) {
    val s = scene.state
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "${scene.levelText}, ${s.coins} coins"
        },
    ) {
        Text(
            scene.levelText,
            color = Color(Brand.bg),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier
                .clip(CircleShape)
                .background(Color(Brand.accent(s.skin)))
                .padding(horizontal = 9.dp, vertical = 3.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PixelItem("coin", Modifier.size(13.dp))
            Text("${s.coins}", color = IslandInk.gold, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/**
 * The strip at the top of an open island, beside the camera: [slot] true is
 * the leading item, false the trailing one. Each goes to the free space on
 * its side of the lens; when a side is too narrow (a punch-hole near the
 * edge), both share the other side.
 */
@Composable
private fun CameraStrip(placement: IslandPlacement, panel: PxRect, slot: @Composable (Boolean) -> Unit) {
    val density = LocalDensity.current
    val inset = with(density) { 22.dp.toPx() }
    val clearance = with(density) { 14.dp.toPx() }
    val hole = placement.hole ?: PxRect(placement.pill.centerX, placement.pill.top, placement.pill.centerX, placement.pill.bottom)
    val stripHeight = (placement.expandedBodyTop - panel.top).coerceAtLeast(1f)
    val leftStart = panel.left + inset
    val leftEnd = hole.left - clearance
    val rightStart = hole.right + clearance
    val rightEnd = panel.right - inset
    val minimum = with(density) { 96.dp.toPx() }
    val leftWide = leftEnd - leftStart >= minimum
    val rightWide = rightEnd - rightStart >= minimum
    val centerY = (placement.pill.centerY - panel.top).coerceIn(0f, stripHeight)

    @Composable
    fun Segment(start: Float, end: Float, arrangement: Arrangement.Horizontal, content: @Composable () -> Unit) {
        Row(
            Modifier
                .placeAt(start - panel.left, 0f)
                .pixelSize((end - start).roundToInt().coerceAtLeast(1), (centerY * 2).roundToInt().coerceAtLeast(1)),
            horizontalArrangement = arrangement,
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }

    when {
        leftWide && rightWide -> {
            Segment(leftStart, leftEnd, Arrangement.Start) { slot(true) }
            Segment(rightStart, rightEnd, Arrangement.End) { slot(false) }
        }
        rightWide -> Segment(rightStart, rightEnd, Arrangement.SpaceBetween) {
            slot(true)
            slot(false)
        }
        else -> Segment(leftStart, max(leftStart + 1, leftEnd), Arrangement.SpaceBetween) {
            slot(true)
            slot(false)
        }
    }
}

/** The five needs as buttons: Feed, Play, Sleep (Wake), Bath, Pet, each with its ring. */
@Composable
private fun NeedButtons(scene: IslandScene, onCare: (Need) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (need in Need.allCases) {
            NeedButton(
                need = need,
                value = scene.state.value(need),
                title = scene.state.actionTitle(need),
                modifier = Modifier.weight(1f),
            ) { onCare(need) }
        }
    }
}

@Composable
private fun NeedButton(need: Need, value: Int, title: String, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.5f, stiffness = 700f), label = "press")
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .heightIn(min = 66.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(if (pressed) IslandInk.tilePressed else IslandInk.tile)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = title
                stateDescription = "${need.title} $value percent"
            }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
    ) {
        NeedDial(need, value, 30.dp)
        Text(title, color = IslandInk.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A need's ring around its pixel icon, in the island's dark palette; red when it runs low. */
@Composable
internal fun NeedDial(need: Need, value: Int, size: Dp, modifier: Modifier = Modifier) {
    val v = (value / 100f).coerceIn(0f, 1f)
    val shownValue by animateFloatAsState(v, tween(300), label = "dial")
    val color = if (value < 25) IslandInk.danger else Theme.color(need, dark = true)
    Box(modifier.size(size).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(IslandInk.track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            if (shownValue > 0f) {
                drawArc(color, -90f, 360f * shownValue, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        PixelItem(Theme.icon(need), Modifier.size(size * 0.46f))
    }
}

// MARK: - Menu

/** Touch and hold: open the app or hide the island. */
@Composable
internal fun MenuContent(placement: IslandPlacement, actions: IslandActions, modifier: Modifier = Modifier) {
    val menu = placement.menu
    Box(modifier.pixelSize(menu.width.roundToInt(), menu.height.roundToInt())) {
        Column(
            Modifier
                .placeAt(0f, placement.menuBodyTop - menu.top)
                .pixelSize(menu.width.roundToInt(), (menu.bottom - placement.menuBodyTop).roundToInt())
                .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 12.dp),
        ) {
            MenuRow("Open Slimy", Icons.AutoMirrored.Filled.OpenInNew, actions.onOpenApp)
            Box(
                Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(IslandInk.divider),
            )
            MenuRow("Hide island", Icons.Filled.VisibilityOff, actions.onHide)
        }
    }
}

@Composable
private fun MenuRow(title: String, icon: ImageVector, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (pressed) IslandInk.tile else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, color = IslandInk.primary, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Icon(icon, contentDescription = null, tint = IslandInk.secondary, modifier = Modifier.size(20.dp))
    }
}

// MARK: - Pixel art

/** Height of the slime art in art pixels. */
internal const val PixelSlimeHeight = 20

/**
 * The slime in the island: crisp pixel art exactly [heightPx] pixels tall
 * (a whole multiple of the art), stepping through its animation at 2.5
 * frames a second from [anchor], or holding the fitting pose when animation
 * is off or the system asks for less motion. Skin overlays (the axolotl's
 * gills) and accessories included.
 */
@Composable
fun IslandSlimeArt(
    stage: Stage,
    anim: SpriteAnim,
    anchor: Instant,
    skin: Skin,
    accessories: Accessories,
    heightPx: Int,
    animated: Boolean,
    modifier: Modifier = Modifier,
    mirrored: Boolean = false,
) {
    val reduceMotion = rememberReduceMotion()
    val moving = animated && !reduceMotion
    val clock by rememberClock(IslandMetrics.frameMillis, running = moving)
    val frames = SlimeSprites.frames(stage, anim)
    val anchors = SlimeSprites.anchors(stage, anim)
    val count = max(1, frames.size)
    val i = if (moving) {
        (((clock - anchor.toEpochMilli()) / IslandMetrics.frameMillis).coerceAtLeast(0L) % count).toInt()
    } else {
        anim.posterIndex(anchor).coerceIn(0, count - 1)
    }
    val widthPx = heightPx * SlimeSprites.width / PixelSlimeHeight.coerceAtLeast(1)
    PixelSlime(
        frame = frames.getOrNull(i) ?: SlimeSprites.blank,
        skin = skin,
        accessories = accessories,
        stage = stage,
        anchor = anchors.getOrNull(i),
        modifier = modifier
            .pixelSize(widthPx, heightPx)
            .graphicsLayer { scaleX = if (mirrored) -1f else 1f },
    )
}

/** The emote the slime "says", a new icon every second like the Dynamic Island, crisp at [sizePx]. */
@Composable
fun IslandEmote(reel: EmoteReel, anchor: Instant, sizePx: Int, animated: Boolean, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val moving = animated && !reduceMotion
    val clock by rememberClock(1000L, running = moving)
    val rows = if (moving) reel.icon((((clock - anchor.toEpochMilli()) / 1000L).coerceAtLeast(0L) % 10).toInt()) else reel.poster
    PixelIcon(rows = rows, color = reel.tint, modifier = modifier.pixelSize(sizePx, sizePx))
}

/** The largest whole multiple of [art] pixels that fits in [px] (at least one). */
internal fun crisp(px: Float, art: Int): Int = max(1, floor(px / art).toInt()) * art

/**
 * Exactly [width] × [height] pixels, whatever the density does to dp. In a
 * smaller parent it keeps its size and hangs out to the right and bottom
 * (instead of being centred), so screen positions stay true.
 */
internal fun Modifier.pixelSize(width: Int, height: Int): Modifier = layout { measurable, constraints ->
    val w = width.coerceAtLeast(0)
    val h = height.coerceAtLeast(0)
    val placeable = measurable.measure(Constraints.fixed(w, h))
    layout(constraints.constrainWidth(w), constraints.constrainHeight(h)) { placeable.place(0, 0) }
}

/** Placed [x], [y] pixels from the parent's top left. */
internal fun Modifier.placeAt(x: Float, y: Float): Modifier = layout { measurable, _ ->
    // Unbounded: content larger than the parent keeps its size and its place instead of being centred.
    val placeable = measurable.measure(Constraints())
    layout(0, 0) { placeable.place(IntOffset(x.roundToInt(), y.roundToInt())) }
}

/** The island's text stops growing at [IslandMetrics.maxFontScale]: the island has a fixed size. */
@Composable
fun IslandTextScale(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val capped = Density(density.density, min(density.fontScale, IslandMetrics.maxFontScale))
    CompositionLocalProvider(LocalDensity provides capped, content = content)
}
