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
        case .notReady: "slime.hyprnode.de isn't online yet. Friends and chat work once it's running."
        }
    }

    /// Worth trying again later (offline queue); a 4xx answer is final.
    var isTransient: Bool {
        switch self {
        case .offline, .notReady: true
        case .server(let status, _): status >= 500 || status == 429
        }
    }
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
}

struct Friend: Codable, Hashable, Identifiable {
    var id: String
    var ownerName: String
    var slime: SlimeProfile
    /// 0...100. The slimes fall in love at 100.
    var love: Int
    var inLove: Bool
    var since: Date

    static let demoID = "demo-mochi"
    var isDemo: Bool { id == Self.demoID }
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

    func friends() async throws -> [Friend] {
        try await send("GET", "friends")
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

    func challenges() async throws -> [Challenge] {
        try await send("GET", "challenges")
    }

    func challenge(_ friendID: String, game: GameID, seed: UInt64, score: Int, key: String) async throws -> Challenge {
        struct Body: Codable { var friendId: String; var game: GameID; var seed: String; var score: Int }
        return try await send("POST", "challenges", body: Body(friendId: friendID, game: game, seed: String(seed), score: score), key: key)
    }

    func answer(_ challengeID: String, score: Int, key: String) async throws -> Challenge {
        try await send("POST", "challenges/\(challengeID)/answer", body: ["score": score], key: key)
    }

    func inbox(after cursor: String?) async throws -> InboxPage {
        var path = "inbox"
        if let cursor { path += "?after=\(cursor)" }
        return try await send("GET", path)
    }

    /// The day's plan the server had Claude make. 404 when it has none.
    func daily(day: String) async throws -> DailyPlan {
        try await send("GET", "daily?day=\(day)")
    }

    func chat(message: String, history: [ChatTurn], context: ChatContext) async throws -> ChatReply {
        struct Body: Codable { var message: String; var history: [ChatTurn]; var slime: ChatContext }
        return try await send("POST", "chat", body: Body(message: message, history: history, slime: context))
    }

    // MARK: Plumbing

    private func send<T: Decodable>(_ method: String, _ path: String) async throws -> T {
        try await send(method, path, body: Optional<Empty>.none)
    }

    private func send<T: Decodable, B: Encodable>(_ method: String, _ path: String, body: B?, key: String? = nil) async throws -> T {
        let data = try await raw(method, path, body: body, key: key)
        return try Self.decoder.decode(T.self, from: data)
    }

    private func sendNoContent<B: Encodable>(_ method: String, _ path: String, body: B?) async throws {
        _ = try await raw(method, path, body: body, key: nil)
    }

    private func raw<B: Encodable>(_ method: String, _ path: String, body: B?, key: String?) async throws -> Data {
        guard let url = URL(string: path, relativeTo: Self.base) else { throw APIError.notReady }
        var request = URLRequest(url: url, timeoutInterval: 20)
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
