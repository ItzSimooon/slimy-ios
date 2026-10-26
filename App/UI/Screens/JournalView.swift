import SwiftUI

/// The slime's diary: today's challenges, achievements, memories, the
/// collection and who the slime is. The photo booth opens from here.
struct JournalView: View {
    @EnvironmentObject private var store: GameStore

    enum Tab: Hashable { case challenges, achievements, memories, collection, about }

    @State private var tab: Tab = .challenges
    @State private var showBooth = false

    private var tabs: [(Tab, String)] {
        [(.challenges, "Challenges"), (.achievements, "Achievements"), (.memories, "Memories"),
         (.collection, "Collection"), (.about, "About")]
    }

    var body: some View {
        let name = store.state.slime.name
        NavigationStack {
            List {
                Section {
                    JournalHeader()
                    Button {
                        Feedback.shared.play(.tap)
                        showBooth = true
                    } label: {
                        Label("Photo Booth", systemImage: "camera")
                    }
                    .accessibilityHint("Makes a picture of \(name) to share")
                }

                Section {
                    ChipTabs(options: tabs, selection: $tab)
                        .contentMargins(.horizontal, 16, for: .scrollContent)
                        .listRowInsets(EdgeInsets(top: 2, leading: 0, bottom: 2, trailing: 0))
                        .listRowBackground(Color.clear)
                }

                switch tab {
                case .challenges: ChallengesContent()
                case .achievements: AchievementsContent()
                case .memories: MemoriesContent()
                case .collection: CollectionContent()
                case .about: AboutContent()
                }
            }
            .listStyle(.insetGrouped)
            .listSectionSpacing(.compact)
            .sheetTitle("Journal")
            .sheet(isPresented: $showBooth) {
                PhotoBoothView()
            }
        }
        .onAppear { store.tick() }
    }
}

// MARK: - Header

private struct JournalHeader: View {
    @EnvironmentObject private var store: GameStore
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let slime = store.state.slime
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 10))
            : AnyLayout(HStackLayout(spacing: 14))
        layout {
            LiveSlimeView(height: 64, interactive: false)
            VStack(alignment: .leading, spacing: 6) {
                Text(slime.name)
                    .font(.title2.weight(.bold))
                    .foregroundStyle(Theme.ink)
                    .lineLimit(2)
                Text("\(slime.stage.title) · Level \(slime.level) · Day \(slime.ageDays() + 1)")
                    .font(.subheadline)
                    .foregroundStyle(Theme.inkSoft)
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text("Bond")
                        Spacer()
                        Text(slime.bondStage.title)
                    }
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Theme.inkSoft)
                    Meter(value: slime.bond / 100, color: Theme.love)
                }
                .padding(.top, 2)
            }
        }
        .padding(.vertical, 6)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(slime.name), \(slime.stage.title), level \(slime.level), day \(slime.ageDays() + 1)")
        .accessibilityValue("Bond: \(slime.bondStage.title), \(Int(slime.bond)) of 100")
    }
}

// MARK: - Challenges

private struct ChallengesContent: View {
    @EnvironmentObject private var store: GameStore

    var body: some View {
        let s = store.state
        let board = s.progress.challenges
        let claimable = (board.daily + board.weekly).filter { !$0.claimed && Challenges.isDone($0, in: s) }
        let claimableCoins = claimable.reduce(0) { $0 + (Challenges.template($1.id)?.coins ?? 0) }

        if claimable.count > 1 {
            Section {
                Button {
                    claimAll(claimable)
                } label: {
                    HStack(spacing: 10) {
                        PixelItem(name: "coin").frame(width: 20, height: 20)
                        Text("Claim all rewards")
                            .font(.body.weight(.semibold))
                        Spacer()
                        Text(verbatim: "+\(claimableCoins)")
                            .font(.body.weight(.semibold))
                            .monospacedDigit()
                    }
                }
                .accessibilityLabel("Claim all rewards, \(claimableCoins) coins")
            }
        }

        Section {
            if board.daily.isEmpty {
                Text("Today's challenges show up in the morning.")
                    .foregroundStyle(Theme.inkSoft)
            } else {
                ForEach(board.daily) { JournalChallengeRow(challenge: $0) }
            }
        } header: {
            Text("Today")
        } footer: {
            Text("New challenges every morning.")
        }

        Section {
            if board.weekly.isEmpty {
                Text("This week's challenges show up on Monday.")
                    .foregroundStyle(Theme.inkSoft)
            } else {
                ForEach(board.weekly) { JournalChallengeRow(challenge: $0) }
            }
        } header: {
            Text("This week")
        } footer: {
            Text("New weekly challenges every Monday.")
        }
    }

