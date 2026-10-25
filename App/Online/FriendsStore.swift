import Foundation
import UIKit

/// Friends, love, greetings, challenges and the co-op garden. The server
/// (slime.hyprnode.de) decides who is friends and how much the slimes love
/// each other. Writes that fail because you're offline wait in a queue and
/// are sent later with the same idempotency key, so nothing counts twice.
/// Until the server is live, a clearly labelled demo friend lets you try it.
@MainActor
final class FriendsStore: ObservableObject {
    static let shared = FriendsStore()

    enum Status: Equatable {
        case unknown, online, unavailable(String)
    }

    @Published private(set) var friends: [Friend] = []
    @Published private(set) var requests = RequestList(incoming: [], outgoing: [])
    @Published private(set) var challenges: [Challenge] = []
    @Published private(set) var blocked: [BlockedDevice] = []
    @Published private(set) var details: [String: FriendDetail] = [:]
    @Published private(set) var status: Status = .unknown
    @Published private(set) var pairCode: PairCode?
    @Published private(set) var privacy = Privacy(acceptRequests: true, shareMood: true)
    @Published private(set) var queue: [PendingAction] = []
    /// A friend's slime visiting your home, until the date.
    @Published private(set) var visitor: (friend: FriendSlime, until: Date)?
    @Published var lastError: String?
    /// A challenge the player chose to play; the arcade opens with it.
    @Published var playChallenge: Challenge?
    /// Something new arrived (shown as a dot on the Friends button).
    @Published var hasNews = false
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
    @Published var demoEnabled: Bool {
        didSet {
            defaults.set(demoEnabled, forKey: Keys.demo)
            if !demoEnabled, partnerID == Friend.demoID { partnerID = nil }
            Task { await IslandController.shared.sync() }
        }
    }
    @Published private var demo: Friend

    private let defaults = UserDefaults.standard
    private var refreshing = false
    private var token: String? {
        get { defaults.string(forKey: Keys.token) }
        set { defaults.set(newValue, forKey: Keys.token) }
    }
    private var inboxCursor: String? {
        get { defaults.string(forKey: Keys.cursor) }
        set { defaults.set(newValue, forKey: Keys.cursor) }
    }

    private enum Keys {
        static let owner = "slimy.friends.owner"
        static let partner = "slimy.friends.partner"
        static let demo = "slimy.friends.demo"
        static let demoState = "slimy.friends.demoState"
        static let token = "slimy.friends.token"
        static let cursor = "slimy.friends.cursor"
        static let queue = "slimy.friends.queue"
        static let cache = "slimy.friends.cache"
    }

    init() {
        let defaults = UserDefaults.standard
        ownerName = defaults.string(forKey: Keys.owner) ?? ""
        partnerID = defaults.string(forKey: Keys.partner)
        demoEnabled = defaults.bool(forKey: Keys.demo)
        if let data = defaults.data(forKey: Keys.demoState),
           let saved = try? JSONDecoder().decode(Friend.self, from: data) {
            demo = saved
        } else {
            demo = Friend(
                id: Friend.demoID, ownerName: "Demo",
                slime: SlimeProfile(ownerName: "Demo", name: "Mochi", skin: .bubblegum, level: 3, stage: .baby,
                                    mood: .idle, happiness: 80, equipped: nil, updatedAt: .now),
                love: 35, inLove: false, since: .now)
        }
        if let data = defaults.data(forKey: Keys.queue),
           let saved = try? JSONDecoder().decode([PendingAction].self, from: data) {
            queue = saved
        }
        // Last known friends, so the island and the list work offline.
        if let data = defaults.data(forKey: Keys.cache),
           let saved = try? HyprnodeAPI.decoder.decode([Friend].self, from: data) {
            friends = saved
        }
    }

    private var api: HyprnodeAPI { HyprnodeAPI(token: token) }

    /// A client that is registered with the server, registering if needed.
    func authorizedAPI() async throws -> HyprnodeAPI {
        try await ensureRegistered()
        return api
    }

    var isRegistered: Bool { token != nil }

    /// Everyone to show: real friends, then the demo friend if switched on.
    var allFriends: [Friend] { friends + (demoEnabled ? [demo] : []) }

    var partner: Friend? {
        let all = allFriends
        if let partnerID, let chosen = all.first(where: { $0.id == partnerID }) { return chosen }
        return all.filter(\.inLove).max { $0.love < $1.love }
    }

