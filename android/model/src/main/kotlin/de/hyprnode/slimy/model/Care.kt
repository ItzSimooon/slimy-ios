package de.hyprnode.slimy.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

// MARK: - Caring together
//
// Two people care for one slime. The host's slime is the shared one; the
// joiner's own slime waits safely on their phone (`CareLink.ownBackup`).
// Every care action on one phone becomes a `CareEvent` that the other phone
// applies; snapshots of the shared slime settle any drift: a snapshot is
// taken over when the phone that wrote it knew of a newer change.
// Coins, things owned, records, challenges and settings stay personal.

/** A care action that reaches the other phone. */
@Serializable
enum class CareKind {
    feed, play, bath, pet, sleep, wake, gift;

    /** Item sprite for the memory book. */
    val icon: String
        get() = when (this) {
            feed -> "apple"
            play -> "ball"
            bath -> "bubbleItem"
            pet -> "heart"
            sleep -> "starJelly"
            wake -> "flower"
            gift -> "chest"
        }

    /** "Anna fed Blob". */
    fun sentence(by: String, slime: String): String = when (this) {
        feed -> "$by fed $slime"
        play -> "$by played with $slime"
        bath -> "$by gave $slime a bath"
        pet -> "$by petted $slime"
        sleep -> "$by put $slime to bed"
        wake -> "$by woke $slime up"
        gift -> "$by gave $slime a present"
    }

    companion object {
        val allCases: List<CareKind> get() = entries
    }
}

/**
 * One care action. [amount] carries what the kind needs: for `pet` the love
 * it gave, for `play` the score as a percentage of a good round (0...200).
 * The other kinds leave it null.
 */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = CareEvent.Coding::class)
data class CareEvent(
    val id: String,
    val kind: CareKind,
    val food: Food? = null,
    val game: GameID? = null,
    val amount: Double? = null,
    /** Who did it: the partner's name (your own on actions you send). */
    val by: String,
    val at: JsonDate,
) {
    /** "Anna fed Blob". */
    fun summary(slime: String): String {
        val person = by.trimmedSwift()
        return kind.sentence(if (person.isEmpty()) "Your partner" else person, slime)
    }

    /** Snapshots and events come from the other phone, which may run another version. */
    internal object Coding : TolerantSerializer<CareEvent>(CareEvent.generatedSerializer()) {
        override fun decode(fields: JsonFields): CareEvent {
            val amount = fields.value("amount", Double.serializer().nullable, null)
            return CareEvent(
                id = fields.require("id", String.serializer()),
                kind = fields.require("kind", CareKind.serializer()),
                food = fields.value("food", Food.serializer().nullable, null),
                game = fields.value("game", GameID.serializer().nullable, null),
                amount = amount?.takeIf { it.isFinite() },
                by = fields.string("by", ""),
                at = fields.require("at", fields.dateSerializer),
            )
        }
    }
}

