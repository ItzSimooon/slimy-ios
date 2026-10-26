import SwiftUI
import UIKit

// Pieces shared by the Friends screens: portraits, rows, quick actions, the
// gift sheet, confirmation dialogs and the toast.

/// A friend's slime, standing still.
struct FriendPortrait: View {
    let skin: Skin
    let stage: Stage
    let mood: Mood
    var accessories = Accessories()

    var body: some View {
        let anim = SpriteAnim(mood)
        let frames = SlimeSprites.frames(stage, anim)
        let anchors = SlimeSprites.anchors(stage, anim)
        PixelSlime(frame: frames[0], skin: skin, accessories: accessories, stage: stage, anchor: anchors.first)
    }
}

/// A slime portrait on a soft square, the leading image of a friend row.
struct FriendAvatar: View {
    let skin: Skin
    let stage: Stage
    var mood: Mood = .idle
    var accessories = Accessories()
    var size: CGFloat = 56

    var body: some View {
        FriendPortrait(skin: skin, stage: stage, mood: mood, accessories: accessories)
            .padding(size * 0.12)
            .frame(width: size, height: size)
            .background(Theme.well, in: RoundedRectangle(cornerRadius: size * 0.26, style: .continuous))
            .accessibilityHidden(true)
    }
}

extension FriendAvatar {
    init(friend: Friend, size: CGFloat = 56) {
        self.init(skin: friend.slime.skin, stage: friend.stage, mood: friend.slime.mood,
                  accessories: friend.slime.equipped ?? Accessories(), size: size)
    }
}

/// Someone a report or a block is about.
struct ReportTarget: Identifiable, Hashable {
    let deviceID: String
    /// The slime's name when known, otherwise the owner's.
    let name: String
    let ownerName: String

    var id: String { deviceID }

    init(friend: Friend) {
        deviceID = friend.id
        name = friend.slime.name
        ownerName = friend.ownerName
    }

    init(request: FriendRequest) {
        deviceID = request.deviceId
        name = request.slime?.name ?? request.ownerName
        ownerName = request.ownerName
    }
}

// MARK: - Quick actions

/// What you can do with a friend in one tap.
enum FriendQuickAction: String, CaseIterable, Identifiable {
    case hug, visit, gift, greet

    var id: String { rawValue }

    var title: String {
        switch self {
        case .hug: "Hug"
        case .visit: "Visit"
        case .gift: "Gift"
        case .greet: "Greet"
        }
    }

    var symbol: String {
        switch self {
        case .hug: "figure.2.arms.open"
        case .visit: "house.fill"
        case .gift: "gift.fill"
        case .greet: "hand.wave.fill"
        }
    }
}

/// Hug, visit, gift and greet as a row of tiles; one menu button at
/// accessibility text sizes, where four tiles don't fit.
struct FriendQuickActions: View {
    let friend: Friend
    var busy = false
    let action: (FriendQuickAction) -> Void

    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        if typeSize.isAccessibilitySize {
            Menu {
                ForEach(FriendQuickAction.allCases) { item in
                    Button {
                        action(item)
                    } label: {
                        Label(item.title, systemImage: item.symbol)
                    }
                }
            } label: {
                Label("Hug, visit, gift …", systemImage: "hand.wave")
                    .font(.body.weight(.semibold))
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .menuStyle(.button)
            .buttonStyle(.bordered)
            .tint(Theme.accent)
            .disabled(busy)
        } else {
            HStack(spacing: 8) {
                ForEach(FriendQuickAction.allCases) { item in
                    tile(item)
                }
            }
        }
    }

