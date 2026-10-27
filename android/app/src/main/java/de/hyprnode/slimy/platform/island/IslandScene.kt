package de.hyprnode.slimy.platform.island

import de.hyprnode.slimy.model.CrateKind
import de.hyprnode.slimy.model.EmoteReel
import de.hyprnode.slimy.model.GameState
import de.hyprnode.slimy.model.MemoryEntry
import de.hyprnode.slimy.model.Mood
import de.hyprnode.slimy.model.PartnerCareNote
import de.hyprnode.slimy.model.Slime
import de.hyprnode.slimy.model.timeIntervalSince
import de.hyprnode.slimy.model.trimmedSwift
import de.hyprnode.slimy.platform.FriendSlime
import de.hyprnode.slimy.platform.IslandState
import de.hyprnode.slimy.platform.SharedMoment
import java.time.Instant

/**
 * Something that just happened and the island shows for a few seconds, like
 * a reaction: a crate turned up, or your Care Together partner looked after
 * the slime.
 */
sealed class IslandNews {
    abstract val at: Instant

    data class Crate(val kind: CrateKind, override val at: Instant) : IslandNews()

    /** "Lina fed Mochi". */
    data class PartnerCare(val text: String, override val at: Instant) : IslandNews()

    /** Still worth showing at [now]: within the slime's reaction window, like iOS. */
    fun isFresh(now: Instant): Boolean {
        val age = now.timeIntervalSince(at)
        return age >= -1 && age < Slime.reactionWindow
    }
}

/**
 * Notices news between two states of the game: more crates than before, or
 * a new care action from the partner. The island feeds it every state it
 * sees; the first one only sets the baseline.
 */
class IslandNewsTracker {
    private var crates: Map<CrateKind, Int>? = null
    private var lastPartnerCare: String? = null
    private var started = false

    /** The latest news, fresh or not. */
    var news: IslandNews? = null
        private set

    fun update(game: GameState, now: Instant): IslandNews? {
        val before = crates
        val counts = game.crates
        if (started && before != null) {
            val grown = CrateKind.entries.lastOrNull { (counts[it] ?: 0) > (before[it] ?: 0) }
            if (grown != null) news = IslandNews.Crate(grown, now)
        }
        crates = counts

        val seen = if (game.care != null) game.careBook.seen.lastOrNull() else null
        if (started && seen != null && seen != lastPartnerCare) {
            val entry = game.memory.last(MemoryEntry.Kind.partnerCare)
            val text = entry?.let { PartnerCareNote.parse(it.detail)?.summary }
            if (text != null) news = IslandNews.PartnerCare(text, now)
        }
        lastPartnerCare = seen
        started = true
        return news?.takeIf { it.isFresh(now) }
    }
}

/** What the right side of the compact island shows. */
sealed class IslandBadge {
    /** The slime's emote reel, like the Dynamic Island's compact trailing view. */
    data class Emote(val reel: EmoteReel) : IslandBadge()

    /** The friend sharing a moment, facing your slime. */
    data class Friend(val friend: FriendSlime) : IslandBadge()

    /** A pixel item that just arrived (a crate). */
    data class Item(val sprite: String) : IslandBadge()
}

/**
 * Everything the island draws: the notification's content (the port of the
 * Live Activity's state) plus what only the island shows, Care Together and
 * fresh news. Built with [of]; pure, so it is tested on the JVM.
 */
data class IslandScene(
    val state: IslandState,
    /** Your Care Together partner's name, while you care for the slime together. */
    val careWith: String? = null,
    /** News within its reaction window, else null. */
    val news: IslandNews? = null,
    /** Unopened crates. */
    val cratesWaiting: Int = 0,
) {
    val name: String get() = state.name
    val isAsleep: Boolean get() = state.mood == Mood.sleep

    /** The friend sharing a moment, if any. */
    val partner: FriendSlime? get() = state.partner

    /** Right of the camera: the friend in a moment, a crate that just came, else the emote. */
    val badge: IslandBadge
        get() {
            state.partner?.let { return IslandBadge.Friend(it) }
            return when (val n = news) {
                is IslandNews.Crate -> IslandBadge.Item(n.kind.sprite)
                is IslandNews.PartnerCare -> IslandBadge.Emote(EmoteReel.love)
                null -> IslandBadge.Emote(state.emote)
            }
        }

    /** What the slime says, in its own pixel voice ("hungry. very hungry."). */
    val line: String get() = state.line

    /**
     * The small line under the slime's words: the news, who it is with, your
     * Care Together partner, or its friends. Null when there's nothing to add.
     */
    val caption: String?
        get() {
            when (val n = news) {
                is IslandNews.Crate -> return "A ${n.kind.title.lowercase()} arrived"
                is IslandNews.PartnerCare -> return n.text
                null -> {}
            }
            val partner = state.partner
            val moment = state.moment
            if (partner != null && moment != null) return "With ${partner.owner}'s slime"
            careWith?.let { return "Caring together with $it" }
            state.event?.let { return it.title }
            val friends = state.friends.orEmpty().size
            if (friends > 0) return if (friends == 1) "1 friend nearby" else "$friends friends nearby"
            if (cratesWaiting > 0) return if (cratesWaiting == 1) "1 crate to open" else "$cratesWaiting crates to open"
            return null
        }

    /** Next to the name in the expanded island. */
    val levelText: String get() = "Level ${state.level}"

    /** What TalkBack reads for the compact island. */
    val spoken: String get() = "${state.name}: ${state.line}"

    companion object {
        /** The island's scene for [game] at [now], with an optional shared moment and fresh [news]. */
        fun of(
            game: GameState,
            now: Instant,
            friends: List<FriendSlime> = emptyList(),
            moment: SharedMoment? = null,
            momentFriend: String? = null,
            animated: Boolean = true,
            news: IslandNews? = null,
        ): IslandScene {
            val state = IslandState.of(game, now, friends, moment, momentFriend, animated)
            val partner = game.care?.partnerName?.trimmedSwift()?.ifEmpty { "your partner" }
            return IslandScene(
                state = state,
                careWith = partner,
                news = news?.takeIf { it.isFresh(now) },
                cratesWaiting = game.crates.values.sum(),
            )
        }
    }
}
