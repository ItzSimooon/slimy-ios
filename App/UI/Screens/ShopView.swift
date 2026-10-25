import SwiftUI

/// Buy and wear things: hats, glasses, scarves, effects, furniture, habitats
/// and colours. Owned items are equipped from here too.
struct ShopView: View {
    @EnvironmentObject private var store: GameStore
    @Environment(\.dismiss) private var dismiss

    enum Tab: Hashable { case kind(ItemKind), habitats, colors }

    @State private var tab: Tab = .kind(.hat)

    private var tabs: [(Tab, String)] {
        ItemKind.allCases.map { (Tab.kind($0), $0.title) } + [(.habitats, "Places"), (.colors, "Colors")]
    }

    var body: some View {
        let s = store.state
        ZStack {
            MenuBackdrop()
            ScrollView {
                VStack(spacing: 14) {
                    MenuHeader(title: "Shop", subtitle: "Clothes, furniture and new places") { dismiss() }

                    PixelWindow {
                        HStack(spacing: 16) {
                            LiveSlimeView(height: 90, interactive: false)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(s.slime.name).font(Theme.title(20)).foregroundStyle(Theme.ink)
                                Text(s.slime.stage.title + " · Level \(s.slime.level)")
                                    .font(Theme.text(14)).foregroundStyle(Theme.inkSoft)
                                CoinCounter(coins: s.wallet.coins)
                            }
                        }
                    }

                    PixelTabs(options: tabs, selection: $tab)

                    switch tab {
                    case .kind(let kind):
                        if kind == .furniture {
                            Text("Furniture goes in its room.")
                                .font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        ForEach(Catalog.items(kind)) { item in
                            ItemRow(item: item)
                        }
                    case .habitats:
                        ForEach(Habitat.allCases) { habitat in
                            HabitatRow(habitat: habitat)
                        }
                    case .colors:
                        ForEach(Skin.allCases) { skin in
                            SkinRow(skin: skin)
                        }
                    }
                }
                .padding(16)
            }
        }
        .presentationDetents([.large])
    }
}

private func unlockText(_ unlock: Unlock) -> String {
    switch unlock {
    case .level(let n): "from level \(n)"
    case .achievement(let id): "Achievement: \(Achievements.achievement(id)?.title ?? id)"
    case .bond(let n): "at \(n) bond"
    case .season(let month): month == 10 ? "October only" : "seasonal"
    }
}

private struct ItemRow: View {
    @EnvironmentObject private var store: GameStore
    let item: CatalogItem

    var body: some View {
        let s = store.state
        let owned = s.owns(item.id)
        let available = s.isAvailable(item.unlock)
        let equipped = s.isEquipped(item.id)
        PixelWindow(padding: 10, fill: equipped ? Color(hex: 0xE6F6DC) : Theme.paper) {
            HStack(spacing: 12) {
                preview
                    .frame(width: 52, height: 52)
                    .background(Theme.paperShade)
                    .overlay(Rectangle().strokeBorder(Theme.border.opacity(0.4), lineWidth: 2))
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text(item.title).font(Theme.label(16)).foregroundStyle(Theme.ink)
                        if item.rare {
                            Text("RARE").font(Theme.label(10)).foregroundStyle(.white)
                                .padding(.horizontal, 4).background(Theme.love)
                        }
                    }
                    if !available {
                        Text(unlockText(item.unlock)).font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
                    } else if !owned {
                        HStack(spacing: 4) {
                            PixelItem(name: "coin").frame(width: 12, height: 12)
                            Text(item.price == 0 ? "geschenkt" : "\(item.price)").font(Theme.label(13)).foregroundStyle(Theme.ink)
                        }
                    } else if let slot = item.slot {
                        Text(slot.title).font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
                    }
                }
                Spacer()
                action(owned: owned, available: available, equipped: equipped)
            }
        }
        .opacity(available || owned ? 1 : 0.6)
    }

    @ViewBuilder private var preview: some View {
        if item.kind == .effect {
            IconShape(rows: SlimeSprites.emoteIcons[item.id == "effect.hearts" ? "heart" : item.id == "effect.bubbles" ? "bubble_big" : "sparkle"] ?? [])
                .fill(item.id == "effect.hearts" ? Theme.love : item.id == "effect.bubbles" ? Theme.sky : Theme.gold)
                .padding(10)
        } else {
            PixelItem(name: item.sprite).padding(6)
        }
    }

    @ViewBuilder
    private func action(owned: Bool, available: Bool, equipped: Bool) -> some View {
        if owned {
            PixelButton(title: equipped ? "Take off" : "Wear", kind: equipped ? .quiet : .primary, compact: true) {
                if equipped {
                    store.update { $0.unequip(item.id) }
                } else {
                    store.update { $0.equip(item.id, now: .now) }
                    Feedback.shared.play(.success)
                }
            }
        } else if available {
            PixelButton(title: item.price == 0 ? "Get" : "Buy", kind: .primary, compact: true) {
                store.apply({ $0.buy(item, now: .now) }, sound: .coin)
            }
            .disabled(store.state.wallet.coins < item.price)
        } else {
            Image(systemName: "lock.fill").foregroundStyle(Theme.inkSoft).accessibilityLabel("Locked")
        }
    }
}

