package de.hyprnode.slimy.arcade

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import de.hyprnode.slimy.art.PixelItem
import de.hyprnode.slimy.art.rememberReduceMotion
import de.hyprnode.slimy.core.Feedback
import de.hyprnode.slimy.model.Brand
import de.hyprnode.slimy.model.SlimeSprites
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.model.arcade.ArcadeEvent
import de.hyprnode.slimy.model.arcade.Board
import de.hyprnode.slimy.model.arcade.BoardSpace
import de.hyprnode.slimy.model.arcade.JellyDashEngine
import de.hyprnode.slimy.model.arcade.Juice
import de.hyprnode.slimy.model.arcade.mainColor
import de.hyprnode.slimy.model.arcade.mixColor
import de.hyprnode.slimy.model.arcade.sound
import de.hyprnode.slimy.ui.kit.Theme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.sin

private val dashRock = listOf(
    "...ggg...",
    "..gwggg..",
    ".gwgggdg.",
    ".ggggggd.",
    "ggggggdgd",
    "gggggdgdd",
    "ggggdgddd",
    "gggdgdddd",
    "ddddddddd",
)

/** Half of a boulder; two of them stack up. */
private val dashBigRock = listOf(
    "...ggggg...",
    "..gwgggggg.",
    ".gwgggggdgg",
    ".ggggggggdg",
    "gggggggdggd",
    "ggggggdgggd",
    "gggggggdgdd",
    "ggggggdgddd",
    "gggggdgdddd",
    "ggggdgddddd",
    "gggdgdddddd",
    "ddddddddddd",
)
private val dashPuddle = listOf(
    "..bbbbbbbbbb..",
    ".bbwwbbbbbbbb.",
    "bbbbbbbbbbbddd",
    ".bbbbbbbbbddd.",
)
private val stone = mapOf('g' to 0x8D9D91L, 'd' to 0x5A6660L, 'w' to 0xC9D2C2L)
private val water = mapOf('b' to 0x7FD6F0L, 'd' to 0x4A9AC2L, 'w' to 0xFFFFFFL)

/** Fixed scatter for the scenery layers. */
private object DashScenery {
    val clouds = ArcadeRules.scatter(501uL, 5)
    val hills = ArcadeRules.values(502uL, 6)
    val bushes = ArcadeRules.values(503uL, 9)
}

