import SwiftUI

/// A game window: paper, a dark ink border, a light bevel on the inside and
/// a hard shadow. The one container style used everywhere.
struct PixelWindow<Content: View>: View {
    var title: String? = nil
    var padding: CGFloat = 14
    var fill: Color = Theme.paper
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let title {
                Text(title)
                    .font(Theme.title(18))
                    .foregroundStyle(Theme.ink)
                    .accessibilityAddTraits(.isHeader)
            }
            content
        }
        .padding(padding)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            ZStack {
                NotchedRect().fill(Theme.border).offset(x: Theme.px, y: Theme.px).opacity(0.35)
                NotchedRect().fill(fill)
                NotchedRect(inset: Theme.px).stroke(Theme.bevel, lineWidth: Theme.px * 0.8).opacity(0.7)
                NotchedRect().strokeBorder(Theme.border, lineWidth: Theme.px)
            }
        }
    }
}

enum PixelButtonStyleKind {
    case primary, secondary, danger, quiet
}

/// A chunky key that sinks into its shadow when pressed.
struct PixelButton: View {
    let title: String
    var icon: String? = nil
    var systemIcon: String? = nil
    var kind: PixelButtonStyleKind = .primary
    var tint: Color? = nil
    var compact = false
    let action: () -> Void

    var body: some View {
        Button {
            Feedback.shared.play(.tap)
            action()
        } label: {
            HStack(spacing: 8) {
                if let icon { PixelItem(name: icon).frame(width: 20, height: 20) }
                if let systemIcon { Image(systemName: systemIcon).font(.system(size: 14, weight: .bold)) }
                Text(title).font(Theme.label(compact ? 14 : 17)).lineLimit(1).minimumScaleFactor(0.7)
            }
            .foregroundStyle(foreground)
            .padding(.horizontal, compact ? 10 : 14)
            .frame(maxWidth: compact ? nil : .infinity)
            .frame(minHeight: compact ? 36 : 48)
        }
        .buttonStyle(KeyStyle(fill: background))
    }

    private var background: Color {
        if let tint { return tint }
        switch kind {
        case .primary: return Theme.accent
        case .secondary: return Theme.paper
        case .danger: return Theme.danger
        case .quiet: return Theme.paperShade
        }
    }

    private var foreground: Color {
        switch kind {
        case .primary, .danger: tint == nil ? .white : Theme.ink
        case .secondary, .quiet: Theme.ink
        }
    }
}

/// Button style: notched key with a hard shadow; pressed, it moves down.
struct KeyStyle: ButtonStyle {
    var fill: Color

    func makeBody(configuration: Configuration) -> some View {
        let down = configuration.isPressed
        configuration.label
            .background(NotchedRect().fill(fill))
            .overlay(NotchedRect().strokeBorder(Theme.border, lineWidth: Theme.px))
            .offset(y: down ? Theme.px : 0)
            .background(NotchedRect().fill(Theme.border).offset(y: Theme.px))
            .animation(.easeOut(duration: 0.05), value: down)
    }
}

/// Square icon button for the HUD.
struct HUDButton: View {
    let icon: String
    let label: String
    var badge = false
    let action: () -> Void

    var body: some View {
        Button {
            Feedback.shared.play(.tap)
            action()
        } label: {
            PixelItem(name: icon)
                .frame(width: 24, height: 24)
                .frame(width: 44, height: 44)
                .overlay(alignment: .topTrailing) {
                    if badge {
                        Rectangle().fill(Theme.love).frame(width: 9, height: 9)
                            .overlay(Rectangle().strokeBorder(Theme.border, lineWidth: 2))
                            .offset(x: 2, y: -2)
                    }
                }
        }
        .buttonStyle(KeyStyle(fill: Theme.paper))
        .accessibilityLabel(label)
    }
}

/// A bar made of blocks.
struct BlockBar: View {
    let value: Double   // 0...1
    var color: Color
    var blocks = 10
    var height: CGFloat = 10

    var body: some View {
        let filled = Int((value * Double(blocks)).rounded(.up))
        HStack(spacing: 2) {
            ForEach(0..<blocks, id: \.self) { i in
                Rectangle().fill(i < filled ? color : Theme.border.opacity(0.15))
            }
        }
        .frame(height: height)
        .padding(2)
        .overlay(Rectangle().strokeBorder(Theme.border, lineWidth: 2))
        .accessibilityElement()
        .accessibilityValue("\(Int(value * 100)) Prozent")
    }
}

