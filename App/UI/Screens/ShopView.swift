import SwiftUI
import UIKit

/// Buy and wear things: crates, hats, glasses, scarves, effects, furniture,
/// places and colours. Tapping anything tries it on the slime first; the
/// bar at the bottom buys, wears or places what is picked. Crates are
/// bought and opened right on their cards.
struct ShopView: View {
    @EnvironmentObject private var store: GameStore
    @Environment(\.dynamicTypeSize) private var typeSize

    enum Tab: Hashable { case crates, kind(ItemKind), places, colours }

    @State private var tab: Tab = .kind(.hat)
    @State private var pick: ShopPick?
    @State private var line: String?
    @State private var lineID = 0
    @State private var newKeys: Set<String> = []
    /// The crate waiting for "Buy for … coins?".
    @State private var buying: CrateKind?
    @State private var opening: CrateKind?
    @State private var showRates = false
    /// Everything that was already for sale on earlier visits, so new things get a tag.
    @AppStorage("slimy.shop.seen") private var seenRaw = ""
    @ScaledMetric(relativeTo: .body) private var tileWidth: CGFloat = 150

    private var tabs: [(Tab, String)] {
        var out: [(Tab, String)] = [(Tab.crates, "Crates")]
        out += ItemKind.allCases.map { (Tab.kind($0), $0.title) }
        out.append((Tab.places, "Places"))
        out.append((Tab.colours, "Colours"))
        return out
    }

