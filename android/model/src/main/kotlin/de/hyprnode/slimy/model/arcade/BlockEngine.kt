package de.hyprnode.slimy.model.arcade

import de.hyprnode.slimy.model.EntityMotion
import de.hyprnode.slimy.model.EntityRole
import de.hyprnode.slimy.model.EntitySpec
import de.hyprnode.slimy.model.GameControl
import de.hyprnode.slimy.model.GameGoal
import de.hyprnode.slimy.model.GameSpec
import de.hyprnode.slimy.model.PowerKind
import de.hyprnode.slimy.model.SplitMix
import de.hyprnode.slimy.model.rounded
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Plays any GameSpec (the daily game). Like the other engines it is a plain
 * object: the view steps it, feeds it input and draws what it holds.
 * Board: 100 × 160.
 */
class BlockEngine(val spec: GameSpec, seed: ULong) {
    data class Thing(
        val id: Int,
        val kind: Int,
        val x: Double,
        val y: Double,
        val vx: Double,
        val vy: Double,
        val phase: Double,
        val age: Double = 0.0,
    )

    /** Something worth a sound, a particle burst or a shake. */
    sealed class Event {
        data class collect(val x: Double, val y: Double, val points: Int, val sprite: String) : Event()
        data class hit(val x: Double, val y: Double) : Event()
        data class blocked(val x: Double, val y: Double) : Event()
        data class power(val kind: PowerKind, val x: Double, val y: Double) : Event()
        data object miss : Event()
        data object jump : Event()
        data class combo(val count: Int) : Event()
    }

    private val rng = SplitMix(seed)
    private val _things = mutableListOf<Thing>()
    val things: List<Thing> get() = _things
    var x = 50.0
        private set
    var y = ground
        private set
    var vy = 0.0
        private set
    var lane = 1
        private set

    /** Where the finger is (drag, steer). */
    var target: BoardPoint = BoardPoint(50.0, ground)
    var time = 0.0
        private set
    var score = 0
        private set
    var lives: Int = spec.lives
        private set
    var combo = 0
        private set
    var bestCombo = 0
        private set
    var collected = 0
        private set
    private val _powers = LinkedHashMap<PowerKind, Double>()

    /** Active power-ups and the seconds they have left. */
    val powers: Map<PowerKind, Double> get() = _powers
    var invulnerable = 0.0
        private set
    var isOver = false
        private set
    private var airJumps = 0
    private val spawnIn: DoubleArray = DoubleArray(spec.entities.size) { 0.6 + 1 / max(0.1, spec.entities[it].rate) * 0.5 }
    private var nextID = 0
    private var survivalCarry = 0.0
    private val events = mutableListOf<Event>()

    init {
        when (spec.control) {
            GameControl.drag, GameControl.lanes -> { x = 50.0; y = ground }
            GameControl.steer -> { x = 50.0; y = 100.0 }
            GameControl.jump -> { x = 24.0; y = ground }
            GameControl.flap -> { x = 28.0; y = 80.0 }
        }
        target = BoardPoint(x, y)
    }

    val timeLeft: Double? get() = if (spec.goal == GameGoal.timed) max(0.0, spec.duration - time) else null
    val multiplier: Double get() = 1 + min(2.0, combo.toDouble() / 10)
    val playerRadius: Double get() = if (_powers[PowerKind.shrink] != null) 5.0 else 8.0
    val isOnGround: Boolean get() = y >= ground - 0.01

    /** How much faster and busier than at the start. */
    val intensity: Double get() = 1 + spec.ramp * time / 10

    // MARK: Input

    fun tap() {
        if (isOver) return
        when (spec.control) {
            GameControl.jump -> {
                if (isOnGround) {
                    vy = -170.0
                    airJumps = 1
                    events.add(Event.jump)
                } else if (airJumps > 0) {
                    vy = -140.0
                    airJumps -= 1
                    events.add(Event.jump)
                }
            }
            GameControl.flap -> {
                vy = -105.0
                events.add(Event.jump)
            }
            else -> Unit
        }
    }

    fun moveLane(direction: Int) {
        if (spec.control != GameControl.lanes || isOver) return
        val next = max(0, min(2, lane + direction))
        if (next != lane) {
            lane = next
            events.add(Event.jump)
        }
    }

    // MARK: Time

