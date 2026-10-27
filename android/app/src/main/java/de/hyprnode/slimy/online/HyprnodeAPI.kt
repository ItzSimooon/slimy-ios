package de.hyprnode.slimy.online

import de.hyprnode.slimy.model.Accessories
import de.hyprnode.slimy.model.CareEvent
import de.hyprnode.slimy.model.CareKind
import de.hyprnode.slimy.model.Food
import de.hyprnode.slimy.model.GameID
import de.hyprnode.slimy.model.JsonDate
import de.hyprnode.slimy.model.JsonFields
import de.hyprnode.slimy.model.LossyListSerializer
import de.hyprnode.slimy.model.Mood
import de.hyprnode.slimy.model.Needs
import de.hyprnode.slimy.model.Reaction
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.SlimeSnapshot
import de.hyprnode.slimy.model.SlimyJson
import de.hyprnode.slimy.model.Stage
import de.hyprnode.slimy.model.TolerantSerializer
import de.hyprnode.slimy.model.prefixChars
import de.hyprnode.slimy.model.trimmedSwift
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Client for the slime.hyprnode.de backend (server/ in this repo). The full
// contract is in server/README.md; keep the two in sync. The Android port of
// App/Online/HyprnodeAPI.swift: same wire types, same JSON field names, ISO
// 8601 dates (`SlimyJson.api`).
//
// Tolerant decoding: the Swift types decode leniently (unknown skins, moods,
// stages, foods, presets and reactions fall back to a default or to none;
// lists skip elements they can't read). Types with a Swift `init(from:)` get
// a `TolerantSerializer` here that reads field by field the same way; the
// others rely on `SlimyJson.api` (`coerceInputValues`, `ignoreUnknownKeys`).
// Lists that skip broken elements use `LossyListSerializer`.

/** What went wrong talking to slime.hyprnode.de. Mirrors the Swift `APIError` enum. */
sealed class APIError(description: String) : Exception(description) {
    /** The network is gone. */
    class offline : APIError("Can't reach slime.hyprnode.de. Check your internet and try again.")

    /**
     * The server answered outside 2xx. [serverMessage] is the server's own
     * words (Swift's `message`, renamed because `Throwable.message` is taken;
     * `message` here is the error description). A timeout counts as 504.
     */
    class server(override val status: Int, val serverMessage: String?) :
        APIError(serverMessage ?: "The server reported error $status.")

    /** The server isn't set up or reachable yet (DNS, TLS, a bare 404). */
    class notReady : APIError("slime.hyprnode.de isn't reachable right now. Please try again later.")

    /** The text to show (Swift's `errorDescription`). */
    val errorDescription: String get() = message ?: ""

    /** The HTTP status the server answered with, if it answered. A request that timed out counts as 504. */
    open val status: Int? get() = null

    /** Worth trying again later (offline queue); a 4xx answer is final. */
    val isTransient: Boolean
        get() = when (this) {
            is offline, is notReady -> true
            is server -> status >= 500 || status == 429
        }

    /** The server banned this device from online features (403). */
    val isBanned: Boolean get() = status == 403

    /** The server doesn't know this device's token (401), for example after its data was deleted. */
    val isUnauthorized: Boolean get() = status == 401

    /** The server refused what was sent (400). For a name or a profile that means its filter. */
    val isRefused: Boolean get() = status == 400

    /** A daily or hourly limit (429). Its message says when to try again. */
    val isRateLimited: Boolean get() = status == 429

    /** A tapped action should wait in the offline queue. */
    val shouldQueue: Boolean get() = isTransient && !isRateLimited
}

/**
 * Thrown instead of calling the server while the player hasn't gone online
 * (or went offline). Nothing reaches slime.hyprnode.de in that state.
 */
class NotOnlineError : Exception("Go online in Friends to use this.") {
    val errorDescription: String get() = message ?: ""
}

/** The text to show for any error, like Swift's `localizedDescription`. */
internal val Throwable.shownText: String
    get() = when (this) {
        is APIError -> errorDescription
        is NotOnlineError -> errorDescription
        else -> message?.takeIf { it.isNotBlank() } ?: "Something went wrong. Please try again."
    }

// MARK: - Wire types

/**
 * A slime as friends see it. Swift decodes leniently: unknown skin -> lime,
 * unknown mood -> idle, unknown stage / outfit -> none.
 */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = SlimeProfile.Coding::class)
data class SlimeProfile(
    val ownerName: String,
    val name: String,
    val skin: Skin = Skin.lime,
    val level: Int,
    val stage: Stage? = null,
    val mood: Mood = Mood.idle,
    /** null when the friend hides their slime's mood. */
    val happiness: Int? = null,
    val equipped: Accessories? = null,
    val updatedAt: JsonDate,
) {
    /** Looks from a newer app version fall back to defaults, so a friend never vanishes from the list because of them. */
    internal object Coding : TolerantSerializer<SlimeProfile>(SlimeProfile.generatedSerializer()) {
        override fun decode(fields: JsonFields): SlimeProfile = SlimeProfile(
            ownerName = fields.require("ownerName", String.serializer()),
            name = fields.require("name", String.serializer()),
            level = fields.require("level", Int.serializer()),
            updatedAt = fields.require("updatedAt", fields.dateSerializer),
            skin = fields.value("skin", Skin.serializer(), Skin.lime),
            stage = fields.value("stage", Stage.serializer().nullable, null),
            mood = fields.value("mood", Mood.serializer(), Mood.idle),
            happiness = fields.value("happiness", Int.serializer().nullable, null),
            equipped = fields.value("equipped", Accessories.serializer().nullable, null),
        )
    }
}

