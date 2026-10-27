package de.hyprnode.slimy.arcade

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.art.PixelCache
import de.hyprnode.slimy.art.SlimeAspect
import de.hyprnode.slimy.art.referenceSeconds
import de.hyprnode.slimy.art.rememberFrameClock
import de.hyprnode.slimy.art.rememberReduceMotion
import de.hyprnode.slimy.core.Feedback
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.model.Brand
import de.hyprnode.slimy.model.SlimeSprites
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.ui.kit.AppButton
import de.hyprnode.slimy.ui.kit.HUDButton
import de.hyprnode.slimy.ui.kit.Theme
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** A patch of mud on the slime, at a spot of its frame (0…1). Its [dirt] fades as you rub. */
private class MudSpot(val unitX: Double, val unitY: Double, val size: Float, val seed: Int) {
    var dirt by mutableDoubleStateOf(1.0)

    /** The blob's corners, from its seed. */
    val blob: List<Pair<Double, Double>> = ArcadeRules.mudBlob(seed)
}

/** A rising bubble, in view pixels; [born] in milliseconds. */
private class Bubble(val origin: Offset, val radius: Float, val wobble: Double, val born: Long)

private val mud = hex(0x6B4A2E)

/** Scrub the mud off with your finger. Bubbles everywhere (App/UI/Screens/BathView.swift). */
@Composable
fun BathView(onClose: () -> Unit) {
    val store = LocalGameStore.current
    val state by store.state.collectAsStateWithLifecycle()
    val reduceMotion = rememberReduceMotion()
    val screen = LocalDensity.current
    var spots by remember { mutableStateOf<List<MudSpot>>(emptyList()) }
    val bubbles = remember { mutableStateListOf<Bubble>() }
    var finished by remember { mutableStateOf(false) }
    var alreadyClean by remember { mutableStateOf(false) }
    var scrubs by remember { mutableIntStateOf(0) }
    val clock = rememberFrameClock(running = !reduceMotion, maxFps = 60)
    val slime = state.slime

    fun makeMess() {
        val count = ArcadeRules.mudSpots(store.state.value.slime.needs.clean)
        if (count == null) {
            alreadyClean = true
            return
        }
        spots = List(count) { i ->
            MudSpot(
                unitX = Random.nextDouble(0.25, 0.75),
                unitY = Random.nextDouble(0.5, 0.9),
                size = Random.nextDouble(26.0, 42.0).toFloat(),
                seed = i,
            )
        }
    }

    fun checkClean() {
        if (finished || !spots.all { it.dirt <= 0.05 }) return
        finished = true
        store.bath()
    }

    fun scrub(point: Offset, origin: Offset, size: Size) {
        if (finished || alreadyClean) return
        scrubs += 1
        if (scrubs % 6 == 0) Feedback.shared.tapHaptic()
        // Rising bubbles are motion; with reduced motion the fading mud is the feedback.
        if (scrubs % 2 == 0 && !reduceMotion) {
            val now = System.currentTimeMillis()
            bubbles.add(
                Bubble(
                    origin = point,
                    radius = with(screen) { Random.nextDouble(5.0, 13.0).toFloat().dp.toPx() },
                    wobble = Random.nextDouble(0.0, 6.0),
                    born = now,
                ),
            )
            bubbles.removeAll { now - it.born > 1600 }
        }
        val reach = with(screen) { 44.dp.toPx() }
        for (spot in spots) {
            val cx = origin.x + spot.unitX.toFloat() * size.width
            val cy = origin.y + spot.unitY.toFloat() * size.height
            if (hypot(cx - point.x, cy - point.y) < reach) spot.dirt = max(0.0, spot.dirt - 0.06)
        }
        checkClean()
    }

    /** TalkBack can't rub, so each Scrub action cleans a good part of the muddiest spot. */
    fun scrubForTalkBack() {
        if (finished || alreadyClean) return
        val spot = spots.maxByOrNull { it.dirt } ?: return
        spot.dirt = max(0.0, spot.dirt - 0.5)
        Feedback.shared.play(Sound.splash)
        checkClean()
    }

    LaunchedEffect(Unit) { makeMess() }
    BackHandler { onClose() }
    HideStatusBar()

    val headline = ArcadeRules.bathHeadline(alreadyClean, finished, spots.count { it.dirt > 0.05 })
    val hint = ArcadeRules.bathHint(alreadyClean, finished)
    val label = if (alreadyClean) "${slime.name} in the bath" else "${slime.name} covered in mud"

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val slimeHeight = with(screen) { 210.dp.toPx() }
        val slimeSize = Size(slimeHeight * SlimeAspect, slimeHeight)
        val origin = Offset((width - slimeSize.width) / 2, height * 0.62f - slimeSize.height)

        Box(
            Modifier
                .fillMaxSize()
                .dragGesture { v -> scrub(v.location, origin, slimeSize) }
                .clearAndSetSemantics {
                    contentDescription = label
                    stateDescription = headline
                    customActions = listOf(CustomAccessibilityAction("Scrub") { scrubForTalkBack(); true })
                },
        ) {
            TileWall()
            Canvas(Modifier.fillMaxSize()) {
                val millis = clock.value
                val t = referenceSeconds(millis)
                val anim = if (finished) SpriteAnim.bath else SpriteAnim.idle
                val frames = SlimeSprites.frames(slime.stage, anim)
                val anchors = SlimeSprites.anchors(slime.stage, anim)
                val i = if (frames.isEmpty()) 0 else ((t * 2.5).toLong() % frames.size).toInt()
                val image = PixelCache.slime(frames.getOrNull(i) ?: SlimeSprites.blank, slime.skin, slime.equipped, anchors.getOrNull(i), true)
                drawPixelImage(image, origin.x, origin.y, slimeSize.width, slimeSize.height, bottom = true)

                for (spot in spots) {
                    if (spot.dirt <= 0) continue
                    val w = spot.size * density
                    val h = w * 0.8f
                    val cx = origin.x + spot.unitX.toFloat() * slimeSize.width
                    val cy = origin.y + spot.unitY.toFloat() * slimeSize.height
                    val path = Path()
                    for ((k, p) in spot.blob.withIndex()) {
                        val x = cx + p.first.toFloat() * w
                        val y = cy + p.second.toFloat() * h
                        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    drawPath(path, mud.copy(alpha = spot.dirt.toFloat().coerceIn(0f, 1f)))
                }

                val cool = Color(Brand.cool)
                for (b in bubbles) {
                    val age = (millis - b.born) / 1000.0
                    if (age >= 1.6 || age < 0) continue
                    val p = Offset(
                        b.origin.x + sin(age * 4 + b.wobble).toFloat() * 10 * density,
                        b.origin.y - age.toFloat() * 90 * density,
                    )
                    val r = b.radius * (1 + age.toFloat() * 0.3f)
                    val alpha = (1 - age / 1.6).toFloat().coerceIn(0f, 1f)
                    drawCircle(cool.copy(alpha = alpha), r, p, style = Stroke(1.5f * density))
                    drawCircle(Color.White.copy(alpha = alpha * 0.8f), r * 0.45f, Offset(p.x - r * 0.3f, p.y - r * 0.3f))
                }
            }
            Water(
                time = { if (reduceMotion) 0.0 else referenceSeconds(clock.value) },
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.42f)
                    .align(Alignment.BottomCenter),
            )
        }

        // Header: how much mud is left, and the way out.
        Row(
            Modifier
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .semantics(mergeDescendants = true) {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Theme.ink)
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft)
            }
            Spacer(Modifier.weight(1f))
            HUDButton(Icons.Filled.Close, "Close") { onClose() }
        }

        AnimatedVisibility(
            visible = finished || alreadyClean,
            enter = if (reduceMotion) EnterTransition.None else slideInVertically(spring()) { it } + fadeIn(),
            exit = if (reduceMotion) ExitTransition.None else slideOutVertically(spring()) { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding(),
        ) {
            AppButton(
                "Done",
                Modifier
                    .widthIn(max = 420.dp)
                    .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                systemIcon = Icons.Filled.Check,
            ) { onClose() }
        }
    }
}

