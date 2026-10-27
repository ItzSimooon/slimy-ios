package de.hyprnode.slimy.art

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import de.hyprnode.slimy.model.EmoteReel
import de.hyprnode.slimy.model.SlimeSprites
import java.time.Instant

/** The colour a reel's icons are drawn in. */
val EmoteReel.tint: Color get() = Color(color)

/** The icon grid of one step of a reel ('#' = filled), counted from 0 and looping. */
fun EmoteReel.icon(step: Int): List<String> {
    val names = icons
    if (names.isEmpty()) return poster
    val name = names[((step % names.size) + names.size) % names.size]
    return SlimeSprites.emoteIcons[name] ?: poster
}

/**
 * The emote icon the slime "says": its reel of ten icons, a new one every
 * second from [anchor], or the reel's first icon when animation is off or
 * the system asks for less motion.
 */
@Composable
fun EmoteView(
    reel: EmoteReel,
    anchor: Instant,
    size: Dp,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
) {
    val reduceMotion = rememberReduceMotion()
    val moving = animated && !reduceMotion
    val clock by rememberClock(1000L, running = moving)
    val rows = if (moving) {
        val step = ((clock - anchor.toEpochMilli()) / 1000L).coerceAtLeast(0L)
        reel.icon((step % 10).toInt())
    } else {
        reel.poster
    }
    PixelIcon(rows = rows, color = reel.tint, modifier = modifier.size(size))
}