private struct HabitatRow: View {
    @EnvironmentObject private var store: GameStore
    let habitat: Habitat

    var body: some View {
        let s = store.state
        let owned = s.inventory.habitats.contains(habitat)
        let available = s.isAvailable(habitat.unlock)
        let here = s.inventory.habitat == habitat
        PixelWindow(padding: 10, fill: here ? Color(hex: 0xE6F6DC) : Theme.paper) {
            VStack(alignment: .leading, spacing: 8) {
                HabitatScene(habitat: habitat, furniture: s.inventory.furniture, date: .now, animate: false)
                    .frame(height: 90)
                    .clipped()
                    .overlay(Rectangle().strokeBorder(Theme.border, lineWidth: 2))
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(habitat.title).font(Theme.label(16)).foregroundStyle(Theme.ink)
                        if !owned {
                            Text(available ? (habitat.price == 0 ? "geschenkt" : "\(habitat.price) coins") : unlockText(habitat.unlock))
                                .font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
                        } else if habitat.hasWeather {
                            Text("with weather").font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
                        }
                    }
                    Spacer()
                    if here {
                        Text("Lives here").font(Theme.label(13)).foregroundStyle(Theme.accentInk)
                    } else if owned {
                        PixelButton(title: "Move here", compact: true) { store.update { $0.move(to: habitat) } }
                    } else if available {
                        PixelButton(title: "Buy", compact: true) {
                            store.apply({ $0.buy(habitat, now: .now) }, sound: .coin)
                        }
                        .disabled(s.wallet.coins < habitat.price)
                    } else {
                        Image(systemName: "lock.fill").foregroundStyle(Theme.inkSoft)
                    }
                }
            }
        }
    }
}

private struct SkinRow: View {
    @EnvironmentObject private var store: GameStore
    let skin: Skin

    var body: some View {
        let s = store.state
        let unlocked = s.slime.isUnlocked(skin)
        let wearing = s.slime.skin == skin
        PixelWindow(padding: 10, fill: wearing ? Color(hex: 0xE6F6DC) : Theme.paper) {
            HStack(spacing: 12) {
                PixelSlime(frame: SlimeSprites.frames(s.slime.stage, .idle)[0], skin: skin)
                    .frame(width: 58, height: 48)
                    .saturation(unlocked ? 1 : 0)
                    .opacity(unlocked ? 1 : 0.4)
                VStack(alignment: .leading, spacing: 2) {
                    Text(skin.title).font(Theme.label(16)).foregroundStyle(Theme.ink)
                    Text(unlocked ? (wearing ? "wearing" : "freigeschaltet") : "from level \(skin.unlockLevel)")
                        .font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
                }
                Spacer()
                if unlocked && !wearing {
                    PixelButton(title: "Dye", compact: true) { store.wear(skin) }
                }
            }
        }
    }
}
