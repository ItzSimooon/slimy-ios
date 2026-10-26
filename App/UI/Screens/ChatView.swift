import SwiftUI
import UIKit

/// Talking to the slime. Replies are written by an AI service through the
/// Slimy server, so the chat only sends once the player went online and
/// agreed to that in the consent sheet.
struct ChatView: View {
    @EnvironmentObject private var chat: ChatStore
    @EnvironmentObject private var store: GameStore
    @EnvironmentObject private var friends: FriendsStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @FocusState private var inputFocused: Bool
    @State private var sheet: ChatSheet?
    @State private var pending: PendingSend?
    @State private var confirmClear = false
    @State private var toast: Toast?

    private let bottomID = "chat.bottom"

    private enum ChatSheet: String, Identifiable {
        case consent, goOnline, aiInfo
        var id: String { rawValue }
    }

    /// What waits for the consent sheet: a new message or a Try Again.
    private enum PendingSend {
        case message(text: String, fromDraft: Bool)
        case retry(ChatMessage)
    }

    private var slime: Slime { store.state.slime }

    /// Sending is possible apart from consent, which the send itself asks for.
    private var canSend: Bool { friends.isOnline && !chat.isThinking }

    var body: some View {
        NavigationStack {
            conversation
                .background(Theme.backdrop)
                .safeAreaInset(edge: .bottom, spacing: 0) { bottomBar }
                .overlay(alignment: .top) { toastBanner }
                .animation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.85), value: toast)
                .sheetTitle(slime.name, inline: true)
                .toolbar {
                    ToolbarItem(placement: .principal) { header }
                    ToolbarItem(placement: .topBarLeading) { menu }
                }
                .confirmationDialog("Clear this chat?", isPresented: $confirmClear, titleVisibility: .visible) {
                    Button("Clear Chat", role: .destructive) { chat.clear() }
                    Button("Cancel", role: .cancel) {}
                } message: {
                    Text("The conversation is deleted from this iPhone. This can't be undone.")
                }
        }
        .sheet(item: $sheet, onDismiss: sheetDismissed) { item in
            Group {
                switch item {
                case .consent:
                    ChatConsentView(slime: slime) { chat.aiConsent = true }
                case .goOnline:
                    GoOnlineView()
                case .aiInfo:
                    ChatAIInfoView()
                }
            }
            .environmentObject(chat)
            .environmentObject(store)
            .environmentObject(friends)
        }
        .onChange(of: friends.isOnline) { _, online in
            // Back to the chat once going online worked.
            if online, sheet == .goOnline { sheet = nil }
        }
    }

    // MARK: Navigation bar

    private var header: some View {
        VStack(spacing: 1) {
            Text(slime.name)
                .font(.headline)
                .foregroundStyle(Theme.ink)
                .lineLimit(1)
            Text(status)
                .font(.caption)
                .foregroundStyle(chat.isThinking ? Theme.accent : Theme.inkSoft)
                .lineLimit(1)
                .contentTransition(.opacity)
                .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: status)
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }

    private var status: String {
        if !friends.isOnline { return "Offline" }
        if chat.isThinking { return "Typing…" }
        if slime.isAsleep { return "Sleepy" }
        if slime.mood == .sad { return "Needs some care" }
        return "Happy to talk"
    }

    private var menu: some View {
        Menu {
            Button {
                sheet = .aiInfo
            } label: {
                Label("About AI Chat", systemImage: "info.circle")
            }
            Button(role: .destructive) {
                confirmClear = true
            } label: {
                Label("Clear Chat", systemImage: "trash")
            }
            .disabled(chat.messages.isEmpty)
        } label: {
            Image(systemName: "ellipsis.circle")
                .accessibilityLabel("Chat options")
        }
    }

    // MARK: Conversation

    @ViewBuilder
    private var conversation: some View {
        if chat.messages.isEmpty && !chat.isThinking {
            emptyState
        } else {
            messageList
        }
    }

    private var emptyState: some View {
        GeometryReader { geo in
            ScrollView {
                VStack(spacing: 18) {
                    LiveSlimeView(height: 96, interactive: false)
                    Text("hi! it's me, \(slime.name).\nlet's talk.")
                        .font(Theme.voice(22)).fontDesign(nil)
                        .foregroundStyle(Theme.ink)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(friends.isOnline
                         ? "Ask how it feels, what it ate or who its friends are."
                         : "Once you're online, you can ask how it feels, what it ate or who its friends are.")
                        .font(.subheadline)
                        .foregroundStyle(Theme.inkSoft)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                    if friends.isOnline {
                        ChatSuggestions(enabled: canSend) { suggestion in
                            submit(suggestion, fromDraft: false)
                        }
                        .padding(.top, 4)
                    }
                    aiNotice
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 24)
                .frame(maxWidth: .infinity, minHeight: geo.size.height)
            }
            .scrollBounceBehavior(.basedOnSize)
            .scrollDismissesKeyboard(.interactively)
        }
    }

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 0) {
                    aiNotice
                        .padding(.bottom, 4)
                    ForEach(Array(chat.messages.enumerated()), id: \.element.id) { index, message in
                        row(message, at: index)
                            .padding(.top, gap(above: index))
                            .transition(arrival(from: .bottom))
                    }
                    if chat.isThinking {
                        ChatTypingIndicator(slime: slime)
                            .padding(.top, 10)
                            .transition(.opacity)
                    }
                    Color.clear
                        .frame(height: 1)
                        .id(bottomID)
                }
                .padding(.horizontal, 12)
                .padding(.top, 4)
                .padding(.bottom, 12)
                .animation(reduceMotion ? nil : .snappy(duration: 0.25), value: chat.messages)
                .animation(reduceMotion ? nil : .snappy(duration: 0.25), value: chat.isThinking)
            }
            .defaultScrollAnchor(.bottom)
            .scrollDismissesKeyboard(.interactively)
            .onAppear { proxy.scrollTo(bottomID, anchor: .bottom) }
            .onChange(of: chat.messages.last?.id) { scrollToBottom(proxy) }
            .onChange(of: chat.isThinking) { scrollToBottom(proxy) }
            .onChange(of: inputFocused) { _, focused in
                guard focused else { return }
                Task {
                    // Once the keyboard is up.
                    try? await Task.sleep(for: .milliseconds(300))
                    scrollToBottom(proxy)
                }
            }
        }
    }

    @ViewBuilder
    private func row(_ message: ChatMessage, at index: Int) -> some View {
        let messages = chat.messages
        let previous = index > 0 ? messages[index - 1] : nil
        let next = index + 1 < messages.count ? messages[index + 1] : nil
        VStack(spacing: 8) {
            if let stamp = timestamp(for: message, after: previous) {
                Text(stamp)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(Theme.inkSoft)
                    .frame(maxWidth: .infinity)
                    .padding(.top, previous == nil ? 0 : 8)
            }
            switch message.role {
            case .note:
                ChatNoteRow(message: message, canRetry: canSend) { retry(message) }
            case .me, .slime:
                ChatBubbleRow(
                    message: message,
                    slime: slime,
                    endsRun: next?.role != message.role,
                    onCopy: { copy(message) },
                    onReport: { report(message) })
            }
        }
    }

    /// Tight between messages from the same sender, roomier between turns.
    private func gap(above index: Int) -> CGFloat {
        guard index > 0 else { return 0 }
        let previous = chat.messages[index - 1], current = chat.messages[index]
        return previous.role == current.role && current.role != .note ? 3 : 12
    }

    /// A time above the first message and after a pause of an hour or more.
    private func timestamp(for message: ChatMessage, after previous: ChatMessage?) -> String? {
        guard let previous else { return ChatTimestamp.text(for: message.date) }
        guard message.date.timeIntervalSince(previous.date) >= 3600 else { return nil }
        return ChatTimestamp.text(for: message.date)
    }

    /// Says that the replies are AI-written; tapping it explains more.
    private var aiNotice: some View {
        Button {
            sheet = .aiInfo
        } label: {
            Label("Replies are written by AI and can be wrong.", systemImage: "info.circle")
                .font(.caption)
                .foregroundStyle(Theme.inkSoft)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 12)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity)
        .accessibilityHint("Explains how AI chat works")
    }

    // MARK: Bottom

    @ViewBuilder
    private var bottomBar: some View {
        if friends.isOnline {
            ChatInputBar(text: $chat.draft, focused: $inputFocused, canSend: canSend) {
                submit(chat.draft, fromDraft: true)
            }
        } else {
            ChatOfflineCard { sheet = .goOnline }
        }
    }

    @ViewBuilder
    private var toastBanner: some View {
        if let toast {
            ToastBanner(toast: toast)
                .padding(.top, 8)
                .padding(.horizontal, 16)
                .transition(arrival(from: .top))
                .id(toast.id)
                .task(id: toast.id) {
                    try? await Task.sleep(for: .seconds(2.2))
                    if self.toast == toast { self.toast = nil }
                }
                .allowsHitTesting(false)
        }
    }

    // MARK: Actions

    private func submit(_ text: String, fromDraft: Bool) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, canSend else { return }
        guard chat.aiConsent else {
            // Nothing leaves the phone before the player agreed.
            pending = .message(text: trimmed, fromDraft: fromDraft)
            inputFocused = false
            sheet = .consent
            return
        }
        if fromDraft { chat.draft = "" }
        Feedback.shared.play(.tap)
        Task {
            let sent = await chat.send(trimmed)
            // It couldn't go out after all: give the text back.
            if !sent, fromDraft, chat.draft.isEmpty { chat.draft = trimmed }
        }
    }

    private func sheetDismissed() {
        guard let waiting = pending else { return }
        pending = nil
        guard chat.aiConsent else { return }
        switch waiting {
        case .message(text: let text, fromDraft: let fromDraft):
            submit(text, fromDraft: fromDraft)
        case .retry(let note):
            retry(note)
        }
    }

    private func retry(_ note: ChatMessage) {
        guard canSend else { return }
        guard chat.aiConsent else {
            pending = .retry(note)
            sheet = .consent
            return
        }
        Feedback.shared.play(.tap)
        Task { await chat.retry(note) }
    }

    private func copy(_ message: ChatMessage) {
        UIPasteboard.general.string = message.text
        show(Toast(text: "Copied", good: true))
    }

    private func report(_ message: ChatMessage) {
        guard !message.reported else { return }
        guard friends.isOnline else {
            show(Toast(text: "Go online to report a reply.", good: false))
            return
        }
        Task {
            friends.lastError = nil
            if await chat.report(message) {
                show(Toast(text: "Thanks. We'll look at this reply.", good: true))
            } else {
                // For example the daily report limit, in the server's words.
                let reason = friends.lastError ?? "Couldn't send the report. Try again later."
                friends.lastError = nil
                show(Toast(text: reason, good: false))
            }
        }
    }

    private func show(_ next: Toast) {
        toast = next
        UIAccessibility.post(notification: .announcement, argument: next.text)
    }

    /// Slides in from an edge, or only fades with Reduce Motion.
    private func arrival(from edge: Edge) -> AnyTransition {
        reduceMotion ? AnyTransition.opacity : AnyTransition.move(edge: edge).combined(with: .opacity)
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy) {
        if reduceMotion {
            proxy.scrollTo(bottomID, anchor: .bottom)
        } else {
            withAnimation(.easeOut(duration: 0.25)) { proxy.scrollTo(bottomID, anchor: .bottom) }
        }
    }
}