    private func tile(_ item: FriendQuickAction) -> some View {
        Button {
            Feedback.shared.play(.tap)
            action(item)
        } label: {
            VStack(spacing: 4) {
                Image(systemName: item.symbol)
                    .font(.body.weight(.semibold))
                    .frame(height: 22)
                Text(item.title)
                    .font(.caption2.weight(.semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
            .foregroundStyle(Theme.accent)
            .frame(maxWidth: .infinity, minHeight: 52)
        }
        .buttonStyle(TileStyle(fill: Theme.well, radius: Theme.smallRadius))
        .disabled(busy)
        .accessibilityLabel("\(item.title) \(friend.slime.name)")
    }
}

// MARK: - Rows

/// A friend in the list: portrait, names and level, then the quick
/// actions. Without `open` it is read-only (shown while offline).
struct FriendListRow: View {
    let friend: Friend
    var isPartner = false
    var busy = false
    var open: (() -> Void)? = nil
    var action: ((FriendQuickAction) -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let open {
                Button(action: open) {
                    summary(chevron: true)
                }
                .buttonStyle(.plain)
                .accessibilityHint("Shows memories and your shared garden.")
            } else {
                summary(chevron: false)
            }
            if let action {
                FriendQuickActions(friend: friend, busy: busy, action: action)
            }
        }
        .padding(.vertical, 4)
    }

    private func summary(chevron: Bool) -> some View {
        HStack(spacing: 12) {
            FriendAvatar(friend: friend)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(friend.slime.name)
                        .font(.headline)
                        .foregroundStyle(Theme.ink)
                        .lineLimit(1)
                    if isPartner { Tag(text: "Island", color: Theme.accent) }
                }
                Text("\(friend.ownerName) · Level \(friend.slime.level)")
                    .font(.subheadline)
                    .foregroundStyle(Theme.inkSoft)
                    .lineLimit(1)
                Label(friend.slime.happiness == nil ? "Mood hidden" : friend.slime.mood.friendTitle,
                      systemImage: friend.slime.happiness == nil ? "eye.slash" : friend.slime.mood.friendSymbol)
                    .font(.caption)
                    .foregroundStyle(Theme.inkSoft)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            if chevron {
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
    }

    private var accessibilityText: String {
        var parts = ["\(friend.slime.name), \(friend.ownerName)'s slime", "level \(friend.slime.level)"]
        if friend.slime.happiness != nil { parts.append(friend.slime.mood.friendTitle.lowercased()) }
        if isPartner { parts.append("in your island") }
        return parts.joined(separator: ", ")
    }
}

/// An incoming friend request with Accept and Decline, and Report and Block
/// in a menu.
struct FriendRequestRow: View {
    let request: FriendRequest
    var busy = false
    let accept: () -> Void
    let decline: () -> Void
    let report: () -> Void
    let block: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                if let slime = request.slime {
                    FriendAvatar(skin: slime.skin, stage: slime.stage ?? .forLevel(slime.level), mood: slime.mood,
                                 accessories: slime.equipped ?? Accessories())
                } else {
                    Image(systemName: "person.crop.circle.fill")
                        .font(.system(size: 40))
                        .foregroundStyle(Theme.inkSoft)
                        .frame(width: 56, height: 56)
                        .accessibilityHidden(true)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(request.slime?.name ?? request.ownerName)
                        .font(.headline)
                        .foregroundStyle(Theme.ink)
                        .lineLimit(1)
                    Text(request.slime == nil ? "wants to be friends" : "from \(request.ownerName)")
                        .font(.subheadline)
                        .foregroundStyle(Theme.inkSoft)
                        .lineLimit(1)
                    Text(request.createdAt.formatted(.relative(presentation: .named)))
                        .font(.caption)
                        .foregroundStyle(Theme.inkSoft)
                }
                .accessibilityElement(children: .combine)
                Spacer(minLength: 0)
                Menu {
                    Button(action: report) {
                        Label("Report…", systemImage: "exclamationmark.bubble")
                    }
                    Button(role: .destructive, action: block) {
                        Label("Block", systemImage: "hand.raised")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .font(.title3)
                        .foregroundStyle(Theme.inkSoft)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("More options for \(request.ownerName)")
            }
            HStack(spacing: 10) {
                AppButton(title: "Accept", systemIcon: "checkmark", action: accept)
                AppButton(title: "Decline", kind: .secondary, action: decline)
            }
            .disabled(busy)
        }
        .padding(.vertical, 4)
    }
}

/// A challenge a friend sent you, with a Play button.
struct FriendChallengeRow: View {
    let challenge: Challenge
    let play: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            ItemIcon(name: challenge.game.icon, size: 44)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(challenge.game.title)
                    .font(.headline)
                    .foregroundStyle(Theme.ink)
                Text("\(challenge.friendName) scored \(challenge.theirScore ?? 0). Beat it on the same board.")
                    .font(.subheadline)
                    .foregroundStyle(Theme.inkSoft)
                Text("Ends \(challenge.expiresAt.formatted(.relative(presentation: .named)))")
                    .font(.caption)
                    .foregroundStyle(Theme.inkSoft)
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: 8)
            AppButton(title: "Play", compact: true, action: play)
                .accessibilityLabel("Play \(challenge.game.title) against \(challenge.friendName)")
        }
        .padding(.vertical, 2)
    }
}

