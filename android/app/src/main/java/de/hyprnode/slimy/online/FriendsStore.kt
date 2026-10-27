package de.hyprnode.slimy.online

import android.content.Context
import android.net.Uri
import de.hyprnode.slimy.core.GameStore
import de.hyprnode.slimy.model.CareKind
import de.hyprnode.slimy.model.Food
import de.hyprnode.slimy.model.GameID
import de.hyprnode.slimy.model.GameResult
import de.hyprnode.slimy.model.JsonDate
import de.hyprnode.slimy.model.SlimeSnapshot
import de.hyprnode.slimy.model.UuidSerializer
import de.hyprnode.slimy.model.addingTimeInterval
import de.hyprnode.slimy.model.timeIntervalSince
import de.hyprnode.slimy.model.uuidString
import de.hyprnode.slimy.platform.FriendSlime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * Friends, greetings, challenges, the co-op garden, the friends leaderboard
 * and caring for one slime together. All of it is opt-in: nothing reaches
 * slime.hyprnode.de until the player taps "Go online" and agrees to the Terms
 * ([goOnline]), and nothing syncs after they go offline again. The Android
 * port of App/Online/FriendsStore.swift.
 *
 * One instance lives in `SlimyApplication.friends` (`LocalFriendsStore` in
 * Compose). Swift's `@Published private(set)` values are read-only
 * [StateFlow]s; its settable `@Published var`s are [MutableStateFlow]s
 * (their `didSet` work, such as saving [ownerName], happens when they change).
 * Plain getters are computed from the flows they read: collect those flows
 * to recompose. Values that depend on private state are flows themselves
 * ([profileNotice], [careNameNotice], [hasServerAccount]).
 */
// Implemented by the online/platform worker.
class FriendsStore(context: Context, private val store: GameStore) {
    private val context: Context = context.applicationContext

    sealed class Status {
        data object unknown : Status()
        data object online : Status()
        data class unavailable(val message: String) : Status()
    }

    /** A friend's slime visiting your home, until the time. */
    data class Visitor(val friend: FriendSlime, val until: Instant)

    private val _isOnline = MutableStateFlow(false)
    private val _friends = MutableStateFlow<List<Friend>>(emptyList())
    private val _requests = MutableStateFlow(RequestList())
    private val _challenges = MutableStateFlow<List<Challenge>>(emptyList())
    private val _blocked = MutableStateFlow<List<BlockedDevice>>(emptyList())
    private val _details = MutableStateFlow<Map<String, FriendDetail>>(emptyMap())
    private val _status = MutableStateFlow<Status>(Status.unknown)
    private val _pairCode = MutableStateFlow<PairCode?>(null)
    private val _privacy = MutableStateFlow(Privacy(acceptRequests = true, shareMood = true))
    private val _queue = MutableStateFlow<List<PendingAction>>(emptyList())
    private val _leaderboard = MutableStateFlow<List<LeaderboardGame>>(emptyList())
    private val _isLoadingLeaderboard = MutableStateFlow(false)
    private val _banNotice = MutableStateFlow<String?>(null)
    private val _profileNotice = MutableStateFlow<String?>(null)
    private val _visitor = MutableStateFlow<Visitor?>(null)
    private val _hasServerAccount = MutableStateFlow(false)
    private val _careCode = MutableStateFlow<PairCode?>(null)
    private val _pendingCare = MutableStateFlow<PendingCare?>(null)
    private val _careActivity = MutableStateFlow<List<CareActivity>>(emptyList())
    private val _careSince = MutableStateFlow<Instant?>(null)
    private val _careSyncedAt = MutableStateFlow<Instant?>(null)
    private val _careProblem = MutableStateFlow<String?>(null)
    private val _careNameNotice = MutableStateFlow<String?>(null)

    /** Registered with the server and not opted out. While false nothing is registered, uploaded, polled or sent. */
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()
    val friends: StateFlow<List<Friend>> = _friends.asStateFlow()
    val requests: StateFlow<RequestList> = _requests.asStateFlow()
    val challenges: StateFlow<List<Challenge>> = _challenges.asStateFlow()
    val blocked: StateFlow<List<BlockedDevice>> = _blocked.asStateFlow()
    val details: StateFlow<Map<String, FriendDetail>> = _details.asStateFlow()
    val status: StateFlow<Status> = _status.asStateFlow()
    val pairCode: StateFlow<PairCode?> = _pairCode.asStateFlow()
    val privacy: StateFlow<Privacy> = _privacy.asStateFlow()
    val queue: StateFlow<List<PendingAction>> = _queue.asStateFlow()

