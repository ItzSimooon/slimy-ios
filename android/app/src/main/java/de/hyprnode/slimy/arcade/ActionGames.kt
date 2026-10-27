package de.hyprnode.slimy.arcade

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.model.arcade.ArcadeEvent
import de.hyprnode.slimy.model.arcade.BasketballEngine
import de.hyprnode.slimy.model.arcade.Board
import de.hyprnode.slimy.model.arcade.BoardSpace
import de.hyprnode.slimy.model.arcade.RhythmBounceEngine
import de.hyprnode.slimy.model.arcade.SlimeJumpEngine
import de.hyprnode.slimy.model.arcade.SlimeRacingEngine
import de.hyprnode.slimy.model.arcade.SnackCatcherEngine
import de.hyprnode.slimy.model.arcade.clock
import de.hyprnode.slimy.model.arcade.mixColor
import de.hyprnode.slimy.ui.kit.Theme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// The real-time games. Each view owns its engine, steps it from the game
// loop while the round runs and draws it into a Canvas. TalkBack users get
// custom actions for movement; the games still need quick reactions, which
// the arcade says in each card's hint.

internal val grass = hex(0x6FBF5A)
internal val grassEdge = hex(0x3F8F3A)
internal val soil = hex(0x8A5A3C)

// MARK: - Snack Catcher