/** The shared slime as it travels between the two phones. */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = SlimeSnapshot.Coding::class)
data class SlimeSnapshot(
    val name: String,
    val skin: Skin,
    val level: Int,
    val xp: Int,
    val needs: Needs,
    val bond: Double,
    val personality: Personality,
    val equipped: Accessories,
    val emotions: Emotions,
    val asleep: Boolean,
    val favorite: Food,
    val favoriteGame: GameID,
    val hatchedAt: JsonDate,
    /** When the slime last changed on the phone that wrote it. The newer wins. */
    val updatedAt: JsonDate,
    /** When its needs and feelings were as written here. */
    val takenAt: JsonDate,
    /** Colours it has besides the ones its level opens. */
    @Serializable(with = LossySkinSetSerializer::class)
    val colours: Set<Skin>,
    /** Which phone wrote it (`CareBook.me`), so a phone skips its own. */
    val author: String? = null,
) {
    /** `slime` with the shared fields from here, checked; what it's saying and its reaction stay. */
    fun applied(to: Slime): Slime {
        val s = to.deepCopy()
        val trimmed = name.trimmedSwift()
        if (trimmed.isNotEmpty()) s.name = trimmed.prefixChars(16)
        s.skin = skin
        s.xp = max(0, xp)
        s.needs = needs.copy()
        s.needs.sanitize()
        s.bond = min(100.0, max(0.0, if (bond.isFinite()) bond else 0.0))
        val p = personality.copy()
        for (trait in Personality.Trait.entries) p[trait] = if (p[trait].isFinite()) p[trait] else 0.5
        s.personality = p
        s.equipped = Accessories(
            hat = wearable(equipped.hat, ItemKind.hat),
            glasses = wearable(equipped.glasses, ItemKind.glasses),
            scarf = wearable(equipped.scarf, ItemKind.scarf),
            effect = wearable(equipped.effect, ItemKind.effect),
        )
        s.emotions = emotions.copy()
        s.isAsleep = asleep
        s.favorite = favorite
        s.favoriteGame = if (favoriteGame == GameID.daily) s.favoriteGame else favoriteGame
        s.birthday = hatchedAt
        s.colours = colours
        return s
    }

    companion object {
        /** The server checks the name but doesn't shorten it: it goes out at most 16 characters long. */
        fun of(slime: Slime, updatedAt: Instant, author: String?): SlimeSnapshot = SlimeSnapshot(
            name = slime.name.prefixChars(16), skin = slime.skin, level = slime.level, xp = slime.xp,
            needs = slime.needs.copy(), bond = slime.bond, personality = slime.personality.copy(),
            equipped = slime.equipped.copy(), emotions = slime.emotions.copy(), asleep = slime.isAsleep,
            favorite = slime.favorite, favoriteGame = slime.favoriteGame, hatchedAt = slime.birthday,
            updatedAt = updatedAt, takenAt = slime.lastUpdate, colours = slime.colours, author = author,
        )

        /** An accessory id only if this app knows it as that kind. */
        private fun wearable(id: String?, kind: ItemKind): String? {
            if (id == null || Catalog.item(id)?.kind != kind) return null
            return id
        }
    }

    /** A snapshot needs at least a name and a time; everything else falls back to a newborn's values. */
    internal object Coding : TolerantSerializer<SlimeSnapshot>(SlimeSnapshot.generatedSerializer()) {
        override fun decode(fields: JsonFields): SlimeSnapshot {
            val d = Slime()
            val stamp = fields.require("updatedAt", fields.dateSerializer)
            val points = max(0, fields.int("xp", d.xp))
            return SlimeSnapshot(
                name = fields.require("name", String.serializer()),
                updatedAt = stamp,
                skin = fields.value("skin", Skin.serializer(), d.skin),
                xp = points,
                level = fields.int("level", Slime.level(forXP = points)),
                needs = fields.value("needs", Needs.serializer(), d.needs),
                bond = fields.double("bond", d.bond),
                personality = fields.value("personality", Personality.serializer(), d.personality),
                equipped = fields.value("equipped", Accessories.serializer(), d.equipped),
                emotions = fields.value("emotions", Emotions.serializer(), d.emotions),
                asleep = fields.bool("asleep", d.isAsleep),
                favorite = fields.value("favorite", Food.serializer(), d.favorite),
                favoriteGame = fields.value("favoriteGame", GameID.serializer(), d.favoriteGame),
                hatchedAt = fields.date("hatchedAt", stamp),
                takenAt = fields.date("takenAt", stamp),
                colours = Skin.lossySet(fields.strings("colours")),
                author = fields.value("author", String.serializer().nullable, null),
            )
        }
    }
}

/**
 * Who you care for the slime with. The online code keeps `cursor` and
 * `version` up to date and may replace the whole link.
 */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = CareLink.Coding::class)
