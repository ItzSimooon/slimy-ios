package de.hyprnode.slimy.model

import kotlinx.serialization.Serializable

@Serializable
enum class Need {
    hunger, `fun`, energy, clean, love;

    val id: String get() = name

    val title: String
        get() = when (this) {
            hunger -> "Food"
            `fun` -> "Fun"
            energy -> "Energy"
            clean -> "Clean"
            love -> "Love"
        }

    /** The SF Symbol the iPhone app shows; screens map it to a Material icon. */
    val symbol: String
        get() = when (this) {
            hunger -> "fork.knife"
            `fun` -> "gamecontroller.fill"
            energy -> "bolt.fill"
            clean -> "bubbles.and.sparkles.fill"
            love -> "heart.fill"
        }

    /** Base points lost per hour, before personality. */
    fun baseDecay(asleep: Boolean): Double = when (this) {
        hunger -> if (asleep) 2.5 else 6.0
        `fun` -> if (asleep) 1.0 else 5.0
        energy -> if (asleep) -15.0 else 4.0
        clean -> if (asleep) 1.0 else 2.5
        love -> if (asleep) 1.5 else 4.0
    }

    /** What it means when this need is low. Shown in the needs panel. */
    val lowHint: String
        get() = when (this) {
            hunger -> "Hungry. Something to eat would help."
            `fun` -> "Bored. A game would help."
            energy -> "Tired. Time for bed."
            clean -> "Sticky. Time for a bath."
            love -> "Wants some cuddles."
        }

    companion object {
        val allCases: List<Need> get() = entries
    }
}

/** A need and its value, like Swift's `(need: Need, value: Double)`. */
data class NeedValue(val need: Need, val value: Double)

@Serializable
data class Needs(
    var hunger: Double = 80.0,
    var `fun`: Double = 70.0,
    var energy: Double = 90.0,
    var clean: Double = 85.0,
    var love: Double = 75.0,
) {
    operator fun get(need: Need): Double = when (need) {
        Need.hunger -> hunger
        Need.`fun` -> `fun`
        Need.energy -> energy
        Need.clean -> clean
        Need.love -> love
    }

    /** Clamped to 0...100; NaN and infinity become 50. */
    operator fun set(need: Need, newValue: Double) {
        val v = if (newValue.isFinite()) newValue.coerceIn(0.0, 100.0) else 50.0
        when (need) {
            Need.hunger -> hunger = v
            Need.`fun` -> `fun` = v
            Need.energy -> energy = v
            Need.clean -> clean = v
            Need.love -> love = v
        }
    }

    fun add(need: Need, amount: Double) {
        this[need] = this[need] + amount
    }

    val lowest: NeedValue
        get() {
            var best = NeedValue(Need.hunger, hunger)
            for (need in Need.entries) if (this[need] < best.value) best = NeedValue(need, this[need])
            return best
        }

    val average: Double get() = Need.entries.sumOf { this[it] } / Need.entries.size

    /** Repairs values from a damaged save. */
    fun sanitize() {
        for (need in Need.entries) this[need] = this[need]
    }
}