@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = Friend.Coding::class)
data class Friend(
    val id: String,
    val ownerName: String,
    val slime: SlimeProfile,
    /** Kept for older app builds, which still show it. This app ignores it. */
    val love: Int = 0,
    /** Kept for older app builds, which still show it. This app ignores it. */
    val inLove: Boolean = false,
    val since: JsonDate,
) {
    val stage: Stage get() = slime.stage ?: Stage.forLevel(slime.level)

    /** The love fields are optional on the way in, so a server that stops sending them never hides a friend. */
    internal object Coding : TolerantSerializer<Friend>(Friend.generatedSerializer()) {
        override fun decode(fields: JsonFields): Friend = Friend(
            id = fields.require("id", String.serializer()),
            ownerName = fields.require("ownerName", String.serializer()),
            slime = fields.require("slime", SlimeProfile.serializer()),
            since = fields.require("since", fields.dateSerializer),
            love = fields.int("love", 0),
            inLove = fields.bool("inLove", false),
        )
    }
}

@Serializable
data class FriendRequest(
    val id: String,
    val deviceId: String,
    val ownerName: String,
    val slime: SlimeProfile? = null,
    val createdAt: JsonDate,
)

@Serializable
data class RequestList(
    val incoming: List<FriendRequest> = emptyList(),
    val outgoing: List<FriendRequest> = emptyList(),
)

@Serializable
data class JoinResult(
    /** "pending" (waits for the other side) or "friends". */
    val status: String,
    val friend: Friend? = null,
    val request: FriendRequest? = null,
)

@Serializable
data class PairCode(
    val code: String,
    val expiresAt: JsonDate,
)

/** What this app sends a friend. (The server still takes "kiss" from older app builds; this one never sends it.) */
@Serializable
enum class InteractionKind {
    hug, visit, gift;

    companion object {
        val allCases: List<InteractionKind> get() = entries
    }
}

/** The server's answer to a hug, visit or gift. Its love fields are only for older app builds and are ignored here. */
@Serializable
data class InteractionResult(
    val love: Int? = null,
    val inLove: Boolean? = null,
    val gained: Int? = null,
    val fellInLove: Boolean? = null,
    /** Today's limit for this kind is reached: it didn't reach the friend. */
    val capped: Boolean? = null,
    val message: String? = null,
)

/** Short greetings friends can send. Only presets, so nobody can send anything unkind through Slimy. */
@Serializable
enum class MessagePreset {
    hello, missYou, goodNight, goodMorning, letsPlay, thanks, congrats, proud;

    val id: String get() = name

    val text: String
        get() = when (this) {
            hello -> "Hi!"
            missYou -> "I miss you."
            goodNight -> "Good night!"
            goodMorning -> "Good morning!"
            letsPlay -> "Let's play!"
            thanks -> "Thank you!"
            congrats -> "Congrats!"
            proud -> "I'm proud of you."
        }

    /** Pixel item sprite for the preset. */
    val icon: String
        get() = when (this) {
            hello, goodMorning -> "speech"
            missYou, proud -> "heart"
            goodNight -> "starJelly"
            letsPlay -> "ball"
            thanks -> "flower"
            congrats -> "gem"
        }

    companion object {
        val allCases: List<MessagePreset> get() = entries
    }
}

@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = InboxEvent.Coding::class)
data class InboxEvent(
    val id: String,
    val friendId: String? = null,
    val friendName: String? = null,
    /**
     * request, accepted, hug, visit, gift, message, challenge,
     * challengeResult, coopDone. Friends on older app builds can still send
     * kiss and inLove; a kiss arrives as a hug, inLove is skipped. Unknown
     * kinds from a newer server are ignored.
     */
    val kind: String,
    val food: Food? = null,
    val preset: MessagePreset? = null,
    val challengeId: String? = null,
    val requestId: String? = null,
    val createdAt: JsonDate,
) {
    internal object Coding : TolerantSerializer<InboxEvent>(InboxEvent.generatedSerializer()) {
        override fun decode(fields: JsonFields): InboxEvent = InboxEvent(
            id = fields.require("id", String.serializer()),
            kind = fields.require("kind", String.serializer()),
            createdAt = fields.require("createdAt", fields.dateSerializer),
            friendId = fields.value("friendId", String.serializer().nullable, null),
            friendName = fields.value("friendName", String.serializer().nullable, null),
            food = fields.value("food", Food.serializer().nullable, null),
            preset = fields.value("preset", MessagePreset.serializer().nullable, null),
            challengeId = fields.value("challengeId", String.serializer().nullable, null),
            requestId = fields.value("requestId", String.serializer().nullable, null),
        )
    }
}

