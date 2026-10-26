import Foundation

// Client for the slime.hyprnode.de backend (server/ in this repo). The full
// contract is in server/README.md; keep the two in sync.

enum APIError: LocalizedError {
    case offline
    case server(status: Int, message: String?)
    case notReady

    var errorDescription: String? {
        switch self {
        case .offline: "Can't reach slime.hyprnode.de. Check your internet and try again."
        case .server(let status, let message): message ?? "The server reported error \(status)."
        case .notReady: "slime.hyprnode.de isn't reachable right now. Please try again later."
        }
    }

    /// The HTTP status the server answered with, if it answered. A request
    /// that timed out counts as 504.
    var status: Int? {
        switch self {
        case .server(let status, _): status
        case .offline, .notReady: nil
        }
    }

    /// Worth trying again later (offline queue); a 4xx answer is final.
    var isTransient: Bool {
        switch self {
        case .offline, .notReady: true
        case .server(let status, _): status >= 500 || status == 429
        }
    }

    /// The server banned this device from online features (403).
    var isBanned: Bool { status == 403 }

    /// The server doesn't know this device's token (401), for example after
    /// its data was deleted.
    var isUnauthorized: Bool { status == 401 }

    /// The server refused what was sent (400). For a name or a profile
    /// that means its filter: "Please choose a different name."
    var isRefused: Bool { status == 400 }

    /// A daily or hourly limit (429). Its message says when to try again,
    /// so a tapped action shows it instead of queueing.
    var isRateLimited: Bool { status == 429 }

    /// A tapped action should wait in the offline queue: the network or the
    /// server is down, not a limit that sending again won't fix today.
    var shouldQueue: Bool { isTransient && !isRateLimited }
}

/// Thrown instead of calling the server while the player hasn't gone online
/// (or went offline). Nothing reaches slime.hyprnode.de in that state.
struct NotOnlineError: LocalizedError {
    var errorDescription: String? { "Go online in Friends to use this." }
}

// MARK: - Wire types

struct SlimeProfile: Codable, Hashable {
    var ownerName: String
    var name: String
    var skin: Skin
    var level: Int
    var stage: Stage?
    var mood: Mood
    /// nil when the friend hides their slime's mood.
    var happiness: Int?
    var equipped: Accessories?
    var updatedAt: Date

    init(ownerName: String, name: String, skin: Skin, level: Int, stage: Stage?, mood: Mood,
         happiness: Int?, equipped: Accessories?, updatedAt: Date) {
        self.ownerName = ownerName
        self.name = name
        self.skin = skin
        self.level = level
        self.stage = stage
        self.mood = mood
        self.happiness = happiness
        self.equipped = equipped
        self.updatedAt = updatedAt
    }

    /// Looks from a newer app version (a skin, mood, stage or outfit this
    /// one doesn't know) fall back to defaults, so a friend never vanishes
    /// from the list because of them.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        ownerName = try c.decode(String.self, forKey: .ownerName)
        name = try c.decode(String.self, forKey: .name)
        level = try c.decode(Int.self, forKey: .level)
        updatedAt = try c.decode(Date.self, forKey: .updatedAt)
        skin = (try? c.decodeIfPresent(Skin.self, forKey: .skin)) ?? .lime
        stage = try? c.decodeIfPresent(Stage.self, forKey: .stage)
        mood = (try? c.decodeIfPresent(Mood.self, forKey: .mood)) ?? .idle
        happiness = try? c.decodeIfPresent(Int.self, forKey: .happiness)
        equipped = try? c.decodeIfPresent(Accessories.self, forKey: .equipped)
    }
}

struct Friend: Codable, Hashable, Identifiable {
    var id: String
    var ownerName: String
    var slime: SlimeProfile
    /// 0...100. The slimes fall in love at 100.
    var love: Int
    var inLove: Bool
    var since: Date

    var stage: Stage { slime.stage ?? Stage.forLevel(slime.level) }
}

struct FriendRequest: Codable, Hashable, Identifiable {
    var id: String
    var deviceId: String
    var ownerName: String
    var slime: SlimeProfile?
    var createdAt: Date
}

struct RequestList: Codable {
    var incoming: [FriendRequest]
    var outgoing: [FriendRequest]
}

