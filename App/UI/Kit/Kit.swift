import SwiftUI

// The building blocks of Slimy's UI. Standard controls wherever iOS has one
// (lists, bordered buttons, toggles, navigation), so the app picks up each
// iOS version's own look; a few small views for things iOS doesn't have.

/// A grouped card for content that isn't a list.
struct Card<Content: View>: View {
    var title: String? = nil
    var padding: CGFloat = 16
    var fill: Color = Theme.card
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let title {
                Text(title)
                    .font(.headline)
                    .foregroundStyle(Theme.ink)
                    .accessibilityAddTraits(.isHeader)
            }
            content
        }
        .padding(padding)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(fill, in: RoundedRectangle(cornerRadius: Theme.radius, style: .continuous))
    }
}

enum AppButtonKind {
    case primary, secondary, danger, quiet
}

/// A standard bordered button with an optional pixel item or SF Symbol.
/// Full width unless `compact`.
struct AppButton: View {
    let title: String
    var icon: String? = nil
    var systemIcon: String? = nil
    var kind: AppButtonKind = .primary
    var tint: Color? = nil
    var compact = false
    let action: () -> Void

    var body: some View {
        let button = Button {
            Feedback.shared.play(.tap)
            action()
        } label: {
            label
        }
        .controlSize(compact ? .regular : .large)

        switch kind {
        case .primary: button.buttonStyle(.borderedProminent).tint(tint ?? Theme.accent)
        case .secondary: button.buttonStyle(.bordered).tint(tint ?? Theme.accent)
        case .danger: button.buttonStyle(.borderedProminent).tint(Theme.danger)
        case .quiet: button.buttonStyle(.bordered).tint(Theme.ink)
        }
    }

    @ViewBuilder private var label: some View {
        let content = HStack(spacing: 8) {
            if let icon { PixelItem(name: icon).frame(width: 20, height: 20) }
            if let systemIcon { Image(systemName: systemIcon) }
            Text(title).lineLimit(1).minimumScaleFactor(0.8)
        }
        .font(.body.weight(.semibold))
        .frame(maxWidth: compact ? nil : .infinity)
        // A regular-size bezel is ~34 pt; a taller label keeps compact
        // buttons at the 44 pt minimum hit target.
        .frame(minHeight: compact ? 30 : nil)

        if kind == .primary && tint == nil {
            content.foregroundStyle(Theme.onAccent)
        } else {
            content
        }
    }
}

/// A tappable tile: a filled rounded rectangle that dims a little when
/// pressed and fades when disabled.
struct TileStyle: ButtonStyle {
    var fill: Color = Theme.card
    var radius: CGFloat = Theme.radius

    func makeBody(configuration: Configuration) -> some View {
        TileBody(configuration: configuration, fill: fill, radius: radius)
    }

    private struct TileBody: View {
        let configuration: ButtonStyleConfiguration
        let fill: Color
        let radius: CGFloat
        @Environment(\.isEnabled) private var isEnabled

        var body: some View {
            let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
            configuration.label
                .background(fill, in: shape)
                .contentShape(shape)
                .opacity(isEnabled ? (configuration.isPressed ? 0.7 : 1) : 0.45)
                .scaleEffect(configuration.isPressed ? 0.97 : 1)
                .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
        }
    }
}

/// Round icon button floating over the slime's home.
struct HUDButton: View {
    let systemImage: String
    let label: String
    var badge = false
    let action: () -> Void

    var body: some View {
        Button {
            Feedback.shared.play(.tap)
            action()
        } label: {
            Image(systemName: systemImage)
                .font(.body.weight(.semibold))
                .imageScale(.large)
                .foregroundStyle(Theme.ink)
                .frame(width: 44, height: 44)
                .background(.regularMaterial, in: Circle())
                .overlay(alignment: .topTrailing) {
                    if badge {
                        Circle().fill(Theme.love)
                            .frame(width: 11, height: 11)
                            .overlay(Circle().stroke(Theme.card, lineWidth: 2))
                            .offset(x: -1, y: 1)
                    }
                }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityValue(badge ? "New" : "")
    }
}

/// A slim progress bar.
struct Meter: View {
    let value: Double   // 0...1
    var color: Color = Theme.accent
    var height: CGFloat = 6

    var body: some View {
        let v = min(1, max(0, value))
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(color.opacity(0.2))
                Capsule().fill(color)
                    .frame(width: max(height, geo.size.width * v))
                    .opacity(v > 0 ? 1 : 0)
            }
        }
        .frame(height: height)
        .accessibilityElement()
        .accessibilityValue("\(Int((v * 100).rounded())) percent")
    }
}

/// A need as a ring around its pixel icon.
struct NeedRing: View {
    let need: Need
    let value: Double   // 0...100
    var size: CGFloat = 40