@Serializable
data class InboxPage(
    val events: List<InboxEvent> = emptyList(),
    val cursor: String? = null,
)

@Serializable
data class FriendMemory(
    val kind: String,
    val detail: String? = null,
    val at: JsonDate,
)

@Serializable
data class CoopStatus(
    val week: String,
    val goal: Int,
    val mine: Int,
    val theirs: Int,
    val total: Int,
    val done: Boolean,
    val added: Int? = null,
)

@Serializable
data class FriendDetail(
    val friend: Friend,
    val memories: List<FriendMemory> = emptyList(),
    val coop: CoopStatus,
    /** Hugs, visits and gifts that still reach the friend today, by kind. */
    val today: Map<String, Int> = emptyMap(),
)

@Serializable
data class Challenge(
    val id: String,
    val game: GameID,
    val seed: String,
    val friendId: String,
    val friendName: String,
    val sentByMe: Boolean,
    val myScore: Int? = null,
    val theirScore: Int? = null,
    val createdAt: JsonDate,
    val expiresAt: JsonDate,
    val answeredAt: JsonDate? = null,
) {
    val isOpenForMe: Boolean get() = !sentByMe && myScore == null && expiresAt > Instant.now()
    val seedValue: ULong get() = seed.toULongOrNull() ?: 1uL
}

@Serializable
data class Privacy(
    val acceptRequests: Boolean,
    val shareMood: Boolean,
)

@Serializable
data class BlockedDevice(
    val deviceId: String,
    val ownerName: String,
    val since: JsonDate,
) {
    val id: String get() = deviceId
}

/**
 * Why someone reports a person or a chat reply. Reports go to the Slimy team;
 * they never block anyone by themselves.
 *
 * Swift's `.name` case is [inappropriateName] here (an enum entry can't be
 * called `name` in Kotlin); it is still "name" on the wire ([rawValue]).
 */
@Serializable
enum class ReportReason {
    @SerialName("name")
    inappropriateName,
    harassment, spam, chat, other;

    val id: String get() = rawValue

    /** The wire value: "name", "harassment", "spam", "chat", "other". */
    val rawValue: String get() = if (this == inappropriateName) "name" else name

    val title: String
        get() = when (this) {
            inappropriateName -> "Inappropriate name"
            harassment -> "Bullying or harassment"
            spam -> "Spam"
            chat -> "Inappropriate slime reply"
            other -> "Something else"
        }

    companion object {
        val allCases: List<ReportReason> get() = entries

        /** The reasons that fit a person. A chat reply is reported from Chat. */
        val forPeople: List<ReportReason> get() = entries.filter { it != chat }
    }
}

/** One player's best score in a game, as the friends leaderboard shows it. Unknown skin -> lime, stage -> young. */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = LeaderboardEntry.Coding::class)
data class LeaderboardEntry(
    val deviceId: String,
    val ownerName: String,
    val slimeName: String,
    val skin: Skin = Skin.lime,
    val stage: Stage = Stage.young,
    val best: Int,
    /** This is you. */
    val me: Boolean = false,
) {
    val id: String get() = deviceId

    /** Skins and stages from a newer server fall back to defaults instead of hiding the whole board. */
    internal object Coding : TolerantSerializer<LeaderboardEntry>(LeaderboardEntry.generatedSerializer()) {
        override fun decode(fields: JsonFields): LeaderboardEntry = LeaderboardEntry(
            deviceId = fields.require("deviceId", String.serializer()),
            ownerName = fields.require("ownerName", String.serializer()),
            slimeName = fields.require("slimeName", String.serializer()),
            best = fields.require("best", Int.serializer()),
            me = fields.bool("me", false),
            skin = fields.value("skin", Skin.serializer(), Skin.lime),
            stage = fields.value("stage", Stage.serializer(), Stage.young),
        )
    }
}

/** The best scores of you and your friends in one game, best first. */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = LeaderboardGame.Coding::class)
data class LeaderboardGame(
    val game: GameID,
    val entries: List<LeaderboardEntry> = emptyList(),
) {
    val id: String get() = game.name

    /** Your own entry, if you played this game. */
    val mine: LeaderboardEntry? get() = entries.firstOrNull { it.me }

    /** 1-based place of an entry. Equal scores share a place. */
    fun rank(entry: LeaderboardEntry): Int = 1 + entries.count { it.best > entry.best }

    /** Entries this version can't read are skipped; the rest best first. */
    internal object Coding : TolerantSerializer<LeaderboardGame>(LeaderboardGame.generatedSerializer()) {
        override fun decode(fields: JsonFields): LeaderboardGame = LeaderboardGame(
            game = fields.require("game", GameID.serializer()),
            entries = fields.require("entries", LossyListSerializer(LeaderboardEntry.serializer()))
                .sortedByDescending { it.best },
        )
    }
}

/** `GET /friends/leaderboard`. Games this app version doesn't know are skipped, and so are `daily` and empty games. */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = Leaderboard.Coding::class)
data class Leaderboard(
    val games: List<LeaderboardGame> = emptyList(),
) {
    internal object Coding : TolerantSerializer<Leaderboard>(Leaderboard.generatedSerializer()) {
        override fun decode(fields: JsonFields): Leaderboard = Leaderboard(
            games = fields.require("games", LossyListSerializer(LeaderboardGame.serializer()))
                .filter { it.game != GameID.daily && it.entries.isNotEmpty() },
        )
    }
}

