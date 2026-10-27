package de.hyprnode.slimy.ui.screens.shop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.hyprnode.slimy.art.HabitatScene
import de.hyprnode.slimy.art.LiveSlimeView
import de.hyprnode.slimy.art.rememberReduceMotion
import de.hyprnode.slimy.model.Achievements
import de.hyprnode.slimy.model.FurnitureSlot
import de.hyprnode.slimy.model.Habitat
import de.hyprnode.slimy.model.Slime
import de.hyprnode.slimy.model.Unlock
import de.hyprnode.slimy.ui.kit.SpeechBox
import de.hyprnode.slimy.ui.kit.Theme
import de.hyprnode.slimy.ui.screens.common.isAccessibilityTextSize
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// Shared with the kitchen, the journal and the photo booth.

/** How something is unlocked, in words. */
object UnlockText {
    fun short(unlock: Unlock): String = when (unlock) {
        is Unlock.level -> "Level ${unlock.n}"
        is Unlock.achievement -> "Achievement"
        is Unlock.bond -> "Bond ${unlock.n}"
        is Unlock.season -> monthName(unlock.month)
    }

    fun long(unlock: Unlock): String = when (unlock) {
        is Unlock.level -> "Unlocks at level ${unlock.n}."
        is Unlock.achievement ->
            "Unlocks with the achievement “${Achievements.achievement(unlock.id)?.title ?: unlock.id}”."
        is Unlock.bond -> "Unlocks at a bond of ${unlock.n}."
        is Unlock.season -> "Only for sale in ${monthName(unlock.month)}."
    }

    /** Seasonal unlocks are Gregorian months, named in English like the rest of the app. */
    private fun monthName(month: Int): String = months.getOrNull(month - 1) ?: "season"

    private val months = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
}

/**
 * A habitat filling its frame edge to edge: drawn at the next whole pixel
 * scale and cropped in the middle, so the pixels stay square and crisp.
 */
@Composable
fun CroppedHabitat(
    habitat: Habitat,
    modifier: Modifier = Modifier,
    furniture: Map<FurnitureSlot, String> = emptyMap(),
    date: Instant = Instant.now(),
    animate: Boolean = false,
) {
    BoxWithConstraints(modifier.clipToBounds().clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val grid = HabitatScene.gridWidth
        val scale = max(max(1f, kotlin.math.floor(2f * density.density)), ceil(widthPx / grid))
        val sceneWidth = with(density) { (scale * grid).toDp() }
        HabitatScene(
            habitat = habitat,
            furniture = furniture,
            date = date,
            animate = animate,
            modifier = Modifier.requiredWidth(sceneWidth).fillMaxHeight(),
        )
    }
}

/**
 * The slime standing in one of its places, with what it just said. Used at
 * the top of the shop and the kitchen. [slime] shows another slime than the
 * player's, e.g. one trying something on.
 */
@Composable
fun SlimeShowcase(
    habitat: Habitat,
    modifier: Modifier = Modifier,
    furniture: Map<FurnitureSlot, String> = emptyMap(),
    slime: Slime? = null,
    line: String? = null,
    height: Dp = 180.dp,
    slimeHeight: Dp = 84.dp,
) {
    val reduceMotion = rememberReduceMotion()
    val bigText = isAccessibilityTextSize()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(Theme.radius)),
        ) {
            val density = LocalDensity.current
            val bubbleWidth = maxWidth * 0.82f
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { height.toPx() }
            val grid = HabitatScene.gridWidth
            val scale = max(max(1f, kotlin.math.floor(2f * density.density)), ceil(widthPx / grid))
            val rows = ceil(heightPx / scale).toInt()
            val floor = (rows * 0.66).toInt() * scale
            val feet = with(density) { min(heightPx - 4.dp.toPx(), floor + 10 * scale).toDp() }
            CroppedHabitat(habitat, Modifier.fillMaxWidth().height(height), furniture, animate = !reduceMotion)
            Box(Modifier.fillMaxWidth().height(feet), contentAlignment = Alignment.BottomCenter) {
                Column(
                    Modifier.wrapContentHeight(Alignment.Bottom, unbounded = true),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (!bigText) {
                        Crossfade(targetState = line, animationSpec = tween(200), label = "showcaseLine") { shown ->
                            if (shown != null) {
                                SpeechBox(text = shown, modifier = Modifier.widthIn(max = bubbleWidth))
                            }
                        }
                    }
                    LiveSlimeView(height = slimeHeight, interactive = false, override = slime)
                }
            }
        }
        if (line != null && bigText) {
            Text(
                line,
                style = MaterialTheme.typography.bodyLarge,
                color = Theme.ink,
                modifier = Modifier.semantics { contentDescription = "Slime says: $line" },
            )
        }
    }
}