    var body: some View {
        let v = min(1, max(0, value / 100))
        let color = value < 25 ? Theme.danger : Theme.color(for: need)
        ZStack {
            Circle().stroke(color.opacity(0.2), lineWidth: 4)
            Circle()
                .trim(from: 0, to: v)
                .stroke(color, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                .rotationEffect(.degrees(-90))
            PixelItem(name: Theme.icon(for: need))
                .frame(width: size * 0.42, height: size * 0.42)
        }
        .frame(width: size, height: size)
        .animation(.easeOut(duration: 0.3), value: v)
    }
}

struct CoinCounter: View {
    let coins: Int
    /// Floating over the slime's home rather than sitting in a card.
    var floating = false
    /// In a navigation bar. From iOS 26 the bar draws glass around its items,
    /// so the counter brings no background of its own there.
    var inToolbar = false

    var body: some View {
        let content = HStack(spacing: 6) {
            PixelItem(name: "coin").frame(width: 16, height: 16)
            Text(coins, format: .number)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Theme.ink)
                .monospacedDigit()
                .contentTransition(.numericText())
        }
        .padding(.horizontal, 12)
        .frame(minHeight: floating ? 44 : 32)
        .fixedSize()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(coins) coins")

        if floating {
            content.background(.regularMaterial, in: Capsule())
        } else if inToolbar {
            toolbarStyle(content)
        } else {
            content.background(Theme.well, in: Capsule())
        }
    }

    @ViewBuilder
    private func toolbarStyle(_ content: some View) -> some View {
        if #available(iOS 26.0, *) {
            content
        } else {
            content.background(Theme.well, in: Capsule())
        }
    }
}

/// What the slime says, in a bubble with a little tail. The slime's own
/// voice is the one place the pixel font appears.
struct SpeechBox: View {
    let text: String

    var body: some View {
        Text(text)
            .font(Theme.voice(16)).fontDesign(nil)
            .foregroundStyle(Theme.ink)
            .multilineTextAlignment(.center)
            .lineLimit(3)
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(alignment: .bottom) {
                BubbleTail().fill(Theme.card).frame(width: 16, height: 8).offset(y: 7)
            }
            .padding(.bottom, 8)
            .accessibilityLabel("Slime says: \(text)")
    }
}

private struct BubbleTail: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: rect.minX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.midX, y: rect.maxY))
        p.closeSubpath()
        return p
    }
}

/// A row of filter chips that scrolls sideways.
struct ChipTabs<T: Hashable>: View {
    let options: [(T, String)]
    @Binding var selection: T

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(options, id: \.0) { value, title in
                    let selected = selection == value
                    Button {
                        Feedback.shared.play(.tap)
                        withAnimation(.snappy(duration: 0.2)) { selection = value }
                    } label: {
                        Text(title)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(selected ? Theme.onAccent : Theme.ink)
                            .padding(.horizontal, 14)
                            .frame(minHeight: 36)
                            .background(selected ? Theme.accent : Theme.well, in: Capsule())
                            // Transparent margin: 44 pt to tap, 36 pt to see.
                            .padding(.vertical, 4)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
            .padding(.vertical, 2)
        }
    }
}

/// Floating confirmation at the top of the screen.
struct ToastBanner: View {
    let toast: Toast

    var body: some View {
        Label {
            Text(toast.text)
        } icon: {
            Image(systemName: toast.good ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
                .foregroundStyle(toast.good ? Theme.accent : Theme.danger)
        }
        .font(.subheadline.weight(.semibold))
        .foregroundStyle(Theme.ink)
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(.regularMaterial, in: Capsule())
        .shadow(color: .black.opacity(0.12), radius: 10, y: 4)
        .accessibilityAddTraits(.isStaticText)
    }
}

/// A pixel item on a soft square, the icon of a list row.
struct ItemIcon: View {
    let name: String
    var size: CGFloat = 36

    var body: some View {
        PixelItem(name: name)
            .padding(size * 0.18)
            .frame(width: size, height: size)
            .background(Theme.well, in: RoundedRectangle(cornerRadius: size * 0.26, style: .continuous))
    }
}

/// A small capsule label like "NEW" or "RARE".
struct Tag: View {
    let text: String
    var color: Color = Theme.accent

    var body: some View {
        Text(text)
            .font(.caption2.weight(.bold))
            .foregroundStyle(color)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(color.opacity(0.15), in: Capsule())
    }
}

private struct DoneButton: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Button("Done") { dismiss() }
    }
}

extension View {
    /// Navigation chrome for the root of a sheet: its title and a Done button.
    func sheetTitle(_ title: String, inline: Bool = false) -> some View {
        navigationTitle(title)
            .navigationBarTitleDisplayMode(inline ? .inline : .large)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { DoneButton() }
            }
    }
}
