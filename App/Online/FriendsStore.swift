import Combine
import Foundation
import Security
import UIKit

/// Friends, greetings, challenges, the co-op garden, the friends leaderboard
/// and caring for one slime together. All of it is opt-in: nothing reaches
/// slime.hyprnode.de until the player taps "Go online" and agrees to the
/// Terms (`goOnline()`), and nothing syncs after they go offline again. The
/// server decides who is friends and who cares together. Writes that fail
/// because the network is gone wait in a queue and are sent later with the
/// same idempotency key, so nothing counts twice.
@MainActor
final class FriendsStore: ObservableObject {
    static let shared = FriendsStore()

    static let termsURL = URL(string: "https://slime.hyprnode.de/terms")!
    static let privacyURL = URL(string: "https://slime.hyprnode.de/privacy")!
    static let supportURL = URL(string: "https://slime.hyprnode.de/support")!
    /// The server's limit.
    static let maxFriends = 20

    enum Status: Equatable {
        case unknown, online, unavailable(String)
    }

    /// Registered with the server and not opted out. While false nothing is
    /// registered, uploaded, polled or sent.
    @Published private(set) var isOnline: Bool
    @Published private(set) var friends: [Friend] = []
    @Published private(set) var requests = RequestList(incoming: [], outgoing: [])
    @Published private(set) var challenges: [Challenge] = []
    @Published private(set) var blocked: [BlockedDevice] = []
    @Published private(set) var details: [String: FriendDetail] = [:]
    @Published private(set) var status: Status = .unknown
    @Published private(set) var pairCode: PairCode?
    @Published private(set) var privacy = Privacy(acceptRequests: true, shareMood: true)
    @Published private(set) var queue: [PendingAction] = []
    /// Best scores of you and your friends, per game.
    @Published private(set) var leaderboard: [LeaderboardGame] = []
    @Published private(set) var isLoadingLeaderboard = false
    /// The server stopped this device's online features. Shown until the
    /// next attempt to go online.
    @Published private(set) var banNotice: String?
    /// The server refused the names friends should see (its filter, or
    /// names the Slimy team reset). Syncs stop sending them; this asks for
    /// new ones until the names change.
    var profileNotice: String? {
        guard let refusedNames, refusedNames == currentNames else { return nil }
        return Self.refusedNotice
    }
    /// The server refused a name the player just entered (going online or
    /// saving names). Shown next to the name fields; views clear it.
    @Published var nameError: String?
    /// A friend's slime visiting your home, until the date.
    @Published private(set) var visitor: (friend: FriendSlime, until: Date)?
    @Published var lastError: String?
    /// A challenge the player chose to play; the arcade opens with it.
    @Published var playChallenge: Challenge?
    /// Something new arrived (shown as a dot on the Friends button).
    @Published var hasNews = false
    /// A friend code from an invite link, waiting to be connected in Friends.
    @Published var pendingInviteCode: String?
    @Published var ownerName: String {
        didSet { defaults.set(ownerName, forKey: Keys.owner) }
    }
    /// Which friend's slime sits next to yours in the island.
    @Published var partnerID: String? {
        didSet {
            defaults.set(partnerID, forKey: Keys.partner)
            Task { await IslandController.shared.sync() }
        }
    }

    // MARK: Care together state

    /// A care code you made, kept until someone joins with it or a check
    /// after it ran out shows nobody did.
    @Published private(set) var careCode: PairCode? {
        didSet { saveCareValue(careCode, forKey: Keys.careCode) }
    }
    /// You joined someone's slime: waiting for your yes, or for their phone
    /// to send the slime.
    @Published private(set) var pendingCare: PendingCare? {
        didSet { saveCareValue(pendingCare, forKey: Keys.pendingCare) }
    }
    /// What your partner did for the shared slime, newest first, kept for a
    /// week.
    @Published private(set) var careActivity: [CareActivity] = [] {
        didSet { saveCareValue(careActivity, forKey: Keys.careActivity) }
    }
    /// When caring together started, by this phone's clock.
    @Published private(set) var careSince: Date? {
        didSet { defaults.set(careSince, forKey: Keys.careSince) }
    }
    /// The last time caring together synced.
    @Published private(set) var careSyncedAt: Date?
    /// Why caring together can't sync right now, while that lasts.
    @Published private(set) var careProblem: String?

    private let defaults = UserDefaults.standard
    private var refreshing = false
    private var connecting = false
    private var flushing = false
    private var deletingAccount = false
    private var careSyncing = false
    private var carePushing = false
    /// The shared slime changed here since the last snapshot was sent. Kept
    /// across launches, so changes made offline still go out.
    private var careDirty = false {
        didSet { if careDirty != oldValue { defaults.set(careDirty, forKey: Keys.careDirty) } }
    }
    private var lastCarePush = Date.distantPast
    private var carePushTask: Task<Void, Never>?
    private var carePoll: Task<Void, Never>?
    /// Set while the partner's actions or snapshot change the slime, so
    /// those changes aren't sent back as this phone's.
    private var applyingRemote = false
    private var lastPetSent = Date.distantPast
    /// The slime name the server refused in a snapshot (400, which is
    /// final), so it isn't sent again until it changes.
    private var refusedSnapshotName: String?
    /// What the server said when it refused that name.
    private var refusedSnapshotMessage: String?
    /// One sync looks for a pairing this phone doesn't know about (after
    /// the server said you're already caring together).
    private var careDiscovery = false
    /// Goes up whenever joining, adopting or ending changes the pairing
    /// here, so a `GET /care` answer from before that is ignored.
    private var careEpoch = 0
    /// Stop Sharing or Don't Join is on its way to the server.
    private var careLeaving = false
    private var careBackgroundTask = UIBackgroundTaskIdentifier.invalid
    private var lookSubscription: AnyCancellable?
    /// Requests and challenges already counted as news.
    private var knownNews: Set<String> = []
    /// Kept in the Keychain; this copy saves a lookup per request.
    private var token: String? {
        didSet { if token != oldValue { TokenVault.save(token) } }
    }
    /// The player agreed to go online and didn't opt out since.
    private var optedIn: Bool {
        get { defaults.bool(forKey: Keys.online) }
        set { defaults.set(newValue, forKey: Keys.online) }
    }
    private var inboxCursor: String? {
        get { defaults.string(forKey: Keys.cursor) }
        set { defaults.set(newValue, forKey: Keys.cursor) }
    }
    /// The best scores the server already has, so each is sent once.
    private var uploadedRecords: [String: Int] {
        get { defaults.dictionary(forKey: Keys.records) as? [String: Int] ?? [:] }
        set { defaults.set(newValue, forKey: Keys.records) }
    }
    /// The owner and slime names the server refused last, so syncs don't
    /// send them again and again.
    @Published private var refusedNames: [String]? = nil {
        didSet { defaults.set(refusedNames, forKey: Keys.refusedNames) }
    }

    private static let refusedNotice =
        "slime.hyprnode.de didn't accept your name or your slime's name, so friends don't see them. Please choose a different one."

    private enum Keys {
        static let owner = "slimy.friends.owner"
        static let partner = "slimy.friends.partner"
        static let online = "slimy.friends.online"
        static let cursor = "slimy.friends.cursor"
        static let queue = "slimy.friends.queue"
        static let cache = "slimy.friends.cache"
        static let records = "slimy.friends.records"
        static let refusedNames = "slimy.friends.refusedNames"
        static let careCode = "slimy.care.code"
        static let pendingCare = "slimy.care.pending"
        static let careActivity = "slimy.care.activity"
        static let careSince = "slimy.care.since"
        static let careDirty = "slimy.care.dirty"
        /// Before 1.0 the token lived in UserDefaults.
        static let legacyToken = "slimy.friends.token"
        /// The demo friend of the betas.
        static let legacyDemo = ["slimy.friends.demo", "slimy.friends.demoState"]
    }

