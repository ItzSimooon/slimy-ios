package de.hyprnode.slimy.ui.screens.crates

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hyprnode.slimy.art.PixelIcon
import de.hyprnode.slimy.art.PixelItem
import de.hyprnode.slimy.art.PixelPainter
import de.hyprnode.slimy.art.PixelSlime
import de.hyprnode.slimy.art.SlimeAspect
import de.hyprnode.slimy.art.drawPixels
import de.hyprnode.slimy.art.rememberClock
import de.hyprnode.slimy.art.rememberFrameClock
import de.hyprnode.slimy.art.rememberReduceMotion
import de.hyprnode.slimy.art.referenceSeconds
import de.hyprnode.slimy.model.Catalog
import de.hyprnode.slimy.model.CrateKind
import de.hyprnode.slimy.model.CrateReward
import de.hyprnode.slimy.model.ItemKind
import de.hyprnode.slimy.model.ItemSprite
import de.hyprnode.slimy.model.Rarity
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.Slime
import de.hyprnode.slimy.model.SlimeSprites
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.model.argb
import de.hyprnode.slimy.ui.kit.Theme
import java.time.Instant
import java.util.UUID
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// Pieces shared by the crate shelf, the opening screen, the drop rates, the
// shop, the gift calendar and the journal (App/UI/Screens/CrateParts.swift).

// MARK: - Words

/** How crates are talked about, the same everywhere. */
object CrateCopy {
    /** "Wooden crate", whether the kind's own title already says "crate" or not. */
    fun name(kind: CrateKind): String =
        if (kind.title.contains("crate", ignoreCase = true)) kind.title else "${kind.title} crate"

    fun coins(n: Int): String = if (n == 1) "1 coin" else "$n coins"

    fun needMore(missing: Int): String =
        if (missing == 1) "You need 1 more coin." else "You need $missing more coins."

    const val contents =
        "Every crate holds one surprise: coins, food, something to wear, furniture, a colour or another crate. Anything you already have turns into coins."
    const val noRealMoney =
        "Crates are earned in the game or bought with coins you earn. They're never sold for real money."
    const val mysteryHint =
        "Mystery crates can't be bought. They come with the seventh daily gift, every fifth level, now and then after a great arcade round, and with some achievements."

    /** Shown instead of a working Buy button once `Crates.maxStack` are waiting. */
    const val shelfFull = "You have the most crates you can keep. Open some first."
}

// MARK: - Rarity

/** The rarity's own colour, for sparks, badges and the flat disc behind a reward. */
val Rarity.displayColor: Color get() = Color(argb(tint))

/**
 * "EPIC" in a capsule of the rarity's colour. The text stays ink, so it
 * reads on any rarity colour in light and dark mode.
 */
@Composable
fun RarityBadge(rarity: Rarity, modifier: Modifier = Modifier, compact: Boolean = false) {
    val color = rarity.displayColor
    Text(
        text = rarity.title.uppercase(),
        fontSize = if (compact) 11.sp else 12.sp,
        fontWeight = if (compact) FontWeight.Bold else FontWeight.ExtraBold,
        letterSpacing = if (compact) 0.2.sp else 0.8.sp,
        color = Theme.ink,
        maxLines = 1,
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.24f))
            .border(if (compact) 1.dp else 1.5.dp, color, CircleShape)
            .padding(horizontal = if (compact) 6.dp else 10.dp, vertical = if (compact) 2.dp else 4.dp)
            .clearAndSetSemantics { contentDescription = rarity.title },
    )
}

// MARK: - Crate art

/**
 * The crate sprites from the sprite data: `crate.wooden` and its open twin
 * `crate.wooden.open`. Both share their bottom rows: closed, the lid sits on
 * the box and the rows above it stay empty; open, the lid stands up behind
 * the box.
 */
object CrateArt {
    fun closed(kind: CrateKind): ItemSprite? = SlimeSprites.items[kind.sprite]

    fun opened(kind: CrateKind): ItemSprite? = SlimeSprites.items["${kind.sprite}.open"]