    /** Best scores of you and your friends, per game. */
    val leaderboard: StateFlow<List<LeaderboardGame>> = _leaderboard.asStateFlow()
    val isLoadingLeaderboard: StateFlow<Boolean> = _isLoadingLeaderboard.asStateFlow()

    /** The server stopped this device's online features. Shown until the next attempt to go online. */
    val banNotice: StateFlow<String?> = _banNotice.asStateFlow()

    /**
     * The server refused the names friends should see; syncs stop sending
     * them and this asks for new ones until the names change.
     */
    val profileNotice: StateFlow<String?> = _profileNotice.asStateFlow()

    /** The server refused a name the player just entered. Shown next to the name fields; views clear it. */
    val nameError = MutableStateFlow<String?>(null)

    /** A friend's slime visiting your home, until the time. */
    val visitor: StateFlow<Visitor?> = _visitor.asStateFlow()

    val lastError = MutableStateFlow<String?>(null)

    /** A challenge the player chose to play; the arcade opens with it. */
    val playChallenge = MutableStateFlow<Challenge?>(null)

    /** Something new arrived (shown as a dot on the Friends button). */
    val hasNews = MutableStateFlow(false)

    /** A friend code from an invite link, waiting to be connected in Friends. */
    val pendingInviteCode = MutableStateFlow<String?>(null)

    /** The name friends see. Saved when it changes. */
    val ownerName = MutableStateFlow("")

    /** Which friend's slime sits next to yours in the slime notification. Saved when it changes; the notification syncs. */
    val partnerID = MutableStateFlow<String?>(null)

    // MARK: Care together state

    /** A care code you made, kept until someone joins with it or a check after it ran out shows nobody did. */
    val careCode: StateFlow<PairCode?> = _careCode.asStateFlow()

    /** You joined someone's slime: waiting for your yes, or for their phone to send the slime. */
    val pendingCare: StateFlow<PendingCare?> = _pendingCare.asStateFlow()

    /** What your partner did for the shared slime, newest first, kept for a week. */
    val careActivity: StateFlow<List<CareActivity>> = _careActivity.asStateFlow()

    /** When caring together started, by this phone's clock. */
    val careSince: StateFlow<Instant?> = _careSince.asStateFlow()

    /** The last time caring together synced. */
    val careSyncedAt: StateFlow<Instant?> = _careSyncedAt.asStateFlow()

    /** Why caring together can't sync right now, while that lasts. */
    val careProblem: StateFlow<String?> = _careProblem.asStateFlow()

    /** The shared slime's name was refused and it still has it. Shown where the player can rename it. */
    val careNameNotice: StateFlow<String?> = _careNameNotice.asStateFlow()

    /** This phone has a device on the server (online or not), so there is server data that can be deleted. */
    val hasServerAccount: StateFlow<Boolean> = _hasServerAccount.asStateFlow()

    // MARK: Derived

    /** The friend you chose to sit next to your slime in the slime notification. */
    val partner: Friend?
        get() {
            val id = partnerID.value ?: return null
            return _friends.value.firstOrNull { it.id == id }
        }

    /** Friends for the slime notification, most important first: the chosen one, then the oldest friendships. */
    val islandFriends: List<FriendSlime>
        get() {
            val chosen = partnerID.value
            return _friends.value
                .sortedWith(compareBy<Friend> { it.id != chosen }.thenBy { it.since })
                .take(3)
                .map { islandSlime(it) }
        }

    fun friend(id: String?): Friend? {
        if (id == null) return null
        return _friends.value.firstOrNull { it.id == id }
    }

    val openChallenges: List<Challenge> get() = _challenges.value.filter { it.isOpenForMe }

    /** Challenges you sent that your friend hasn't played yet. */
    val waitingChallenges: List<Challenge>
        get() {
            val now = Instant.now()
            return _challenges.value.filter { it.sentByMe && it.theirScore == null && it.expiresAt > now }
        }

