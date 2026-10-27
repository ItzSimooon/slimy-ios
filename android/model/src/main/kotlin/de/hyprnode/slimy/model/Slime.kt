package de.hyprnode.slimy.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** The looping animation the slime falls back to. */
@Serializable
enum class Mood { idle, sad, sleep }

/** A short animation that plays after something happens. */
@Serializable
enum class Reaction {
    none, eat, play, love, bath, melt, zap, confused, wave,
    celebrate, stretch, sulk, hide, curious, blush;

    companion object {
        val allCases: List<Reaction> get() = entries
    }
}

/** Evolution stage. Each one has its own sprite set. */
@Serializable
enum class Stage {
    baby, young, grown;

    val title: String
        get() = when (this) {
            baby -> "Baby slime"
            young -> "Slime"
            grown -> "Big slime"
        }

    val firstLevel: Int
        get() = when (this) {
            baby -> 1
            young -> 4
            grown -> 12
        }

    companion object {
        val allCases: List<Stage> get() = entries

        fun forLevel(level: Int): Stage = when {
            level < 4 -> baby
            level < 12 -> young
            else -> grown
        }
    }
}

/**
 * What kind of slime it is. Shapes decay rates, dialogue and favourites,
 * and drifts slowly with how it is cared for.
 */
@Serializable
data class Personality(
    var playful: Double,
    var cuddly: Double,
    var foodie: Double,
    var sleepy: Double,
    var curious: Double,
) {
    @Serializable
    enum class Trait {
        playful, cuddly, foodie, sleepy, curious;

        val title: String get() = name

        val blurb: String
            get() = when (this) {
                playful -> "Gets bored quickly and loves games."
                cuddly -> "Wants lots of cuddles."
                foodie -> "Thinks about food all day."
                sleepy -> "Needs plenty of sleep."
                curious -> "Wants to look at and try everything."
            }

        companion object {
            val allCases: List<Trait> get() = entries
        }
    }

    operator fun get(trait: Trait): Double = when (trait) {
        Trait.playful -> playful
        Trait.cuddly -> cuddly
        Trait.foodie -> foodie
        Trait.sleepy -> sleepy
        Trait.curious -> curious
    }

    operator fun set(trait: Trait, newValue: Double) {
        val v = min(1.0, max(0.0, newValue))
        when (trait) {
            Trait.playful -> playful = v
            Trait.cuddly -> cuddly = v
            Trait.foodie -> foodie = v
            Trait.sleepy -> sleepy = v
            Trait.curious -> curious = v
        }
    }

    /** The two strongest traits, strongest first. */
    val top: List<Trait> get() = Trait.entries.sortedByDescending { this[it] }.take(2)

    val dominant: Trait get() = top[0]

    /** How much faster than normal a need drains for this personality. */
    fun decayFactor(need: Need): Double = when (need) {
        Need.hunger -> 0.85 + 0.35 * foodie
        Need.`fun` -> 0.85 + 0.35 * playful
        Need.energy -> 0.85 + 0.35 * sleepy
        Need.clean -> 1.0
        Need.love -> 0.85 + 0.35 * cuddly
    }

    /** Care shapes character, a little at a time. */
    fun drift(trait: Trait, by: Double = 0.004) {
        this[trait] = this[trait] + by
    }

    companion object {
        fun random(rng: SplitMix): Personality =
            Personality(playful = rng.unit(), cuddly = rng.unit(), foodie = rng.unit(), sleepy = rng.unit(), curious = rng.unit())

        val neutral: Personality get() = Personality(0.5, 0.5, 0.5, 0.5, 0.5)
    }
}

/** Something the slime is saying right now. */
@Serializable
data class Utterance(
    val text: String,
    val at: JsonDate,
    val lineID: String? = null,
)

@Serializable
data class Accessories(
    var hat: String? = null,
    var glasses: String? = null,
    var scarf: String? = null,
    var effect: String? = null,
) {
    val all: List<String> get() = listOfNotNull(hat, glasses, scarf, effect)
}

@Serializable
data class SleepSchedule(
    var bedtime: Int = 22,
    var wake: Int = 7,
    /** Goes to bed by itself at bedtime. */
    var auto: Boolean = true,
) {
    fun isNight(hour: Int): Boolean =
        if (bedtime > wake) (hour >= bedtime || hour < wake) else (hour >= bedtime && hour < wake)
}