    /** The first row with any pixels in it. */
    fun firstFilledRow(sprite: ItemSprite): Int =
        sprite.rows.indexOfFirst { row -> row.any { it != '.' } }.coerceAtLeast(0)

    /** The rows of a closed crate that make its lid: the top of what's drawn. */
    fun lidRows(sprite: ItemSprite): IntRange {
        val first = firstFilledRow(sprite)
        val lid = max(1, ((sprite.rows.size - first) * 0.4).roundToInt())
        return first until min(sprite.rows.size, first + lid)
    }

    /** The sprite without the empty rows above it, for icons. */
    fun cropped(sprite: ItemSprite): ItemSprite {
        val first = firstFilledRow(sprite)
        if (first <= 0) return sprite
        return ItemSprite(palette = sprite.palette, rows = sprite.rows.drop(first))
    }

    /** How far down the closed crate starts, as a share of its sprite's height. */
    fun topShare(kind: CrateKind): Float {
        val sprite = closed(kind) ?: return 0f
        if (sprite.rows.isEmpty()) return 0f
        return firstFilledRow(sprite).toFloat() / sprite.rows.size
    }

    /** The crate's own colours, for the splinters a tap knocks off. */
    fun colors(kind: CrateKind): List<Color> {
        val sprite = closed(kind) ?: return emptyList()
        return sprite.palette.keys.sorted().mapNotNull { key -> sprite.palette[key]?.let { Color(argb(it)) } }
    }

    /** The sprite with only the rows in [keep] drawn; the others stay empty but keep their place. */
    fun masked(sprite: ItemSprite, keep: IntRange?): ItemSprite {
        if (keep == null) return sprite
        return ItemSprite(
            palette = sprite.palette,
            rows = sprite.rows.mapIndexed { index, row -> if (index in keep) row else ".".repeat(row.length) },
        )
    }
}

/**
 * Some rows of a sprite, drawn crisp. Rows outside [keep] stay empty but
 * keep their place, so a lid and a box drawn apart line up exactly.
 */
@Composable
fun CratePixels(sprite: ItemSprite, modifier: Modifier = Modifier, keep: IntRange? = null) {
    val cols = max(1, sprite.rows.firstOrNull()?.length ?: 1)
    val count = max(1, sprite.rows.size)
    val image = remember(sprite, keep) { PixelPainter.item(CrateArt.masked(sprite, keep)).toImageBitmap() }
    Spacer(
        modifier
            .aspectRatio(cols.toFloat() / count)
            .clearAndSetSemantics { }
            .drawBehind { drawPixels(image) },
    )
}

/**
 * A crate as pixels. [lift] (0...1) loosens the lid a little with every
 * tap and [tilt] leans it (degrees); [open] shows the open crate.
 * [cropped] leaves out the empty rows above a closed crate, for icons; the
 * opening screen keeps them, so the open crate lines up with the closed one.
 */
@Composable
fun CrateSprite(
    kind: CrateKind,
    modifier: Modifier = Modifier,
    cropped: Boolean = true,
    lift: Float = 0f,
    tilt: Float = 0f,
    open: Boolean = false,
) {
    val opened = if (open) CrateArt.opened(kind) else null
    val closed = CrateArt.closed(kind)
    when {
        opened != null -> Box(modifier, contentAlignment = Alignment.Center) {
            CratePixels(if (cropped) CrateArt.cropped(opened) else opened)
        }
        closed != null -> {
            val sprite = if (cropped) CrateArt.cropped(closed) else closed
            val lid = CrateArt.lidRows(sprite)
            val hinge = (lid.last + 1).toFloat() / max(1, sprite.rows.size)
            BoxWithConstraints(modifier.clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                val density = LocalDensity.current
                val side = with(density) { min(maxWidth.toPx(), maxHeight.toPx()) }
                val loose = lift.coerceIn(0f, 1f)
                val dx = if (open) side * 0.34f else 0f
                val dy = if (open) -side * 0.55f else -side * 0.05f * loose
                CratePixels(sprite, keep = (lid.last + 1) until sprite.rows.size)
                CratePixels(
                    sprite,
                    keep = lid,
                    modifier = Modifier.graphicsLayer {
                        // The lid turns about its own bottom edge.
                        transformOrigin = TransformOrigin(0.5f, hinge)
                        rotationZ = if (open) -34f else tilt
                        translationX = dx
                        translationY = dy
                        // Without an open sprite, opening throws the lid off.
                        alpha = if (open) 0f else 1f
                    },
                )
            }
        }
        else -> Spacer(modifier)
    }
}

