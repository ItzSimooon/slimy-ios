package de.hyprnode.slimy.model.arcade

import de.hyprnode.slimy.model.SplitMix
import de.hyprnode.slimy.model.rounded
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// The real-time games. Each engine is a plain object with no UI: the view
// feeds it time and input, draws what it holds and plays the events it
// reports. Everything random comes from a seeded generator, so a round can
// be replayed exactly (async challenges, tests), on iPhone and Android alike.
//
// Boards are 100 units wide and 160 high, y pointing down.

object Board {
    const val width = 100.0
    const val height = 160.0
}

/** Sounds and effects an engine wants played after a step. */
sealed class ArcadeEvent {
    data object good : ArcadeEvent()
    data object great : ArcadeEvent()
    data object bad : ArcadeEvent()
    data object coin : ArcadeEvent()
    data object miss : ArcadeEvent()
    data object jump : ArcadeEvent()
    data object land : ArcadeEvent()
    data class tone(val i: Int) : ArcadeEvent()
    data object win : ArcadeEvent()
    data object lose : ArcadeEvent()
}

// MARK: - Snack Catcher

class SnackCatcherEngine(seed: ULong) {
    enum class Kind { food, coin, bad }

    data class Drop(
        val id: Int,
        val x: Double,
        val y: Double,
        val kind: Kind,
        val sprite: String,
        val speed: Double,
    )

    private val rng = SplitMix(seed)
    private val _drops = mutableListOf<Drop>()
    val drops: List<Drop> get() = _drops
    var playerX = 50.0

    /** Where the finger is; the slime slides there. */
    var targetX = 50.0
    var score = 0
        private set
    var combo = 0
        private set
    var bestCombo = 0
        private set
    var time = 0.0
        private set
    private var spawnIn = 0.5
    private var nextID = 0
    private val events = mutableListOf<ArcadeEvent>()

    val isOver: Boolean get() = time >= duration
    val timeLeft: Double get() = max(0.0, duration - time)

    fun step(dt: Double) {
        if (isOver || dt <= 0) return
        time += dt
        val maxMove = 170 * dt
        playerX += max(-maxMove, min(maxMove, targetX - playerX))
        playerX = max(8.0, min(Board.width - 8, playerX))

        spawnIn -= dt
        if (spawnIn <= 0) spawn()

        val kept = mutableListOf<Drop>()
        for (original in _drops) {
            val before = original.y
            val drop = original.copy(y = original.y + original.speed * dt)
            // Caught when it passes the slime's mouth this step.
            if (before < catchY && drop.y >= catchY - 8 && abs(drop.x - playerX) <= reach) {
                collect(drop)
                continue
            }
            if (drop.y > Board.height + 10) {
                if (drop.kind == Kind.food && combo > 0) {
                    combo = 0
                    events.add(ArcadeEvent.miss)
                }
                continue
            }
            kept.add(drop)
        }
        _drops.clear()
        _drops.addAll(kept)
    }

    fun drainEvents(): List<ArcadeEvent> {
        val out = events.toList()
        events.clear()
        return out
    }

    private fun spawn() {
        val progress = time / duration
        spawnIn = 0.8 - progress * 0.4 + rng.unit() * 0.25
        val roll = rng.unit()
        val kind = if (roll < 0.66) Kind.food else (if (roll < 0.78) Kind.coin else Kind.bad)
        val sprite = when (kind) {
            Kind.food -> foods[rng.int(0..foods.size - 1)]
            Kind.coin -> "coin"
            Kind.bad -> if (rng.unit() < 0.5) "rotten" else "cactus"
        }
        val speed = 48 + progress * 60 + rng.unit() * 18
        val x = 8 + rng.unit() * (Board.width - 16)
        _drops.add(Drop(id = nextID, x = x, y = -8.0, kind = kind, sprite = sprite, speed = speed))
        nextID += 1
    }

    private fun collect(drop: Drop) {
        when (drop.kind) {
            Kind.food -> {
                combo += 1
                bestCombo = max(bestCombo, combo)
                // Every five in a row adds one point per catch.
                score += 1 + combo / 5
                events.add(if (combo % 5 == 0) ArcadeEvent.great else ArcadeEvent.good)
            }
            Kind.coin -> {
                score += 3
                events.add(ArcadeEvent.coin)
            }
            Kind.bad -> {
                score = max(0, score - 3)
                combo = 0
                events.add(ArcadeEvent.bad)
            }
        }
    }

