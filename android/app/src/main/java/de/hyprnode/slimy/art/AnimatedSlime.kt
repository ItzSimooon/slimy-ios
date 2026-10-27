package de.hyprnode.slimy.art

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import de.hyprnode.slimy.model.Accessories
import de.hyprnode.slimy.model.SlimeSprites
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.model.Stage
import kotlinx.coroutines.delay
import java.time.Instant

/** Glyphs are 24 × 20 pixels: width over height of a slime frame. */
const val SlimeAspect: Float = 24f / 20f

/** Seconds since 1 Jan 2001, like Swift's `timeIntervalSinceReferenceDate`, for animation phases. */
fun referenceSeconds(at: Instant = Instant.now()): Double =
    (at.epochSecond - 978_307_200L).toDouble() + at.nano / 1_000_000_000.0

/** Seconds since 2001 from a wall-clock time in milliseconds. */
fun referenceSeconds(millis: Long): Double = (millis - 978_307_200_000L) / 1000.0

/**
 * Whether the system asks for less motion: Android's "Remove animations"
 * (animator duration scale 0). Follows the setting while on screen.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val state = remember { mutableStateOf(readReduceMotion(context)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                state.value = readReduceMotion(context)
            }
        }
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        resolver.registerContentObserver(uri, false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return state.value
}

/** The reduce-motion setting right now, outside composition (notifications, widgets). */
fun readReduceMotion(context: android.content.Context): Boolean =
    runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

/**
 * The wall clock in milliseconds, updated every [periodMillis] while
 * [running] (aligned to whole periods, so second ticks land on the second).
 */
@Composable
fun rememberClock(periodMillis: Long, running: Boolean = true): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMillis, running) {
        now.longValue = System.currentTimeMillis()
        if (!running) return@LaunchedEffect
        while (true) {
            val t = System.currentTimeMillis()
            delay((periodMillis - t % periodMillis).coerceAtLeast(1))
            now.longValue = System.currentTimeMillis()
        }
    }
    return now
}

/** The wall clock in milliseconds, updated on every display frame (at most [maxFps]) while [running]. */
@Composable
fun rememberFrameClock(running: Boolean = true, maxFps: Int = 30): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running, maxFps) {
        now.longValue = System.currentTimeMillis()
        if (!running) return@LaunchedEffect
        val step = 1000L / maxFps.coerceIn(1, 120)
        var last = 0L
        while (true) {
            withFrameMillis { }
            val t = System.currentTimeMillis()
            if (t - last >= step) {
                last = t
                now.longValue = t
            }
        }
    }
    return now
}

/**
 * The slime looping an animation, a new frame every second from [anchor]
 * (the island and Lock Screen counterpart; there are no timer fonts here,
 * so frames are stepped by a clock).
 */
@Composable
fun AnimatedSlime(
    stage: Stage,
    anim: SpriteAnim,
    anchor: Instant,
    skin: Skin,
    height: Dp,
    modifier: Modifier = Modifier,
    accessories: Accessories = Accessories(),
    mirrored: Boolean = false,
    label: String = "Slime",
) {
    val clock by rememberClock(1000L)
    val frames = SlimeSprites.frames(stage, anim)
    val anchors = SlimeSprites.anchors(stage, anim)
    val elapsed = ((clock - anchor.toEpochMilli()) / 1000L).coerceAtLeast(0L)
    val i = (elapsed % frames.size.coerceAtLeast(1)).toInt()
    SlimeFrameBox(frames, anchors, i, skin, accessories, height, mirrored, label, modifier)
}

/**
 * The slime as the island shows it: animated, or a single fitting pose when
 * animation is off or the system asks for less motion.
 */
@Composable
fun IslandSlime(
    stage: Stage,
    anim: SpriteAnim,
    anchor: Instant,
    skin: Skin,
    height: Dp,
    modifier: Modifier = Modifier,
    accessories: Accessories = Accessories(),
    animated: Boolean = true,
    mirrored: Boolean = false,
    label: String = "Slime",
) {
    val reduceMotion = rememberReduceMotion()
    if (animated && !reduceMotion) {
        AnimatedSlime(stage, anim, anchor, skin, height, modifier, accessories, mirrored, label)
    } else {
        val frames = SlimeSprites.frames(stage, anim)
        val anchors = SlimeSprites.anchors(stage, anim)
        val i = anim.posterIndex(anchor).coerceIn(0, (frames.size - 1).coerceAtLeast(0))
        SlimeFrameBox(frames, anchors, i, skin, accessories, height, mirrored, label, modifier)
    }
}

@Composable
private fun SlimeFrameBox(
    frames: List<List<String>>,
    anchors: List<List<Int>>,
    index: Int,
    skin: Skin,
    accessories: Accessories,
    height: Dp,
    mirrored: Boolean,
    label: String,
    modifier: Modifier,
) {
    val frame = if (frames.isEmpty()) SlimeSprites.blank else frames[index.coerceIn(0, frames.size - 1)]
    Box(modifier.semantics { contentDescription = label }) {
        PixelSlime(
            frame = frame,
            skin = skin,
            accessories = accessories,
            anchor = anchors.getOrNull(index),
            modifier = Modifier
                .size(width = height * SlimeAspect, height = height)
                .scale(scaleX = if (mirrored) -1f else 1f, scaleY = 1f),
        )
    }
}