    /** Challenges both of you played, newest first. */
    val finishedChallenges: List<Challenge>
        get() = _challenges.value
            .filter { it.myScore != null && it.theirScore != null }
            .sortedByDescending { it.answeredAt ?: it.createdAt }

    /** The friend code is still usable. */
    val activePairCode: PairCode?
        get() = _pairCode.value?.takeIf { it.expiresAt > Instant.now() }

    /** Your slime is shared with a partner right now. */
    val isSharing: Boolean get() = store.isSharing

    /** The care code while it can still be used. */
    val activeCareCode: PairCode?
        get() = _careCode.value?.takeIf { it.expiresAt > Instant.now() }

    /** A care code is out there, or you joined someone and wait. */
    val isWaitingForCare: Boolean
        get() = !isSharing && (_careCode.value != null || _pendingCare.value != null)

    // MARK: Server access

    /**
     * A client for this device. Throws [NotOnlineError] while the player
     * isn't online, so nothing is sent without their consent.
     */
    suspend fun authorizedAPI(): HyprnodeAPI {
        if (!_isOnline.value) throw NotOnlineError()
        return HyprnodeAPI(token = null)
    }

    /** For other stores that call the server through [authorizedAPI]: lets a ban or a forgotten device take effect here too. */
    fun handleServerError(error: Throwable) {}

    // MARK: Going online

    /**
     * Registers this device (if it has none yet) and uploads the slime's
     * profile. Call only after the player agreed to the Terms. The owner name
     * must be set first. Returns false and sets [lastError] when it fails;
     * when the server refuses a name, also [nameError].
     */
    suspend fun goOnline(): Boolean = false

    /** Stops all syncing. Friends, the queue and the token stay on the phone. Caring together stays set up. */
    fun goOffline() {}

    // MARK: Sync

    suspend fun refresh() {}

    /** Changes the name friends see. Offline it is only stored. A refused name sets [nameError] and keeps the previous one. */
    suspend fun rename(name: String): Boolean = false

    /** Sends the slime's current name and look to friends right away. A refused name sets [nameError]. */
    suspend fun uploadProfileNow(): Boolean = false

    // MARK: Pairing

    /** A fresh code to give to a friend (10 minutes, single use). */
    suspend fun makeCode(): Boolean = false

    /** Connects with the owner of a friend's code. Returns a line to show on success. */
    suspend fun join(code: String): String? = null

    suspend fun accept(request: FriendRequest): Boolean = false

    /** Declines a request to you, or withdraws one you sent. */
    suspend fun decline(request: FriendRequest): Boolean = false

    suspend fun remove(friend: Friend): Boolean = false

    suspend fun block(deviceID: String): Boolean = false

    suspend fun unblock(device: BlockedDevice): Boolean = false

    suspend fun loadBlocked() {}

    suspend fun loadDetail(friend: Friend) {}

    /** Changes a privacy setting on the server: `setPrivacy { it.copy(shareMood = false) }`. */
    suspend fun setPrivacy(change: (Privacy) -> Privacy): Boolean = false

    /**
     * Deletes everything the server knows about this phone, then goes offline
     * and forgets the token, the name, the queue and the friends. The slime
     * stays. Works while banned too.
     */
    suspend fun deleteAccount(): Boolean = false

    // MARK: Reports

    /**
     * Sends a report to the Slimy team. It doesn't block anyone. [deviceID]
     * is needed unless the reason is `chat`, where [text] is the slime's reply.
     */
    suspend fun report(deviceID: String?, reason: ReportReason, note: String?, text: String?): Boolean = false

    // MARK: Records and leaderboard

    /** Sends best scores the server doesn't have yet. */
    suspend fun uploadRecords(records: Map<GameID, Int>) {}

    suspend fun loadLeaderboard() {}

    // MARK: Invites

    /**
     * Takes a friend code from an invite link (https://slime.hyprnode.de/f/CODE
     * or slimy://f/CODE). Returns false for other links.
     */
    fun openInvite(url: String): Boolean {
        val code = FriendInvite.code(url) ?: return false
        pendingInviteCode.value = code
        hasNews.value = true
        return true
    }

    fun openInvite(url: Uri): Boolean = openInvite(url.toString())

    // MARK: Hugs, visits, gifts, messages

