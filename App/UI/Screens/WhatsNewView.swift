import SwiftUI

/// The highlights of a release, shown once to people who had Slimy before
/// it (RootView presents it; someone who just installed sees onboarding
/// instead). For a release worth telling about, set `version` to its
/// marketing version without the patch number and replace the items.
enum WhatsNew {
    struct Item: Identifiable {
        let symbol: String
        let title: String
        let detail: String
        var id: String { title }
    }

    static let version = "1.1"

    static let items: [Item] = [
        Item(symbol: "shippingbox.fill", title: "Crates with a secret",
             detail: "Earn crates or buy them with coins, then tap them open. The drop rates are listed in the app, and one legendary reward is a secret."),
        Item(symbol: "person.2.fill", title: "Care Together",
             detail: "Share your slime with one other person. Every meal, game, bath and cuddle shows up on both iPhones."),
        Item(symbol: "bubble.left.fill", title: "A chattier slime",
             detail: "It says what's on its mind every few seconds while you watch. You can turn this off in Settings."),
        Item(symbol: "face.smiling", title: "Real feelings",
             detail: "Your slime can be cheerful, curious, grumpy, sleepy and more. You see it in its mood, in how it moves and in what it says."),
        Item(symbol: "bubble.left.and.bubble.right.fill", title: "Chats with feeling",
             detail: "When you talk with your slime, it answers in its mood, and your words can cheer it up."),
        Item(symbol: "figure.2.arms.open", title: "Friends, simpler",
             detail: "The love meter and kisses are gone. Hugs, visits, gifts, greetings, challenges and the shared garden stay."),
    ]

    private static let seenKey = "slimy.whatsNew.seen"

    /// True when this build's notes haven't been shown yet. Patch releases
    /// (1.0.1) share the notes of their release (1.0).
    static var isDue: Bool {
        guard appVersion == version else { return false }
        return UserDefaults.standard.string(forKey: seenKey) != version
    }

    static func markSeen() {
        UserDefaults.standard.set(version, forKey: seenKey)
    }

    /// The marketing version without the patch number: "1.0" for 1.0.2.
    private static var appVersion: String {
        let full = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        return full.split(separator: ".").prefix(2).joined(separator: ".")
    }
}

/// What's New, as a sheet.
struct WhatsNewView: View {
    @Environment(\.dismiss) private var dismiss
    @ScaledMetric(relativeTo: .title2) private var iconWidth: CGFloat = 40

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 28) {
                    VStack(spacing: 8) {
                        LiveSlimeView(height: 96, interactive: false)
                            .accessibilityHidden(true)
                        Text("Slimy \(WhatsNew.version)")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Theme.inkSoft)
                    }

                    VStack(alignment: .leading, spacing: 22) {
                        ForEach(WhatsNew.items) { item in
                            row(item)
                        }
                    }
                }
                .padding(.horizontal, 24)
                .padding(.top, 4)
                .padding(.bottom, 24)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                AppButton(title: "Continue") { dismiss() }
                    .padding(.horizontal, 24)
                    .padding(.top, 12)
                    .padding(.bottom, 8)
                    .frame(maxWidth: 560)
                    .frame(maxWidth: .infinity)
                    .background(.bar)
            }
            .sheetTitle("What's New")
        }
    }

    private func row(_ item: WhatsNew.Item) -> some View {
        HStack(alignment: .top, spacing: 16) {
            Image(systemName: item.symbol)
                .font(.title2)
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(Theme.accent)
                .frame(width: iconWidth)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(item.title)
                    .font(.headline)
                    .foregroundStyle(Theme.ink)
                Text(item.detail)
                    .font(.subheadline)
                    .foregroundStyle(Theme.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}