    init() {
        let defaults = UserDefaults.standard
        ownerName = defaults.string(forKey: Keys.owner) ?? ""

        // Move the token to the Keychain once. If the Keychain can't take it
        // right now, keep using the old copy and try again next launch.
        var stored = TokenVault.load()
        if let legacy = defaults.string(forKey: Keys.legacyToken) {
            if stored == nil, TokenVault.save(legacy) { stored = legacy }
            if stored != nil {
                defaults.removeObject(forKey: Keys.legacyToken)
            } else {
                stored = legacy
            }
        }
        token = stored
        isOnline = defaults.bool(forKey: Keys.online) && stored != nil

        for key in Keys.legacyDemo { defaults.removeObject(forKey: key) }
        let savedPartner = defaults.string(forKey: Keys.partner)
        if savedPartner == "demo-mochi" {
            defaults.removeObject(forKey: Keys.partner)
            partnerID = nil
        } else {
            partnerID = savedPartner
        }

        // Actions this version no longer sends (kisses) are dropped, not the queue.
        if let data = defaults.data(forKey: Keys.queue),
           let saved = try? JSONDecoder().decode([LossyElement<PendingAction>].self, from: data) {
            queue = saved.compactMap { $0.value }
        }
        // Last known friends, so the island and the list work without network.
        if let data = defaults.data(forKey: Keys.cache),
           let saved = try? HyprnodeAPI.decoder.decode([Friend].self, from: data) {
            friends = saved
        }
        refusedNames = defaults.stringArray(forKey: Keys.refusedNames)

        careCode = Self.loadCareValue(PairCode.self, forKey: Keys.careCode)
        pendingCare = Self.loadCareValue(PendingCare.self, forKey: Keys.pendingCare)
        let activity = Self.loadCareValue([LossyElement<CareActivity>].self, forKey: Keys.careActivity) ?? []
        careActivity = CareActivity.merged([], into: activity.compactMap { $0.value }, now: .now)
        careSince = defaults.object(forKey: Keys.careSince) as? Date
        careDirty = defaults.bool(forKey: Keys.careDirty)

        // Every care action on this phone goes to the partner's, and a
        // changed name or look goes out with the next snapshot.
        let game = GameStore.shared
        game.onCare = { [weak self] event in
            MainActor.assumeIsolated { self?.sendCare(event) }
        }
        lookSubscription = game.$state
            .map { SharedLook($0.slime) }
            .removeDuplicates()
            .dropFirst()
            .sink { [weak self] _ in
                MainActor.assumeIsolated { self?.sharedLookChanged() }
            }
    }

    private var api: HyprnodeAPI { HyprnodeAPI(token: token) }

    /// A client for this device. Throws `NotOnlineError` while the player
    /// isn't online, so nothing is sent without their consent.
    func authorizedAPI() async throws -> HyprnodeAPI {
        reloadTokenIfNeeded()
        guard isOnline else { throw NotOnlineError() }
        if token == nil { try await register(whileOnline: true) }
        return api
    }

    /// This phone has a device on the server (online or not), so there is
    /// server data that can be deleted.
    var hasServerAccount: Bool { token != nil }

    /// The friend you chose to sit next to your slime in the island.
    var partner: Friend? {
        guard let partnerID else { return nil }
        return friends.first { $0.id == partnerID }
    }

    /// Friends for the island, most important first: the chosen one, then
    /// the oldest friendships.
    var islandFriends: [FriendSlime] {
        let sorted = friends.sorted { a, b in
            if a.id == partnerID { return true }
            if b.id == partnerID { return false }
            return a.since < b.since
        }
        return sorted.prefix(3).map(Self.islandSlime)
    }

    static func islandSlime(_ f: Friend) -> FriendSlime {
        FriendSlime(id: f.id, name: f.slime.name, owner: f.ownerName, skin: f.slime.skin, stage: f.stage,
                    mood: f.slime.mood, equipped: f.slime.equipped)
    }

    func friend(_ id: String?) -> Friend? {
        guard let id else { return nil }
        return friends.first { $0.id == id }
    }

    var openChallenges: [Challenge] { challenges.filter(\.isOpenForMe) }

    /// Challenges you sent that your friend hasn't played yet.
    var waitingChallenges: [Challenge] {
        challenges.filter { $0.sentByMe && $0.theirScore == nil && $0.expiresAt > .now }
    }

    /// Challenges both of you played, newest first.
    var finishedChallenges: [Challenge] {
        challenges
            .filter { $0.myScore != nil && $0.theirScore != nil }
            .sorted { ($0.answeredAt ?? $0.createdAt) > ($1.answeredAt ?? $1.createdAt) }
    }

    /// The name friends see, never empty.
    private var displayName: String {
        let name = HyprnodeAPI.cleanedName(ownerName)
        return name.isEmpty ? "Slime friend" : name
    }

    /// The slime's name as friends see it, never empty.
    private var slimeDisplayName: String {
        let cleaned = HyprnodeAPI.cleanedName(GameStore.shared.state.slime.name)
        let name = String(cleaned.prefix(HyprnodeAPI.slimeNameLimit)).trimmingCharacters(in: .whitespaces)
        return name.isEmpty ? "Slime" : name
    }

    /// Both names as the profile upload sends them.
    private var currentNames: [String] { [displayName, slimeDisplayName] }

    // MARK: Going online

    /// Registers this device (if it has none yet) and uploads the slime's
    /// profile. Call only after the player agreed to the Terms; it remembers
    /// that they did. The owner name must be set first. Returns false and
    /// sets `lastError` when it fails; when the server refuses a name, also
    /// `nameError`.
    func goOnline() async -> Bool {
        guard !connecting, !deletingAccount else { return false }
        let name = HyprnodeAPI.cleanedName(ownerName)
        guard !name.isEmpty else {
            lastError = "Please enter your name."
            return false
        }
        connecting = true
        defer { connecting = false }
        if ownerName != name { ownerName = name }
        banNotice = nil
        lastError = nil
        nameError = nil

        do {
            try await connect()
        } catch let error as APIError where error.isUnauthorized {
            // The server forgot this device (its data was deleted): start over.
            resetServerIdentity()
            do {
                try await connect()
            } catch {
                return failedToConnect(error)
            }
        } catch {
            return failedToConnect(error)
        }

        optedIn = true
        isOnline = true
        status = .online
        Task { await self.refresh() }
        return true
    }

    /// Stops all syncing. Friends, the queue and the token stay on the
    /// phone, so going online again picks up where it left off. Requests
    /// and challenges need the server to answer, so they go until then.
    /// Caring together stays set up; it just doesn't sync while offline.
    func goOffline() {
        optedIn = false
        isOnline = false
        status = .unknown
        pairCode = nil
        requests = RequestList(incoming: [], outgoing: [])
        challenges = []
        leaderboard = []
        knownNews = []
        carePushTask?.cancel()
        carePushTask = nil
        careProblem = nil
    }

    private func connect() async throws {
        // The Keychain may not have answered at launch. Its token keeps this
        // phone's device, friends and caring together; a new device would not.
        if token == nil, let stored = TokenVault.load() { token = stored }
        if token == nil {
            resetServerIdentity()
            try await register()
        }
        try await uploadProfile()
    }

    private func failedToConnect(_ error: Error) -> Bool {
        if let error = error as? APIError {
            if error.isBanned { banNotice = Self.banMessage(for: error) }
            if error.isRefused { nameError = error.localizedDescription }
        }
        lastError = error.localizedDescription
        return false
    }

    /// Registers a new device. `whileOnline` is for syncs and other stores:
    /// if the player went offline or deleted their data while the request
    /// was on its way, the new device is deleted again instead of kept.
    private func register(whileOnline: Bool = false) async throws {
        let registration = try await HyprnodeAPI().register(ownerName: displayName)
        if (whileOnline && !isOnline) || deletingAccount || token != nil {
            // Gone offline meanwhile, or another call registered first.
            try? await HyprnodeAPI(token: registration.token).deleteAccount()
            if token == nil { throw NotOnlineError() }
            return
        }
        token = registration.token
    }

    /// A Keychain read can fail for a moment (for example right after a
    /// restart); try again before treating the device as offline.
    private func reloadTokenIfNeeded() {
        guard token == nil, optedIn, !deletingAccount, let stored = TokenVault.load() else { return }
        token = stored
        isOnline = true
    }

    /// Forgets everything that belonged to the server's old device for this
    /// phone. Coins paid for gifts that never left are given back.
    private func resetServerIdentity() {
        for action in queue { refund(action) }
        token = nil
        inboxCursor = nil
        uploadedRecords = [:]
        queue = []
        saveQueue()
        friends = []
        cacheFriends()
        requests = RequestList(incoming: [], outgoing: [])
        challenges = []
        details = [:]
        blocked = []
        leaderboard = []
        pairCode = nil
        knownNews = []
        visitor = nil
        if partnerID != nil { partnerID = nil }
        // A device the server no longer has can't be caring together.
        endCareLocally(notice: nil)
    }

    /// What Friends and Go Online show after a ban. The server still lets
    /// a banned device delete its data.
    private static func banMessage(for error: APIError) -> String {
        "\(error.localizedDescription) You can still delete your data on the server in Settings under Privacy."
    }

    /// The server banned this device: stop syncing and say why. A ban also
    /// ends caring together on the server.
    private func handleBanned(_ error: APIError) {
        goOffline()
        endCareLocally(notice: nil)
        banNotice = Self.banMessage(for: error)
        lastError = error.localizedDescription
    }

    /// Shows a failed action's reason. A ban also takes the device offline;
    /// a device the server forgot (401) registers again in the background.
    private func fail(_ error: Error) {
        guard let error = error as? APIError else {
            lastError = error.localizedDescription
            return
        }
        if error.isBanned {
            handleBanned(error)
        } else if error.isUnauthorized {
            lastError = "Slimy is reconnecting to its server. Try again in a moment."
            Task { await refresh() }
        } else {
            lastError = error.localizedDescription
        }
    }

