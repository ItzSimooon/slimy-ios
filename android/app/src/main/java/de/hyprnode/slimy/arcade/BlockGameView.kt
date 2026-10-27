package de.hyprnode.slimy.arcade

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import de.hyprnode.slimy.art.PixelItem
import de.hyprnode.slimy.art.rememberReduceMotion
import de.hyprnode.slimy.core.Feedback
import de.hyprnode.slimy.model.Brand
import de.hyprnode.slimy.model.EntityRole
import de.hyprnode.slimy.model.GameControl
import de.hyprnode.slimy.model.GameSpec
import de.hyprnode.slimy.model.GameTheme
import de.hyprnode.slimy.model.PowerKind
import de.hyprnode.slimy.model.Slime
import de.hyprnode.slimy.model.SlimeSprites
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.model.arcade.BlockEngine
import de.hyprnode.slimy.model.arcade.BoardPoint
import de.hyprnode.slimy.model.arcade.BoardSpace
import de.hyprnode.slimy.model.arcade.Juice
import de.hyprnode.slimy.model.arcade.clock
import de.hyprnode.slimy.model.arcade.mainColor
import de.hyprnode.slimy.ui.kit.Meter
import de.hyprnode.slimy.ui.kit.Theme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Fixed scatter of points (0…1) for background decoration. */
private val decorPoints = ArcadeRules.scatter(42uL, 30)