struct JoinResult: Codable {
    /// "pending" (waits for the other side) or "friends".
    var status: String
    var friend: Friend?
    var request: FriendRequest?
}

struct PairCode: Codable, Hashable {
    var code: String
    var expiresAt: Date
}

enum InteractionKind: String, Codable, CaseIterable {
    case hug, visit, gift, kiss
}

struct InteractionResult: Codable {
    var love: Int
    var inLove: Bool
    var gained: Int?
    var fellInLove: Bool?
    var capped: Bool?
    var message: String?
}

/// Short greetings friends can send. Only presets, so nobody can send
/// anything unkind through Slimy.
enum MessagePreset: String, Codable, CaseIterable, Identifiable {
    case hello, missYou, goodNight, goodMorning, letsPlay, thanks, congrats, proud

    var id: String { rawValue }

    var text: String {
        switch self {
        case .hello: "Hi!"
        case .missYou: "I miss you."
        case .goodNight: "Good night!"
        case .goodMorning: "Good morning!"
        case .letsPlay: "Let's play!"
        case .thanks: "Thank you!"
        case .congrats: "Congrats!"
        case .proud: "I'm proud of you."
        }
    }

    var icon: String {
        switch self {
        case .hello, .goodMorning: "speech"
        case .missYou, .proud: "heart"
        case .goodNight: "starJelly"
        case .letsPlay: "ball"
        case .thanks: "flower"
        case .congrats: "gem"
        }
    }
}

struct InboxEvent: Codable, Identifiable {
    var id: String
    var friendId: String?
    var friendName: String?
    /// request, accepted, hug, visit, gift, kiss, inLove, message, challenge,
    /// challengeResult, coopDone. Unknown kinds from a newer server are ignored.
    var kind: String
    var food: Food?
    var preset: MessagePreset?
    var challengeId: String?
    var requestId: String?
    var createdAt: Date

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        kind = try c.decode(String.self, forKey: .kind)
        createdAt = try c.decode(Date.self, forKey: .createdAt)
        friendId = try? c.decodeIfPresent(String.self, forKey: .friendId)
        friendName = try? c.decodeIfPresent(String.self, forKey: .friendName)
        food = try? c.decodeIfPresent(Food.self, forKey: .food)
        preset = try? c.decodeIfPresent(MessagePreset.self, forKey: .preset)
        challengeId = try? c.decodeIfPresent(String.self, forKey: .challengeId)
        requestId = try? c.decodeIfPresent(String.self, forKey: .requestId)
    }
}

struct InboxPage: Codable {
    var events: [InboxEvent]
    var cursor: String?
}

struct FriendMemory: Codable, Hashable {
    var kind: String
    var detail: String?
    var at: Date
}

struct CoopStatus: Codable, Hashable {
    var week: String
    var goal: Int
    var mine: Int
    var theirs: Int
    var total: Int
    var done: Bool
    var added: Int?
}

struct FriendDetail: Codable {
    var friend: Friend
    var memories: [FriendMemory]
    var coop: CoopStatus
    /// Interactions left today that still raise love, by kind.
    var today: [String: Int]
}

struct Challenge: Codable, Hashable, Identifiable {
    var id: String
    var game: GameID
    var seed: String
    var friendId: String
    var friendName: String
    var sentByMe: Bool
    var myScore: Int?
    var theirScore: Int?
    var createdAt: Date
    var expiresAt: Date
    var answeredAt: Date?

    var isOpenForMe: Bool { !sentByMe && myScore == nil && expiresAt > .now }
    var seedValue: UInt64 { UInt64(seed) ?? 1 }
}

struct Privacy: Codable, Hashable {
    var acceptRequests: Bool
    var shareMood: Bool
}

struct BlockedDevice: Codable, Hashable, Identifiable {
    var deviceId: String
    var ownerName: String
    var since: Date
    var id: String { deviceId }
}

/// Why someone reports a person or a chat reply. Reports go to the Slimy
/// team; they never block anyone by themselves.
enum ReportReason: String, Codable, CaseIterable, Identifiable {
    case name, harassment, spam, chat, other

    var id: String { rawValue }

    var title: String {
        switch self {
        case .name: "Inappropriate name"
        case .harassment: "Bullying or harassment"
        case .spam: "Spam"
        case .chat: "Inappropriate slime reply"
        case .other: "Something else"
        }
    }

