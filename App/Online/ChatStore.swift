import Foundation

/// One line in the conversation with the slime.
struct ChatMessage: Codable, Identifiable, Hashable {
    enum Role: String, Codable { case me, slime, note }

    var id: UUID
    var role: Role
    var text: String
    var date: Date
    /// On your message: it didn't reach the slime and isn't sent as history.
    /// On a note: the note explains an error.
    var failed: Bool
    /// On a slime reply: you reported it.
    var reported: Bool
    /// On an error note: the message that Try Again sends again.
    var retryID: UUID?
    /// On a slime reply: the feeling it expressed, if the server named one.
    var emotion: Emotion?
    /// How strongly the reply expressed `emotion`, 0...1.
    var intensity: Double?

    init(role: Role, text: String, date: Date = .now, failed: Bool = false, retryID: UUID? = nil,
         emotion: Emotion? = nil, intensity: Double? = nil) {
        self.id = UUID()
        self.role = role
        self.text = text
        self.date = date
        self.failed = failed
        self.reported = false
        self.retryID = retryID
        self.emotion = emotion
        self.intensity = intensity
    }

    private enum CodingKeys: String, CodingKey {
        case id, role, text, date, failed, reported, retryID, emotion, intensity
    }

    // Tolerant, so chats saved by older versions still load.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(UUID.self, forKey: .id) ?? UUID()
        role = try c.decode(Role.self, forKey: .role)
        text = try c.decode(String.self, forKey: .text)
        date = try c.decodeIfPresent(Date.self, forKey: .date) ?? .now
        failed = try c.decodeIfPresent(Bool.self, forKey: .failed) ?? false
        reported = try c.decodeIfPresent(Bool.self, forKey: .reported) ?? false
        retryID = try c.decodeIfPresent(UUID.self, forKey: .retryID)
        // A feeling this version doesn't know just isn't shown.
        emotion = try? c.decodeIfPresent(Emotion.self, forKey: .emotion)
        intensity = try? c.decodeIfPresent(Double.self, forKey: .intensity)
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(role, forKey: .role)
        try c.encode(text, forKey: .text)
        try c.encode(date, forKey: .date)
        try c.encode(failed, forKey: .failed)
        try c.encode(reported, forKey: .reported)
        try c.encodeIfPresent(retryID, forKey: .retryID)
        try c.encodeIfPresent(emotion, forKey: .emotion)
        try c.encodeIfPresent(intensity, forKey: .intensity)
    }
}

/// Talking to the slime. Replies come from slime.hyprnode.de/api/v1/chat,
/// which has an AI service answer in character. Nothing is sent before the
/// player agreed to that (`aiConsent`) and went online (`FriendsStore.isOnline`).
/// The conversation itself is kept only on this phone.
@MainActor
final class ChatStore: ObservableObject {
    static let shared = ChatStore()

    static let consentKey = "slimy.chat.aiConsent"
    static let privacyURL = URL(string: "https://slime.hyprnode.de/privacy")!
    /// The longest message the server accepts.
    static let maxLength = 500
    /// The longest reply the server sends.
    static let maxReplyLength = 240
    /// The server's answer when no AI service is set up. Trying again right
    /// away can't help, so that note has no Try Again.
    private static let notSetUp = "Talking isn't set up on the server right now."

    @Published private(set) var messages: [ChatMessage] = []
    @Published private(set) var isThinking = false
    /// The player agreed that messages go to an AI service to write the
    /// replies. Nothing is sent without it.
    @Published var aiConsent: Bool {
        didSet { UserDefaults.standard.set(aiConsent, forKey: Self.consentKey) }
    }
    /// What's typed but not sent yet. Kept while the app runs, so closing
    /// the chat doesn't lose it.
    @Published var draft = ""

    private let historyKey = "slimy.chat.v1"
    /// Messages kept on the phone.
    private static let keep = 80
    /// Earlier turns sent along so the slime knows what you talked about.
    private static let historyTurns = 12
    /// Goes up with every `clear()`. A reply that was on its way to a
    /// cleared conversation is dropped instead of landing in the next one.
    private var conversation = 0

