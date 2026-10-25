import SwiftUI

/// The slime's diary: today's tasks, achievements, memories, collection
/// and who the slime is.
struct JournalView: View {
    @EnvironmentObject private var store: GameStore
    @Environment(\.dismiss) private var dismiss

    enum Tab: Hashable { case tasks, achievements, memories, collection, about }
    @State private var tab: Tab = .tasks

    var body: some View {
        ZStack {
            MenuBackdrop()
            ScrollView {
                VStack(spacing: 14) {
                    MenuHeader(title: "Journal", subtitle: store.state.slime.name) { dismiss() }
                    PixelTabs(options: [(Tab.tasks, "Challenges"), (.achievements, "Achievements"), (.memories, "Memories"),
                                        (.collection, "Collection"), (.about, "About")],
                              selection: $tab)
                    switch tab {
                    case .tasks: TasksSection()
                    case .achievements: AchievementsSection()
                    case .memories: MemoriesSection()
                    case .collection: CollectionSection()
                    case .about: AboutSection()
                    }
                }
                .padding(16)
            }
        }
        .presentationDetents([.large])
        .onAppear { store.tick() }
    }
}

private struct TasksSection: View {
    @EnvironmentObject private var store: GameStore

    var body: some View {
        let board = store.state.progress.challenges
        VStack(spacing: 12) {
            PixelWindow(title: "Today") {
                ForEach(board.daily) { c in ChallengeRow(challenge: c) }
            }
            PixelWindow(title: "This week") {
                ForEach(board.weekly) { c in ChallengeRow(challenge: c) }
            }
            Text("New challenges every morning and every Monday.")
                .font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
        }
    }
}

private struct ChallengeRow: View {
    @EnvironmentObject private var store: GameStore
    let challenge: ChallengeState

    var body: some View {
        let template = Challenges.template(challenge.id)
        let p = Challenges.progress(challenge, in: store.state)
        let done = p.value >= p.target
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 4) {
                Text(template?.title ?? challenge.id).font(Theme.label(15)).foregroundStyle(Theme.ink)
                HStack(spacing: 6) {
                    BlockBar(value: Double(p.value) / Double(max(1, p.target)), color: Theme.accent, blocks: 8, height: 6)
                        .frame(maxWidth: 120)
                    Text("\(p.value)/\(p.target)").font(Theme.label(12)).foregroundStyle(Theme.inkSoft)
                }
            }
            Spacer()
            if challenge.claimed {
                Text("done").font(Theme.label(13)).foregroundStyle(Theme.accentInk)
            } else if done {
                PixelButton(title: "+\(template?.coins ?? 0)", icon: "coin", compact: true) {
                    store.apply({ $0.claimChallenge(challenge.id, now: .now) }, sound: .coin)
                }
            } else {
                HStack(spacing: 3) {
                    PixelItem(name: "coin").frame(width: 12, height: 12)
                    Text("\(template?.coins ?? 0)").font(Theme.label(13)).foregroundStyle(Theme.inkSoft)
                }
            }
        }
        .padding(.vertical, 4)
    }
}

private struct AchievementsSection: View {
    @EnvironmentObject private var store: GameStore

    var body: some View {
        let unlocked = store.state.progress.achievements
        PixelWindow(title: "\(unlocked.count) of \(Achievements.all.count)") {
            ForEach(Achievements.all) { a in
                let date = unlocked[a.id]
                HStack(spacing: 10) {
                    PixelItem(name: date == nil ? "rock" : "furn.trophy")
                        .frame(width: 26, height: 26)
                        .opacity(date == nil ? 0.5 : 1)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(a.title).font(Theme.label(15)).foregroundStyle(date == nil ? Theme.inkSoft : Theme.ink)
                        Text(a.detail).font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
                    }
                    Spacer()
                    if let date {
                        Text(date.formatted(.dateTime.day().month(.abbreviated))).font(Theme.label(12)).foregroundStyle(Theme.accentInk)
                    } else {
                        Text("+\(a.coins)").font(Theme.label(12)).foregroundStyle(Theme.inkSoft)
                    }
                }
                .padding(.vertical, 3)
                .accessibilityElement(children: .combine)
            }
        }
    }
}

private struct MemoriesSection: View {
    @EnvironmentObject private var store: GameStore