/// A challenge you sent that isn't answered yet, or one you both played.
struct FriendChallengeResultRow: View {
    let challenge: Challenge

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.title3)
                .foregroundStyle(color)
                .frame(width: 44, height: 44)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(headline)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.ink)
                Text(detail)
                    .font(.footnote)
                    .foregroundStyle(Theme.inkSoft)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var outcome: Int? {
        guard let mine = challenge.myScore, let theirs = challenge.theirScore else { return nil }
        return mine == theirs ? 0 : (mine > theirs ? 1 : -1)
    }

    private var symbol: String {
        switch outcome {
        case .some(1): "trophy.fill"
        case .some(0): "equal.circle.fill"
        case .some: "flag.checkered"
        case .none: "hourglass"
        }
    }

    private var color: Color {
        switch outcome {
        case .some(1): Theme.gold
        case .some: Theme.inkSoft
        case .none: Theme.sky
        }
    }

    private var headline: String {
        switch outcome {
        case .some(1): "You beat \(challenge.friendName) in \(challenge.game.title)"
        case .some(0): "A tie with \(challenge.friendName) in \(challenge.game.title)"
        case .some: "\(challenge.friendName) won \(challenge.game.title)"
        case .none: "Waiting for \(challenge.friendName)"
        }
    }

    private var detail: String {
        if let mine = challenge.myScore, let theirs = challenge.theirScore {
            return "You \(mine), \(challenge.friendName) \(theirs)"
        }
        return "\(challenge.game.title) · your score \(challenge.myScore ?? 0) · ends \(challenge.expiresAt.formatted(.relative(presentation: .named)))"
    }
}

// MARK: - Gift sheet

/// Food to send a friend, paid with coins.
struct FriendsGiftSheet: View {
    let friend: Friend
    let send: (Food) -> Void

    @EnvironmentObject private var store: GameStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(foods) { food in
                        row(food)
                    }
                } footer: {
                    Text("\(friend.slime.name) gets to eat it. You can send each friend three gifts a day. More food unlocks as your slime levels up.")
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Gift for \(friend.slime.name)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    CoinCounter(coins: store.state.wallet.coins, inToolbar: true)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var foods: [Food] {
        Food.allCases.filter { $0.price > 0 && store.state.slime.level >= $0.unlockLevel }
    }

    private func row(_ food: Food) -> some View {
        let affordable = store.state.wallet.coins >= food.price
        return Button {
            send(food)
            dismiss()
        } label: {
            HStack(spacing: 12) {
                ItemIcon(name: food.sprite, size: 40)
                Text(food.title)
                    .font(.body)
                    .foregroundStyle(Theme.ink)
                Spacer()
                HStack(spacing: 4) {
                    PixelItem(name: "coin").frame(width: 14, height: 14)
                    Text(food.price, format: .number)
                        .font(.body.weight(.semibold))
                        .monospacedDigit()
                }
                .foregroundStyle(affordable ? Theme.ink : Theme.inkSoft)
            }
        }
        .disabled(!affordable)
        .accessibilityLabel("\(food.title), \(food.price) coins")
        .accessibilityHint(affordable ? "" : "Not enough coins.")
    }
}

// MARK: - Acting on friends