    var body: some View {
        let s = store.state
        NavigationStack {
            VStack(spacing: 0) {
                SlimeShowcase(habitat: previewHabitat(s), furniture: previewFurniture(s), slime: previewSlime(s),
                              line: line, height: typeSize.isAccessibilitySize ? 140 : 196,
                              slimeHeight: typeSize.isAccessibilitySize ? 70 : 96)
                    .padding(.horizontal, 16)
                    .padding(.top, 4)

                ChipTabs(options: tabs, selection: $tab)
                    .contentMargins(.horizontal, 16, for: .scrollContent)
                    .padding(.vertical, 10)

                Divider()

                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        if let note = note(for: tab, s) {
                            Text(note)
                                .font(.footnote)
                                .foregroundStyle(Theme.inkSoft)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        if tab == .crates {
                            crateCards(s)
                        } else {
                            LazyVGrid(columns: [GridItem(.adaptive(minimum: tileWidth), spacing: 12)], spacing: 12) {
                                tiles(s)
                            }
                        }
                    }
                    .padding(16)
                }
            }
            .background(Theme.backdrop)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                // Crates have their own buttons; nothing to try on.
                if tab != .crates { actionBar(s) }
            }
            .sheetTitle("Shop", inline: true)
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
        .sheet(isPresented: $showRates) {
            DropRatesView(standalone: true)
        }
        .onChange(of: tab) { pick = nil }
        .onAppear(perform: markSeen)
        .task(id: lineID) {
            guard line != nil else { return }
            try? await Task.sleep(for: .seconds(4))
            guard !Task.isCancelled else { return }
            line = nil
        }
    }

    // MARK: Grid

    @ViewBuilder
    private func tiles(_ s: GameState) -> some View {
        switch tab {
        case .crates:
            EmptyView()
        case .kind(let kind):
            ForEach(Catalog.items(kind)) { item in
                itemTile(item, s)
            }
        case .places:
            ForEach(Habitat.allCases) { habitat in
                habitatTile(habitat, s)
            }
        case .colours:
            ForEach(Self.shownSkins(s)) { skin in
                skinTile(skin, s)
            }
        }
    }

    /// Every colour, except a secret one nobody has found yet: those are
    /// never for sale and never shown locked.
    private static func shownSkins(_ s: GameState) -> [Skin] {
        Skin.allCases.filter { !$0.isSecret || s.slime.isUnlocked($0) }
    }

    private func itemTile(_ item: CatalogItem, _ s: GameState) -> some View {
        let owned = s.owns(item.id)
        let available = s.isAvailable(item.unlock)
        let status: TileStatus
        if s.isEquipped(item.id) {
            status = .active(item.kind == .furniture ? "In the room" : "Wearing")
        } else if owned {
            status = .owned
        } else if available {
            status = item.price == 0 ? .free : .price(item.price, affordable: s.wallet.coins >= item.price)
        } else {
            status = .locked(UnlockText.short(item.unlock))
        }
        let actions = model(for: .item(item.id), s)
        return ShopTile(title: item.title, status: status, isNew: newKeys.contains(item.id) && !owned,
                        rare: item.rare, legendary: false, selected: pick == .item(item.id),
                        accessibility: accessibilityText(item.title, rare: item.rare, status: status),
                        quickAction: actions.quickAction,
                        action: { select(.item(item.id)) }) {
            ShopItemArt(item: item, size: 64)
                .saturation(available || owned ? 1 : 0)
                .opacity(available || owned ? 1 : 0.5)
        }
    }

    private func habitatTile(_ habitat: Habitat, _ s: GameState) -> some View {
        let owned = s.inventory.habitats.contains(habitat)
        let available = s.isAvailable(habitat.unlock)
        let status: TileStatus
        if s.inventory.habitat == habitat {
            status = .active("Home")
        } else if owned {
            status = .owned
        } else if available {
            status = habitat.price == 0 ? .free : .price(habitat.price, affordable: s.wallet.coins >= habitat.price)
        } else {
            status = .locked(UnlockText.short(habitat.unlock))
        }
        let key = "habitat.\(habitat.rawValue)"
        let actions = model(for: .habitat(habitat), s)
        return ShopTile(title: habitat.title, status: status, isNew: newKeys.contains(key) && !owned,
                        rare: false, legendary: false, selected: pick == .habitat(habitat),
                        accessibility: accessibilityText(habitat.title, rare: false, status: status),
                        quickAction: actions.quickAction,
                        action: { select(.habitat(habitat)) }) {
            CroppedHabitat(habitat: habitat, furniture: habitat == .room ? s.inventory.furniture : [:])
                .frame(height: 76)
                .clipShape(RoundedRectangle(cornerRadius: Theme.smallRadius, style: .continuous))
                .saturation(available || owned ? 1 : 0)
                .opacity(available || owned ? 1 : 0.55)
        }
    }

    private func skinTile(_ skin: Skin, _ s: GameState) -> some View {
        let unlocked = s.slime.isUnlocked(skin)
        let status: TileStatus
        if s.slime.skin == skin {
            status = .active("Wearing")
        } else if unlocked {
            status = .owned
        } else {
            status = .locked("Level \(skin.unlockLevel)")
        }
        let key = "skin.\(skin.rawValue)"
        let actions = model(for: .skin(skin), s)
        let spoken = accessibilityText(skin.title, rare: false, status: status) + (skin.isSecret ? ", legendary" : "")
        return ShopTile(title: skin.title, status: status,
                        isNew: newKeys.contains(key) && s.slime.skin != skin,
                        rare: false, legendary: skin.isSecret, selected: pick == .skin(skin),
                        accessibility: spoken,
                        quickAction: actions.quickAction,
                        action: { select(.skin(skin)) }) {
            ShopSkinArt(skin: skin, slime: s.slime)
                .frame(width: 77, height: 64)
                .saturation(unlocked ? 1 : 0)
                .opacity(unlocked ? 1 : 0.45)
        }
    }

    private func note(for tab: Tab, _ s: GameState) -> String? {
        switch tab {
        case .crates:
            return CrateCopy.contents
        case .kind(let kind):
            if kind == .furniture { return "Furniture goes in the room, one piece per spot." }
            if kind == .effect { return "Effects float around \(s.slime.name) wherever it goes." }
            return nil
        case .places:
            return "Garden and beach have weather. Furniture stays in the room."
        case .colours:
            return "New colours unlock as \(s.slime.name) levels up. They're free."
        }
    }

    private func accessibilityText(_ title: String, rare: Bool, status: TileStatus) -> String {
        var parts = [title]
        if rare { parts.append("rare") }
        switch status {
        case .price(let price, let affordable):
            parts.append(affordable ? "\(price) coins" : "\(price) coins, not enough coins")
        case .free: parts.append("free")
        case .owned: parts.append("owned")
        case .active(let text): parts.append(text.lowercased())
        case .locked(let text): parts.append("locked, \(text)")
        }
        return parts.joined(separator: ", ")
    }

    // MARK: Try-on preview

    private func select(_ next: ShopPick) {
        Feedback.shared.play(.tap)
        withAnimation(.snappy(duration: 0.2)) {
            pick = pick == next ? nil : next
        }
    }

    private func previewSlime(_ s: GameState) -> Slime {
        var slime = s.slime
        switch pick {
        case .item(let id)?:
            guard let item = Catalog.item(id), !s.isEquipped(id) else { break }
            switch item.kind {
            case .hat: slime.equipped.hat = id
            case .glasses: slime.equipped.glasses = id
            case .scarf: slime.equipped.scarf = id
            case .effect: slime.equipped.effect = id
            default: break
            }
        case .skin(let skin)?:
            slime.skin = skin
        default:
            break
        }
        return slime
    }

    private func previewHabitat(_ s: GameState) -> Habitat {
        if case .habitat(let habitat)? = pick { return habitat }
        if tab == .kind(.furniture) { return .room }
        return s.inventory.habitat
    }

    private func previewFurniture(_ s: GameState) -> [FurnitureSlot: String] {
        var furniture = s.inventory.furniture
        if case .item(let id)? = pick, let item = Catalog.item(id), let slot = item.slot {
            furniture[slot] = id
        }
        return furniture
    }

    // MARK: Action bar

    private func actionBar(_ s: GameState) -> some View {
        VStack(spacing: 0) {
            Divider()
            Group {
                if let pick {
                    pickBar(pick, s)
                } else {
                    Text("Tap anything to try it on.")
                        .font(.subheadline)
                        .foregroundStyle(Theme.inkSoft)
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        }
        .background(.bar)
    }

    private func pickBar(_ pick: ShopPick, _ s: GameState) -> some View {
        let m = model(for: pick, s)
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 10))
            : AnyLayout(HStackLayout(spacing: 12))
        return layout {
            HStack(spacing: 12) {
                pickArt(pick, s)
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text(m.title)
                            .font(.headline)
                            .foregroundStyle(Theme.ink)
                            .lineLimit(2)
                        if m.rare { Tag(text: "RARE", color: rareGold) }
                        if m.legendary { RarityBadge(rarity: .legendary, compact: true) }
                    }
                    Text(m.detail)
                        .font(.subheadline)
                        .foregroundStyle(m.warning ? Theme.danger : Theme.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .accessibilityElement(children: .combine)
            }
            if !typeSize.isAccessibilitySize { Spacer(minLength: 4) }
            if let title = m.buttonTitle {
                AppButton(title: title, icon: m.buttonIcon, systemIcon: m.buttonSystemIcon,
                          kind: m.buttonKind, compact: true) { m.run() }
                    .disabled(!m.enabled)
            }
        }
        .frame(minHeight: 44)
    }

    @ViewBuilder
    private func pickArt(_ pick: ShopPick, _ s: GameState) -> some View {
        switch pick {
        case .item(let id):
            if let item = Catalog.item(id) { ShopItemArt(item: item, size: 44) }
        case .habitat(let habitat):
            CroppedHabitat(habitat: habitat, furniture: habitat == .room ? s.inventory.furniture : [:])
                .frame(width: 44, height: 44)
                .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
        case .skin(let skin):
            ShopSkinArt(skin: skin, slime: s.slime)
                .frame(width: 44, height: 44)
                .background(Theme.well, in: RoundedRectangle(cornerRadius: 11, style: .continuous))
        }
    }

    private func model(for pick: ShopPick, _ s: GameState) -> PickModel {
        switch pick {
        case .item(let id):
            guard let item = Catalog.item(id) else { return PickModel(title: "", detail: "") }
            return itemModel(item, s)
        case .habitat(let habitat):
            return habitatModel(habitat, s)
        case .skin(let skin):
            return skinModel(skin, s)
        }
    }

    private func itemModel(_ item: CatalogItem, _ s: GameState) -> PickModel {
        let furniture = item.kind == .furniture
        if s.isEquipped(item.id) {
            let detail: String
            if furniture, let slot = item.slot {
                detail = "In the room · \(slot.title)"
            } else {
                detail = furniture ? "In the room" : "Wearing it now"
            }
            return PickModel(title: item.title, detail: detail, rare: item.rare,
                             buttonTitle: furniture ? "Remove" : "Take off", buttonKind: .quiet,
                             run: { takeOff(item) })
        }
        if s.owns(item.id) {
            return PickModel(title: item.title, detail: ownedDetail(item, s), rare: item.rare,
                             buttonTitle: furniture ? "Place" : "Wear", run: { wear(item) })
        }
        if s.isAvailable(item.unlock) {
            let missing = item.price - s.wallet.coins
            let detail = missing > 0 ? Self.needMore(missing) : Self.priceText(item.price)
            return PickModel(title: item.title, detail: detail, warning: missing > 0, rare: item.rare,
                             buttonTitle: item.price == 0 ? "Get" : "Buy",
                             buttonIcon: item.price == 0 ? nil : "coin",
                             enabled: missing <= 0, run: { buy(item) })
        }
        return PickModel(title: item.title, detail: UnlockText.long(item.unlock), rare: item.rare,
                         buttonTitle: "Locked", buttonSystemIcon: "lock.fill", buttonKind: .quiet, enabled: false)
    }

    private func ownedDetail(_ item: CatalogItem, _ s: GameState) -> String {
        guard let slot = item.slot else { return "Owned" }
        if let current = s.inventory.furniture[slot], current != item.id, let other = Catalog.item(current) {
            return "\(slot.title) · replaces \(other.title.lowercased())"
        }
        return "Owned · \(slot.title.lowercased())"
    }

    private func habitatModel(_ habitat: Habitat, _ s: GameState) -> PickModel {
        let weather = habitat.hasWeather ? " · has weather" : ""
        if s.inventory.habitat == habitat {
            return PickModel(title: habitat.title, detail: "\(s.slime.name) lives here\(weather)")
        }
        if s.inventory.habitats.contains(habitat) {
            return PickModel(title: habitat.title, detail: "Owned\(weather)", buttonTitle: "Move here",
                             run: { move(to: habitat) })
        }
        if s.isAvailable(habitat.unlock) {
            let missing = habitat.price - s.wallet.coins
            let detail = missing > 0 ? Self.needMore(missing) : Self.priceText(habitat.price) + weather
            return PickModel(title: habitat.title, detail: detail, warning: missing > 0,
                             buttonTitle: habitat.price == 0 ? "Get" : "Buy",
                             buttonIcon: habitat.price == 0 ? nil : "coin",
                             enabled: missing <= 0, run: { buy(habitat) })
        }
        return PickModel(title: habitat.title, detail: UnlockText.long(habitat.unlock),
                         buttonTitle: "Locked", buttonSystemIcon: "lock.fill", buttonKind: .quiet, enabled: false)
    }

    private static func needMore(_ missing: Int) -> String {
        missing == 1 ? "You need 1 more coin." : "You need \(missing) more coins."
    }

    private static func priceText(_ price: Int) -> String {
        price == 0 ? "Free" : "\(price) coins"
    }

    private func skinModel(_ skin: Skin, _ s: GameState) -> PickModel {
        if s.slime.skin == skin {
            return PickModel(title: skin.title, detail: "\(s.slime.name)'s colour right now", legendary: skin.isSecret)
        }
        if s.slime.isUnlocked(skin) {
            return PickModel(title: skin.title, detail: skin.isSecret ? "Found in a crate" : "Unlocked",
                             legendary: skin.isSecret, buttonTitle: "Use colour", run: { dye(skin) })
        }
        if skin.isSecret {
            // Never listed before it's found; nothing to say about it.
            return PickModel(title: "???", detail: "Only found in crates.")
        }
        return PickModel(title: skin.title, detail: "Unlocks at level \(skin.unlockLevel).",
                         buttonTitle: "Locked", buttonSystemIcon: "lock.fill", buttonKind: .quiet, enabled: false)
    }

    // MARK: Actions

    private func buy(_ item: CatalogItem) {
        let out = purchase { $0.buy(item, now: .now) }
        if out.ok {
            // It was just tried on, so it goes straight on (or into its spot).
            store.update { $0.equip(item.id, now: .now) }
        }
        say(out.line, announce: true)
    }

    private func buy(_ habitat: Habitat) {
        let out = purchase { $0.buy(habitat, now: .now) }
        say(out.line, announce: true)
    }

    /// Runs a purchase through the store without the app-wide toast: the
    /// line shows in the speech bubble at the top instead (a toast would
    /// cover this sheet's title bar), and `say` reads it to VoiceOver.
    private func purchase(_ rule: (inout GameState) -> Outcome) -> Outcome {
        var said = ""
        var out = store.apply({ s in
            var result = rule(&s)
            said = result.line
            result.line = ""
            return result
        }, sound: .coin)
        out.line = said
        return out
    }

    private func wear(_ item: CatalogItem) {
        store.update { $0.equip(item.id, now: .now) }
        Feedback.shared.play(.success)
        say(store.state.slime.say?.text, announce: true)
    }

    private func takeOff(_ item: CatalogItem) {
        store.update { $0.unequip(item.id) }
        withAnimation(.snappy(duration: 0.2)) { pick = nil }
    }

    private func move(to habitat: Habitat) {
        store.update { $0.move(to: habitat) }
        Feedback.shared.play(.success)
    }

    private func dye(_ skin: Skin) {
        store.wear(skin)
        Feedback.shared.play(.success)
    }

    // MARK: Crates

    private func crateCards(_ s: GameState) -> some View {
        VStack(spacing: 12) {
            ForEach(CrateKind.allCases) { kind in
                ShopCrateCard(kind: kind, owned: s.crates[kind, default: 0], coins: s.wallet.coins,
                              buy: { buying = kind }, open: { opening = kind })
            }
            AppButton(title: "Drop rates", systemIcon: "percent", kind: .quiet) { showRates = true }
            Text(CrateCopy.noRealMoney)
                .font(.footnote)
                .foregroundStyle(Theme.inkSoft)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// The store plays the coin sound, or its "nope" when it can't be bought.
    private func buy(_ kind: CrateKind) {
        guard store.buyCrate(kind) else { return }
        say(Self.crateLines.randomElement(), announce: false)
        UIAccessibility.post(notification: .announcement,
                             argument: "Bought a \(CrateCopy.name(kind).lowercased()). Open it whenever you like.")
    }

    private static let crateLines = ["ooh, a crate!", "what's inside? open it!", "shake it! shake it!",
                                     "i heard something rattle!"]

    private func say(_ text: String?, announce: Bool) {
        guard let text, !text.isEmpty else { return }
        line = text
        lineID += 1
        if announce { UIAccessibility.post(notification: .announcement, argument: text) }
    }

    // MARK: New things

    private func markSeen() {
        let current = Self.forSale(store.state)
        let stored = seenRaw.split(separator: ",").map(String.init)
        let started = stored.first == "v1"
        let seen = Set(started ? Array(stored.dropFirst()) : [])
        // The first visit marks everything as seen; later visits tag what's new.
        newKeys = started ? current.subtracting(seen) : []
        seenRaw = (["v1"] + seen.union(current).sorted()).joined(separator: ",")
    }

    private static func forSale(_ s: GameState) -> Set<String> {
        var keys = Set<String>()
        for item in Catalog.items where s.isAvailable(item.unlock) && !s.owns(item.id) {
            keys.insert(item.id)
        }
        for habitat in Habitat.allCases where s.isAvailable(habitat.unlock) && !s.inventory.habitats.contains(habitat) {
            keys.insert("habitat.\(habitat.rawValue)")
        }
        for skin in Skin.allCases where s.slime.isUnlocked(skin) {
            keys.insert("skin.\(skin.rawValue)")
        }
        return keys
    }
}

// MARK: - Pieces

/// Gold dark enough for small text on light backgrounds.
private let rareGold = Color.dynamic(light: 0x8A5A00, dark: 0xFFD166)

private enum ShopPick: Hashable {
    case item(String)
    case habitat(Habitat)
    case skin(Skin)
}

private enum TileStatus {
    case price(Int, affordable: Bool)
    case free
    case owned
    case active(String)
    case locked(String)
}

/// What the action bar shows for the picked thing.
private struct PickModel {
    var title: String
    var detail: String
    var warning = false
    var rare = false
    /// A secret colour found in a crate.
    var legendary = false
    var buttonTitle: String? = nil
    var buttonIcon: String? = nil
    var buttonSystemIcon: String? = nil
    var buttonKind: AppButtonKind = .primary
    var enabled = true
    var run: () -> Void = {}

    /// The main action, for VoiceOver and the context menu of a tile.
    var quickAction: (title: String, run: () -> Void)? {
        guard enabled, let buttonTitle, buttonTitle != "Locked" else { return nil }
        return (title: buttonTitle, run: run)
    }
}

private struct ShopTile<Art: View>: View {
    let title: String
    let status: TileStatus
    let isNew: Bool
    let rare: Bool
    let legendary: Bool
    let selected: Bool
    let accessibility: String
    let quickAction: (title: String, run: () -> Void)?
    let action: () -> Void
    @ViewBuilder let art: Art

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                art
                    .frame(maxWidth: .infinity)
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.ink)
                    .multilineTextAlignment(.center)
                    .lineLimit(2, reservesSpace: true)
                statusView
            }
            .padding(12)
            .frame(maxWidth: .infinity)
            .overlay(alignment: .topLeading) {
                VStack(alignment: .leading, spacing: 4) {
                    if isNew { Tag(text: "NEW") }
                    if rare { Tag(text: "RARE", color: rareGold) }
                    if legendary { RarityBadge(rarity: .legendary, compact: true) }
                }
                .padding(8)
            }
            .overlay {
                if selected {
                    RoundedRectangle(cornerRadius: Theme.radius, style: .continuous)
                        .strokeBorder(Theme.accent, lineWidth: 2.5)
                }
            }
        }
        .buttonStyle(TileStyle())
        .contextMenu {
            if let quickAction {
                Button(quickAction.title) { quickAction.run() }
            }
        }
        .accessibilityLabel(accessibility + (isNew ? ", new" : ""))
        .accessibilityHint("Shows a preview at the top")
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityActions {
            if let quickAction {
                Button(quickAction.title) { quickAction.run() }
            }
        }
    }

    @ViewBuilder private var statusView: some View {
        switch status {
        case .price(let price, let affordable):
            HStack(spacing: 4) {
                PixelItem(name: "coin").frame(width: 14, height: 14)
                Text(price, format: .number)
                    .font(.subheadline.weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(affordable ? Theme.ink : Theme.inkSoft)
            }
        case .free:
            Text("Free")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Theme.accent)
        case .owned:
            Text("Owned")
                .font(.subheadline)
                .foregroundStyle(Theme.inkSoft)
        case .active(let text):
            Label(text, systemImage: "checkmark.circle.fill")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Theme.accent)
        case .locked(let text):
            Label(text, systemImage: "lock.fill")
                .font(.subheadline)
                .foregroundStyle(Theme.inkSoft)
        }
    }
}