@Composable
fun SnackCatcherView(session: SessionState) {
    val engine = remember { SnackCatcherEngine(session.seed) }
    val ticker = rememberTicker()
    var biteAt by remember { mutableDoubleStateOf(-10.0) }
    val slime = currentSlime()
    val ink = Theme.ink
    val card = Theme.card

    LaunchedEffect(Unit) { session.status = clock(engine.timeLeft) }
    GameLoop(session.running) { dt ->
        engine.step(dt)
        val events = engine.drainEvents()
        if (ArcadeEvent.good in events || ArcadeEvent.great in events) biteAt = engine.time
        events.play()
        session.score = engine.score
        session.status = clock(engine.timeLeft)
        if (engine.isOver) session.end()
        ticker.tick()
    }

    @Suppress("UNUSED_VARIABLE") val frame = ticker.value
    val points = "${engine.score} points"
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .dragGesture { v -> engine.targetX = v.space.boardX(v.location.x.toDouble()) }
            .clearAndSetSemantics {
                contentDescription = "Playing field. The slime catches falling food."
                stateDescription = points
                customActions = listOf(
                    CustomAccessibilityAction("Move left") {
                        engine.targetX = max(8.0, min(92.0, engine.targetX - 15)); true
                    },
                    CustomAccessibilityAction("Move right") {
                        engine.targetX = max(8.0, min(92.0, engine.targetX + 15)); true
                    },
                )
            },
    ) {
        ticker.value
        val space = BoardSpace(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        Canvas(Modifier.fillMaxSize()) {
            ticker.value
            drawBackdrop()
            val groundTop = space.point(0.0, 150.0).y.toFloat()
            block(0f, groundTop, size.width, size.height - groundTop, soil)
            block(0f, groundTop, size.width, space.scale.toFloat() * 3, grass, edge = grassEdge)
            for (drop in engine.drops) drawSprite(drop.sprite, space.centered(drop.x, drop.y, 10.0, 10.0))
            drawGameSlime(
                slime,
                if (engine.time - biteAt < 0.4) SpriteAnim.eat else SpriteAnim.idle,
                height = (16 * space.scale).toFloat(),
                center = space.offset(engine.playerX, 142.0),
                time = engine.time,
                mirrored = engine.targetX < engine.playerX - 2,
            )
        }
        if (engine.combo >= 3) {
            val at = space.offset(50.0, 12.0)
            Text(
                "${engine.combo}x combo",
                style = Theme.label(14.sp),
                color = ink,
                modifier = Modifier
                    .fillMaxSize()
                    .centeredAt(at.x, at.y)
                    .background(card.copy(alpha = 0.85f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

// MARK: - Slime Jump

@Composable
fun SlimeJumpView(session: SessionState) {
    val engine = remember { SlimeJumpEngine(session.seed) }
    val ticker = rememberTicker()
    var time by remember { mutableDoubleStateOf(0.0) }
    val slime = currentSlime()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(Unit) { session.status = "" }
    GameLoop(session.running) { dt ->
        time += dt
        engine.step(dt)
        engine.drainEvents().play()
        session.score = engine.score
        if (engine.isOver) session.end()
        ticker.tick()
    }

    fun nudge(direction: Double) {
        engine.steer = direction
        scope.launch {
            delay(300)
            engine.steer = 0.0
        }
    }

    @Suppress("UNUSED_VARIABLE") val frame = ticker.value
    val points = "${engine.score} points"
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .dragGesture(onEnded = { engine.steer = 0.0 }) { v ->
                engine.steer = if (v.location.x < v.size.width / 2f) -1.0 else 1.0
            }
            .clearAndSetSemantics {
                contentDescription = "Playing field. The slime bounces by itself, you steer."
                stateDescription = points
                customActions = listOf(
                    CustomAccessibilityAction("Steer left") { nudge(-1.0); true },
                    CustomAccessibilityAction("Steer right") { nudge(1.0); true },
                )
            },
    ) {
        ticker.value
        val space = BoardSpace(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        Canvas(Modifier.fillMaxSize()) {
            ticker.value
            val cam = engine.cameraY
            // The sky darkens as you climb.
            val height = min(1.0, engine.score.toDouble() / 80)
            drawBackdrop(mixColor(0x8CC8E8, 0x2E2A5A, height), mixColor(0xDDF1F5, 0x6A5AA0, height))
            for (p in engine.platforms) {
                val y = p.y - cam
                if (y <= -10 || y >= Board.height + 10) continue
                val rect = space.rect(p.x - p.width / 2, y, p.width, 4.0)
                val still = p.drift == 0.0
                block(rect, if (still) grass else hex(0xE8A92A), edge = if (still) grassEdge else hex(0xA86F1A), edgeHeight = 0.4)
                if (p.coin) drawSprite("coin", space.centered(p.x, y - 6, 7.0, 7.0))
            }
            drawGameSlime(
                slime,
                if (engine.vy < 0) SpriteAnim.play else SpriteAnim.idle,
                height = (12 * space.scale).toFloat(),
                center = space.offset(engine.x, engine.y - cam - 6),
                time = time,
                mirrored = engine.steer < 0,
            )
        }
    }
}

// MARK: - Slime Racing

@Composable
fun SlimeRacingView(session: SessionState) {
    val engine = remember { SlimeRacingEngine(session.seed) }
    val ticker = rememberTicker()
    val slime = currentSlime()
    val reduceMotion = de.hyprnode.slimy.art.rememberReduceMotion()
    val accent = Theme.accent
    val playerY = 128.0
    val zoom = 0.9
    fun laneX(lane: Int): Double = 20 + lane.toDouble() * 30
    // The slime slides to its lane (`.animation(.easeOut(duration: 0.1))`).
    var shownX by remember { mutableDoubleStateOf(laneX(1)) }
    val swipe = with(LocalDensity.current) { 20.dp.toPx() }

    GameLoop(session.running) { dt ->
        engine.step(dt)
        engine.drainEvents().play()
        session.score = engine.score
        val gap = (engine.distance - engine.ghostDistance) / SlimeRacingEngine.ghostSpeed
        session.status = ArcadeRules.racingGap(gap)
        if (engine.isOver) session.end()
        val goal = laneX(engine.lane)
        shownX = if (reduceMotion) goal else shownX + (goal - shownX) * min(1.0, dt * 30)
        ticker.tick()
    }

    @Suppress("UNUSED_VARIABLE") val frame = ticker.value
    val laneValue = "Lane ${engine.lane + 1} of 3"
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .dragGesture(onEnded = { v ->
                if (abs(v.translation.x) > swipe) {
                    engine.move(if (v.translation.x > 0) 1 else -1)
                } else {
                    engine.move(if (v.location.x < v.size.width / 2f) -1 else 1)
                }
                if (reduceMotion) shownX = laneX(engine.lane)
            })
            .clearAndSetSemantics {
                contentDescription = "Race track with three lanes"
                stateDescription = laneValue
                customActions = listOf(
                    CustomAccessibilityAction("Move left") { engine.move(-1); true },
                    CustomAccessibilityAction("Move right") { engine.move(1); true },
                )
            },
    ) {
        ticker.value
        val space = BoardSpace(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        Canvas(Modifier.fillMaxSize()) {
            ticker.value
            drawRect(hex(0x7CC46A))
            val road = space.rect(5.0, 0.0, 90.0, Board.height)
            fill(road.x.toFloat(), 0f, road.width.toFloat(), size.height, hex(0xC9A36B))
            // Moving lane marks.
            val shift = (engine.distance * zoom) % 16
            for (lane in 1 until SlimeRacingEngine.lanes) {
                val x = 5 + lane.toDouble() * 30
                var y = -16 + shift
                while (y < Board.height) {
                    fill(space.rect(x - 0.8, y, 1.6, 7.0), hex(0xEAD2A0))
                    y += 16
                }
            }
            // Finish line.
            val finishY = playerY - (SlimeRacingEngine.length - engine.distance) * zoom
            if (finishY > -10) {
                for (i in 0 until 18) {
                    for (row in 0 until 2) {
                        val dark = (i + row) % 2 == 0
                        fill(space.rect(5 + i.toDouble() * 5, finishY + row.toDouble() * 3, 5.0, 3.0), if (dark) hex(0x2B2336) else Color.White)
                    }
                }
            }
            for (o in engine.obstacles) {
                if (o.hit && o.kind != SlimeRacingEngine.Kind.rock) continue
                val y = playerY - (o.distance - engine.distance) * zoom
                if (y <= -12 || y >= Board.height + 12) continue
                drawSprite(
                    if (o.kind == SlimeRacingEngine.Kind.rock) "rock" else "puddle",
                    space.centered(laneX(o.lane), y, 14.0, 14.0),
                    alpha = if (o.hit) 0.5f else 1f,
                )
            }
            // Progress: you and the ghost.
            val bar = space.rect(10.0, 4.0, 80.0, 3.0)
            fill(bar, Color.Black.copy(alpha = 0.25f))
            val d = density
            val ghostX = bar.x + bar.width * engine.ghostDistance / SlimeRacingEngine.length
            val meX = bar.x + bar.width * engine.distance / SlimeRacingEngine.length
            fill((ghostX - 3 * d).toFloat(), (bar.y - 3 * d).toFloat(), 6 * d, (bar.height + 6 * d).toFloat(), Color.White.copy(alpha = 0.8f))
            fill((meX - 3 * d).toFloat(), (bar.y - 3 * d).toFloat(), 6 * d, (bar.height + 6 * d).toFloat(), accent)
            // The ghost, when it is on screen.
            val ghostY = playerY - (engine.ghostDistance - engine.distance) * zoom
            if (ghostY > 0 && ghostY < Board.height) {
                drawGameSlime(
                    slime, SpriteAnim.idle, (12 * space.scale).toFloat(), space.offset(laneX(1), ghostY - 6),
                    engine.time, alpha = 0.35f,
                )
            }
            drawGameSlime(
                slime,
                if (engine.speed < 30) SpriteAnim.confused else SpriteAnim.play,
                (13 * space.scale).toFloat(),
                space.offset(shownX, playerY - 6),
                engine.time,
            )
        }
    }
}

// MARK: - Rhythm Bounce

private val laneColors = listOf(0xE0577FL, 0x4FA3D9L, 0xE8A92AL)

@Composable
fun RhythmBounceView(session: SessionState) {
    val engine = remember { RhythmBounceEngine(session.seed) }
    val ticker = rememberTicker()
    val slime = currentSlime()
    val reduceMotion = de.hyprnode.slimy.art.rememberReduceMotion()
    var touching by remember { mutableStateOf(false) }
    var lastLane by remember { mutableIntStateOf(1) }
    val lineY = 132.0
    fun laneX(lane: Int): Double = 20 + lane.toDouble() * 30
    var shownX by remember { mutableDoubleStateOf(laneX(1)) }

    fun tap(lane: Int) {
        if (!session.running) return
        lastLane = lane
        if (reduceMotion) shownX = laneX(lane)
        engine.tap(lane)
        engine.drainEvents().play()
        session.score = engine.score
    }

    GameLoop(session.running) { dt ->
        engine.step(dt)
        engine.drainEvents().play()
        session.score = engine.score
        session.status = if (engine.combo >= 4) "x${engine.combo}" else ""
        if (engine.isOver) session.end()
        val goal = laneX(lastLane)
        shownX = if (reduceMotion) goal else shownX + (goal - shownX) * min(1.0, dt * 40)
        ticker.tick()
    }

    @Suppress("UNUSED_VARIABLE") val frame = ticker.value
    val points = "${engine.score} points"
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .dragGesture(onEnded = { touching = false }) { v ->
                if (touching) return@dragGesture
                touching = true
                tap(ArcadeRules.rhythmLane(v.space.boardX(v.location.x.toDouble())))
            }
            .clearAndSetSemantics {
                contentDescription = "Three lanes. Tap a lane when a note hits the line."
                stateDescription = points
                customActions = (0 until RhythmBounceEngine.lanes).map { lane ->
                    CustomAccessibilityAction("Tap lane ${lane + 1}") { tap(lane); true }
                }
            },
    ) {
        ticker.value
        val space = BoardSpace(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        Canvas(Modifier.fillMaxSize()) {
            ticker.value
            drawBackdrop(0x2B2336, 0x4A3A6A)
            for (lane in 0 until RhythmBounceEngine.lanes) {
                val color = laneColors[lane]
                fill(space.rect(laneX(lane) - 12, 0.0, 24.0, Board.height), hex(color, 0.10f))
                fill(space.rect(laneX(lane) - 12, lineY - 1.5, 24.0, 3.0), hex(color, 0.9f))
            }
            for (note in engine.notes) {
                if (note.judged != null) continue
                val ahead = note.time - engine.time
                if (ahead >= RhythmBounceEngine.lead || ahead <= -0.3) continue
                val y = lineY - ahead / RhythmBounceEngine.lead * (lineY - 4)
                val rect = space.centered(laneX(note.lane), y, 14.0, 7.0)
                fill(rect, hex(laneColors[note.lane]))
                val dx = rect.width * 0.2
                val dy = rect.height * 0.3
                fill((rect.x + dx).toFloat(), (rect.y + dy).toFloat(), (rect.width - 2 * dx).toFloat(), (rect.height - 2 * dy).toFloat(), Color.White.copy(alpha = 0.6f))
            }
            drawGameSlime(slime, SpriteAnim.play, (14 * space.scale).toFloat(), space.offset(shownX, lineY + 14), engine.time * 2)
        }
        val last = engine.lastJudgement
        if (last != null && engine.time - last.second < 0.5) {
            val judgement = last.first
            val at = space.offset(50.0, lineY - 30)
            Text(
                when (judgement) {
                    RhythmBounceEngine.Judgement.perfect -> "Perfect"
                    RhythmBounceEngine.Judgement.good -> "Good"
                    RhythmBounceEngine.Judgement.miss -> "Miss"
                },
                style = Theme.title(20.sp),
                color = if (judgement == RhythmBounceEngine.Judgement.miss) Color.White.copy(alpha = 0.6f) else Color.White,
                modifier = Modifier.fillMaxSize().centeredAt(at.x, at.y),
            )
        }
    }
}

// MARK: - Slime Basketball

@Composable
fun BasketballView(session: SessionState) {
    val engine = remember { BasketballEngine(session.seed) }
    val ticker = rememberTicker()
    val slime = currentSlime()
    var aim by remember { mutableStateOf<Offset?>(null) }
    val danger = Theme.danger

    LaunchedEffect(Unit) { session.status = clock(engine.timeLeft) }
    GameLoop(session.running) { dt ->
        engine.step(dt)
        engine.drainEvents().play()
        session.score = engine.score
        session.status = clock(engine.timeLeft)
        if (engine.isOver) session.end()
        ticker.tick()
    }

    @Suppress("UNUSED_VARIABLE") val frame = ticker.value
    val points = "${engine.score} points, ${engine.hits} hits"
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .dragGesture(minimumDistance = 4.dp, onEnded = { v ->
                aim = null
                val space = v.space
                engine.throwBall(v.translation.x / space.scale, v.translation.y / space.scale)
                engine.drainEvents().play()
            }) { v -> aim = v.translation }
            .clearAndSetSemantics {
                contentDescription = "Basketball court. Swipe up to throw."
                stateDescription = points
            },
    ) {
        ticker.value
        val space = BoardSpace(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        Canvas(Modifier.fillMaxSize()) {
            ticker.value
            drawBackdrop(0xF2C48D, 0xF7E3C4)
            val floorTop = space.point(0.0, 150.0).y.toFloat()
            block(0f, floorTop, size.width, size.height - floorTop, hex(0xC98A4B), edge = hex(0x9A6533), edgeHeight = 0.15)
            // Backboard, rim and net.
            val hx = engine.hoopX
            block(space.rect(hx - 12, BasketballEngine.hoopY - 20, 24.0, 16.0), Color.White, edge = hex(0xD0C8C0), edgeHeight = 0.12)
            val net = space.rect(hx - 6, BasketballEngine.hoopY - 15, 12.0, 9.0)
            drawRect(
                danger, Offset(net.x.toFloat(), net.y.toFloat()), Size(net.width.toFloat(), net.height.toFloat()),
                style = Stroke(space.scale.toFloat()),
            )
            drawSprite("hoop", space.centered(hx, BasketballEngine.hoopY + 3, 2 * BasketballEngine.rim + 2, 12.0))
            // Where a throw would go while you aim.
            val a = aim
            if (a != null && !engine.flying) {
                var x = BasketballEngine.start.x
                var y = BasketballEngine.start.y
                var vx = a.x / space.scale * 2.5
                var vy = a.y / space.scale * 2.5
                val length = sqrt(vx * vx + vy * vy)
                if (length > 330) {
                    vx *= 330 / length
                    vy *= 330 / length
                }
                for (i in 0 until 14) {
                    repeat(3) {
                        vy += BasketballEngine.gravity * 0.02
                        x += vx * 0.02
                        y += vy * 0.02
                    }
                    fillEllipse(space.centered(x, y, 1.6, 1.6), Color.Black.copy(alpha = (0.35 - i * 0.02).toFloat()))
                }
            }
            drawSprite("ball", space.centered(engine.ball.x, engine.ball.y, 10.0, 10.0))
            drawGameSlime(
                slime,
                if (engine.streak >= 2) SpriteAnim.celebrate else SpriteAnim.idle,
                (12 * space.scale).toFloat(),
                space.offset(50.0, 152.0),
                engine.time,
            )
        }
        if (engine.streak >= 2) {
            // The court is always light, so the text stays dark in dark mode too.
            val at = space.offset(50.0, 70.0)
            Text(
                "${engine.streak} in a row",
                style = Theme.label(14.sp),
                color = hex(0x3A2B2E),
                modifier = Modifier.fillMaxSize().centeredAt(at.x, at.y),
            )
        }
    }
}
