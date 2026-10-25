import Foundation

struct ChatMessage: Codable, Identifiable, Hashable {
    var id = UUID()
    var role: Role
    var text: String
    var date = Date.now

    enum Role: String, Codable { case me, slime, note }
}

/// Talking to the slime. Replies come from slime.hyprnode.de/api/v1/chat,
/// which calls an AI model in character. Until that's live the slime only
/// blubs back, and a note explains why.
@MainActor
final class ChatStore: ObservableObject {
    static let shared = ChatStore()

    @Published private(set) var messages: [ChatMessage] = []
    @Published private(set) var isThinking = false

    private let key = "slimy.chat.v1"
    private var shownOfflineNote = false

    init() {
        if let data = UserDefaults.standard.data(forKey: key),
           let saved = try? JSONDecoder().decode([ChatMessage].self, from: data) {
            messages = saved
        }
    }

    func send(_ text: String) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !isThinking else { return }
        messages.append(ChatMessage(role: .me, text: trimmed))
        isThinking = true
        defer { isThinking = false; save() }

        let slime = GameStore.shared.state.slime
        let history = messages.suffix(12).compactMap { m -> ChatTurn? in
            switch m.role {
            case .me: ChatTurn(role: .user, text: m.text)
            case .slime: ChatTurn(role: .slime, text: m.text)
            case .note: nil
            }
        }
        let context = ChatContext(
            name: slime.name, ageDays: slime.ageDays(), level: slime.level, mood: slime.mood,
            needs: slime.needs, favorite: slime.favorite,
            partnerName: FriendsStore.shared.partner?.slime.name,
            localTime: Date.now.formatted(date: .omitted, time: .shortened))

        do {
            let api = try await FriendsStore.shared.authorizedAPI()
            let reply = try await api.chat(message: trimmed, history: Array(history.dropLast()), context: context)
            messages.append(ChatMessage(role: .slime, text: reply.reply))
            if let reaction = reply.reaction {
                GameStore.shared.update { $0.slime.react(reaction, at: .now) }
            }
        } catch let error as APIError where !error.isTransient {
            // The server answered with a reason (for example the daily limit).
            messages.append(ChatMessage(role: .note, text: error.localizedDescription))
        } catch {
            try? await Task.sleep(for: .milliseconds(700))
            messages.append(ChatMessage(role: .slime, text: Self.blubs.randomElement()!))
            if !shownOfflineNote {
                shownOfflineNote = true
                messages.append(ChatMessage(role: .note, text: "Richtig reden kann er, sobald slime.hyprnode.de erreichbar ist."))
            }
        }
    }

    func clear() {
        messages.removeAll()
        save()
    }

    private func save() {
        if let data = try? JSONEncoder().encode(Array(messages.suffix(80))) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }

    private static let blubs = [
        "blub?", "blubb blubb.", "*wackelt*", "blub.", "*legt sich schief*", "bwop?",
    ]
}