    /// Like `fail`, but a refused name (400) is also shown next to the name
    /// fields.
    private func failNames(_ error: Error) {
        if let error = error as? APIError, error.isRefused {
            nameError = error.localizedDescription
            lastError = error.localizedDescription
        } else {
            fail(error)
        }
    }

    /// For other stores that call the server through `authorizedAPI()`:
    /// lets a ban or a forgotten device take effect here too.
    func handleServerError(_ error: Error) {
        guard let error = error as? APIError else { return }
        if error.isBanned {
            handleBanned(error)
        } else if error.isUnauthorized {
            Task { await refresh() }
        }
    }

    private func requireOnline() -> Bool {
        reloadTokenIfNeeded()
        guard isOnline else {
            lastError = "Go online in Friends first."
            return false
        }
        return true
    }

    /// Stops a sync whose answers arrive after the player went offline or
    /// deleted their data, so nothing comes back into the store.
    private func checkOnline() throws {
        guard isOnline else { throw NotOnlineError() }
    }

    // MARK: Sync

    func refresh() async {
        reloadTokenIfNeeded()
        guard isOnline, !refreshing, !deletingAccount else { return }
        refreshing = true
        defer { refreshing = false }
        do {
            try await sync()
        } catch let error as APIError where error.isUnauthorized {
            // The server no longer knows this device. If the player is still
            // online (not deleting their data), register a fresh one.
            guard isOnline, !deletingAccount else { return }
            resetServerIdentity()
            do {
                try await sync()
            } catch {
                syncFailed(error)
            }
        } catch {
            syncFailed(error)
        }
        if let visitor, visitor.until < .now { self.visitor = nil }
        // Coming to the front also starts the care poll; one `GET /care`
        // within a few seconds is enough.
        let careFresh = careSyncedAt.map { Date.now.timeIntervalSince($0) < Self.careRecentSync } ?? false
        if !careFresh { await syncCare() }
        await IslandController.shared.sync()
    }

    private func sync() async throws {
        try checkOnline()
        let names = currentNames
        let namesRefused = refusedNames == names
        if token == nil {
            // A new device needs an owner name the server takes.
            guard !namesRefused else {
                status = .unavailable("Friends can't sync until you choose different names.")
                return
            }
            do {
                try await register(whileOnline: true)
            } catch let error as APIError where error.isRefused {
                rememberRefused(names)
                throw error
            }
        }
        if !namesRefused {
            do {
                try await uploadProfile()
            } catch let error as APIError where error.isRefused {
                rememberRefused(names)
            }
            try checkOnline()
        }
        let me = try await api.me()
        try checkOnline()
        privacy = me.privacy
        await flushQueue()
        let fetchedFriends = try await api.friends()
        try checkOnline()
        friends = fetchedFriends
        cacheFriends()
        if let partnerID, !friends.contains(where: { $0.id == partnerID }) { self.partnerID = nil }
        let fetchedRequests = try await api.requests()
        try checkOnline()
        requests = fetchedRequests
        let fetchedChallenges = try await api.challenges()
        try checkOnline()
        challenges = fetchedChallenges
        try await readInbox()
        await syncRecords()
        try checkOnline()
        status = .online

        let news = Set(requests.incoming.map(\.id) + openChallenges.map(\.id))
        if !news.subtracting(knownNews).isEmpty { hasNews = true }
        knownNews = news
    }

    private func syncFailed(_ error: Error) {
        if error is NotOnlineError { return }
        if let error = error as? APIError, error.isBanned {
            handleBanned(error)
        } else {
            status = .unavailable(error.localizedDescription)
        }
    }

    /// Uploads the slime's profile with both names. When the server takes
    /// it, a notice about refused names goes away.
    private func uploadProfile() async throws {
        let slime = GameStore.shared.state.slime
        try await api.upload(SlimeProfile(
            ownerName: displayName, name: slimeDisplayName, skin: slime.skin,
            level: slime.level, stage: slime.stage, mood: slime.mood, happiness: Int(slime.needs.average),
            equipped: slime.equipped, updatedAt: .now))
        refusedNames = nil
    }

    /// The server refused these names: syncs stop sending them, and Friends
    /// asks for new ones (`profileNotice`).
    private func rememberRefused(_ names: [String]) {
        refusedNames = names
    }

    /// Changes the name friends see. Offline it is only stored; it goes up
    /// with the profile when the player goes online. A refused name sets
    /// `nameError` and keeps the previous one.
    func rename(to name: String) async -> Bool {
        let cleaned = HyprnodeAPI.cleanedName(name)
        guard !cleaned.isEmpty else {
            lastError = "Please enter your name."
            return false
        }
        let previous = ownerName
        ownerName = cleaned
        nameError = nil
        guard isOnline else { return true }
        do {
            if token == nil { try await register(whileOnline: true) }
            try await uploadProfile()
            return true
        } catch {
            ownerName = previous
            failNames(error)
            return false
        }
    }

    /// Sends the slime's current name and look to friends right away, for
    /// example after renaming it. A refused name sets `nameError`.
    func uploadProfileNow() async -> Bool {
        guard requireOnline() else { return false }
        nameError = nil
        do {
            if token == nil { try await register(whileOnline: true) }
            try await uploadProfile()
            return true
        } catch {
            failNames(error)
            return false
        }
    }

    private func cacheFriends() {
        if let data = try? HyprnodeAPI.encoder.encode(friends) { defaults.set(data, forKey: Keys.cache) }
    }

    private func readInbox() async throws {
        var page = try await api.inbox(after: inboxCursor)
        var rounds = 0
        while true {
            try checkOnline()
            for event in page.events { handle(event) }
            if let cursor = page.cursor { inboxCursor = cursor }
            rounds += 1
            guard page.events.count == 50, rounds < 5 else { break }
            page = try await api.inbox(after: inboxCursor)
        }
    }

    /// What a friend did. Each kind only shows something or gives food;
    /// nothing a friend sends can change settings or act for you.
    private func handle(_ event: InboxEvent) {
        let name = event.friendName ?? friend(event.friendId)?.slime.name ?? "A slime"
        let store = GameStore.shared
        let earlierToast = store.toast?.id
        // Love between slimes is gone; a friend on an older app can still
        // send it, and it is simply skipped.
        guard event.kind != "inLove" else { return }
        hasNews = true
        defer {
            // In the background (refresh), a few kinds also become a notification,
            // with the line this event showed (never an older toast).
            if UIApplication.shared.applicationState != .active,
               ["request", "accepted", "gift", "message", "challenge"].contains(event.kind),
               let toast = store.toast, toast.id != earlierToast {
                Notifier.post(event.kind == "gift" ? .gifts : .friends, title: name, body: toast.text, state: store.state)
            }
        }
        switch event.kind {
        case "request":
            store.toast = Toast(text: "\(name) wants to be friends", good: true)
        case "accepted":
            store.update { $0.madeFriend(name, now: .now) }
            store.toast = Toast(text: "\(name) is your friend now", good: true)
            if let id = event.friendId { Task { await IslandController.shared.show(.reunion, with: id) } }
        case "gift":
            var line = ""
            store.update { line = $0.receiveGift(event.food ?? .berry, from: name, now: .now) }
            store.toast = Toast(text: line, good: true)
            if let id = event.friendId { Task { await IslandController.shared.show(.gift, with: id) } }
        case "hug", "kiss":
            // A kiss comes from a friend on an older app; it arrives as a hug.
            store.toast = Toast(text: "\(name): hug!", good: true)
            // One update, so the feelings are saved and synced once.
            store.update {
                $0.slime.needs.add(.love, 4)
                $0.slime.feel(.love, 0.15)
                $0.slime.feel(.joy, 0.15)
                $0.slime.react(.blush, at: .now)
            }
            if let id = event.friendId { Task { await IslandController.shared.show(.hug, with: id) } }
        case "visit":
            store.toast = Toast(text: "\(name) came to visit", good: true)
            store.update {
                $0.slime.feel(.joy, 0.25)
                $0.slime.feel(.excitement, 0.2)
            }
            if let f = friend(event.friendId) { visitor = (Self.islandSlime(f), Date.now.addingTimeInterval(10 * 60)) }
            if let id = event.friendId { Task { await IslandController.shared.show(.reunion, with: id) } }
        case "message":
            guard let preset = event.preset else { return }
            store.toast = Toast(text: "\(name): \(preset.text)", good: true)
            let moment: SharedMoment? = switch preset {
            case .missYou: .missing
            case .goodNight: .sleep
            case .letsPlay: .play
            case .congrats, .proud: .achievement
            default: nil
            }
            if let moment, let id = event.friendId { Task { await IslandController.shared.show(moment, with: id) } }
        case "challenge":
            store.toast = Toast(text: "\(name) challenges you", good: true)
        case "challengeResult":
            store.toast = Toast(text: "\(name) played your challenge", good: true)
        case "coopDone":
            store.toast = Toast(text: "Your shared garden with \(name) is full", good: true)
            if let id = event.friendId { Task { await IslandController.shared.show(.achievement, with: id) } }
        default:
            break
        }
    }

    // MARK: Pairing

