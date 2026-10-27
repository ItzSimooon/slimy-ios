package de.hyprnode.slimy.arcade

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.art.PixelCache
import de.hyprnode.slimy.art.PixelSlime
import de.hyprnode.slimy.art.SlimeAspect
import de.hyprnode.slimy.core.Feedback
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.model.GameID
import de.hyprnode.slimy.model.GameSpec
import de.hyprnode.slimy.model.Slime
import de.hyprnode.slimy.model.SlimeSprites
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.model.argb
import de.hyprnode.slimy.model.arcade.ArcadeEvent
import de.hyprnode.slimy.model.arcade.BoardRect
import de.hyprnode.slimy.model.arcade.BoardSpace
import de.hyprnode.slimy.model.arcade.GameSession
import de.hyprnode.slimy.model.arcade.Juice
import de.hyprnode.slimy.model.arcade.mixColor
import de.hyprnode.slimy.model.arcade.sound
import de.hyprnode.slimy.ui.kit.Fonts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The drawing half of the iPhone app's App/Arcade/GameKit.swift: the round
// as Compose state, the game loop, sprites in a Canvas, the player's slime
// inside a game, the banded backdrop and the game feel. The pure half
// (GameSession, BoardSpace, Juice) lives in `model.arcade`.

// MARK: - The round

/**
 * One round of one game, observable by Compose. It wraps the model's
 * [GameSession] (whose id is the reward key) and mirrors its values into
 * snapshot state, so the host's top bar and cards follow the game.
 */
@Stable
class SessionState(val session: GameSession) {
    val game: GameID get() = session.game
    val id: UUID get() = session.id
    val seed: ULong get() = session.seed

    /** The day's building-block game, for `daily`. */
    val spec: GameSpec? get() = session.spec

    private val _score = mutableIntStateOf(session.score)
    private val _status = mutableStateOf(session.status)
    private val _paused = mutableStateOf(session.paused)
    private val _ended = mutableStateOf(session.ended)

    var score: Int
        get() = _score.intValue
        set(value) {
            session.score = value
            if (_score.intValue != value) _score.intValue = value
        }

    /** Short text for the top bar, like the time left. */
    var status: String
        get() = _status.value
        set(value) {
            session.status = value
            if (_status.value != value) _status.value = value
        }

    var paused: Boolean
        get() = _paused.value
        set(value) {
            session.paused = value
            if (_paused.value != value) _paused.value = value
        }

    val ended: Boolean get() = _ended.value

    val running: Boolean get() = !paused && !ended

    fun end() {
        session.end()
        _ended.value = session.ended
    }
}

/** Plays the sounds (and their haptics) of engine events. */
fun List<ArcadeEvent>.play() {
    for (event in this) Feedback.shared.play(event.sound)
}

/**
 * Background work a round starts that must outlive the game screen:
 * answering a challenge, uploading records. Short calls on the main thread.
 */
internal val ArcadeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

// MARK: - Loop

/**
 * Calls [step] once per display frame (about 60 times a second) while
 * [running], with the seconds since the last frame. Long gaps (a hitch,
 * the app coming back) count as one short step.
 */
@Composable
fun GameLoop(running: Boolean, step: (Double) -> Unit) {
    val current by rememberUpdatedState(step)
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = min(0.05, max(0.0, (now - last) / 1_000_000_000.0))
                last = now
                current(dt)
            }
        }
    }
}

// MARK: - Input

/** A finger on the board: where it is, where it went down, and the size of the area (pixels). */
class DragValue(val location: Offset, val startLocation: Offset, val size: IntSize) {
    val translation: Offset get() = location - startLocation

    /** The board the finger is on. */
    val space: BoardSpace get() = BoardSpace(size.width.toFloat(), size.height.toFloat())
}

/**
 * SwiftUI's `DragGesture(minimumDistance:)`: [onChanged] while the finger
 * moves (from touch-down when the distance is 0), [onEnded] when it lifts,
 * but only if the gesture started.
 */