/// Runs friend actions for a screen and holds what its dialogs and sheets
/// show, so the list and the detail screen behave the same.
@MainActor
final class FriendActionCenter: ObservableObject {
    @Published var giftFor: Friend?
    @Published var greetFor: Friend?
    @Published var reportTarget: ReportTarget?
    @Published var blockTarget: ReportTarget?
    @Published var removeFor: Friend?
    @Published var toast: Toast?
    /// Friends with an action on its way.
    @Published private(set) var busy: Set<String> = []
    /// The device that was just removed or blocked from here.
    @Published private(set) var gone: String?

    private var friends: FriendsStore { FriendsStore.shared }

    func quick(_ action: FriendQuickAction, on friend: Friend) {
        switch action {
        case .hug: interact(.hug, with: friend)
        case .visit: interact(.visit, with: friend)
        case .gift: giftFor = friend
        case .greet: greetFor = friend
        }
    }

    func interact(_ kind: InteractionKind, with friend: Friend, food: Food? = nil) {
        guard !busy.contains(friend.id) else { return }
        busy.insert(friend.id)
        Task {
            let line = await self.friends.interact(with: friend, kind, food: food)
            self.busy.remove(friend.id)
            self.announce(line)
        }
    }

    func greet(_ friend: Friend, with preset: MessagePreset) {
        Task {
            let line = await self.friends.send(preset, to: friend)
            self.announce(line)
        }
    }

    func remove(_ friend: Friend) {
        Task {
            if await self.friends.remove(friend) {
                self.gone = friend.id
                self.toast = Toast(text: "\(friend.slime.name) is no longer your friend.", good: true)
            } else {
                self.showError()
            }
        }
    }

    func block(_ target: ReportTarget) {
        Task {
            if await self.friends.block(deviceID: target.deviceID) {
                self.gone = target.deviceID
                self.toast = Toast(text: "\(target.ownerName) is blocked.", good: true)
            } else {
                self.showError()
            }
        }
    }

    /// Runs `work`; shows `success` when it returns true, the store's error
    /// otherwise.
    func perform(success: String? = nil, _ work: @escaping @MainActor () async -> Bool) {
        Task {
            if await work() {
                if let success { self.toast = Toast(text: success, good: true) }
            } else {
                self.showError()
            }
        }
    }

    func say(_ text: String, good: Bool = true) {
        toast = Toast(text: text, good: good)
    }

    /// Moves the store's last error into the toast.
    func showError() {
        let message = friends.lastError ?? "Something went wrong. Please try again."
        friends.lastError = nil
        toast = Toast(text: message, good: false)
    }

    private func announce(_ line: String?) {
        if let line {
            toast = Toast(text: line, good: true)
        } else {
            showError()
        }
    }
}

private struct FriendActionsModifier: ViewModifier {
    @ObservedObject var center: FriendActionCenter
    @EnvironmentObject private var friends: FriendsStore
    @EnvironmentObject private var store: GameStore

    func body(content: Content) -> some View {
        content
            .sheet(item: $center.giftFor) { friend in
                FriendsGiftSheet(friend: friend) { food in
                    center.interact(.gift, with: friend, food: food)
                }
                .environmentObject(friends)
                .environmentObject(store)
            }
            .sheet(item: $center.reportTarget) { target in
                FriendsReportSheet(target: target)
                    .environmentObject(friends)
                    .environmentObject(store)
            }
            .confirmationDialog("Send a greeting", isPresented: presented(\.greetFor),
                                titleVisibility: .visible, presenting: center.greetFor) { friend in
                ForEach(MessagePreset.allCases) { preset in
                    Button(preset.text) { center.greet(friend, with: preset) }
                }
            } message: { friend in
                Text("To \(friend.slime.name). Greetings are short presets, so they're always friendly.")
            }
            .confirmationDialog(removeTitle,
                                isPresented: presented(\.removeFor),
                                titleVisibility: .visible, presenting: center.removeFor) { friend in
                Button("Remove Friend", role: .destructive) { center.remove(friend) }
            } message: { friend in
                Text("You and \(friend.ownerName) stop being friends. Memories and your shared garden are gone, and you'd need a new code to connect again.")
            }
            .confirmationDialog(blockTitle,
                                isPresented: presented(\.blockTarget),
                                titleVisibility: .visible, presenting: center.blockTarget) { target in
                Button("Block", role: .destructive) { center.block(target) }
            } message: { target in
                Text("You won't be friends anymore and nothing from \(target.ownerName) will reach you. They aren't notified. You can unblock them under Privacy & blocked.")
            }
            .friendsToast($center.toast)
    }