@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = Slime.Coding::class)
data class Slime(
    var name: String = "Blob",
    var skin: Skin = Skin.lime,
    var birthday: JsonDate = Instant.now(),
    var needs: Needs = Needs(),
    var xp: Int = 0,
    var isAsleep: Boolean = false,
    var lastUpdate: JsonDate = Instant.now(),
    var reaction: Reaction = Reaction.none,
    var reactionAt: JsonDate = DistantPast,
    var favorite: Food = Food.berry,
    var favoriteGame: GameID = GameID.snackCatcher,
    var personality: Personality = Personality.neutral,
    /** 0...100: how close the slime feels to you. Only grows. */
    var bond: Double = 0.0,
    var say: Utterance? = null,
    var lastEvent: PhoneEvent? = null,
    var eventAt: JsonDate = DistantPast,
    var recentPets: List<JsonDate> = emptyList(),
    var equipped: Accessories = Accessories(),
    /** The need it last told you about, so it doesn't repeat itself. */
    var lastNudged: Need? = null,
    var lastNudgeAt: JsonDate = DistantPast,
    /** How it feels. Settles over time in `advance`; care, games, friends, crates and the chat add to it. */
    var emotions: Emotions = Emotions(),
    /** Colours it has besides the ones its level opens: found in crates. The secret colour only ever comes this way. */
    @Serializable(with = LossySkinSetSerializer::class)
    var colours: Set<Skin> = emptySet(),
) {
    /** A copy that shares nothing mutable with this one (Swift's value semantics). */
    fun deepCopy(): Slime = copy(
        needs = needs.copy(),
        personality = personality.copy(),
        equipped = equipped.copy(),
        emotions = emotions.copy(),
    )

    // MARK: Derived

    val level: Int get() = level(forXP = xp)
    val stage: Stage get() = Stage.forLevel(level)

    val levelProgress: Double
        get() {
            val a = xpNeeded(level)
            val b = xpNeeded(level + 1)
            return (xp - a).toDouble() / (b - a).toDouble()
        }

    fun ageDays(now: Instant = Instant.now(), calendar: SlimyCalendar = SlimyCalendar.current): Int =
        max(0, calendar.daysBetween(birthday, now))

    val mood: Mood
        get() {
            if (isAsleep) return Mood.sleep
            if (needs.lowest.value < 25) return Mood.sad
            return if (emotions.dominant == Emotion.sadness && emotions.intensity >= 0.5) Mood.sad else Mood.idle
        }

    /** The need it is asking for, if anything is getting low. */
    val wish: Need?
        get() {
            val low = needs.lowest
            return if (low.value < 50) low.need else null
        }

    enum class BondStage(val rawValue: Int) {
        new(0), familiar(1), close(2), inseparable(3);

        val title: String
            get() = when (this) {
                new -> "New"
                familiar -> "Familiar"
                close -> "Close"
                inseparable -> "Inseparable"
            }
    }

    val bondStage: BondStage
        get() = when {
            bond < 20 -> BondStage.new
            bond < 50 -> BondStage.familiar
            bond < 80 -> BondStage.close
            else -> BondStage.inseparable
        }

    /**
     * The colours offered at hatching are open from the start; the others
     * unlock with levels or come out of crates. The secret colour only ever
     * comes out of a crate.
     */
    fun isUnlocked(skin: Skin): Boolean {
        if (colours.contains(skin)) return true
        if (skin.isSecret) return false
        return Skin.hatchChoices.contains(skin) || level >= skin.unlockLevel
    }

    fun activeReaction(now: Instant): Reaction =
        if (now.timeIntervalSince(reactionAt) < reactionWindow) reaction else Reaction.none

    /** The phone event it is still reacting to, if it happened within `eventWindow`. */
    fun recentEvent(now: Instant): PhoneEvent? =
        if (now.timeIntervalSince(eventAt) < eventWindow) lastEvent else null

    /** What it's saying, if it said something recently. */
    fun currentLine(now: Instant): String? {
        val say = say ?: return null
        if (now.timeIntervalSince(say.at) >= 20 * 60) return null
        return say.text
    }

    fun react(r: Reaction, now: Instant) {
        reaction = r
        reactionAt = now
    }

    /** Adds to a feeling (negative amounts take away). */
    fun feel(e: Emotion, amount: Double) {
        emotions.feel(e, amount)
    }

    fun sanitize(now: Instant) {
        needs.sanitize()
        xp = max(0, xp)
        bond = min(100.0, max(0.0, if (bond.isFinite()) bond else 0.0))
        if (lastUpdate > now.addingTimeInterval(3600.0)) lastUpdate = now
        if (birthday > now) birthday = now
        recentPets = recentPets.filter { it <= now }.takeLast(12)
    }

    // MARK: Time

    /**
     * Runs the clock forward: needs drain, it falls asleep and wakes up, and
     * its feelings settle toward the baseline its needs and character give.
     * Safe against clocks set backwards, huge gaps and missed background
     * runs: it only ever looks at the saved time.
     */
    fun advance(to: Instant, schedule: SleepSchedule, calendar: SlimyCalendar = SlimyCalendar.current) {
        val now = to
        val elapsed = now.timeIntervalSince(lastUpdate)
        if (elapsed < 0) {
            // Clock moved backwards. Start again from here without decay.
            lastUpdate = now
            return
        }
        if (elapsed < 30) return
        val simulated = min(elapsed, maxSimulated)
        var t = now.addingTimeInterval(-simulated)
        var remaining = simulated
        while (remaining > 0) {
            val step = min(remaining, 15.0 * 60)
            val hours = step / 3600
            for (need in Need.entries) {
                val decay = need.baseDecay(isAsleep) *
                    (if (need == Need.energy && isAsleep) 1.0 else personality.decayFactor(need))
                var value = needs[need] - decay * hours
                if (decay > 0) value = max(value, min(needs[need], restingFloor))
                needs[need] = value
            }
            emotions.settle(
                toward = Emotions.baseline(needs, personality, bond, isAsleep),
                over = step,
            )
            t = t.addingTimeInterval(step)
            remaining -= step

            val hour = calendar.hour(t)
            if (isAsleep) {
                if (!schedule.isNight(hour) && (needs.energy >= 98 || hour == schedule.wake)) {
                    isAsleep = false
                }
            } else if (schedule.auto && schedule.isNight(hour) && (hour == schedule.bedtime || needs.energy < 40)) {
                isAsleep = true
            }
        }
        lastUpdate = now
    }

    companion object {
        fun newborn(named: String, skin: Skin, seed: ULong): Slime {
            val rng = SplitMix(seed)
            val s = Slime()
            s.name = named
            s.skin = skin
            s.personality = Personality.random(rng)
            s.favorite = Food.discoverable[rng.nextIndex(Food.discoverable.size)]
            s.favoriteGame = GameID.classics[rng.nextIndex(GameID.classics.size)]
            return s
        }

        fun level(forXP: Int): Int {
            // 40 xp to level 2, then each level costs 40 more than the last.
            val steps = max(0, forXP).toDouble() / 40
            val root = sqrt(1 + 8 * steps)
            return ((root - 1) / 2).toInt() + 1
        }

        fun xpNeeded(level: Int): Int = 40 * level * (level - 1) / 2

        /** How long a reaction plays in the island before it goes back to the mood (seconds). */
        const val reactionWindow: Double = 8.0

        /** How long a phone event shows in the island (emote, icon) after it happened (seconds). */
        const val eventWindow: Double = 3.0 * 60

        /** Needs never drop below this from time passing alone. */
        const val restingFloor = 12.0

        /** At most this much time is simulated (a longer gap counts as this), in seconds. */
        const val maxSimulated: Double = 21.0 * 24 * 3600
    }

    internal object Coding : TolerantSerializer<Slime>(Slime.generatedSerializer()) {
        override fun decode(fields: JsonFields): Slime {
            val d = Slime()
            return Slime(
                name = fields.string("name", d.name),
                skin = fields.value("skin", Skin.serializer(), d.skin),
                birthday = fields.date("birthday", d.birthday),
                needs = fields.value("needs", Needs.serializer(), d.needs),
                xp = fields.int("xp", d.xp),
                isAsleep = fields.bool("isAsleep", d.isAsleep),
                lastUpdate = fields.date("lastUpdate", d.lastUpdate),
                reaction = fields.value("reaction", Reaction.serializer(), d.reaction),
                reactionAt = fields.date("reactionAt", d.reactionAt),
                favorite = fields.value("favorite", Food.serializer(), d.favorite),
                favoriteGame = fields.value("favoriteGame", GameID.serializer(), d.favoriteGame),
                personality = fields.value("personality", Personality.serializer(), d.personality),
                bond = fields.double("bond", d.bond),
                say = fields.value("say", Utterance.serializer().nullable, d.say),
                lastEvent = fields.value("lastEvent", PhoneEvent.serializer().nullable, d.lastEvent),
                eventAt = fields.date("eventAt", d.eventAt),
                recentPets = fields.value("recentPets", ListSerializer(fields.dateSerializer), d.recentPets),
                equipped = fields.value("equipped", Accessories.serializer(), d.equipped),
                lastNudged = fields.value("lastNudged", Need.serializer().nullable, d.lastNudged),
                lastNudgeAt = fields.date("lastNudgeAt", d.lastNudgeAt),
                emotions = fields.value("emotions", Emotions.serializer(), d.emotions),
                colours = Skin.lossySet(fields.strings("colours")),
            )
        }
    }
}
