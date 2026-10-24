import SwiftUI

struct FeedSheet: View {
    @EnvironmentObject private var store: SlimeStore
    @Environment(\.dismiss) private var dismiss
    @State private var munch = 0

    var body: some View {
        let s = store.state
        VStack(spacing: 18) {
            SheetHeader(eyebrow: "kitchen", title: "Feed \(s.name)") { dismiss() }

            HStack(spacing: 16) {
                LiveSlimeView(height: 96, interactive: false)
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 8) {
                        NeedRing(need: .hunger, value: Int(s.needs.hunger), size: 30)
                        Text("\(Int(s.needs.hunger))% full")
                            .font(Brand.display(15, .bold))
                            .foregroundStyle(Brand.text)
                            .contentTransition(.numericText())
                    }
                    Text("Favorite: \(s.favorite.emoji) \(s.favorite.title). It loves those extra.")
                        .font(Brand.display(13, .medium))
                        .foregroundStyle(Brand.muted)
                }
                Spacer(minLength: 0)
            }

            LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                ForEach(Food.allCases) { food in
                    FoodTile(food: food, favorite: food == s.favorite, affordable: s.coins >= food.price) {
                        munch += 1
                        withAnimation(.spring) { _ = store.act(.feed(food)) }
                    }
                }
            }
            HStack {
                Text("Earn coins by playing Snack Catch.")
                    .font(Brand.display(13, .medium))
                    .foregroundStyle(Brand.muted)
                Spacer()
                CoinChip(coins: s.coins)
            }
            Spacer(minLength: 0)
        }
        .padding(20)
        .sensoryFeedback(.success, trigger: munch)
        .presentationDetents([.medium, .large])
    }
}

private struct FoodTile: View {
    let food: Food
    let favorite: Bool
    let affordable: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Text(food.emoji).font(.system(size: 34))
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 4) {
                        Text(food.title)
                            .font(Brand.display(15, .bold))
                            .foregroundStyle(Brand.text)
                        if favorite {
                            Image(systemName: "heart.fill")
                                .font(.system(size: 10, weight: .black))
                                .foregroundStyle(Brand.warm)
                        }
                    }
                    Text(food.price == 0 ? "free" : "\(food.price) coins")
                        .font(Brand.mono(11, .bold))
                        .foregroundStyle(food.price == 0 ? Brand.color(for: .clean) : Brand.gold)
                    Text("+\(Int(food.nutrition)) food")
                        .font(Brand.mono(10, .semibold))
                        .foregroundStyle(Brand.muted)
                }
                Spacer(minLength: 0)
            }
            .padding(12)
            .background(Brand.deep, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous)
                .strokeBorder(favorite ? Brand.warm.opacity(0.6) : Brand.line, lineWidth: 1))
            .opacity(affordable ? 1 : 0.45)
        }
        .buttonStyle(SquishStyle())
        .disabled(!affordable)
        .accessibilityLabel("\(food.title), \(food.price == 0 ? "free" : "\(food.price) coins")")
    }
}

struct SheetHeader: View {
    let eyebrow: String
    let title: String
    let close: () -> Void

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 4) {
                Eyebrow(text: eyebrow, color: Brand.hyprGlow)
                Text(title)
                    .font(Brand.display(26, .black))
                    .foregroundStyle(Brand.text)
            }
            Spacer()
            CloseButton(action: close)
        }
    }
}