    /// A fresh code to give to a friend (10 minutes, single use).
    @discardableResult
    func makeCode() async -> Bool {
        guard requireOnline() else { return false }
        do {
            pairCode = try await api.newPairCode()
            lastError = nil
            return true
        } catch {
            fail(error)
            return false
        }
    }

    /// The code is still usable.
    var activePairCode: PairCode? {
        guard let pairCode, pairCode.expiresAt > .now else { return nil }
        return pairCode
    }

    /// Connects with the owner of a friend's code. Returns a line to show
    /// on success.
    func join(code: String) async -> String? {
        let cleaned = FriendInvite.sanitize(code)
        guard cleaned.count == FriendInvite.length else {
            lastError = "Friend codes have \(FriendInvite.length) characters."
            return nil
        }
        guard requireOnline() else { return nil }
        do {
            let result = try await api.join(code: cleaned)
            lastError = nil
            if pendingInviteCode == cleaned { pendingInviteCode = nil }
            if let friend = result.friend {
                adopt(friend)
                return "You and \(friend.slime.name) are friends now."
            }
            // The request is sent; a failed reload must not report it as lost.
            if let request = result.request, !requests.outgoing.contains(where: { $0.id == request.id }) {
                requests.outgoing.append(request)
            }
            if let fetched = try? await api.requests(), isOnline { requests = fetched }
            return "Request sent. You're friends once it's accepted."
        } catch {
            fail(error)
            return nil
        }
    }

    @discardableResult
    func accept(_ request: FriendRequest) async -> Bool {
        guard requireOnline() else { return false }
        do {
            let friend = try await api.accept(request.id)
            requests.incoming.removeAll { $0.id == request.id }
            adopt(friend)
            return true
        } catch {
            fail(error)
            return false
        }
    }

    /// Declines a request to you, or withdraws one you sent.
    @discardableResult
    func decline(_ request: FriendRequest) async -> Bool {
        guard requireOnline() else { return false }
        do {
            try await api.decline(request.id)
            requests.incoming.removeAll { $0.id == request.id }
            requests.outgoing.removeAll { $0.id == request.id }
            return true
        } catch {
            fail(error)
            return false
        }
    }

    private func adopt(_ friend: Friend) {
        let isNew = !friends.contains { $0.id == friend.id }
        friends.removeAll { $0.id == friend.id }
        friends.append(friend)
        cacheFriends()
        if isNew {
            GameStore.shared.update { $0.madeFriend(friend.slime.name, now: .now) }
            Task { await IslandController.shared.show(.reunion, with: friend.id) }
        }
    }

    @discardableResult
    func remove(_ friend: Friend) async -> Bool {
        guard requireOnline() else { return false }
        do {
            try await api.removeFriend(friend.id)
            forget(friend.id)
            return true
        } catch {
            fail(error)
            return false
        }
    }

    @discardableResult
    func block(deviceID: String) async -> Bool {
        guard requireOnline() else { return false }
        do {
            try await api.block(deviceID)
            forget(deviceID)
            requests.incoming.removeAll { $0.deviceId == deviceID }
            requests.outgoing.removeAll { $0.deviceId == deviceID }
            challenges.removeAll { $0.friendId == deviceID }
            if visitor?.friend.id == deviceID { visitor = nil }
            // Blocking the person you care for a slime with ends that too.
            if GameStore.shared.state.care?.partnerID == deviceID || pendingCare?.partnerID == deviceID {
                endCareLocally(notice: nil)
            }
            blocked = (try? await api.blocked()) ?? blocked
            return true
        } catch {
            fail(error)
            return false
        }
    }

    @discardableResult
    func unblock(_ device: BlockedDevice) async -> Bool {
        guard requireOnline() else { return false }
        do {
            try await api.unblock(device.deviceId)
            blocked.removeAll { $0.deviceId == device.deviceId }
            return true
        } catch {
            fail(error)
            return false
        }
    }

    func loadBlocked() async {
        guard isOnline else { return }
        do {
            let list = try await api.blocked()
            if isOnline { blocked = list }
        } catch {
            fail(error)
        }
    }

    private func forget(_ id: String) {
        friends.removeAll { $0.id == id }
        details[id] = nil
        for action in queue where action.friendID == id { refund(action) }
        queue.removeAll { $0.friendID == id }
        saveQueue()
        cacheFriends()
        if partnerID == id { partnerID = nil }
    }

    func loadDetail(_ friend: Friend) async {
        guard isOnline else { return }
        do {
            let detail = try await api.friend(friend.id)
            guard isOnline else { return }
            details[friend.id] = detail
            update(friend.id) { $0 = detail.friend }
        } catch {
            fail(error)
        }
    }

    @discardableResult
    func setPrivacy(_ change: (inout Privacy) -> Void) async -> Bool {
        guard requireOnline() else { return false }
        var next = privacy
        change(&next)
        do {
            privacy = try await api.setPrivacy(next)
            if !privacy.acceptRequests { pairCode = nil }
            return true
        } catch {
            fail(error)
            return false
        }
    }

    /// Deletes everything the server knows about this phone, then goes
    /// offline and forgets the token, the name, the queue and the friends.
    /// The slime itself stays. Works while banned too: the server lets a
    /// banned device delete its data.
    func deleteAccount() async -> Bool {
        guard !deletingAccount else { return false }
        deletingAccount = true
        defer { deletingAccount = false }
        // The Keychain copy too, in case reading it failed at launch.
        let token = self.token ?? TokenVault.load()
        let wasOptedIn = optedIn
        let wasOnline = isOnline
        // Stop syncing first, so a refresh that is running can't register a
        // new device or bring friends back while the delete is on its way.
        optedIn = false
        isOnline = false
        if let token {
            do {
                try await HyprnodeAPI(token: token).deleteAccount()
            } catch let error as APIError where error.isUnauthorized {
                // Already gone on the server.
            } catch let error as APIError where error.isBanned {
                handleBanned(error)
                lastError = "\(error.localizedDescription) To have its data deleted, contact us at slime.hyprnode.de/support."
                return false
            } catch {
                optedIn = wasOptedIn
                isOnline = wasOnline
                lastError = error.localizedDescription
                return false
            }
        }
        resetServerIdentity()
        // `token = nil` only clears the Keychain when the copy was set.
        TokenVault.save(nil)
        refusedNames = nil
        nameError = nil
        goOffline()
        ownerName = ""
        privacy = Privacy(acceptRequests: true, shareMood: true)
        status = .unknown
        banNotice = nil
        pendingInviteCode = nil
        hasNews = false
        lastError = nil
        await IslandController.shared.sync()
        return true
    }

    // MARK: Reports

    /// Sends a report to the Slimy team. It doesn't block anyone; that's a
    /// separate choice. `deviceID` is needed unless the reason is `.chat`,
    /// where `text` is the slime's reply.
    func report(deviceID: String?, reason: ReportReason, note: String?, text: String?) async -> Bool {
        guard requireOnline() else { return false }
        if reason != .chat, deviceID == nil {
            lastError = "Choose who you're reporting."
            return false
        }
        let note = note.map { String($0.trimmingCharacters(in: .whitespacesAndNewlines).prefix(HyprnodeAPI.reportNoteLimit)) }
        let text = text.map { String($0.trimmingCharacters(in: .whitespacesAndNewlines).prefix(HyprnodeAPI.reportTextLimit)) }
        let body = HyprnodeAPI.ReportBody(
            deviceId: deviceID, reason: reason,
            note: note?.isEmpty == false ? note : nil,
            text: text?.isEmpty == false ? text : nil)
        do {
            // A second report of the same thing still answers 204.
            try await api.report(body, key: UUID().uuidString)
            lastError = nil
            return true
        } catch {
            if deviceID != nil, let apiError = error as? APIError, apiError.status == 404 {
                // They deleted their data; a sync takes them off the lists.
                Task { await self.refresh() }
            }
            fail(error)
            return false
        }
    }

    // MARK: Records and leaderboard

    /// Sends best scores the server doesn't have yet. It only ever raises
    /// them, so sending again is harmless.
    func uploadRecords(_ records: [GameID: Int]) async {
        guard isOnline, token != nil else { return }
        let known = uploadedRecords
        let fresh = HyprnodeAPI.wireRecords(records).filter { $0.value > known[$0.key, default: 0] }
        guard !fresh.isEmpty else { return }
        do {
            try await api.uploadRecords(fresh)
            guard isOnline else { return }
            uploadedRecords = uploadedRecords.merging(fresh) { max($0, $1) }
        } catch let error as APIError where error.isBanned {
            handleBanned(error)
        } catch {
            // Sent again with the next refresh.
        }
    }

    /// Every best score on this phone.
    private var localRecords: [GameID: Int] {
        GameStore.shared.state.progress.records.mapValues { $0.best }
    }

    private func syncRecords() async {
        await uploadRecords(localRecords)
    }

