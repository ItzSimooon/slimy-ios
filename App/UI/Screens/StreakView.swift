import SwiftUI
import UIKit

/// The daily gift: a calendar of seven gifts, one for each day the player
/// drops by, and the streak of days in a row. Missing a day never takes a
/// gift away; only the streak starts over.
struct StreakView: View {
    @EnvironmentObject private var store: GameStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var pop = false

    var body: some View {
        NavigationStack {
            // Looked at again every minute, so the next day's gift appears
            // while the screen is open.
            TimelineView(.everyMinute) { context in
                content(store.state.streakInfo(at: context.date))
            }
            .sheetTitle("Daily Gift", inline: true)
        }
        .presentationDetents([.large])
    }

    private func content(_ info: StreakInfo) -> some View {
        List {
            Section {
                // On the list's backdrop, not in a card: the speech bubble
                // is card-coloured and would vanish inside a row.
                hero(info)
                    .listRowBackground(Color.clear)
            }
            Section {
                calendar(info)
                    .listRowInsets(EdgeInsets(top: 12, leading: 12, bottom: 12, trailing: 12))
            } header: {
                Text("This Week")
            } footer: {
                Text("A new gift every day you visit. Missing a day never takes a gift away, only the streak starts over.")
            }
            Section {
                LabeledContent("Current streak", value: Self.days(info.current))
                LabeledContent("Best streak", value: Self.days(info.best))
                LabeledContent("Gifts opened", value: "\(info.opened)")
            }
        }
        .listStyle(.insetGrouped)
    }

    // MARK: Today

