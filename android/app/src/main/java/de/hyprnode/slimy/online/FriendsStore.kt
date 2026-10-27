package de.hyprnode.slimy.online

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import de.hyprnode.slimy.SlimyApplication
import de.hyprnode.slimy.core.GameStore
import de.hyprnode.slimy.model.CareEvent
import de.hyprnode.slimy.model.CareKind
import de.hyprnode.slimy.model.CareLink
import de.hyprnode.slimy.model.DistantPast
import de.hyprnode.slimy.model.Emotion
import de.hyprnode.slimy.model.Food
import de.hyprnode.slimy.model.GameID
import de.hyprnode.slimy.model.GameResult
import de.hyprnode.slimy.model.JsonDate
import de.hyprnode.slimy.model.LineCategory
import de.hyprnode.slimy.model.LossyListSerializer
import de.hyprnode.slimy.model.Need
import de.hyprnode.slimy.model.Reaction
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.Slime
import de.hyprnode.slimy.model.SlimeSnapshot
import de.hyprnode.slimy.model.SlimyJson
import de.hyprnode.slimy.model.UuidSerializer
import de.hyprnode.slimy.model.Accessories
import de.hyprnode.slimy.model.addingTimeInterval
import de.hyprnode.slimy.model.friendMoment
import de.hyprnode.slimy.model.madeFriend
import de.hyprnode.slimy.model.newUUIDString
import de.hyprnode.slimy.model.prefixChars
import de.hyprnode.slimy.model.receiveGift
import de.hyprnode.slimy.model.timeIntervalSince
import de.hyprnode.slimy.model.trimmedSwift
import de.hyprnode.slimy.model.uuidString
import de.hyprnode.slimy.platform.FriendSlime
import de.hyprnode.slimy.platform.IslandController
import de.hyprnode.slimy.platform.Notifier
import de.hyprnode.slimy.platform.SharedMoment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Friends, greetings, challenges, the co-op garden, the friends leaderboard
 * and caring for one slime together. All of it is opt-in: nothing reaches
 * slime.hyprnode.de until the player taps "Go online" and agrees to the Terms
 * ([goOnline]), and nothing syncs after they go offline again. The server
 * decides who is friends and who cares together. Writes that fail because
 * the network is gone wait in a queue and are sent later with the same
 * idempotency key, so nothing counts twice. The Android port of
 * App/Online/FriendsStore.swift.
 *
 * One instance lives in `SlimyApplication.friends` (`LocalFriendsStore` in
 * Compose). Swift's `@Published private(set)` values are read-only
 * [StateFlow]s; its settable `@Published var`s are [MutableStateFlow]s
 * (their `didSet` work, such as saving [ownerName], happens when they change).
 * Plain getters are computed from the flows they read: collect those flows
 * to recompose. Values that depend on private state are flows themselves
 * ([profileNotice], [careNameNotice], [hasServerAccount]).
 *
 * Like the Swift store (`@MainActor`) all of its state lives on the main
 * thread: suspend functions move there themselves, so they can be called
 * from any coroutine. The app calls [onForeground] and [onBackground] from
 * its lifecycle.
 */
class FriendsStore(context: Context, private val store: GameStore) {
    private val context: Context = context.applicationContext

    sealed class Status {
        data object unknown : Status()
        data object online : Status()
        data class unavailable(val message: String) : Status()
    }

    /** A friend's slime visiting your home, until the time. */
    data class Visitor(val friend: FriendSlime, val until: Instant)

    private val prefs: SharedPreferences = this.context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val vault = TokenVault.forContext(this.context)
    /**
     * Where the store's own work runs, on the main thread. Screens launch
     * actions here too, so a request that is on its way finishes even when
     * the screen closes (like a Swift `Task {}`).
     */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

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

    // MARK: Private state

    private var refreshing = false
    private var connecting = false
    private var flushing = false
    private var deletingAccount = false
    private var careSyncing = false
    private var carePushing = false

    /** The shared slime changed here since the last snapshot was sent. Kept across launches, so changes made offline still go out. */
    private var careDirty: Boolean = prefs.getBoolean(Keys.careDirty, false)
        set(value) {
            if (field == value) return
            field = value
            prefs.edit().putBoolean(Keys.careDirty, value).apply()
        }
    private var lastCarePush: Instant = DistantPast
    private var carePushTask: Job? = null
    private var carePoll: Job? = null
    private var backgroundPush: Job? = null

    /** Set while the partner's actions or snapshot change the slime, so those changes aren't sent back as this phone's. */
    private var applyingRemote = false

    /** The shared look last seen, so only a change made here marks the slime for the next snapshot. */
    private var lastLook: SharedLook = SharedLook(store.state.value.slime)
    private var lastPetSent: Instant = DistantPast

    /** The slime name the server refused in a snapshot (400, which is final), so it isn't sent again until it changes. */
    private var refusedSnapshotName: String? = null

    /** What the server said when it refused that name. */
    private var refusedSnapshotMessage: String? = null

    /** One sync looks for a pairing this phone doesn't know about (after the server said you're already caring together). */
    private var careDiscovery = false

    /** Goes up whenever joining, adopting or ending changes the pairing here, so a `GET /care` answer from before that is ignored. */
    private var careEpoch = 0

    /** Stop Sharing or Don't Join is on its way to the server. */
    private var careLeaving = false

    /** Requests and challenges already counted as news. */
    private var knownNews: Set<String> = emptySet()

    /** Kept encrypted in [vault]; this copy saves a read per request. */
    private var token: String? = vault.load()
        set(value) {
            if (field == value) return
            field = value
            vault.save(value)
            _hasServerAccount.value = value != null
        }

    /** The player agreed to go online and didn't opt out since. */
    private var optedIn: Boolean
        get() = prefs.getBoolean(Keys.online, false)
        set(value) {
            prefs.edit().putBoolean(Keys.online, value).apply()
        }

    private var inboxCursor: String?
        get() = prefs.getString(Keys.cursor, null)
        set(value) {
            prefs.edit().putString(Keys.cursor, value).apply()
        }

    /** The best scores the server already has, so each is sent once. */
    private var uploadedRecords: Map<String, Int>
        get() = prefs.getString(Keys.records, null)?.let {
            runCatching { SlimyJson.storage.decodeFromString(recordsSerializer, it) }.getOrNull()
        } ?: emptyMap()
        set(value) {
            prefs.edit().putString(Keys.records, SlimyJson.storage.encodeToString(recordsSerializer, value)).apply()
        }

    /** The owner and slime names the server refused last, so syncs don't send them again and again. */
    private var refusedNames: List<String>? = null
        set(value) {
            field = value
            if (value == null) {
                prefs.edit().remove(Keys.refusedNames).apply()
            } else {
                prefs.edit().putString(Keys.refusedNames, SlimyJson.storage.encodeToString(namesSerializer, value)).apply()
            }
            updateProfileNotice()
        }

    private object Keys {
        const val owner = "slimy.friends.owner"
        const val partner = "slimy.friends.partner"
        const val online = "slimy.friends.online"
        const val cursor = "slimy.friends.cursor"
        const val queue = "slimy.friends.queue"
        const val cache = "slimy.friends.cache"
        const val records = "slimy.friends.records"
        const val refusedNames = "slimy.friends.refusedNames"
        const val careCode = "slimy.care.code"
        const val pendingCare = "slimy.care.pending"
        const val careActivity = "slimy.care.activity"
        const val careSince = "slimy.care.since"
        const val careDirty = "slimy.care.dirty"
    }

