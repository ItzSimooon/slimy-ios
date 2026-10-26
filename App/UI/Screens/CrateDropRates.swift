import SwiftUI

/// The odds in every crate, rarity by rarity. The legendary reward stays a
/// secret: its row only says "???".
struct DropRatesView: View {
    /// Presented on its own rather than pushed: brings its own navigation
    /// bar and Done button.
    var standalone = false

    var body: some View {
        if standalone {
            NavigationStack {
                list.sheetTitle("Drop Rates", inline: true)
            }
        } else {
            list
                .navigationTitle("Drop Rates")
                .navigationBarTitleDisplayMode(.inline)
        }
    }

    private var list: some View {
        List {
            Section {
                Text(CrateCopy.contents)
                    .font(.subheadline)
                    .foregroundStyle(Theme.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }

            ForEach(CrateKind.allCases) { kind in
                Section {
                    ForEach(Self.rows(kind)) { row in
                        RateRow(row: row)
                    }
                } header: {
                    HStack(spacing: 8) {
                        CrateSprite(kind: kind)
                            .frame(width: 22, height: 22)
                        Text(CrateCopy.name(kind))
                    }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(CrateCopy.name(kind))
                    .accessibilityAddTraits(.isHeader)
                } footer: {
                    Text(Self.footer(kind))
                }
            }

            Section {
                Label {
                    Text(CrateCopy.noRealMoney)
                } icon: {
                    PixelItem(name: "coin").frame(width: 20, height: 20)
                }
                .font(.subheadline)
                .foregroundStyle(Theme.inkSoft)
                .fixedSize(horizontal: false, vertical: true)
            }
        }
        .listStyle(.insetGrouped)
    }

    // MARK: Odds

    struct Rate: Identifiable {
        let rarity: Rarity
        /// "62.5%", or "???" for the secret.
        let text: String
        let spoken: String
        var id: Rarity { rarity }
    }

    /// Each rarity's share of the crate, from the game's own drop table.
    /// Shares are worked out from the table's total, so they add up however
    /// the table writes its chances.
    static func rows(_ kind: CrateKind) -> [Rate] {
        let rates = Crates.dropRates(kind).filter { $0.chance > 0 }
        let total = rates.reduce(0.0) { $0 + $1.chance }
        return rates
            .sorted { $0.rarity < $1.rarity }
            .map { entry in
                if entry.rarity == .legendary {
                    return Rate(rarity: entry.rarity, text: "???", spoken: "Secret")
                }
                let share = total > 0 ? entry.chance / total : 0
                let text = share.formatted(.percent.precision(.fractionLength(0...1)))
                return Rate(rarity: entry.rarity, text: text, spoken: text)
            }
    }

    private static func footer(_ kind: CrateKind) -> String {
        if let price = kind.price {
            return "\(CrateCopy.coins(price)) in the shop, or as a reward in the game."
        }
        return CrateCopy.mysteryHint
    }
}

private struct RateRow: View {
    let row: DropRatesView.Rate

    var body: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(row.rarity.displayColor)
                .frame(width: 12, height: 12)
                .overlay(Circle().strokeBorder(Theme.separator, lineWidth: 0.5))
            Text(row.rarity.title)
                .font(.body)
                .foregroundStyle(Theme.ink)
            Spacer(minLength: 8)
            Text(row.text)
                .font(.body.weight(.semibold))
                .monospacedDigit()
                .foregroundStyle(row.rarity == .legendary ? Theme.inkSoft : Theme.ink)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(row.rarity.title)
        .accessibilityValue(row.spoken)
    }
}
