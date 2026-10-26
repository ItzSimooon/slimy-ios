import SwiftUI
import UIKit

/// The crate shelf: the crates waiting to be opened, crates for sale for
/// coins, and the drop rates.
struct CrateShelfView: View {
    @EnvironmentObject private var store: GameStore

    @State private var opening: CrateKind?
    @State private var buying: CrateKind?

    var body: some View {
        let s = store.state
        let owned = CrateKind.allCases.filter { s.crates[$0, default: 0] > 0 }
        let forSale = CrateKind.allCases.filter { $0.price != nil }
        NavigationStack {
            List {
                Section {
                    if owned.isEmpty {
                        ContentUnavailableView {
                            Label("No crates yet", systemImage: "shippingbox")
                        } description: {
                            Text("Crates come with the seventh daily gift, every fifth level, now and then after a great arcade round, and with some achievements. You can also buy one with coins below.")
                        }
                    } else {
                        ForEach(owned) { kind in
                            OwnedCrateRow(kind: kind, count: s.crates[kind, default: 0]) {
                                opening = kind
                            }
                        }
                    }
                } header: {
                    Text("Your crates")
                }

                Section {
                    ForEach(forSale) { kind in
                        ShelfBuyRow(kind: kind, coins: s.wallet.coins, owned: s.crates[kind, default: 0]) {
                            buying = kind
                        }
                    }
                } header: {
                    Text("Buy with coins")
                } footer: {
                    Text(CrateCopy.noRealMoney)
                }

                Section {
                    NavigationLink {
                        DropRatesView()
                    } label: {
                        Label("Drop rates", systemImage: "percent")
                    }
                } footer: {
                    Text(CrateCopy.mysteryHint)
                }
            }
            .listStyle(.insetGrouped)
            .sheetTitle("Crates")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    CoinCounter(coins: s.wallet.coins, inToolbar: true)
                }
            }
            .confirmationDialog(buying.map { "Buy a \(CrateCopy.name($0).lowercased())?" } ?? "",
                                isPresented: Binding(get: { buying != nil }, set: { if !$0 { buying = nil } }),
                                titleVisibility: .visible,
                                presenting: buying) { kind in
                if let price = kind.price {
                    Button("Buy for \(CrateCopy.coins(price))") { buy(kind) }
                }
                Button("Cancel", role: .cancel) {}
            } message: { _ in
                Text("What's inside is a surprise until you open it.")
            }
        }
        .fullScreenCover(item: $opening) { kind in
            CrateOpenView(kind: kind) { opening = nil }
        }
    }

    /// The store plays the coin sound, or its "nope" when it can't be bought.
    private func buy(_ kind: CrateKind) {
        guard store.buyCrate(kind) else { return }
        UIAccessibility.post(notification: .announcement,
                             argument: "Bought a \(CrateCopy.name(kind).lowercased()). It's on the shelf.")
    }
}

/// A crate on the shelf, how many there are, and Open.
private struct OwnedCrateRow: View {
    let kind: CrateKind
    let count: Int
    let open: () -> Void

    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 10))
            : AnyLayout(HStackLayout(spacing: 12))
        layout {
            HStack(spacing: 12) {
                ShelfCrateIcon(kind: kind)
                VStack(alignment: .leading, spacing: 2) {
                    Text(CrateCopy.name(kind))
                        .font(.body.weight(.semibold))
                        .foregroundStyle(Theme.ink)
                    Text(count == 1 ? "1 to open" : "\(count) to open")
                        .font(.subheadline)
                        .foregroundStyle(Theme.inkSoft)
                        .monospacedDigit()
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(CrateCopy.name(kind))
            .accessibilityValue(count == 1 ? "1 to open" : "\(count) to open")
            if !typeSize.isAccessibilitySize { Spacer(minLength: 8) }
            AppButton(title: "Open", systemIcon: "shippingbox", compact: true, action: open)
                .accessibilityLabel("Open \(CrateCopy.name(kind).lowercased())")
        }
        .padding(.vertical, 4)
    }
}

/// A crate for sale: price, and Buy (asks first).
private struct ShelfBuyRow: View {
    let kind: CrateKind
    let coins: Int
    /// How many of this kind are already waiting.
    let owned: Int
    let buy: () -> Void

    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let price = kind.price ?? 0
        let missing = price - coins
        let full = owned >= Crates.maxStack
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 10))
            : AnyLayout(HStackLayout(spacing: 12))
        layout {
            HStack(spacing: 12) {
                ShelfCrateIcon(kind: kind)
                VStack(alignment: .leading, spacing: 2) {
                    Text(CrateCopy.name(kind))
                        .font(.body.weight(.semibold))
                        .foregroundStyle(Theme.ink)
                    Text(kind.blurb)
                        .font(.footnote)
                        .foregroundStyle(Theme.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                    HStack(spacing: 4) {
                        PixelItem(name: "coin").frame(width: 14, height: 14)
                        Text(price, format: .number)
                            .font(.subheadline.weight(.semibold))
                            .monospacedDigit()
                            .foregroundStyle(missing > 0 ? Theme.inkSoft : Theme.ink)
                    }
                    if missing > 0 {
                        Text(CrateCopy.needMore(missing))
                            .font(.footnote)
                            .foregroundStyle(Theme.danger)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if full {
                        Text(CrateCopy.shelfFull)
                            .font(.footnote)
                            .foregroundStyle(Theme.inkSoft)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(CrateCopy.name(kind))
            .accessibilityValue(spoken(price: price, missing: missing, full: full))
            if !typeSize.isAccessibilitySize { Spacer(minLength: 8) }
            AppButton(title: "Buy", icon: "coin", kind: .secondary, compact: true, action: buy)
                .disabled(missing > 0 || full)
                .accessibilityLabel("Buy \(CrateCopy.name(kind).lowercased()) for \(CrateCopy.coins(price))")
        }
        .padding(.vertical, 4)
    }

    private func spoken(price: Int, missing: Int, full: Bool) -> String {
        var text = missing > 0 ? "\(CrateCopy.coins(price)), not enough coins. \(kind.blurb)"
                               : "\(CrateCopy.coins(price)). \(kind.blurb)"
        if full { text += " \(CrateCopy.shelfFull)" }
        return text
    }
}

/// A crate on a soft square, the icon of a shelf row.
private struct ShelfCrateIcon: View {
    let kind: CrateKind

    var body: some View {
        CrateSprite(kind: kind)
            .padding(7)
            .frame(width: 48, height: 48)
            .background(Theme.well, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}
