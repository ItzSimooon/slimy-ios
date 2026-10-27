package de.hyprnode.slimy.platform

import de.hyprnode.slimy.model.Accessories
import de.hyprnode.slimy.model.EmoteReel
import de.hyprnode.slimy.model.GameState
import de.hyprnode.slimy.model.JsonDate
import de.hyprnode.slimy.model.Mood
import de.hyprnode.slimy.model.Need
import de.hyprnode.slimy.model.PhoneEvent
import de.hyprnode.slimy.model.Reaction
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.model.Stage
import de.hyprnode.slimy.model.line
import de.hyprnode.slimy.model.rounded
import kotlinx.serialization.Serializable
import java.time.Instant

// The data half of Shared/Activity/SlimeActivity.swift: what the slime
// notification (the Live Activity counterpart) shows.

/** A friend's slime as the slime notification shows it. */
@Serializable
data class FriendSlime(
    val id: String,
    val name: String,
    val owner: String,
    val skin: Skin,
    val stage: Stage,
    val mood: Mood,
    val equipped: Accessories? = null,
)

/** Something you and a friend are doing together right now. Unknown raw values read as `hug`. */
@Serializable
enum class SharedMoment {
    reunion, missing, hug, play, gift, achievement, sleep, meetup;

    val line: String
        get() = when (this) {
            reunion -> "together again!"
            missing -> "misses you"
            hug -> "hug!"
            play -> "let's play"
            gift -> "sent a gift"
            achievement -> "did it, together"
            sleep -> "good night, you two"
            meetup -> "came to say hi"
        }

    /** The slimes' animation during the moment. */
    val anim: SpriteAnim
        get() = when (this) {
            reunion, achievement -> SpriteAnim.celebrate
            missing -> SpriteAnim.sad
            hug -> SpriteAnim.love
            play -> SpriteAnim.play
            gift -> SpriteAnim.blush
            sleep -> SpriteAnim.sleep
            meetup -> SpriteAnim.meet
        }

    val reel: EmoteReel
        get() = when (this) {
            hug, missing, meetup -> EmoteReel.love
            play -> EmoteReel.`fun`
            sleep -> EmoteReel.sleepy
            reunion, gift, achievement -> EmoteReel.happyB
        }

    /** Seconds the notification stays in this moment before going back to solo. */
    val duration: Double
        get() = when (this) {
            sleep -> 30.0 * 60
            meetup -> 3.0 * 60
            else -> 10.0 * 60
        }

    companion object {
        val allCases: List<SharedMoment> get() = entries
    }
}

/**
 * What the slime notification shows: ActivityKit's
 * `SlimeActivityAttributes.ContentState`. Built from the game with
 * [ContentState.of].
 */