    init {
        ownerName.value = prefs.getString(Keys.owner, null) ?: ""
        _hasServerAccount.value = token != null
        _isOnline.value = optedIn && token != null
        partnerID.value = prefs.getString(Keys.partner, null)

        // Actions this version can't read are dropped, not the queue.
        _queue.value = prefs.getString(Keys.queue, null)?.let {
            runCatching { SlimyJson.storage.decodeFromString(LossyListSerializer(PendingAction.serializer()), it) }.getOrNull()
        } ?: emptyList()
        // Last known friends, so the slime notification and the list work without network.
        _friends.value = prefs.getString(Keys.cache, null)?.let {
            runCatching { HyprnodeAPI.decoder.decodeFromString(ListSerializer(Friend.serializer()), it) }.getOrNull()
        } ?: emptyList()
        refusedNames = prefs.getString(Keys.refusedNames, null)?.let {
            runCatching { SlimyJson.storage.decodeFromString(namesSerializer, it) }.getOrNull()
        }

        _careCode.value = loadCareValue(Keys.careCode, PairCode.serializer())
        _pendingCare.value = loadCareValue(Keys.pendingCare, PendingCare.serializer())
        val activity = loadCareValue(Keys.careActivity, LossyListSerializer(CareActivity.serializer())) ?: emptyList()
        _careActivity.value = CareActivity.merged(emptyList(), activity, Instant.now())
        _careSince.value = if (prefs.contains(Keys.careSince)) Instant.ofEpochMilli(prefs.getLong(Keys.careSince, 0)) else null
        updateProfileNotice()
        updateCareNameNotice()

        HyprnodeAPI.hasNetwork = { hasNetwork() }

        // Every care action on this phone goes to the partner's, and a
        // changed name or look goes out with the next snapshot.
        store.onCare = { event -> sendCare(event) }
        scope.launch { store.state.collect { gameChanged() } }
        scope.launch {
            ownerName.collect { name ->
                if (prefs.getString(Keys.owner, "") != name) prefs.edit().putString(Keys.owner, name).apply()
                updateProfileNotice()
            }
        }
        scope.launch {
            partnerID.collect { id ->
                if (prefs.getString(Keys.partner, null) != id) {
                    prefs.edit().putString(Keys.partner, id).apply()
                    syncIsland()
                }
            }
        }
    }

    private val api: HyprnodeAPI get() = HyprnodeAPI(token = token)

    /** The slime notification, created on first use by the app. */
    private val island: IslandController? get() = (context as? SlimyApplication)?.island

    /** Slimy is on screen. */
    private val inForeground: Boolean get() = (context as? SlimyApplication)?.isInForeground ?: true

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

    /** Something about caring together needs the server. */
    private val wantsCareSync: Boolean
        get() = isSharing || _careCode.value != null || _pendingCare.value != null || careDiscovery

    /** The name friends see, never empty. */
    private val displayName: String
        get() = HyprnodeAPI.cleanedName(ownerName.value).ifEmpty { "Slime friend" }

    /** The slime's name as friends see it, never empty. */
    private val slimeDisplayName: String
        get() {
            val cleaned = HyprnodeAPI.cleanedName(store.state.value.slime.name)
            return cleaned.prefixChars(HyprnodeAPI.slimeNameLimit).trimmedSwift().ifEmpty { "Slime" }
        }

    /** Both names as the profile upload sends them. */
    private val currentNames: List<String> get() = listOf(displayName, slimeDisplayName)

    // MARK: App lifecycle

    /** The app came to the front: caring together polls, and everything syncs once. */
    fun onForeground() {
        onMainThread {
            startCarePolling()
            scope.launch { refresh() }
        }
    }

    /** The app left the screen ([enterBackground]). */
    fun onBackground() {
        onMainThread { enterBackground() }
    }

    // MARK: Server access

    /**
     * A client for this device. Throws [NotOnlineError] while the player
     * isn't online, so nothing is sent without their consent.
     */
    suspend fun authorizedAPI(): HyprnodeAPI = onMain {
        reloadTokenIfNeeded()
        if (!_isOnline.value) throw NotOnlineError()
        if (token == null) register(whileOnline = true)
        api
    }

    /** For other stores that call the server through [authorizedAPI]: lets a ban or a forgotten device take effect here too. */
    fun handleServerError(error: Throwable) {
        val apiError = error as? APIError ?: return
        onMainThread {
            if (apiError.isBanned) {
                handleBanned(apiError)
            } else if (apiError.isUnauthorized) {
                scope.launch { refresh() }
            }
        }
    }

    // MARK: Going online

    /**
     * Registers this device (if it has none yet) and uploads the slime's
     * profile. Call only after the player agreed to the Terms. The owner name
     * must be set first. Returns false and sets [lastError] when it fails;
     * when the server refuses a name, also [nameError].
     */
    suspend fun goOnline(): Boolean = onMain {
        if (connecting || deletingAccount) return@onMain false
        val name = HyprnodeAPI.cleanedName(ownerName.value)
        if (name.isEmpty()) {
            lastError.value = "Please enter your name."
            return@onMain false
        }
        connecting = true
        try {
            if (ownerName.value != name) ownerName.value = name
            _banNotice.value = null
            lastError.value = null
            nameError.value = null
            try {
                connect()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (!(e is APIError && e.isUnauthorized)) return@onMain failedToConnect(e)
                // The server forgot this device (its data was deleted): start over.
                resetServerIdentity()
                try {
                    connect()
                } catch (again: Exception) {
                    if (again is CancellationException) throw again
                    return@onMain failedToConnect(again)
                }
            }
            optedIn = true
            _isOnline.value = true
            _status.value = Status.online
            scope.launch { refresh() }
            true
        } finally {
            connecting = false
        }
    }

    /**
     * Stops all syncing. Friends, the queue and the token stay on the phone,
     * so going online again picks up where it left off. Requests and
     * challenges need the server to answer, so they go until then. Caring
     * together stays set up; it just doesn't sync while offline.
     */
    fun goOffline() {
        optedIn = false
        _isOnline.value = false
        _status.value = Status.unknown
        _pairCode.value = null
        _requests.value = RequestList()
        _challenges.value = emptyList()
        _leaderboard.value = emptyList()
        knownNews = emptySet()
        carePushTask?.cancel()
        carePushTask = null
        _careProblem.value = null
    }

    private suspend fun connect() {
        // The vault may not have answered at launch. Its token keeps this
        // phone's device, friends and caring together; a new device would not.
        if (token == null) vault.load()?.let { token = it }
        if (token == null) {
            resetServerIdentity()
            register()
        }
        uploadProfile()
    }

    private fun failedToConnect(error: Throwable): Boolean {
        if (error is APIError) {
            if (error.isBanned) _banNotice.value = banMessage(error)
            if (error.isRefused) nameError.value = error.errorDescription
        }
        lastError.value = error.shownText
        return false
    }