    fun step(dt: Double) {
        if (isOver || dt <= 0) return
        time += dt
        invulnerable = max(0.0, invulnerable - dt)
        for ((p, left) in _powers.entries.toList()) {
            if (left - dt > 0) _powers[p] = left - dt else _powers.remove(p)
        }
        movePlayer(dt)
        spawn(dt)
        moveThings(dt)
        collide()

        if (spec.goal == GameGoal.survive) {
            survivalCarry += dt
            while (survivalCarry >= 2) {
                survivalCarry -= 2
                score += 1
            }
        }
        if ((spec.goal == GameGoal.timed && time >= spec.duration) || (spec.lives > 0 && lives <= 0) || time >= 180) {
            isOver = true
        }
    }

    fun drainEvents(): List<Event> {
        val out = events.toList()
        events.clear()
        return out
    }

    private fun movePlayer(dt: Double) {
        when (spec.control) {
            GameControl.drag -> {
                val maxMove = 190 * dt
                x += max(-maxMove, min(maxMove, target.x - x))
                x = max(8.0, min(92.0, x))
            }
            GameControl.lanes -> {
                val goal = lanes[lane]
                x += max(-260 * dt, min(260 * dt, goal - x))
            }
            GameControl.steer -> {
                val dx = target.x - x
                val dy = target.y - y
                val dist = sqrt(dx * dx + dy * dy)
                val maxMove = 130 * dt
                if (dist > 0.5) {
                    x += dx / dist * min(dist, maxMove)
                    y += dy / dist * min(dist, maxMove)
                }
                x = max(8.0, min(92.0, x))
                y = max(16.0, min(150.0, y))
            }
            GameControl.jump -> {
                vy += 430 * dt
                y += vy * dt
                if (y >= ground) {
                    y = ground
                    vy = 0.0
                }
            }
            GameControl.flap -> {
                vy = min(160.0, vy + 280 * dt)
                y += vy * dt
                if (y < 14) {
                    y = 14.0
                    vy = max(0.0, vy)
                }
                if (y > 150) {
                    // Touching the ground hurts, then bounces you back up.
                    y = 150.0
                    vy = -120.0
                    hurt(x, y)
                }
            }
        }
    }

    private fun spawn(dt: Double) {
        for (i in spec.entities.indices) {
            spawnIn[i] -= dt * intensity
            if (spawnIn[i] > 0) continue
            val e = spec.entities[i]
            spawnIn[i] = (1 / e.rate) * (0.7 + rng.unit() * 0.6)
            _things.add(make(i, e))
            nextID += 1
        }
    }

    private fun make(kind: Int, e: EntitySpec): Thing {
        val speed = e.speed * min(2.2, intensity)
        val phase = rng.unit() * PI * 2
        return when (spec.control) {
            GameControl.drag, GameControl.lanes -> {
                val x = if (spec.control == GameControl.lanes) lanes[rng.int(0..2)] else 8 + rng.unit() * 84
                val side = if (e.motion == EntityMotion.bounce) (if (rng.unit() < 0.5) -30.0 else 30.0) else 0.0
                Thing(id = nextID, kind = kind, x = x, y = -8.0, vx = side, vy = 44 * speed, phase = phase)
            }
            GameControl.steer -> {
                // From a random edge toward a point in the middle.
                val sx: Double
                val sy: Double
                when (rng.int(0..3)) {
                    0 -> { sx = rng.unit() * 100; sy = -8.0 }
                    1 -> { sx = 108.0; sy = 10 + rng.unit() * 140 }
                    2 -> { sx = rng.unit() * 100; sy = 168.0 }
                    else -> { sx = -8.0; sy = 10 + rng.unit() * 140 }
                }
                val tx = 25 + rng.unit() * 50
                val ty = 40 + rng.unit() * 80
                val len = max(1.0, sqrt((tx - sx) * (tx - sx) + (ty - sy) * (ty - sy)))
                val v = 34 * speed
                Thing(id = nextID, kind = kind, x = sx, y = sy, vx = (tx - sx) / len * v, vy = (ty - sy) / len * v, phase = phase)
            }
            GameControl.jump -> {
                val low = if (e.role == EntityRole.avoid) rng.unit() < 0.75 else rng.unit() < 0.35
                val y = if (low) ground - 2 else ground - 26 - rng.unit() * 20
                Thing(id = nextID, kind = kind, x = 108.0, y = y, vx = -48 * speed, vy = 0.0, phase = phase)
            }
            GameControl.flap ->
                Thing(id = nextID, kind = kind, x = 108.0, y = 20 + rng.unit() * 125, vx = -42 * speed, vy = 0.0, phase = phase)
        }
    }