class SlimeActivityAttributes {
    @Serializable
    data class ContentState(
        val name: String,
        val skin: Skin,
        val stage: Stage? = null,
        val mood: Mood,
        val reaction: Reaction,
        /** Start of the animation (the reaction's start, or when the state was made). */
        val anchor: JsonDate,
        val hunger: Int,
        val `fun`: Int,
        val energy: Int,
        val clean: Int,
        val love: Int,
        val wish: Need? = null,
        val level: Int,
        val coins: Int,
        val line: String,
        /** The everyday line while a moment shows its own. */
        val baseLine: String? = null,
        val event: PhoneEvent? = null,
        val equipped: Accessories? = null,
        /** Up to three friends, most important first. */
        val friends: List<FriendSlime>? = null,
        val moment: SharedMoment? = null,
        val momentFriend: String? = null,
        val animated: Boolean? = null,
    ) {
        fun value(need: Need): Int = when (need) {
            Need.hunger -> hunger
            Need.`fun` -> `fun`
            Need.energy -> energy
            Need.clean -> clean
            Need.love -> love
        }

        val slimeStage: Stage get() = stage ?: Stage.young
        val accessories: Accessories get() = equipped ?: Accessories()
        val isAnimated: Boolean get() = animated ?: true

        /** What the slime plays right now. */
        val anim: SpriteAnim
            get() {
                moment?.let { return it.anim }
                return SpriteAnim.of(reaction) ?: SpriteAnim.of(mood)
            }

        /** The friend sharing the moment, while there is one. */
        val partner: FriendSlime?
            get() {
                if (moment == null) return null
                val id = momentFriend ?: return null
                return friends?.firstOrNull { it.id == id }
            }

        /**
         * The everyday state without the shared moment or the phone event:
         * what the notification goes back to once their time is up.
         */
        val solo: ContentState
            get() {
                val s = copy(event = null)
                if (moment == null) return s
                return s.copy(moment = null, momentFriend = null, line = baseLine ?: line)
            }

        /**
         * Under the name in the notification: who the slime is with, or its
         * level with its coins or friends, and the phone event it reacts to.
         */
        val detail: String
            get() {
                val parts = mutableListOf<String>()
                val partner = partner
                if (partner != null) {
                    parts += "with ${partner.name}"
                } else {
                    parts += "Level $level"
                    val count = friends.orEmpty().size
                    parts += when (count) {
                        0 -> "$coins coins"
                        1 -> "1 friend"
                        else -> "$count friends"
                    }
                }
                event?.let { parts += it.title }
                return parts.joinToString(" · ")
            }

        /** The action on a need's button: Feed, Play, Sleep (Wake while asleep), Bath, Pet. */
        fun actionTitle(need: Need): String = when (need) {
            Need.hunger -> "Feed"
            Need.`fun` -> "Play"
            Need.energy -> if (mood == Mood.sleep) "Wake" else "Sleep"
            Need.clean -> "Bath"
            Need.love -> "Pet"
        }

        /** What the slime is emoting next to it. */
        val emote: EmoteReel
            get() {
                moment?.let { return it.reel }
                event?.let { e -> EmoteReel.of(e)?.let { return it } }
                if (mood == Mood.sleep) return EmoteReel.sleepy
                when (reaction) {
                    Reaction.love, Reaction.blush -> return EmoteReel.love
                    Reaction.bath -> return EmoteReel.clean
                    Reaction.sulk, Reaction.confused -> return EmoteReel.confused
                    else -> {}
                }
                wish?.let { return EmoteReel.of(it) }
                return EmoteReel.happy(anchor)
            }

        companion object {
            /** The notification's state for [game] at [now], with [friends] beside the slime and an optional moment. */
            fun of(
                game: GameState,
                now: Instant,
                friends: List<FriendSlime> = emptyList(),
                moment: SharedMoment? = null,
                momentFriend: String? = null,
                animated: Boolean = true,
            ): ContentState {
                val s = game.slime
                val reaction = s.activeReaction(now)
                var momentLine: String? = null
                if (moment != null) {
                    val friend = momentFriend?.let { id -> friends.firstOrNull { it.id == id } }
                    momentLine = if (friend != null) "${friend.name}: ${moment.line}" else moment.line
                }
                // Up to three friends. The one sharing a moment is always among
                // them, so the notification can show who the slime is with.
                var shown = friends.take(3)
                if (momentFriend != null && shown.none { it.id == momentFriend }) {
                    friends.firstOrNull { it.id == momentFriend }?.let { shown = listOf(it) + shown.take(2) }
                }
                val everyday = game.line(now)
                return ContentState(
                    name = s.name,
                    skin = s.skin,
                    stage = s.stage,
                    mood = s.mood,
                    reaction = reaction,
                    anchor = if (reaction == Reaction.none) now else s.reactionAt,
                    hunger = s.needs.hunger.rounded().toInt(),
                    `fun` = s.needs.`fun`.rounded().toInt(),
                    energy = s.needs.energy.rounded().toInt(),
                    clean = s.needs.clean.rounded().toInt(),
                    love = s.needs.love.rounded().toInt(),
                    wish = s.wish,
                    level = s.level,
                    coins = game.wallet.coins,
                    line = momentLine ?: everyday,
                    baseLine = if (momentLine == null) null else everyday,
                    event = s.recentEvent(now),
                    equipped = s.equipped.copy(),
                    friends = shown.ifEmpty { null },
                    moment = moment,
                    momentFriend = momentFriend,
                    animated = animated,
                )
            }
        }
    }
}

/** The notification's state, as the Swift code calls it. */
typealias IslandState = SlimeActivityAttributes.ContentState