    private func claimAll(_ challenges: [ChallengeState]) {
        for challenge in challenges {
            store.apply({ $0.claimChallenge(challenge.id, now: .now) }, sound: .coin)
        }
    }
}

private struct JournalChallengeRow: View {
    @EnvironmentObject private var store: GameStore
    let challenge: ChallengeState

    var body: some View {
        let template = Challenges.template(challenge.id)
        let p = Challenges.progress(challenge, in: store.state)
        let done = p.value >= p.target
        let coins = template?.coins ?? 0
        let title = template?.title ?? "Challenge"
        let status: String = challenge.claimed ? "Done, reward claimed"
            : done ? "Done, \(coins) coins to claim"
            : "\(p.value) of \(p.target), reward \(coins) coins"

        HStack(spacing: 12) {
            ItemIcon(name: icon(template), size: 40)
            VStack(alignment: .leading, spacing: 6) {
                Text(title)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Theme.ink)
                    .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 8) {
                    Meter(value: Double(p.value) / Double(max(1, p.target)), color: done ? Theme.accent : Theme.sky)
                        .frame(maxWidth: 140)
                    Text(verbatim: "\(p.value)/\(p.target)")
                        .font(.caption.weight(.semibold))
                        .monospacedDigit()
                        .foregroundStyle(Theme.inkSoft)
                }
            }
            Spacer(minLength: 8)
            if challenge.claimed {
                Image(systemName: "checkmark.circle.fill")
                    .font(.title3)
                    .foregroundStyle(Theme.accent)
            } else if done {
                AppButton(title: "+\(coins)", icon: "coin", compact: true) { claim() }
            } else {
                HStack(spacing: 4) {
                    PixelItem(name: "coin").frame(width: 14, height: 14)
                    Text(coins, format: .number)
                        .font(.subheadline.weight(.semibold))
                        .monospacedDigit()
                        .foregroundStyle(Theme.inkSoft)
                }
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue(status)
        .accessibilityActions {
            if done && !challenge.claimed {
                Button("Claim \(coins) coins") { claim() }
            }
        }
    }

    private func claim() {
        store.apply({ $0.claimChallenge(challenge.id, now: .now) }, sound: .coin)
    }

    private func icon(_ template: ChallengeTemplate?) -> String {
        guard let template else { return "coin" }
        if case .score(let game, _) = template.goal { return game.icon }
        if case .stat(let key, _) = template.goal { return Self.statIcons[key] ?? "coin" }
        return "coin"
    }

    private static let statIcons: [StatKey: String] = [
        .feeds: "apple", .pets: "heart", .baths: "bubbleItem", .plays: "ball", .goodGames: "furn.trophy",
        .sleeps: "starJelly", .giftsSent: "chest", .giftsReceived: "chest", .eventsSeen: "speech",
        .friends: "heart", .hugs: "heart", .visits: "flower", .purchases: "coin",
    ]
}

// MARK: - Achievements

private struct AchievementsContent: View {
    @EnvironmentObject private var store: GameStore

    var body: some View {
        let unlocked = store.state.progress.achievements
        let total = Achievements.all.count
        let done = Achievements.all
            .compactMap { a in unlocked[a.id].map { UnlockedAchievement(achievement: a, date: $0) } }
            .sorted { $0.date > $1.date }
        let open = Achievements.all.filter { unlocked[$0.id] == nil }
        let earned = done.reduce(0) { $0 + $1.achievement.coins }

        Section {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("\(done.count) of \(total) unlocked")
                        .font(.headline)
                        .foregroundStyle(Theme.ink)
                    Spacer()
                    HStack(spacing: 4) {
                        PixelItem(name: "coin").frame(width: 14, height: 14)
                        Text(earned, format: .number)
                            .font(.subheadline.weight(.semibold))
                            .monospacedDigit()
                            .foregroundStyle(Theme.inkSoft)
                    }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("\(earned) coins earned")
                }
                Meter(value: Double(done.count) / Double(max(1, total)), color: Theme.accent, height: 8)
            }
            .padding(.vertical, 4)
            .accessibilityElement(children: .combine)
        }

        if !done.isEmpty {
            Section("Unlocked") {
                ForEach(done) { item in
                    AchievementRow(achievement: item.achievement, date: item.date)
                }
            }
        }

        if !open.isEmpty {
            Section("Still to do") {
                ForEach(open) { achievement in
                    AchievementRow(achievement: achievement, date: nil)
                }
            }
        }
    }
}