/// A crate in the shop: its price, how many are waiting, Buy and Open.
private struct ShopCrateCard: View {
    let kind: CrateKind
    let owned: Int
    let coins: Int
    let buy: () -> Void
    let open: () -> Void

    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        Card {
            HStack(alignment: .top, spacing: 14) {
                CrateSprite(kind: kind)
                    .padding(10)
                    .frame(width: 76, height: 76)
                    .background(Theme.well, in: RoundedRectangle(cornerRadius: Theme.smallRadius, style: .continuous))
                VStack(alignment: .leading, spacing: 4) {
                    Text(CrateCopy.name(kind))
                        .font(.headline)
                        .foregroundStyle(Theme.ink)
                    Text(kind.blurb)
                        .font(.footnote)
                        .foregroundStyle(Theme.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                    price
                    Text(ownedText)
                        .font(.subheadline)
                        .foregroundStyle(Theme.inkSoft)
                        .monospacedDigit()
                }
                Spacer(minLength: 0)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(CrateCopy.name(kind))
            .accessibilityValue(spoken)

            if kind.price == nil && owned == 0 {
                Text(CrateCopy.mysteryHint)
                    .font(.footnote)
                    .foregroundStyle(Theme.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if full {
                Text(CrateCopy.shelfFull)
                    .font(.footnote)
                    .foregroundStyle(Theme.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if kind.price != nil || owned > 0 {
                let layout = typeSize.isAccessibilitySize
                    ? AnyLayout(VStackLayout(spacing: 8))
                    : AnyLayout(HStackLayout(spacing: 10))
                layout {
                    if let cost = kind.price {
                        AppButton(title: "Buy", icon: "coin", kind: .secondary, action: buy)
                            .disabled(coins < cost || full)
                            .accessibilityLabel("Buy \(CrateCopy.name(kind).lowercased()) for \(CrateCopy.coins(cost))")
                    }
                    if owned > 0 {
                        AppButton(title: "Open", systemIcon: "shippingbox", action: open)
                            .accessibilityLabel("Open \(CrateCopy.name(kind).lowercased())")
                    }
                }
                .padding(.top, 2)
            }
        }
    }

    /// The price, and how many coins are still missing. A crate that isn't
    /// for sale says so in its blurb.
    @ViewBuilder private var price: some View {
        if let cost = kind.price {
            HStack(spacing: 4) {
                PixelItem(name: "coin").frame(width: 14, height: 14)
                Text(cost, format: .number)
                    .font(.subheadline.weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(coins >= cost ? Theme.ink : Theme.inkSoft)
            }
            if coins < cost {
                Text(CrateCopy.needMore(cost - coins))
                    .font(.footnote)
                    .foregroundStyle(Theme.danger)
            }
        }
    }

    /// A crate for sale with no room left for another; the store says no.
    private var full: Bool { kind.price != nil && owned >= Crates.maxStack }

    private var ownedText: String {
        switch owned {
        case 0: "None yet"
        case 1: "1 to open"
        default: "\(owned) to open"
        }
    }

    private var spoken: String {
        var parts: [String] = []
        if let cost = kind.price {
            parts.append(CrateCopy.coins(cost))
            if coins < cost { parts.append("not enough coins") }
        }
        parts.append(ownedText.lowercased())
        var text = "\(parts.joined(separator: ", ")). \(kind.blurb)"
        if full { text += " \(CrateCopy.shelfFull)" }
        return text
    }
}

/// An item's icon. Effects have no sprite of their own, so they show what
/// they make float around the slime.
private struct ShopItemArt: View {
    let item: CatalogItem
    var size: CGFloat = 64

    var body: some View {
        if item.kind == .effect {
            ZStack {
                RoundedRectangle(cornerRadius: size * 0.26, style: .continuous).fill(Theme.well)
                effect.padding(size * 0.22)
            }
            .frame(width: size, height: size)
        } else {
            ItemIcon(name: item.sprite, size: size)
        }
    }

    @ViewBuilder private var effect: some View {
        switch item.id {
        case "effect.hearts": PixelItem(name: "heart")
        case "effect.bubbles": PixelItem(name: "bubbleItem")
        default: IconShape(rows: SlimeSprites.emoteIcons["sparkle"] ?? []).fill(Theme.gold)
        }
    }
}

/// The slime in another colour, wearing what it wears now.
private struct ShopSkinArt: View {
    let skin: Skin
    let slime: Slime

    var body: some View {
        let frames = SlimeSprites.frames(slime.stage, .idle)
        let anchors = SlimeSprites.anchors(slime.stage, .idle)
        PixelSlime(frame: frames.first ?? SlimeSprites.blank, skin: skin, accessories: slime.equipped,
                   stage: slime.stage, anchor: anchors.first)
    }
}

// MARK: - Shared with the kitchen, journal and photo booth

/// How something is unlocked, in words.
enum UnlockText {
    static func short(_ unlock: Unlock) -> String {
        switch unlock {
        case .level(let n): "Level \(n)"
        case .achievement: "Achievement"
        case .bond(let n): "Bond \(n)"
        case .season(let month): monthName(month)
        }
    }

    static func long(_ unlock: Unlock) -> String {
        switch unlock {
        case .level(let n):
            "Unlocks at level \(n)."
        case .achievement(let id):
            "Unlocks with the achievement \u{201C}\(Achievements.achievement(id)?.title ?? id)\u{201D}."
        case .bond(let n):
            "Unlocks at a bond of \(n)."
        case .season(let month):
            "Only for sale in \(monthName(month))."
        }
    }

    /// Seasonal unlocks are Gregorian months, named in English like the rest
    /// of the app, whatever calendar the phone uses.
    private static func monthName(_ month: Int) -> String {
        let names = gregorianMonths
        return names.indices.contains(month - 1) ? names[month - 1] : "season"
    }

    private static let gregorianMonths: [String] = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = Locale(identifier: "en_US_POSIX")
        return calendar.standaloneMonthSymbols
    }()
}

/// A habitat filling its frame edge to edge: drawn at the next whole pixel
/// scale and cropped in the middle, so the pixels stay square and crisp.
struct CroppedHabitat: View {
    let habitat: Habitat
    var furniture: [FurnitureSlot: String] = [:]
    var date: Date = .now
    var animate = false

    var body: some View {
        GeometryReader { geo in
            let grid = CGFloat(HabitatScene.gridWidth)
            let scale = max(2, (geo.size.width / grid).rounded(.up))
            HabitatScene(habitat: habitat, furniture: furniture, date: date, animate: animate)
                .frame(width: scale * grid, height: geo.size.height)
                .frame(width: geo.size.width, height: geo.size.height)
        }
        .clipped()
        .accessibilityHidden(true)
    }
}

/// The slime standing in one of its places, with what it just said. Used at
/// the top of the shop and the kitchen.
struct SlimeShowcase: View {
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    let habitat: Habitat
    var furniture: [FurnitureSlot: String] = [:]
    /// Shows this slime instead of the player's, e.g. trying something on.
    var slime: Slime? = nil
    var line: String? = nil
    var height: CGFloat = 180
    var slimeHeight: CGFloat = 84

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            GeometryReader { geo in
                let grid = CGFloat(HabitatScene.gridWidth)
                let scale = max(2, (geo.size.width / grid).rounded(.up))
                let rows = Int((height / scale).rounded(.up))
                let floor = CGFloat(Int(Double(rows) * 0.66)) * scale
                let feet = min(height - 4, floor + 10 * scale)
                ZStack(alignment: .top) {
                    CroppedHabitat(habitat: habitat, furniture: furniture, animate: !reduceMotion)
                    VStack(spacing: 2) {
                        if let line, !typeSize.isAccessibilitySize {
                            SpeechBox(text: line)
                                .frame(maxWidth: geo.size.width * 0.82)
                                .dynamicTypeSize(...DynamicTypeSize.xLarge)
                                .id(line)
                                .transition(.opacity)
                        }
                        LiveSlimeView(height: slimeHeight, interactive: false, override: slime)
                    }
                    .frame(width: geo.size.width, height: feet, alignment: .bottom)
                }
            }
            .frame(height: height)
            .clipShape(RoundedRectangle(cornerRadius: Theme.radius, style: .continuous))

            if let line, typeSize.isAccessibilitySize {
                Text(line)
                    .font(.body)
                    .foregroundStyle(Theme.ink)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityLabel("Slime says: \(line)")
            }
        }
        .animation(.easeOut(duration: 0.2), value: line)
    }
}