/** Draws and runs the day's building-block game. */
@Composable
fun BlockGameView(session: SessionState, spec: GameSpec) {
    val engine = remember { BlockEngine(spec, session.seed) }
    val reduceMotion = rememberReduceMotion()
    val juice = remember { Juice().also { it.reduceMotion = reduceMotion } }
    val ticker = rememberTicker()
    var touching by remember { mutableStateOf(false) }
    var facingLeft by remember { mutableStateOf(false) }
    var mood by remember { mutableStateOf(Pair(SpriteAnim.play, 0.0)) }
    val slime = currentSlime()
    val text = rememberGameText()
    val gold = Theme.gold
    val goldLong = gold.argbLong()
    val dangerLong = Theme.danger.argbLong()
    val love = Theme.love
    val swipe = with(LocalDensity.current) { 20.dp.toPx() }

    fun report() {
        session.score = engine.score
        session.status = clock(engine.timeLeft ?: engine.time)
    }

    fun react(event: BlockEngine.Event) {
        when (event) {
            is BlockEngine.Event.collect -> {
                juice.burst(event.x, event.y, SlimeSprites.mainColor(event.sprite), count = 8)
                juice.popup("+${event.points}", event.x, event.y - 6, 0xFFFFFFFF)
                juice.bounce()
                mood = Pair(SpriteAnim.eat, engine.time + 0.35)
                Feedback.shared.play(if (engine.combo >= 5) Sound.perfect else Sound.pop)
            }
            is BlockEngine.Event.hit -> {
                juice.hit()
                juice.burst(event.x, event.y, dangerLong, count = 14, power = 80.0)
                mood = Pair(SpriteAnim.confused, engine.time + 0.8)
                Feedback.shared.play(Sound.nope)
            }
            is BlockEngine.Event.blocked -> {
                juice.burst(event.x, event.y, Brand.cool, count = 12)
                juice.popup("blocked", event.x, event.y - 8, Brand.cool)
                Feedback.shared.play(Sound.land)
            }
            is BlockEngine.Event.power -> {
                juice.burst(event.x, event.y, goldLong, count = 18, power = 90.0)
                juice.popup(event.kind.title, 50.0, 60.0, goldLong, big = true)
                mood = Pair(SpriteAnim.celebrate, engine.time + 0.8)
                Feedback.shared.play(Sound.success)
            }
            is BlockEngine.Event.combo -> {
                juice.popup("${event.count} combo!", 50.0, 48.0, 0xFFFFFFFF, big = true)
                Feedback.shared.play(Sound.levelUp)
            }
            BlockEngine.Event.miss -> Feedback.shared.play(Sound.miss)
            BlockEngine.Event.jump -> Feedback.shared.play(Sound.hop)
        }
    }

    LaunchedEffect(reduceMotion) { juice.reduceMotion = reduceMotion }
    LaunchedEffect(Unit) { report() }
    GameLoop(session.running) { dt ->
        engine.step(dt)
        for (event in engine.drainEvents()) react(event)
        juice.step(dt)
        report()
        if (engine.isOver) session.end()
        ticker.tick()
    }

    fun step(direction: Int) {
        when (spec.control) {
            GameControl.lanes -> engine.moveLane(direction)
            GameControl.drag, GameControl.steer ->
                engine.target = BoardPoint(max(8.0, min(92.0, engine.x + direction * 15.0)), engine.y)
            GameControl.jump, GameControl.flap -> engine.tap()
        }
        for (event in engine.drainEvents()) react(event)
    }

    @Suppress("UNUSED_VARIABLE") val frame = ticker.value
    val label = "${spec.title}. ${spec.tutorial.joinToString(" ")}"
    val value = "${engine.score} points" + if (spec.lives > 0) ", ${engine.lives} lives" else ""
    val jumps = spec.control == GameControl.jump || spec.control == GameControl.flap
    Box(
        Modifier
            .fillMaxSize()
            .dragGesture(onEnded = { v ->
                touching = false
                if (spec.control == GameControl.lanes && session.running) {
                    if (abs(v.translation.x) > swipe) {
                        engine.moveLane(if (v.translation.x > 0) 1 else -1)
                    } else {
                        engine.moveLane(if (v.location.x < v.size.width / 2f) -1 else 1)
                    }
                }
            }) { v ->
                if (!session.running) return@dragGesture
                val space = v.space
                val bx = space.boardX(v.location.x.toDouble())
                val by = space.boardY(v.location.y.toDouble())
                when (spec.control) {
                    GameControl.drag, GameControl.steer -> {
                        facingLeft = bx < engine.x
                        engine.target = BoardPoint(bx, by)
                    }
                    GameControl.jump, GameControl.flap -> if (!touching) engine.tap()
                    GameControl.lanes -> Unit
                }
                touching = true
            }
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = value
                customActions = if (jumps) {
                    listOf(CustomAccessibilityAction(if (spec.control == GameControl.jump) "Jump" else "Flap") { step(1); true })
                } else {
                    listOf(
                        CustomAccessibilityAction("Move left") { step(-1); true },
                        CustomAccessibilityAction("Move right") { step(1); true },
                    )
                }
            },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().shake(juice, engine.time)) {
            ticker.value
            val space = BoardSpace(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
            val dp = LocalDensity.current.density
            Canvas(Modifier.fillMaxSize()) {
                ticker.value
                val colors = spec.theme.colors
                drawBackdrop(colors.top, colors.bottom)
                drawDecor(engine, spec, space)
                drawBlockGround(engine, spec, space)
                drawThings(engine, spec, space, gold)
                drawJuice(juice, space, text)
                drawPlayer(engine, spec, slime, mood, juice, space, facingLeft, gold, dp)
            }
            BlockHud(
                lives = if (spec.lives > 0) max(engine.lives, 0) else null,
                powers = engine.powers.entries.sortedBy { it.key.name }.map { it.key to it.value },
                multiplier = if (engine.combo >= 3) ArcadeRules.multiplier(engine.multiplier) else null,
                gold = gold,
                love = love,
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            ticker.value
            drawFlash(juice)
        }
    }
}

// MARK: HUD