    /**
     * Registers a new device. [whileOnline] is for syncs and other stores: if
     * the player went offline or deleted their data while the request was on
     * its way, the new device is deleted again instead of kept.
     */
    private suspend fun register(whileOnline: Boolean = false) {
        val registration = HyprnodeAPI().register(displayName)
        if ((whileOnline && !_isOnline.value) || deletingAccount || token != null) {
            // Gone offline meanwhile, or another call registered first.
            try {
                HyprnodeAPI(registration.token).deleteAccount()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
            if (token == null) throw NotOnlineError()
            return
        }
        token = registration.token
    }

    /** A vault read can fail for a moment; try again before treating the device as offline. */
    private fun reloadTokenIfNeeded() {
        if (token != null || !optedIn || deletingAccount) return
        val stored = vault.load() ?: return
        token = stored
        _isOnline.value = true
    }

    /** Forgets everything that belonged to the server's old device for this phone. Coins paid for gifts that never left come back. */
    private fun resetServerIdentity() {
        for (action in _queue.value) refund(action)
        token = null
        inboxCursor = null
        uploadedRecords = emptyMap()
        _queue.value = emptyList()
        saveQueue()
        _friends.value = emptyList()
        cacheFriends()
        _requests.value = RequestList()
        _challenges.value = emptyList()
        _details.value = emptyMap()
        _blocked.value = emptyList()
        _leaderboard.value = emptyList()
        _pairCode.value = null
        knownNews = emptySet()
        _visitor.value = null
        if (partnerID.value != null) partnerID.value = null
        // A device the server no longer has can't be caring together.
        endCareLocally(notice = null)
    }

    /** The server banned this device: stop syncing and say why. A ban also ends caring together on the server. */
    private fun handleBanned(error: APIError) {
        goOffline()
        endCareLocally(notice = null)
        _banNotice.value = banMessage(error)
        lastError.value = error.errorDescription
    }

    /** Shows a failed action's reason. A ban also takes the device offline; a device the server forgot (401) registers again in the background. */
    private fun fail(error: Throwable) {
        if (error !is APIError) {
            lastError.value = error.shownText
            return
        }
        when {
            error.isBanned -> handleBanned(error)
            error.isUnauthorized -> {
                lastError.value = "Slimy is reconnecting to its server. Try again in a moment."
                scope.launch { refresh() }
            }
            else -> lastError.value = error.errorDescription
        }
    }

    /** Like [fail], but a refused name (400) is also shown next to the name fields. */
    private fun failNames(error: Throwable) {
        if (error is APIError && error.isRefused) {
            nameError.value = error.errorDescription
            lastError.value = error.errorDescription
        } else {
            fail(error)
        }
    }

    private fun requireOnline(): Boolean {
        reloadTokenIfNeeded()
        if (!_isOnline.value) {
            lastError.value = "Go online in Friends first."
            return false
        }
        return true
    }

    /** Stops a sync whose answers arrive after the player went offline or deleted their data. */
    private fun checkOnline() {
        if (!_isOnline.value) throw NotOnlineError()
    }

    // MARK: Sync

    suspend fun refresh(): Unit = onMain {
        reloadTokenIfNeeded()
        if (!_isOnline.value || refreshing || deletingAccount) return@onMain
        refreshing = true
        try {
            try {
                sync()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is APIError && e.isUnauthorized) {
                    // The server no longer knows this device. If the player is
                    // still online (not deleting their data), register a fresh one.
                    if (!_isOnline.value || deletingAccount) return@onMain
                    resetServerIdentity()
                    try {
                        sync()
                    } catch (again: Exception) {
                        if (again is CancellationException) throw again
                        syncFailed(again)
                    }
                } else {
                    syncFailed(e)
                }
            }
            _visitor.value?.let { if (it.until < Instant.now()) _visitor.value = null }
            // Coming to the front also starts the care poll; one `GET /care`
            // within a few seconds is enough.
            val careFresh = _careSyncedAt.value?.let { Instant.now().timeIntervalSince(it) < careRecentSync } ?: false
            if (!careFresh) syncCare()
            island?.sync()
        } finally {
            refreshing = false
        }
    }

    private suspend fun sync() {
        checkOnline()
        val names = currentNames
        val namesRefused = refusedNames == names
        if (token == null) {
            // A new device needs an owner name the server takes.
            if (namesRefused) {
                _status.value = Status.unavailable("Friends can't sync until you choose different names.")
                return
            }
            try {
                register(whileOnline = true)
            } catch (e: APIError) {
                if (e.isRefused) refusedNames = names
                throw e
            }
        }
        if (!namesRefused) {
            try {
                uploadProfile()
            } catch (e: APIError) {
                if (!e.isRefused) throw e
                refusedNames = names
            }
            checkOnline()
        }
        val me = api.me()
        checkOnline()
        _privacy.value = me.privacy
        flushQueue()
        val fetchedFriends = api.friends()
        checkOnline()
        _friends.value = fetchedFriends
        cacheFriends()
        partnerID.value?.let { id -> if (fetchedFriends.none { it.id == id }) partnerID.value = null }
        val fetchedRequests = api.requests()
        checkOnline()
        _requests.value = fetchedRequests
        val fetchedChallenges = api.challenges()
        checkOnline()
        _challenges.value = fetchedChallenges
        readInbox()
        syncRecords()
        checkOnline()
        _status.value = Status.online

        val news = (_requests.value.incoming.map { it.id } + openChallenges.map { it.id }).toSet()
        if ((news - knownNews).isNotEmpty()) hasNews.value = true
        knownNews = news
    }

    private fun syncFailed(error: Throwable) {
        if (error is NotOnlineError) return
        if (error is APIError && error.isBanned) {
            handleBanned(error)
        } else {
            _status.value = Status.unavailable(error.shownText)
        }
    }

    /** Uploads the slime's profile with both names. When the server takes it, a notice about refused names goes away. */
    private suspend fun uploadProfile() {
        val slime = store.state.value.slime
        api.upload(
            SlimeProfile(
                ownerName = displayName, name = slimeDisplayName, skin = slime.skin, level = slime.level,
                stage = slime.stage, mood = slime.mood, happiness = slime.needs.average.toInt(),
                equipped = slime.equipped.copy(), updatedAt = Instant.now(),
            ),
        )
        refusedNames = null
    }

    /** Changes the name friends see. Offline it is only stored. A refused name sets [nameError] and keeps the previous one. */
    suspend fun rename(name: String): Boolean = onMain {
        val cleaned = HyprnodeAPI.cleanedName(name)
        if (cleaned.isEmpty()) {
            lastError.value = "Please enter your name."
            return@onMain false
        }
        val previous = ownerName.value
        ownerName.value = cleaned
        nameError.value = null
        if (!_isOnline.value) return@onMain true
        try {
            if (token == null) register(whileOnline = true)
            uploadProfile()
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ownerName.value = previous
            failNames(e)
            false
        }
    }

    /** Sends the slime's current name and look to friends right away. A refused name sets [nameError]. */
    suspend fun uploadProfileNow(): Boolean = onMain {
        if (!requireOnline()) return@onMain false
        nameError.value = null
        try {
            if (token == null) register(whileOnline = true)
            uploadProfile()
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            failNames(e)
            false
        }
    }

    private fun cacheFriends() {
        prefs.edit()
            .putString(Keys.cache, HyprnodeAPI.encoder.encodeToString(ListSerializer(Friend.serializer()), _friends.value))
            .apply()
    }

    private suspend fun readInbox() {
        var page = api.inbox(inboxCursor)
        var rounds = 0
        while (true) {
            checkOnline()
            for (event in page.events) handle(event)
            page.cursor?.let { inboxCursor = it }
            rounds += 1
            if (page.events.size != 50 || rounds >= 5) break
            page = api.inbox(inboxCursor)
        }
    }

    /** What a friend did. Each kind only shows something or gives food; nothing a friend sends can change settings or act for you. */
    private fun handle(event: InboxEvent) {
        val name = event.friendName ?: friend(event.friendId)?.slime?.name ?: "A slime"
        val earlierToast = store.toast.value?.id
        // Love between slimes is gone; a friend on an older app can still send it, and it is simply skipped.
        if (event.kind == "inLove") return
        hasNews.value = true
        val now = Instant.now()
        when (event.kind) {
            "request" -> store.showToast("$name wants to be friends")
            "accepted" -> {
                store.update { it.madeFriend(name, now) }
                store.showToast("$name is your friend now")
                event.friendId?.let { showMoment(SharedMoment.reunion, it) }
            }
            "gift" -> {
                var line = ""
                store.update { line = it.receiveGift(event.food ?: Food.berry, from = name, now = now) }
                store.showToast(line)
                event.friendId?.let { showMoment(SharedMoment.gift, it) }
            }
            "hug", "kiss" -> {
                // A kiss comes from a friend on an older app; it arrives as a hug.
                store.showToast("$name: hug!")
                // One update, so the feelings are saved and synced once.
                store.update {
                    it.slime.needs.add(Need.love, 4.0)
                    it.slime.feel(Emotion.love, 0.15)
                    it.slime.feel(Emotion.joy, 0.15)
                    it.slime.react(Reaction.blush, now)
                }
                event.friendId?.let { showMoment(SharedMoment.hug, it) }
            }
            "visit" -> {
                store.showToast("$name came to visit")
                store.update {
                    it.slime.feel(Emotion.joy, 0.25)
                    it.slime.feel(Emotion.excitement, 0.2)
                }
                friend(event.friendId)?.let { _visitor.value = Visitor(islandSlime(it), now.addingTimeInterval(10.0 * 60)) }
                event.friendId?.let { showMoment(SharedMoment.reunion, it) }
            }
            "message" -> {
                val preset = event.preset ?: return
                store.showToast("$name: ${preset.text}")
                val moment = when (preset) {
                    MessagePreset.missYou -> SharedMoment.missing
                    MessagePreset.goodNight -> SharedMoment.sleep
                    MessagePreset.letsPlay -> SharedMoment.play
                    MessagePreset.congrats, MessagePreset.proud -> SharedMoment.achievement
                    else -> null
                }
                if (moment != null) event.friendId?.let { showMoment(moment, it) }
            }
            "challenge" -> store.showToast("$name challenges you")
            "challengeResult" -> store.showToast("$name played your challenge")
            "coopDone" -> {
                store.showToast("Your shared garden with $name is full")
                event.friendId?.let { showMoment(SharedMoment.achievement, it) }
            }
            else -> {}
        }
        // In the background (refresh), a few kinds also become a notification,
        // with the line this event showed (never an older toast).
        if (!inForeground && event.kind in listOf("request", "accepted", "gift", "message", "challenge")) {
            val toast = store.toast.value
            if (toast != null && toast.id != earlierToast) {
                val category = if (event.kind == "gift") Notifier.Category.gifts else Notifier.Category.friends
                Notifier.post(category, name, toast.text, store.state.value)
            }
        }
    }