private struct UnlockedAchievement: Identifiable {
    let achievement: Achievement
    let date: Date
    var id: String { achievement.id }
}

private struct AchievementRow: View {
    let achievement: Achievement
    let date: Date?

    /// A hidden achievement stays a secret until it's earned.
    private var secret: Bool { achievement.hidden && date == nil }
    private var title: String { secret ? "???" : achievement.title }
    private var detail: String { secret ? "A secret. It shows itself once it's earned." : achievement.detail }

    private var reward: String {
        guard let crate = achievement.crate else { return CrateCopy.coins(achievement.coins) }
        return "\(CrateCopy.coins(achievement.coins)) and a \(CrateCopy.name(crate).lowercased())"
    }

    private var status: String {
        if let date { return "Unlocked \(date.formatted(date: .long, time: .omitted))" }
        return "Locked, reward \(reward)"
    }

    var body: some View {
        HStack(spacing: 12) {
            ItemIcon(name: "furn.trophy", size: 40)
                .saturation(date == nil ? 0 : 1)
                .opacity(date == nil ? 0.45 : 1)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Theme.ink)
                Text(detail)
                    .font(.subheadline)
                    .foregroundStyle(Theme.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 8)
            if let date {
                Text(date.formatted(date: .abbreviated, time: .omitted))
                    .font(.caption)
                    .foregroundStyle(Theme.inkSoft)
                    .multilineTextAlignment(.trailing)
            } else {
                VStack(alignment: .trailing, spacing: 4) {
                    HStack(spacing: 4) {
                        PixelItem(name: "coin").frame(width: 14, height: 14)
                        Text(achievement.coins, format: .number)
                            .font(.subheadline.weight(.semibold))
                            .monospacedDigit()
                            .foregroundStyle(Theme.inkSoft)
                    }
                    if let crate = achievement.crate {
                        CrateSprite(kind: crate)
                            .frame(width: 20, height: 20)
                    }
                }
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(secret ? "Secret achievement" : "\(achievement.title). \(achievement.detail)")
        .accessibilityValue(status)
    }
}

// MARK: - Memories

private struct MemoriesContent: View {
    @EnvironmentObject private var store: GameStore

    var body: some View {
        let entries = store.state.memory.keepsakes.sorted { $0.at > $1.at }
        let months = Self.months(entries)

        if entries.isEmpty {
            Section {
                ContentUnavailableView {
                    Label("No memories yet", systemImage: "book.closed")
                } description: {
                    Text("Big moments end up here: a favourite food found, records, new friends and growing up.")
                }
            }
        } else {
            ForEach(months) { month in
                Section(month.start.formatted(.dateTime.month(.wide).year())) {
                    ForEach(month.entries) { MemoryRow(entry: $0) }
                }
            }
        }
    }

    private static func months(_ entries: [MemoryEntry]) -> [MemoryMonth] {
        let calendar = Calendar.current
        var out: [MemoryMonth] = []
        for entry in entries {
            let start = calendar.dateInterval(of: .month, for: entry.at)?.start ?? entry.at
            if let last = out.indices.last, out[last].start == start {
                out[last].entries.append(entry)
            } else {
                out.append(MemoryMonth(start: start, entries: [entry]))
            }
        }
        return out
    }
}

private struct MemoryMonth: Identifiable {
    let start: Date
    var entries: [MemoryEntry]
    var id: Date { start }
}

private struct MemoryRow: View {
    let entry: MemoryEntry

    var body: some View {
        HStack(spacing: 12) {
            icon
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.summary)
                    .font(.body)
                    .foregroundStyle(Theme.ink)
                    .fixedSize(horizontal: false, vertical: true)
                Text(entry.at.formatted(date: .abbreviated, time: .omitted))
                    .font(.caption)
                    .foregroundStyle(Theme.inkSoft)
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }

    /// Crates are drawn like everywhere else (their sprites leave room
    /// above the lid), and effects, which have no sprite, show what floats
    /// around the slime.
    @ViewBuilder private var icon: some View {
        if let kind = CrateKind.allCases.first(where: { $0.sprite == entry.icon }) {
            CrateSprite(kind: kind)
                .padding(7)
                .frame(width: 40, height: 40)
                .background(Theme.well, in: RoundedRectangle(cornerRadius: 40 * 0.26, style: .continuous))
        } else if let item = Catalog.item(entry.icon), item.kind == .effect {
            CollectionItemIcon(item: item, size: 40)
        } else {
            ItemIcon(name: entry.icon, size: 40)
        }
    }
}

// MARK: - Collection

private struct CollectionContent: View {
    @EnvironmentObject private var store: GameStore
    @ScaledMetric(relativeTo: .caption) private var cell: CGFloat = 64