@Composable
private fun BlockHud(
    lives: Int?,
    powers: List<Pair<PowerKind, Double>>,
    multiplier: String?,
    gold: Color,
    love: Color,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (lives != null) {
            Row(
                Modifier
                    .background(Color.Black.copy(alpha = 0.25f))
                    .padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                repeat(lives) { PixelItem("heart", Modifier.size(width = 14.dp, height = 11.dp)) }
            }
        }
        Spacer(Modifier.weight(1f))
        for ((p, left) in powers) {
            Column(
                Modifier
                    .background(Color.Black.copy(alpha = 0.3f))
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(p.title, style = pixelStyle(10f), color = Color.White)
                Box(Modifier.width(44.dp)) {
                    Meter(value = left / max(1.0, p.seconds), color = gold, height = 4.dp)
                }
            }
        }
        if (multiplier != null) {
            Text(
                multiplier,
                style = pixelStyle(16f),
                color = Color.White,
                modifier = Modifier
                    .background(love)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

// MARK: Drawing

private fun DrawScope.drawPlayer(
    engine: BlockEngine,
    spec: GameSpec,
    slime: Slime,
    mood: Pair<SpriteAnim, Double>,
    juice: Juice,
    space: BoardSpace,
    facingLeft: Boolean,
    gold: Color,
    dp: Float,
) {
    val shrink = engine.powers[PowerKind.shrink] != null
    val height = (16 * space.scale * (if (shrink) 0.65 else 1.0)).toFloat()
    val jumps = spec.control == GameControl.jump || spec.control == GameControl.flap
    val anim = if (engine.time < mood.second) mood.first else if (jumps && engine.vy < 0) SpriteAnim.celebrate else SpriteAnim.play
    val blink = engine.invulnerable > 0 && (engine.time * 12).toLong() % 2 == 0L
    val alpha = if (blink) 0.35f else 1f
    val (sx, sy) = juice.squashScale
    val center = space.offset(engine.x, engine.y - 8)
    drawGameSlime(slime, anim, height, center, engine.time, mirrored = facingLeft, alpha = alpha,
        squashX = sx.toFloat(), squashY = sy.toFloat())
    if (engine.powers[PowerKind.shield] != null) {
        // `strokeBorder`: the line sits inside the circle.
        val line = 2.5f * dp
        val radius = height * 1.45f / 2 - line / 2
        drawCircle(Color(Brand.cool).copy(alpha = 0.8f * alpha), radius, center, style = Stroke(line))
    }
    if (engine.powers[PowerKind.magnet] != null) {
        val line = 1.5f * dp
        val radius = (42 * space.scale).toFloat() - line / 2
        drawCircle(
            gold.copy(alpha = 0.35f * alpha), radius, center,
            style = Stroke(line, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * dp, 4 * dp))),
        )
    }
}

private fun DrawScope.drawBlockGround(engine: BlockEngine, spec: GameSpec, space: BoardSpace) {
    val ground = hex(spec.theme.colors.ground)
    val top = space.point(0.0, 152.0).y.toFloat()
    when (spec.control) {
        GameControl.steer -> return
        GameControl.flap -> fill(0f, top, size.width, size.height - top, ground)
        GameControl.lanes -> {
            fill(0f, top, size.width, size.height - top, ground)
            for (lx in listOf(35.0, 65.0)) {
                var y = (engine.time * 60) % 16 - 16
                while (y < 160) {
                    fill(space.rect(lx - 0.6, y, 1.2, 7.0), Color.White.copy(alpha = 0.25f))
                    y += 16
                }
            }
        }
        GameControl.drag, GameControl.jump -> {
            fill(0f, top, size.width, size.height - top, ground)
            fill(0f, top, size.width, (space.scale * 2).toFloat(), Color.White.copy(alpha = 0.25f))
            if (spec.control == GameControl.jump) {
                // Ground marks scroll by, so running feels like running.
                var x = 100 - (engine.time * 48 * engine.intensity) % 20
                while (x > -20) {
                    fill(space.rect(x, 155.0, 6.0, 1.5), Color.Black.copy(alpha = 0.15f))
                    x -= 20
                }
            }
        }
    }
}

private fun DrawScope.drawThings(engine: BlockEngine, spec: GameSpec, space: BoardSpace, gold: Color) {
    for (t in engine.things) {
        val e = spec.entities[t.kind]
        val size = 10 * e.size
        if (e.role == EntityRole.power) {
            // Power-ups glow and pulse so they read at a glance.
            val pulse = 1 + sin(engine.time * 6) * 0.12
            val r = space.centered(t.x, t.y, size * 1.7 * pulse, size * 1.7 * pulse)
            fillEllipse(r, gold.copy(alpha = 0.35f))
            strokeEllipse(r, gold, 1.5f * density)
        } else if (e.role == EntityRole.avoid) {
            fillEllipse(space.centered(t.x, t.y + size * 0.55, size * 0.9, size * 0.25), Color.Black.copy(alpha = 0.15f))
        }
        drawSprite(e.sprite, space.centered(t.x, t.y, size, size))
    }
}

private fun DrawScope.drawDecor(engine: BlockEngine, spec: GameSpec, space: BoardSpace) {
    val t = engine.time
    when (spec.theme.decor) {
        GameTheme.Decor.clouds -> {
            for ((i, c) in decorPoints.take(5).withIndex()) {
                val x = (c.first * 140 + t * (3 + i.toDouble())) % 140 - 20
                val y = 10 + c.second * 50
                fill(space.rect(x, y, 22.0, 5.0), Color.White.copy(alpha = 0.6f))
                fill(space.rect(x + 5, y - 3, 11.0, 3.0), Color.White.copy(alpha = 0.6f))
            }
        }
        GameTheme.Decor.stars -> {
            for ((i, c) in decorPoints.withIndex()) {
                val a = 0.35 + 0.35 * sin(t * 2 + i.toDouble())
                fill(space.rect(c.first * 100, c.second * 140, 1.2, 1.2), Color.White.copy(alpha = a.toFloat().coerceIn(0f, 1f)))
            }
        }
        GameTheme.Decor.bubbles -> {
            for ((i, c) in decorPoints.take(14).withIndex()) {
                val y = 160 - (c.second * 160 + t * (8 + (i % 4).toDouble() * 3)) % 170
                val x = c.first * 100 + sin(t + i.toDouble()) * 3
                strokeEllipse(space.centered(x, y, 3.0, 3.0), Color.White.copy(alpha = 0.4f), 1f * density)
            }
        }
        GameTheme.Decor.leaves, GameTheme.Decor.snowflakes -> {
            val color = if (spec.theme.decor == GameTheme.Decor.leaves) hex(0xE8A92A) else Color.White
            for ((i, c) in decorPoints.take(16).withIndex()) {
                val y = (c.second * 160 + t * (10 + (i % 3).toDouble() * 4)) % 170 - 10
                val x = c.first * 100 + sin(t * 1.5 + i.toDouble()) * 5
                fill(space.rect(x, y, 1.8, 1.8), color.copy(alpha = 0.7f))
            }
        }
        GameTheme.Decor.sparkles -> {
            for ((i, c) in decorPoints.take(14).withIndex()) {
                val a = max(0.0, sin(t * 2.5 + i.toDouble() * 1.3)).toFloat()
                val x = c.first * 100
                val y = c.second * 140
                fill(space.rect(x - 1.5, y, 3.0, 0.8), Color.White.copy(alpha = (a * 0.7f).coerceIn(0f, 1f)))
                fill(space.rect(x - 0.4, y - 1.1, 0.8, 3.0), Color.White.copy(alpha = (a * 0.7f).coerceIn(0f, 1f)))
            }
        }
        GameTheme.Decor.embers -> {
            for ((i, c) in decorPoints.take(16).withIndex()) {
                val y = 160 - (c.second * 160 + t * (14 + (i % 4).toDouble() * 5)) % 170
                val x = c.first * 100 + sin(t * 2 + i.toDouble()) * 4
                fill(space.rect(x, y, 1.4, 1.4), hex(0xFF9A3C, 0.75f))
            }
        }
    }
}