@Serializable
data class ChatTurn(
    val role: Role,
    val text: String,
) {
    @Serializable
    enum class Role { user, slime }
}

@Serializable
data class ChatContext(
    val name: String,
    val ageDays: Int,
    val level: Int,
    val mood: Mood,
    val needs: Needs,
    val favorite: Food,
    val localTime: String,
    /** How the slime feels right now, emotion name to 0...1 (`Emotions.wire`). Left out when null. */
    val emotions: Map<String, Double>? = null,
)

/**
 * The one feeling a chat reply expresses, as the server returns it. `name` is
 * an `Emotion` raw value when the AI kept to the list. Swift trims and
 * lower-cases the name and clamps the intensity to 0...1 (missing or broken: 0.5).
 */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = ChatEmotion.Coding::class)
data class ChatEmotion(
    val name: String,
    /** 0...1. */
    val intensity: Double = 0.5,
) {
    internal object Coding : TolerantSerializer<ChatEmotion>(ChatEmotion.generatedSerializer()) {
        override fun decode(fields: JsonFields): ChatEmotion {
            val raw = fields.value("intensity", Double.serializer().nullable, null) ?: 0.5
            return ChatEmotion(
                name = fields.require("name", String.serializer()).trimmedSwift().lowercase(),
                intensity = if (raw.isFinite()) raw.coerceIn(0.0, 1.0) else 0.5,
            )
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = ChatReply.Coding::class)
data class ChatReply(
    val reply: String,
    /** Optional animation the slime plays while saying it. */
    val reaction: Reaction? = null,
    /** The feeling the reply expresses. Missing from older servers, and when the AI gave none. */
    val emotion: ChatEmotion? = null,
) {
    /** Only the reply is required: an unknown reaction or a broken emotion leaves that part out instead of losing the answer. */
    internal object Coding : TolerantSerializer<ChatReply>(ChatReply.generatedSerializer()) {
        override fun decode(fields: JsonFields): ChatReply = ChatReply(
            reply = fields.require("reply", String.serializer()),
            reaction = fields.value("reaction", Reaction.serializer().nullable, null),
            emotion = fields.value("emotion", ChatEmotion.serializer().nullable, null),
        )
    }
}

// MARK: - Care together

/** The person you care for a slime with, as the server describes them. */
@Serializable
data class CarePartner(
    val deviceId: String,
    val ownerName: String,
    /** The partner's own role, "host" or "joiner". Not in the join answer. */
    val role: String? = null,
) {
    /** This phone's role, the opposite of the partner's. */
    val myRole: String?
        get() = when (role) {
            CareRole.host -> CareRole.joiner
            CareRole.joiner -> CareRole.host
            else -> null
        }
}

/** The two roles in caring together. The host's slime is the shared one. */
object CareRole {
    const val host = "host"
    const val joiner = "joiner"
}

/** A string the server may also send as a number (ids, cursors). Null when missing, null or neither. */
private fun JsonFields.flexibleString(key: String): String? {
    val primitive = obj[key] as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    if (primitive.isString) return primitive.content
    return primitive.longOrNull?.toString()
}

/** Just the name inside a snapshot, whatever else it holds. */
private fun JsonFields.snapshotName(key: String): String? {
    val snapshot = obj[key] as? JsonObject ?: return null
    return JsonFields(json, snapshot).stringOrNull("name")
}

/**
 * A care action the partner took, as `GET /care` lists it. Swift: the id may
 * come as a number; a missing owner name is "Your partner"; a non-finite
 * amount is none; a missing `createdAt` is `at`.
 */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = CareEventWire.Coding::class)
data class CareEventWire(
    val id: String,
    val deviceId: String,
    val ownerName: String = "Your partner",
    val kind: CareKind,
    val food: Food? = null,
    val game: GameID? = null,
    val amount: Double? = null,
    val at: JsonDate,
    val createdAt: JsonDate = at,
) {
    /** The event as the game applies it. */
    val event: CareEvent
        get() = CareEvent(id = id, kind = kind, food = food, game = game, amount = amount, by = ownerName, at = at)

    /** The kind must be one this app knows (otherwise the event is skipped); food, game and amount from a newer app fall back to none. */
    internal object Coding : TolerantSerializer<CareEventWire>(CareEventWire.generatedSerializer()) {
        override fun decode(fields: JsonFields): CareEventWire {
            val at = fields.require("at", fields.dateSerializer)
            return CareEventWire(
                id = fields.flexibleString("id") ?: throw SerializationException("Missing id"),
                deviceId = fields.require("deviceId", String.serializer()),
                ownerName = fields.value("ownerName", String.serializer(), "Your partner"),
                kind = fields.require("kind", CareKind.serializer()),
                food = fields.value("food", Food.serializer().nullable, null),
                game = fields.value("game", GameID.serializer().nullable, null),
                amount = fields.value("amount", Double.serializer().nullable, null)?.takeIf { it.isFinite() },
                at = at,
                createdAt = fields.date("createdAt", at),
            )
        }
    }
}

/**
 * `GET /care`: the partner (null when not caring together), the newest shared
 * snapshot, and the partner's actions after the cursor. Decoded only.
 * [snapshotName] is not a JSON key: it is the `name` inside the snapshot,
 * read even when the snapshot itself can't be.
 */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = CareStatus.Coding::class)