    var body: some View {
        let s = store.state
        let tasted = s.inventory.foodsTasted
        let wardrobe = Catalog.items.filter { $0.kind != .furniture }
        let furniture = Catalog.items(.furniture)
        // Secret colours live in the legendary slot under Crates.
        let colours = Skin.allCases.filter { !$0.isSecret }
        let skins = colours.filter { s.slime.isUnlocked($0) }

        Section {
            grid {
                ForEach(Food.allCases) { food in
                    let have = tasted.contains(food)
                    CollectionCell(title: have ? food.title : "???", have: have,
                                   accessibility: have ? food.title : "Not tried yet") {
                        ItemIcon(name: food.sprite, size: 44)
                            .overlay(alignment: .topTrailing) {
                                if have && s.inventory.favoriteFound && food == s.slime.favorite {
                                    PixelItem(name: "heart").frame(width: 12, height: 10).offset(x: 4, y: -3)
                                }
                            }
                    }
                }
            }
        } header: {
            countHeader("Food", tasted.count, Food.allCases.count)
        }

        Section {
            grid {
                ForEach(wardrobe) { item in
                    let have = s.owns(item.id)
                    CollectionCell(title: item.title, have: have,
                                   accessibility: "\(item.title), \(have ? "owned" : "not owned yet")") {
                        CollectionItemIcon(item: item)
                    }
                }
            }
        } header: {
            countHeader("Wardrobe", wardrobe.filter { s.owns($0.id) }.count, wardrobe.count)
        }

        Section {
            grid {
                ForEach(furniture) { item in
                    let have = s.owns(item.id)
                    CollectionCell(title: item.title, have: have,
                                   accessibility: "\(item.title), \(have ? "owned" : "not owned yet")") {
                        ItemIcon(name: item.sprite, size: 44)
                    }
                }
            }
        } header: {
            countHeader("Furniture", furniture.filter { s.owns($0.id) }.count, furniture.count)
        }

        Section {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: cell * 1.7), spacing: 10)], spacing: 12) {
                ForEach(Habitat.allCases) { habitat in
                    let have = s.inventory.habitats.contains(habitat)
                    CollectionCell(title: habitat.title, have: have,
                                   accessibility: "\(habitat.title), \(have ? "owned" : "not owned yet")") {
                        CroppedHabitat(habitat: habitat, furniture: habitat == .room ? s.inventory.furniture : [:])
                            .frame(height: 56)
                            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    }
                }
            }
            .padding(.vertical, 8)
        } header: {
            countHeader("Places", s.inventory.habitats.count, Habitat.allCases.count)
        }

        Section {
            grid {
                ForEach(colours) { skin in
                    let have = s.slime.isUnlocked(skin)
                    CollectionCell(title: skin.title, have: have,
                                   accessibility: "\(skin.title), \(have ? "unlocked" : "unlocks at level \(skin.unlockLevel)")") {
                        CollectionSkin(skin: skin, stage: s.slime.stage)
                            .frame(width: 53, height: 44)
                    }
                }
            }
        } header: {
            countHeader("Colours", skins.count, colours.count)
        }

        CratesCollection(state: s)

        Section("Records") {
            ForEach(GameID.classics) { game in
                let record = s.record(game)
                let rounds: String = record.plays == 0 ? "Not played yet"
                    : "\(record.plays) \(record.plays == 1 ? "round" : "rounds")"
                HStack(spacing: 12) {
                    ItemIcon(name: game.icon, size: 36)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(game.title)
                            .font(.body)
                            .foregroundStyle(Theme.ink)
                        Text(rounds)
                            .font(.caption)
                            .foregroundStyle(Theme.inkSoft)
                    }
                    Spacer(minLength: 8)
                    if record.plays > 0 {
                        Text(record.best, format: .number)
                            .font(.headline)
                            .monospacedDigit()
                            .foregroundStyle(Theme.ink)
                    }
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(game.title)
                .accessibilityValue(record.plays == 0 ? rounds : "Best \(record.best), \(rounds)")
            }
        }
    }

    private func grid<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: cell), spacing: 8)], spacing: 12) {
            content()
        }
        .padding(.vertical, 8)
    }

    private func countHeader(_ title: String, _ have: Int, _ total: Int) -> some View {
        HStack {
            Text(title)
            Spacer()
            Text(verbatim: "\(have)/\(total)")
                .monospacedDigit()
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(title), \(have) of \(total)")
        .accessibilityAddTraits(.isHeader)
    }
}