    companion object {
        const val duration = 45.0
        const val catchY = 142.0
        const val reach = 12.0
        private val foods = listOf("apple", "carrot", "berry", "mushroom", "cookie", "honey", "cake", "pudding")
    }
}

// MARK: - Slime Jump

class SlimeJumpEngine(seed: ULong) {
    data class Platform(
        val id: Int,
        val x: Double,
        val y: Double,
        val width: Double,
        /** Horizontal speed; 0 for still platforms. */
        val drift: Double,
        val coin: Boolean,
    )

    private val rng = SplitMix(seed)
    private val _platforms = mutableListOf<Platform>()
    val platforms: List<Platform> get() = _platforms

    /** Position in world units; y grows downward like the screen, so going up means y gets smaller. */
    var x = 50.0
        private set
    var y = 140.0
        private set
    var vy = -jumpSpeed
        private set

    /** -1 left, 0 none, 1 right. */
    var steer = 0.0

    /** Top of the visible window in world units. */
    var cameraY = 0.0
        private set
    var score = 0
        private set
    var coins = 0
        private set
    var isOver = false
        private set
    private var highest = 0
    private var nextID = 0
    private val events = mutableListOf<ArcadeEvent>()

    init {
        _platforms.add(Platform(id = 0, x = 50.0, y = 150.0, width = 40.0, drift = 0.0, coin = false))
        nextID = 1
        fill()
    }

    fun step(dt: Double) {
        if (isOver || dt <= 0) return
        for (i in _platforms.indices) {
            var p = _platforms[i]
            if (p.drift == 0.0) continue
            p = p.copy(x = p.x + p.drift * dt)
            val half = p.width / 2
            if (p.x < half || p.x > Board.width - half) {
                p = p.copy(drift = -p.drift, x = max(half, min(Board.width - half, p.x)))
            }
            _platforms[i] = p
        }

        x += steer * steerSpeed * dt
        if (x < 0) x += Board.width
        if (x > Board.width) x -= Board.width

        val before = y
        vy += gravity * dt
        y += vy * dt

        if (vy > 0) {
            for (p in _platforms.toList()) {
                if (!(before <= p.y && y >= p.y && abs(x - p.x) <= p.width / 2 + 3)) continue
                y = p.y
                vy = -jumpSpeed
                events.add(ArcadeEvent.jump)
                if (p.id > highest) {
                    score += p.id - highest
                    highest = p.id
                }
                if (p.coin) {
                    val i = _platforms.indexOfFirst { it.id == p.id }
                    if (i >= 0) {
                        _platforms[i] = _platforms[i].copy(coin = false)
                        coins += 1
                        score += 2
                        events.add(ArcadeEvent.coin)
                    }
                }
                break
            }
        }

        // The camera only follows upward.
        cameraY = min(cameraY, y - 90)
        if (y > cameraY + Board.height + 12) {
            isOver = true
            events.add(ArcadeEvent.lose)
        }
        _platforms.removeAll { it.y > cameraY + Board.height + 30 }
        fill()
    }

    fun drainEvents(): List<ArcadeEvent> {
        val out = events.toList()
        events.clear()
        return out
    }

    private fun fill() {
        var top = _platforms.minOfOrNull { it.y } ?: 150.0
        while (top > cameraY - 60) {
            val difficulty = min(1.0, nextID.toDouble() / 60)
            top -= 20 + rng.unit() * (10 + difficulty * 14)
            val width = 26 - difficulty * 10 + rng.unit() * 6
            val half = width / 2
            val moving = nextID > 12 && rng.unit() < 0.15 + difficulty * 0.3
            val px = half + rng.unit() * (Board.width - width)
            val drift = if (moving) (if (rng.unit() < 0.5) -1.0 else 1.0) * (14 + difficulty * 18) else 0.0
            val coin = rng.unit() < 0.18
            _platforms.add(Platform(id = nextID, x = px, y = top, width = width, drift = drift, coin = coin))
            nextID += 1
        }
    }

    companion object {
        const val gravity = 190.0
        const val jumpSpeed = 150.0
        const val steerSpeed = 75.0
    }
}

// MARK: - Slime Racing

class SlimeRacingEngine(seed: ULong) {
    enum class Kind { rock, puddle }

    data class Obstacle(
        val id: Int,
        val lane: Int,
        val distance: Double,
        val kind: Kind,
        val hit: Boolean = false,
    )

    private val _obstacles = mutableListOf<Obstacle>()
    val obstacles: List<Obstacle> get() = _obstacles
    var lane = 1
        private set
    var distance = 0.0
        private set
    var speed = 20.0
        private set
    var time = 0.0
        private set
    var finished = false
        private set
    private val events = mutableListOf<ArcadeEvent>()

