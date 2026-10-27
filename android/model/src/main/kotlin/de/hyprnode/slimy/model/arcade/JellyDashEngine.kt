package de.hyprnode.slimy.model.arcade

import de.hyprnode.slimy.model.SplitMix
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// MARK: - Jelly Dash

/**
 * An endless run to the right. A tap jumps, holding the finger down jumps
 * higher and farther. Puddles and rocks cost a heart, coins and berries
 * give points, and the run gets faster the longer it lasts.
 *
 * Things live in world units along the track (`x` grows to the right);
 * on screen a thing sits at `x - distance`, and the slime always runs at
 * `playerX`. Heights use the board's y, pointing down, with the track at
 * `ground`.
 */
class JellyDashEngine(seed: ULong) {
    enum class ObstacleKind {
        puddle, rock,

        /** Two rocks on top of each other: only a held jump clears it. */
        boulder;

        val width: Double
            get() = when (this) {
                puddle -> 14.0
                rock -> 9.0
                boulder -> 11.0
            }

        val height: Double
            get() = when (this) {
                puddle -> 2.5
                rock -> 9.0
                boulder -> 24.0
            }
    }

    enum class PickupKind { coin, berry }

    data class Obstacle(
        val id: Int,
        /** Centre along the track. */
        val x: Double,
        val kind: ObstacleKind,
        /** Already touched; it can't hurt twice. */
        val touched: Boolean = false,
    )

    data class Pickup(
        val id: Int,
        val x: Double,
        /** Board y of its centre. */
        val y: Double,
        val kind: PickupKind,
    )

    private val rng = SplitMix(seed)
    private val _obstacles = mutableListOf<Obstacle>()
    private val _pickups = mutableListOf<Pickup>()
    val obstacles: List<Obstacle> get() = _obstacles
    val pickups: List<Pickup> get() = _pickups

    /** Track units run so far. */
    var distance = 0.0
        private set
    var speed = startSpeed
        private set

    /** The slime's feet. */
    var y = ground
        private set
    var vy = 0.0
        private set
    var time = 0.0
        private set
    var lives = JellyDashEngine.lives
        private set
    var invulnerable = 0.0
        private set

    /** Points from pickups and streaks. */
    var bonus = 0
        private set

    /** Pickups since the last hit. */
    var streak = 0
        private set
    var hits = 0
        private set
    var isOver = false
        private set
    var holding = false
        private set
    private var holdTime = maxHold
    private var buffered = 0.0
    private var nextSpawn = 130.0
    private var nextID = 0
    private val events = mutableListOf<ArcadeEvent>()

    val isOnGround: Boolean get() = y >= ground - 0.001 && vy >= 0
    val meters: Int get() = (distance / meter).toInt()
    val score: Int get() = meters + bonus

    /** 0 at the start, 1 from 4000 track units on. */
    val difficulty: Double get() = min(1.0, distance / 4000)

    // MARK: Input

    /** The finger went down: jump now, or right on landing. */
    fun press() {
        if (isOver) return
        holding = true
        if (isOnGround) jump() else buffered = jumpBuffer
    }

    /** The finger came up: the jump stops rising early. */
    fun release() {
        holding = false
        holdTime = maxHold
    }

    private fun jump() {
        vy = -jumpSpeed
        holdTime = 0.0
        buffered = 0.0
        events.add(ArcadeEvent.jump)
    }

    // MARK: Time

    fun step(dt: Double) {
        if (isOver || dt <= 0) return
        time += dt
        speed = min(maxSpeed, speed + acceleration * dt)
        distance += speed * dt
        invulnerable = max(0.0, invulnerable - dt)
        buffered = max(0.0, buffered - dt)

        var g = gravity
        if (holding && vy < 0 && holdTime < maxHold) {
            g *= holdGravity
            holdTime += dt
        }
        val wasInAir = !isOnGround
        vy += g * dt
        y += vy * dt
        if (y >= ground) {
            y = ground
            if (vy > 0) {
                vy = 0.0
                if (wasInAir) {
                    events.add(ArcadeEvent.land)
                    if (buffered > 0) jump()
                }
            }
        }

        spawnAhead()
        collide()
        val left = distance - 16
        _obstacles.removeAll { it.x + it.kind.width / 2 <= left }
        _pickups.removeAll { it.x <= left }
    }

    fun drainEvents(): List<ArcadeEvent> {
        val out = events.toList()
        events.clear()
        return out
    }

    // MARK: Track

    private enum class Pattern {
        puddle, rock, rockCoins, coinRow, boulder, highBerry, doubleRock, puddleRock, rockBoulder
    }

    private fun spawnAhead() {
        while (nextSpawn - distance < Board.width + 40) {
            val end = place(nextSpawn)
            // Gaps leave room to land and react; they shrink as it gets harder.
            val gap = speed * (0.9 + rng.unit() * 0.8) * (1 - 0.3 * difficulty)
            nextSpawn = end + gap + 16
        }
    }