    /// The reasons that fit a person. A chat reply is reported from Chat.
    static var forPeople: [ReportReason] { allCases.filter { $0 != .chat } }
}

/// One player's best score in a game, as the friends leaderboard shows it.
struct LeaderboardEntry: Codable, Hashable, Identifiable {
    var deviceId: String
    var ownerName: String
    var slimeName: String
    var skin: Skin
    var stage: Stage
    var best: Int
    /// This is you.
    var me: Bool

    var id: String { deviceId }

    init(deviceId: String, ownerName: String, slimeName: String, skin: Skin, stage: Stage, best: Int, me: Bool) {
        self.deviceId = deviceId
        self.ownerName = ownerName
        self.slimeName = slimeName
        self.skin = skin
        self.stage = stage
        self.best = best
        self.me = me
    }

    /// Skins and stages from a newer server fall back to defaults instead
    /// of hiding the whole board.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        deviceId = try c.decode(String.self, forKey: .deviceId)
        ownerName = try c.decode(String.self, forKey: .ownerName)
        slimeName = try c.decode(String.self, forKey: .slimeName)
        best = try c.decode(Int.self, forKey: .best)
        me = (try? c.decodeIfPresent(Bool.self, forKey: .me)) ?? false
        skin = (try? c.decodeIfPresent(Skin.self, forKey: .skin)) ?? .lime
        stage = (try? c.decodeIfPresent(Stage.self, forKey: .stage)) ?? .young
    }
}

/// The best scores of you and your friends in one game, best first.
struct LeaderboardGame: Codable, Hashable, Identifiable {
    var game: GameID
    var entries: [LeaderboardEntry]

    var id: String { game.rawValue }

    /// Your own entry, if you played this game.
    var mine: LeaderboardEntry? { entries.first { $0.me } }

    /// 1-based place of an entry. Equal scores share a place.
    func rank(of entry: LeaderboardEntry) -> Int {
        1 + entries.filter { $0.best > entry.best }.count
    }

    init(game: GameID, entries: [LeaderboardEntry]) {
        self.game = game
        self.entries = entries
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        game = try c.decode(GameID.self, forKey: .game)
        entries = try c.decode([LossyElement<LeaderboardEntry>].self, forKey: .entries)
            .compactMap { $0.value }
            .sorted { $0.best > $1.best }
    }
}

/// `GET /friends/leaderboard`. Games this app version doesn't know are
/// skipped, so a newer server never breaks the board.
struct Leaderboard: Codable {
    var games: [LeaderboardGame]

    init(games: [LeaderboardGame]) {
        self.games = games
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        games = try c.decode([LossyElement<LeaderboardGame>].self, forKey: .games)
            .compactMap { $0.value }
            .filter { $0.game != .daily && !$0.entries.isEmpty }
    }
}

/// Decodes a list element when it can and skips it otherwise.
private struct LossyElement<T: Decodable>: Decodable {
    var value: T?

    init(from decoder: Decoder) throws {
        value = try? T(from: decoder)
    }
}

struct ChatTurn: Codable, Hashable {
    enum Role: String, Codable { case user, slime }
    var role: Role
    var text: String
}

struct ChatContext: Codable {
    var name: String
    var ageDays: Int
    var level: Int
    var mood: Mood
    var needs: Needs
    var favorite: Food
    var partnerName: String?
    var localTime: String
}

struct ChatReply: Codable {
    var reply: String
    /// Optional animation the slime plays while saying it.
    var reaction: Reaction?
}

// MARK: - Client

struct HyprnodeAPI {
    static let base = URL(string: "https://slime.hyprnode.de/api/v1/")!

    var token: String?

