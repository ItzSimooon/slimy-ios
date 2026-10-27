package de.hyprnode.slimy.arcade

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.art.PixelItem
import de.hyprnode.slimy.art.referenceSeconds
import de.hyprnode.slimy.art.rememberFrameClock
import de.hyprnode.slimy.art.rememberReduceMotion
import de.hyprnode.slimy.core.LocalDailyStore
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.model.DailyPlan
import de.hyprnode.slimy.model.EntityRole
import de.hyprnode.slimy.model.GameControl
import de.hyprnode.slimy.model.GameGoal
import de.hyprnode.slimy.model.GameSpec
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.model.arcade.BoardRect
import de.hyprnode.slimy.model.questProgress
import de.hyprnode.slimy.ui.kit.AppButton
import de.hyprnode.slimy.ui.kit.AppButtonKind
import de.hyprnode.slimy.ui.kit.Meter
import de.hyprnode.slimy.ui.kit.Theme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min
import kotlin.math.sin

/**
 * Today's game at the top of the arcade: a window into its pixel world,
 * what kind of game it is, the slime's thought of the day and the quest.
 * [paused] stops the little scene while a game runs on top of the menu.
 */
@Composable
fun TodayCard(playable: Boolean, paused: Boolean = false, play: () -> Unit) {
    val daily = LocalDailyStore.current
    val store = LocalGameStore.current
    val plan by daily.plan.collectAsStateWithLifecycle()
    val state by store.state.collectAsStateWithLifecycle()
    val game = plan.game
    val date = remember(plan.day) {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEMMMd")
        LocalDate.now().format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Theme.radius))
            .background(Theme.card),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clipToBounds()
                .clearAndSetSemantics { },
        ) {
            TodayScene(game, paused, Modifier.matchParentSize())
            Text(
                date,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Theme.ink,
                modifier = Modifier
                    .padding(12.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Today's game", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Theme.accent)
                Text(game.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Theme.ink)
                Text(game.blurb, style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft)
            }

            Facts(game)

            if (plan.story.isNotEmpty()) Thought(plan.story, state.slime.name)

            Quest(plan, claimed = daily.questClaimed, value = state.questProgress(plan).value, target = state.questProgress(plan).target) {
                daily.claimQuest()
            }

            AppButton(
                if (daily.playedToday) "Play Again" else "Play",
                systemIcon = Icons.Filled.PlayArrow,
                enabled = playable,
                action = play,
            )
        }
    }
}

// MARK: Pieces

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Facts(game: GameSpec) {
    FlowRow(
        Modifier.semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (game.goal) {
            GameGoal.timed -> Chip("${game.duration.toInt()} seconds", Icons.Filled.Timer)
            GameGoal.survive -> Chip("Last as long as you can", Icons.Filled.HourglassBottom)
        }
        if (game.lives > 0) Chip(if (game.lives == 1) "1 life" else "${game.lives} lives", Icons.Filled.Favorite)
        Chip(controlName(game.control), controlIcon(game.control))
    }
}

@Composable
private fun Chip(text: String, icon: ImageVector) {
    Row(
        Modifier
            .clip(RoundedCornerShape(Theme.smallRadius))
            .background(Theme.well)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Theme.inkSoft, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = Theme.inkSoft)
    }
}

private fun controlName(control: GameControl): String = when (control) {
    GameControl.drag -> "Drag to move"
    GameControl.lanes -> "Switch lanes"
    GameControl.steer -> "Float around"
    GameControl.jump -> "Tap to jump"
    GameControl.flap -> "Tap to fly"
}

private fun controlIcon(control: GameControl): ImageVector = when (control) {
    GameControl.drag -> Icons.Filled.TouchApp
    GameControl.lanes -> Icons.Filled.SwapHoriz
    GameControl.steer -> Icons.Filled.PanTool
    GameControl.jump -> Icons.Filled.ArrowUpward
    GameControl.flap -> Icons.Filled.Air
}

/** The slime's thought of the day, in its own voice. */
@Composable
private fun Thought(story: String, name: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Theme.smallRadius))
            .background(Theme.well)
            .padding(12.dp)
            .clearAndSetSemantics { contentDescription = "$name says: $story" },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.FormatQuote, contentDescription = null, tint = Theme.accent, modifier = Modifier.size(18.dp))
        Text(story, style = Theme.voice(15.sp), color = Theme.ink)
    }
}

@Composable
private fun Quest(plan: DailyPlan, claimed: Boolean, value: Int, target: Int, collect: () -> Unit) {
    val done = value >= target
    val shown = min(value, target)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Theme.smallRadius))
            .background(Theme.well)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.clearAndSetSemantics {
                contentDescription = "Quest: ${plan.quest.title}"
                stateDescription = if (claimed) "Done, reward collected" else "$shown of $target"
            },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PixelItem("flag", Modifier.padding(top = 2.dp).size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        plan.quest.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Theme.ink,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(8.dp))
                    if (claimed) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Theme.accent, modifier = Modifier.size(20.dp))
                    } else {
                        Text(
                            "$shown/$target",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.SemiBold,
                            color = Theme.inkSoft,
                        )
                    }
                }
                Meter(value = value.toDouble() / maxOf(1, target).toDouble(), color = Theme.accent, height = 6.dp)
                Text(
                    if (claimed) "Reward collected" else "Reward: ${plan.quest.coins} coins",
                    style = MaterialTheme.typography.bodySmall,
                    color = Theme.inkSoft,
                )
            }
        }
        if (done && !claimed) {
            AppButton("Collect ${plan.quest.coins} Coins", icon = "coin", kind = AppButtonKind.secondary, action = collect)
        }
    }
}

/**
 * A little window into today's world: its sky, ground, things and the
 * slime, gently bobbing. Holds still while [paused] or with reduced motion.
 */
@Composable
private fun TodayScene(game: GameSpec, paused: Boolean, modifier: Modifier) {
    val reduceMotion = rememberReduceMotion()
    val clock = rememberFrameClock(running = !paused && !reduceMotion, maxFps = 20)
    val slime = currentSlime()
    val gold = Theme.gold
    Canvas(modifier) {
        val t = referenceSeconds(clock.value)
        val colors = game.theme.colors
        val d = density
        drawBackdrop(colors.top, colors.bottom)
        val groundTop = size.height - 22 * d
        fill(0f, groundTop, size.width, size.height - groundTop, hex(colors.ground))
        fill(0f, groundTop, size.width, 3 * d, Color.White.copy(alpha = 0.25f))
        val things = game.entities.take(5)
        for ((i, e) in things.withIndex()) {
            val slot = (i + 1.0) / (things.size + 1).toDouble()
            val x = size.width * (0.3 + 0.66 * slot)
            val bob = if (reduceMotion) 0.0 else sin(t * 2 + i * 1.7) * 4 * d
            val lift = (28 + (i % 2) * 30) * d
            val y = groundTop - lift + bob
            val side = 26 * e.size * d
            val rect = BoardRect(x - side / 2, y - side / 2, side, side)
            if (e.role == EntityRole.power) {
                val glow = 6.0 * d
                fillEllipse(BoardRect(rect.x - glow, rect.y - glow, rect.width + 2 * glow, rect.height + 2 * glow), gold.copy(alpha = 0.3f))
            }
            drawSprite(e.sprite, rect)
        }
        drawGameSlime(slime, SpriteAnim.play, 46 * d, androidx.compose.ui.geometry.Offset(size.width * 0.15f, groundTop - 22 * d), t)
    }
}