// MARK: - Rewards

/**
 * What came out of a crate, drawn big. Colours show the slime wearing them;
 * the legendary reward is always the axolotl.
 */
@Composable
fun CrateRewardArt(reward: CrateReward, slime: Slime, modifier: Modifier = Modifier, size: Dp = 112.dp) {
    val skin = CrateRewards.shownSkin(reward)
    val kind = (reward.kind as? CrateReward.Kind.crate)?.kind
    when {
        skin != null -> RewardSlime(skin, slime, modifier.size(width = size * 1.4f, height = size * 1.4f / SlimeAspect))
        kind != null -> CrateSprite(kind, modifier.size(size))
        else -> CrateRewardIcon(reward, modifier.size(size))
    }
}

@Composable
private fun RewardSlime(skin: Skin, slime: Slime, modifier: Modifier) {
    val reduceMotion = rememberReduceMotion()
    val clock by rememberClock(if (reduceMotion) 3_600_000L else 220L)
    val t = referenceSeconds(clock)
    // A cheer now and then, otherwise just being there.
    val anim = if (reduceMotion || (t / 2.4).toLong() % 3 != 0L) SpriteAnim.idle else SpriteAnim.celebrate
    val frames = SlimeSprites.frames(slime.stage, anim)
    val anchors = SlimeSprites.anchors(slime.stage, anim)
    val count = max(1, frames.size)
    val i = if (reduceMotion) anim.posterIndex(Instant.ofEpochMilli(clock)) % count else ((t / 0.22).toLong() % count).toInt()
    PixelSlime(
        frame = frames.getOrNull(i) ?: SlimeSprites.blank,
        skin = skin,
        accessories = slime.equipped,
        stage = slime.stage,
        anchor = anchors.getOrNull(i),
        modifier = modifier,
    )
}

/** Reward facts the art and the words share. */
object CrateRewards {
    /** The colour the reward art shows: the axolotl for the legendary reward, else a won colour. */
    fun shownSkin(reward: CrateReward): Skin? {
        if (reward.rarity == Rarity.legendary) return Skin.axolotl
        return (reward.kind as? CrateReward.Kind.skin)?.skin
    }

    /** The catalog effect a reward stands for, if it is one (effects have no sprite of their own). */
    fun effectID(reward: CrateReward): String? {
        val id = (reward.kind as? CrateReward.Kind.item)?.id ?: return null
        return if (Catalog.item(id)?.kind == ItemKind.effect) id else null
    }

    /** The sprite to draw: the reward's own icon, else the food or item it stands for, else a coin. */
    fun spriteName(reward: CrateReward, has: (String) -> Boolean = { SlimeSprites.items[it] != null }): String {
        val names = mutableListOf(reward.icon)
        when (val kind = reward.kind) {
            is CrateReward.Kind.coins -> names.add("coin")
            is CrateReward.Kind.food -> names.add(kind.food.sprite)
            is CrateReward.Kind.item -> Catalog.item(kind.id)?.let { names.add(it.sprite) }
            is CrateReward.Kind.skin, is CrateReward.Kind.crate -> Unit
        }
        return names.firstOrNull(has) ?: "coin"
    }
}

/**
 * The pixel icon of a reward: its own sprite if there is one, otherwise the
 * sprite of the food or item it stands for. Effects show what they make
 * float around the slime.
 */
@Composable
fun CrateRewardIcon(reward: CrateReward, modifier: Modifier = Modifier) {
    val effect = CrateRewards.effectID(reward)
    if (SlimeSprites.items[reward.icon] == null && effect != null) {
        Box(modifier.padding(8.dp), contentAlignment = Alignment.Center) {
            EffectIcon(effect, Modifier.fillMaxSize())
        }
    } else {
        PixelItem(CrateRewards.spriteName(reward), modifier)
    }
}