/// Crates opened so far, and the legendary slot: a question mark until the
/// secret colour turns up, then the slime wearing it.
private struct CratesCollection: View {
    let state: GameState

    var body: some View {
        let opened = state.progress.stats.cratesOpened
        let waiting = CrateKind.allCases.reduce(0) { $0 + state.crates[$1, default: 0] }
        let waitingText = waiting == 0 ? "None waiting" : (waiting == 1 ? "1 waiting to be opened" : "\(waiting) waiting to be opened")
        let found = Skin.allCases.first { $0.isSecret && state.slime.isUnlocked($0) }

        Section {
            HStack(spacing: 12) {
                CrateSprite(kind: .golden)
                    .padding(7)
                    .frame(width: 40, height: 40)
                    .background(Theme.well, in: RoundedRectangle(cornerRadius: 40 * 0.26, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text("Crates opened")
                        .font(.body)
                        .foregroundStyle(Theme.ink)
                    Text(waitingText)
                        .font(.caption)
                        .foregroundStyle(Theme.inkSoft)
                }
                Spacer(minLength: 8)
                Text(opened, format: .number)
                    .font(.headline)
                    .monospacedDigit()
                    .foregroundStyle(Theme.ink)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Crates opened")
            .accessibilityValue("\(opened), \(waitingText.lowercased())")

            HStack(spacing: 12) {
                legendaryArt(found)
                VStack(alignment: .leading, spacing: 4) {
                    RarityBadge(rarity: .legendary, compact: true)
                    Text(found?.title ?? "???")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(Theme.ink)
                    Text(found == nil ? "Still a secret. Some crates hold it, very rarely."
                                      : "Found in a crate. Wear it any time from the shop.")
                        .font(.caption)
                        .foregroundStyle(Theme.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.vertical, 2)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Legendary")
            .accessibilityValue(found.map { "\($0.title), found" } ?? "Still a secret")
        } header: {
            Text("Crates")
        }
    }

    @ViewBuilder
    private func legendaryArt(_ found: Skin?) -> some View {
        if let found {
            CollectionSkin(skin: found, stage: state.slime.stage)
                .frame(width: 44, height: 37)
                .frame(width: 56, height: 52)
                .background(Rarity.legendary.displayColor.opacity(0.2),
                            in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        } else {
            Image(systemName: "questionmark")
                .font(.title3.weight(.bold))
                .foregroundStyle(Theme.inkSoft)
                .frame(width: 56, height: 52)
                .background(Theme.well, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
    }
}

private struct CollectionCell<Art: View>: View {
    let title: String
    let have: Bool
    let accessibility: String
    @ViewBuilder let art: Art

    var body: some View {
        VStack(spacing: 4) {
            art
                .saturation(have ? 1 : 0)
                .opacity(have ? 1 : 0.35)
            Text(title)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(have ? Theme.ink : Theme.inkSoft)
                .multilineTextAlignment(.center)
                .lineLimit(2, reservesSpace: true)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibility)
    }
}

/// Accessories show their sprite; effects show what floats around the slime.
private struct CollectionItemIcon: View {
    let item: CatalogItem
    var size: CGFloat = 44

    var body: some View {
        if item.kind == .effect {
            ZStack {
                RoundedRectangle(cornerRadius: size * 0.26, style: .continuous).fill(Theme.well)
                Group {
                    switch item.id {
                    case "effect.hearts": PixelItem(name: "heart")
                    case "effect.bubbles": PixelItem(name: "bubbleItem")
                    default: IconShape(rows: SlimeSprites.emoteIcons["sparkle"] ?? []).fill(Theme.gold)
                    }
                }
                .padding(size * 0.23)
            }
            .frame(width: size, height: size)
            .accessibilityHidden(true)
        } else {
            ItemIcon(name: item.sprite, size: size)
        }
    }
}

private struct CollectionSkin: View {
    let skin: Skin
    let stage: Stage

    var body: some View {
        let frames = SlimeSprites.frames(stage, .idle)
        let anchors = SlimeSprites.anchors(stage, .idle)
        PixelSlime(frame: frames.first ?? SlimeSprites.blank, skin: skin, stage: stage, anchor: anchors.first)
    }
}

// MARK: - About

private struct AboutContent: View {
    @EnvironmentObject private var store: GameStore

    var body: some View {
        let s = store.state
        let slime = s.slime
        let stats = s.progress.stats
        let traits = slime.personality.top.map(\.title)

        Section {
            VStack(alignment: .leading, spacing: 4) {
                Text("\(slime.name) is \(traits.joined(separator: " and ")).")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Theme.ink)
                Text(slime.personality.dominant.blurb)
                    .font(.subheadline)
                    .foregroundStyle(Theme.inkSoft)
            }
            .padding(.vertical, 2)
            .accessibilityElement(children: .combine)

            ForEach(Personality.Trait.allCases, id: \.self) { trait in
                let value = min(1, max(0, slime.personality[trait]))
                let title: String = trait.title.prefix(1).uppercased() + trait.title.dropFirst()
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text(title)
                            .font(.subheadline)
                            .foregroundStyle(Theme.ink)
                        Spacer()
                        Text(verbatim: "\(Int((value * 100).rounded()))%")
                            .font(.subheadline)
                            .monospacedDigit()
                            .foregroundStyle(Theme.inkSoft)
                    }
                    Meter(value: value, color: Theme.sky)
                }
                .padding(.vertical, 2)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(title)
                .accessibilityValue("\(Int((value * 100).rounded())) percent")
            }
        } header: {
            Text("Personality")
        } footer: {
            Text("Its personality shifts a little with how you spend time together.")
        }

        Section {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text(slime.bondStage.title)
                        .font(.headline)
                        .foregroundStyle(Theme.ink)
                    Spacer()
                    Text(verbatim: "\(Int(slime.bond)) / 100")
                        .font(.subheadline)
                        .monospacedDigit()
                        .foregroundStyle(Theme.inkSoft)
                }
                Meter(value: slime.bond / 100, color: Theme.love, height: 8)
                if let next = Self.nextBond(slime.bond) {
                    Text("\(next.title) at \(next.at)")
                        .font(.caption)
                        .foregroundStyle(Theme.inkSoft)
                }
            }
            .padding(.vertical, 4)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Bond")
            .accessibilityValue("\(slime.bondStage.title), \(Int(slime.bond)) of 100")
        } header: {
            Text("Your bond")
        } footer: {
            Text("Grows with petting, feeding, baths and playing together. It never goes down.")
        }

        Section("Facts") {
            LabeledContent("Birthday", value: slime.birthday.formatted(date: .long, time: .omitted))
            LabeledContent("Age", value: Self.days(slime.ageDays()))
            LabeledContent("Level") {
                HStack(spacing: 8) {
                    Meter(value: slime.levelProgress, height: 5)
                        .frame(width: 60)
                    Text(slime.level, format: .number)
                        .monospacedDigit()
                }
            }
            LabeledContent("Colour", value: slime.skin.title)
            LabeledContent("Favourite food",
                           value: s.inventory.favoriteFound ? slime.favorite.title : "Still a secret")
            LabeledContent("Favourite game", value: slime.favoriteGame.title)
        }

        Section("Together") {
            Group {
                stat("Meals", stats.feeds)
                stat("Pets", stats.pets)
                stat("Baths", stats.baths)
                stat("Bedtimes", stats.sleeps)
                stat("Rounds played", stats.plays)
                stat("Really good rounds", stats.goodGames)
            }
            stat("Gifts given", stats.giftsSent)
            stat("Gifts received", stats.giftsReceived)
            stat("Friends", stats.friends)
            stat("Coins earned", s.wallet.earnedTotal)
            stat("Days visited", s.player.daysActive.count)
            stat("Longest gift streak", s.streak.best)
        }
    }

    private func stat(_ title: String, _ value: Int) -> some View {
        LabeledContent {
            Text(value, format: .number).monospacedDigit()
        } label: {
            Text(title)
        }
    }

    private static func days(_ n: Int) -> String {
        n == 1 ? "1 day" : "\(n) days"
    }

    private static func nextBond(_ bond: Double) -> (title: String, at: Int)? {
        switch bond {
        case ..<20: (title: "Next: Familiar", at: 20)
        case ..<50: (title: "Next: Close", at: 50)
        case ..<80: (title: "Next: Inseparable", at: 80)
        default: nil
        }
    }
}
