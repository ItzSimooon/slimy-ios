package de.hyprnode.slimy.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.max
import kotlin.math.min

// The daily plan: every day there is a new game built from blocks, a quest
// and a short story for the slime. DailyPlanner composes it on the phone from
// the date. A plan is only data, never code, and everything is clamped to
// safe ranges before it is played, wherever it came from.

/** How the player moves the slime. */
@Serializable
enum class GameControl {
    /** Slides along the bottom, following the finger. */
    drag,

    /** Three lanes at the bottom; tap or swipe to switch. */
    lanes,

    /** Floats freely after the finger; things come from every side. */
    steer,

    /** Runs along the ground; tap to jump (twice for a double jump). */
    jump,

    /** Hovers; every tap is a little flap upward. */
    flap;

    companion object {
        val allCases: List<GameControl> get() = entries
    }
}

/** What touching a thing does. */
@Serializable
enum class EntityRole { collect, avoid, power }

/** How a thing moves on top of travelling across the board. */
@Serializable
enum class EntityMotion {
    straight, zigzag, wave, homing, bounce;

    companion object {
        val allCases: List<EntityMotion> get() = entries
    }
}

@Serializable
enum class PowerKind {
    /** Ignores the next bad hit. */
    shield,

    /** Pulls good things in. */
    magnet,

    /** Everything moves at half speed for a while. */
    slow,

    /** Points count double for a while. */
    double,

    /** One more life (or bonus points when there are no lives). */
    life,

    /** The slime gets small and slips through gaps. */
    shrink;

    val seconds: Double
        get() = when (this) {
            shield -> 10.0
            magnet -> 7.0
            slow -> 5.0
            double -> 8.0
            life -> 0.0
            shrink -> 6.0
        }

    val title: String
        get() = when (this) {
            shield -> "Shield"
            magnet -> "Magnet"
            slow -> "Slow-mo"
            double -> "Double points"
            life -> "Extra life"
            shrink -> "Tiny"
        }

    companion object {
        val allCases: List<PowerKind> get() = entries
    }
}

@Serializable
enum class GameGoal {
    /** Score as much as you can before the time runs out. */
    timed,

    /** Last as long as you can; it gets harder and harder. */
    survive,
}

/** Sky top, sky bottom and ground colours of a theme (`0xRRGGBB`). */
data class ThemeColors(val top: Long, val bottom: Long, val ground: Long)

/** Background looks. Each is a palette plus drifting decoration. */
@Serializable
enum class GameTheme {
    dawn, day, dusk, night, ocean, forest, candy, lava, space, snow;

    /** Sky top, sky bottom, ground (`0xRRGGBB`; use `argb()` for drawing). */
    val colors: ThemeColors
        get() = when (this) {
            dawn -> ThemeColors(0xF6B48F, 0xFDE3C0, 0x8FBF6A)
            day -> ThemeColors(0x8CC8E8, 0xDDF1F5, 0x6FBF5A)
            dusk -> ThemeColors(0x5B4A8A, 0xF29A76, 0x5E7D4A)
            night -> ThemeColors(0x141A33, 0x2E3A66, 0x2F4A3A)
            ocean -> ThemeColors(0x1E5F8C, 0x4FB3C9, 0xD9C38A)
            forest -> ThemeColors(0x2F6B4A, 0x9DD382, 0x4E7A36)
            candy -> ThemeColors(0xF7A8C8, 0xFFE6F0, 0xC98AD9)
            lava -> ThemeColors(0x3A1414, 0xC9502A, 0x2A1A1A)
            space -> ThemeColors(0x07081A, 0x23164A, 0x4A3A6A)
            snow -> ThemeColors(0xB9D6EA, 0xF3F8FC, 0xE8F0F6)
        }

    enum class Decor { clouds, stars, bubbles, leaves, sparkles, embers, snowflakes }

    val decor: Decor
        get() = when (this) {
            dawn, day -> Decor.clouds
            dusk, candy -> Decor.sparkles
            night, space -> Decor.stars
            ocean -> Decor.bubbles
            forest -> Decor.leaves
            lava -> Decor.embers
            snow -> Decor.snowflakes
        }