data class CareStatus(
    val partner: CarePartner? = null,
    val snapshot: SlimeSnapshot? = null,
    @Transient val snapshotName: String? = null,
    val version: Int = 0,
    val events: List<CareEventWire> = emptyList(),
    val cursor: String? = null,
) {
    /** A snapshot this app version can't read counts as none (the next one may be readable); events it doesn't know are skipped. */
    internal object Coding : TolerantSerializer<CareStatus>(CareStatus.generatedSerializer()) {
        override fun decode(fields: JsonFields): CareStatus = CareStatus(
            partner = fields.value("partner", CarePartner.serializer().nullable, null),
            snapshot = fields.value("snapshot", SlimeSnapshot.serializer().nullable, null),
            snapshotName = fields.snapshotName("snapshot"),
            version = fields.int("version", 0),
            events = fields.value("events", LossyListSerializer(CareEventWire.serializer()), emptyList()),
            cursor = fields.flexibleString("cursor"),
        )
    }
}

/** `POST /care/join`: who you now care with and their slime, if their phone already sent it. A missing partner role is "host". */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = CareJoinResult.Coding::class)
data class CareJoinResult(
    val partner: CarePartner,
    val snapshot: SlimeSnapshot? = null,
    @Transient val snapshotName: String? = null,
    val version: Int = 0,
) {
    internal object Coding : TolerantSerializer<CareJoinResult>(CareJoinResult.generatedSerializer()) {
        override fun decode(fields: JsonFields): CareJoinResult {
            val partner = fields.require("partner", CarePartner.serializer())
            return CareJoinResult(
                // Whoever owns the code hosts.
                partner = if (partner.role == null) partner.copy(role = CareRole.host) else partner,
                snapshot = fields.value("snapshot", SlimeSnapshot.serializer().nullable, null),
                snapshotName = fields.snapshotName("snapshot"),
                version = fields.int("version", 0),
            )
        }
    }
}

/** `POST /care/events`. Build it from a `CareEvent` so the amount is clamped like the Swift init. */
@Serializable
data class CareEventBody(
    val kind: CareKind,
    val food: Food? = null,
    val game: GameID? = null,
    val amount: Double? = null,
    val at: JsonDate,
) {
    constructor(event: CareEvent) : this(event.kind, event.food, event.game, clampedAmount(event.amount), event.at)

    companion object {
        /** The most the server takes as an amount. */
        const val amountLimit = 200.0

        /** An amount as the server takes it: finite, 0...[amountLimit]; anything else is none. */
        fun clampedAmount(amount: Double?): Double? =
            amount?.takeIf { it.isFinite() }?.coerceIn(0.0, amountLimit)

        /** Swift's memberwise init, which clamps the amount. */
        fun of(kind: CareKind, food: Food?, game: GameID?, amount: Double?, at: Instant): CareEventBody =
            CareEventBody(kind, food, game, clampedAmount(amount), at)
    }
}

/** What `PUT /care/snapshot` did. */
sealed class CareSnapshotResult {
    /** Stored; the shared slime is at this version now. */
    data class saved(val version: Int) : CareSnapshotResult()

    /** The other phone saved first (409). Without a version, nobody is caring together. */
    data class conflict(val version: Int?, val snapshot: SlimeSnapshot?) : CareSnapshotResult()
}

/** The body of a 409 from `PUT /care/snapshot`. */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = CareConflict.Coding::class)
data class CareConflict(
    val error: String? = null,
    val version: Int? = null,
    val snapshot: SlimeSnapshot? = null,
) {
    internal object Coding : TolerantSerializer<CareConflict>(CareConflict.generatedSerializer()) {
        override fun decode(fields: JsonFields): CareConflict = CareConflict(
            error = fields.value("error", String.serializer().nullable, null),
            version = fields.value("version", Int.serializer().nullable, null),
            snapshot = fields.value("snapshot", SlimeSnapshot.serializer().nullable, null),
        )
    }
}

// MARK: - Client

/**
 * The slime.hyprnode.de client for one device token. Every endpoint is a
 * `suspend fun` that throws [APIError] (or a serialization exception for an
 * unreadable answer). Requests run on OkHttp's own threads; cancelling the
 * coroutine cancels the request.
 */