    @ViewBuilder
    private func hero(_ info: StreakInfo) -> some View {
        // Today's gift, still shown after it's opened.
        let gift = info.todayGift
        VStack(spacing: 14) {
            if info.claimedToday {
                SpeechBox(text: store.state.line(at: .now))
                    .frame(maxWidth: 300)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(.opacity)
            }
            HStack(alignment: .bottom, spacing: 20) {
                LiveSlimeView(height: 84, interactive: false)
                ArtView(name: info.canClaim ? "gift.closed" : "gift.open")
                    .frame(width: 64, height: 64)
                    .scaleEffect(pop ? 1.15 : 1, anchor: .bottom)
                    .padding(.bottom, 6)
            }
            VStack(spacing: 4) {
                Text(headline(info))
                    .font(.title3.weight(.bold))
                    .foregroundStyle(Theme.ink)
                    .multilineTextAlignment(.center)
                    .accessibilityAddTraits(.isHeader)
                Text(subline(info, gift: gift))
                    .font(.subheadline)
                    .foregroundStyle(Theme.inkSoft)
                    .multilineTextAlignment(.center)
            }
            if info.canClaim {
                AppButton(title: "Open Gift") { open() }
            } else if info.claimedToday {
                reward(gift)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .animation(reduceMotion ? nil : Animation.easeOut(duration: 0.25), value: info.claimedToday)
    }

    private func headline(_ info: StreakInfo) -> String {
        if info.canClaim { return info.todayGift.isSpecial ? "The big gift is here" : "Today's gift is here" }
        if info.claimedToday { return info.current >= 2 ? "\(info.current) days in a row" : "Gift opened" }
        return "See you tomorrow"
    }

    private func subline(_ info: StreakInfo, gift: DailyGift) -> String {
        if info.canClaim { return "Day \(info.today) of \(DailyGift.days): \(gift.summary)" }
        if let next = info.nextGift {
            return "The next gift comes \(Self.wait(until: next, from: .now))."
        }
        return "Come back tomorrow for the next one."
    }

    /// "in 5 hours", "in 20 minutes".
    private static func wait(until date: Date, from now: Date) -> String {
        let minutes = max(1, Int((date.timeIntervalSince(now) / 60).rounded(.up)))
        if minutes < 60 { return minutes == 1 ? "in a minute" : "in \(minutes) minutes" }
        let hours = Int((Double(minutes) / 60).rounded())
        return hours == 1 ? "in an hour" : "in \(hours) hours"
    }

    /// What today's gift held.
    private func reward(_ gift: DailyGift) -> some View {
        HStack(spacing: 12) {
            if gift.item != nil {
                ItemIcon(name: gift.icon, size: 40)
            }
            HStack(spacing: 6) {
                PixelItem(name: "coin").frame(width: 18, height: 18)
                Text("+\(gift.coins)")
                    .font(.headline)
                    .foregroundStyle(Theme.ink)
                    .monospacedDigit()
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 40)
            .background(Theme.well, in: Capsule())
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("You got \(gift.summary)")
    }

    private func open() {
        guard let gift = store.claimDailyGift() else { return }
        UIAccessibility.post(notification: .announcement, argument: "You got \(gift.summary)")
        guard !reduceMotion else { return }
        withAnimation(.spring(response: 0.25, dampingFraction: 0.45)) { pop = true }
        Task {
            try? await Task.sleep(for: .milliseconds(260))
            withAnimation(.spring(response: 0.35, dampingFraction: 0.6)) { pop = false }
        }
    }

    // MARK: The week

    private func calendar(_ info: StreakInfo) -> some View {
        Grid(horizontalSpacing: 8, verticalSpacing: 8) {
            GridRow {
                ForEach(info.week.prefix(4)) { gift in
                    DayTile(gift: gift, state: tileState(gift, info))
                }
            }
            GridRow {
                DayTile(gift: info.week[4], state: tileState(info.week[4], info))
                DayTile(gift: info.week[5], state: tileState(info.week[5], info))
                DayTile(gift: info.week[6], state: tileState(info.week[6], info))
                    .gridCellColumns(2)
            }
        }
    }

    private func tileState(_ gift: DailyGift, _ info: StreakInfo) -> DayTile.Status {
        if info.isOpened(gift) { return gift.day == info.today ? .openedToday : .opened }
        if gift.day == info.today { return info.canClaim ? .ready : .next }
        return .later
    }

    private static func days(_ n: Int) -> String { n == 1 ? "1 day" : "\(n) days" }
}

/// One day of the gift calendar.
private struct DayTile: View {
    enum Status {
        case opened, openedToday, ready, next, later

        var isToday: Bool { self == .openedToday || self == .ready }
        var isOpened: Bool { self == .opened || self == .openedToday }

        var spoken: String {
            switch self {
            case .opened: "Opened"
            case .openedToday: "Opened today"
            case .ready: "Ready to open"
            case .next: "Next"
            case .later: "Later"
            }
        }
    }

    let gift: DailyGift
    let state: Status

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: Theme.smallRadius, style: .continuous)
        VStack(spacing: 6) {
            Text(state.isToday ? "Today" : "Day \(gift.day)")
                .font(.caption.weight(.semibold))
                .foregroundStyle(state.isToday ? Theme.accent : Theme.inkSoft)
                .lineLimit(1)
            HStack(spacing: 6) {
                if gift.item != nil {
                    ArtView(name: gift.icon).frame(width: 30, height: 30)
                }
                PixelItem(name: "coin").frame(width: gift.item == nil ? 24 : 16, height: gift.item == nil ? 24 : 16)
            }
            .frame(height: 30)
            .opacity(state.isOpened ? 0.4 : 1)
            Text(label)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(state.isOpened ? Theme.inkSoft : Theme.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, minHeight: 88)
        .background(fill, in: shape)
        .overlay {
            if state.isToday { shape.strokeBorder(Theme.accent, lineWidth: 2) }
        }
        .overlay(alignment: .topTrailing) {
            if state.isOpened {
                Image(systemName: "checkmark.circle.fill")
                    .font(.footnote)
                    .foregroundStyle(Theme.accent)
                    .padding(5)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Day \(gift.day): \(gift.summary)")
        .accessibilityValue(state.spoken)
    }

    private var label: String {
        if let item = gift.item, let entry = Catalog.item(item) { return "\(entry.title) + \(gift.coins)" }
        return "\(gift.coins) coins"
    }

    private var fill: Color {
        if state.isToday { return Theme.accent.opacity(0.14) }
        if gift.isSpecial { return Theme.gold.opacity(0.14) }
        return Theme.well
    }
}