    companion object {
        val allCases: List<GameTheme> get() = entries
    }
}

@Serializable
data class EntitySpec(
    /** An item sprite name from the allowed list. */
    var sprite: String,
    var role: EntityRole,
    var power: PowerKind? = null,
    var motion: EntityMotion,
    /** 0.5 (slow) … 2.0 (fast). */
    var speed: Double,
    /** Spawns per second at the start, 0.1 … 2.5. */
    var rate: Double,
    /** Points for collecting (1…25); for avoid things, points lost when there are no lives (0…10). */
    var points: Int,
    /** 0.6 … 1.6. */
    var size: Double,
)

@Serializable
data class GameSpec(
    var id: String,
    var title: String,
    var blurb: String,
    var tutorial: List<String>,
    var control: GameControl,
    var goal: GameGoal,
    /** Seconds for timed games, 20 … 90. */
    var duration: Double,
    /** 0 … 5. With 0 lives, bad hits cost points instead. */
    var lives: Int,
    var theme: GameTheme,
    var entities: List<EntitySpec>,
    /** How much faster and busier it gets every 10 seconds, 0 … 0.5. */
    var ramp: Double,
) {
    fun deepCopy(): GameSpec = copy(entities = entities.map { it.copy() })

    /**
     * Every value inside its safe range, at least one thing to collect, and
     * never so busy that it can't be played. The app runs this on every spec,
     * wherever it came from.
     */
    fun sanitized(): GameSpec {
        val s = deepCopy()
        s.title = title.trimmedSwift().prefixChars(28)
        if (s.title.isEmpty()) s.title = "Daily game"
        s.blurb = blurb.prefixChars(90)
        s.tutorial = tutorial.map { it.prefixChars(80) }.filter { it.isNotEmpty() }.take(3)
        s.duration = min(90.0, max(20.0, if (duration.isFinite()) duration else 45.0))
        s.lives = min(5, max(0, lives))
        if (goal == GameGoal.survive && s.lives == 0) s.lives = 3
        s.ramp = min(0.5, max(0.0, if (ramp.isFinite()) ramp else 0.15))

        val things = entities.take(6).mapNotNull { original ->
            if (!allowedSprites.contains(original.sprite)) return@mapNotNull null
            val e = original.copy()
            e.speed = min(2.0, max(0.5, if (e.speed.isFinite()) e.speed else 1.0))
            e.rate = min(2.5, max(0.1, if (e.rate.isFinite()) e.rate else 0.6))
            e.size = min(1.6, max(0.6, if (e.size.isFinite()) e.size else 1.0))
            when (e.role) {
                EntityRole.collect -> { e.points = min(25, max(1, e.points)); e.power = null }
                EntityRole.avoid -> { e.points = min(10, max(0, e.points)); e.power = null }
                EntityRole.power -> { e.points = 0; e.power = e.power ?: PowerKind.shield; e.rate = min(e.rate, 0.25) }
            }
            // Lanes keep things in their lane.
            if (control == GameControl.lanes) e.motion = if (e.motion == EntityMotion.homing) EntityMotion.homing else EntityMotion.straight
            e
        }.toMutableList()
        if (things.none { it.role == EntityRole.collect }) {
            things.add(
                0,
                EntitySpec(sprite = "coin", role = EntityRole.collect, power = null, motion = EntityMotion.straight,
                    speed = 1.0, rate = 0.8, points = 3, size = 1.0),
            )
        }
        // Keep the screen readable: total and harmful spawn rates are capped.
        val avoidRate = things.filter { it.role == EntityRole.avoid }.sumOf { it.rate }
        if (avoidRate > 1.6) {
            for (t in things) if (t.role == EntityRole.avoid) t.rate *= 1.6 / avoidRate
        }
        val total = things.sumOf { it.rate }
        if (total > 3.5) {
            for (t in things) t.rate *= 3.5 / total
        }
        s.entities = things
        if (s.tutorial.isEmpty()) s.tutorial = DailyPlanner.tutorial(s)
        return s
    }

    /**
     * A score that counts as a good round, estimated from what there is to
     * collect. Used for rewards and the quest; never taken from the spec.
     */
    val goodScore: Int
        get() {
            val seconds = if (goal == GameGoal.timed) duration else 60.0
            val expected = entities.filter { it.role == EntityRole.collect }
                .sumOf { it.rate * seconds * it.points.toDouble() * 0.45 }
            val survival = if (goal == GameGoal.survive) seconds / 2 else 0.0
            return max(10, ((expected + survival) / 5).rounded().toInt() * 5)
        }

    companion object {
        val allowedSprites: Set<String> = setOf(
            "apple", "carrot", "berry", "mushroom", "cookie", "soup", "honey", "pudding", "cake", "starJelly",
            "coin", "gem", "heart", "chest", "flower", "seed", "noteItem", "bubbleItem", "ball",
            "rock", "cactus", "rotten", "puddle",
        )
    }
}

