package de.hyprnode.slimy.arcade

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import de.hyprnode.slimy.art.rememberReduceMotion
import de.hyprnode.slimy.model.Slime
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.model.arcade.Board
import de.hyprnode.slimy.model.arcade.BoardSpace
import de.hyprnode.slimy.model.arcade.Juice
import de.hyprnode.slimy.model.arcade.SlimeStackEngine
import de.hyprnode.slimy.model.arcade.mixColor
import de.hyprnode.slimy.ui.kit.Theme
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Jelly colours: fill and darker edge (`0xRRGGBB`). */
private val jelly = listOf(
    0x7DD86BL to 0x4E9E45L, 0xF58AB0L to 0xC25782L, 0x6FC3F0L to 0x3E8CC0L,
    0xF7D35CL to 0xC9A12EL, 0xB08CF0L to 0x7E5CC0L, 0xF5A05AL to 0xC2702EL,
)
private val goldJelly = 0xFFD166L to 0xC9962EL

/** Fixed star field for the high sky. */
private val stackStars = ArcadeRules.scatter(77uL, 26)

private const val white = 0xFFFFFFFFL

/** Slime Stack: the slime rides the sliding jelly block; a tap drops it on the tower. */
@Composable
fun SlimeStackView(session: SessionState) {
    val engine = remember { SlimeStackEngine(session.seed) }
    val reduceMotion = rememberReduceMotion()
    val juice = remember { Juice().also { it.reduceMotion = reduceMotion } }
    val ticker = rememberTicker()
    var touching by remember { mutableStateOf(false) }
    var mood by remember { mutableStateOf(Pair(SpriteAnim.idle, 0.0)) }
    var endIn by remember { mutableStateOf<Double?>(null) }
    val slime = currentSlime()
    val text = rememberGameText()
    val gold = Theme.gold.argbLong()
    val love = Theme.love

    fun report() {
        session.score = engine.score
        session.status = ArcadeRules.blocks(engine.height)
    }

    fun drop() {
        if (!session.running || engine.isOver) return
        val before = engine.current
        val below = engine.top
        val result = engine.drop()
        engine.drainEvents().play()
        val y = SlimeStackEngine.y(before.level) + engine.scroll
        when (result) {
            SlimeStackEngine.Drop.perfect -> {
                val placed = engine.top
                juice.burst(placed.left, y + 4, white, count = 6, power = 40.0)
                juice.burst(placed.right, y + 4, white, count = 6, power = 40.0)
                juice.popup("Perfect", placed.x, y - 10, white, big = engine.combo >= 3)
                juice.bounce()
                mood = Pair(SpriteAnim.celebrate, engine.time + 0.6)
            }
            SlimeStackEngine.Drop.cut -> {
                val edge = if (before.x > below.x) below.right else below.left
                juice.burst(edge, y + 4, de.hyprnode.slimy.model.argb(jelly[before.color % jelly.size].first), count = 8)
                juice.bounce()
                mood = Pair(SpriteAnim.idle, 0.0)
            }
            SlimeStackEngine.Drop.missed -> {
                juice.hit()
                mood = Pair(SpriteAnim.confused, engine.time + 5)
                endIn = 0.9
            }
            SlimeStackEngine.Drop.ignored -> Unit
        }
        if (before.gold && (result == SlimeStackEngine.Drop.perfect || result == SlimeStackEngine.Drop.cut)) {
            juice.burst(before.x, y, gold, count = 14, power = 70.0)
            juice.popup("+${SlimeStackEngine.goldBonus}", before.x, y - 20, gold, big = true)
        }
        report()
        ticker.tick()
    }

    LaunchedEffect(reduceMotion) { juice.reduceMotion = reduceMotion }
    LaunchedEffect(Unit) { report() }
    LaunchedEffect(session.running) { if (!session.running) touching = false }
    GameLoop(session.running) { dt ->
        engine.step(dt)
        engine.drainEvents().play()
        juice.step(dt)
        report()
        val left = endIn
        if (left != null) {
            endIn = left - dt
            if (left - dt <= 0) session.end()
        }
        ticker.tick()
    }

    @Suppress("UNUSED_VARIABLE") val frame = ticker.value
    val value = "Tower of ${engine.height} blocks, ${engine.score} points"
    Box(
        Modifier
            .fillMaxSize()
            .dragGesture(onEnded = { touching = false }) {
                if (!touching) {
                    touching = true
                    drop()
                }
            }
            .clearAndSetSemantics {
                contentDescription = "Slime Stack. A jelly block slides above the tower. Drop it when it is right above the block below."
                stateDescription = value
                onClick(label = "Drop block") { drop(); true }
                customActions = listOf(CustomAccessibilityAction("Drop block") { drop(); true })
            },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().shake(juice, engine.time)) {
            ticker.value
            val space = BoardSpace(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
            Canvas(Modifier.fillMaxSize()) {
                ticker.value
                // The sky gets darker the higher the tower grows.
                val height = min(1.0, engine.height.toDouble() / 40)
                drawBackdrop(mixColor(0x9FD4E8, 0x1B1F4A, height), mixColor(0xF6E7C8, 0x4A3A7A, height))
                drawStars(engine, space, max(0.0, height * 2 - 0.6))
                drawStackGround(engine, space)
                drawMarks(engine, space, text)
                for (block in engine.tower) {
                    if (visible(engine, block.level)) drawStackBlock(engine, block, space)
                }
                if (!engine.isOver) drawStackBlock(engine, engine.current, space)
                for (piece in engine.pieces) drawPiece(engine, piece, space)
                drawJuice(juice, space, text)
                drawRider(engine, slime, mood, juice, space)
            }
            // The HUD: perfect drops in a row.
            val combo = max(2, engine.combo)
            Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.TopEnd) {
                AnimatedVisibility(
                    visible = engine.combo >= 2,
                    enter = if (reduceMotion) EnterTransition.None else scaleIn(tween(200)) + fadeIn(tween(200)),
                    exit = if (reduceMotion) ExitTransition.None else scaleOut(tween(200)) + fadeOut(tween(200)),
                ) {
                    Text(
                        "Perfect x$combo",
                        style = pixelStyle(15f),
                        color = Color.White,
                        modifier = Modifier
                            .background(love)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            ticker.value
            drawFlash(juice)
        }
    }
}

// MARK: Drawing

private fun screenY(engine: SlimeStackEngine, worldY: Double): Double = worldY + engine.scroll

private fun visible(engine: SlimeStackEngine, level: Int): Boolean {
    val y = screenY(engine, SlimeStackEngine.y(level))
    return y > -SlimeStackEngine.blockHeight - 4 && y < Board.height + 40
}

private fun DrawScope.drawStackBlock(engine: SlimeStackEngine, block: SlimeStackEngine.Block, space: BoardSpace) {
    val y = screenY(engine, SlimeStackEngine.y(block.level))
    val h = SlimeStackEngine.blockHeight
    if (block.level == 0) {
        // The base: a grassy plinth.
        block(space.rect(block.left, y, block.width, h), hex(0x8A5A3C), edge = hex(0x6B4429), edgeHeight = 0.3)
        block(space.rect(block.left, y, block.width, 2.5), hex(0x6FBF5A), edge = hex(0x3F8F3A), edgeHeight = 0.4)
        return
    }
    val colors = if (block.gold) goldJelly else jelly[block.color % jelly.size]
    block(space.rect(block.left, y, block.width, h), hex(colors.first), edge = hex(colors.second), edgeHeight = 0.25)
    // A light stripe on top and a pixel of shine make it read as jelly.
    fill(space.rect(block.left + 1, y + 1, max(0.0, block.width - 2), 1.2), Color.White.copy(alpha = 0.45f))
    if (block.width > 6) fill(space.rect(block.left + 2, y + 3, 1.4, 1.4), Color.White.copy(alpha = 0.7f))
    if (block.gold) {
        val a = (0.5 + 0.5 * sin(engine.time * 6 + block.id.toDouble())).toFloat()
        fill(space.rect(block.x - 0.7, y + 2.5, 1.4, 4.0), Color.White.copy(alpha = a.coerceIn(0f, 1f)))
        fill(space.rect(block.x - 2, y + 3.8, 4.0, 1.4), Color.White.copy(alpha = a.coerceIn(0f, 1f)))
    }
}

private fun DrawScope.drawPiece(engine: SlimeStackEngine, piece: SlimeStackEngine.Piece, space: BoardSpace) {
    val colors = if (piece.gold) goldJelly else jelly[piece.color % jelly.size]
    val h = SlimeStackEngine.blockHeight
    val cx = space.point(piece.x, 0.0).x.toFloat()
    val cy = space.point(0.0, screenY(engine, piece.y) + h / 2).y.toFloat()
    val width = (piece.width * space.scale).toFloat()
    val height = (h * space.scale).toFloat()
    withTransform({
        translate(cx, cy)
        rotate((piece.angle * 180 / PI).toFloat(), pivot = Offset.Zero)
    }) {
        block(-width / 2, -height / 2, width, height, hex(colors.first), edge = hex(colors.second), edgeHeight = 0.25)
    }
}

private fun DrawScope.drawStackGround(engine: SlimeStackEngine, space: BoardSpace) {
    val top = space.point(0.0, screenY(engine, SlimeStackEngine.ground)).y.toFloat()
    if (top >= size.height) return
    block(0f, top, size.width, size.height - top, hex(0x8A5A3C))
    block(0f, top, size.width, space.scale.toFloat() * 3, hex(0x6FBF5A), edge = hex(0x3F8F3A))
}

/** Height marks every ten blocks along the left edge. */
private fun DrawScope.drawMarks(engine: SlimeStackEngine, space: BoardSpace, text: GameText) {
    var level = 10
    while (level <= engine.height + 12) {
        val y = screenY(engine, SlimeStackEngine.y(level - 1))
        if (y > -4 && y < Board.height) {
            fill(space.rect(0.0, y, 5.0, 0.8), Color.White.copy(alpha = 0.6f))
            drawGameText(text, "$level", 11f, Color.White.copy(alpha = 0.75f), space.offset(4.0, y - 3.5), leading = true)
        }
        level += 10
    }
}

private fun DrawScope.drawStars(engine: SlimeStackEngine, space: BoardSpace, alpha: Double) {
    if (alpha <= 0) return
    for ((i, p) in stackStars.withIndex()) {
        // Stars drift down slowly with the tower so the climb is felt.
        val y = (p.second * 180 + engine.scroll * 0.2) % 180 - 10
        val twinkle = 0.6 + 0.4 * sin(engine.time * 2 + i.toDouble())
        fill(space.rect(p.first * 100, y, 1.2, 1.2), Color.White.copy(alpha = (alpha * twinkle).toFloat().coerceIn(0f, 1f)))
    }
}

/** The slime riding the sliding block (or standing on the tower once it is over). */
private fun DrawScope.drawRider(
    engine: SlimeStackEngine,
    slime: Slime,
    mood: Pair<SpriteAnim, Double>,
    juice: Juice,
    space: BoardSpace,
) {
    val block = if (engine.isOver) engine.top else engine.current
    val y = screenY(engine, SlimeStackEngine.y(block.level))
    val anim = if (engine.time < mood.second) mood.first else SpriteAnim.idle
    val (sx, sy) = juice.squashScale
    drawGameSlime(
        slime, anim,
        height = (12 * space.scale).toFloat(),
        center = space.offset(block.x, y - 6),
        time = engine.time,
        mirrored = !engine.isOver && engine.direction < 0,
        squashX = sx.toFloat(),
        squashY = sy.toFloat(),
    )
}