    /** Lays out one pattern starting at [x]; returns where it ends. */
    private fun place(x: Double): Double {
        val d = difficulty
        val g = ground
        val options = mutableListOf(Pattern.puddle, Pattern.rock, Pattern.rockCoins, Pattern.coinRow)
        if (d >= 0.08) options += listOf(Pattern.boulder, Pattern.highBerry)
        if (d >= 0.2) options.add(Pattern.doubleRock)
        if (d >= 0.3) options.add(Pattern.puddleRock)
        if (d >= 0.5) options.add(Pattern.rockBoulder)
        return when (options[rng.int(0..options.size - 1)]) {
            Pattern.puddle -> {
                add(ObstacleKind.puddle, x)
                if (rng.unit() < 0.5) add(PickupKind.coin, x, g - 20)
                x + 7
            }
            Pattern.rock -> {
                add(ObstacleKind.rock, x)
                x + 5
            }
            Pattern.rockCoins -> {
                add(ObstacleKind.rock, x)
                add(PickupKind.coin, x - 10, g - 16)
                add(PickupKind.coin, x, g - 24)
                add(PickupKind.coin, x + 10, g - 16)
                x + 14
            }
            Pattern.coinRow -> {
                for (i in 0 until 5) add(PickupKind.coin, x + i.toDouble() * 9, g - 5)
                x + 36
            }
            Pattern.boulder -> {
                add(ObstacleKind.boulder, x)
                add(PickupKind.berry, x, g - 34)
                x + 5
            }
            Pattern.highBerry -> {
                add(PickupKind.berry, x, g - 36)
                add(PickupKind.coin, x - 10, g - 26)
                add(PickupKind.coin, x + 10, g - 26)
                x + 14
            }
            Pattern.doubleRock -> {
                add(ObstacleKind.rock, x)
                add(ObstacleKind.rock, x + 15)
                add(PickupKind.coin, x + 7.5, g - 30)
                x + 20
            }
            Pattern.rockBoulder -> {
                add(ObstacleKind.rock, x)
                val x2 = x + speed * 1.15 + 16
                add(ObstacleKind.boulder, x2)
                add(PickupKind.berry, x2, g - 34)
                x2 + 6
            }
            Pattern.puddleRock -> {
                add(ObstacleKind.puddle, x)
                val x2 = x + speed * 1.1 + 14
                add(ObstacleKind.rock, x2)
                x2 + 5
            }
        }
    }

    private fun add(kind: ObstacleKind, x: Double) {
        _obstacles.add(Obstacle(id = nextID, x = x, kind = kind))
        nextID += 1
    }

    private fun add(kind: PickupKind, x: Double, y: Double) {
        _pickups.add(Pickup(id = nextID, x = x, y = y, kind = kind))
        nextID += 1
    }

    // MARK: Contact

    private fun collide() {
        val px = distance + playerX
        for (i in _obstacles.indices) {
            val o = _obstacles[i]
            if (o.touched) continue
            val across = abs(o.x - px) < o.kind.width / 2 + halfWidth
            val low = y > ground - o.kind.height + 0.5
            if (!across || !low) continue
            _obstacles[i] = o.copy(touched = true)
            if (invulnerable > 0) continue
            lives -= 1
            hits += 1
            invulnerable = invulnerability
            streak = 0
            speed = max(startSpeed, speed * 0.85)
            events.add(ArcadeEvent.bad)
            if (lives <= 0) {
                isOver = true
                events.add(ArcadeEvent.lose)
                return
            }
        }

        val cy = y - bodyHeight / 2
        val kept = mutableListOf<Pickup>()
        for (p in _pickups) {
            val dx = p.x - px
            val dy = p.y - cy
            if (dx * dx + dy * dy > reach * reach) {
                kept.add(p)
                continue
            }
            bonus += if (p.kind == PickupKind.coin) coinValue else berryValue
            streak += 1
            events.add(if (p.kind == PickupKind.coin) ArcadeEvent.coin else ArcadeEvent.good)
            if (streak % 10 == 0) {
                bonus += streakBonus
                events.add(ArcadeEvent.great)
            }
        }
        _pickups.clear()
        _pickups.addAll(kept)
    }

    companion object {
        const val ground = 140.0
        const val playerX = 24.0
        const val gravity = 430.0
        const val jumpSpeed = 150.0

        /** Share of gravity while the finger stays down early in a jump. */
        const val holdGravity = 0.45
        const val maxHold = 0.22
        const val startSpeed = 48.0
        const val maxSpeed = 112.0

        /** Speed gained per second. */
        const val acceleration = 1.1
        const val lives = 3

        /** Track units per point. */
        const val meter = 25.0
        const val coinValue = 2
        const val berryValue = 5

        /** Extra points for every tenth pickup without getting hurt. */
        const val streakBonus = 10

        /** A tap this long before landing still jumps on touchdown. */
        const val jumpBuffer = 0.12
        const val invulnerability = 1.4

        /** The slime's hit box: half its width and its height. */
        const val halfWidth = 3.5
        const val bodyHeight = 9.0

        /** How close a pickup has to come to the slime's middle. */
        const val reach = 8.0
    }
}
