import Foundation

/// Friends and the love between slimes. Talks to slime.hyprnode.de; until
/// that server is live, a clearly labelled demo friend lets you try it out.
@MainActor
final class FriendsStore: ObservableObject {
    static let shared = FriendsStore()

    enum Status: Equatable {
        case unknown, online, unavailable(String)
    }

    @Published private(set) var friends: [Friend] = []
    @Published private(set) var status: Status = .unknown
    @Published private(set) var pairCode: PairCode?
    @Published var lastError: String?
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
    }

    init() {
        ownerName = defaults.string(forKey: Keys.owner) ?? ""
        partnerID = defaults.string(forKey: Keys.partner)
        demoEnabled = defaults.bool(forKey: Keys.demo)
        if let data = defaults.data(forKey: Keys.demoState),
           let saved = try? JSONDecoder().decode(Friend.self, from: data) {
            demo = saved
        } else {
            demo = Friend(
                id: Friend.demoID, ownerName: "Demo",
                slime: SlimeProfile(ownerName: "Demo", name: "Mochi", skin: .bubblegum, level: 3,
                                    mood: .idle, happiness: 80, updatedAt: .now),
                love: 35, inLove: false, since: .now)
        }
    }

    private var api: HyprnodeAPI { HyprnodeAPI(token: token) }

    /// Everyone to show: real friends, then the demo friend if switched on.
    var allFriends: [Friend] { friends + (demoEnabled ? [demo] : []) }

    var partner: Friend? {
        let all = allFriends
        if let partnerID, let chosen = all.first(where: { $0.id == partnerID }) { return chosen }
        return all.filter(\.inLove).max { $0.love < $1.love }
    }

    var partnerSnapshot: PartnerSnapshot? {
        guard let p = partner else { return nil }
        return PartnerSnapshot(ownerName: p.ownerName, slimeName: p.slime.name, skin: p.slime.skin,
                               mood: p.slime.mood, love: p.love, inLove: p.inLove)
    }

    // MARK: Sync

    func refresh() async {
        do {
            try await ensureRegistered()
            let slime = SlimeStore.shared.state
            try await api.upload(SlimeProfile(
                ownerName: ownerName.isEmpty ? "Someone" : ownerName,
                name: slime.name, skin: slime.skin, level: slime.level, mood: slime.mood,
                happiness: Int(slime.needs.average), updatedAt: .now))
            friends = try await api.friends()
            try await readInbox()
            status = .online
        } catch let error as APIError {
            status = .unavailable(error.localizedDescription)
        } catch {
            status = .unavailable(error.localizedDescription)
        }
        await IslandController.shared.sync()
    }

    private func ensureRegistered() async throws {
        guard token == nil else { return }
        let reg = try await HyprnodeAPI().register(ownerName: ownerName.isEmpty ? "Someone" : ownerName)
        token = reg.token
    }

    private func readInbox() async throws {
        let page = try await api.inbox(after: inboxCursor)
        for event in page.events {
            let who = friends.first { $0.id == event.friendId }?.slime.name ?? "A friend"
            switch event.kind {
            case .gift:
                SlimeStore.shared.receiveGift(event.food ?? .berry, from: who)
            case .kiss, .hug, .visit:
                SlimeStore.shared.toast = Toast(text: "\(who) sent a \(event.kind.rawValue) 💌", good: true)
            }
        }
        if let cursor = page.cursor { inboxCursor = cursor }
    }

    // MARK: Pairing

    func makeCode() async {
        do {
            try await ensureRegistered()
            pairCode = try await api.newPairCode()
            lastError = nil
        } catch {
            lastError = error.localizedDescription
        }
    }

    func join(code: String) async -> Bool {
        let cleaned = code.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard cleaned.count == 6 else {
            lastError = "Friend codes have 6 letters and numbers."
            return false
        }
        do {
            try await ensureRegistered()
            let friend = try await api.join(code: cleaned)
            friends.removeAll { $0.id == friend.id }
            friends.append(friend)
            lastError = nil
            return true
        } catch {
            lastError = error.localizedDescription
            return false
        }
    }

    func remove(_ friend: Friend) async {
        if friend.isDemo { demoEnabled = false; return }
        do {
            try await api.removeFriend(friend.id)
            friends.removeAll { $0.id == friend.id }
            if partnerID == friend.id { partnerID = nil }
        } catch {
            lastError = error.localizedDescription
        }
    }

    // MARK: Love

    /// Returns the line to show, or nil if it failed.
    func interact(with friend: Friend, _ kind: InteractionKind, food: Food? = nil) async -> String? {
        if kind == .gift, let food {
            guard SlimeStore.shared.state.coins >= food.price else {
                lastError = "Not enough coins for \(food.emoji)."
                return nil
            }
        }
        if kind == .kiss, friend.love < 50 {
            lastError = "Too soon for kisses. Get to 50% love first."
            return nil
        }

        let result: InteractionResult
        if friend.isDemo {
            let gain = [InteractionKind.hug: 6, .visit: 8, .gift: 10, .kiss: 12][kind] ?? 5
            let love = min(100, demo.love + gain)
            result = InteractionResult(love: love, inLove: demo.inLove || love >= 100)
        } else {
            do {
                result = try await api.interact(with: friend.id, kind: kind, food: food)
            } catch {
                lastError = error.localizedDescription
                return nil
            }
        }

        if kind == .gift, let food { SlimeStore.shared.spend(food.price) }
        let fellInLove = result.inLove && !friend.inLove
        update(friend.id) { $0.love = result.love; $0.inLove = result.inLove }
        if fellInLove { partnerID = friend.id }
        SlimeStore.shared.loveMoment(fellInLove: fellInLove)
        await IslandController.shared.sync()
        lastError = nil

        if fellInLove { return "\(SlimeStore.shared.state.name) and \(friend.slime.name) are in love!! 💞" }
        switch kind {
        case .hug: return "squishy hug with \(friend.slime.name) 🤗"
        case .visit: return "visited \(friend.slime.name)'s room 🏠"
        case .gift: return "sent \(food?.emoji ?? "🎁") to \(friend.slime.name)"
        case .kiss: return "mwah 💋"
        }
    }

    private func update(_ id: String, _ change: (inout Friend) -> Void) {
        if id == Friend.demoID {
            change(&demo)
            if let data = try? JSONEncoder().encode(demo) { defaults.set(data, forKey: Keys.demoState) }
        } else if let i = friends.firstIndex(where: { $0.id == id }) {
            change(&friends[i])
        }
    }
}