    func loadLeaderboard() async {
        guard isOnline else {
            leaderboard = []
            return
        }
        isLoadingLeaderboard = true
        defer { isLoadingLeaderboard = false }
        await syncRecords()
        // Sending the records can end in a ban, which takes the device offline.
        guard isOnline else {
            leaderboard = []
            return
        }
        do {
            let games = try await api.leaderboard()
            guard isOnline else { return }
            let order = GameID.allCases
            leaderboard = games.sorted {
                (order.firstIndex(of: $0.game) ?? order.count) < (order.firstIndex(of: $1.game) ?? order.count)
            }
            lastError = nil
        } catch {
            fail(error)
        }
    }

    // MARK: Invites

    /// Takes a friend code from an invite link (https://slime.hyprnode.de/f/CODE
    /// or slimy://f/CODE). Returns false for other links.
    @discardableResult
    func openInvite(_ url: URL) -> Bool {
        guard let code = FriendInvite.code(from: url) else { return false }
        pendingInviteCode = code
        hasNews = true
        return true
    }

    // MARK: Hugs, visits and gifts

    /// Returns the line to show, or nil if it failed.
    func interact(with friend: Friend, _ kind: InteractionKind, food: Food? = nil) async -> String? {
        guard requireOnline() else { return nil }
        if kind == .gift {
            guard let food, GameStore.shared.state.wallet.coins >= food.price else {
                lastError = "Not enough coins for \(food?.title ?? "the gift")."
                return nil
            }
        }

        let action = PendingAction(kind: .interaction(friend.id, kind, food), coins: kind == .gift ? food?.price ?? 0 : 0)
        // Pay for a gift now; a final "no" from the server refunds it.
        if action.coins > 0 { GameStore.shared.update { _ = $0.wallet.spend(action.coins) } }
        do {
            let result = try await api.interact(with: friend.id, kind: kind, food: food, key: action.key)
            return applied(result, to: friend, kind, food)
        } catch let error as APIError where error.shouldQueue {
            enqueue(action)
            return "Will arrive once you're back online."
        } catch {
            refund(action)
            fail(error)
            return nil
        }
    }

    private func applied(_ result: InteractionResult, to friend: Friend, _ kind: InteractionKind, _ food: Food?) -> String {
        // Today's limit for this kind: it didn't reach the friend.
        if result.capped == true, let message = result.message { return message }

        let category: LineCategory = switch kind {
        case .hug: .friendHug
        case .visit: .friendVisit
        case .gift: .gift
        }
        GameStore.shared.update { _ = $0.friendMoment(category, friend: friend.slime.name, now: .now) }
        let moment: SharedMoment = switch kind {
        case .hug: .hug
        case .visit: .reunion
        case .gift: .gift
        }
        Task { await IslandController.shared.show(moment, with: friend.id) }
        lastError = nil

        switch kind {
        case .hug: return "You hugged \(friend.slime.name)."
        case .visit: return "Your slime is visiting \(friend.slime.name)."
        case .gift: return "\(food?.title ?? "Gift") sent to \(friend.slime.name)."
        }
    }

    func send(_ preset: MessagePreset, to friend: Friend) async -> String? {
        guard requireOnline() else { return nil }
        let action = PendingAction(kind: .message(friend.id, preset))
        do {
            try await api.message(friend.id, preset: preset, key: action.key)
            lastError = nil
            return "\u{201C}\(preset.text)\u{201D} sent to \(friend.slime.name)."
        } catch let error as APIError where error.shouldQueue {
            enqueue(action)
            return "Will arrive once you're back online."
        } catch {
            fail(error)
            return nil
        }
    }

    // MARK: Arcade together

    /// A finished round adds to the co-op garden you share with the friend
    /// in your island (or your oldest friend). Points are the round's
    /// quality, not its raw score, so every game counts the same.
    func contributeCoop(_ result: GameResult) {
        guard isOnline, token != nil, result.score > 0,
              let target = partner ?? friends.min(by: { $0.since < $1.since }) else { return }
        // `result.goodScore` is the day's own target for the daily game.
        let points = Int(min(150, Double(result.score) / Double(result.goodScore) * 100))
        guard points > 0 else { return }
        enqueue(PendingAction(kind: .coop(target.id, points)))
        Task { await flushQueue() }
    }

    func challenge(_ friend: Friend, with result: GameResult, seed: UInt64) async -> String? {
        guard requireOnline() else { return nil }
        let action = PendingAction(kind: .challenge(friend.id, result.game, String(seed), result.score))
        do {
            let c = try await api.challenge(friend.id, game: result.game, seed: seed, score: result.score, key: action.key)
            challenges.insert(c, at: 0)
            return "Challenge sent to \(friend.slime.name)."
        } catch let error as APIError where error.shouldQueue {
            enqueue(action)
            return "Will be sent once you're back online."
        } catch {
            fail(error)
            return nil
        }
    }

    func answer(_ challenge: Challenge, score: Int) async {
        guard requireOnline() else { return }
        let action = PendingAction(kind: .answer(challenge.id, score))
        do {
            let updated = try await api.answer(challenge.id, score: score, key: action.key)
            if let i = challenges.firstIndex(where: { $0.id == updated.id }) { challenges[i] = updated }
        } catch let error as APIError where error.shouldQueue {
            if let i = challenges.firstIndex(where: { $0.id == challenge.id }) { challenges[i].myScore = score }
            enqueue(action)
        } catch {
            fail(error)
        }
    }

    // MARK: Care together

    /// How often caring together syncs while the app is on screen. Every
    /// request counts toward the server's hourly limit per device (1200),
    /// and each answer carries the whole snapshot.
    static let carePollInterval: TimeInterval = 20
    /// How often it checks while a code waits for someone, or you wait for
    /// your partner's slime to arrive. Never faster than about every 10 s.
    static let careWaitInterval: TimeInterval = 12
    /// A refresh skips `GET /care` when one answered this recently.
    static let careRecentSync: TimeInterval = 10
    /// Snapshots go out at most this often.
    static let careSnapshotSpacing: TimeInterval = 60
    /// Petting is sent to the partner at most this often; the snapshot
    /// carries the rest.
    static let carePetSpacing: TimeInterval = 30

    /// Your slime is shared with a partner right now.
    var isSharing: Bool { GameStore.shared.isSharing }

    /// The care code while it can still be used.
    var activeCareCode: PairCode? {
        guard let careCode, careCode.expiresAt > .now else { return nil }
        return careCode
    }

    /// A care code is out there, or you joined someone and wait.
    var isWaitingForCare: Bool { !isSharing && (careCode != nil || pendingCare != nil) }

    /// Something about caring together needs the server.
    private var wantsCareSync: Bool { isSharing || careCode != nil || pendingCare != nil || careDiscovery }

    /// A fresh code for someone to care for your slime with you (10
    /// minutes, single use).
    @discardableResult
    func makeCareCode() async -> Bool {
        guard requireOnline() else { return false }
        if isSharing {
            lastError = "You're already caring for a slime together."
            return false
        }
        if let pendingCare {
            lastError = "You're joining \(pendingCare.partnerName)'s slime. Cancel that first to make your own code."
            return false
        }
        do {
            let code = try await api.newCareCode()
            try checkOnline()
            careCode = code
            careProblem = nil
            lastError = nil
            return true
        } catch {
            if let apiError = error as? APIError, apiError.status == 409 {
                // The server knows a pairing this phone doesn't: pick it up.
                Task { await self.syncCare(discover: true) }
            }
            fail(error)
            return false
        }
    }

    /// Joins the slime behind a care code. On success `pendingCare` holds
    /// who it belongs to; the slime moves here once you confirm
    /// (`confirmJoin()`).
    func joinCare(code: String) async -> Bool {
        let cleaned = FriendInvite.sanitize(code)
        guard cleaned.count == FriendInvite.length else {
            lastError = "Care codes have \(FriendInvite.length) characters."
            return false
        }
        guard requireOnline() else { return false }
        if isSharing {
            lastError = "You're already caring for a slime together. Stop sharing first."
            return false
        }
        if pendingCare != nil {
            lastError = "You're already joining a slime. Finish or cancel that first."
            return false
        }
        if activeCareCode?.code == cleaned {
            lastError = "That's your own code. Give it to the person you want to care with."
            return false
        }
        do {
            let joined = try await api.joinCare(code: cleaned)
            try checkOnline()
            careCode = nil
            pendingCare = PendingCare(partnerID: joined.partner.deviceId, partnerName: joined.partner.ownerName,
                                      slimeName: joined.snapshotName, snapshot: joined.snapshot,
                                      version: joined.version, cursor: nil, confirmed: false, joinedAt: .now)
            careEpoch += 1
            careProblem = nil
            lastError = nil
            return true
        } catch {
            if let apiError = error as? APIError, apiError.status == 409 {
                Task { await self.syncCare(discover: true) }
            }
            fail(error)
            return false
        }
    }