    static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        return e
    }()

    static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }()

    private struct Empty: Codable {}
    private struct ServerError: Decodable { var error: String }

    struct Registration: Codable {
        var deviceId: String
        var token: String
    }

    /// Longest owner name the server keeps.
    static let nameLimit = 24
    /// Longest slime name the server keeps (the game allows the same).
    static let slimeNameLimit = 16

    /// A name as the server stores it: trimmed, single spaces, at most
    /// `nameLimit` characters.
    static func cleanedName(_ name: String) -> String {
        let words = name.split(whereSeparator: { $0.isWhitespace || $0.isNewline })
        let joined = words.joined(separator: " ")
        return String(joined.prefix(nameLimit)).trimmingCharacters(in: .whitespaces)
    }

    func register(ownerName: String) async throws -> Registration {
        try await send("POST", "devices", body: ["ownerName": ownerName])
    }

    func upload(_ profile: SlimeProfile) async throws {
        try await sendNoContent("PUT", "me/slime", body: profile)
    }

    struct Me: Codable {
        var deviceId: String
        var ownerName: String
        var privacy: Privacy
    }

    func me() async throws -> Me {
        try await send("GET", "me")
    }

    func setPrivacy(_ privacy: Privacy) async throws -> Privacy {
        try await send("PUT", "me/privacy", body: privacy)
    }

    func deleteAccount() async throws {
        try await sendNoContent("DELETE", "me", body: Optional<Empty>.none)
    }

    func newPairCode() async throws -> PairCode {
        try await send("POST", "pair-codes", body: Empty())
    }

    func join(code: String) async throws -> JoinResult {
        try await send("POST", "friend-requests", body: ["code": code.uppercased()])
    }

    func requests() async throws -> RequestList {
        try await send("GET", "friend-requests")
    }

    func accept(_ requestID: String) async throws -> Friend {
        struct Accepted: Codable { var friend: Friend }
        let accepted: Accepted = try await send("POST", "friend-requests/\(requestID)/accept", body: Empty())
        return accepted.friend
    }

    func decline(_ requestID: String) async throws {
        try await sendNoContent("POST", "friend-requests/\(requestID)/decline", body: Empty())
    }

    /// Friends this app version can't read are skipped, not the whole list.
    func friends() async throws -> [Friend] {
        let list: [LossyElement<Friend>] = try await send("GET", "friends")
        return list.compactMap { $0.value }
    }

    func friend(_ id: String) async throws -> FriendDetail {
        try await send("GET", "friends/\(id)")
    }

    func removeFriend(_ id: String) async throws {
        try await sendNoContent("DELETE", "friends/\(id)", body: Optional<Empty>.none)
    }

    func block(_ deviceID: String) async throws {
        try await sendNoContent("POST", "blocks", body: ["deviceId": deviceID])
    }

    func unblock(_ deviceID: String) async throws {
        try await sendNoContent("DELETE", "blocks/\(deviceID)", body: Optional<Empty>.none)
    }

    func blocked() async throws -> [BlockedDevice] {
        try await send("GET", "blocks")
    }

    func interact(with id: String, kind: InteractionKind, food: Food?, key: String) async throws -> InteractionResult {
        struct Body: Codable { var kind: InteractionKind; var food: Food? }
        return try await send("POST", "friends/\(id)/interactions", body: Body(kind: kind, food: food), key: key)
    }

    func message(_ id: String, preset: MessagePreset, key: String) async throws {
        struct Sent: Codable { var sent: Bool }
        let _: Sent = try await send("POST", "friends/\(id)/messages", body: ["preset": preset.rawValue], key: key)
    }

    func addCoop(_ id: String, points: Int, key: String) async throws -> CoopStatus {
        try await send("POST", "friends/\(id)/coop", body: ["points": points], key: key)
    }

    /// Challenges in games this app version doesn't have are skipped, so a
    /// friend with a newer Slimy can't break the list.
    func challenges() async throws -> [Challenge] {
        let list: [LossyElement<Challenge>] = try await send("GET", "challenges")
        return list.compactMap { $0.value }
    }

    func challenge(_ friendID: String, game: GameID, seed: UInt64, score: Int, key: String) async throws -> Challenge {
        struct Body: Codable { var friendId: String; var game: GameID; var seed: String; var score: Int }
        return try await send("POST", "challenges", body: Body(friendId: friendID, game: game, seed: String(seed), score: score), key: key)
    }

    func answer(_ challengeID: String, score: Int, key: String) async throws -> Challenge {
        try await send("POST", "challenges/\(challengeID)/answer", body: ["score": score], key: key)
    }

    // MARK: Reports, records, leaderboard

    struct ReportBody: Codable, Equatable {
        var deviceId: String?
        var reason: ReportReason
        var note: String?
        var text: String?
    }

    /// Longest note a report may carry.
    static let reportNoteLimit = 200
    /// Longest reported chat reply.
    static let reportTextLimit = 500

    /// Sends a report to the Slimy team. `deviceId` is required unless the
    /// reason is `.chat`; then `text` is the slime's reply.
    func report(_ report: ReportBody, key: String) async throws {
        try await sendNoContent("POST", "reports", body: report, key: key)
    }

    struct RecordsBody: Codable, Equatable {
        var records: [String: Int]
    }

    /// Highest score the server keeps per game.
    static let recordCap = 1_000_000

    /// Best scores as the server takes them: game id to score, without the
    /// app-only daily game and empty scores, capped.
    static func wireRecords(_ records: [GameID: Int]) -> [String: Int] {
        var out: [String: Int] = [:]
        for (game, best) in records where game != .daily && best > 0 {
            out[game.rawValue] = min(best, recordCap)
        }
        return out
    }

    /// Raises your best scores on the server; it never lowers them.
    func uploadRecords(_ records: [String: Int]) async throws {
        try await sendNoContent("PUT", "me/records", body: RecordsBody(records: records))
    }

    func leaderboard() async throws -> [LeaderboardGame] {
        let board: Leaderboard = try await send("GET", "friends/leaderboard")
        return board.games
    }

    func inbox(after cursor: String?) async throws -> InboxPage {
        var path = "inbox"
        if let cursor { path += "?after=\(cursor)" }
        return try await send("GET", path)
    }

    /// The server tries up to three AI services one after another, 12 s
    /// each, and sends nothing until it has the reply.
    static let chatTimeout: TimeInterval = 45

    func chat(message: String, history: [ChatTurn], context: ChatContext) async throws -> ChatReply {
        struct Body: Codable { var message: String; var history: [ChatTurn]; var slime: ChatContext }
        return try await send("POST", "chat", body: Body(message: message, history: history, slime: context),
                              timeout: Self.chatTimeout,
                              slowAnswer: "Your slime took too long to answer. Try again in a moment.")
    }

    // MARK: Plumbing

    /// How long a request may wait for the server's next bytes.
    static let defaultTimeout: TimeInterval = 20
    static let slowServerAnswer = "slime.hyprnode.de took too long to answer. Try again in a moment."

    private func send<T: Decodable>(_ method: String, _ path: String) async throws -> T {
        try await send(method, path, body: Optional<Empty>.none)
    }

    private func send<T: Decodable, B: Encodable>(_ method: String, _ path: String, body: B?, key: String? = nil,
                                                  timeout: TimeInterval = HyprnodeAPI.defaultTimeout,
                                                  slowAnswer: String = HyprnodeAPI.slowServerAnswer) async throws -> T {
        let data = try await raw(method, path, body: body, key: key, timeout: timeout, slowAnswer: slowAnswer)
        return try Self.decoder.decode(T.self, from: data)
    }

    private func sendNoContent<B: Encodable>(_ method: String, _ path: String, body: B?, key: String? = nil) async throws {
        _ = try await raw(method, path, body: body, key: key, timeout: Self.defaultTimeout, slowAnswer: Self.slowServerAnswer)
    }

    /// A timeout becomes a 504 with `slowAnswer`: the connection worked but
    /// the answer didn't come in time. Like a 5xx it is worth trying again.
    private func raw<B: Encodable>(_ method: String, _ path: String, body: B?, key: String?,
                                   timeout: TimeInterval, slowAnswer: String) async throws -> Data {
        guard let url = URL(string: path, relativeTo: Self.base) else { throw APIError.notReady }
        var request = URLRequest(url: url, timeoutInterval: timeout)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let key { request.setValue(key, forHTTPHeaderField: "Idempotency-Key") }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try Self.encoder.encode(body)
        }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await URLSession.shared.data(for: request)
        } catch let error as URLError where [.cannotFindHost, .dnsLookupFailed, .cannotConnectToHost,
                                             .secureConnectionFailed].contains(error.code) {
            throw APIError.notReady
        } catch let error as URLError where error.code == .timedOut {
            throw APIError.server(status: 504, message: slowAnswer)
        } catch {
            throw APIError.offline
        }

        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            let message = try? Self.decoder.decode(ServerError.self, from: data).error
            if status == 404, message == nil { throw APIError.notReady }
            throw APIError.server(status: status, message: message)
        }
        return data
    }
}