    // MARK: Pairing

    /** A fresh code to give to a friend (10 minutes, single use). */
    suspend fun makeCode(): Boolean = onMain {
        if (!requireOnline()) return@onMain false
        try {
            _pairCode.value = api.newPairCode()
            lastError.value = null
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fail(e)
            false
        }
    }

    /** Connects with the owner of a friend's code. Returns a line to show on success. */
    suspend fun join(code: String): String? = onMain {
        val cleaned = FriendInvite.sanitize(code)
        if (cleaned.length != FriendInvite.length) {
            lastError.value = "Friend codes have ${FriendInvite.length} characters."
            return@onMain null
        }
        if (!requireOnline()) return@onMain null
        try {
            val result = api.join(cleaned)
            lastError.value = null
            if (pendingInviteCode.value == cleaned) pendingInviteCode.value = null
            val friend = result.friend
            if (friend != null) {
                adopt(friend)
                return@onMain "You and ${friend.slime.name} are friends now."
            }
            // The request is sent; a failed reload must not report it as lost.
            val request = result.request
            if (request != null && _requests.value.outgoing.none { it.id == request.id }) {
                _requests.value = _requests.value.copy(outgoing = _requests.value.outgoing + request)
            }
            val fetched = try {
                api.requests()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            if (fetched != null && _isOnline.value) _requests.value = fetched
            "Request sent. You're friends once it's accepted."
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fail(e)
            null
        }
    }

    suspend fun accept(request: FriendRequest): Boolean = onMain {
        if (!requireOnline()) return@onMain false
        try {
            val friend = api.accept(request.id)
            _requests.value = _requests.value.copy(incoming = _requests.value.incoming.filter { it.id != request.id })
            adopt(friend)
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fail(e)
            false
        }
    }

    /** Declines a request to you, or withdraws one you sent. */
    suspend fun decline(request: FriendRequest): Boolean = onMain {
        if (!requireOnline()) return@onMain false
        try {
            api.decline(request.id)
            _requests.value = RequestList(
                incoming = _requests.value.incoming.filter { it.id != request.id },
                outgoing = _requests.value.outgoing.filter { it.id != request.id },
            )
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fail(e)
            false
        }
    }

    private fun adopt(friend: Friend) {
        val isNew = _friends.value.none { it.id == friend.id }
        _friends.value = _friends.value.filter { it.id != friend.id } + friend
        cacheFriends()
        if (isNew) {
            store.update { it.madeFriend(friend.slime.name, Instant.now()) }
            showMoment(SharedMoment.reunion, friend.id)
        }
    }

    suspend fun remove(friend: Friend): Boolean = onMain {
        if (!requireOnline()) return@onMain false
        try {
            api.removeFriend(friend.id)
            forget(friend.id)
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fail(e)
            false
        }
    }

    suspend fun block(deviceID: String): Boolean = onMain {
        if (!requireOnline()) return@onMain false
        try {
            api.block(deviceID)
            forget(deviceID)
            _requests.value = RequestList(
                incoming = _requests.value.incoming.filter { it.deviceId != deviceID },
                outgoing = _requests.value.outgoing.filter { it.deviceId != deviceID },
            )
            _challenges.value = _challenges.value.filter { it.friendId != deviceID }
            if (_visitor.value?.friend?.id == deviceID) _visitor.value = null
            // Blocking the person you care for a slime with ends that too.
            if (store.state.value.care?.partnerID == deviceID || _pendingCare.value?.partnerID == deviceID) {
                endCareLocally(notice = null)
            }
            val list = try {
                api.blocked()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            if (list != null) _blocked.value = list
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fail(e)
            false
        }
    }

    suspend fun unblock(device: BlockedDevice): Boolean = onMain {
        if (!requireOnline()) return@onMain false
        try {
            api.unblock(device.deviceId)
            _blocked.value = _blocked.value.filter { it.deviceId != device.deviceId }
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fail(e)
            false
        }
    }

    suspend fun loadBlocked(): Unit = onMain {
        if (!_isOnline.value) return@onMain
        try {
            val list = api.blocked()
            if (_isOnline.value) _blocked.value = list
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fail(e)
        }
    }

    private fun forget(id: String) {
        _friends.value = _friends.value.filter { it.id != id }
        _details.value = _details.value - id
        for (action in _queue.value) if (action.friendID == id) refund(action)
        _queue.value = _queue.value.filter { it.friendID != id }
        saveQueue()
        cacheFriends()
        if (partnerID.value == id) partnerID.value = null
    }

    suspend fun loadDetail(friend: Friend): Unit = onMain {
        if (!_isOnline.value) return@onMain
        try {
            val detail = api.friend(friend.id)
            if (!_isOnline.value) return@onMain
            _details.value = _details.value + (friend.id to detail)
            if (_friends.value.any { it.id == friend.id }) {
                _friends.value = _friends.value.map { if (it.id == friend.id) detail.friend else it }
                cacheFriends()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fail(e)
        }
    }

    /** Changes a privacy setting on the server: `setPrivacy { it.copy(shareMood = false) }`. */
    suspend fun setPrivacy(change: (Privacy) -> Privacy): Boolean = onMain {
        if (!requireOnline()) return@onMain false
        val next = change(_privacy.value)
        try {
            _privacy.value = api.setPrivacy(next)
            if (!_privacy.value.acceptRequests) _pairCode.value = null
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fail(e)
            false
        }
    }

    /**
     * Deletes everything the server knows about this phone, then goes offline
     * and forgets the token, the name, the queue and the friends. The slime
     * stays. Works while banned too.
     */
    suspend fun deleteAccount(): Boolean = onMain {
        if (deletingAccount) return@onMain false
        deletingAccount = true
        try {
            // The vault's copy too, in case reading it failed at launch.
            val token = this.token ?: vault.load()
            val wasOptedIn = optedIn
            val wasOnline = _isOnline.value
            // Stop syncing first, so a refresh that is running can't register a
            // new device or bring friends back while the delete is on its way.
            optedIn = false
            _isOnline.value = false
            if (token != null) {
                try {
                    HyprnodeAPI(token).deleteAccount()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    when {
                        e is APIError && e.isUnauthorized -> {
                            // Already gone on the server.
                        }
                        e is APIError && e.isBanned -> {
                            handleBanned(e)
                            lastError.value =
                                "${e.errorDescription} To have its data deleted, contact us at slime.hyprnode.de/support."
                            return@onMain false
                        }
                        else -> {
                            optedIn = wasOptedIn
                            _isOnline.value = wasOnline
                            lastError.value = e.shownText
                            return@onMain false
                        }
                    }
                }
            }
            resetServerIdentity()
            // `token = null` only clears the vault when the copy was set.
            vault.save(null)
            refusedNames = null
            nameError.value = null
            goOffline()
            ownerName.value = ""
            _privacy.value = Privacy(acceptRequests = true, shareMood = true)
            _status.value = Status.unknown
            _banNotice.value = null
            pendingInviteCode.value = null
            hasNews.value = false
            lastError.value = null
            island?.sync()
            true
        } finally {
            deletingAccount = false
        }
    }

    // MARK: Reports

    /**
     * Sends a report to the Slimy team. It doesn't block anyone. [deviceID]
     * is needed unless the reason is `chat`, where [text] is the slime's reply.
     */
    suspend fun report(deviceID: String?, reason: ReportReason, note: String?, text: String?): Boolean = onMain {
        if (!requireOnline()) return@onMain false
        if (reason != ReportReason.chat && deviceID == null) {
            lastError.value = "Choose who you're reporting."
            return@onMain false
        }
        val cleanNote = note?.trimmedSwift()?.prefixChars(HyprnodeAPI.reportNoteLimit)
        val cleanText = text?.trimmedSwift()?.prefixChars(HyprnodeAPI.reportTextLimit)
        val body = HyprnodeAPI.ReportBody(
            deviceId = deviceID, reason = reason,
            note = cleanNote?.takeIf { it.isNotEmpty() },
            text = cleanText?.takeIf { it.isNotEmpty() },
        )
        try {
            // A second report of the same thing still answers 204.
            api.report(body, key = newUUIDString())
            lastError.value = null
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (deviceID != null && (e as? APIError)?.status == 404) {
                // They deleted their data; a sync takes them off the lists.
                scope.launch { refresh() }
            }
            fail(e)
            false
        }
    }

    // MARK: Records and leaderboard

    /** Sends best scores the server doesn't have yet. It only ever raises them, so sending again is harmless. */
    suspend fun uploadRecords(records: Map<GameID, Int>): Unit = onMain {
        if (!_isOnline.value || token == null) return@onMain
        val known = uploadedRecords
        val fresh = HyprnodeAPI.wireRecords(records).filter { it.value > (known[it.key] ?: 0) }
        if (fresh.isEmpty()) return@onMain
        try {
            api.uploadRecords(fresh)
            if (!_isOnline.value) return@onMain
            val merged = uploadedRecords.toMutableMap()
            for ((game, best) in fresh) merged[game] = max(merged[game] ?: 0, best)
            uploadedRecords = merged
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is APIError && e.isBanned) handleBanned(e)
            // Anything else: sent again with the next refresh.
        }
    }

    /** Every best score on this phone. */
    private val localRecords: Map<GameID, Int>
        get() = store.state.value.progress.records.mapValues { it.value.best }

    private suspend fun syncRecords() {
        uploadRecords(localRecords)
    }

    suspend fun loadLeaderboard(): Unit = onMain {
        if (!_isOnline.value) {
            _leaderboard.value = emptyList()
            return@onMain
        }
        _isLoadingLeaderboard.value = true
        try {
            syncRecords()
            // Sending the records can end in a ban, which takes the device offline.
            if (!_isOnline.value) {
                _leaderboard.value = emptyList()
                return@onMain
            }
            try {
                val games = api.leaderboard()
                if (!_isOnline.value) return@onMain
                val order = GameID.entries
                _leaderboard.value = games.sortedBy { order.indexOf(it.game).let { i -> if (i < 0) order.size else i } }
                lastError.value = null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                fail(e)
            }
        } finally {
            _isLoadingLeaderboard.value = false
        }
    }

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
    suspend fun interact(friend: Friend, kind: InteractionKind, food: Food? = null): String? = onMain {
        if (!requireOnline()) return@onMain null
        if (kind == InteractionKind.gift) {
            if (food == null || store.state.value.wallet.coins < food.price) {
                lastError.value = "Not enough coins for ${food?.title ?: "the gift"}."
                return@onMain null
            }
        }
        val action = PendingAction(
            kind = PendingAction.Kind.interaction(friend.id, kind, food),
            coins = if (kind == InteractionKind.gift) food?.price ?: 0 else 0,
        )
        // Pay for a gift now; a final "no" from the server refunds it.
        if (action.coins > 0) store.update { it.wallet.spend(action.coins) }
        try {
            val result = api.interact(friend.id, kind, food, action.key)
            applied(result, friend, kind, food)
        } catch (e: Exception) {
            if (e is CancellationException) {
                // The answer never came: keep it for later rather than lose the coins.
                enqueue(action)
                throw e
            }
            if (e is APIError && e.shouldQueue) {
                enqueue(action)
                "Will arrive once you're back online."
            } else {
                refund(action)
                fail(e)
                null
            }
        }
    }

    private fun applied(result: InteractionResult, friend: Friend, kind: InteractionKind, food: Food?): String {
        // Today's limit for this kind: it didn't reach the friend.
        val capped = result.message
        if (result.capped == true && capped != null) return capped

        val category = when (kind) {
            InteractionKind.hug -> LineCategory.friendHug
            InteractionKind.visit -> LineCategory.friendVisit
            InteractionKind.gift -> LineCategory.gift
        }
        store.update { it.friendMoment(category, friend.slime.name, Instant.now()) }
        val moment = when (kind) {
            InteractionKind.hug -> SharedMoment.hug
            InteractionKind.visit -> SharedMoment.reunion
            InteractionKind.gift -> SharedMoment.gift
        }
        showMoment(moment, friend.id)
        lastError.value = null

        return when (kind) {
            InteractionKind.hug -> "You hugged ${friend.slime.name}."
            InteractionKind.visit -> "Your slime is visiting ${friend.slime.name}."
            InteractionKind.gift -> "${food?.title ?: "Gift"} sent to ${friend.slime.name}."
        }
    }

    suspend fun send(preset: MessagePreset, friend: Friend): String? = onMain {
        if (!requireOnline()) return@onMain null
        val action = PendingAction(kind = PendingAction.Kind.message(friend.id, preset))
        try {
            api.message(friend.id, preset, action.key)
            lastError.value = null
            "“${preset.text}” sent to ${friend.slime.name}."
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is APIError && e.shouldQueue) {
                enqueue(action)
                "Will arrive once you're back online."
            } else {
                fail(e)
                null
            }
        }
    }

    // MARK: Arcade together

    /**
     * A finished round adds to the co-op garden you share with the friend in
     * your slime notification (or your oldest friend). Points are the
     * round's quality, not its raw score, so every game counts the same.
     */
    fun contributeCoop(result: GameResult) {
        onMainThread {
            if (!_isOnline.value || token == null || result.score <= 0) return@onMainThread
            val target = partner ?: _friends.value.minByOrNull { it.since } ?: return@onMainThread
            // `result.goodScore` is the day's own target for the daily game.
            val points = min(150.0, result.score.toDouble() / result.goodScore.toDouble() * 100).toInt()
            if (points <= 0) return@onMainThread
            enqueue(PendingAction(kind = PendingAction.Kind.coop(target.id, points)))
            scope.launch { flushQueue() }
        }
    }

    suspend fun challenge(friend: Friend, result: GameResult, seed: ULong): String? = onMain {
        if (!requireOnline()) return@onMain null
        val action = PendingAction(
            kind = PendingAction.Kind.challenge(friend.id, result.game, seed.toString(), result.score),
        )
        try {
            val c = api.challenge(friend.id, result.game, seed, result.score, action.key)
            _challenges.value = listOf(c) + _challenges.value
            "Challenge sent to ${friend.slime.name}."
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is APIError && e.shouldQueue) {
                enqueue(action)
                "Will be sent once you're back online."
            } else {
                fail(e)
                null
            }
        }
    }