    init() {
        let defaults = UserDefaults.standard
        aiConsent = defaults.bool(forKey: Self.consentKey)
        if let data = defaults.data(forKey: historyKey),
           var saved = try? JSONDecoder().decode([ChatMessage].self, from: data) {
            // The app was closed while a reply was on its way: that reply
            // never comes, so the message gets a Try Again instead of hanging.
            if let last = saved.last, last.role == .me, !last.failed {
                saved[saved.count - 1].failed = true
                saved.append(ChatMessage(role: .note, text: "Your message didn't get an answer. Try again.",
                                         failed: true, retryID: last.id))
            }
            messages = saved
        }
    }

    /// Whether a message could go out right now.
    var canSend: Bool { aiConsent && FriendsStore.shared.isOnline && !isThinking }

    /// Stops sending messages to the AI service. The next message asks again.
    func revokeConsent() {
        aiConsent = false
    }

    /// Sends a message and waits for the reply. Returns false when nothing
    /// was sent: empty text, no consent, not online, or a reply is on its way.
    @discardableResult
    func send(_ text: String) async -> Bool {
        let trimmed = String(text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Self.maxLength))
        guard !trimmed.isEmpty, canSend else { return false }
        let message = ChatMessage(role: .me, text: trimmed)
        messages.append(message)
        save()
        await deliver(message)
        return true
    }

    /// Sends the message an error note is about once more.
    func retry(_ note: ChatMessage) async {
        guard canSend, let target = note.retryID,
              var message = messages.first(where: { $0.id == target }) else { return }
        messages.removeAll { $0.id == note.id || $0.id == target }
        // Back at the end, so the reply follows it.
        message.failed = false
        message.date = .now
        messages.append(message)
        save()
        await deliver(message)
    }

    /// Reports a slime reply to the Slimy team. Only its text is sent.
    func report(_ message: ChatMessage) async -> Bool {
        guard message.role == .slime, !message.reported else { return false }
        let text = String(message.text.prefix(500))
        let sent = await FriendsStore.shared.report(deviceID: nil, reason: .chat, note: nil, text: text)
        if sent, let i = messages.firstIndex(where: { $0.id == message.id }) {
            messages[i].reported = true
            save()
        }
        return sent
    }

    /// Deletes the conversation, also while a reply is on its way (for
    /// example when the slime is released in Settings): that reply is
    /// dropped when it comes, and the next message can go out right away.
    func clear() {
        conversation += 1
        isThinking = false
        messages.removeAll()
        save()
    }

    // MARK: Sending

    private func deliver(_ message: ChatMessage) async {
        let round = conversation
        isThinking = true
        defer {
            // After a clear the wait is already over, and a message in the
            // new conversation may be waiting for its own reply by now.
            if round == conversation {
                isThinking = false
                save()
            }
        }

        let history = turns(before: message.id)
        let context = Self.context(for: GameStore.shared.state.slime)

        do {
            let api = try await FriendsStore.shared.authorizedAPI()
            let reply = try await api.chat(message: message.text, history: history, context: context)
            // Cleared while the reply was on its way.
            guard round == conversation, messages.contains(where: { $0.id == message.id }) else { return }
            // A message the server's filter stops (swearing, an email or a
            // phone number) still gets an in-character reply with `.confused`,
            // so it shows like any other reply.
            let text = String(reply.reply.trimmingCharacters(in: .whitespacesAndNewlines)
                .prefix(Self.maxReplyLength))
            // A feeling this version doesn't know is left out.
            let felt = reply.emotion.flatMap { Emotion(rawValue: $0.name) }
            let intensity: Double? = felt == nil ? nil : reply.emotion?.intensity
            messages.append(ChatMessage(role: .slime, text: text.isEmpty ? "*wobbles*" : text,
                                        emotion: felt, intensity: intensity))
            express(felt, intensity: intensity ?? 0, reaction: reply.reaction)
            Feedback.shared.play(.pop)
        } catch {
            // A ban takes the device offline; a device the server forgot
            // registers again, so Try Again can work.
            FriendsStore.shared.handleServerError(error)
            guard round == conversation,
                  let i = messages.firstIndex(where: { $0.id == message.id }) else { return }
            messages[i].failed = true
            let problem = Self.describe(error)
            messages.append(ChatMessage(role: .note, text: problem.text, failed: true,
                                        retryID: problem.canRetry ? message.id : nil))
            Feedback.shared.play(.nope)
        }
    }

    /// What the AI service learns about the slime, including how it feels
    /// right now, so the reply comes in that mood. No owner name, no device
    /// ID, and no friend's or caretaker's name.
    private static func context(for slime: Slime, now: Date = .now) -> ChatContext {
        var context = ChatContext(
            name: String(slime.name.prefix(16)),
            ageDays: slime.ageDays(at: now),
            level: slime.level,
            mood: slime.mood,
            needs: slime.needs,
            favorite: slime.favorite,
            localTime: now.formatted(date: .omitted, time: .shortened))
        let feelings = slime.emotions.wire
        context.emotions = feelings.isEmpty ? nil : feelings
        return context
    }

    // MARK: Feelings

    /// How much of a reply's feeling rubs off on the slime: a reply at full
    /// intensity moves that feeling by about a third.
    static let feelingShare = 0.35

    /// The slime feels a bit more of what its reply expressed and shows it.
    /// The animation the reply asked for wins; without one, the feeling's own
    /// plays, unless the feeling was only faint.
    private func express(_ emotion: Emotion?, intensity: Double, reaction asked: Reaction?) {
        let store = GameStore.shared
        var reaction: Reaction = asked ?? Reaction.none
        if let emotion {
            store.feel(emotion, intensity * Self.feelingShare)
            if reaction == Reaction.none, intensity >= 0.15 {
                reaction = Self.reaction(showing: emotion, intensity: intensity)
            }
        }
        guard reaction != Reaction.none else { return }
        let chosen = reaction
        store.update { $0.slime.react(chosen, at: .now) }
    }

    /// The animation that shows a feeling.
    static func reaction(showing emotion: Emotion, intensity: Double) -> Reaction {
        switch emotion {
        case .joy: return intensity >= 0.7 ? .celebrate : .wave
        case .sadness: return .melt
        case .anger: return .sulk
        case .fear: return .hide
        case .love: return intensity >= 0.6 ? .love : .blush
        case .curiosity: return .curious
        case .boredom: return .stretch
        case .excitement: return .celebrate
        case .sleepiness: return .stretch
        }
    }

    /// The conversation before a message, as the server wants it: only
    /// what was really said, newest last.
    private func turns(before id: UUID) -> [ChatTurn] {
        var turns: [ChatTurn] = []
        for m in messages {
            if m.id == id { break }
            guard !m.failed else { continue }
            switch m.role {
            case .me: turns.append(ChatTurn(role: .user, text: m.text))
            case .slime: turns.append(ChatTurn(role: .slime, text: m.text))
            case .note: continue
            }
        }
        return Array(turns.suffix(Self.historyTurns))
    }

    /// What to tell the player, and whether trying again could help.
    private static func describe(_ error: Error) -> (text: String, canRetry: Bool) {
        if let api = error as? APIError {
            if case .offline = api {
                return ("Your message didn't go through. Check your internet connection and try again.", true)
            }
            if api.isUnauthorized {
                return ("Slimy is reconnecting to its server. Try again in a moment.", true)
            }
            if case .server(let status, let message) = api {
                // The server's own words, shown as they are: for example
                // "Your slime is sleepy from all the talking. Try again later."
                // (503) or a rate limit (429).
                if let message {
                    return (message, api.isTransient && message != notSetUp)
                }
                // No JSON error, for example a proxy page while the server restarts.
                return (status >= 500
                        ? "Slimy's server isn't answering right now. Try again in a moment."
                        : "Something went wrong. Your message wasn't sent.",
                        api.isTransient)
            }
            return (api.localizedDescription, api.isTransient)
        }
        if error is DecodingError {
            return ("The reply got lost on the way. Try again.", true)
        }
        if let described = (error as? LocalizedError)?.errorDescription {
            return (described, true)
        }
        return ("Something went wrong. Your message wasn't sent.", true)
    }

    private func save() {
        if let data = try? JSONEncoder().encode(Array(messages.suffix(Self.keep))) {
            UserDefaults.standard.set(data, forKey: historyKey)
        }
    }
}