    var body: some View {
        let keepsakes = store.state.memory.keepsakes.reversed()
        PixelWindow(title: "Special moments") {
            if keepsakes.isEmpty {
                Text("Your special moments end up here.").font(Theme.text(14)).foregroundStyle(Theme.inkSoft)
            }
            ForEach(Array(keepsakes)) { entry in
                HStack(alignment: .top, spacing: 10) {
                    PixelItem(name: icon(entry)).frame(width: 24, height: 24)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(describe(entry)).font(Theme.label(15)).foregroundStyle(Theme.ink)
                        Text(entry.at.formatted(date: .abbreviated, time: .omitted)).font(Theme.text(12)).foregroundStyle(Theme.inkSoft)
                    }
                }
                .padding(.vertical, 3)
                .accessibilityElement(children: .combine)
            }
        }
    }

    private func highScoreText(_ detail: String) -> String {
        let parts = detail.split(separator: ":")
        let game = GameID(rawValue: String(parts.first ?? ""))?.title ?? "a game"
        return "Record in \(game): \(parts.last ?? "")"
    }

    private func icon(_ e: MemoryEntry) -> String {
        switch e.kind {
        case .hatched: "starJelly"
        case .favoriteFound: Food(rawValue: e.detail)?.sprite ?? "apple"
        case .highScore: "furn.trophy"
        case .levelUp, .stageUp: "coin"
        case .achievement: "furn.trophy"
        case .giftReceived, .gift: "chest"
        case .friendMade, .inLove: "heart"
        default: "flower"
        }
    }

    private func describe(_ e: MemoryEntry) -> String {
        switch e.kind {
        case .hatched: "\(e.detail) hatched"
        case .favoriteFound: "Found its favourite food: \(Food(rawValue: e.detail)?.title ?? e.detail)"
        case .highScore: highScoreText(e.detail)
        case .levelUp: "Reached level \(e.detail)"
        case .stageUp: "Grew up: \(Stage(rawValue: e.detail)?.title ?? e.detail)"
        case .achievement: "Achievement: \(Achievements.achievement(e.detail)?.title ?? e.detail)"
        case .giftReceived: "First gift from \(e.detail)"
        case .friendMade: "New friend: \(e.detail)"
        case .inLove: "In love with \(e.detail)"
        case .reunion: "Back together after \(e.detail) days"
        default: e.kind.rawValue
        }
    }
}

private struct CollectionSection: View {
    @EnvironmentObject private var store: GameStore

    var body: some View {
        let s = store.state
        VStack(spacing: 12) {
            PixelWindow(title: "Food") {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 5), spacing: 10) {
                    ForEach(Food.allCases) { food in
                        let tasted = s.inventory.foodsTasted.contains(food)
                        VStack(spacing: 2) {
                            PixelItem(name: food.sprite).frame(width: 30, height: 30)
                                .saturation(tasted ? 1 : 0).opacity(tasted ? 1 : 0.35)
                            Text(tasted ? food.title : "???").font(Theme.label(10)).foregroundStyle(Theme.inkSoft).lineLimit(1)
                        }
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(tasted ? food.title : "Not tried yet")
                    }
                }
            }
            PixelWindow(title: "Records") {
                ForEach(GameID.classics) { game in
                    let record = s.record(game)
                    HStack {
                        PixelItem(name: game.icon).frame(width: 20, height: 20)
                        Text(game.title).font(Theme.label(14)).foregroundStyle(Theme.ink)
                        Spacer()
                        Text(record.plays == 0 ? "–" : "\(record.best)").font(Theme.label(14)).foregroundStyle(Theme.inkSoft)
                    }
                }
            }
        }
    }
}

private struct AboutSection: View {
    @EnvironmentObject private var store: GameStore

    var body: some View {
        let s = store.state.slime
        let stats = store.state.progress.stats
        VStack(spacing: 12) {
            PixelWindow(title: "Who is \(s.name)?") {
                HStack(spacing: 12) {
                    LiveSlimeView(height: 80, interactive: false)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("\(s.stage.title), day \(s.ageDays() + 1)").font(Theme.label(15)).foregroundStyle(Theme.ink)
                        Text("It's \(s.personality.top.map(\.title).joined(separator: " and ")).")
                            .font(Theme.text(14)).foregroundStyle(Theme.ink)
                        Text(s.personality.dominant.blurb).font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
                    }
                }
                ForEach(Personality.Trait.allCases, id: \.self) { trait in
                    HStack {
                        Text(trait.title).font(Theme.label(13)).foregroundStyle(Theme.ink).frame(width: 100, alignment: .leading)
                        BlockBar(value: s.personality[trait], color: Theme.sky, blocks: 10, height: 6)
                    }
                }
            }
            PixelWindow(title: "Your bond") {
                HStack {
                    Text(s.bondStage.title).font(Theme.label(16)).foregroundStyle(Theme.love)
                    Spacer()
                    Text("\(Int(s.bond)) / 100").font(Theme.label(13)).foregroundStyle(Theme.inkSoft)
                }
                BlockBar(value: s.bond / 100, color: Theme.love)
                Text("Grows with petting, feeding, baths and playing together. It never goes down.")
                    .font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
            }
            PixelWindow(title: "Numbers") {
                statRow("Fed", stats.feeds)
                statRow("Petted", stats.pets)
                statRow("Bathed", stats.baths)
                statRow("Games", stats.plays)
                statRow("Favourite game", nil, text: s.favoriteGame.title)
                statRow("Coins earned", store.state.wallet.earnedTotal)
            }
        }
    }

    private func statRow(_ title: String, _ value: Int?, text: String? = nil) -> some View {
        HStack {
            Text(title).font(Theme.text(14)).foregroundStyle(Theme.ink)
            Spacer()
            Text(text ?? "\(value ?? 0)").font(Theme.label(14)).foregroundStyle(Theme.inkSoft)
        }
    }
}