    init {
        val rng = SplitMix(seed)
        var d = 90.0
        var id = 0
        while (d < length - 40) {
            val lane = rng.int(0..lanes - 1)
            val kind = if (rng.unit() < 0.75) Kind.rock else Kind.puddle
            _obstacles.add(Obstacle(id = id, lane = lane, distance = d, kind = kind))
            id += 1
            // Sometimes a second rock beside it, never all three lanes.
            if (rng.unit() < 0.3) {
                val other = (lane + 1 + rng.int(0..1)) % lanes
                _obstacles.add(Obstacle(id = id, lane = other, distance = d, kind = Kind.rock))
                id += 1
            }
            d += 55 + rng.unit() * 55
        }
    }

    val ghostDistance: Double get() = min(length, time * ghostSpeed)
    val isOver: Boolean get() = finished || time >= timeLimit

    /** Equal to the ghost is a good race (60); every second faster adds 6. */
    val score: Int
        get() {
            if (finished) {
                val ghostTime = length / ghostSpeed
                return max(5, min(150, (60 + (ghostTime - time) * 6).rounded().toInt()))
            }
            return (distance / length * 40).toInt()
        }

    fun move(direction: Int) {
        if (isOver) return
        val next = max(0, min(lanes - 1, lane + direction))
        if (next != lane) {
            lane = next
            events.add(ArcadeEvent.jump)
        }
    }

    fun step(dt: Double) {
        if (isOver || dt <= 0) return
        time += dt
        speed = if (speed < cruise) min(cruise, speed + 28 * dt) else max(cruise, speed - 14 * dt)
        val before = distance
        distance += speed * dt
        for (i in _obstacles.indices) {
            val o = _obstacles[i]
            if (o.hit || o.lane != lane || !(before < o.distance && distance >= o.distance)) continue
            _obstacles[i] = o.copy(hit = true)
            when (o.kind) {
                Kind.rock -> {
                    speed = 14.0
                    events.add(ArcadeEvent.bad)
                }
                Kind.puddle -> {
                    speed = 90.0
                    events.add(ArcadeEvent.good)
                }
            }
        }
        if (distance >= length) {
            distance = length
            finished = true
            events.add(if (time <= length / ghostSpeed) ArcadeEvent.win else ArcadeEvent.lose)
        }
    }

    fun drainEvents(): List<ArcadeEvent> {
        val out = events.toList()
        events.clear()
        return out
    }

    companion object {
        const val length = 1500.0
        const val lanes = 3
        const val cruise = 55.0
        const val ghostSpeed = 50.0
        const val timeLimit = 60.0
    }
}

// MARK: - Rhythm Bounce

class RhythmBounceEngine(seed: ULong) {
    enum class Judgement { perfect, good, miss }

    data class Note(
        val id: Int,
        val time: Double,
        val lane: Int,
        val judged: Judgement? = null,
    )

    private val _notes = mutableListOf<Note>()
    val notes: List<Note> get() = _notes
    var time = -1.0
        private set
    var score = 0
        private set
    var combo = 0
        private set
    var bestCombo = 0
        private set
    var perfects = 0
        private set

    /** The last judgement and when it happened. */
    var lastJudgement: Pair<Judgement, Double>? = null
        private set
    private val events = mutableListOf<ArcadeEvent>()

    init {
        val rng = SplitMix(seed)
        val beat = 60 / bpm
        var id = 0
        var lane = 1
        for (i in 0 until 56) {
            val t = 1.5 + i.toDouble() * beat
            // Rests now and then, quicker eighths later in the song.
            if (rng.unit() >= 0.82) continue
            lane = (lane + 1 + rng.int(0..1)) % lanes
            _notes.add(Note(id = id, time = t, lane = lane))
            id += 1
            if (i > 24 && rng.unit() < 0.25) {
                _notes.add(Note(id = id, time = t + beat / 2, lane = (lane + 1) % lanes))
                id += 1
            }
        }
    }

    val duration: Double get() = (_notes.lastOrNull()?.time ?: 0.0) + 1.2
    val isOver: Boolean get() = time >= duration

    fun step(dt: Double) {
        if (isOver || dt <= 0) return
        time += dt
        for (i in _notes.indices) {
            val n = _notes[i]
            if (n.judged != null || n.time >= time - goodWindow) continue
            _notes[i] = n.copy(judged = Judgement.miss)
            combo = 0
            lastJudgement = Pair(Judgement.miss, time)
            events.add(ArcadeEvent.miss)
        }
    }

