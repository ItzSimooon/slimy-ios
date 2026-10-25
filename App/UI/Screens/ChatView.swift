import SwiftUI

struct ChatView: View {
    @EnvironmentObject private var chat: ChatStore
    @EnvironmentObject private var store: GameStore
    @Environment(\.dismiss) private var dismiss
    @State private var draft = ""
    @FocusState private var typing: Bool

    var body: some View {
        ZStack {
            MenuBackdrop()
            VStack(spacing: 10) {
                MenuHeader(title: "Reden", subtitle: chat.isThinking ? "\(store.state.slime.name) schreibt …" : "mit \(store.state.slime.name)") {
                    dismiss()
                }
                .padding(.horizontal, 16)
                .padding(.top, 16)

                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 10) {
                            if chat.messages.isEmpty {
                                PixelWindow {
                                    HStack(spacing: 12) {
                                        LiveSlimeView(height: 50, interactive: false)
                                        Text("Sag hallo. \(store.state.slime.name) weiß, wie es ihm geht, was er gegessen hat und wer seine Freunde sind.")
                                            .font(Theme.text(14)).foregroundStyle(Theme.ink)
                                    }
                                }
                            }
                            ForEach(chat.messages) { message in
                                MessageRow(message: message).id(message.id)
                            }
                            if chat.isThinking {
                                HStack { Text("…").font(Theme.title(20)).foregroundStyle(Theme.inkSoft); Spacer() }
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
                    TextField("Nachricht", text: $draft, axis: .vertical)
                        .font(Theme.text(16))
                        .foregroundStyle(Theme.ink)
                        .lineLimit(1...4)
                        .focused($typing)
                        .padding(10)
                        .background(NotchedRect().fill(Theme.paper))
                        .overlay(NotchedRect().strokeBorder(Theme.border, lineWidth: Theme.px))
                        .onSubmit(send)
                    PixelButton(title: "Senden", compact: true, action: send)
                        .disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty || chat.isThinking)
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 12)
            }
        }
        .presentationDetents([.large])
    }

    private func send() {
        let text = draft
        draft = ""
        Task { await chat.send(text) }
    }
}

private struct MessageRow: View {
    let message: ChatMessage

    var body: some View {
        switch message.role {
        case .note:
            Text(message.text)
                .font(Theme.text(12))
                .foregroundStyle(Theme.inkSoft)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
        case .me, .slime:
            let mine = message.role == .me
            HStack {
                if mine { Spacer(minLength: 50) }
                Text(message.text)
                    .font(mine ? Theme.text(15) : Theme.label(15))
                    .foregroundStyle(mine ? Color.white : Theme.ink)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 9)
                    .background(NotchedRect().fill(mine ? Theme.accent : Theme.paper))
                    .overlay(NotchedRect().strokeBorder(Theme.border, lineWidth: 2))
                if !mine { Spacer(minLength: 50) }
            }
        }
    }
}