    suspend fun answer(challenge: Challenge, score: Int): Unit = onMain {
        if (!requireOnline()) return@onMain
        val action = PendingAction(kind = PendingAction.Kind.answer(challenge.id, score))
        try {
            val updated = api.answer(challenge.id, score, action.key)
            _challenges.value = _challenges.value.map { if (it.id == updated.id) updated else it }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is APIError && e.shouldQueue) {
                _challenges.value = _challenges.value.map { if (it.id == challenge.id) it.copy(myScore = score) else it }
                enqueue(action)
            } else {
                fail(e)
            }
        }
    }

    // MARK: Care together

    /** A fresh code for someone to care for your slime with you (10 minutes, single use). */
    suspend fun makeCareCode(): Boolean = onMain {
        if (!requireOnline()) return@onMain false
        if (isSharing) {
            lastError.value = "You're already caring for a slime together."
            return@onMain false
        }
        _pendingCare.value?.let {
            lastError.value = "You're joining ${it.partnerName}'s slime. Cancel that first to make your own code."
            return@onMain false
        }
        try {
            val code = api.newCareCode()
            checkOnline()
            setCareCode(code)
            _careProblem.value = null
            lastError.value = null
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if ((e as? APIError)?.status == 409) {
                // The server knows a pairing this phone doesn't: pick it up.
                scope.launch { syncCare(discover = true) }
            }
            fail(e)
            false
        }
    }

    /** Joins the slime behind a care code. On success [pendingCare] holds who it belongs to; the slime moves here on [confirmJoin]. */
    suspend fun joinCare(code: String): Boolean = onMain {
        val cleaned = FriendInvite.sanitize(code)
        if (cleaned.length != FriendInvite.length) {
            lastError.value = "Care codes have ${FriendInvite.length} characters."
            return@onMain false
        }
        if (!requireOnline()) return@onMain false
        if (isSharing) {
            lastError.value = "You're already caring for a slime together. Stop sharing first."
            return@onMain false
        }
        if (_pendingCare.value != null) {
            lastError.value = "You're already joining a slime. Finish or cancel that first."
            return@onMain false
        }
        if (activeCareCode?.code == cleaned) {
            lastError.value = "That's your own code. Give it to the person you want to care with."
            return@onMain false
        }
        try {
            val joined = api.joinCare(cleaned)
            checkOnline()
            setCareCode(null)
            setPendingCare(
                PendingCare(
                    partnerID = joined.partner.deviceId, partnerName = joined.partner.ownerName,
                    slimeName = joined.snapshotName, snapshot = joined.snapshot, version = joined.version,
                    cursor = null, confirmed = false, joinedAt = Instant.now(),
                ),
            )
            careEpoch += 1
            _careProblem.value = null
            lastError.value = null
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if ((e as? APIError)?.status == 409) scope.launch { syncCare(discover = true) }
            fail(e)
            false
        }
    }

    /** You said yes to the slime you joined: it moves onto this phone now, or as soon as your partner's phone has sent it. */
    suspend fun confirmJoin(): Unit = onMain {
        val pending = _pendingCare.value?.copy(confirmed = true) ?: return@onMain
        setPendingCare(pending)
        val snapshot = pending.snapshot
        if (snapshot != null) adopt(snapshot, pending) else syncCare()
    }

    /** Takes back joining someone's slime before it moved here. Your own slime never left. */
    suspend fun cancelJoin(): Boolean = onMain {
        if (_pendingCare.value == null) return@onMain true
        if (!requireOnline() || careLeaving) return@onMain false
        careLeaving = true
        try {
            careEpoch += 1
            try {
                api.leaveCare()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val status = (e as? APIError)?.status
                if (status != 409 && status != 404) {
                    fail(e)
                    return@onMain false
                }
                // Already over on the server.
            }
            setPendingCare(null)
            careEpoch += 1
            lastError.value = null
            true
        } finally {
            careLeaving = false
        }
    }

    /** Stops caring together for both of you. The joiner gets their own slime back; the host keeps the slime. */
    suspend fun leaveCare(): Boolean = onMain {
        if (!isSharing && _pendingCare.value == null) return@onMain true
        if (!requireOnline() || careLeaving) return@onMain false
        pushCareSnapshot()
        // A sync answering meanwhile would still show the pairing, or that it
        // ended, as if the partner had stopped.
        careLeaving = true
        try {
            careEpoch += 1
            try {
                api.leaveCare()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val status = (e as? APIError)?.status
                if (status != 409 && status != 404) {
                    fail(e)
                    return@onMain false
                }
                // The partner already ended it.
            }
            endCareLocally(notice = null)
            lastError.value = null
            true
        } finally {
            careLeaving = false
        }
    }

    /**
     * Brings caring together up to date: who you care with, your partner's
     * actions (applied to the slime here), and the newest snapshot. Also
     * notices when a code was used or your partner stopped sharing.
     * [discover] checks once even when this phone knows of no pairing.
     */
    suspend fun syncCare(discover: Boolean = false): Unit = onMain {
        reloadTokenIfNeeded()
        if (discover) careDiscovery = true
        if (!_isOnline.value || token == null || careSyncing || careLeaving || deletingAccount || !wantsCareSync) {
            return@onMain
        }
        careSyncing = true
        try {
            // Your own actions go out before the partner's come in.
            if (_queue.value.any { it.isCare }) flushQueue()
            var rounds = 0
            while (true) {
                val epoch = careEpoch
                val cursor = store.state.value.care?.cursor ?: _pendingCare.value?.cursor
                val status = api.care(cursor)
                checkOnline()
                rounds += 1
                if (epoch != careEpoch || careLeaving) {
                    // You joined or stopped while this was on its way: the
                    // answer is from before. Ask again unless you're leaving.
                    if (rounds < 5 && !careLeaving) continue
                    break
                }
                val more = applyCare(status)
                if (!more || rounds >= 5) break
            }
            _careSyncedAt.value = Instant.now()
            // A refused name keeps its notice until the slime has a new one.
            _careProblem.value = refusedSnapshotProblem
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            careSyncFailed(e)
        } finally {
            careSyncing = false
            careDiscovery = false
        }
        if (careDirty && isSharing) scheduleCarePush()
    }

    /**
     * Why the shared slime's changes don't reach the partner: the server
     * refused its name, and it still has that name. Ends with the server's
     * own words.
     */
    private val refusedSnapshotProblem: String?
        get() {
            val refused = refusedSnapshotName ?: return null
            if (!isSharing || refused != store.state.value.slime.name) return null
            val reason = refusedSnapshotMessage ?: "Please choose a different name."
            return "slime.hyprnode.de didn't accept the name $refused, so your partner doesn't see changes to it. $reason"
        }

    private fun careSyncFailed(error: Throwable) {
        if (error is NotOnlineError) return
        if (error !is APIError) {
            _careProblem.value = error.shownText
            return
        }
        when {
            error.isBanned -> handleBanned(error)
            error.isUnauthorized -> scope.launch { refresh() }
            else -> _careProblem.value = error.errorDescription
        }
    }

    /** Applies one answer of `GET /care`. Returns whether more of the partner's actions are waiting. */
    private fun applyCare(status: CareStatus): Boolean {
        val partner = status.partner
        if (partner == null) {
            careNotPaired()
            return false
        }
        // Paired with someone else than this phone thought: start over.
        store.state.value.care?.let { if (it.partnerID != partner.deviceId) endCareLocally(notice = null) }

        val link = store.state.value.care?.deepCopy()
        if (link != null) {
            val changed = link.deepCopy()
            changed.partnerName = partner.ownerName
            status.cursor?.let { changed.cursor = it }
            val fresh = status.events.filter { it.deviceId == link.partnerID }
            applyPartnerEvents(fresh)
            if (status.version > link.version) {
                status.snapshot?.let { reconcileShared(it) }
                changed.version = status.version
            }
            if (changed != link) setCareLink(changed)
            if (_careSince.value == null) setCareSince(Instant.now())
            // The host sends the slime first, so the joiner gets it.
            if (changed.role == CareRole.host && status.version == 0 && status.snapshot == null) {
                scope.launch { pushCareSnapshot(force = true) }
            }
            return status.events.size >= 100
        }

        val current = _pendingCare.value
        if (current != null) {
            // You joined: keep the newest slime and skip actions the slime
            // already shows, until it moves here.
            var pending = current.copy(
                partnerID = partner.deviceId,
                partnerName = partner.ownerName,
                cursor = status.cursor ?: current.cursor,
            )
            val snapshot = status.snapshot
            if (snapshot != null) {
                pending = pending.copy(
                    snapshot = snapshot,
                    slimeName = status.snapshotName ?: pending.slimeName,
                    version = status.version,
                )
            }
            setPendingCare(pending)
            val arrived = pending.snapshot
            if (pending.confirmed && arrived != null) {
                adopt(arrived, pending)
                return false
            }
            return status.events.size >= 100
        }

        // A pairing this phone doesn't know yet: your code was used, or you
        // joined on an earlier launch.
        val role = if (_careCode.value != null) CareRole.host else partner.myRole
        if (role == CareRole.host) {
            startHosting(partner, status)
        } else if (role == CareRole.joiner) {
            setPendingCare(
                PendingCare(
                    partnerID = partner.deviceId, partnerName = partner.ownerName,
                    slimeName = status.snapshotName, snapshot = status.snapshot,
                    version = status.version, cursor = status.cursor, confirmed = false,
                    joinedAt = Instant.now(),
                ),
            )
        }
        return false
    }

    /** The server says nobody cares together with you (anymore). */
    private fun careNotPaired() {
        val link = store.state.value.care
        val pending = _pendingCare.value
        val code = _careCode.value
        if (link != null) {
            val name = link.partnerName
            val shared = store.state.value.slime.name
            val wasHost = link.role == CareRole.host
            endCareLocally(notice = null)
            val line = if (wasHost) {
                "$name stopped caring for $shared with you."
            } else {
                "$name stopped sharing $shared. ${store.state.value.slime.name} is back with you."
            }
            announceCare(line)
        } else if (pending != null) {
            setPendingCare(null)
            announceCare("${pending.partnerName} stopped sharing before their slime arrived.")
        } else if (code != null && code.expiresAt <= Instant.now()) {
            // Nobody used the code before it ran out.
            setCareCode(null)
        }
    }

    /** Someone joined with your code: from now on you care for your slime together, and their phone needs it right away. */
    private fun startHosting(partner: CarePartner, status: CareStatus) {
        val link = CareLink(
            partnerID = partner.deviceId, partnerName = partner.ownerName, role = CareRole.host,
            cursor = status.cursor, version = status.version,
        )
        store.startHosting(link)
        setCareCode(null)
        setCareSince(Instant.now())
        setCareActivity(emptyList())
        announceCare("${partner.ownerName} joined. You care for ${store.state.value.slime.name} together now.")
        scope.launch { pushCareSnapshot(force = true) }
    }

    /** The slime you joined moves onto this phone; your own is kept safe. */
    private fun adopt(snapshot: SlimeSnapshot, pending: PendingCare) {
        val link = CareLink(
            partnerID = pending.partnerID, partnerName = pending.partnerName, role = CareRole.joiner,
            cursor = pending.cursor, version = pending.version,
        )
        remote { store.adoptShared(snapshot, link) }
        careEpoch += 1
        setPendingCare(null)
        setCareCode(null)
        setCareSince(Instant.now())
        setCareActivity(emptyList())
        careDirty = false
        lastCarePush = Instant.now()
        announceCare("You care for ${store.state.value.slime.name} with ${pending.partnerName} now.")
        syncIsland()
    }

    /**
     * Ends caring together on this phone only: the joiner's own slime comes
     * back, the host keeps the slime; codes, waiting actions and the
     * activity list go.
     */
    private fun endCareLocally(notice: String?) {
        if (store.isSharing) remote { store.stopSharing() }
        careEpoch += 1
        setPendingCare(null)
        setCareCode(null)
        setCareSince(null)
        setCareActivity(emptyList())
        _careProblem.value = null
        careDirty = false
        refusedSnapshotName = null
        refusedSnapshotMessage = null
        updateCareNameNotice()
        carePushTask?.cancel()
        carePushTask = null
        if (_queue.value.any { it.isCare }) {
            _queue.value = _queue.value.filter { !it.isCare }
            saveQueue()
        }
        if (notice != null) announceCare(notice)
    }

    /** Shows a line about caring together: a toast, and in the background a notification. */
    private fun announceCare(line: String) {
        store.showToast(line)
        if (!inForeground) Notifier.post(Notifier.Category.friends, "Care Together", line, store.state.value)
    }

    /** The partner's actions, applied to the shared slime and listed in the activity. Each one only once. */
    private fun applyPartnerEvents(wires: List<CareEventWire>) {
        val known = _careActivity.value.map { it.id }.toSet()
        val fresh = wires.filter { it.id !in known }.sortedBy { it.at }
        if (fresh.isEmpty()) return
        remote { store.applyPartnerCare(fresh.map { it.event }) }
        val slimeName = store.state.value.slime.name
        setCareActivity(CareActivity.merged(fresh.map { CareActivity(it, slimeName) }, _careActivity.value, Instant.now()))
    }

    private fun reconcileShared(snapshot: SlimeSnapshot) {
        remote { store.reconcile(snapshot) }
    }

    /** A change that came from the partner: not sent back as this phone's. */
    private inline fun remote(change: () -> Unit) {
        applyingRemote = true
        try {
            change()
        } finally {
            applyingRemote = false
            lastLook = SharedLook(store.state.value.slime)
        }
    }

    private fun setCareLink(link: CareLink) {
        val copy = link.deepCopy()
        store.update { it.care = copy }
    }

    /** One of your care actions: off to the partner's phone through the queue, and the slime is due for a snapshot. */
    private fun sendCare(event: CareEvent) {
        if (!isSharing) return
        // Offline, the snapshot still goes out once you're back online.
        markCareChanged()
        if (!_isOnline.value || token == null) return
        if (event.kind == CareKind.pet) {
            val now = Instant.now()
            if (now.timeIntervalSince(lastPetSent) < carePetSpacing) return
            lastPetSent = now
        }
        enqueue(PendingAction(kind = PendingAction.Kind.care(event.kind, event.food, event.game, event.amount, event.at)))
        scope.launch { flushQueue() }
    }

    /** Every change of the game: the notices that depend on it, and a changed shared look goes out with the next snapshot. */
    private fun gameChanged() {
        updateProfileNotice()
        updateCareNameNotice()
        val look = SharedLook(store.state.value.slime)
        if (look == lastLook) return
        lastLook = look
        if (!applyingRemote && isSharing) markCareChanged()
    }

    private fun markCareChanged() {
        careDirty = true
        scheduleCarePush()
    }

    /** Sends the snapshot once the minute since the last one is over. */
    private fun scheduleCarePush() {
        if (carePushTask != null || !_isOnline.value || !isSharing) return
        val wait = max(2.0, careSnapshotSpacing - Instant.now().timeIntervalSince(lastCarePush))
        carePushTask = scope.launch {
            delay((wait * 1000).toLong())
            carePushTask = null
            pushCareSnapshot()
        }
    }

    /**
     * Stores the shared slime on the server when it changed here (or
     * [force]). If the other phone saved first, its slime is reconciled with
     * this one and sent once more.
     */
    private suspend fun pushCareSnapshot(force: Boolean = false) {
        if (!_isOnline.value || token == null || carePushing || !store.isSharing || !(careDirty || force)) return
        val name = store.state.value.slime.name
        if (refusedSnapshotName != null && refusedSnapshotName == name) return
        carePushing = true
        careDirty = false
        lastCarePush = Instant.now()
        // The name in the snapshot actually sent (a conflict can bring the partner's newer one).
        var sentName = name
        try {
            val outcome = CarePush.rounds(
                link = { store.state.value.care },
                snapshot = { store.sharedSnapshot() },
                put = { snapshot, version -> api.putCareSnapshot(snapshot, version) },
                setVersion = { version ->
                    store.state.value.care?.let { setCareLink(it.deepCopy().also { link -> link.version = version }) }
                },
                reconcile = { reconcileShared(it) },
                onSending = { sentName = it },
            )
            when (outcome) {
                is CarePush.Outcome.saved -> {
                    refusedSnapshotName = null
                    refusedSnapshotMessage = null
                    _careProblem.value = null
                    updateCareNameNotice()
                }
                // Nobody cares together anymore: find out what happened.
                CarePush.Outcome.notPaired -> scope.launch { syncCare() }
                // Still behind after the second try: the next push sends it.
                CarePush.Outcome.behind -> careDirty = true
                CarePush.Outcome.gone -> {}
            }
        } catch (e: CancellationException) {
            careDirty = true
            throw e
        } catch (e: APIError) {
            when {
                e.isBanned -> handleBanned(e)
                e.isRefused -> {
                    // The name filter (400) is final: not sent again until the
                    // slime has a different name. Not a version conflict.
                    refusedSnapshotName = sentName
                    refusedSnapshotMessage = e.errorDescription
                    _careProblem.value = refusedSnapshotProblem
                    updateCareNameNotice()
                }
                // Too big for the server; sending the same slime again won't help. The next change tries again.
                e.status == 413 -> _careProblem.value = e.errorDescription
                else -> careDirty = true
            }
        } catch (e: Exception) {
            careDirty = true
        } finally {
            carePushing = false
        }
    }

    /** While the app is on screen, caring together syncs every 20 seconds (every 12 while waiting for a partner). */
    fun startCarePolling() {
        if (carePoll != null) return
        carePoll = scope.launch {
            while (isActive) {
                if (_isOnline.value && wantsCareSync) syncCare()
                val wait = if (isWaitingForCare) careWaitInterval else carePollInterval
                delay((wait * 1000).toLong())
            }
        }
    }

    fun stopCarePolling() {
        carePoll?.cancel()
        carePoll = null
    }

    /** The app left the screen: polling stops, and a changed shared slime is sent right away. */
    fun enterBackground() {
        stopCarePolling()
        carePushTask?.cancel()
        carePushTask = null
        if (!careDirty || !_isOnline.value || !isSharing || backgroundPush?.isActive == true) return
        backgroundPush = scope.launch { pushCareSnapshot() }
    }

    private fun setCareCode(value: PairCode?) {
        _careCode.value = value
        saveCareValue(value, Keys.careCode, PairCode.serializer())
    }

    private fun setPendingCare(value: PendingCare?) {
        _pendingCare.value = value
        saveCareValue(value, Keys.pendingCare, PendingCare.serializer())
    }

    private fun setCareActivity(value: List<CareActivity>) {
        _careActivity.value = value
        saveCareValue(value, Keys.careActivity, ListSerializer(CareActivity.serializer()))
    }

    private fun setCareSince(value: Instant?) {
        _careSince.value = value
        if (value == null) {
            prefs.edit().remove(Keys.careSince).apply()
        } else {
            prefs.edit().putLong(Keys.careSince, value.toEpochMilli()).apply()
        }
    }

    private fun <T> saveCareValue(value: T?, key: String, serializer: KSerializer<T>) {
        if (value == null) {
            prefs.edit().remove(key).apply()
        } else {
            val text = runCatching { HyprnodeAPI.encoder.encodeToString(serializer, value) }.getOrNull()
            if (text == null) prefs.edit().remove(key).apply() else prefs.edit().putString(key, text).apply()
        }
    }

    private fun <T> loadCareValue(key: String, serializer: KSerializer<T>): T? {
        val text = prefs.getString(key, null) ?: return null
        return runCatching { HyprnodeAPI.decoder.decodeFromString(serializer, text) }.getOrNull()
    }

    // MARK: Offline queue

    /** Adds a write to the queue. When it is full, the oldest care action goes first, then the oldest of the rest. */
    private fun enqueue(action: PendingAction) {
        val (kept, dropped) = OfflineQueue.adding(action, _queue.value, Instant.now())
        for (gone in dropped) refund(gone)
        _queue.value = kept
        saveQueue()
    }

    /** Old entries would only confuse a friend days later. */
    private fun dropStaleActions() {
        val (kept, stale) = OfflineQueue.withoutStale(_queue.value, Instant.now())
        if (stale.isEmpty()) return
        for (action in stale) refund(action)
        _queue.value = kept
    }

    private fun saveQueue() {
        val text = SlimyJson.storage.encodeToString(ListSerializer(PendingAction.serializer()), _queue.value)
        prefs.edit().putString(Keys.queue, text).apply()
    }

    private fun refund(action: PendingAction) {
        if (action.coins <= 0) return
        store.update { it.wallet.coins += action.coins }
    }

    /** Sends what waited, oldest first. Does nothing while offline. */
    suspend fun flushQueue(): Unit = onMain {
        if (!_isOnline.value || token == null || flushing) return@onMain
        flushing = true
        try {
            dropStaleActions()
            saveQueue()
            val careEnded = OfflineQueue.flush(
                pending = _queue.value,
                isQueued = { action -> _queue.value.any { it.id == action.id } },
                isOnline = { _isOnline.value },
                send = { action -> deliver(action) },
                remove = { action, refund ->
                    if (refund) refund(action)
                    _queue.value = _queue.value.filter { it.id != action.id }
                    saveQueue()
                },
                banned = { handleBanned(it) },
            )
            if (careEnded) scope.launch { syncCare() }
        } finally {
            flushing = false
        }
    }

    /** Sends one queued write with its own key. */
    private suspend fun deliver(action: PendingAction) {
        val key = action.key
        when (val kind = action.kind) {
            is PendingAction.Kind.interaction -> api.interact(kind.friendID, kind.kind, kind.food, key)
            is PendingAction.Kind.message -> api.message(kind.friendID, kind.preset, key)
            is PendingAction.Kind.coop -> {
                val status = api.addCoop(kind.friendID, kind.points, key)
                _details.value[kind.friendID]?.let { detail ->
                    _details.value = _details.value + (kind.friendID to detail.copy(coop = status))
                }
            }
            is PendingAction.Kind.challenge ->
                api.challenge(kind.friendID, kind.game, kind.seed.toULongOrNull() ?: 1uL, kind.score, key)
            is PendingAction.Kind.answer -> api.answer(kind.challengeID, kind.score, key)
            is PendingAction.Kind.care -> {
                // Sharing stopped meanwhile: nobody to send it to.
                if (store.isSharing) {
                    api.sendCare(CareEventBody.of(kind.kind, kind.food, kind.game, kind.amount, kind.at), key)
                }
            }
        }
    }

    // MARK: Plumbing

    private fun updateProfileNotice() {
        val refused = refusedNames
        _profileNotice.value = if (refused != null && refused == currentNames) refusedNotice else null
    }

    private fun updateCareNameNotice() {
        _careNameNotice.value = refusedSnapshotProblem
    }

    private fun showMoment(moment: SharedMoment, friendID: String) {
        scope.launch { island?.show(moment, friendID) }
    }

    private fun syncIsland() {
        scope.launch { island?.sync() }
    }

    private fun hasNetwork(): Boolean = try {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        connectivity == null || capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: Exception) {
        true
    }

    /** Runs [block] on the main thread, right away when already there. */
    private fun onMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /** All state lives on the main thread, like the Swift store's `@MainActor`. */
    private suspend fun <T> onMain(block: suspend () -> T): T =
        withContext(Dispatchers.Main.immediate) { block() }

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

        /** The shared preferences file the online stores keep their values in (keys as in the iPhone app). */
        const val prefsName = "slimy.online"

        private const val refusedNotice =
            "slime.hyprnode.de didn't accept your name or your slime's name, so friends don't see them. Please choose a different one."

        private val recordsSerializer = MapSerializer(String.serializer(), Int.serializer())
        private val namesSerializer = ListSerializer(String.serializer())

        /** What Friends and Go Online show after a ban. The server still lets a banned device delete its data. */
        private fun banMessage(error: APIError): String =
            "${error.errorDescription} You can still delete your data on the server in Settings under Privacy."

        fun islandSlime(f: Friend): FriendSlime = FriendSlime(
            id = f.id, name = f.slime.name, owner = f.ownerName, skin = f.slime.skin, stage = f.stage,
            mood = f.slime.mood, equipped = f.slime.equipped,
        )
    }
}

/**
 * The parts of the shared slime that change outside care actions (a new
 * name, colour or outfit, XP from elsewhere). A change goes out with the
 * next snapshot.
 */
private data class SharedLook(val name: String, val skin: Skin, val xp: Int, val equipped: Accessories) {
    constructor(slime: Slime) : this(slime.name, slime.skin, slime.xp, slime.equipped.copy())
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
