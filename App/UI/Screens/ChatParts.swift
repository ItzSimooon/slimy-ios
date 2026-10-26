import SwiftUI

// The pieces of the chat screen: bubbles, notes, the typing dots, the input
// bar and the card that asks you to go online first.

/// The slime's face next to its messages: a still pixel portrait in a circle.
struct ChatAvatar: View {
    let slime: Slime
    var size: CGFloat = 30
    var fill: Color = Theme.card

    var body: some View {
        let anim = SpriteAnim(slime.mood)
        let frames = SlimeSprites.frames(slime.stage, anim)
        let anchors = SlimeSprites.anchors(slime.stage, anim)
        PixelSlime(frame: frames.first ?? SlimeSprites.blank, skin: slime.skin, accessories: slime.equipped,
                   stage: slime.stage, anchor: anchors.first)
            .padding(size * 0.12)
            .frame(width: size, height: size)
            .background(fill, in: Circle())
            .accessibilityHidden(true)
    }
}

/// How the chat puts the slime's feelings into words: the header says what
/// the home screen says (`SlimeMoodWord`), and each reply carries the
/// feeling it expressed.
enum ChatMood {
    /// From this intensity on, a feeling takes its stronger word.
    private static let strong = 0.55

    /// One feeling as a word that follows "feeling": "content" when mild,
    /// "cheerful" when strong. `SlimeMoodWord` only words a whole slime, so
    /// this mirrors its per-feeling words until it can word a single feeling.
    static func word(_ emotion: Emotion, intensity: Double) -> String {
        let isStrong = intensity >= strong
        switch emotion {
        case .joy: return isStrong ? "cheerful" : "content"
        case .sadness: return isStrong ? "sad" : "glum"
        case .anger: return isStrong ? "cross" : "grumpy"
        case .fear: return isStrong ? "scared" : "nervous"
        case .love: return isStrong ? "cuddly" : "fond"
        case .curiosity: return isStrong ? "fascinated" : "curious"
        case .boredom: return isStrong ? "bored" : "a bit bored"
        case .excitement: return isStrong ? "excited" : "eager"
        case .sleepiness: return isStrong ? "sleepy" : "drowsy"
        }
    }

    /// A small touch of colour next to the word.
    static func tint(_ emotion: Emotion) -> Color {
        switch emotion {
        case .joy, .excitement: Theme.gold
        case .love: Theme.love
        case .sadness, .fear: Theme.sky
        case .anger: Theme.danger
        case .curiosity: Theme.accent
        case .boredom, .sleepiness: Theme.inkSoft
        }
    }

    /// The line under the slime's name in the chat header: the same word
    /// the home screen shows under its name.
    static func status(of slime: Slime) -> String {
        slime.isAsleep ? "Asleep" : "Feeling \(SlimeMoodWord.word(for: slime))"
    }

    /// The word under a reply. Replies saved before the server sent an
    /// intensity count as mild.
    static func word(for message: ChatMessage) -> String? {
        guard let emotion = message.emotion else { return nil }
        return word(emotion, intensity: message.intensity ?? 0.5)
    }
}

/// The feeling a reply expressed, quietly under its bubble: one pixel in
/// the feeling's colour and its word.
struct ChatMoodLabel: View {
    let emotion: Emotion
    let word: String

    @ScaledMetric(relativeTo: .caption2) private var pixel: CGFloat = 6

    var body: some View {
        HStack(spacing: 5) {
            Rectangle()
                .fill(ChatMood.tint(emotion))
                .frame(width: pixel, height: pixel)
            Text(word)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(Theme.inkSoft)
        }
        .accessibilityHidden(true)
    }
}

private extension VerticalAlignment {
    /// The bottom of a slime bubble, below which its mood line may follow.
    enum ChatBubbleBottom: AlignmentID {
        static func defaultValue(in d: ViewDimensions) -> CGFloat { d[.bottom] }
    }

    static let chatBubbleBottom = VerticalAlignment(ChatBubbleBottom.self)
}

/// A message bubble. On the last message of a run, the corner nearest the
/// sender is tighter, like a small tail.
struct ChatBubbleShape: Shape {
    let mine: Bool
    let tail: Bool

    func path(in rect: CGRect) -> Path {
        let radius = min(20, rect.height / 2)
        let tight: CGFloat = 6
        return UnevenRoundedRectangle(
            topLeadingRadius: radius,
            bottomLeadingRadius: !mine && tail ? tight : radius,
            bottomTrailingRadius: mine && tail ? tight : radius,
            topTrailingRadius: radius,
            style: .continuous
        ).path(in: rect)
    }
}

/// One message from you or from the slime. Slime replies sit on the left
/// next to its portrait; yours sit on the right in the accent colour.
/// Bubbles use the system font so long replies stay easy to read.
struct ChatBubbleRow: View {
    let message: ChatMessage
    let slime: Slime
    /// Last message of a run from the same sender.
    let endsRun: Bool
    let onCopy: () -> Void
    let onReport: () -> Void

