package de.hyprnode.slimy.arcade

import de.hyprnode.slimy.model.GameID
import de.hyprnode.slimy.model.SeededRandom
import de.hyprnode.slimy.model.SplitMix
import de.hyprnode.slimy.model.arcade.RhythmBounceEngine
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// The words and small numbers the arcade screens show, kept apart from the
// drawing so they can be tested on the JVM. Same copy as the iPhone app.

object ArcadeRules {
    /** GameState.canPlay needs this much energy before a round. */
    const val minimumEnergy = 12.0

    /** Rounds the slime's energy is good for (each round costs `GameID.energyCost`). */
    fun rounds(energy: Double): Int {
        if (energy < minimumEnergy) return 0
        return ((energy - minimumEnergy) / GameID.daily.energyCost).toInt() + 1
    }

    /** The line under the energy meter in the arcade. */
    fun energyNote(energy: Double, name: String, canPlay: Boolean): String {
        if (!canPlay) return "$name is too tired to play. A nap or some star jelly helps."
        val rounds = rounds(energy)
        return if (rounds <= 1) "Enough for one more round." else "Enough for about $rounds rounds."
    }

    /** The headline of the results card. */
    fun headline(record: Boolean, paid: Boolean, score: Int, goodScore: Int): String {
        if (record) return "New record!"
        if (!paid) return "Round over"
        return if (score >= goodScore) "Great round" else "Round over"
    }

    /** "1 point so far" in the pause card. */
    fun pointsSoFar(score: Int): String = if (score == 1) "1 point so far" else "$score points so far"

    /** What ending the round early means. */
    fun quitWarning(score: Int): String =
        if (score > 0) "The round ends with $score points and counts."
        else "No points means no reward, but it costs no energy either."

    /** The line under the score when the round paid nothing. */
    const val noPointsLine = "No points this time. It didn't cost any energy."

    /** How a challenge round went. */
    fun challengeOutcome(friendName: String, mine: Int, theirs: Int): String =
        if (mine > theirs) "You beat $friendName: $mine to $theirs."
        else "$friendName stays ahead: $theirs to $mine."

    /** The top bar's second line. */
    fun hostSubtitle(
        goodScore: Int,
        isDaily: Boolean,
        challengeFriend: String?,
        challengeScore: Int,
        best: Int,
    ): String {
        if (isDaily) return "Good from $goodScore points"
        if (challengeFriend != null) return "Beat $challengeFriend: $challengeScore"
        return if (best > 0) "Best $best" else "Good from $goodScore points"
    }

    /**
     * When something ends, like Swift's relative date with named
     * presentation: "in 5 minutes", "tomorrow", "in 3 days".
     */
    fun relative(date: Instant, now: Instant): String {
        val seconds = Duration.between(now, date).seconds
        if (seconds < 0) {
            val ago = -seconds
            return when {
                ago < 60 -> "now"
                ago < 3600 -> plural(ago / 60, "minute") + " ago"
                ago < 86_400 -> plural(ago / 3600, "hour") + " ago"
                ago < 2 * 86_400 -> "yesterday"
                else -> plural(ago / 86_400, "day") + " ago"
            }
        }
        return when {
            seconds < 60 -> "now"
            seconds < 3600 -> "in " + plural(seconds / 60, "minute")
            seconds < 86_400 -> "in " + plural(seconds / 3600, "hour")
            seconds < 2 * 86_400 -> "tomorrow"
            else -> "in " + plural(seconds / 86_400, "day")
        }
    }

    private fun plural(n: Long, unit: String): String = if (n == 1L) "1 $unit" else "$n ${unit}s"

    /** Slime Racing's lead over the ghost: "+1.2 s" or "-0.4 s". */
    fun racingGap(seconds: Double): String =
        if (seconds >= 0) String.format(Locale.US, "+%.1f s", seconds) else String.format(Locale.US, "%.1f s", seconds)

    /** Slime Stack's status: "1 block", "12 blocks". */
    fun blocks(height: Int): String = if (height == 1) "1 block" else "$height blocks"

    /** The lane under a tap in Rhythm Bounce (board x). */
    fun rhythmLane(boardX: Double): Int = max(0, min(RhythmBounceEngine.lanes - 1, ((boardX - 5) / 30).toInt()))

    /** The day's game multiplier as the HUD writes it: "x1.4". */
    fun multiplier(value: Double): String = String.format(Locale.US, "x%.1f", value)

    // MARK: Bath

    /** How many mud spots a slime with this cleanliness gets; null when it is clean enough already. */
    fun mudSpots(clean: Double): Int? {
        if (clean > 95) return null
        return max(3, ((100 - clean) / 11).toInt())
    }

    fun bathHeadline(alreadyClean: Boolean, finished: Boolean, spotsLeft: Int): String {
        if (alreadyClean) return "Already clean"
        if (finished) return "Sparkling clean"
        return if (spotsLeft == 1) "1 mud spot left" else "$spotsLeft mud spots left"
    }

    fun bathHint(alreadyClean: Boolean, finished: Boolean): String {
        if (alreadyClean) return "Come back when it gets muddy."
        if (finished) return "All the mud is gone."
        return "Rub the mud off with your finger."
    }

    /**
     * The corners of a lumpy mud blob, as unit offsets from its centre
     * (multiply by the blob's width and height). Different for every seed,
     * the same every time for one seed.
     */
    fun mudBlob(seed: Int): List<Pair<Double, Double>> {
        val rng = SeededRandom((seed + 11).toULong())
        val n = 9
        return (0..n).map { i ->
            val a = i.toDouble() / n.toDouble() * PI * 2
            val r = 0.36 + rng.next() * 0.14
            Pair(cos(a) * r, sin(a) * r)
        }
    }

    // MARK: Fixed scatter

    /** `count` points in 0..<1 from a fixed seed, for stars, clouds and decoration. */
    fun scatter(seed: ULong, count: Int): List<Pair<Double, Double>> {
        val rng = SplitMix(seed)
        return List(count) { Pair(rng.unit(), rng.unit()) }
    }

    /** `count` values in 0..<1 from a fixed seed. */
    fun values(seed: ULong, count: Int): List<Double> {
        val rng = SplitMix(seed)
        return List(count) { rng.unit() }
    }

    /** Remainder that is never negative, for wrapping scenery around. */
    fun positiveRemainder(value: Double, divisor: Double): Double {
        if (divisor <= 0) return 0.0
        val r = value % divisor
        return if (r < 0) r + divisor else r
    }

    /**
     * The size of one art pixel when a `columns` × `rows` sprite is fitted
     * into `width` × `height`: whole device pixels, unless the box is
     * smaller than the art itself.
     */
    fun pixelSize(columns: Int, rows: Int, width: Double, height: Double): Double {
        if (columns <= 0 || rows <= 0 || width <= 0 || height <= 0) return 0.0
        val fit = min(width / columns, height / rows)
        return if (fit >= 1) floor(fit) else fit
    }
}
