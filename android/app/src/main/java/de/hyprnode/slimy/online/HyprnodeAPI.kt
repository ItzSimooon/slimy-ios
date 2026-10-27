package de.hyprnode.slimy.online

import de.hyprnode.slimy.model.Accessories
import de.hyprnode.slimy.model.CareEvent
import de.hyprnode.slimy.model.CareKind
import de.hyprnode.slimy.model.Food
import de.hyprnode.slimy.model.GameID
import de.hyprnode.slimy.model.JsonDate
import de.hyprnode.slimy.model.Mood
import de.hyprnode.slimy.model.Needs
import de.hyprnode.slimy.model.Reaction
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.SlimeSnapshot
import de.hyprnode.slimy.model.SlimyJson
import de.hyprnode.slimy.model.Stage
import de.hyprnode.slimy.model.prefixChars
import de.hyprnode.slimy.model.trimmedSwift
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import java.time.Instant

// Client for the slime.hyprnode.de backend (server/ in this repo). The full
// contract is in server/README.md; keep the two in sync. The Android port of
// App/Online/HyprnodeAPI.swift: same wire types, same JSON field names, ISO
// 8601 dates (`SlimyJson.api`).
//
// Tolerant decoding: the Swift types decode leniently (unknown skins, moods,
// stages, foods, presets and reactions fall back to a default or to none;
// lists skip elements they can't read). The defaults below plus
// `SlimyJson.api` (`coerceInputValues`, `ignoreUnknownKeys`) cover the simple
// cases; lists that skip broken elements use `LossyListSerializer`.

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

// MARK: - Wire types

/**
 * A slime as friends see it. Swift decodes leniently: unknown skin -> lime,
 * unknown mood -> idle, unknown stage / outfit -> none.
 */
@Serializable
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
)

@Serializable
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

@Serializable
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
)

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
@Serializable
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
}

/** The best scores of you and your friends in one game, best first. */
@Serializable
data class LeaderboardGame(
    val game: GameID,
    val entries: List<LeaderboardEntry> = emptyList(),
) {
    val id: String get() = game.name

    /** Your own entry, if you played this game. */
    val mine: LeaderboardEntry? get() = entries.firstOrNull { it.me }

    /** 1-based place of an entry. Equal scores share a place. */
    fun rank(entry: LeaderboardEntry): Int = 1 + entries.count { it.best > entry.best }
}

/** `GET /friends/leaderboard`. Games this app version doesn't know are skipped, and so are `daily` and empty games. */
@Serializable
data class Leaderboard(
    val games: List<LeaderboardGame> = emptyList(),
)

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
@Serializable
data class ChatEmotion(
    val name: String,
    /** 0...1. */
    val intensity: Double = 0.5,
)

@Serializable
data class ChatReply(
    val reply: String,
    /** Optional animation the slime plays while saying it. */
    val reaction: Reaction? = null,
    /** The feeling the reply expresses. Missing from older servers, and when the AI gave none. */
    val emotion: ChatEmotion? = null,
)

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

/**
 * A care action the partner took, as `GET /care` lists it. Swift: the id may
 * come as a number; a missing owner name is "Your partner"; a non-finite
 * amount is none; a missing `createdAt` is `at`.
 */
@Serializable
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
}

/**
 * `GET /care`: the partner (null when not caring together), the newest shared
 * snapshot, and the partner's actions after the cursor. Decoded only.
 * [snapshotName] is not a JSON key: it is the `name` inside the snapshot,
 * read even when the snapshot itself can't be.
 */
@Serializable
data class CareStatus(
    val partner: CarePartner? = null,
    val snapshot: SlimeSnapshot? = null,
    @Transient val snapshotName: String? = null,
    val version: Int = 0,
    val events: List<CareEventWire> = emptyList(),
    val cursor: String? = null,
)

/** `POST /care/join`: who you now care with and their slime, if their phone already sent it. A missing partner role is "host". */
@Serializable
data class CareJoinResult(
    val partner: CarePartner,
    val snapshot: SlimeSnapshot? = null,
    @Transient val snapshotName: String? = null,
    val version: Int = 0,
)

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
@Serializable
data class CareConflict(
    val error: String? = null,
    val version: Int? = null,
    val snapshot: SlimeSnapshot? = null,
)