class HyprnodeAPI internal constructor(
    val token: String?,
    private val http: OkHttpClient,
    private val baseURL: String,
) {
    constructor(token: String? = null) : this(token, sharedClient, base)

    @Serializable
    data class Registration(val deviceId: String, val token: String)

    @Serializable
    data class Me(val deviceId: String, val ownerName: String, val privacy: Privacy)

    @Serializable
    data class ReportBody(
        val deviceId: String? = null,
        val reason: ReportReason,
        val note: String? = null,
        val text: String? = null,
    )

    @Serializable
    data class RecordsBody(val records: Map<String, Int>)

    @Serializable
    private data class ServerError(val error: String)

    @Serializable
    private data class Accepted(val friend: Friend)

    @Serializable
    private data class Sent(val sent: Boolean)

    @Serializable
    private data class InteractionBody(val kind: InteractionKind, val food: Food? = null)

    @Serializable
    private data class ChallengeBody(val friendId: String, val game: GameID, val seed: String, val score: Int)

    @Serializable
    private data class ChatBody(val message: String, val history: List<ChatTurn>, val slime: ChatContext)

    @Serializable
    private data class SnapshotBody(val snapshot: SlimeSnapshot, val version: Int)

    @Serializable
    private data class Saved(val version: Int)

    suspend fun register(ownerName: String): Registration =
        send("POST", "devices", body("ownerName" to JsonPrimitive(ownerName)), Registration.serializer())

    suspend fun upload(profile: SlimeProfile) {
        sendNoContent("PUT", "me/slime", encode(SlimeProfile.serializer(), profile))
    }

    suspend fun me(): Me = send("GET", "me", null, Me.serializer())

    suspend fun setPrivacy(privacy: Privacy): Privacy =
        send("PUT", "me/privacy", encode(Privacy.serializer(), privacy), Privacy.serializer())

    suspend fun deleteAccount() {
        sendNoContent("DELETE", "me", null)
    }

    suspend fun newPairCode(): PairCode = send("POST", "pair-codes", empty, PairCode.serializer())

    suspend fun join(code: String): JoinResult =
        send("POST", "friend-requests", body("code" to JsonPrimitive(code.uppercase())), JoinResult.serializer())

    suspend fun requests(): RequestList = send("GET", "friend-requests", null, RequestList.serializer())

    suspend fun accept(requestID: String): Friend =
        send("POST", "friend-requests/$requestID/accept", empty, Accepted.serializer()).friend

    suspend fun decline(requestID: String) {
        sendNoContent("POST", "friend-requests/$requestID/decline", empty)
    }

    /** Friends this app version can't read are skipped, not the whole list. */
    suspend fun friends(): List<Friend> = send("GET", "friends", null, LossyListSerializer(Friend.serializer()))

    suspend fun friend(id: String): FriendDetail = send("GET", "friends/$id", null, FriendDetail.serializer())

    suspend fun removeFriend(id: String) {
        sendNoContent("DELETE", "friends/$id", null)
    }

    suspend fun block(deviceID: String) {
        sendNoContent("POST", "blocks", body("deviceId" to JsonPrimitive(deviceID)))
    }

    suspend fun unblock(deviceID: String) {
        sendNoContent("DELETE", "blocks/$deviceID", null)
    }

    suspend fun blocked(): List<BlockedDevice> = send("GET", "blocks", null, ListSerializer(BlockedDevice.serializer()))

    suspend fun interact(id: String, kind: InteractionKind, food: Food?, key: String): InteractionResult =
        send(
            "POST", "friends/$id/interactions", encode(InteractionBody.serializer(), InteractionBody(kind, food)),
            InteractionResult.serializer(), key = key,
        )

    suspend fun message(id: String, preset: MessagePreset, key: String) {
        send("POST", "friends/$id/messages", body("preset" to JsonPrimitive(preset.name)), Sent.serializer(), key = key)
    }

    suspend fun addCoop(id: String, points: Int, key: String): CoopStatus =
        send("POST", "friends/$id/coop", body("points" to JsonPrimitive(points)), CoopStatus.serializer(), key = key)

    /** Challenges in games this app version doesn't have are skipped. */
    suspend fun challenges(): List<Challenge> =
        send("GET", "challenges", null, LossyListSerializer(Challenge.serializer()))

    suspend fun challenge(friendID: String, game: GameID, seed: ULong, score: Int, key: String): Challenge =
        send(
            "POST", "challenges",
            encode(ChallengeBody.serializer(), ChallengeBody(friendID, game, seed.toString(), score)),
            Challenge.serializer(), key = key,
        )

    suspend fun answer(challengeID: String, score: Int, key: String): Challenge =
        send("POST", "challenges/$challengeID/answer", body("score" to JsonPrimitive(score)), Challenge.serializer(), key = key)

    /** Sends a report to the Slimy team. `deviceId` is required unless the reason is `chat`; then `text` is the slime's reply. */
    suspend fun report(report: ReportBody, key: String) {
        sendNoContent("POST", "reports", encode(ReportBody.serializer(), report), key = key)
    }

    /** Raises your best scores on the server; it never lowers them. */
    suspend fun uploadRecords(records: Map<String, Int>) {
        sendNoContent("PUT", "me/records", encode(RecordsBody.serializer(), RecordsBody(records)))
    }

    suspend fun leaderboard(): List<LeaderboardGame> =
        send("GET", "friends/leaderboard", null, Leaderboard.serializer()).games

    suspend fun inbox(cursor: String?): InboxPage =
        send("GET", afterPath("inbox", cursor), null, InboxPage.serializer())

    /** Waits up to [chatTimeout]; a timeout is a 504 with "Your slime took too long to answer. Try again in a moment." */
    suspend fun chat(message: String, history: List<ChatTurn>, context: ChatContext): ChatReply =
        send(
            "POST", "chat", encode(ChatBody.serializer(), ChatBody(message, history, context)), ChatReply.serializer(),
            timeout = chatTimeout, slowAnswer = "Your slime took too long to answer. Try again in a moment.",
        )

    /** A code someone else enters to care for your slime with you (10 minutes, single use). 409 while you already care together. */
    suspend fun newCareCode(): PairCode = send("POST", "care/codes", empty, PairCode.serializer())

    /** Cares for the slime of the code's owner from now on. 404 unknown or used, 410 expired, 400 your own code, 409 already caring. */
    suspend fun joinCare(code: String): CareJoinResult =
        send("POST", "care/join", body("code" to JsonPrimitive(code.uppercase())), CareJoinResult.serializer())

    /** The pair, the newest snapshot and the partner's actions after [cursor]. */
    suspend fun care(cursor: String?): CareStatus = send("GET", carePath(cursor), null, CareStatus.serializer())

    /** Sends one of your care actions to the partner's phone. The key makes a resend count once. */
    suspend fun sendCare(body: CareEventBody, key: String) {
        sendNoContent("POST", "care/events", encode(CareEventBody.serializer(), body), key = key)
    }

    /** Stores the shared slime. [version] must be the server's version + 1; otherwise the answer is the server's newest state. */
    suspend fun putCareSnapshot(snapshot: SlimeSnapshot, version: Int): CareSnapshotResult {
        val (data, status) = perform(
            "PUT", "care/snapshot", encode(SnapshotBody.serializer(), SnapshotBody(snapshot, version)),
            key = null, timeout = defaultTimeout, slowAnswer = slowServerAnswer,
        )
        if (status in 200..299) {
            val saved = runCatching { decoder.decodeFromString(Saved.serializer(), data.decodeToString()) }.getOrNull()
            return CareSnapshotResult.saved(saved?.version ?: version)
        }
        if (status == 409) {
            val conflict = careConflict(data)
            return CareSnapshotResult.conflict(conflict?.version, conflict?.snapshot)
        }
        throw failure(status, data)
    }

    /** Stops caring together, for both of you. */
    suspend fun leaveCare() {
        sendNoContent("DELETE", "care", null)
    }

    // MARK: Plumbing

    private suspend fun <T> send(
        method: String,
        path: String,
        body: String?,
        deserializer: DeserializationStrategy<T>,
        key: String? = null,
        timeout: Double = defaultTimeout,
        slowAnswer: String = slowServerAnswer,
    ): T {
        val data = raw(method, path, body, key, timeout, slowAnswer)
        return decoder.decodeFromString(deserializer, data.decodeToString())
    }

    private suspend fun sendNoContent(method: String, path: String, body: String?, key: String? = null) {
        raw(method, path, body, key, defaultTimeout, slowServerAnswer)
    }

    /**
     * A timeout becomes a 504 with [slowAnswer]: the connection worked but
     * the answer didn't come in time. Like a 5xx it is worth trying again.
     */
    private suspend fun raw(
        method: String,
        path: String,
        body: String?,
        key: String?,
        timeout: Double,
        slowAnswer: String,
    ): ByteArray {
        val (data, status) = perform(method, path, body, key, timeout, slowAnswer)
        if (status !in 200..299) throw failure(status, data)
        return data
    }

    /** Sends the request and returns the answer with its status, whatever the status is. Only network problems throw. */
    private suspend fun perform(
        method: String,
        path: String,
        body: String?,
        key: String?,
        timeout: Double,
        slowAnswer: String,
    ): Pair<ByteArray, Int> {
        val url = urlFor(baseURL, path) ?: throw APIError.notReady()
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
        token?.let { builder.header("Authorization", "Bearer $it") }
        key?.let { builder.header("Idempotency-Key", it) }
        if (body != null) builder.header("Content-Type", "application/json")
        val requestBody = body?.toByteArray(Charsets.UTF_8)?.toRequestBody(jsonType)
        builder.method(method, requestBody)
        val client = if (timeout == defaultTimeout) http else http.withTimeout(timeout)
        try {
            return client.newCall(builder.build()).await()
        } catch (e: SocketTimeoutException) {
            throw APIError.server(504, slowAnswer)
        } catch (e: IOException) {
            if (hasNetwork?.invoke() == false) throw APIError.offline()
            throw when (e) {
                is UnknownHostException, is ConnectException, is SSLException -> APIError.notReady()
                else -> APIError.offline()
            }
        }
    }

    companion object {
        const val base = "https://slime.hyprnode.de/api/v1/"

        /** Swift's `HyprnodeAPI.encoder`: ISO 8601 dates, nil fields left out. */
        val encoder: Json get() = SlimyJson.api

        /** Swift's `HyprnodeAPI.decoder`. */
        val decoder: Json get() = SlimyJson.api

        /** Longest owner name the server keeps. */
        const val nameLimit = 24

        /** Longest slime name the server keeps (the game allows the same). */
        const val slimeNameLimit = 16

        /** Longest note a report may carry. */
        const val reportNoteLimit = 200

        /** Longest reported chat reply. */
        const val reportTextLimit = 500

        /** Highest score the server keeps per game. */
        const val recordCap = 1_000_000

        /** Seconds. The server tries up to three AI services, 12 s each, and sends nothing until it has the reply. */
        const val chatTimeout = 45.0

        /** Seconds a request may wait for the server's next bytes. */
        const val defaultTimeout = 20.0

        const val slowServerAnswer = "slime.hyprnode.de took too long to answer. Try again in a moment."

        /**
         * Whether the phone has a network at all, set by the app (from
         * `ConnectivityManager`). A failed request without one is [APIError.offline]
         * rather than [APIError.notReady]. Null (tests): decided by the error alone.
         */
        @Volatile
        var hasNetwork: (() -> Boolean)? = null

        private val jsonType = "application/json".toMediaType()

        /** One connection pool for the app; per-request timeouts derive from it. */
        private val sharedClient: OkHttpClient by lazy {
            val seconds = defaultTimeout.toLong()
            OkHttpClient.Builder()
                .connectTimeout(seconds, TimeUnit.SECONDS)
                .readTimeout(seconds, TimeUnit.SECONDS)
                .writeTimeout(seconds, TimeUnit.SECONDS)
                .build()
        }

        private fun OkHttpClient.withTimeout(seconds: Double): OkHttpClient {
            val millis = (seconds * 1000).toLong()
            return newBuilder()
                .connectTimeout(millis, TimeUnit.MILLISECONDS)
                .readTimeout(millis, TimeUnit.MILLISECONDS)
                .writeTimeout(millis, TimeUnit.MILLISECONDS)
                .build()
        }

        private val empty: String = "{}"

        private fun body(vararg pairs: Pair<String, JsonElement>): String = JsonObject(mapOf(*pairs)).toString()

        private fun <T> encode(serializer: SerializationStrategy<T>, value: T): String =
            encoder.encodeToString(serializer, value)

        /** The URL for a path relative to the API base, or null when it isn't one. */
        internal fun urlFor(baseURL: String, path: String): HttpUrl? = baseURL.toHttpUrlOrNull()?.resolve(path)

        /**
         * The error for an answer outside 2xx. A bare 404 means the server
         * isn't set up yet (the proxy answers without our JSON).
         */
        internal fun failure(status: Int, data: ByteArray): APIError {
            val message = runCatching { decoder.decodeFromString(ServerError.serializer(), data.decodeToString()).error }
                .getOrNull()
            if (status == 404 && message == null) return APIError.notReady()
            return APIError.server(status, message)
        }

        /** A name as the server stores it: trimmed, single spaces, at most [nameLimit] characters. */
        fun cleanedName(name: String): String {
            val words = mutableListOf<String>()
            val word = StringBuilder()
            for (ch in name) {
                if (ch.isWhitespace()) {
                    if (word.isNotEmpty()) words.add(word.toString())
                    word.setLength(0)
                } else {
                    word.append(ch)
                }
            }
            if (word.isNotEmpty()) words.add(word.toString())
            return words.joinToString(" ").prefixChars(nameLimit).trimmedSwift()
        }

        /** Best scores as the server takes them: game id to score, without the app-only daily game and empty scores, capped. */
        fun wireRecords(records: Map<GameID, Int>): Map<String, Int> {
            val out = LinkedHashMap<String, Int>()
            for ((game, best) in records) {
                if (game == GameID.daily || best <= 0) continue
                out[game.name] = minOf(best, recordCap)
            }
            return out
        }

        /** `care` or `care?after=<cursor>`, the cursor percent-encoded except letters and digits. */
        fun carePath(cursor: String?): String = afterPath("care", cursor)

        /** `<path>` or `<path>?after=<cursor>`, the cursor percent-encoded except letters and digits. */
        internal fun afterPath(path: String, cursor: String?): String {
            if (cursor.isNullOrEmpty()) return path
            val encoded = StringBuilder()
            var i = 0
            while (i < cursor.length) {
                val cp = cursor.codePointAt(i)
                val chars = Character.charCount(cp)
                if (Character.isLetterOrDigit(cp)) {
                    encoded.appendCodePoint(cp)
                } else {
                    for (b in cursor.substring(i, i + chars).toByteArray(Charsets.UTF_8)) {
                        encoded.append('%').append("%02X".format(b.toInt() and 0xFF))
                    }
                }
                i += chars
            }
            return "$path?after=$encoded"
        }

        /** Reads a 409 from `PUT /care/snapshot`. */
        fun careConflict(data: ByteArray): CareConflict? =
            runCatching { decoder.decodeFromString(CareConflict.serializer(), data.toString(Charsets.UTF_8)) }.getOrNull()
    }
}

/** Runs the call on OkHttp's threads and returns the body with the status. Cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Pair<ByteArray, Int> = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use { answer ->
                    val status = answer.code
                    (answer.body?.bytes() ?: ByteArray(0)) to status
                }
            } catch (e: IOException) {
                continuation.resumeWithException(e)
                return
            }
            continuation.resume(result)
        }
    })
}