data class CareLink(
    var partnerID: String,
    var partnerName: String,
    /** "host" or "joiner". */
    var role: String,
    var cursor: String? = null,
    var version: Int,
    /** The joiner's own slime, kept safe on this phone while the shared one is here. */
    var ownBackup: Slime? = null,
) {
    val isHost: Boolean get() = role == host

    fun deepCopy(): CareLink = copy(ownBackup = ownBackup?.deepCopy())

    companion object {
        const val host = "host"
        const val joiner = "joiner"
    }

    /** A link without a partner is no link: it fails, and the save loads as not caring together. */
    internal object Coding : TolerantSerializer<CareLink>(CareLink.generatedSerializer()) {
        override fun decode(fields: JsonFields): CareLink {
            val partner = fields.require("partnerID", String.serializer())
            if (partner.isEmpty()) throw SerializationException("No partner.")
            val backup = fields.value("ownBackup", Slime.serializer().nullable, null)
            val decodedRole = fields.string("role", if (backup == null) host else joiner)
            return CareLink(
                partnerID = partner,
                partnerName = fields.string("partnerName", ""),
                role = if (decodedRole == host || decodedRole == joiner) decodedRole else (if (backup == null) host else joiner),
                cursor = fields.value("cursor", String.serializer().nullable, null),
                version = fields.int("version", 0),
                ownBackup = backup,
            )
        }
    }
}

object CareEventListSerializer : LossyListSerializer<CareEvent>(CareEvent.serializer())

/**
 * This phone's side of caring together. Kept apart from `CareLink`, which
 * the online code replaces as a whole.
 */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = CareBook.Coding::class)
data class CareBook(
    /** This phone's mark on the snapshots it writes; new for every pairing. */
    var me: String = newUUIDString(),
    /** When the shared slime last changed here. */
    var changedAt: JsonDate = DistantPast,
    /** The partner's actions up to this time are in the snapshot taken over. */
    var adoptedAt: JsonDate? = null,
    /** The partner's actions already applied (ids), newest last. */
    var seen: List<String> = emptyList(),
    /** Care actions done on this phone, waiting to be sent. */
    @Serializable(with = CareEventListSerializer::class)
    var outbox: List<CareEvent> = emptyList(),
    /** Colours found in crates while caring together: they come home with the joiner's own slime. */
    @Serializable(with = LossySkinSetSerializer::class)
    var coloursFound: Set<Skin> = emptySet(),
) {
    /** Keeps the lists short and the times out of the future. */
    fun sanitize(now: Instant) {
        if (seen.size > seenLimit) seen = seen.drop(seen.size - seenLimit)
        if (outbox.size > outboxLimit) outbox = outbox.drop(outbox.size - outboxLimit)
        if (changedAt > now) changedAt = now
        val adopted = adoptedAt
        if (adopted != null && adopted > now.addingTimeInterval(24.0 * 3600)) adoptedAt = now
    }

    companion object {
        const val seenLimit = 300
        const val outboxLimit = 50

        /** A new pairing's book. */
        fun fresh(changedAt: Instant): CareBook = CareBook(changedAt = changedAt)
    }

    internal object Coding : TolerantSerializer<CareBook>(CareBook.generatedSerializer()) {
        override fun decode(fields: JsonFields): CareBook {
            val d = CareBook()
            return CareBook(
                me = fields.string("me", d.me),
                changedAt = fields.date("changedAt", d.changedAt),
                adoptedAt = fields.dateOrNull("adoptedAt"),
                seen = fields.value("seen", ListSerializer(String.serializer()), d.seen),
                outbox = fields.value("outbox", CareEventListSerializer, d.outbox),
                coloursFound = Skin.lossySet(fields.strings("coloursFound")),
            )
        }
    }
}

/** A partner's care action as the memory book keeps it: "Anna|feed|Blob". */
class PartnerCareNote(person: String, val kind: CareKind, slime: String) {
    val person: String = person.replace("|", "/")
    val slime: String = slime.replace("|", "/")

    val detail: String get() = "$person|${kind.name}|$slime"

    /** "Anna fed Blob". */
    val summary: String get() = kind.sentence(person, slime)