    /// You said yes to the slime you joined: it moves onto this phone now,
    /// or as soon as your partner's phone has sent it. Your own slime is
    /// kept safe and comes back when you stop sharing.
    func confirmJoin() async {
        guard var pending = pendingCare else { return }
        pending.confirmed = true
        pendingCare = pending
        if let snapshot = pending.snapshot {
            adopt(snapshot, from: pending)
        } else {
            await syncCare()
        }
    }

    /// Takes back joining someone's slime before it moved here. Your own
    /// slime never left.
    func cancelJoin() async -> Bool {
        guard pendingCare != nil else { return true }
        guard requireOnline(), !careLeaving else { return false }
        careLeaving = true
        defer { careLeaving = false }
        careEpoch += 1
        do {
            try await api.leaveCare()
        } catch let error as APIError where error.status == 409 || error.status == 404 {
            // Already over on the server.
        } catch {
            fail(error)
            return false
        }
        pendingCare = nil
        careEpoch += 1
        lastError = nil
        return true
    }

    /// Stops caring together for both of you. The joiner gets their own
    /// slime back; the host keeps the slime.
    func leaveCare() async -> Bool {
        guard isSharing || pendingCare != nil else { return true }
        guard requireOnline(), !careLeaving else { return false }
        await pushCareSnapshot()
        // A sync answering meanwhile would still show the pairing, or that
        // it ended, as if the partner had stopped.
        careLeaving = true
        defer { careLeaving = false }
        careEpoch += 1
        do {
            try await api.leaveCare()
        } catch let error as APIError where error.status == 409 || error.status == 404 {
            // The partner already ended it.
        } catch {
            fail(error)
            return false
        }
        endCareLocally(notice: nil)
        lastError = nil
        return true
    }

    /// Brings caring together up to date: who you care with, your partner's
    /// actions (applied to the slime here), and the newest snapshot. Also
    /// notices when a code was used or your partner stopped sharing.
    /// `discover` checks once even when this phone knows of no pairing.
    func syncCare(discover: Bool = false) async {
        reloadTokenIfNeeded()
        if discover { careDiscovery = true }
        guard isOnline, token != nil, !careSyncing, !careLeaving, !deletingAccount, wantsCareSync else { return }
        careSyncing = true
        defer {
            careSyncing = false
            careDiscovery = false
        }
        do {
            // Your own actions go out before the partner's come in.
            if queue.contains(where: \.isCare) { await flushQueue() }
            var rounds = 0
            while true {
                let epoch = careEpoch
                let cursor = GameStore.shared.state.care?.cursor ?? pendingCare?.cursor
                let status = try await api.care(after: cursor)
                try checkOnline()
                rounds += 1
                if epoch != careEpoch || careLeaving {
                    // You joined or stopped while this was on its way: the
                    // answer is from before. Ask again unless you're leaving.
                    guard rounds < 5, !careLeaving else { break }
                    continue
                }
                let more = applyCare(status)
                guard more, rounds < 5 else { break }
            }
            careSyncedAt = .now
            // A refused name keeps its notice until the slime has a new one.
            careProblem = refusedSnapshotProblem
        } catch {
            careSyncFailed(error)
        }
        if careDirty, isSharing { scheduleCarePush() }
    }

    /// Why the shared slime's changes don't reach the partner: the server
    /// refused its name, and it still has that name. Ends with the server's
    /// own words.
    private var refusedSnapshotProblem: String? {
        guard isSharing, let refusedSnapshotName, refusedSnapshotName == GameStore.shared.state.slime.name else { return nil }
        let reason = refusedSnapshotMessage ?? "Please choose a different name."
        return "slime.hyprnode.de didn't accept the name \(refusedSnapshotName), so your partner doesn't see changes to it. \(reason)"
    }

    /// The shared slime's name was refused and it still has it. Shown where
    /// the player can rename it; a new name is sent with the next snapshot.
    var careNameNotice: String? { refusedSnapshotProblem }

    private func careSyncFailed(_ error: Error) {
        if error is NotOnlineError { return }
        guard let error = error as? APIError else {
            careProblem = error.localizedDescription
            return
        }
        if error.isBanned {
            handleBanned(error)
        } else if error.isUnauthorized {
            Task { await refresh() }
        } else {
            careProblem = error.localizedDescription
        }
    }

    /// Applies one answer of `GET /care`. Returns whether more of the
    /// partner's actions are waiting.
    private func applyCare(_ status: CareStatus) -> Bool {
        let store = GameStore.shared
        guard let partner = status.partner else {
            careNotPaired()
            return false
        }
        // Paired with someone else than this phone thought: start over.
        if let link = store.state.care, link.partnerID != partner.deviceId {
            endCareLocally(notice: nil)
        }

        if let link = store.state.care {
            var changed = link
            changed.partnerName = partner.ownerName
            if let cursor = status.cursor { changed.cursor = cursor }
            let fresh = status.events.filter { $0.deviceId == link.partnerID }
            applyPartnerEvents(fresh)
            if status.version > link.version {
                if let snapshot = status.snapshot { reconcileShared(snapshot) }
                changed.version = status.version
            }
            if changed != link { setCareLink(changed) }
            if careSince == nil { careSince = .now }
            // The host sends the slime first, so the joiner gets it.
            if changed.role == CareRole.host, status.version == 0, status.snapshot == nil {
                Task { await self.pushCareSnapshot(force: true) }
            }
            return status.events.count >= 100
        }

        if var pending = pendingCare {
            // You joined: keep the newest slime and skip actions the slime
            // already shows, until it moves here.
            pending.partnerID = partner.deviceId
            pending.partnerName = partner.ownerName
            if let cursor = status.cursor { pending.cursor = cursor }
            if let snapshot = status.snapshot {
                pending.snapshot = snapshot
                pending.slimeName = status.snapshotName ?? pending.slimeName
                pending.version = status.version
            }
            pendingCare = pending
            if pending.confirmed, let snapshot = pending.snapshot {
                adopt(snapshot, from: pending)
                return false
            }
            return status.events.count >= 100
        }

        // A pairing this phone doesn't know yet: your code was used, or you
        // joined on an earlier launch.
        let role = careCode != nil ? CareRole.host : partner.myRole
        if role == CareRole.host {
            startHosting(with: partner, status: status)
        } else if role == CareRole.joiner {
            pendingCare = PendingCare(partnerID: partner.deviceId, partnerName: partner.ownerName,
                                      slimeName: status.snapshotName, snapshot: status.snapshot,
                                      version: status.version, cursor: status.cursor, confirmed: false,
                                      joinedAt: .now)
        }
        return false
    }

    /// The server says nobody cares together with you (anymore).
    private func careNotPaired() {
        let store = GameStore.shared
        if let link = store.state.care {
            let name = link.partnerName
            let shared = store.state.slime.name
            let wasHost = link.role == CareRole.host
            endCareLocally(notice: nil)
            let line = wasHost
                ? "\(name) stopped caring for \(shared) with you."
                : "\(name) stopped sharing \(shared). \(store.state.slime.name) is back with you."
            announceCare(line)
        } else if let pending = pendingCare {
            pendingCare = nil
            announceCare("\(pending.partnerName) stopped sharing before their slime arrived.")
        } else if let code = careCode, code.expiresAt <= .now {
            // Nobody used the code before it ran out.
            careCode = nil
        }
    }

    /// Someone joined with your code: from now on you care for your slime
    /// together, and their phone needs it right away.
    private func startHosting(with partner: CarePartner, status: CareStatus) {
        let store = GameStore.shared
        let link = CareLink(partnerID: partner.deviceId, partnerName: partner.ownerName, role: CareRole.host,
                            cursor: status.cursor, version: status.version)
        store.startHosting(link: link)
        careCode = nil
        careSince = .now
        careActivity = []
        announceCare("\(partner.ownerName) joined. You care for \(store.state.slime.name) together now.")
        Task { await self.pushCareSnapshot(force: true) }
    }

    /// The slime you joined moves onto this phone; your own is kept safe.
    private func adopt(_ snapshot: SlimeSnapshot, from pending: PendingCare) {
        let link = CareLink(partnerID: pending.partnerID, partnerName: pending.partnerName, role: CareRole.joiner,
                            cursor: pending.cursor, version: pending.version)
        let store = GameStore.shared
        applyingRemote = true
        store.adoptShared(snapshot, link: link)
        applyingRemote = false
        careEpoch += 1
        pendingCare = nil
        careCode = nil
        careSince = .now
        careActivity = []
        careDirty = false
        lastCarePush = .now
        announceCare("You care for \(store.state.slime.name) with \(pending.partnerName) now.")
        Task { await IslandController.shared.sync() }
    }

    /// Ends caring together on this phone only: the joiner's own slime
    /// comes back, the host keeps the slime; codes, waiting actions and the
    /// activity list go.
    private func endCareLocally(notice: String?) {
        let store = GameStore.shared
        if store.isSharing {
            applyingRemote = true
            store.stopSharing()
            applyingRemote = false
        }
        careEpoch += 1
        pendingCare = nil
        careCode = nil
        careSince = nil
        careActivity = []
        careProblem = nil
        careDirty = false
        refusedSnapshotName = nil
        refusedSnapshotMessage = nil
        carePushTask?.cancel()
        carePushTask = nil
        if queue.contains(where: \.isCare) {
            queue.removeAll(where: \.isCare)
            saveQueue()
        }
        if let notice { announceCare(notice) }
    }