    /// Friends for the island, most important first: the chosen one, the
    /// ones in love, then by love.
    var islandFriends: [FriendSlime] {
        let sorted = allFriends.sorted { a, b in
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
        return allFriends.first { $0.id == id }
    }

    var openChallenges: [Challenge] { challenges.filter(\.isOpenForMe) }

    // MARK: Sync

    func refresh() async {
        guard !refreshing else { return }
        refreshing = true
        defer { refreshing = false }
        do {
            try await ensureRegistered()
            try await uploadProfile()
            privacy = try await api.me().privacy
            await flushQueue()
            friends = try await api.friends()
            cacheFriends()
            requests = try await api.requests()
            challenges = try await api.challenges()
            try await readInbox()
            status = .online
            if !requests.incoming.isEmpty || !openChallenges.isEmpty { hasNews = true }
        } catch let error as APIError {
            status = .unavailable(error.localizedDescription)
        } catch {
            status = .unavailable(error.localizedDescription)
        }
        if let visitor, visitor.until < .now { self.visitor = nil }
        await IslandController.shared.sync()
    }

    private func ensureRegistered() async throws {
        guard token == nil else { return }
        let reg = try await HyprnodeAPI().register(ownerName: displayName)
        token = reg.token
    }

    private var displayName: String { ownerName.isEmpty ? "Someone" : ownerName }

    private func uploadProfile() async throws {
        let slime = GameStore.shared.state.slime
        try await api.upload(SlimeProfile(
            ownerName: displayName, name: slime.name, skin: slime.skin, level: slime.level, stage: slime.stage,
            mood: slime.mood, happiness: Int(slime.needs.average), equipped: slime.equipped, updatedAt: .now))
    }

    private func cacheFriends() {
        if let data = try? HyprnodeAPI.encoder.encode(friends) { defaults.set(data, forKey: Keys.cache) }
    }

    private func readInbox() async throws {
        var page = try await api.inbox(after: inboxCursor)
        var rounds = 0
        while true {
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
        hasNews = true
        defer {
            // In the background (refresh), a few kinds also become a notification.
            if UIApplication.shared.applicationState != .active,
               ["request", "accepted", "gift", "inLove", "message", "challenge"].contains(event.kind),
               let text = store.toast?.text {
                Notifier.post(event.kind == "gift" ? .gifts : .friends, title: name, body: text, state: store.state)
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

    func makeCode() async {
        do {
            pairCode = try await authorizedAPI().newPairCode()
            lastError = nil
        } catch {
            lastError = error.localizedDescription
        }
    }

    /// Returns a line to show on success.
    func join(code: String) async -> String? {
        let cleaned = code.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard cleaned.count == 6 else {
            lastError = "Friend codes have 6 characters."
            return nil
        }
        do {
            let result = try await authorizedAPI().join(code: cleaned)
            lastError = nil
            if let friend = result.friend {
                adopt(friend)
                return "You're friends now."
            }
            requests = try await api.requests()
            return "Request sent. Once it's accepted, you're friends."
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    func accept(_ request: FriendRequest) async {
        do {
            let friend = try await api.accept(request.id)
            requests.incoming.removeAll { $0.id == request.id }
            adopt(friend)
        } catch {
            lastError = error.localizedDescription
        }
    }

    func decline(_ request: FriendRequest) async {
        do {
            try await api.decline(request.id)
            requests.incoming.removeAll { $0.id == request.id }
            requests.outgoing.removeAll { $0.id == request.id }
        } catch {
            lastError = error.localizedDescription
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

    func remove(_ friend: Friend) async {
        if friend.isDemo { demoEnabled = false; return }
        do {
            try await api.removeFriend(friend.id)
            forget(friend.id)
        } catch {
            lastError = error.localizedDescription
        }
    }

    func block(deviceID: String) async {
        do {
            try await api.block(deviceID)
            forget(deviceID)
            requests.incoming.removeAll { $0.deviceId == deviceID }
            blocked = try await api.blocked()
        } catch {
            lastError = error.localizedDescription
        }
    }

    func unblock(_ device: BlockedDevice) async {
        do {
            try await api.unblock(device.deviceId)
            blocked.removeAll { $0.deviceId == device.deviceId }
        } catch {
            lastError = error.localizedDescription
        }
    }

    func loadBlocked() async {
        do { blocked = try await authorizedAPI().blocked() } catch { lastError = error.localizedDescription }
    }

    private func forget(_ id: String) {
        friends.removeAll { $0.id == id }
        details[id] = nil
        queue.removeAll { $0.friendID == id }
        saveQueue()
        cacheFriends()
        if partnerID == id { partnerID = nil }
    }

    func loadDetail(_ friend: Friend) async {
        guard !friend.isDemo else { return }
        do { details[friend.id] = try await api.friend(friend.id) } catch { lastError = error.localizedDescription }
    }

    func setPrivacy(_ change: (inout Privacy) -> Void) async {
        var next = privacy
        change(&next)
        do {
            privacy = try await authorizedAPI().setPrivacy(next)
            if !privacy.acceptRequests { pairCode = nil }
        } catch {
            lastError = error.localizedDescription
        }
    }

    /// Deletes everything the server knows about this phone.
    func deleteAccount() async -> Bool {
        guard token != nil else { return true }
        do {
            try await api.deleteAccount()
            token = nil
            inboxCursor = nil
            friends = []
            requests = RequestList(incoming: [], outgoing: [])
            challenges = []
            details = [:]
            queue = []
            saveQueue()
            cacheFriends()
            partnerID = nil
            return true
        } catch {
            lastError = error.localizedDescription
            return false
        }
    }

    // MARK: Love

    /// Returns the line to show, or nil if it failed.
    func interact(with friend: Friend, _ kind: InteractionKind, food: Food? = nil) async -> String? {
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

        if friend.isDemo {
            let gain = [InteractionKind.hug: 3, .visit: 5, .gift: 6, .kiss: 8][kind] ?? 3
            let love = min(100, demo.love + gain)
            return applied(InteractionResult(love: love, inLove: demo.inLove || love >= 100, gained: gain), to: friend, kind, food)
        }

        let action = PendingAction(kind: .interaction(friend.id, kind, food), coins: kind == .gift ? food?.price ?? 0 : 0)
        // Pay for a gift now; a final "no" from the server refunds it.
        if action.coins > 0 { GameStore.shared.update { _ = $0.wallet.spend(action.coins) } }
        do {
            let result = try await api.interact(with: friend.id, kind: kind, food: food, key: action.key)
            return applied(result, to: friend, kind, food)
        } catch let error as APIError where error.isTransient {
            enqueue(action)
            return "Will arrive once you're back online."
        } catch {
            refund(action)
            lastError = error.localizedDescription
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
        case .hug: return "hugged \(friend.slime.name). squish."
        case .visit: return "visiting \(friend.slime.name)"
        case .gift: return "\(food?.title ?? "Gift") sent to \(friend.slime.name)"
        case .kiss: return "smooch."
        }
    }

    func send(_ preset: MessagePreset, to friend: Friend) async -> String? {
        if friend.isDemo { return "\(preset.text) sent to \(friend.slime.name)" }
        let action = PendingAction(kind: .message(friend.id, preset))
        do {
            try await api.message(friend.id, preset: preset, key: action.key)
            return "\(preset.text) sent to \(friend.slime.name)"
        } catch let error as APIError where error.isTransient {
            enqueue(action)
            return "Will arrive once you're back online."
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    // MARK: Arcade together

    /// A finished round adds to the co-op garden you share with your
    /// partner (or your closest friend). Points are the round's quality,
    /// not its raw score, so every game counts the same.
    func contributeCoop(_ result: GameResult) {
        guard isRegistered, result.score > 0,
              let target = partner.flatMap({ $0.isDemo ? nil : $0 }) ?? friends.max(by: { $0.love < $1.love }) else { return }
        let points = min(150, Int(Double(result.score) / Double(result.game.goodScore) * 100))
        guard points > 0 else { return }
        enqueue(PendingAction(kind: .coop(target.id, points)))
        Task { await flushQueue() }
    }

    func challenge(_ friend: Friend, with result: GameResult, seed: UInt64) async -> String? {
        let action = PendingAction(kind: .challenge(friend.id, result.game, String(seed), result.score))
        do {
            let c = try await api.challenge(friend.id, game: result.game, seed: seed, score: result.score, key: action.key)
            challenges.insert(c, at: 0)
            return "Challenge sent to \(friend.slime.name)"
        } catch let error as APIError where error.isTransient {
            enqueue(action)
            return "Will be sent once you're back online."
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    func answer(_ challenge: Challenge, score: Int) async {
        let action = PendingAction(kind: .answer(challenge.id, score))
        do {
            let updated = try await api.answer(challenge.id, score: score, key: action.key)
            if let i = challenges.firstIndex(where: { $0.id == updated.id }) { challenges[i] = updated }
        } catch let error as APIError where error.isTransient {
            if let i = challenges.firstIndex(where: { $0.id == challenge.id }) { challenges[i].myScore = score }
            enqueue(action)
        } catch {
            lastError = error.localizedDescription
        }
    }

    // MARK: Offline queue

    private func enqueue(_ action: PendingAction) {
        queue.append(action)
        // Old entries would only confuse a friend days later.
        queue.removeAll { Date.now.timeIntervalSince($0.createdAt) > 3 * 86400 }
        if queue.count > 50 { queue.removeFirst(queue.count - 50) }
        saveQueue()
    }

    private func saveQueue() {
        if let data = try? JSONEncoder().encode(queue) { defaults.set(data, forKey: Keys.queue) }
    }

    private func refund(_ action: PendingAction) {
        guard action.coins > 0 else { return }
        GameStore.shared.update { $0.wallet.coins += action.coins }
    }

    /// Sends what waited. Stops at the first network problem; drops what the
    /// server refuses for good (and gives back coins paid for it).
    func flushQueue() async {
        guard token != nil else { return }
        while let action = queue.first {
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
            } catch let error as APIError where error.isTransient {
                return
            } catch {
                refund(action)
            }
            queue.removeFirst()
            saveQueue()
        }
    }

    private func update(_ id: String, _ change: (inout Friend) -> Void) {
        if id == Friend.demoID {
            change(&demo)
            if let data = try? JSONEncoder().encode(demo) { defaults.set(data, forKey: Keys.demoState) }
        } else if let i = friends.firstIndex(where: { $0.id == id }) {
            change(&friends[i])
            cacheFriends()
        }
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