    override fun equals(other: Any?): Boolean =
        other is PartnerCareNote && other.person == person && other.kind == kind && other.slime == slime

    override fun hashCode(): Int = detail.hashCode()

    companion object {
        /** `PartnerCareNote(detail:)`: null when the text isn't one. */
        fun parse(detail: String): PartnerCareNote? {
            val parts = detail.split("|")
            if (parts.size != 3) return null
            val kind = enumOf<CareKind>(parts[1]) ?: return null
            if (parts[0].isEmpty()) return null
            return PartnerCareNote(parts[0], kind, if (parts[2].isEmpty()) "your slime" else parts[2])
        }
    }
}

// MARK: - Rules

val GameState.isSharing: Boolean get() = care != null

/** Notes a care action done on this phone, to be sent to the partner. Does nothing unless you care together. */
fun GameState.noteCare(kind: CareKind, food: Food? = null, game: GameID? = null, amount: Double? = null, now: Instant) {
    if (care == null) return
    val event = CareEvent(
        id = newUUIDString(), kind = kind, food = food,
        game = game?.takeIf { it != GameID.daily },
        amount = amount?.let { if (it.isFinite()) min(200.0, max(0.0, it)) else null },
        by = player.name, at = now,
    )
    var outbox = careBook.outbox + event
    if (outbox.size > CareBook.outboxLimit) outbox = outbox.drop(outbox.size - CareBook.outboxLimit)
    careBook.outbox = outbox
    careBook.changedAt = maxOf(careBook.changedAt, now)
}

/** Hands over the care actions waiting to be sent, and forgets them. */
fun GameState.takeCareOutbox(): List<CareEvent> {
    val out = careBook.outbox
    careBook.outbox = emptyList()
    return out
}

/** The shared slime's look changed on this phone: the next snapshot should win on the other phone. */
fun GameState.noteSharedChange(now: Instant) {
    if (care == null) return
    careBook.changedAt = maxOf(careBook.changedAt, now)
}

/** The shared slime as the other phone should see it. */
fun GameState.sharedSnapshot(now: Instant): SlimeSnapshot {
    val stamp = if (care == null) now else minOf(maxOf(careBook.changedAt, slime.birthday), now)
    return SlimeSnapshot.of(slime, updatedAt = stamp, author = careBook.me)
}

/** The host's side: someone joined with your code. Just keeps the link; your slime is the shared one. */
fun GameState.startHosting(link: CareLink, now: Instant) {
    tick(now)
    val alreadySharing = care != null
    care = link
    if (alreadySharing) return
    careBook = CareBook.fresh(changedAt = now)
    memory.add(MemoryEntry(kind = MemoryEntry.Kind.careStarted, at = now, detail = link.partnerName, keepsake = true))
    welcome(link.partnerName, now)
    checkAchievements(now)
}

/**
 * The joiner's side: the shared slime moves in. Your own slime is kept in
 * `link.ownBackup` (the first one, if you join again).
 */
fun GameState.adoptShared(snapshot: SlimeSnapshot, link: CareLink, now: Instant) {
    tick(now)
    val joined = link.deepCopy()
    if (joined.ownBackup == null) {
        val current = care
        if (current != null) {
            joined.ownBackup = current.ownBackup ?: (if (current.isHost) slime else null)
        } else if (hatched) {
            joined.ownBackup = slime
        }
    }
    val shared = snapshot.applied(Slime())
    shared.lastUpdate = minOf(snapshot.takenAt, now)
    slime = shared
    hatched = true
    care = joined
    careBook = CareBook.fresh(changedAt = minOf(snapshot.updatedAt, now))
    careBook.adoptedAt = snapshot.updatedAt
    slime.advance(now, settings.sleep)
    memory.add(MemoryEntry(kind = MemoryEntry.Kind.careStarted, at = now, detail = joined.partnerName, keepsake = true))
    welcome(joined.partnerName, now)
    refreshChallenges(now)
    checkAchievements(now)
}