    /// Shows a line about caring together: a toast, and in the background a
    /// notification.
    private func announceCare(_ line: String) {
        let store = GameStore.shared
        store.toast = Toast(text: line, good: true)
        if UIApplication.shared.applicationState != .active {
            Notifier.post(.friends, title: "Care Together", body: line, state: store.state)
        }
    }

    /// The partner's actions, applied to the shared slime and listed in the
    /// activity. Each one only once.
    private func applyPartnerEvents(_ wires: [CareEventWire]) {
        let known = Set(careActivity.map(\.id))
        let fresh = wires.filter { !known.contains($0.id) }.sorted { $0.at < $1.at }
        guard !fresh.isEmpty else { return }
        let store = GameStore.shared
        applyingRemote = true
        store.applyPartnerCare(fresh.map(\.event))
        applyingRemote = false
        let slimeName = store.state.slime.name
        careActivity = CareActivity.merged(fresh.map { CareActivity(wire: $0, slimeName: slimeName) },
                                           into: careActivity, now: .now)
    }

    private func reconcileShared(_ snapshot: SlimeSnapshot) {
        applyingRemote = true
        GameStore.shared.reconcile(with: snapshot)
        applyingRemote = false
    }

    private func setCareLink(_ link: CareLink) {
        GameStore.shared.update { $0.care = link }
    }

    /// One of your care actions: off to the partner's phone through the
    /// queue, and the slime is due for a snapshot.
    private func sendCare(_ event: CareEvent) {
        guard isSharing else { return }
        // Offline, the snapshot still goes out once you're back online.
        markCareChanged()
        guard isOnline, token != nil else { return }
        if event.kind == .pet {
            guard Date.now.timeIntervalSince(lastPetSent) >= Self.carePetSpacing else { return }
            lastPetSent = .now
        }
        enqueue(PendingAction(kind: .care(event.kind, event.food, event.game, event.amount, event.at)))
        Task { await self.flushQueue() }
    }

    private func sharedLookChanged() {
        guard !applyingRemote, isSharing else { return }
        markCareChanged()
    }

    private func markCareChanged() {
        careDirty = true
        scheduleCarePush()
    }

    /// Sends the snapshot once the minute since the last one is over.
    private func scheduleCarePush() {
        guard carePushTask == nil, isOnline, isSharing else { return }
        let wait = max(2, Self.careSnapshotSpacing - Date.now.timeIntervalSince(lastCarePush))
        carePushTask = Task { [weak self] in
            do {
                try await Task.sleep(for: .seconds(wait))
            } catch {
                return
            }
            guard let self else { return }
            self.carePushTask = nil
            await self.pushCareSnapshot()
        }
    }

    /// Stores the shared slime on the server when it changed here (or
    /// `force`). If the other phone saved first, its slime is reconciled
    /// with this one and sent once more.
    private func pushCareSnapshot(force: Bool = false) async {
        let store = GameStore.shared
        guard isOnline, token != nil, !carePushing, store.isSharing, careDirty || force else { return }
        let name = store.state.slime.name
        if let refusedSnapshotName, refusedSnapshotName == name { return }
        carePushing = true
        defer { carePushing = false }
        careDirty = false
        lastCarePush = .now
        // The name in the snapshot actually sent (a conflict can bring the
        // partner's newer one).
        var sentName = name
        do {
            for attempt in 0..<2 {
                guard let link = store.state.care else { return }
                let outgoing = store.sharedSnapshot()
                sentName = outgoing.name
                let result = try await api.putCareSnapshot(outgoing, version: link.version + 1)
                guard var current = store.state.care, current.partnerID == link.partnerID else { return }
                switch result {
                case .saved(let version):
                    current.version = version
                    setCareLink(current)
                    refusedSnapshotName = nil
                    refusedSnapshotMessage = nil
                    careProblem = nil
                    return
                case .conflict(let newest, let snapshot):
                    guard let newest else {
                        // Nobody cares together anymore: find out what happened.
                        Task { await self.syncCare() }
                        return
                    }
                    if let snapshot { reconcileShared(snapshot) }
                    if var latest = store.state.care {
                        latest.version = newest
                        setCareLink(latest)
                    }
                    // Still behind after the second try: the next push sends it.
                    if attempt == 1 { careDirty = true }
                }
            }
        } catch let error as APIError where error.isBanned {
            handleBanned(error)
        } catch let error as APIError where error.isRefused {
            // The name filter (400) is final: not sent again until the
            // slime has a different name. Not a version conflict.
            refusedSnapshotName = sentName
            refusedSnapshotMessage = error.localizedDescription
            careProblem = refusedSnapshotProblem
        } catch let error as APIError where error.status == 413 {
            // Too big for the server; sending the same slime again won't
            // help. The next change tries again.
            careProblem = error.localizedDescription
        } catch {
            careDirty = true
        }
    }