    /** Returns the line to show, or null if it failed. */
    suspend fun interact(friend: Friend, kind: InteractionKind, food: Food? = null): String? = null

    suspend fun send(preset: MessagePreset, friend: Friend): String? = null

    // MARK: Arcade together

    /**
     * A finished round adds to the co-op garden you share with the friend in
     * your slime notification (or your oldest friend).
     */
    fun contributeCoop(result: GameResult) {}

    suspend fun challenge(friend: Friend, result: GameResult, seed: ULong): String? = null

    suspend fun answer(challenge: Challenge, score: Int) {}

    // MARK: Care together

    /** A fresh code for someone to care for your slime with you (10 minutes, single use). */
    suspend fun makeCareCode(): Boolean = false

    /** Joins the slime behind a care code. On success [pendingCare] holds who it belongs to; the slime moves here on [confirmJoin]. */
    suspend fun joinCare(code: String): Boolean = false

    /** You said yes to the slime you joined: it moves onto this phone now, or as soon as your partner's phone has sent it. */
    suspend fun confirmJoin() {}

    /** Takes back joining someone's slime before it moved here. */
    suspend fun cancelJoin(): Boolean = false

    /** Stops caring together for both of you. The joiner gets their own slime back; the host keeps the slime. */
    suspend fun leaveCare(): Boolean = false

    /**
     * Brings caring together up to date: who you care with, your partner's
     * actions, and the newest snapshot. [discover] checks once even when this
     * phone knows of no pairing.
     */
    suspend fun syncCare(discover: Boolean = false) {}

    /** While the app is on screen, caring together syncs every 20 seconds (every 12 while waiting for a partner). */
    fun startCarePolling() {}

    fun stopCarePolling() {}

    /** The app left the screen: polling stops, and a changed shared slime is sent right away. */
    fun enterBackground() {}

    // MARK: Offline queue

    /** Sends what waited, oldest first. Does nothing while offline. */
    suspend fun flushQueue() {}

    companion object {
        const val termsURL = "https://slime.hyprnode.de/terms"
        const val privacyURL = "https://slime.hyprnode.de/privacy"
        const val supportURL = "https://slime.hyprnode.de/support"

        /** The server's limit. */
        const val maxFriends = 20

        /** Seconds between care syncs while the app is on screen. */
        const val carePollInterval = 20.0

        /** Seconds between checks while a code waits for someone, or you wait for your partner's slime. */
        const val careWaitInterval = 12.0

        /** A refresh skips `GET /care` when one answered this recently (seconds). */
        const val careRecentSync = 10.0

        /** Snapshots go out at most this often (seconds). */
        const val careSnapshotSpacing = 60.0

        /** Petting is sent to the partner at most this often (seconds). */
        const val carePetSpacing = 30.0

        fun islandSlime(f: Friend): FriendSlime = FriendSlime(
            id = f.id, name = f.slime.name, owner = f.ownerName, skin = f.slime.skin, stage = f.stage,
            mood = f.slime.mood, equipped = f.slime.equipped,
        )
    }
}

/** A write waiting for the network. The key makes a resend count once. */
@Serializable
data class PendingAction(
    val kind: Kind,
    val coins: Int = 0,
    val createdAt: JsonDate = Instant.now(),
    @Serializable(with = UuidSerializer::class)
    val id: UUID = UUID.randomUUID(),
) {
    @Serializable
    sealed class Kind {
        @Serializable
        data class interaction(val friendID: String, val kind: InteractionKind, val food: Food? = null) : Kind()

        @Serializable
        data class message(val friendID: String, val preset: MessagePreset) : Kind()

        @Serializable
        data class coop(val friendID: String, val points: Int) : Kind()

        @Serializable
        data class challenge(val friendID: String, val game: GameID, val seed: String, val score: Int) : Kind()

        @Serializable
        data class answer(val challengeID: String, val score: Int) : Kind()

        /** One of your care actions for the slime you share: kind, food, game, amount and when it happened. */
        @Serializable
        data class care(
            val kind: CareKind,
            val food: Food? = null,
            val game: GameID? = null,
            val amount: Double? = null,
            val at: JsonDate,
        ) : Kind()
    }

    val key: String get() = id.uuidString

    val friendID: String?
        get() = when (kind) {
            is Kind.interaction -> kind.friendID
            is Kind.message -> kind.friendID
            is Kind.coop -> kind.friendID
            is Kind.challenge -> kind.friendID
            is Kind.answer, is Kind.care -> null
        }

    val isCare: Boolean get() = kind is Kind.care
}