/**
 * Takes over a snapshot from the other phone if it knew of a newer change
 * than this phone. Feelings are blended rather than replaced; what only ever
 * grows (xp, bond, colours) never goes down. Returns whether anything changed.
 */
fun GameState.reconcile(snapshot: SlimeSnapshot, now: Instant): Boolean {
    if (care == null) return false
    val author = snapshot.author
    if (author != null && author == careBook.me) return false
    val stamp = minOf(snapshot.updatedAt, now)
    if (!(stamp > careBook.changedAt)) return false
    tick(now)
    val local = slime
    val s = snapshot.applied(slime)
    s.emotions = local.emotions.blended(snapshot.emotions, weight = 0.5)
    s.xp = max(local.xp, s.xp)
    s.bond = max(local.bond, s.bond)
    s.colours = s.colours + local.colours
    s.lastUpdate = minOf(snapshot.takenAt, now)
    slime = s
    slime.advance(now, settings.sleep)
    careBook.changedAt = stamp
    careBook.adoptedAt = maxOf(careBook.adoptedAt ?: DistantPast, snapshot.updatedAt)
    return true
}

/**
 * Applies one of the partner's care actions to the shared slime: the same
 * effect as here, but no coins. Each action counts once; actions already in
 * a snapshot taken over, and bedtimes that are long past, are skipped.
 * Returns "Anna fed Blob", or null if skipped.
 */
fun GameState.applyPartnerCare(event: CareEvent, now: Instant): String? {
    val link = care ?: return null
    if (careBook.seen.contains(event.id)) return null
    var seen = careBook.seen + event.id
    if (seen.size > CareBook.seenLimit) seen = seen.drop(seen.size - CareBook.seenLimit)
    careBook.seen = seen
    val adopted = careBook.adoptedAt
    if (adopted != null && event.at <= adopted) return null
    if ((event.kind == CareKind.sleep || event.kind == CareKind.wake) && now.timeIntervalSince(event.at) > 30 * 60) return null
    tick(now)

    when (event.kind) {
        CareKind.feed -> {
            val food = event.food ?: Food.apple
            val favorite = food == slime.favorite
            slime.isAsleep = false
            slime.needs.add(Need.hunger, food.nutrition * (if (favorite) 1.3 else 1.0))
            slime.needs.add(Need.`fun`, food.funBonus)
            if (food == Food.starJelly) {
                for (need in Need.entries) slime.needs.add(need, 20.0)
            }
            if (favorite) {
                slime.needs.add(Need.love, 6.0)
                slime.feel(Emotion.love, 0.15)
            }
            slime.bond = min(100.0, slime.bond + 0.5)
            slime.personality.drift(Personality.Trait.foodie)
            slime.feel(Emotion.joy, 0.2)
            slime.xp += 6
            slime.react(Reaction.eat, now)
        }
        CareKind.play -> {
            val game = event.game?.takeIf { it != GameID.daily } ?: slime.favoriteGame
            val ratio = max(0.0, min(2.0, (event.amount ?: 50.0) / 100))
            val favorite = game == slime.favoriteGame
            slime.isAsleep = false
            slime.needs.add(Need.`fun`, (14 + min(16.0, ratio * 10)) * (if (favorite) 1.3 else 1.0))
            slime.needs.add(Need.energy, -game.energyCost)
            slime.needs.add(Need.clean, -5.0)
            slime.needs.add(Need.hunger, -4.0)
            if (favorite) slime.bond = min(100.0, slime.bond + 1)
            slime.personality.drift(Personality.Trait.playful)
            slime.feel(Emotion.excitement, 0.25)
            slime.xp += min(30, 6 + (ratio * 14).rounded().toInt())
            slime.react(Reaction.play, now)
        }
        CareKind.bath -> {
            slime.isAsleep = false
            slime.needs.clean = 100.0
            slime.needs.add(Need.love, 4.0)
            slime.bond = min(100.0, slime.bond + 0.5)
            slime.feel(Emotion.joy, 0.2)
            slime.xp += 6
            slime.react(Reaction.bath, now)
        }
        CareKind.pet -> {
            slime.needs.add(Need.love, max(0.0, min(14.0, event.amount ?: 8.0)))
            slime.needs.add(Need.`fun`, 2.0)
            slime.bond = min(100.0, slime.bond + 1)
            slime.personality.drift(Personality.Trait.cuddly, by = 0.002)
            slime.feel(Emotion.love, 0.15)
            slime.xp += 3
            if (!slime.isAsleep) slime.react(Reaction.love, now)
        }
        CareKind.sleep -> {
            slime.isAsleep = true
            slime.reaction = Reaction.none
            slime.personality.drift(Personality.Trait.sleepy, by = 0.002)
        }
        CareKind.wake -> {
            slime.isAsleep = false
            slime.react(Reaction.stretch, now)
        }
        CareKind.gift -> {
            slime.isAsleep = false
            slime.needs.add(Need.love, 5.0)
            slime.needs.add(Need.`fun`, 4.0)
            slime.bond = min(100.0, slime.bond + 0.5)
            slime.feel(Emotion.excitement, 0.15)
            slime.xp += Streak.xp
            slime.react(Reaction.celebrate, now)
        }
    }
    // Being looked after by two people feels good.
    slime.feel(Emotion.love, 0.12)
    slime.feel(Emotion.joy, 0.12)
    progress.stats.partnerCare += 1

    val given = event.by.trimmedSwift()
    val named = link.partnerName.trimmedSwift()
    val person = if (given.isEmpty()) (if (named.isEmpty()) "Your partner" else named) else given
    val note = PartnerCareNote(person, event.kind, slime.name)
    memory.add(MemoryEntry(kind = MemoryEntry.Kind.partnerCare, at = now, detail = note.detail))
    careBook.changedAt = maxOf(careBook.changedAt, minOf(event.at, now))
    if (!slime.isAsleep) {
        val line = Dialogue.say(LineCategory.partnerCared, this, LineContext(friend = person), now)
        slime.say = Utterance(text = line, at = now)
    }
    checkAchievements(now)
    return note.summary
}