@Composable
fun Modifier.dragGesture(
    minimumDistance: Dp = 0.dp,
    onEnded: (DragValue) -> Unit = {},
    onChanged: (DragValue) -> Unit = {},
): Modifier {
    val changed by rememberUpdatedState(onChanged)
    val ended by rememberUpdatedState(onEnded)
    val minPx = with(LocalDensity.current) { minimumDistance.toPx() }
    return this.pointerInput(minPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = down.position
            var started = minPx <= 0f
            var last = start
            if (started) changed(DragValue(start, start, size))
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                last = change.position
                if (!change.pressed) break
                if (!started && (last - start).getDistance() >= minPx) started = true
                if (started) {
                    change.consume()
                    changed(DragValue(last, start, size))
                }
            }
            if (started) ended(DragValue(last, start, size))
        }
    }
}

/** Lets TalkBack read a line, but only while it is on (`UIAccessibility.post(.announcement)`). */
@Composable
fun rememberAnnouncer(): (String) -> Unit {
    val view = LocalView.current
    val context = LocalContext.current
    return remember(view, context) {
        { text: String ->
            val manager = context.getSystemService(AccessibilityManager::class.java)
            @Suppress("DEPRECATION")
            if (manager?.isTouchExplorationEnabled == true) view.announceForAccessibility(text)
        }
    }
}

// MARK: - Layout helpers

/** Places the content centred on a point (pixels) of the parent box; use after `fillMaxSize()`. */
fun Modifier.centeredAt(x: Float, y: Float): Modifier = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.place((x - placeable.width / 2f).roundToInt(), (y - placeable.height / 2f).roundToInt())
    }
}

/** Draws the content without colour (locked games). */
fun Modifier.grayscale(enabled: Boolean): Modifier {
    if (!enabled) return this
    val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    return this.drawWithContent {
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(Offset.Zero, size), paint)
            drawContent()
            canvas.restore()
        }
    }
}

// MARK: - Colours

/** A `0xRRGGBB` colour. */
fun hex(rgb: Long, alpha: Float = 1f): Color = Color(argb(rgb)).let { if (alpha == 1f) it else it.copy(alpha = alpha.coerceIn(0f, 1f)) }

/** A Compose colour as the ARGB long the model's Juice takes. */
fun Color.argbLong(): Long = toArgb().toLong() and 0xFFFFFFFFL

/** The system red behind a hit flash. */
val FlashRed = Color(0xFFFF3B30)

// MARK: - Sprites in a Canvas

/**
 * Draws a pixel bitmap fitted into a box (pixels), keeping its pixels
 * square and whole, centred (or standing on the bottom), never smoothed.
 */
fun DrawScope.drawPixelImage(
    image: ImageBitmap,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    bottom: Boolean = false,
    alpha: Float = 1f,
) {
    val cols = image.width
    val rows = image.height
    val px = ArcadeRules.pixelSize(cols, rows, width.toDouble(), height.toDouble()).toFloat()
    if (px <= 0f || alpha <= 0f) return
    val w = cols * px
    val h = rows * px
    val x = left + (width - w) / 2
    val y = if (bottom) top + height - h else top + (height - h) / 2
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(cols, rows),
        dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
        dstSize = IntSize(max(1, w.roundToInt()), max(1, h.roundToInt())),
        alpha = alpha.coerceIn(0f, 1f),
        filterQuality = FilterQuality.None,
    )
}

/** Draws an item sprite fitted into [rect], keeping its pixels square. */
fun DrawScope.drawSprite(name: String, rect: BoardRect, alpha: Float = 1f) {
    val sprite = SlimeSprites.items[name] ?: return
    val image = PixelCache.item(name, sprite)
    drawPixelImage(image, rect.x.toFloat(), rect.y.toFloat(), rect.width.toFloat(), rect.height.toFloat(), alpha = alpha)
}

/**
 * Draws a small pixel pattern (one character per pixel, "." is empty)
 * fitted into [rect] with square pixels, bottom-aligned. [palette] maps
 * characters to `0xRRGGBB` colours.
 */
fun DrawScope.drawPixelRows(rows: List<String>, palette: Map<Char, Long>, rect: BoardRect, alpha: Float = 1f) {
    val image = PixelCache.layered(rows, palette.mapValues { argb(it.value) })
    drawPixelImage(image, rect.x.toFloat(), rect.y.toFloat(), rect.width.toFloat(), rect.height.toFloat(), bottom = true, alpha = alpha)
}

fun DrawScope.fill(rect: BoardRect, color: Color) {
    drawRect(color, Offset(rect.x.toFloat(), rect.y.toFloat()), Size(rect.width.toFloat(), rect.height.toFloat()))
}