/** Jelly Dash: the slime runs on its own; tap to jump, hold to jump higher. */
@Composable
fun JellyDashView(session: SessionState) {
    val engine = remember { JellyDashEngine(session.seed) }
    val reduceMotion = rememberReduceMotion()
    val juice = remember { Juice().also { it.reduceMotion = reduceMotion } }
    val ticker = rememberTicker()
    var touching by remember { mutableStateOf(false) }
    var mood by remember { mutableStateOf(Pair(SpriteAnim.play, 0.0)) }
    var endIn by remember { mutableStateOf<Double?>(null) }
    var elapsed by remember { mutableDoubleStateOf(0.0) }
    val slime = currentSlime()
    val text = rememberGameText()
    val scope = rememberCoroutineScope()
    val gold = Theme.gold.argbLong()
    val danger = Theme.danger.argbLong()
    val love = Theme.love

    fun report() {
        session.score = engine.score
        session.status = "${engine.meters} m"
    }

    fun react(events: List<ArcadeEvent>) {
        val x = JellyDashEngine.playerX
        val y = engine.y
        for (event in events) {
            when (event) {
                ArcadeEvent.jump -> {
                    juice.burst(x, JellyDashEngine.ground, de.hyprnode.slimy.model.argb(0xC9A36B), count = 5, power = 26.0)
                    Feedback.shared.play(Sound.hop)
                }
                ArcadeEvent.land -> juice.bounce()
                ArcadeEvent.coin -> {
                    juice.burst(x + 4, y - 8, gold, count = 7, power = 45.0)
                    juice.popup("+${JellyDashEngine.coinValue}", x + 6, y - 16, 0xFFFFFFFF)
                    Feedback.shared.play(Sound.coin)
                }
                ArcadeEvent.good -> {
                    juice.burst(x + 4, y - 8, SlimeSprites.mainColor("berry"), count = 10, power = 55.0)
                    juice.popup("+${JellyDashEngine.berryValue}", x + 6, y - 18, 0xFFFFFFFF, big = true)
                    mood = Pair(SpriteAnim.eat, engine.time + 0.35)
                    Feedback.shared.play(Sound.pop)
                }
                ArcadeEvent.great -> {
                    juice.popup("Streak +${JellyDashEngine.streakBonus}", 50.0, 52.0, gold, big = true)
                    Feedback.shared.play(Sound.perfect)
                }
                ArcadeEvent.bad -> {
                    juice.hit()
                    juice.burst(x, y - 4, danger, count = 12, power = 70.0)
                    mood = Pair(SpriteAnim.confused, engine.time + 0.7)
                    Feedback.shared.play(Sound.nope)
                }
                ArcadeEvent.lose -> {
                    mood = Pair(SpriteAnim.sad, engine.time + 10)
                    Feedback.shared.play(Sound.whoosh)
                }
                ArcadeEvent.miss, is ArcadeEvent.tone, ArcadeEvent.win -> Feedback.shared.play(event.sound)
            }
        }
    }

    fun jumpForTalkBack(high: Boolean) {
        if (!session.running) return
        engine.press()
        react(engine.drainEvents())
        scope.launch {
            delay(if (high) 260 else 40)
            engine.release()
        }
    }

    LaunchedEffect(reduceMotion) { juice.reduceMotion = reduceMotion }
    LaunchedEffect(Unit) { report() }
    LaunchedEffect(session.running) {
        // A pause in the middle of a held jump lets go of it.
        if (!session.running) {
            touching = false
            engine.release()
        }
    }
    GameLoop(session.running) { dt ->
        elapsed += dt
        engine.step(dt)
        react(engine.drainEvents())
        juice.step(dt)
        report()
        if (engine.isOver) {
            val left = endIn
            if (left != null) {
                endIn = left - dt
                if (left - dt <= 0) session.end()
            } else {
                endIn = 0.8
            }
        }
        ticker.tick()
    }

    @Suppress("UNUSED_VARIABLE") val frame = ticker.value
    val value = "${engine.meters} meters, ${engine.lives} hearts, ${engine.score} points"
    Box(
        Modifier
            .fillMaxSize()
            .dragGesture(onEnded = {
                touching = false
                engine.release()
            }) {
                if (!touching && session.running) {
                    touching = true
                    engine.press()
                    react(engine.drainEvents())
                }
            }
            .clearAndSetSemantics {
                contentDescription = "Jelly Dash. The slime runs by itself. Jump over puddles and rocks, collect coins and berries."
                stateDescription = value
                onClick(label = "Jump") { jumpForTalkBack(high = false); true }
                customActions = listOf(
                    CustomAccessibilityAction("Jump") { jumpForTalkBack(high = false); true },
                    CustomAccessibilityAction("High jump") { jumpForTalkBack(high = true); true },
                )
            },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().shake(juice, engine.time)) {
            ticker.value
            val space = BoardSpace(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
            Canvas(Modifier.fillMaxSize()) {
                ticker.value
                // Day turns to dusk as the run goes on.
                val dusk = min(1.0, engine.distance / 6000)
                drawBackdrop(mixColor(0x8CC8E8, 0x5B4A8A, dusk), mixColor(0xDDF1F5, 0xF29A76, dusk))
                drawScenery(engine, space, dusk)
                drawDashGround(engine, space)
                drawObstacles(engine, space)
                drawPickups(engine, space, if (reduceMotion) null else elapsed)
                drawJuice(juice, space, text)
                // The runner. `elapsed` keeps running after the last heart, so the sad slime moves.
                val anim = if (engine.time < mood.second) mood.first else if (engine.isOnGround) SpriteAnim.play else SpriteAnim.celebrate
                val blink = engine.invulnerable > 0 && (engine.time * 12).toLong() % 2 == 0L
                val (sx, sy) = juice.squashScale
                drawGameSlime(
                    slime, anim,
                    height = (12 * space.scale).toFloat(),
                    center = space.offset(JellyDashEngine.playerX, engine.y - 6),
                    time = elapsed * 1.6,
                    alpha = if (blink) 0.35f else 1f,
                    squashX = sx.toFloat(),
                    squashY = sy.toFloat(),
                )
            }
            DashHud(engine.lives, engine.streak, love)
        }
        Canvas(Modifier.fillMaxSize()) {
            ticker.value
            drawFlash(juice)
        }
    }
}

@Composable
private fun DashHud(lives: Int, streak: Int, love: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Row(
            Modifier
                .background(Color.Black.copy(alpha = 0.25f))
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            for (i in 0 until JellyDashEngine.lives) {
                PixelItem("heart", Modifier.size(width = 15.dp, height = 12.dp), alpha = if (i < lives) 1f else 0.25f)
            }
        }
        Spacer(Modifier.weight(1f))
        if (streak >= 3) {
            Text(
                "$streak in a row",
                style = pixelStyle(14f),
                color = Color.White,
                modifier = Modifier
                    .background(love)
                    .padding(horizontal = 7.dp, vertical = 4.dp),
            )
        }
    }
}

// MARK: Drawing

private fun screenX(engine: JellyDashEngine, trackX: Double): Double = trackX - engine.distance