    private var removeTitle: String {
        guard let friend = center.removeFor else { return "Remove friend?" }
        return "Remove \(friend.slime.name)?"
    }

    private var blockTitle: String {
        guard let target = center.blockTarget else { return "Block?" }
        return "Block \(target.ownerName)?"
    }

    private func presented<T>(_ keyPath: ReferenceWritableKeyPath<FriendActionCenter, T?>) -> Binding<Bool> {
        Binding(get: { center[keyPath: keyPath] != nil },
                set: { if !$0 { center[keyPath: keyPath] = nil } })
    }
}

private struct FriendsToastModifier: ViewModifier {
    @Binding var toast: Toast?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content.overlay(alignment: .bottom) {
            ZStack {
                if let current = toast {
                    ToastBanner(toast: current)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 16)
                        .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
                        .onTapGesture { toast = nil }
                        .task(id: current.id) {
                            UIAccessibility.post(notification: .announcement, argument: current.text)
                            do {
                                try await Task.sleep(for: .seconds(3))
                            } catch {
                                return
                            }
                            if toast?.id == current.id { toast = nil }
                        }
                }
            }
            .animation(reduceMotion ? .easeInOut(duration: 0.2) : .snappy(duration: 0.3), value: toast?.id)
        }
    }
}

extension View {
    /// The gift sheet, greeting picker, report sheet, remove and block
    /// confirmations and the toast of a `FriendActionCenter`.
    func friendActions(_ center: FriendActionCenter) -> some View {
        modifier(FriendActionsModifier(center: center))
    }

    /// A short confirmation at the bottom that hides itself.
    func friendsToast(_ toast: Binding<Toast?>) -> some View {
        modifier(FriendsToastModifier(toast: toast))
    }
}

// MARK: - Titles

extension InteractionKind {
    var title: String {
        switch self {
        case .hug: "Hugs"
        case .visit: "Visits"
        case .gift: "Gifts"
        }
    }

    var symbol: String {
        switch self {
        case .hug: "figure.2.arms.open"
        case .visit: "house.fill"
        case .gift: "gift.fill"
        }
    }
}

extension FriendMemory {
    /// Shown in the list. The server still keeps memories about love for
    /// older app builds; those stay hidden. Kinds from a newer server show
    /// with a general title.
    var isShown: Bool {
        !(kind.hasPrefix("love.") || kind == "inLove" || kind == "first.kiss")
    }

    var title: String {
        switch kind {
        case "met": "Became friends"
        case "first.hug": "First hug"
        case "first.visit": "First visit"
        case "first.gift": "First gift"
        case "firstChallengeWin": "First challenge won"
        case "firstCoop": "First full garden"
        default: "A moment together"
        }
    }

    var symbol: String {
        switch kind {
        case "met": "person.2.fill"
        case "first.hug": "figure.2.arms.open"
        case "first.visit": "house.fill"
        case "first.gift": "gift.fill"
        case "firstChallengeWin": "trophy.fill"
        case "firstCoop": "leaf.fill"
        default: "sparkles"
        }
    }
}

extension Mood {
    /// How a friend's slime is doing, in a few words.
    var friendTitle: String {
        switch self {
        case .idle: "Doing fine"
        case .sad: "Feeling a bit low"
        case .sleep: "Sleeping"
        }
    }

    var friendSymbol: String {
        switch self {
        case .idle: "face.smiling"
        case .sad: "cloud.drizzle"
        case .sleep: "moon.zzz.fill"
        }
    }
}
