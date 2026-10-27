package de.hyprnode.slimy.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** A feeling the slime can have. It has all of them at once, each with its own intensity; the strongest one leads. */
@Serializable
enum class Emotion {
    joy, sadness, anger, fear, love, curiosity, boredom, excitement, sleepiness;

    val id: String get() = name

    val title: String
        get() = when (this) {
            joy -> "Joy"
            sadness -> "Sadness"
            anger -> "Anger"
            fear -> "Fear"
            love -> "Love"
            curiosity -> "Curiosity"
            boredom -> "Boredom"
            excitement -> "Excitement"
            sleepiness -> "Sleepiness"
        }

    /** How the slime is while this feeling leads: "happy", "grumpy". */
    val adjective: String
        get() = when (this) {
            joy -> "happy"
            sadness -> "sad"
            anger -> "grumpy"
            fear -> "scared"
            love -> "loving"
            curiosity -> "curious"
            boredom -> "bored"
            excitement -> "excited"
            sleepiness -> "sleepy"
        }

    /** SF Symbol for the feeling (screens map it to a Material icon). */
    val symbol: String
        get() = when (this) {
            joy -> "sun.max.fill"
            sadness -> "cloud.drizzle.fill"
            anger -> "flame.fill"
            fear -> "exclamationmark.circle.fill"
            love -> "heart.fill"
            curiosity -> "magnifyingglass"
            boredom -> "hourglass"
            excitement -> "sparkles"
            sleepiness -> "moon.zzz.fill"
        }

    /** Feelings that fade a little when this one rises. */
    val opposites: List<Emotion>
        get() = when (this) {
            joy -> listOf(sadness, anger, boredom)
            sadness -> listOf(joy, excitement)
            anger -> listOf(love, joy)
            fear -> listOf(joy, curiosity)
            love -> listOf(anger, sadness, fear)
            curiosity -> listOf(boredom)
            boredom -> listOf(excitement, curiosity)
            excitement -> listOf(boredom, sleepiness, sadness)
            sleepiness -> listOf(excitement)
        }

    companion object {
        val allCases: List<Emotion> get() = entries
    }
}

/**
 * How the slime feels right now: nine feelings, each 0...1. Events add to
 * them, and over time they settle toward a baseline that comes from its
 * needs and personality (half of the way every 30 minutes).
 */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = Emotions.Coding::class)