    /// While the app is on screen, caring together syncs every 20 seconds
    /// (every 12 while waiting for a partner).
    func startCarePolling() {
        guard carePoll == nil else { return }
        carePoll = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                if self.isOnline, self.wantsCareSync { await self.syncCare() }
                let wait = self.isWaitingForCare ? Self.careWaitInterval : Self.carePollInterval
                do {
                    try await Task.sleep(for: .seconds(wait))
                } catch {
                    return
                }
            }
        }
    }

    func stopCarePolling() {
        carePoll?.cancel()
        carePoll = nil
    }

    /// The app left the screen: polling stops, and a changed shared slime
    /// is sent right away, with a little background time to finish.
    func enterBackground() {
        stopCarePolling()
        carePushTask?.cancel()
        carePushTask = nil
        guard careDirty, isOnline, isSharing, careBackgroundTask == .invalid else { return }
        careBackgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Slimy shared slime") {
            MainActor.assumeIsolated { FriendsStore.shared.endCareBackgroundTask() }
        }
        Task {
            await self.pushCareSnapshot()
            self.endCareBackgroundTask()
        }
    }

    private func endCareBackgroundTask() {
        guard careBackgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(careBackgroundTask)
        careBackgroundTask = .invalid
    }

    private func saveCareValue<T: Encodable>(_ value: T?, forKey key: String) {
        if let value, let data = try? HyprnodeAPI.encoder.encode(value) {
            defaults.set(data, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
    }

    private static func loadCareValue<T: Decodable>(_ type: T.Type, forKey key: String) -> T? {
        guard let data = UserDefaults.standard.data(forKey: key) else { return nil }
        return try? HyprnodeAPI.decoder.decode(T.self, from: data)
    }

    // MARK: Offline queue

    private static let queueMaxAge: TimeInterval = 3 * 86400
    private static let queueLimit = 50

    /// Adds a write to the queue. When it is full, the oldest care action
    /// goes first (the next snapshot carries what it did), then the oldest
    /// of the rest.
    private func enqueue(_ action: PendingAction) {
        queue.append(action)
        dropStaleActions()
        while queue.count > Self.queueLimit {
            let index = queue.firstIndex(where: \.isCare) ?? 0
            refund(queue[index])
            queue.remove(at: index)
        }
        saveQueue()
    }

    /// Old entries would only confuse a friend days later.
    private func dropStaleActions() {
        let stale = queue.filter { Date.now.timeIntervalSince($0.createdAt) > Self.queueMaxAge }
        guard !stale.isEmpty else { return }
        for action in stale { refund(action) }
        let staleIDs = Set(stale.map(\.id))
        queue.removeAll { staleIDs.contains($0.id) }
    }

    private func saveQueue() {
        if let data = try? JSONEncoder().encode(queue) { defaults.set(data, forKey: Keys.queue) }
    }

    private func refund(_ action: PendingAction) {
        guard action.coins > 0 else { return }
        GameStore.shared.update { $0.wallet.coins += action.coins }
    }

    /// Sends what waited, oldest first. Stops at the first network problem;
    /// skips what hit a daily limit (it goes out on a later day); drops what
    /// the server refuses for good (and gives back coins paid for it). Does
    /// nothing while offline. One flush runs at a time, so nothing is sent
    /// or refunded twice.
    func flushQueue() async {
        guard isOnline, token != nil, !flushing else { return }
        flushing = true
        defer { flushing = false }
        dropStaleActions()
        saveQueue()
        var careEnded = false
        for action in queue {
            // Removing a friend in the meantime already refunded their actions.
            guard isOnline, queue.contains(where: { $0.id == action.id }) else { continue }
            do {
                switch action.kind {
                case .interaction(let id, let kind, let food):
                    _ = try await api.interact(with: id, kind: kind, food: food, key: action.key)
                case .message(let id, let preset):
                    try await api.message(id, preset: preset, key: action.key)
                case .coop(let id, let points):
                    let status = try await api.addCoop(id, points: points, key: action.key)
                    if var detail = details[id] { detail.coop = status; details[id] = detail }
                case .challenge(let id, let game, let seed, let score):
                    _ = try await api.challenge(id, game: game, seed: UInt64(seed) ?? 1, score: score, key: action.key)
                case .answer(let id, let score):
                    _ = try await api.answer(id, score: score, key: action.key)
                case .care(let kind, let food, let game, let amount, let at):
                    // Sharing stopped meanwhile: nobody to send it to.
                    if GameStore.shared.isSharing {
                        let body = CareEventBody(kind: kind, food: food, game: game, amount: amount, at: at)
                        try await api.sendCare(body, key: action.key)
                    }
                }
            } catch let error as APIError where error.isRateLimited {
                continue
            } catch let error as APIError where error.isTransient {
                return
            } catch let error as APIError where error.isBanned {
                handleBanned(error)
                return
            } catch let error as APIError where error.isUnauthorized {
                // The next refresh sorts out the device; keep the action.
                return
            } catch {
                // A care action answered 409: the partner stopped sharing.
                if action.isCare, (error as? APIError)?.status == 409 { careEnded = true }
                if queue.contains(where: { $0.id == action.id }) { refund(action) }
            }
            queue.removeAll { $0.id == action.id }
            saveQueue()
        }
        if careEnded { Task { await self.syncCare() } }
    }

    private func update(_ id: String, _ change: (inout Friend) -> Void) {
        guard let i = friends.firstIndex(where: { $0.id == id }) else { return }
        change(&friends[i])
        cacheFriends()
    }
}

/// A write waiting for the network. The key makes a resend count once.
struct PendingAction: Codable, Identifiable, Hashable {
    enum Kind: Codable, Hashable {
        case interaction(String, InteractionKind, Food?)
        case message(String, MessagePreset)
        case coop(String, Int)
        case challenge(String, GameID, String, Int)
        case answer(String, Int)
        /// One of your care actions for the slime you share: kind, food,
        /// game, amount and when it happened.
        case care(CareKind, Food?, GameID?, Double?, Date)
    }

    var id = UUID()
    var kind: Kind
    var coins = 0
    var createdAt = Date.now

    var key: String { id.uuidString }

    var friendID: String? {
        switch kind {
        case .interaction(let id, _, _), .message(let id, _), .coop(let id, _), .challenge(let id, _, _, _): id
        case .answer, .care: nil
        }
    }

    var isCare: Bool {
        if case .care = kind { return true }
        return false
    }
}

/// Joining someone's slime, until it has moved onto this phone.
struct PendingCare: Codable, Equatable {
    var partnerID: String
    var partnerName: String
    /// The slime's name, once their phone has sent it.
    var slimeName: String?
    var snapshot: SlimeSnapshot?
    var version: Int
    var cursor: String?
    /// You said yes; only the slime itself is still on its way.
    var confirmed: Bool
    var joinedAt: Date
}

/// One thing your partner did for the shared slime, as the activity list
/// shows it: "Anna fed Blob · 5 min ago".
struct CareActivity: Codable, Hashable, Identifiable {
    var id: String
    var kind: CareKind
    var food: Food?
    var game: GameID?
    var by: String
    /// The shared slime's name when it happened.
    var slimeName: String
    var at: Date

    /// Entries are kept on this phone for a week.
    static let keptFor: TimeInterval = 7 * 86400
    static let limit = 100

    init(id: String, kind: CareKind, food: Food? = nil, game: GameID? = nil, by: String, slimeName: String, at: Date) {
        self.id = id
        self.kind = kind
        self.food = food
        self.game = game
        self.by = by
        self.slimeName = slimeName
        self.at = at
    }

    init(wire: CareEventWire, slimeName: String) {
        self.init(id: wire.id, kind: wire.kind, food: wire.food, game: wire.game, by: wire.ownerName,
                  slimeName: slimeName, at: wire.at)
    }

    /// "Anna fed Blob".
    var text: String {
        switch kind {
        case .feed: "\(by) fed \(slimeName)"
        case .play: "\(by) played with \(slimeName)"
        case .bath: "\(by) gave \(slimeName) a bath"
        case .pet: "\(by) petted \(slimeName)"
        case .sleep: "\(by) put \(slimeName) to bed"
        case .wake: "\(by) woke \(slimeName) up"
        case .gift: "\(by) gave \(slimeName) a present"
        }
    }

    /// What it was: the food or the game.
    var detail: String? { food?.title ?? game?.title }

    /// A pixel item for the food or game, if there is one.
    var sprite: String? { food?.sprite ?? game?.icon }

    var symbol: String {
        switch kind {
        case .feed: "fork.knife"
        case .play: "gamecontroller.fill"
        case .bath: "drop.fill"
        case .pet: "hand.raised.fill"
        case .sleep: "moon.zzz.fill"
        case .wake: "sun.max.fill"
        case .gift: "gift.fill"
        }
    }

    /// "Anna fed Blob · 5 min ago".
    func line(now: Date) -> String {
        "\(text) · \(Self.ago(at, now: now))"
    }

    /// How long ago, in a few words.
    static func ago(_ date: Date, now: Date) -> String {
        let seconds = now.timeIntervalSince(date)
        if seconds < 60 { return "just now" }
        let minutes = Int(seconds / 60)
        if minutes < 60 { return "\(minutes) min ago" }
        let hours = minutes / 60
        if hours < 24 { return hours == 1 ? "1 hour ago" : "\(hours) hours ago" }
        let days = hours / 24
        return days == 1 ? "yesterday" : "\(days) days ago"
    }

    /// New entries added to the list: each once, newest first, nothing
    /// older than a week, at most `limit`.
    static func merged(_ new: [CareActivity], into old: [CareActivity], now: Date) -> [CareActivity] {
        let cutoff = now.addingTimeInterval(-keptFor)
        var seen = Set<String>()
        let kept = (new + old).filter { $0.at > cutoff && seen.insert($0.id).inserted }
        return Array(kept.sorted { $0.at > $1.at }.prefix(limit))
    }
}

/// The parts of the shared slime that change outside care actions (a new
/// name, colour or outfit, XP from elsewhere). A change goes out with the
/// next snapshot.
private struct SharedLook: Equatable {
    var name: String
    var skin: Skin
    var xp: Int
    var equipped: Accessories

    init(_ slime: Slime) {
        name = slime.name
        skin = slime.skin
        xp = slime.xp
        equipped = slime.equipped
    }
}

/// Friend codes and invite links. https://slime.hyprnode.de/f/CODE opens the
/// website's invite page, slimy://f/CODE opens the app.
enum FriendInvite {
    static let length = 6
    /// The characters the server uses for codes (no 0/O, 1/I/L).
    static let alphabet = Set("ABCDEFGHJKMNPQRSTUVWXYZ23456789")

    /// A code as typed or pasted: upper case, letters and digits only, at
    /// most six characters.
    static func sanitize(_ text: String) -> String {
        let kept = text.uppercased().filter { $0.isASCII && ($0.isLetter || $0.isNumber) }
        return String(kept.prefix(length))
    }

    static func url(for code: String) -> URL {
        URL(string: "https://slime.hyprnode.de/f/\(code)")!
    }

    /// What the share sheet sends.
    static func message(for code: String) -> String {
        "Be my slime friend in Slimy! Code: \(code) \(url(for: code).absoluteString)"
    }

    /// The code in an invite link, or nil when the link isn't one.
    static func code(from url: URL) -> String? {
        let parts = url.pathComponents.filter { $0 != "/" }
        let candidate: String
        switch (url.scheme ?? "").lowercased() {
        case "https":
            guard url.host?.lowercased() == "slime.hyprnode.de", parts.count == 2, parts[0] == "f" else { return nil }
            candidate = parts[1]
        case "slimy":
            guard url.host?.lowercased() == "f", parts.count == 1 else { return nil }
            candidate = parts[0]
        default:
            return nil
        }
        let code = candidate.uppercased()
        guard code.count == length, code.allSatisfy({ alphabet.contains($0) }) else { return nil }
        return code
    }
}

/// The friends token in the Keychain: available after the first unlock (so
/// background refresh works), never synced and never restored to another
/// device. The widget doesn't need it.
enum TokenVault {
    private static let service = "de.hyprnode.slimy.friends"
    private static let account = "device-token"

    private static var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    static func load() -> String? {
        var request = query
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(request as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let token = String(data: data, encoding: .utf8), !token.isEmpty else { return nil }
        return token
    }

    /// Stores the token, or removes it for nil. Returns whether it worked.
    @discardableResult
    static func save(_ token: String?) -> Bool {
        guard let token else {
            let status = SecItemDelete(query as CFDictionary)
            return status == errSecSuccess || status == errSecItemNotFound
        }
        let attributes: [String: Any] = [
            kSecValueData as String: Data(token.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var item = query
            item.merge(attributes) { _, new in new }
            status = SecItemAdd(item as CFDictionary, nil)
        }
        return status == errSecSuccess
    }
}