fun DrawScope.fill(x: Float, y: Float, width: Float, height: Float, color: Color) {
    if (width <= 0f || height <= 0f) return
    drawRect(color, Offset(x, y), Size(width, height))
}

fun DrawScope.fillEllipse(rect: BoardRect, color: Color) {
    drawOval(color, Offset(rect.x.toFloat(), rect.y.toFloat()), Size(rect.width.toFloat(), rect.height.toFloat()))
}

fun DrawScope.strokeEllipse(rect: BoardRect, color: Color, width: Float) {
    drawOval(color, Offset(rect.x.toFloat(), rect.y.toFloat()), Size(rect.width.toFloat(), rect.height.toFloat()), style = Stroke(width))
}

/** A chunky pixel rectangle with a darker bottom edge. */
fun DrawScope.block(rect: BoardRect, color: Color, edge: Color? = null, edgeHeight: Double = 0.22) {
    block(rect.x.toFloat(), rect.y.toFloat(), rect.width.toFloat(), rect.height.toFloat(), color, edge, edgeHeight)
}

fun DrawScope.block(x: Float, y: Float, width: Float, height: Float, color: Color, edge: Color? = null, edgeHeight: Double = 0.22) {
    fill(x, y, width, height, color)
    if (edge != null) {
        val h = height * edgeHeight.toFloat()
        fill(x, y + height - h, width, h, edge)
    }
}

/**
 * Banded sky behind the action games, eight flat stripes like an old
 * handheld instead of a smooth gradient (`GameBackdrop`).
 */
fun DrawScope.drawBackdrop(top: Long = 0x9FD4E8, bottom: Long = 0xD8F0E0) {
    val bands = 8
    val h = size.height / bands
    for (i in 0 until bands) {
        val t = i.toDouble() / (bands - 1)
        fill(0f, i * h, size.width, h + 1, hex(mixColor(top, bottom, t)))
    }
}

// MARK: - The player's slime inside a game

/** The player's slime, as the store has it right now. */
@Composable
fun currentSlime(): Slime = LocalGameStore.current.state.collectAsStateWithLifecycle().value.slime

/** The frame of an animation at [time] seconds: three frames a second. */
private fun frameIndex(time: Double, count: Int): Int =
    if (count <= 0) 0 else ((max(0.0, time) * 3).toLong() % count).toInt()

/**
 * Draws the player's slime centred on [center] (pixels), [height] pixels
 * tall: `GameSlime` placed with `.position`. [squashX]/[squashY] stretch it
 * around its feet, [mirrored] turns it to face left.
 */
fun DrawScope.drawGameSlime(
    slime: Slime,
    anim: SpriteAnim,
    height: Float,
    center: Offset,
    time: Double,
    mirrored: Boolean = false,
    alpha: Float = 1f,
    squashX: Float = 1f,
    squashY: Float = 1f,
) {
    val frames = SlimeSprites.frames(slime.stage, anim)
    val anchors = SlimeSprites.anchors(slime.stage, anim)
    val i = frameIndex(time, frames.size)
    val frame = frames.getOrNull(i) ?: SlimeSprites.blank
    val image = PixelCache.slime(frame, slime.skin, slime.equipped, anchors.getOrNull(i), true)
    val width = height * SlimeAspect
    val left = center.x - width / 2
    val top = center.y - height / 2
    withTransform({
        if (squashX != 1f || squashY != 1f) scale(squashX, squashY, pivot = Offset(center.x, top + height))
        if (mirrored) scale(-1f, 1f, pivot = center)
    }) {
        drawPixelImage(image, left, top, width, height, bottom = true, alpha = alpha)
    }
}

/** The player's slime as a view (cards, puzzle tiles). [time] in seconds picks the frame. */
@Composable
fun GameSlime(
    height: Dp,
    time: Double,
    modifier: Modifier = Modifier,
    anim: SpriteAnim = SpriteAnim.idle,
    mirrored: Boolean = false,
) {
    val s = currentSlime()
    val frames = SlimeSprites.frames(s.stage, anim)
    val anchors = SlimeSprites.anchors(s.stage, anim)
    val i = frameIndex(time, frames.size)
    PixelSlime(
        frame = frames.getOrNull(i) ?: SlimeSprites.blank,
        skin = s.skin,
        accessories = s.equipped,
        stage = s.stage,
        anchor = anchors.getOrNull(i),
        modifier = modifier
            .size(width = height * SlimeAspect, height = height)
            .graphicsLayer { scaleX = if (mirrored) -1f else 1f },
    )
}

