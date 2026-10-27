package de.hyprnode.slimy.model.arcade

import de.hyprnode.slimy.model.GameID
import de.hyprnode.slimy.model.GameResult
import de.hyprnode.slimy.model.GameSpec
import de.hyprnode.slimy.model.SlimeSprites
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.model.argb
import de.hyprnode.slimy.model.rounded
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// The pure half of the iPhone app's App/Arcade/GameKit.swift: the round,
// board geometry, game feel state and small helpers. Drawing lives in the app.

/** A point in board units (or view units, for `BoardSpace` results). */
data class BoardPoint(val x: Double, val y: Double)

/** A size in board or view units. */
data class BoardSize(val width: Double, val height: Double) {
    companion object {
        val zero = BoardSize(0.0, 0.0)
    }
}

/** A rectangle in view units, from `BoardSpace`. */
data class BoardRect(val x: Double, val y: Double, val width: Double, val height: Double) {
    val left: Double get() = x
    val top: Double get() = y
    val right: Double get() = x + width
    val bottom: Double get() = y + height
    val centerX: Double get() = x + width / 2
    val centerY: Double get() = y + height / 2
}

/**
 * One round of one game. The host owns it; the game view writes its score
 * and calls end(). Its id is the reward key, so a round pays out once.
 *
 * Plain state: the arcade UI wraps it in Compose state to observe it.
 */
class GameSession(
    val game: GameID,
    seed: ULong? = null,
    /** The day's building-block game, for `daily`. */
    val spec: GameSpec? = null,
) {
    val id: UUID = UUID.randomUUID()
    val seed: ULong = seed ?: randomSeed()
    var score: Int = 0

    /** Short text for the top bar, like the time left. */
    var status: String = ""
    var paused: Boolean = false
    var ended: Boolean = false
        private set

    val running: Boolean get() = !paused && !ended

    fun end() {
        if (ended) return
        ended = true
    }

    /** What the round pays out, as GameHost builds it when the round ends. */
    fun result(now: Instant = Instant.now()): GameResult =
        GameResult(game = game, score = score, session = id, finishedAt = now,
            target = spec?.goodScore, day = spec?.id)

    companion object {
        /** `UInt64.random(in: 1...UInt64.max)`. */
        fun randomSeed(): ULong {
            val v = Random.nextLong().toULong()
            return if (v == 0uL) 1uL else v
        }
    }
}

/** The sound an arcade event plays. */
val ArcadeEvent.sound: Sound
    get() = when (this) {
        ArcadeEvent.good -> Sound.pop
        ArcadeEvent.great -> Sound.perfect
        ArcadeEvent.bad -> Sound.nope
        ArcadeEvent.coin -> Sound.coin
        ArcadeEvent.miss -> Sound.miss
        ArcadeEvent.jump -> Sound.hop
        ArcadeEvent.land -> Sound.land
        is ArcadeEvent.tone -> Sound.tone(i)
        ArcadeEvent.win -> Sound.success
        ArcadeEvent.lose -> Sound.whoosh
    }

// MARK: - Board geometry

/** Maps board units (100 × 160) into a view of [size] (any unit, e.g. pixels). */
class BoardSpace(size: BoardSize, width: Double = Board.width, height: Double = Board.height) {
    val scale: Double = min(size.width / width, size.height / height)
    val origin: BoardPoint = BoardPoint((size.width - width * scale) / 2, (size.height - height * scale) / 2)

    constructor(viewWidth: Float, viewHeight: Float, width: Double = Board.width, height: Double = Board.height) :
        this(BoardSize(viewWidth.toDouble(), viewHeight.toDouble()), width, height)

    fun point(x: Double, y: Double): BoardPoint = BoardPoint(origin.x + x * scale, origin.y + y * scale)

    fun rect(x: Double, y: Double, w: Double, h: Double): BoardRect =
        BoardRect(origin.x + x * scale, origin.y + y * scale, w * scale, h * scale)

    /** A rect of the given size centred on a board point. */
    fun centered(x: Double, y: Double, w: Double, h: Double): BoardRect = rect(x - w / 2, y - h / 2, w, h)

    fun boardX(viewX: Double): Double = (viewX - origin.x) / scale
    fun boardY(viewY: Double): Double = (viewY - origin.y) / scale
}