struct CoinCounter: View {
    let coins: Int

    var body: some View {
        HStack(spacing: 6) {
            PixelItem(name: "coin").frame(width: 18, height: 18)
            Text("\(coins)")
                .font(Theme.label(17))
                .foregroundStyle(Theme.ink)
                .monospacedDigit()
                .contentTransition(.numericText())
        }
        .padding(.horizontal, 10)
        .frame(height: 44)
        .background(NotchedRect().fill(Theme.paper))
        .overlay(NotchedRect().strokeBorder(Theme.border, lineWidth: Theme.px))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(coins) Münzen")
    }
}

/// What the slime says, in a speech box with a little tail.
struct SpeechBox: View {
    let text: String

    var body: some View {
        VStack(spacing: 0) {
            Text(text)
                .font(Theme.label(15))
                .foregroundStyle(Theme.ink)
                .multilineTextAlignment(.center)
                .lineLimit(3)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(NotchedRect().fill(Theme.paper))
                .overlay(NotchedRect().strokeBorder(Theme.border, lineWidth: Theme.px))
            // tail
            VStack(spacing: 0) {
                Rectangle().fill(Theme.border).frame(width: 12, height: 3)
                Rectangle().fill(Theme.border).frame(width: 6, height: 3)
            }
        }
        .accessibilityLabel("Slime sagt: \(text)")
    }
}

/// Small tabs for windows with sections.
struct PixelTabs<T: Hashable>: View {
    let options: [(T, String)]
    @Binding var selection: T

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(options, id: \.0) { value, title in
                    Button {
                        Feedback.shared.play(.tap)
                        selection = value
                    } label: {
                        Text(title)
                            .font(Theme.label(14))
                            .foregroundStyle(selection == value ? Color.white : Theme.ink)
                            .padding(.horizontal, 12)
                            .frame(height: 34)
                    }
                    .buttonStyle(KeyStyle(fill: selection == value ? Theme.accent : Theme.paper))
                    .accessibilityAddTraits(selection == value ? .isSelected : [])
                }
            }
            .padding(.vertical, 4)
            .padding(.horizontal, 2)
        }
    }
}

/// Header for full-screen menus: title and a close key.
struct MenuHeader: View {
    let title: String
    var subtitle: String? = nil
    let close: () -> Void

    var body: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(Theme.title(26)).foregroundStyle(Theme.ink)
                if let subtitle {
                    Text(subtitle).font(Theme.text(14)).foregroundStyle(Theme.inkSoft)
                }
            }
            Spacer()
            Button(action: close) {
                Image(systemName: "xmark")
                    .font(.system(size: 15, weight: .black))
                    .foregroundStyle(Theme.ink)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(KeyStyle(fill: Theme.paper))
            .accessibilityLabel("Schließen")
        }
    }
}

/// Background behind menus.
struct MenuBackdrop: View {
    var body: some View {
        Theme.backdrop
            .overlay {
                // A faint checker of big pixels, like a game's menu backdrop.
                GeometryReader { geo in
                    let size: CGFloat = 24
                    Path { p in
                        var y: CGFloat = 0
                        var row = 0
                        while y < geo.size.height {
                            var x: CGFloat = row % 2 == 0 ? 0 : size
                            while x < geo.size.width {
                                p.addRect(CGRect(x: x, y: y, width: size, height: size))
                                x += size * 2
                            }
                            y += size
                            row += 1
                        }
                    }
                    .fill(Theme.border.opacity(0.04))
                }
            }
            .ignoresSafeArea()
    }
}

struct ToastBanner: View {
    let toast: Toast

    var body: some View {
        Text(toast.text)
            .font(Theme.label(16))
            .foregroundStyle(toast.good ? Theme.ink : Theme.danger)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(NotchedRect().fill(Theme.paper))
            .overlay(NotchedRect().strokeBorder(Theme.border, lineWidth: Theme.px))
            .background(NotchedRect().fill(Theme.border).offset(y: Theme.px))
            .accessibilityAddTraits(.isStaticText)
    }
}