// MARK: - Text in a Canvas

/** Measures and draws the pixel-world numbers and words inside a game board. */
class GameText(val measurer: TextMeasurer, val pixel: FontFamily) {
    fun style(size: Float, bold: Boolean = true): TextStyle =
        TextStyle(fontFamily = pixel, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, fontSize = size.sp)
}

/** Pixel numbers and words in a game's HUD (`Brand.pixel(size)`). */
@Composable
fun pixelStyle(size: Float): TextStyle =
    TextStyle(fontFamily = Fonts.pixel, fontWeight = FontWeight.Bold, fontSize = size.sp)

@Composable
fun rememberGameText(): GameText {
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val pixel = Fonts.pixel
    return remember(measurer, pixel) { GameText(measurer, pixel) }
}

/**
 * Draws [text] in the pixel font centred on [at] (pixels), or with its
 * leading edge there when [leading].
 */
fun DrawScope.drawGameText(
    text: GameText,
    string: String,
    size: Float,
    color: Color,
    at: Offset,
    leading: Boolean = false,
    alpha: Float = 1f,
) {
    val layout = text.measurer.measure(string, text.style(size))
    val w = layout.size.width.toFloat()
    val h = layout.size.height.toFloat()
    val x = if (leading) at.x else at.x - w / 2
    drawText(layout, color = color, topLeft = Offset(x, at.y - h / 2), alpha = alpha.coerceIn(0f, 1f))
}

// MARK: - Game feel

/** Draws the juice's particles and score popups (`Juice.draw`). */
fun DrawScope.drawJuice(juice: Juice, space: BoardSpace, text: GameText) {
    for (p in juice.particles) {
        val alpha = max(0.0, p.life / p.maxLife).toFloat()
        val r = space.centered(p.x, p.y, p.size, p.size)
        fill(r, Color(p.color).copy(alpha = alpha.coerceIn(0f, 1f) * (Color(p.color).alpha)))
    }
    for (p in juice.popups) {
        val alpha = max(0.0, 1 - p.age / 0.9).toFloat()
        val at = space.point(p.x, p.y)
        drawGameText(text, p.text, if (p.big) 22f else 15f, Color(p.color), Offset(at.x.toFloat(), at.y.toFloat()), alpha = alpha)
    }
}

/** The screen shake: `.offset(juice.shakeOffset(time))`, in dp. Read while composing each frame. */
fun Modifier.shake(juice: Juice, time: Double): Modifier {
    val o = juice.shakeOffset(time)
    if (o.width == 0.0 && o.height == 0.0) return this
    return this.offset(x = o.width.dp, y = o.height.dp)
}

/** The red hit flash over a board. */
fun DrawScope.drawFlash(juice: Juice) {
    val a = (juice.flash * 1.2).toFloat().coerceIn(0f, 1f)
    if (a > 0f) drawRect(FlashRed.copy(alpha = a))
}

/** The current board for a size in pixels. */
fun boardSpace(size: Size): BoardSpace = BoardSpace(size.width, size.height)

/** A board point in view pixels, as a Compose offset. */
fun BoardSpace.offset(x: Double, y: Double): Offset {
    val p = point(x, y)
    return Offset(p.x.toFloat(), p.y.toFloat())
}

/**
 * Counts frames so Compose redraws a game whose engine is a plain object:
 * the loop calls [tick] after stepping, the view reads [value] while
 * composing and drawing.
 */
@Stable
class FrameTicker {
    private val state = androidx.compose.runtime.mutableLongStateOf(0L)
    val value: Long get() = state.longValue

    fun tick() {
        state.longValue = state.longValue + 1
    }
}

@Composable
fun rememberTicker(): FrameTicker = remember { FrameTicker() }

/**
 * Keeps touches from reaching whatever lies below this layer (Compose stops
 * hit testing at the first sibling that takes pointer input), while the
 * layer's own children still get them. Laid over a game while its round
 * isn't running (`.allowsHitTesting(session.running)`), and behind the
 * host's cards.
 */
fun Modifier.swallowTouches(): Modifier = this.pointerInput(Unit) {
    awaitEachGesture { awaitFirstDown(requireUnconsumed = false) }
}