// MARK: - Game feel

/**
 * Particle bursts, floating score popups, screen shake and a hit flash.
 * Purely visual; games add to it from their events and draw it on top.
 * Colours are ARGB longs.
 */
class Juice {
    data class Particle(
        val x: Double, val y: Double, val vx: Double, val vy: Double,
        val life: Double, val maxLife: Double, val size: Double,
        val color: Long,
    )

    data class Popup(
        val text: String,
        val x: Double, val y: Double, val age: Double,
        val color: Long,
        val big: Boolean,
    )

    private val _particles = mutableListOf<Particle>()
    private val _popups = mutableListOf<Popup>()
    val particles: List<Particle> get() = _particles
    val popups: List<Popup> get() = _popups
    var shake = 0.0
        private set
    var flash = 0.0
        private set
    var squash = 0.0
        private set
    var reduceMotion = false

    fun burst(x: Double, y: Double, color: Long, count: Int = 10, power: Double = 60.0) {
        if (reduceMotion) return
        for (i in 0 until count) {
            val angle = i.toDouble() / count.toDouble() * PI * 2 + Random.nextDouble(-0.3, 0.3)
            val speed = power * Random.nextDouble(0.5, 1.1)
            val life = Random.nextDouble(0.35, 0.7)
            _particles.add(
                Particle(x = x, y = y, vx = cos(angle) * speed, vy = sin(angle) * speed - 20,
                    life = life, maxLife = life, size = Random.nextDouble(1.4, 2.6), color = color),
            )
        }
        if (_particles.size > 220) repeat(_particles.size - 220) { _particles.removeAt(0) }
    }

    fun popup(text: String, x: Double, y: Double, color: Long = 0xFFFFFFFF, big: Boolean = false) {
        _popups.add(Popup(text = text, x = x, y = y, age = 0.0, color = color, big = big))
        if (_popups.size > 12) repeat(_popups.size - 12) { _popups.removeAt(0) }
    }

    fun hit() {
        shake = if (reduceMotion) 0.0 else 0.32
        flash = 0.25
    }

    fun bounce() {
        squash = 0.18
    }

    fun step(dt: Double) {
        for (i in _particles.indices) {
            val p = _particles[i]
            _particles[i] = p.copy(x = p.x + p.vx * dt, y = p.y + p.vy * dt, vy = p.vy + 140 * dt, life = p.life - dt)
        }
        _particles.removeAll { it.life <= 0 }
        for (i in _popups.indices) {
            val p = _popups[i]
            _popups[i] = p.copy(age = p.age + dt, y = p.y - 22 * dt)
        }
        _popups.removeAll { it.age > 0.9 }
        shake = max(0.0, shake - dt)
        flash = max(0.0, flash - dt)
        squash = max(0.0, squash - dt)
    }

    /** Screen offset while shaking (view points). */
    fun shakeOffset(time: Double): BoardSize {
        if (shake <= 0) return BoardSize.zero
        val a = shake / 0.32 * 6
        return BoardSize(sin(time * 90) * a, cos(time * 77) * a * 0.6)
    }

    /** Squash-and-stretch scale for the player, (x, y). */
    val squashScale: Pair<Double, Double>
        get() {
            val k = sin(squash / 0.18 * PI) * 0.22
            return Pair(1 + k, 1 - k)
        }
}

/** The main colour of an item sprite, for particles (ARGB). */
fun SlimeSprites.mainColor(name: String): Long {
    val sprite = items[name] ?: return 0xFFFFFFFF
    val hex = sprite.palette["a"] ?: sprite.palette.values.sorted().lastOrNull() ?: 0xFFFFFF
    return argb(hex)
}

/** Blends two `0xRRGGBB` colours channel by channel (the banded game backdrop). */
fun mixColor(a: Long, b: Long, t: Double): Long {
    fun channel(shift: Int): Long {
        val x = ((a shr shift) and 0xFF).toDouble()
        val y = ((b shr shift) and 0xFF).toDouble()
        return (x + (y - x) * t).rounded().toLong() shl shift
    }
    return channel(16) or channel(8) or channel(0)
}

/** "0:32" */
fun clock(seconds: Double): String {
    val s = ceil(seconds).toInt()
    return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}