/** Dark tiles behind the tub. */
@Composable
private fun TileWall() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(hex(0x141A2C))
        val tile = 46 * density
        val inset = 2 * density
        val corner = CornerRadius(6 * density, 6 * density)
        val fill = hex(0x1B2340)
        var y = 0f
        while (y < size.height) {
            var x = 0f
            while (x < size.width) {
                drawRoundRect(fill, Offset(x + inset, y + inset), Size(tile - 2 * inset, tile - 2 * inset), corner)
                x += tile
            }
            y += tile
        }
    }
}

/** Two gently rolling layers of water; [time] is 0 when they should hold still. */
@Composable
private fun Water(time: () -> Double, modifier: Modifier) {
    Canvas(modifier) {
        val t = time()
        val step = 6 * density
        for (layer in 0 until 2) {
            val amp = (if (layer == 0) 7 else 5) * density
            val phase = t * (if (layer == 0) 1.6 else -1.1)
            val path = Path()
            path.moveTo(0f, size.height)
            var x = 0f
            while (x <= size.width + step) {
                val px = minOf(x, size.width)
                val y = 14 * density + layer * 8 * density + sin(px / density / 38.0 + phase).toFloat() * amp
                path.lineTo(px, y)
                x += step
            }
            path.lineTo(size.width, size.height)
            path.close()
            drawPath(path, Color(Brand.cool).copy(alpha = if (layer == 0) 0.22f else 0.3f))
        }
    }
}

/** Hides the status bar while the bath is open (`.statusBarHidden(true)`), and brings it back after. */
@Composable
private fun HideStatusBar() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val behavior = controller?.systemBarsBehavior
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.statusBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.statusBars())
            if (behavior != null) controller.systemBarsBehavior = behavior
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