/** What an effect makes float around the slime: hearts, bubbles or sparkles. */
@Composable
fun EffectIcon(effect: String, modifier: Modifier = Modifier) {
    when (effect) {
        "effect.hearts" -> PixelItem("heart", modifier)
        "effect.bubbles" -> PixelItem("bubbleItem", modifier)
        else -> PixelIcon(SlimeSprites.emoteIcons["sparkle"] ?: emptyList(), Theme.gold, modifier.aspectRatio(1f))
    }
}

// MARK: - Sparks

/** Pixel sparks thrown out of a point, falling back with a little gravity. Sizes in dp. */
data class SparkBurst(
    val colors: List<Color>,
    val count: Int = 24,
    /** dp per second at the start. */
    val speed: Float = 420f,
    val size: Float = 7f,
    val life: Double = 1.1,
    val gravity: Float = 760f,
    /** Where they start, from the centre of the layer (dp). */
    val origin: Offset = Offset.Zero,
    /** 0: straight up, 1: all around. */
    val spread: Double = 0.6,
    val start: Long = System.currentTimeMillis(),
    val seed: Long = Random.nextLong(1, Long.MAX_VALUE),
    val id: UUID = UUID.randomUUID(),
)

/** Draws spark bursts while any is in the air. */
@Composable
fun SparkLayer(bursts: List<SparkBurst>, modifier: Modifier = Modifier) {
    val clock by rememberFrameClock(running = bursts.isNotEmpty(), maxFps = 60)
    Canvas(modifier.clearAndSetSemantics { }) {
        val now = clock
        val unit = density
        for (burst in bursts) {
            if (burst.colors.isEmpty()) continue
            val t = (now - burst.start) / 1000.0
            if (t < 0 || t >= burst.life) continue
            val fade = (1 - t / burst.life).toFloat()
            for (i in 0 until burst.count) {
                val turn = (SparkMath.unit(burst.seed, i, 1) - 0.5) * 2 * Math.PI * burst.spread
                val angle = -Math.PI / 2 + turn
                val v = burst.speed * (0.45 + 0.75 * SparkMath.unit(burst.seed, i, 2))
                val dx = (cos(angle) * v * t).toFloat()
                val fall = (0.5 * burst.gravity * t * t).toFloat()
                val dy = (sin(angle) * v * t).toFloat() + fall
                val x = size.width / 2 + (burst.origin.x + dx) * unit
                val y = size.height / 2 + (burst.origin.y + dy) * unit
                val scale = (0.6 + 0.8 * SparkMath.unit(burst.seed, i, 3)).toFloat()
                val side = (burst.size * scale * unit).roundToInt().toFloat()
                drawRect(
                    color = burst.colors[i % burst.colors.size],
                    topLeft = Offset((x - side / 2).roundToInt().toFloat(), (y - side / 2).roundToInt().toFloat()),
                    size = Size(side, side),
                    alpha = fade.coerceIn(0f, 1f),
                )
            }
        }
    }
}

/** Steady random numbers for sparks, so a burst looks the same in every frame. */
object SparkMath {
    /** A number in 0 until 1 for one spark ([index]) and one property ([salt]). */
    fun unit(seed: Long, index: Int, salt: Long): Double {
        var z = seed.toULong() + index.toULong() * 0x9E3779B97F4A7C15uL + salt.toULong() * 0xD1B54A32D192ED03uL
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        z = z xor (z shr 31)
        return (z shr 11).toDouble() / (1uL shl 53).toDouble()
    }
}

/** A crate on a soft square, the icon of a row. */
@Composable
fun CrateIcon(kind: CrateKind, modifier: Modifier = Modifier, size: Dp = 48.dp, padding: Dp = 7.dp) {
    Box(
        modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(size * 0.26f))
            .background(Theme.well)
            .padding(padding)
            .semantics { },
        contentAlignment = Alignment.Center,
    ) {
        CrateSprite(kind, Modifier.fillMaxSize())
    }
}