// MARK: - Client

/**
 * The slime.hyprnode.de client for one device token. Every endpoint is a
 * `suspend fun` that throws [APIError] (or a serialization exception for an
 * unreadable answer).
 */
// Implemented by the online/platform worker.
class HyprnodeAPI(val token: String? = null) {
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

    suspend fun register(ownerName: String): Registration = notConnected()

    suspend fun upload(profile: SlimeProfile): Unit = notConnected()

    suspend fun me(): Me = notConnected()

    suspend fun setPrivacy(privacy: Privacy): Privacy = notConnected()

    suspend fun deleteAccount(): Unit = notConnected()

    suspend fun newPairCode(): PairCode = notConnected()

    suspend fun join(code: String): JoinResult = notConnected()

    suspend fun requests(): RequestList = notConnected()

    suspend fun accept(requestID: String): Friend = notConnected()

    suspend fun decline(requestID: String): Unit = notConnected()

    /** Friends this app version can't read are skipped, not the whole list. */
    suspend fun friends(): List<Friend> = notConnected()

    suspend fun friend(id: String): FriendDetail = notConnected()

    suspend fun removeFriend(id: String): Unit = notConnected()

    suspend fun block(deviceID: String): Unit = notConnected()

    suspend fun unblock(deviceID: String): Unit = notConnected()

    suspend fun blocked(): List<BlockedDevice> = notConnected()

    suspend fun interact(id: String, kind: InteractionKind, food: Food?, key: String): InteractionResult = notConnected()

    suspend fun message(id: String, preset: MessagePreset, key: String): Unit = notConnected()

    suspend fun addCoop(id: String, points: Int, key: String): CoopStatus = notConnected()

    /** Challenges in games this app version doesn't have are skipped. */
    suspend fun challenges(): List<Challenge> = notConnected()

    suspend fun challenge(friendID: String, game: GameID, seed: ULong, score: Int, key: String): Challenge = notConnected()

    suspend fun answer(challengeID: String, score: Int, key: String): Challenge = notConnected()

    /** Sends a report to the Slimy team. `deviceId` is required unless the reason is `chat`; then `text` is the slime's reply. */
    suspend fun report(report: ReportBody, key: String): Unit = notConnected()

    /** Raises your best scores on the server; it never lowers them. */
    suspend fun uploadRecords(records: Map<String, Int>): Unit = notConnected()

    suspend fun leaderboard(): List<LeaderboardGame> = notConnected()

    suspend fun inbox(cursor: String?): InboxPage = notConnected()

    /** Waits up to [chatTimeout]; a timeout is a 504 with "Your slime took too long to answer. Try again in a moment." */
    suspend fun chat(message: String, history: List<ChatTurn>, context: ChatContext): ChatReply = notConnected()

    /** A code someone else enters to care for your slime with you (10 minutes, single use). 409 while you already care together. */
    suspend fun newCareCode(): PairCode = notConnected()

    /** Cares for the slime of the code's owner from now on. 404 unknown or used, 410 expired, 400 your own code, 409 already caring. */
    suspend fun joinCare(code: String): CareJoinResult = notConnected()

    /** The pair, the newest snapshot and the partner's actions after [cursor]. */
    suspend fun care(cursor: String?): CareStatus = notConnected()

    /** Sends one of your care actions to the partner's phone. The key makes a resend count once. */
    suspend fun sendCare(body: CareEventBody, key: String): Unit = notConnected()

    /** Stores the shared slime. [version] must be the server's version + 1; otherwise the answer is the server's newest state. */
    suspend fun putCareSnapshot(snapshot: SlimeSnapshot, version: Int): CareSnapshotResult = notConnected()

    /** Stops caring together, for both of you. */
    suspend fun leaveCare(): Unit = notConnected()

    private fun notConnected(): Nothing = throw APIError.offline()

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
        fun carePath(cursor: String?): String {
            if (cursor.isNullOrEmpty()) return "care"
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
            return "care?after=$encoded"
        }

        /** Reads a 409 from `PUT /care/snapshot`. */
        fun careConflict(data: ByteArray): CareConflict? =
            runCatching { decoder.decodeFromString(CareConflict.serializer(), data.toString(Charsets.UTF_8)) }.getOrNull()
    }
}