    @ScaledMetric(relativeTo: .body) private var avatarSize: CGFloat = 30

    var body: some View {
        if message.role == .me {
            mine
        } else {
            theirs
        }
    }

    private var mine: some View {
        HStack(alignment: .bottom, spacing: 6) {
            Spacer(minLength: 52)
            if message.failed {
                Image(systemName: "exclamationmark.circle.fill")
                    .font(.body)
                    .foregroundStyle(Theme.danger)
                    .padding(.bottom, 8)
                    .accessibilityHidden(true)
            }
            bubble(fill: Theme.accent, text: Theme.onAccent, mine: true)
                .contextMenu {
                    Button(action: onCopy) { Label("Copy", systemImage: "doc.on.doc") }
                }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("You: \(message.text)")
        .accessibilityValue(message.failed ? "Not delivered" : "")
        .accessibilityAction(named: "Copy", onCopy)
    }

    private var theirs: some View {
        // The portrait sits by the bubble's tail, not by the line under it.
        HStack(alignment: .chatBubbleBottom, spacing: 8) {
            Group {
                if endsRun {
                    ChatAvatar(slime: slime, size: avatarSize)
                } else {
                    Color.clear.frame(width: avatarSize, height: 1)
                }
            }
            VStack(alignment: .leading, spacing: 3) {
                bubble(fill: Theme.card, text: Theme.ink, mine: false)
                    .contextMenu {
                        Button(action: onCopy) { Label("Copy", systemImage: "doc.on.doc") }
                        if message.reported {
                            Button {} label: { Label("Reported", systemImage: "checkmark") }
                                .disabled(true)
                        } else {
                            Button(action: onReport) { Label("Report Reply", systemImage: "exclamationmark.bubble") }
                        }
                    }
                    .alignmentGuide(.chatBubbleBottom) { $0[.bottom] }
                if message.emotion != nil || message.reported {
                    HStack(spacing: 8) {
                        if let emotion = message.emotion, let word = ChatMood.word(for: message) {
                            ChatMoodLabel(emotion: emotion, word: word)
                        }
                        if message.reported {
                            Text("Reported")
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(Theme.inkSoft)
                        }
                    }
                    .padding(.leading, 8)
                }
            }
            Spacer(minLength: 44)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(slime.name): \(message.text)")
        .accessibilityValue(accessibilityDetails)
        .accessibilityAction(named: "Copy", onCopy)
        .accessibilityAction(named: "Report reply") {
            if !message.reported { onReport() }
        }
    }

    /// What VoiceOver reads after a reply: how the slime felt saying it,
    /// and whether you reported it.
    private var accessibilityDetails: String {
        var details: [String] = []
        if let word = ChatMood.word(for: message) { details.append("Feeling \(word)") }
        if message.reported { details.append("Reported") }
        return details.joined(separator: ", ")
    }

    private func bubble(fill: Color, text: Color, mine: Bool) -> some View {
        let shape = ChatBubbleShape(mine: mine, tail: endsRun)
        return Text(message.text)
            .font(.body)
            .foregroundStyle(text)
            .multilineTextAlignment(.leading)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
            .background(fill, in: shape)
            .opacity(message.failed ? 0.6 : 1)
            .contentShape(.contextMenuPreview, shape)
    }
}

/// A line from the app between the bubbles, for example an error with a
/// Try Again button.
struct ChatNoteRow: View {
    let message: ChatMessage
    let canRetry: Bool
    let onRetry: () -> Void

    var body: some View {
        VStack(spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                if message.failed {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(Theme.danger)
                        .accessibilityHidden(true)
                }
                Text(message.text)
                    .foregroundStyle(Theme.inkSoft)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .font(.footnote)

            if message.retryID != nil {
                Button(action: onRetry) {
                    Label("Try Again", systemImage: "arrow.clockwise")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(canRetry ? Theme.accent : Theme.inkSoft)
                        .padding(.horizontal, 16)
                        .frame(minHeight: 34)
                        .background(Theme.accent.opacity(canRetry ? 0.14 : 0.06), in: Capsule())
                        .padding(.vertical, 5)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(!canRetry)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 28)
    }
}

/// Three dots in a slime bubble while the reply is on its way. With Reduce
/// Motion the dots hold still.
struct ChatTypingIndicator: View {
    let slime: Slime

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ScaledMetric(relativeTo: .body) private var avatarSize: CGFloat = 30
    @ScaledMetric(relativeTo: .body) private var dot: CGFloat = 8

    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            ChatAvatar(slime: slime, size: avatarSize)
            TimelineView(.animation(minimumInterval: 1 / 30, paused: reduceMotion)) { context in
                let t = context.date.timeIntervalSinceReferenceDate
                HStack(spacing: dot * 0.6) {
                    ForEach(0..<3, id: \.self) { i in
                        let wave: Double = reduceMotion ? 0.5 : (sin(t * 6 - Double(i) * 0.9) + 1) / 2
                        Circle()
                            .fill(Theme.inkSoft)
                            .frame(width: dot, height: dot)
                            .opacity(0.35 + 0.65 * wave)
                            .offset(y: reduceMotion ? 0 : CGFloat(-3 * wave))
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 15)
                .background(Theme.card, in: ChatBubbleShape(mine: false, tail: true))
            }
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(slime.name) is typing")
    }
}

/// The field and the send button, pinned to the bottom of the chat.
struct ChatInputBar: View {
    @Binding var text: String
    var focused: FocusState<Bool>.Binding
    let canSend: Bool
    let onSend: () -> Void

    @ScaledMetric(relativeTo: .body) private var buttonSize: CGFloat = 34

    private var hasText: Bool {
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        let enabled = canSend && hasText
        VStack(alignment: .trailing, spacing: 4) {
            HStack(alignment: .bottom, spacing: 6) {
                TextField("Message", text: $text, axis: .vertical)
                    .font(.body)
                    .lineLimit(1...4)
                    .focused(focused)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .frame(minHeight: buttonSize + 6)
                    .background(Theme.well, in: RoundedRectangle(cornerRadius: (buttonSize + 6) / 2, style: .continuous))

                Button(action: onSend) {
                    Image(systemName: "arrow.up")
                        .font(.body.weight(.bold))
                        .foregroundStyle(enabled ? Theme.onAccent : Theme.inkSoft)
                        .frame(width: buttonSize, height: buttonSize)
                        .background(enabled ? Theme.accent : Theme.well, in: Circle())
                        // Centred on a one-line field, 44 pt to tap.
                        .padding(.bottom, 3)
                        .frame(width: max(44, buttonSize + 6), height: max(44, buttonSize + 6), alignment: .bottom)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(!enabled)
                .accessibilityLabel("Send")
            }

            if text.count > ChatStore.maxLength - 60 {
                Text("\(text.count) of \(ChatStore.maxLength)")
                    .font(.caption2.weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(text.count >= ChatStore.maxLength ? Theme.danger : Theme.inkSoft)
                    .padding(.trailing, 52)
            }
        }
        .padding(.leading, 12)
        .padding(.trailing, 6)
        .padding(.vertical, 6)
        .background(.bar)
        .overlay(alignment: .top) { Divider() }
        .onChange(of: text) { _, new in
            if new.count > ChatStore.maxLength { text = String(new.prefix(ChatStore.maxLength)) }
        }
    }
}

/// Instead of the input bar while the app is offline: talking needs the
/// server, so it asks you to go online first.
struct ChatOfflineCard: View {
    let onGoOnline: () -> Void

    var body: some View {
        Card {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .font(.title3)
                    .foregroundStyle(Theme.accent)
                    .frame(width: 28)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Go online to talk")
                        .font(.headline)
                        .foregroundStyle(Theme.ink)
                    Text("An AI service writes your slime's replies through Slimy's server, so talking works only while you're online.")
                        .font(.subheadline)
                        .foregroundStyle(Theme.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .accessibilityElement(children: .combine)
            AppButton(title: "Go Online", systemIcon: "antenna.radiowaves.left.and.right", action: onGoOnline)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(.bar)
        .overlay(alignment: .top) { Divider() }
    }
}

/// Things to say when the chat is empty. Tapping one sends it.
struct ChatSuggestions: View {
    static let all = ["How are you?", "What did you eat?", "Tell me a secret"]

    let enabled: Bool
    let onPick: (String) -> Void

    var body: some View {
        // In a row when they fit; stacked (and free to wrap) at large text sizes.
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 8) { chips }
            VStack(spacing: 8) { chips }
        }
    }

    private var chips: some View {
        ForEach(Self.all, id: \.self) { suggestion in
            Button {
                onPick(suggestion)
            } label: {
                Text(suggestion)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.accent)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .frame(minHeight: 44)
            }
            .buttonStyle(TileStyle(fill: Theme.card, radius: 22))
            .disabled(!enabled)
            .accessibilityHint("Sends this message")
        }
    }
}

enum ChatTimestamp {
    /// "Today 14:32", "Yesterday 09:10", or the date for older messages.
    static func text(for date: Date, now: Date = .now) -> String {
        let calendar = Calendar.current
        let time = date.formatted(date: .omitted, time: .shortened)
        if calendar.isDate(date, inSameDayAs: now) { return "Today \(time)" }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: now),
           calendar.isDate(date, inSameDayAs: yesterday) { return "Yesterday \(time)" }
        return date.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated).hour().minute())
    }
}
