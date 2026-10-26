import SwiftUI

/// What the player needs to know before talking to the slime: what is sent,
/// who processes it and what is kept. Shown in the consent sheet and in
/// About AI Chat, so both always say the same.
enum ChatDisclosure {
    struct Point: Identifiable {
        let systemImage: String
        let title: String
        let text: String
        var id: String { title }
    }

    static func points(slimeName name: String) -> [Point] {
        [
            Point(systemImage: "text.bubble",
                  title: "What is sent",
                  text: "Your message, the recent chat and a few facts about \(name): its name, age, level, mood, how it feels, its needs and favourite food, and the time of day. Your name and your device ID are never passed on to the AI services."),
            Point(systemImage: "cpu",
                  title: "Who writes the reply",
                  text: "An AI service writes \(name)'s replies in the mood it's in, and the chat can change how it feels. Cloudflare Workers AI and Groq process your message to do that."),
            Point(systemImage: "lock",
                  title: "What is kept",
                  text: "Slimy's server doesn't keep your messages. The chat stays on this iPhone until you clear it. Please don't share personal details."),
            Point(systemImage: "exclamationmark.bubble",
                  title: "Replies can be wrong",
                  text: "AI makes the replies up, so they can be odd or wrong. Touch and hold a reply to report it."),
        ]
    }
}

/// A row with a symbol, a short title and an explanation.
struct ChatInfoRow: View {
    let point: ChatDisclosure.Point

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: point.systemImage)
                .font(.title3)
                .foregroundStyle(Theme.accent)
                .frame(width: 30)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(point.title)
                    .font(.headline)
                    .foregroundStyle(Theme.ink)
                Text(point.text)
                    .font(.subheadline)
                    .foregroundStyle(Theme.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Asked once, before the first message leaves the phone. Agree turns on
/// `ChatStore.aiConsent`; Not Now (or swiping the sheet away) sends nothing.
struct ChatConsentView: View {
    let slime: Slime
    let onAgree: () -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    VStack(spacing: 12) {
                        ChatAvatar(slime: slime, size: 72, fill: Theme.well)
                        Text("Talk with \(slime.name)")
                            .font(.title2.weight(.bold))
                            .foregroundStyle(Theme.ink)
                            .multilineTextAlignment(.center)
                            .accessibilityAddTraits(.isHeader)
                        Text("\(slime.name)'s replies are written by AI. Here's what happens when you send a message.")
                            .font(.subheadline)
                            .foregroundStyle(Theme.inkSoft)
                            .multilineTextAlignment(.center)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity)

                    VStack(alignment: .leading, spacing: 18) {
                        ForEach(ChatDisclosure.points(slimeName: slime.name)) { point in
                            ChatInfoRow(point: point)
                        }
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Link(destination: ChatStore.privacyURL) {
                            Label("Privacy Policy", systemImage: "hand.raised")
                                .font(.body.weight(.semibold))
                                .frame(minHeight: 44)
                        }
                        Text("You can turn this off any time in Settings under Privacy, or under About AI Chat in the chat menu.")
                            .font(.footnote)
                            .foregroundStyle(Theme.inkSoft)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.horizontal, 24)
                .padding(.top, 8)
                .padding(.bottom, 16)
            }
            .scrollBounceBehavior(.basedOnSize)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                VStack(spacing: 4) {
                    AppButton(title: "Agree") {
                        onAgree()
                        dismiss()
                    }
                    Button {
                        dismiss()
                    } label: {
                        Text("Not Now")
                            .font(.body.weight(.semibold))
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(Theme.accent)
                }
                .padding(.horizontal, 24)
                .padding(.top, 12)
                .padding(.bottom, 8)
                .background(.bar)
            }
            .navigationTitle("AI Chat")
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.fraction(0.8), .large])
        .presentationDragIndicator(.visible)
    }
}

/// How AI chat works, the privacy policy, and the switch to stop it.
struct ChatAIInfoView: View {
    @EnvironmentObject private var chat: ChatStore
    @EnvironmentObject private var store: GameStore
    @State private var confirmTurnOff = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(ChatDisclosure.points(slimeName: store.state.slime.name)) { point in
                        ChatInfoRow(point: point)
                            .padding(.vertical, 4)
                    }
                } header: {
                    Text("How it works")
                }

                Section {
                    Label {
                        Text(chat.aiConsent ? "AI chat is on" : "AI chat is off")
                            .foregroundStyle(Theme.ink)
                    } icon: {
                        Image(systemName: chat.aiConsent ? "checkmark.circle.fill" : "pause.circle")
                            .foregroundStyle(chat.aiConsent ? Theme.accent : Theme.inkSoft)
                    }
                    if chat.aiConsent {
                        Button(role: .destructive) {
                            confirmTurnOff = true
                        } label: {
                            Text("Turn Off AI Chat")
                        }
                    }
                } header: {
                    Text("Your choice")
                } footer: {
                    Text(chat.aiConsent
                         ? "Turning it off stops sending messages to the AI service. You'll be asked again before your next message."
                         : "You'll be asked before your next message is sent.")
                }

                Section {
                    Link(destination: ChatStore.privacyURL) {
                        Label("Privacy Policy", systemImage: "hand.raised")
                    }
                } footer: {
                    Text("Reporting a reply sends only its text to the Slimy team, so we can make the slime's answers better.")
                }
            }
            .listStyle(.insetGrouped)
            .sheetTitle("About AI Chat", inline: true)
            .confirmationDialog("Turn off AI chat?", isPresented: $confirmTurnOff, titleVisibility: .visible) {
                Button("Turn Off", role: .destructive) { chat.revokeConsent() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Your chat stays on this iPhone. Nothing more is sent until you agree again.")
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}