/** A small task for the day, made with the plan. */
@Serializable
data class DailyQuest(
    var title: String,
    var goal: Goal,
    var coins: Int,
) {
    /**
     * JSON: {"kind": "stat", "stat": "feeds", "target": 3} or
     * {"kind": "dailyScore", "target": 120}.
     */
    @Serializable(with = Goal.Coding::class)
    sealed class Goal {
        data class stat(val key: StatKey, val target: Int) : Goal()
        data class dailyScore(val target: Int) : Goal()

        internal object Coding : KSerializer<Goal> {
            override val descriptor: SerialDescriptor = buildClassSerialDescriptor("DailyQuest.Goal")

            override fun serialize(encoder: Encoder, value: Goal) {
                val out = encoder as? JsonEncoder ?: throw SerializationException("JSON only")
                out.encodeJsonElement(buildJsonObject {
                    when (value) {
                        is stat -> {
                            put("kind", "stat")
                            put("stat", value.key.name)
                            put("target", value.target)
                        }
                        is dailyScore -> {
                            put("kind", "dailyScore")
                            put("target", value.target)
                        }
                    }
                })
            }

            override fun deserialize(decoder: Decoder): Goal {
                val input = decoder as? JsonDecoder ?: throw SerializationException("JSON only")
                val obj = input.decodeJsonElement() as? JsonObject ?: throw SerializationException("Goal expected")
                val target = obj["target"]?.jsonPrimitive?.int ?: throw SerializationException("No target")
                val kind = obj["kind"]?.jsonPrimitive?.content ?: throw SerializationException("No kind")
                return if (kind == "stat") {
                    val key = enumOf<StatKey>(obj["stat"]?.jsonPrimitive?.content) ?: throw SerializationException("Bad stat")
                    stat(key, target)
                } else {
                    dailyScore(target)
                }
            }
        }
    }

    fun sanitized(goodScore: Int): DailyQuest {
        val q = copy()
        q.title = title.prefixChars(60)
        q.coins = min(40, max(5, coins))
        q.goal = when (val g = goal) {
            is Goal.stat -> Goal.stat(g.key, min(15, max(1, g.target)))
            is Goal.dailyScore -> Goal.dailyScore(min(goodScore * 2, max(5, g.target)))
        }
        return q
    }
}

@Serializable
data class DailyPlan(
    /** The local day it belongs to, "yyyy-MM-dd". */
    var day: String,
    var game: GameSpec,
    var quest: DailyQuest,
    /** What the slime is thinking about today, one or two short sentences. */
    var story: String,
    /** "local" for plans made by DailyPlanner. Saves from older versions may say "ai". */
    var source: String,
) {
    val id: String get() = day

    fun sanitized(): DailyPlan {
        val p = copy()
        p.game = game.sanitized()
        p.game.id = day
        p.quest = quest.sanitized(p.game.goodScore)
        p.story = story.prefixChars(160)
        return p
    }
}

/** Progress on today's quest, kept in the save. */
@Serializable
data class QuestState(
    var day: String,
    var baseline: Int = 0,
    var best: Int = 0,
    var claimed: Boolean = false,
)
