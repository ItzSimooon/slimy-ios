import SwiftUI

struct KitchenView: View {
    @EnvironmentObject private var store: GameStore
    @Environment(\.dismiss) private var dismiss

    private let columns = [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)]

    var body: some View {
        let s = store.state
        ZStack {
            MenuBackdrop()
            ScrollView {
                VStack(spacing: 14) {
                    MenuHeader(title: "Kitchen", subtitle: "What should \(s.slime.name) eat?") { dismiss() }

                    PixelWindow {
                        HStack(spacing: 14) {
                            LiveSlimeView(height: 70, interactive: false)
                            VStack(alignment: .leading, spacing: 6) {
                                HStack {
                                    Text("Hunger").font(Theme.label(15)).foregroundStyle(Theme.ink)
                                    Spacer()
                                    Text("\(Int(s.slime.needs.hunger)) %").font(Theme.label(15)).foregroundStyle(Theme.inkSoft)
                                }
                                BlockBar(value: s.slime.needs.hunger / 100, color: Theme.color(for: .hunger))
                                Text(s.inventory.favoriteFound
                                     ? "Favourite: \(s.slime.favorite.title)"
                                     : "You don't know its favourite food yet. Try things out.")
                                    .font(Theme.text(13))
                                    .foregroundStyle(Theme.inkSoft)
                            }
                        }
                    }

                    LazyVGrid(columns: columns, spacing: 10) {
                        ForEach(Food.allCases) { food in
                            FoodCard(food: food,
                                     locked: s.slime.level < food.unlockLevel,
                                     affordable: s.wallet.coins >= food.price,
                                     tasted: s.inventory.foodsTasted.contains(food),
                                     favorite: s.inventory.favoriteFound && food == s.slime.favorite) {
                                store.feed(food)
                            }
                        }
                    }

                    HStack {
                        Text("Tried: \(s.inventory.foodsTasted.count) of \(Food.allCases.count)")
                            .font(Theme.label(14)).foregroundStyle(Theme.inkSoft)
                        Spacer()
                        CoinCounter(coins: s.wallet.coins)
                    }
                }
                .padding(16)
            }
        }
        .presentationDetents([.large])
    }
}

private struct FoodCard: View {
    let food: Food
    let locked: Bool
    let affordable: Bool
    let tasted: Bool
    let favorite: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                ZStack(alignment: .topTrailing) {
                    PixelItem(name: food.sprite)
                        .frame(width: 44, height: 44)
                        .saturation(locked ? 0 : 1)
                        .opacity(locked ? 0.4 : 1)
                    if favorite {
                        PixelItem(name: "heart").frame(width: 14, height: 11).offset(x: 10, y: -4)
                    }
                }
                Text(locked ? "from level \(food.unlockLevel)" : food.title)
                    .font(Theme.label(15)).foregroundStyle(Theme.ink)
                HStack(spacing: 4) {
                    if food.price == 0 {
                        Text("free").font(Theme.label(13)).foregroundStyle(Theme.accentInk)
                    } else {
                        PixelItem(name: "coin").frame(width: 12, height: 12)
                        Text("\(food.price)").font(Theme.label(13)).foregroundStyle(Theme.ink)
                    }
                    Text("· +\(Int(food.nutrition))").font(Theme.label(12)).foregroundStyle(Theme.inkSoft)
                    if !tasted && !locked {
                        Text("new").font(Theme.label(11)).foregroundStyle(.white)
                            .padding(.horizontal, 4).background(Theme.love)
                    }
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
        }
        .buttonStyle(KeyStyle(fill: Theme.paper))
        .disabled(locked || !affordable)
        .opacity(!locked && !affordable ? 0.55 : 1)
        .accessibilityLabel("\(food.title), \(food.price == 0 ? "free" : "\(food.price) coins")\(locked ? ", locked" : "")")
    }
}