    fun tap(lane: Int) {
        if (isOver) return
        val candidates = _notes.indices.filter { _notes[it].judged == null && _notes[it].lane == lane }
        val i = candidates.minByOrNull { abs(_notes[it].time - time) } ?: return
        if (abs(_notes[i].time - time) > goodWindow) return
        val off = abs(_notes[i].time - time)
        val judgement = if (off <= perfectWindow) Judgement.perfect else Judgement.good
        _notes[i] = _notes[i].copy(judged = judgement)
        combo += 1
        bestCombo = max(bestCombo, combo)
        if (judgement == Judgement.perfect) {
            perfects += 1
            score += 10 + min(combo, 10)
        } else {
            score += 5
        }
        lastJudgement = Pair(judgement, time)
        events.add(ArcadeEvent.tone(lane * 3 + (if (judgement == Judgement.perfect) 2 else 0)))
    }

    fun drainEvents(): List<ArcadeEvent> {
        val out = events.toList()
        events.clear()
        return out
    }

    companion object {
        const val lanes = 3
        const val bpm = 112.0
        const val perfectWindow = 0.07
        const val goodWindow = 0.16

        /** Seconds a note is visible before it reaches the line. */
        const val lead = 1.6
    }
}

// MARK: - Slime Basketball

class BasketballEngine(seed: ULong) {
    var time = 0.0
        private set
    var hoopX = 50.0
        private set
    var ball: BoardPoint = start
        private set

    /** Ball velocity in board units per second (x, y). */
    var velocity: BoardPoint = BoardPoint(0.0, 0.0)
        private set
    var flying = false
        private set
    var score = 0
        private set
    var streak = 0
        private set
    var hits = 0
        private set
    var shots = 0
        private set
    private var scoredThisShot = false
    private val phase: Double
    private val events = mutableListOf<ArcadeEvent>()

    init {
        val rng = SplitMix(seed)
        phase = rng.unit() * PI * 2
    }

    val isOver: Boolean get() = time >= duration && !flying
    val timeLeft: Double get() = max(0.0, duration - time)

    /** A swipe from the ball, in board units. Longer swipes throw harder. */
    fun throwBall(dx: Double, dy: Double) {
        if (flying || isOver || dy >= -4) return
        var vx = dx * 2.5
        var vy = dy * 2.5
        val length = sqrt(vx * vx + vy * vy)
        if (length > 330) {
            vx *= 330 / length
            vy *= 330 / length
        }
        velocity = BoardPoint(vx, vy)
        flying = true
        scoredThisShot = false
        shots += 1
        events.add(ArcadeEvent.jump)
    }

    fun step(dt: Double) {
        if (dt <= 0) return
        if (time < duration) time += dt
        // The hoop drifts faster the more you score.
        val pace = 0.9 + min(hits, 15).toDouble() * 0.08
        hoopX = 50 + sin(time * pace + phase) * 30
        if (!flying) return

        val beforeY = ball.y
        var vx = velocity.x
        var vy = velocity.y + gravity * dt
        ball = BoardPoint(ball.x + vx * dt, ball.y + vy * dt)

        if (vy > 0 && beforeY < hoopY && ball.y >= hoopY && !scoredThisShot) {
            val off = abs(ball.x - hoopX)
            if (off <= rim - 2) {
                scoredThisShot = true
                hits += 1
                streak += 1
                score += if (streak >= 2) 2 else 1
                events.add(if (streak >= 2) ArcadeEvent.great else ArcadeEvent.good)
            } else if (off <= rim + 3) {
                // Clanks off the rim.
                vy = -vy * 0.35
                vx = (if (ball.x < hoopX) -1.0 else 1.0) * (abs(vx) * 0.5 + 20)
                events.add(ArcadeEvent.land)
            }
        }
        velocity = BoardPoint(vx, vy)

        if (ball.y > Board.height + 10 || ball.x < -20 || ball.x > Board.width + 20) {
            if (!scoredThisShot) {
                streak = 0
                events.add(ArcadeEvent.miss)
            }
            flying = false
            ball = start
            velocity = BoardPoint(0.0, 0.0)
        }
    }

    fun drainEvents(): List<ArcadeEvent> {
        val out = events.toList()
        events.clear()
        return out
    }

    companion object {
        const val duration = 45.0
        const val gravity = 230.0
        val start = BoardPoint(50.0, 140.0)
        const val hoopY = 42.0
        const val rim = 9.0
    }
}
