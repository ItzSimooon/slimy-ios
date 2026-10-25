import SwiftUI

struct FeedSheet: View {
    @EnvironmentObject private var store: SlimeStore
    @Environment(\.dismiss) private var dismiss
    @State private var munch = 0

    var body: some View {
        let s = store.state
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                SheetHeader(eyebrow: "küche", title: "\(s.name) füttern") { dismiss() }

                HStack(spacing: 14) {
                    LiveSlimeView(height: 80, interactive: false)
                    VStack(alignment: .leading, spacing: 8) {
                        NeedRow(need: .hunger, value: Int(s.needs.hunger.rounded()))
                        Text("Lieblingsessen: \(s.favorite.title). Macht doppelt glücklich.")
                            .font(Brand.body(14))
                            .foregroundStyle(Brand.muted)
                    }
                }

                VStack(spacing: 8) {
                    ForEach(Food.allCases) { food in
                        FoodRow(food: food, favorite: food == s.favorite, affordable: s.coins >= food.price) {
                            munch += 1
                            store.act(.feed(food))
                        }
                    }
                }

                HStack {
                    Text("Münzen gibt es beim Snack-Fangen.")
                        .font(Brand.body(14))
                        .foregroundStyle(Brand.muted)
                    Spacer()
                    CoinChip(coins: s.coins)
                }
            }
            .padding(18)
        }
        .sensoryFeedback(.success, trigger: munch)
        .presentationDetents([.large])
    }
}

private struct FoodRow: View {
    let food: Food
    let favorite: Bool
    let affordable: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                PixelItem(name: food.sprite)
                    .frame(width: 34, height: 34)
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text(food.title)
                            .font(Brand.pixel(17))
                            .foregroundStyle(Brand.text)
                        if favorite {
                            Text("LIEBLING")
                                .font(Brand.pixel(10))
                                .foregroundStyle(Brand.bg)
                                .padding(.horizontal, 4)
                                .padding(.vertical, 1)
                                .background(Brand.warm)
                        }
                    }
                    Text("+\(Int(food.nutrition)) essen")
                        .font(Brand.pixel(12, bold: false))
                        .foregroundStyle(Brand.muted)
                }
                Spacer()
                if food.price == 0 {
                    Text("gratis")
                        .font(Brand.pixel(15))
                        .foregroundStyle(Brand.slime)
                } else {
                    HStack(spacing: 5) {
                        PixelItem(name: "coin").frame(width: 13, height: 13)
                        Text("\(food.price)")
                            .font(Brand.pixel(15))
                            .foregroundStyle(Brand.gold)
                    }
                }
            }
            .padding(12)
            .background(Brand.panel)
            .overlay(Rectangle().strokeBorder(favorite ? Brand.warm.opacity(0.7) : Brand.line, lineWidth: 2))
            .opacity(affordable ? 1 : 0.4)
        }
        .buttonStyle(PressStyle())
        .disabled(!affordable)
        .accessibilityLabel("\(food.title), \(food.price == 0 ? "gratis" : "\(food.price) Münzen")")
    }
}
