package de.hyprnode.slimy.model

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** The arcade. Every game has its own rules in the arcade engines. */
@Serializable
enum class GameID {
    snackCatcher, slimeJump, bubblePop, memoryGarden, slimeRacing,
    rhythmBounce, gardenPuzzle, treasureDig, basketball, colorMatch,
    slimeStack, jellyDash,

    /** The day's game, built from blocks (DailyPlan). */
    daily;

    val id: String get() = name

    val title: String
        get() = when (this) {
            snackCatcher -> "Snack Catcher"
            slimeJump -> "Slime Jump"
            bubblePop -> "Bubble Pop"
            memoryGarden -> "Memory Garden"
            slimeRacing -> "Slime Racing"
            rhythmBounce -> "Rhythm Bounce"
            gardenPuzzle -> "Garden Puzzle"
            treasureDig -> "Treasure Dig"
            basketball -> "Slime Basketball"
            colorMatch -> "Color Match"
            slimeStack -> "Slime Stack"
            jellyDash -> "Jelly Dash"
            daily -> "Today's game"
        }

    val blurb: String
        get() = when (this) {
            snackCatcher -> "Catch food, dodge the rotten stuff, build combos."
            slimeJump -> "Bounce from platform to platform, as high as you can."
            bubblePop -> "Pop groups of matching bubbles. Big groups score more."
            memoryGarden -> "Remember which flowers light up and tap them back."
            slimeRacing -> "Dodge rocks and beat the ghost to the finish."
            rhythmBounce -> "Tap right when the note arrives."
            gardenPuzzle -> "Push stones, collect seeds, reach the flower bed."
            treasureDig -> "Dig for treasure with few shovels. The numbers help."
            basketball -> "Swipe to throw. The hoop moves."
            colorMatch -> "Does the color or the shape match? Decide fast."
            slimeStack -> "Drop jelly blocks and build the tallest tower."
            jellyDash -> "Run, jump and grab berries. Hold to jump higher."
            daily -> "A new game every day."
        }

    /** Short rules for the first-play introduction. */
    val tutorial: List<String>
        get() = when (this) {
            snackCatcher -> listOf("Drag left and right to catch.", "Rotten food and cacti cost points.", "Several catches in a row give a combo bonus.")
            slimeJump -> listOf("Hold the left or right side to steer.", "Land on a platform and you bounce again.", "Coins give extra points. Don't fall.")
            bubblePop -> listOf("Tap a group of at least two matching bubbles.", "The bigger the group, the more points.", "The round ends when no moves are left.")
            memoryGarden -> listOf("Watch which flowers light up, one after another.", "Tap them in the same order.", "Every round adds one more flower.")
            slimeRacing -> listOf("Swipe or tap left and right to change lanes.", "Rocks slow you down, puddles speed you up.", "Finish before the ghost.")
            rhythmBounce -> listOf("Notes fall toward the line.", "Tap the lane exactly when a note hits the line.", "Perfect scores more than good.")
            gardenPuzzle -> listOf("Swipe or use the arrows to move.", "Stones can be pushed, hedges can't.", "Collect every seed, then step onto the bed.")
            treasureDig -> listOf("Tap a square to dig.", "Numbers show how far the nearest treasure is.", "You only have a few shovels.")
            basketball -> listOf("Swipe up from the ball toward the hoop.", "A longer swipe throws farther.", "Hits in a row count double.")
            colorMatch -> listOf("The rule at the top says color or shape.", "Tap Yes if the piece matches the sample, otherwise No.", "Be quick, the clock is running.")
            slimeStack -> listOf("Tap to drop the sliding block onto the tower.", "Whatever hangs over the edge is cut off.", "Perfect drops in a row score more and widen the tower again.")
            jellyDash -> listOf("Tap to jump. Hold for a higher, longer jump.", "Puddles and rocks cost a heart. Tall rocks need a held jump.", "Coins and berries give extra points. It keeps getting faster.")
            daily -> listOf("Every day brings a different game.")
        }

    /** Icon sprite from SlimeSprites.items. */
    val icon: String
        get() = when (this) {
            snackCatcher -> "apple"
            slimeJump -> "coin"
            bubblePop -> "bubbleItem"
            memoryGarden -> "flower"
            slimeRacing -> "flag"
            rhythmBounce -> "noteItem"
            gardenPuzzle -> "seed"
            treasureDig -> "chest"
            basketball -> "ball"
            colorMatch -> "gem"
            slimeStack -> "cake"
            jellyDash -> "berry"
            daily -> "starJelly"
        }

    val energyCost: Double get() = 8.0

    /** Score that counts as a good round, used for rewards and challenges. */
    val goodScore: Int
        get() = when (this) {
            snackCatcher -> 40
            slimeJump -> 30
            bubblePop -> 400
            memoryGarden -> 8
            slimeRacing -> 60
            rhythmBounce -> 300
            gardenPuzzle -> 3
            treasureDig -> 60
            basketball -> 12
            colorMatch -> 25
            slimeStack -> 30
            jellyDash -> 150
            daily -> 100
        }

    /** Unlocked in the arcade from this slime level on. */
    val unlockLevel: Int
        get() = when (this) {
            snackCatcher, bubblePop, memoryGarden -> 1
            slimeJump, colorMatch, slimeStack -> 2
            basketball, treasureDig -> 3
            rhythmBounce, jellyDash -> 4
            slimeRacing -> 5
            gardenPuzzle -> 6
            daily -> 1
        }

    companion object {
        val allCases: List<GameID> get() = entries

        /** The fixed games, without the daily one, in arcade order. */
        val classics: List<GameID> get() = entries.filter { it != daily }
    }
}

/**
 * What a finished round produces. Rewards are worked out here, not by the
 * game views, so they are the same everywhere and can be tested.
 */
@Serializable
data class GameResult(
    val game: GameID,
    val score: Int,
    /** Unique per round; the reward for a round can only be claimed once. */
    @Serializable(with = UuidSerializer::class) val session: UUID,
    val finishedAt: JsonDate,
    /** For the daily game: the good score of that day's game and its day. */
    val target: Int? = null,
    val day: String? = null,
) {
    /** A good round's score for this game. */
    val goodScore: Int get() = max(1, target ?: game.goodScore)

    val coins: Int
        get() {
            val ratio = max(0, score).toDouble() / goodScore.toDouble()
            return min(30, 2 + (ratio * 12).rounded().toInt())
        }

    val xp: Int
        get() {
            val ratio = max(0, score).toDouble() / goodScore.toDouble()
            return min(30, 6 + (ratio * 14).rounded().toInt())
        }

    val funGain: Double get() = 14 + min(16.0, score.toDouble() / goodScore.toDouble() * 10)
}

@Serializable
data class GameRecord(
    var best: Int = 0,
    var plays: Int = 0,
    var lastPlayed: JsonDate? = null,
    var tutorialSeen: Boolean = false,
)