private fun DrawScope.drawDashGround(engine: JellyDashEngine, space: BoardSpace) {
    val top = space.point(0.0, JellyDashEngine.ground).y.toFloat()
    block(0f, top, size.width, size.height - top, hex(0x8A5A3C))
    block(0f, top, size.width, space.scale.toFloat() * 3, hex(0x6FBF5A), edge = hex(0x3F8F3A))
    // Marks in the soil scroll at running speed.
    val shift = engine.distance % 18
    val left = space.boardX(0.0) - 18
    var x = left - shift
    val end = space.boardX(size.width.toDouble()) + 18
    while (x < end) {
        fill(space.rect(x, JellyDashEngine.ground + 7, 4.0, 1.4), Color.Black.copy(alpha = 0.15f))
        fill(space.rect(x + 9, JellyDashEngine.ground + 13, 3.0, 1.4), Color.Black.copy(alpha = 0.12f))
        x += 18
    }
}

/** Clouds, far hills and bushes, each layer slower than the track. */
private fun DrawScope.drawScenery(engine: JellyDashEngine, space: BoardSpace, dusk: Double) {
    val left = space.boardX(0.0) - 30
    val right = space.boardX(size.width.toDouble()) + 30
    val span = right - left
    val cloud = Color.White.copy(alpha = (0.65 - dusk * 0.25).toFloat())
    for ((i, c) in DashScenery.clouds.withIndex()) {
        val x = left + ArcadeRules.positiveRemainder(c.first * span - engine.distance * 0.08 - i.toDouble() * 7, span)
        val y = 18 + c.second * 50
        fill(space.rect(x, y, 20.0, 4.5), cloud)
        fill(space.rect(x + 4, y - 3, 10.0, 3.0), cloud)
    }
    val hill = hex(mixColor(0x9CCB8A, 0x6E5A8C, dusk))
    for ((i, h) in DashScenery.hills.withIndex()) {
        val x = left + ArcadeRules.positiveRemainder(i.toDouble() * 34 - engine.distance * 0.2, span)
        val height = 10 + h * 18
        // Stepped pixel hill.
        fill(space.rect(x, JellyDashEngine.ground - height, 26.0, height), hill)
        fill(space.rect(x + 5, JellyDashEngine.ground - height - 4, 16.0, 4.0), hill)
        fill(space.rect(x + 9, JellyDashEngine.ground - height - 7, 8.0, 3.0), hill)
    }
    val bush = hex(mixColor(0x4FA34A, 0x3E5A4A, dusk))
    for ((i, b) in DashScenery.bushes.withIndex()) {
        val x = left + ArcadeRules.positiveRemainder(i.toDouble() * 23 + b * 9 - engine.distance * 0.5, span)
        fill(space.rect(x, JellyDashEngine.ground - 5, 10.0, 5.0), bush)
        fill(space.rect(x + 2, JellyDashEngine.ground - 8, 6.0, 3.0), bush)
    }
}

private fun DrawScope.drawObstacles(engine: JellyDashEngine, space: BoardSpace) {
    val g = JellyDashEngine.ground
    val shadow = Color.Black.copy(alpha = 0.15f)
    for (o in engine.obstacles) {
        val x = screenX(engine, o.x)
        if (x <= -20 || x >= Board.width + 30) continue
        when (o.kind) {
            JellyDashEngine.ObstacleKind.puddle ->
                drawPixelRows(dashPuddle, water, space.rect(x - 7, g - 2.5, 14.0, 4.0))
            JellyDashEngine.ObstacleKind.rock -> {
                fillEllipse(space.rect(x - 6, g - 1, 12.0, 2.4), shadow)
                drawPixelRows(dashRock, stone, space.rect(x - 4.5, g - 9, 9.0, 9.0))
            }
            JellyDashEngine.ObstacleKind.boulder -> {
                fillEllipse(space.rect(x - 7, g - 1, 14.0, 2.4), shadow)
                drawPixelRows(dashBigRock, stone, space.rect(x - 5.5, g - 12, 11.0, 12.0))
                drawPixelRows(dashBigRock, stone, space.rect(x - 5, g - 24, 10.0, 12.0))
            }
        }
    }
}

/** [elapsed] is null with reduced motion: the pickups hold still. */
private fun DrawScope.drawPickups(engine: JellyDashEngine, space: BoardSpace, elapsed: Double?) {
    for (p in engine.pickups) {
        val x = screenX(engine, p.x)
        if (x <= -10 || x >= Board.width + 20) continue
        val bob = if (elapsed == null) 0.0 else sin(elapsed * 5 + p.x * 0.3) * 1.2
        when (p.kind) {
            JellyDashEngine.PickupKind.coin -> drawSprite("coin", space.centered(x, p.y + bob, 7.0, 7.0))
            JellyDashEngine.PickupKind.berry -> drawSprite("berry", space.centered(x, p.y + bob, 9.0, 9.0))
        }
    }
}