/**
 * Ends caring together on this phone. The joiner gets their own slime back
 * (with any colour they found meanwhile); the host keeps the slime.
 * [quietly] leaves no memory or words (a save being imported).
 */
fun GameState.stopSharing(now: Instant, quietly: Boolean = false) {
    val link = care ?: return
    tick(now)
    val backup = link.ownBackup
    if (link.role == CareLink.joiner && backup != null) {
        val own = backup.deepCopy()
        own.colours = own.colours + careBook.coloursFound
        // It waited safely: no time passed for it.
        own.lastUpdate = now
        own.say = null
        own.reaction = Reaction.none
        own.reactionAt = DistantPast
        slime = own
    }
    care = null
    careBook = CareBook()
    // Back to wearing only your own things.
    for (id in slime.equipped.all) if (!inventory.owned.contains(id)) unequip(id)
    if (quietly) return
    memory.add(MemoryEntry(kind = MemoryEntry.Kind.careEnded, at = now, detail = link.partnerName))
    slime.feel(Emotion.joy, 0.2)
    slime.feel(Emotion.love, 0.15)
    if (!slime.isAsleep) {
        slime.react(Reaction.wave, now)
        val line = Dialogue.say(LineCategory.careEnded, this, LineContext(friend = link.partnerName), now)
        slime.say = Utterance(text = line, at = now)
    }
}

/** The slime meets its second caretaker. */
private fun GameState.welcome(partner: String, now: Instant) {
    slime.feel(Emotion.joy, 0.3)
    slime.feel(Emotion.curiosity, 0.25)
    slime.feel(Emotion.love, 0.1)
    if (slime.isAsleep) return
    slime.react(Reaction.wave, now)
    val line = Dialogue.say(LineCategory.careStarted, this, LineContext(friend = partner), now)
    slime.say = Utterance(text = line, at = now)
}
