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
                    MenuHeader(title: "Tagebuch", subtitle: store.state.slime.name) { dismiss() }
                    PixelTabs(options: [(Tab.tasks, "Aufgaben"), (.achievements, "Erfolge"), (.memories, "Erinnerungen"),
                                        (.collection, "Sammlung"), (.about, "Über ihn")],
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
            PixelWindow(title: "Heute") {
                ForEach(board.daily) { c in ChallengeRow(challenge: c) }
            }
            PixelWindow(title: "Diese Woche") {
                ForEach(board.weekly) { c in ChallengeRow(challenge: c) }
            }
            Text("Neue Aufgaben gibt es jeden Morgen und jeden Montag.")
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
                Text("erledigt").font(Theme.label(13)).foregroundStyle(Theme.accentInk)
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
        PixelWindow(title: "\(unlocked.count) von \(Achievements.all.count)") {
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
        PixelWindow(title: "Besondere Momente") {
            if keepsakes.isEmpty {
                Text("Hier landen eure besonderen Momente.").font(Theme.text(14)).foregroundStyle(Theme.inkSoft)
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
        let game = GameID(rawValue: String(parts.first ?? ""))?.title ?? "einem Spiel"
        return "Rekord in \(game): \(parts.last ?? "")"
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
        case .hatched: "\(e.detail) ist geschlüpft"
        case .favoriteFound: "Lieblingsessen entdeckt: \(Food(rawValue: e.detail)?.title ?? e.detail)"
        case .highScore: highScoreText(e.detail)
        case .levelUp: "Level \(e.detail) erreicht"
        case .stageUp: "Gewachsen: \(Stage(rawValue: e.detail)?.title ?? e.detail)"
        case .achievement: "Erfolg: \(Achievements.achievement(e.detail)?.title ?? e.detail)"
        case .giftReceived: "Erstes Geschenk von \(e.detail)"
        case .friendMade: "Neuer Freund: \(e.detail)"
        case .inLove: "Verliebt in \(e.detail)"
        case .reunion: "Wiedersehen nach \(e.detail) Tagen"
        default: e.kind.rawValue
        }
    }
}

private struct CollectionSection: View {
    @EnvironmentObject private var store: GameStore

    var body: some View {
        let s = store.state
        VStack(spacing: 12) {
            PixelWindow(title: "Essen") {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 5), spacing: 10) {
                    ForEach(Food.allCases) { food in
                        let tasted = s.inventory.foodsTasted.contains(food)
                        VStack(spacing: 2) {
                            PixelItem(name: food.sprite).frame(width: 30, height: 30)
                                .saturation(tasted ? 1 : 0).opacity(tasted ? 1 : 0.35)
                            Text(tasted ? food.title : "???").font(Theme.label(10)).foregroundStyle(Theme.inkSoft).lineLimit(1)
                        }
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(tasted ? food.title : "Noch nicht probiert")
                    }
                }
            }
            PixelWindow(title: "Rekorde") {
                ForEach(GameID.allCases) { game in
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
            PixelWindow(title: "Wer ist \(s.name)?") {
                HStack(spacing: 12) {
                    LiveSlimeView(height: 80, interactive: false)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("\(s.stage.title), \(s.ageDays() + 1). Tag").font(Theme.label(15)).foregroundStyle(Theme.ink)
                        Text("Ist \(s.personality.top.map(\.title).joined(separator: " und ")).")
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
            PixelWindow(title: "Eure Bindung") {
                HStack {
                    Text(s.bondStage.title).font(Theme.label(16)).foregroundStyle(Theme.love)
                    Spacer()
                    Text("\(Int(s.bond)) / 100").font(Theme.label(13)).foregroundStyle(Theme.inkSoft)
                }
                BlockBar(value: s.bond / 100, color: Theme.love)
                Text("Wächst mit Kraulen, Füttern, Baden und gemeinsamen Spielen. Sie sinkt nie.")
                    .font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
            }
            PixelWindow(title: "Zahlen") {
                statRow("Gefüttert", stats.feeds)
                statRow("Gekrault", stats.pets)
                statRow("Gebadet", stats.baths)
                statRow("Spiele", stats.plays)
                statRow("Lieblingsspiel", nil, text: s.favoriteGame.title)
                statRow("Münzen verdient", store.state.wallet.earnedTotal)
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
