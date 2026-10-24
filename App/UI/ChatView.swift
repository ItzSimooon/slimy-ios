import SwiftUI

struct ChatView: View {
    @EnvironmentObject private var chat: ChatStore
    @EnvironmentObject private var store: SlimeStore
    @Environment(\.dismiss) private var dismiss
    @State private var draft = ""
    @FocusState private var typing: Bool

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                LiveSlimeView(height: 44, interactive: false)
                VStack(alignment: .leading, spacing: 2) {
                    Text(store.state.name)
                        .font(Brand.display(20, .black))
                        .foregroundStyle(Brand.text)
                    Eyebrow(text: chat.isThinking ? "typing…" : "talk to your slime", color: Brand.hyprGlow)
                }
                Spacer()
                Menu {
                    Button("Clear chat", systemImage: "trash", role: .destructive) { chat.clear() }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 16, weight: .bold))
                        .frame(width: 36, height: 36)
                        .background(Brand.raised, in: Circle())
                }
                CloseButton { dismiss() }
            }
            .padding(16)

            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 10) {
                        if chat.messages.isEmpty {
                            Text("Say hi to \(store.state.name)! It knows how it feels, what it ate and who its friends are.")
                                .font(Brand.display(14, .medium))
                                .foregroundStyle(Brand.muted)
                                .multilineTextAlignment(.center)
                                .padding(.top, 40)
                                .padding(.horizontal, 30)
                        }
                        ForEach(chat.messages) { message in
                            Bubble(message: message).id(message.id)
                        }
                        if chat.isThinking {
                            HStack {
                                TypingDots()
                                Spacer()
                            }
                            .id("typing")
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 12)
                }
                .scrollDismissesKeyboard(.interactively)
                .onChange(of: chat.messages.count) {
                    if let id = chat.messages.last?.id {
                        withAnimation { proxy.scrollTo(id, anchor: .bottom) }
                    }
                }
            }

            HStack(spacing: 10) {
                TextField("Message \(store.state.name)…", text: $draft, axis: .vertical)
                    .font(Brand.display(16, .medium))
                    .lineLimit(1...4)
                    .focused($typing)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 11)
                    .background(Brand.deep, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .onSubmit(send)
                Button(action: send) {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 16, weight: .black))
                        .foregroundStyle(Brand.void)
                        .frame(width: 42, height: 42)
                        .background(Brand.hypr, in: Circle())
                }
                .disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty || chat.isThinking)
                .accessibilityLabel("Send")
            }
            .padding(12)
        }
        .presentationDetents([.large])
    }

    private func send() {
        let text = draft
        draft = ""
        Task { await chat.send(text) }
    }
}

private struct Bubble: View {
    let message: ChatMessage

    var body: some View {
        switch message.role {
        case .note:
            Text(message.text)
                .font(Brand.mono(11, .semibold))
                .foregroundStyle(Brand.muted)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 4)
        case .me, .slime:
            let mine = message.role == .me
            HStack {
                if mine { Spacer(minLength: 50) }
                Text(message.text)
                    .font(Brand.display(15, .medium))
                    .foregroundStyle(mine ? Brand.void : Brand.text)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(mine ? AnyShapeStyle(Brand.hyprGradient) : AnyShapeStyle(Brand.raised),
                                in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                if !mine { Spacer(minLength: 50) }
            }
        }
    }
}

private struct TypingDots: View {
    var body: some View {
        TimelineView(.animation) { context in
            let t = context.date.timeIntervalSinceReferenceDate
            HStack(spacing: 5) {
                ForEach(0..<3) { i in
                    Circle()
                        .fill(Brand.muted)
                        .frame(width: 7, height: 7)
                        .offset(y: CGFloat(sin(t * 6 + Double(i) * 0.8)) * 3)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
            .background(Brand.raised, in: Capsule())
        }
    }
}
