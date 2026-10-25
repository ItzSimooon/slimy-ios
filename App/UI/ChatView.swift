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
                        .font(Brand.pixel(20))
                        .foregroundStyle(Brand.text)
                    Eyebrow(text: chat.isThinking ? "schreibt …" : "red mit deinem slime", color: Brand.slime)
                }
                Spacer()
                Menu {
                    Button("Chat löschen", systemImage: "trash", role: .destructive) { chat.clear() }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(Brand.text)
                        .frame(width: 36, height: 36)
                        .background(Brand.raised)
                        .overlay(Rectangle().strokeBorder(Brand.line, lineWidth: 2))
                }
                CloseButton { dismiss() }
            }
            .padding(16)

            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 10) {
                        if chat.messages.isEmpty {
                            Text("Sag hallo zu \(store.state.name). Er weiß, wie es ihm geht, was er gegessen hat und wer seine Freunde sind.")
                                .font(Brand.body(15))
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
                TextField("Nachricht an \(store.state.name)", text: $draft, axis: .vertical)
                    .font(Brand.body(16))
                    .lineLimit(1...4)
                    .focused($typing)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 11)
                    .background(Brand.panel)
                    .overlay(Rectangle().strokeBorder(Brand.line, lineWidth: 2))
                    .onSubmit(send)
                Button(action: send) {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 16, weight: .black))
                        .foregroundStyle(Brand.bg)
                        .frame(width: 44, height: 44)
                        .background(Brand.slime)
                        .overlay(Rectangle().strokeBorder(Color.black.opacity(0.55), lineWidth: 2))
                }
                .disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty || chat.isThinking)
                .accessibilityLabel("Senden")
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
                .font(Brand.pixel(12, bold: false))
                .foregroundStyle(Brand.muted)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 4)
        case .me, .slime:
            let mine = message.role == .me
            HStack {
                if mine { Spacer(minLength: 50) }
                Text(message.text)
                    .font(Brand.body(15, .medium))
                    .foregroundStyle(mine ? Brand.bg : Brand.text)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 9)
                    .background(mine ? Brand.slime : Brand.raised)
                    .overlay(Rectangle().strokeBorder(mine ? Color.black.opacity(0.4) : Brand.line, lineWidth: 2))
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
                    Rectangle()
                        .fill(Brand.muted)
                        .frame(width: 6, height: 6)
                        .offset(y: sin(t * 6 + Double(i) * 0.8) > 0 ? -3 : 0)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
            .background(Brand.raised)
            .overlay(Rectangle().strokeBorder(Brand.line, lineWidth: 2))
        }
    }
}
