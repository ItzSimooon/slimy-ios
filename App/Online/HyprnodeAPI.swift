import Foundation

// Client for the slime.hyprnode.de backend. The full contract, including
// rules the server enforces, is in HANDOVER.md. Keep the two in sync.

enum APIError: LocalizedError {
    case offline
    case server(status: Int, message: String?)
    case notReady

    var errorDescription: String? {
        switch self {
        case .offline: "Can't reach slime.hyprnode.de. Check your internet and try again."
        case .server(let status, let message): message ?? "The server answered with error \(status)."
        case .notReady: "slime.hyprnode.de isn't live yet. Friends and chat switch on once it is."
        }
    }
}

// MARK: - Wire types

struct SlimeProfile: Codable, Hashable {
    var ownerName: String
    var name: String
    var skin: Skin
    var level: Int
    var mood: Mood
    var happiness: Int
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
}

struct InboxEvent: Codable, Identifiable {
    var id: String
    var friendId: String
    var kind: InteractionKind
    var food: Food?
    var createdAt: Date
}

struct InboxPage: Codable {
    var events: [InboxEvent]
    var cursor: String?
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
    static let base = URL(string: "https://slime.hyprnode.de/api/v1")!

    var token: String?

    private static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        return e
    }()

    private static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }()

    private struct Empty: Codable {}

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

    func newPairCode() async throws -> PairCode {
        try await send("POST", "pair-codes", body: Empty())
    }

    func join(code: String) async throws -> Friend {
        try await send("POST", "friends", body: ["code": code.uppercased()])
    }

    func friends() async throws -> [Friend] {
        try await send("GET", "friends")
    }

    func removeFriend(_ id: String) async throws {
        try await sendNoContent("DELETE", "friends/\(id)", body: Optional<Empty>.none)
    }

    func interact(with id: String, kind: InteractionKind, food: Food? = nil) async throws -> InteractionResult {
        struct Body: Codable { var kind: InteractionKind; var food: Food? }
        return try await send("POST", "friends/\(id)/interactions", body: Body(kind: kind, food: food))
    }

    func inbox(after cursor: String?) async throws -> InboxPage {
        var path = "inbox"
        if let cursor { path += "?after=\(cursor)" }
        return try await send("GET", path)
    }

    func chat(message: String, history: [ChatTurn], context: ChatContext) async throws -> ChatReply {
        struct Body: Codable { var message: String; var history: [ChatTurn]; var slime: ChatContext }
        return try await send("POST", "chat", body: Body(message: message, history: history, slime: context))
    }

    // MARK: Plumbing

    private func send<T: Decodable>(_ method: String, _ path: String) async throws -> T {
        try await send(method, path, body: Optional<Empty>.none)
    }

    private func send<T: Decodable, B: Encodable>(_ method: String, _ path: String, body: B?) async throws -> T {
        let data = try await raw(method, path, body: body)
        return try Self.decoder.decode(T.self, from: data)
    }

    private func sendNoContent<B: Encodable>(_ method: String, _ path: String, body: B?) async throws {
        _ = try await raw(method, path, body: body)
    }

    private func raw<B: Encodable>(_ method: String, _ path: String, body: B?) async throws -> Data {
        guard let url = URL(string: path, relativeTo: Self.base) else { throw APIError.notReady }
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
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
            struct ServerError: Decodable { var error: String }
            let message = try? Self.decoder.decode(ServerError.self, from: data).error
            if status == 404, message == nil { throw APIError.notReady }
            throw APIError.server(status: status, message: message)
        }
        return data
    }
}