data class Emotions(
    private var joy: Double = 0.3,
    private var sadness: Double = 0.0,
    private var anger: Double = 0.0,
    private var fear: Double = 0.0,
    private var love: Double = 0.0,
    private var curiosity: Double = 0.0,
    private var boredom: Double = 0.0,
    private var excitement: Double = 0.0,
    private var sleepiness: Double = 0.0,
) {
    operator fun get(e: Emotion): Double = when (e) {
        Emotion.joy -> joy
        Emotion.sadness -> sadness
        Emotion.anger -> anger
        Emotion.fear -> fear
        Emotion.love -> love
        Emotion.curiosity -> curiosity
        Emotion.boredom -> boredom
        Emotion.excitement -> excitement
        Emotion.sleepiness -> sleepiness
    }

    operator fun set(e: Emotion, newValue: Double) {
        val v = clamp(newValue)
        when (e) {
            Emotion.joy -> joy = v
            Emotion.sadness -> sadness = v
            Emotion.anger -> anger = v
            Emotion.fear -> fear = v
            Emotion.love -> love = v
            Emotion.curiosity -> curiosity = v
            Emotion.boredom -> boredom = v
            Emotion.excitement -> excitement = v
            Emotion.sleepiness -> sleepiness = v
        }
    }

    /** The strongest feeling. On a tie, the one listed first wins. */
    val dominant: Emotion
        get() {
            var best = Emotion.joy
            for (e in Emotion.entries) if (this[e] > this[best]) best = e
            return best
        }

    /** How strong the leading feeling is. */
    val intensity: Double get() = this[dominant]

    /** Adds to a feeling (or takes away, with a negative amount). A feeling that rises softens its opposites a little. */
    fun feel(e: Emotion, amount: Double) {
        if (!amount.isFinite() || amount == 0.0) return
        this[e] = this[e] + amount
        if (amount <= 0) return
        for (other in e.opposites) this[other] = this[other] - amount * damping
    }

    /** Feelings for the chat API: only those that are there at all (0.05 or more), rounded to two decimals. */
    val wire: Map<String, Double>
        get() {
            val out = LinkedHashMap<String, Double>()
            for (e in Emotion.entries) if (this[e] >= 0.05) out[e.name] = (this[e] * 100).rounded() / 100
            return out
        }

    /** The strongest feelings at or above [threshold], strongest first. */
    fun strongest(count: Int = 3, atLeast: Double = 0.2): List<Emotion> =
        Emotion.entries.filter { this[it] >= atLeast }
            .sortedByDescending { this[it] }
            .take(max(0, count))

    /** A mix of two moods: [weight] 0 keeps this one, 1 takes [other]. */
    fun blended(with: Emotions, weight: Double): Emotions {
        val w = clamp(weight)
        val out = copy()
        for (e in Emotion.entries) out[e] = this[e] * (1 - w) + with[e] * w
        return out
    }

    /** Lets time pass: every feeling moves toward [toward], half of the way per [halfLife]. */
    fun settle(toward: Emotions, over: Double, halfLife: Double = Emotions.halfLife) {
        if (!over.isFinite() || over <= 0 || halfLife <= 0) return
        val pull = 1 - 0.5.pow(over / halfLife)
        for (e in Emotion.entries) this[e] = this[e] + (toward[e] - this[e]) * pull
    }

    companion object {
        /** How much of a rising feeling is taken from its opposites. */
        const val damping = 0.3

        /** Feelings fade halfway back to the baseline in this time (seconds). */
        const val halfLife: Double = 30.0 * 60

        fun clamp(value: Double): Double = if (value.isFinite()) min(1.0, max(0.0, value)) else 0.0

        /**
         * Where the feelings drift when nothing happens: low needs bring
         * sadness, boredom, sleepiness and a little grumpiness; a
         * well-cared-for slime is quietly happy; personality and closeness
         * colour the rest.
         */
        fun baseline(needs: Needs, personality: Personality, bond: Double, asleep: Boolean): Emotions {
            fun low(value: Double, under: Double): Double = max(0.0, min(1.0, (under - value) / under))
            val wellbeing = max(0.0, min(1.0, (needs.average - 30) / 60))
            val closeness = max(0.0, min(1.0, (if (bond.isFinite()) bond else 0.0) / 100))
            val b = Emotions()
            b[Emotion.joy] = 0.08 + 0.32 * wellbeing
            b[Emotion.sadness] = max(0.55 * low(needs.love, 45.0), 0.4 * low(needs.lowest.value, 30.0))
            b[Emotion.anger] = 0.4 * low(needs.hunger, 35.0) * (0.6 + 0.8 * personality.foodie) +
                0.2 * low(needs.clean, 15.0)
            b[Emotion.fear] = 0.0
            b[Emotion.love] = 0.05 + 0.2 * closeness + 0.1 * personality.cuddly * (if (needs.love >= 50) 1.0 else 0.4)
            b[Emotion.curiosity] = 0.05 + 0.2 * personality.curious
            b[Emotion.boredom] = 0.65 * low(needs.`fun`, 50.0) * (0.7 + 0.6 * personality.playful)
            b[Emotion.excitement] = 0.03 + 0.1 * personality.playful
            b[Emotion.sleepiness] = 0.7 * low(needs.energy, 50.0) * (0.7 + 0.6 * personality.sleepy)
            if (asleep) {
                b[Emotion.sleepiness] = max(b[Emotion.sleepiness], 0.6)
                b[Emotion.excitement] = 0.0
                b[Emotion.boredom] = 0.0
                b[Emotion.anger] = 0.0
                b[Emotion.curiosity] = 0.02
            }
            return b
        }
    }

    /** Missing or damaged values fall back to calm, so a save from before feelings existed still loads. */
    internal object Coding : TolerantSerializer<Emotions>(Emotions.generatedSerializer()) {
        override fun decode(fields: JsonFields): Emotions {
            val d = Emotions()
            val out = Emotions()
            for (e in Emotion.entries) out[e] = clamp(fields.double(e.name, d[e]))
            return out
        }
    }
}

/** Some slimes don't care for baths: a little grumpy for a while, though they like being clean. */
val Personality.dislikesBaths: Boolean
    get() = curious < 0.3 || (sleepy > 0.7 && playful < 0.4)