    private fun moveThings(dt: Double) {
        val slow = if (_powers[PowerKind.slow] != null) 0.5 else 1.0
        val magnet = _powers[PowerKind.magnet] != null
        val horizontalTravel = spec.control == GameControl.jump || spec.control == GameControl.flap
        val kept = mutableListOf<Thing>()
        for (original in _things) {
            val e = spec.entities[original.kind]
            val age = original.age + dt
            var tx = original.x
            var ty = original.y
            var tvx = original.vx
            var tvy = original.vy
            var dx = tvx
            var dy = tvy
            // The motion works across the direction of travel.
            when (e.motion) {
                EntityMotion.straight -> Unit
                EntityMotion.zigzag -> {
                    val flip = if ((age / 0.6 + original.phase).toInt() % 2 == 0) 1.0 else -1.0
                    if (horizontalTravel) dy += flip * 26 else dx += flip * 26
                }
                EntityMotion.wave -> {
                    val w = cos(age * 3 + original.phase) * 30
                    if (horizontalTravel) dy += w else dx += w
                }
                EntityMotion.homing -> {
                    if (horizontalTravel) {
                        dy += max(-30.0, min(30.0, (y - ty) * 1.2))
                    } else {
                        dx += max(-30.0, min(30.0, (x - tx) * 1.2))
                    }
                }
                EntityMotion.bounce -> {
                    if (spec.control == GameControl.jump) {
                        // Hops along the ground.
                        dy = 0.0
                        ty = ground - 2 - abs(sin(age * 4 + original.phase)) * 22
                    }
                }
            }
            if (magnet && e.role == EntityRole.collect) {
                val ddx = x - tx
                val ddy = y - ty
                val d = sqrt(ddx * ddx + ddy * ddy)
                if (d < 42 && d > 0.5) {
                    dx += ddx / d * 110
                    dy += ddy / d * 110
                }
            }
            tx += dx * dt * slow
            ty += dy * dt * slow
            if (e.motion == EntityMotion.bounce && spec.control != GameControl.jump) {
                if (tx < 6 || tx > 94) {
                    tvx = -tvx
                    tx = max(6.0, min(94.0, tx))
                }
                if (spec.control == GameControl.flap && (ty < 16 || ty > 148)) tvy = if (tvy == 0.0) 30.0 else -tvy
            }
            val gone = ty > 172 || ty < -20 || tx < -16 || tx > 116
            if (gone) {
                // A good thing that fell past you breaks the combo.
                if (e.role == EntityRole.collect && (spec.control == GameControl.drag || spec.control == GameControl.lanes) && combo > 0) {
                    combo = 0
                    events.add(Event.miss)
                }
                continue
            }
            kept.add(original.copy(x = tx, y = ty, vx = tvx, vy = tvy, age = age))
        }
        _things.clear()
        _things.addAll(kept)
    }

    private fun collide() {
        val kept = mutableListOf<Thing>()
        for (t in _things) {
            val e = spec.entities[t.kind]
            val reach = playerRadius + 5 * e.size
            val dx = t.x - x
            val dy = t.y - (y - 6)
            if (dx * dx + dy * dy >= reach * reach) {
                kept.add(t)
                continue
            }
            when (e.role) {
                EntityRole.collect -> {
                    combo += 1
                    bestCombo = max(bestCombo, combo)
                    collected += 1
                    val points = (e.points.toDouble() * multiplier * (if (_powers[PowerKind.double] != null) 2.0 else 1.0)).rounded().toInt()
                    score += points
                    events.add(Event.collect(t.x, t.y, points, e.sprite))
                    if (combo % 10 == 0) events.add(Event.combo(combo))
                }
                EntityRole.avoid -> {
                    if (invulnerable > 0) {
                        kept.add(t)
                        continue
                    }
                    if (_powers[PowerKind.shield] != null) {
                        _powers.remove(PowerKind.shield)
                        invulnerable = 0.6
                        events.add(Event.blocked(t.x, t.y))
                    } else {
                        if (spec.lives == 0) score = max(0, score - e.points)
                        hurt(t.x, t.y)
                    }
                }
                EntityRole.power -> {
                    val p = e.power ?: PowerKind.shield
                    if (p == PowerKind.life) {
                        if (spec.lives > 0) lives = min(5, lives + 1) else score += 10
                    } else {
                        _powers[p] = p.seconds
                    }
                    events.add(Event.power(p, t.x, t.y))
                }
            }
        }
        _things.clear()
        _things.addAll(kept)
    }

    private fun hurt(x: Double, y: Double) {
        if (invulnerable > 0) return
        combo = 0
        invulnerable = 1.2
        if (spec.lives > 0) lives -= 1
        events.add(Event.hit(x, y))
    }

    companion object {
        const val ground = 146.0
        val lanes = listOf(20.0, 50.0, 80.0)
    }
}