/** Joining someone's slime, until it has moved onto this phone. */
@Serializable
data class PendingCare(
    val partnerID: String,
    val partnerName: String,
    /** The slime's name, once their phone has sent it. */
    val slimeName: String? = null,
    val snapshot: SlimeSnapshot? = null,
    val version: Int,
    val cursor: String? = null,
    /** You said yes; only the slime itself is still on its way. */
    val confirmed: Boolean,
    val joinedAt: JsonDate,
)

/** One thing your partner did for the shared slime, as the activity list shows it: "Anna fed Blob · 5 min ago". */
@Serializable
data class CareActivity(
    val id: String,
    val kind: CareKind,
    val food: Food? = null,
    val game: GameID? = null,
    val by: String,
    /** The shared slime's name when it happened. */
    val slimeName: String,
    val at: JsonDate,
) {
    constructor(wire: CareEventWire, slimeName: String) : this(
        id = wire.id, kind = wire.kind, food = wire.food, game = wire.game, by = wire.ownerName,
        slimeName = slimeName, at = wire.at,
    )

    /** "Anna fed Blob". */
    val text: String get() = kind.sentence(by, slimeName)

    /** What it was: the food or the game. */
    val detail: String? get() = food?.title ?: game?.title

    /** A pixel item for the food or game, if there is one. */
    val sprite: String? get() = food?.sprite ?: game?.icon

    /** "Anna fed Blob · 5 min ago". */
    fun line(now: Instant): String = "$text · ${ago(at, now)}"

    companion object {
        /** Entries are kept on this phone for a week (seconds). */
        const val keptFor = 7.0 * 86400
        const val limit = 100

        /** How long ago, in a few words. */
        fun ago(date: Instant, now: Instant): String {
            val seconds = now.timeIntervalSince(date)
            if (seconds < 60) return "just now"
            val minutes = (seconds / 60).toInt()
            if (minutes < 60) return "$minutes min ago"
            val hours = minutes / 60
            if (hours < 24) return if (hours == 1) "1 hour ago" else "$hours hours ago"
            val days = hours / 24
            return if (days == 1) "yesterday" else "$days days ago"
        }

        /** New entries added to the list: each once, newest first, nothing older than a week, at most [limit]. */
        fun merged(new: List<CareActivity>, old: List<CareActivity>, now: Instant): List<CareActivity> {
            val cutoff = now.addingTimeInterval(-keptFor)
            val seen = HashSet<String>()
            return (new + old)
                .filter { it.at > cutoff && seen.add(it.id) }
                .sortedByDescending { it.at }
                .take(limit)
        }
    }
}

/** Friend codes and invite links. https://slime.hyprnode.de/f/CODE opens the website's invite page, slimy://f/CODE opens the app. */
object FriendInvite {
    const val length = 6

    /** The characters the server uses for codes (no 0/O, 1/I/L). */
    val alphabet: Set<Char> = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toSet()

    /** A code as typed or pasted: upper case, letters and digits only, at most six characters. */
    fun sanitize(text: String): String =
        text.uppercase().filter { it.code < 128 && it.isLetterOrDigit() }.take(length)

    fun url(code: String): String = "https://slime.hyprnode.de/f/$code"

    /** What the share sheet sends. */
    fun message(code: String): String = "Be my slime friend in Slimy! Code: $code ${url(code)}"

    /** The code in an invite link, or null when the link isn't one. */
    fun code(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val parts = (uri.path ?: "").split("/").filter { it.isNotEmpty() }
        val host = uri.host?.lowercase()
        val candidate = when (uri.scheme?.lowercase()) {
            "https" -> if (host == "slime.hyprnode.de" && parts.size == 2 && parts[0] == "f") parts[1] else return null
            "slimy" -> if (host == "f" && parts.size == 1) parts[0] else return null
            else -> return null
        }
        val code = candidate.uppercase()
        if (code.length != length || !code.all { it in alphabet }) return null
        return code
    }
}
