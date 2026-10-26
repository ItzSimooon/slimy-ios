import SwiftUI
import UIKit

/// Feeding: every food, what it costs and how much it fills. Tapping a food
/// feeds it right away and the sheet stays open for another bite.
struct KitchenView: View {
    @EnvironmentObject private var store: GameStore

    @State private var line: String?
    @State private var lineID = 0
    @ScaledMetric(relativeTo: .body) private var tileWidth: CGFloat = 104

    var body: some View {
        let s = store.state
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    header(s)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: tileWidth), spacing: 12)], spacing: 12) {
                        ForEach(Food.allCases) { food in
                            FoodTile(food: food,
                                     locked: s.slime.level < food.unlockLevel,
                                     affordable: s.wallet.coins >= food.price,
                                     tasted: s.inventory.foodsTasted.contains(food),
                                     favorite: s.inventory.favoriteFound && food == s.slime.favorite) {
                                feed(food)
                            }
                        }
                    }
                    footer(s)
                }
                .padding(16)
            }
            .background(Theme.backdrop)
            .sheetTitle("Kitchen")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    CoinCounter(coins: s.wallet.coins, inToolbar: true)
                }
            }
        }
        .onAppear { store.tick() }
        .task(id: lineID) {
            guard line != nil else { return }
            try? await Task.sleep(for: .seconds(4))
            guard !Task.isCancelled else { return }
            line = nil
        }
    }

    // MARK: Header

    private func header(_ s: GameState) -> some View {
        let hunger = s.slime.needs.hunger
        let full = hunger >= 97
        let percent = Int(hunger.rounded())
        return Card {
            SlimeShowcase(habitat: s.inventory.habitat, furniture: s.inventory.furniture,
                          line: line ?? s.line(at: .now), height: 168, slimeHeight: 78)

            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 8) {
                    PixelItem(name: Theme.icon(for: .hunger))
                        .frame(width: 18, height: 18)
                    Text(Need.hunger.title)
                        .font(.headline)
                        .foregroundStyle(Theme.ink)
                    if full { Tag(text: "FULL") }
                    Spacer(minLength: 8)
                    Text(verbatim: "\(percent)%")
                        .font(.headline)
                        .monospacedDigit()
                        .foregroundStyle(Theme.inkSoft)
                }
                Meter(value: hunger / 100, color: hunger < 25 ? Theme.danger : Theme.color(for: .hunger), height: 8)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Need.hunger.title)
            .accessibilityValue(full ? "\(percent) percent, full" : "\(percent) percent")

            HStack(alignment: .top, spacing: 10) {
                if s.inventory.favoriteFound {
                    PixelItem(name: "heart")
                        .frame(width: 18, height: 15)
                        .padding(.top, 2)
                    Text("\(s.slime.name) loves \(s.slime.favorite.title.lowercased()). It fills more and gives extra love.")
                } else {
                    Image(systemName: "questionmark.circle")
                        .foregroundStyle(Theme.inkSoft)
                        .accessibilityHidden(true)
                    Text("\(s.slime.name) has a favourite food. Try new things to find it.")
                }
            }
            .font(.subheadline)
            .foregroundStyle(Theme.inkSoft)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityElement(children: .combine)
        }
    }

    // MARK: Footer

    private func footer(_ s: GameState) -> some View {
        let tried = s.inventory.foodsTasted.count
        let total = Food.allCases.count
        let hint: String = tried >= total
            ? "Every food tried. A true gourmet."
            : "Try every food once for the Gourmet achievement. New foods unlock as \(s.slime.name) levels up."
        return Card {
            HStack {
                Text("Tried \(tried) of \(total)")
                    .font(.headline)
                    .foregroundStyle(Theme.ink)
                Spacer()
            }
            Meter(value: Double(tried) / Double(max(1, total)))
            Text(hint)
                .font(.footnote)
                .foregroundStyle(Theme.inkSoft)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }

    // MARK: Feeding

    private func feed(_ food: Food) {
        // GameStore plays the eating sound and its haptic, or a soft "nope".
        // The line shows in the speech bubble above instead of the app-wide
        // toast, which would cover the sheet's title bar, so VoiceOver hears
        // it from here.
        var said = ""
        store.apply({ s in
            var result = s.feed(food, now: .now)
            said = result.line
            result.line = ""
            return result
        }, sound: .eat)
        guard !said.isEmpty else { return }
        line = said
        lineID += 1
        UIAccessibility.post(notification: .announcement, argument: said)
    }
}

private struct FoodTile: View {
    let food: Food
    let locked: Bool
    let affordable: Bool
    let tasted: Bool
    let favorite: Bool
    let action: () -> Void

    @ScaledMetric(relativeTo: .body) private var iconSize: CGFloat = 56

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                ItemIcon(name: food.sprite, size: iconSize)
                    .saturation(locked ? 0 : 1)
                    .overlay(alignment: .topTrailing) {
                        if favorite {
                            PixelItem(name: "heart")
                                .frame(width: 16, height: 13)
                                .padding(4)
                                .background(Theme.card, in: Circle())
                                .offset(x: 8, y: -8)
                        }
                    }
                    .padding(.top, 4)

                Text(food.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.ink)
                    .multilineTextAlignment(.center)
                    .lineLimit(2, reservesSpace: true)

                if locked {
                    Label("Level \(food.unlockLevel)", systemImage: "lock.fill")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Theme.inkSoft)
                } else {
                    price
                }

                Label("+\(Int(food.nutrition))", systemImage: Need.hunger.symbol)
                    .font(.caption)
                    .foregroundStyle(Theme.inkSoft)
            }
            .padding(.vertical, 12)
            .padding(.horizontal, 8)
            .frame(maxWidth: .infinity)
            .overlay(alignment: .topLeading) {
                if !tasted && !locked {
                    Tag(text: "NEW").padding(8)
                }
            }
        }
        .buttonStyle(TileStyle())
        .disabled(locked)
        .accessibilityLabel(accessibilityText)
        .accessibilityHint(locked ? "" : "Feeds your slime")
    }

    @ViewBuilder private var price: some View {
        if food.price == 0 {
            Text("Free")
                .font(.caption.weight(.semibold))
                .foregroundStyle(Theme.accent)
        } else {
            HStack(spacing: 4) {
                PixelItem(name: "coin").frame(width: 13, height: 13)
                Text(food.price, format: .number)
                    .font(.caption.weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(affordable ? Theme.ink : Theme.inkSoft)
            }
        }
    }

    private var accessibilityText: String {
        var parts = [food.title]
        if favorite { parts.append("favourite") }
        if locked {
            parts.append("locked, unlocks at level \(food.unlockLevel)")
        } else {
            parts.append(food.price == 0 ? "free" : "\(food.price) coins")
            if !affordable { parts.append("not enough coins") }
            if !tasted { parts.append("not tried yet") }
        }
        parts.append("fills \(Int(food.nutrition))")
        return parts.joined(separator: ", ")
    }
}
