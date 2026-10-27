package de.hyprnode.slimy.platform

import de.hyprnode.slimy.model.Accessories
import de.hyprnode.slimy.model.EmoteReel
import de.hyprnode.slimy.model.Mood
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.model.Stage
import kotlinx.serialization.Serializable

// The data half of Shared/Activity/SlimeActivity.swift: what the slime
// notification (the Live Activity counterpart) shows besides your own slime.
// The notification's content state is the platform worker's to add here.

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
