import Foundation
import Security
import UIKit

/// Friends, love, greetings, challenges, the co-op garden and the friends
/// leaderboard. All of it is opt-in: nothing reaches slime.hyprnode.de until
/// the player taps "Go online" and agrees to the Terms (`goOnline()`), and
/// nothing syncs after they go offline again. The server decides who is
/// friends and how much the slimes love each other. Writes that fail because
/// the network is gone wait in a queue and are sent later with the same
/// idempotency key, so nothing counts twice.
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

    private let defaults = UserDefaults.standard
    private var refreshing = false
    private var connecting = false
    private var flushing = false
    private var deletingAccount = false
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

        if let data = defaults.data(forKey: Keys.queue),
           let saved = try? JSONDecoder().decode([PendingAction].self, from: data) {
            queue = saved
        }
        // Last known friends, so the island and the list work without network.
        if let data = defaults.data(forKey: Keys.cache),
           let saved = try? HyprnodeAPI.decoder.decode([Friend].self, from: data) {
            friends = saved
        }
        refusedNames = defaults.stringArray(forKey: Keys.refusedNames)
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

    var partner: Friend? {
        if let partnerID, let chosen = friends.first(where: { $0.id == partnerID }) { return chosen }
        return friends.filter(\.inLove).max { $0.love < $1.love }
    }

    /// Friends for the island, most important first: the chosen one, the
    /// ones in love, then by love.
    var islandFriends: [FriendSlime] {
        let sorted = friends.sorted { a, b in
            if a.id == partnerID { return true }
            if b.id == partnerID { return false }
            if a.inLove != b.inLove { return a.inLove }
            return a.love > b.love
        }
        return sorted.prefix(3).map(Self.islandSlime)
    }

    static func islandSlime(_ f: Friend) -> FriendSlime {
        FriendSlime(id: f.id, name: f.slime.name, owner: f.ownerName, skin: f.slime.skin, stage: f.stage,
                    mood: f.slime.mood, love: f.love, inLove: f.inLove, equipped: f.slime.equipped)
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
    func goOffline() {
        optedIn = false
        isOnline = false
        status = .unknown
        pairCode = nil
        requests = RequestList(incoming: [], outgoing: [])
        challenges = []
        leaderboard = []
        knownNews = []
    }

    private func connect() async throws {
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
    }

    /// What Friends and Go Online show after a ban. The server still lets
    /// a banned device delete its data.
    private static func banMessage(for error: APIError) -> String {
        "\(error.localizedDescription) You can still delete your data on the server in Settings under Privacy."
    }

    /// The server banned this device: stop syncing and say why.
    private func handleBanned(_ error: APIError) {
        goOffline()
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
        hasNews = true
        defer {
            // In the background (refresh), a few kinds also become a notification,
            // with the line this event showed (never an older toast).
            if UIApplication.shared.applicationState != .active,
               ["request", "accepted", "gift", "inLove", "message", "challenge"].contains(event.kind),
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
            store.toast = Toast(text: "\(name): \(event.kind == "hug" ? "hug!" : "smooch.")", good: true)
            store.update { $0.slime.needs.add(.love, 4); $0.slime.react(.blush, at: .now) }
            if let id = event.friendId { Task { await IslandController.shared.show(.hug, with: id) } }
        case "visit":
            store.toast = Toast(text: "\(name) came to visit", good: true)
            if let f = friend(event.friendId) { visitor = (Self.islandSlime(f), Date.now.addingTimeInterval(10 * 60)) }
            if let id = event.friendId { Task { await IslandController.shared.show(.reunion, with: id) } }
        case "inLove":
            var line = ""
            store.update { line = $0.friendMoment(.inLove, friend: name, fellInLove: true, now: .now) }
            store.toast = Toast(text: line, good: true)
            if let id = event.friendId { Task { await IslandController.shared.show(.love, with: id, alert: line) } }
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

    // MARK: Love

    /// Returns the line to show, or nil if it failed.
    func interact(with friend: Friend, _ kind: InteractionKind, food: Food? = nil) async -> String? {
        guard requireOnline() else { return nil }
        if kind == .gift {
            guard let food, GameStore.shared.state.wallet.coins >= food.price else {
                lastError = "Not enough coins for \(food?.title ?? "the gift")."
                return nil
            }
        }
        if kind == .kiss, friend.love < 50 {
            lastError = "Too early for kisses. They unlock at 50% love."
            return nil
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
        let fellInLove = result.inLove && !friend.inLove
        update(friend.id) { $0.love = result.love; $0.inLove = result.inLove }
        if fellInLove { partnerID = friend.id }
        if result.capped == true, let message = result.message { return message }

        let category: LineCategory = switch kind {
        case .hug, .kiss: .friendHug
        case .visit: .friendVisit
        case .gift: .gift
        }
        var said = ""
        GameStore.shared.update { said = $0.friendMoment(category, friend: friend.slime.name, fellInLove: fellInLove, now: .now) }
        let moment: SharedMoment = fellInLove ? .love : (kind == .gift ? .gift : kind == .visit ? .reunion : .hug)
        Task { await IslandController.shared.show(moment, with: friend.id, alert: fellInLove ? said : nil) }
        lastError = nil

        if fellInLove { return said }
        switch kind {
        case .hug: return "You hugged \(friend.slime.name)."
        case .visit: return "Your slime is visiting \(friend.slime.name)."
        case .gift: return "\(food?.title ?? "Gift") sent to \(friend.slime.name)."
        case .kiss: return "You sent \(friend.slime.name) a kiss."
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

    /// A finished round adds to the co-op garden you share with your
    /// partner (or your closest friend). Points are the round's quality,
    /// not its raw score, so every game counts the same.
    func contributeCoop(_ result: GameResult) {
        guard isOnline, token != nil, result.score > 0,
              let target = partner ?? friends.max(by: { $0.love < $1.love }) else { return }
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

    // MARK: Offline queue

    private static let queueMaxAge: TimeInterval = 3 * 86400

    private func enqueue(_ action: PendingAction) {
        queue.append(action)
        dropStaleActions()
        if queue.count > 50 {
            for old in queue.prefix(queue.count - 50) { refund(old) }
            queue.removeFirst(queue.count - 50)
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
                if queue.contains(where: { $0.id == action.id }) { refund(action) }
            }
            queue.removeAll { $0.id == action.id }
            saveQueue()
        }
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
    }

    var id = UUID()
    var kind: Kind
    var coins = 0
    var createdAt = Date.now

    var key: String { id.uuidString }

    var friendID: String? {
        switch kind {
        case .interaction(let id, _, _), .message(let id, _), .coop(let id, _), .challenge(let id, _, _, _): id
        case .answer: nil
        }
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
