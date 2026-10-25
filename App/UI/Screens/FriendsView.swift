import SwiftUI

struct FriendsView: View {
    @EnvironmentObject private var friends: FriendsStore
    @EnvironmentObject private var store: GameStore
    @Environment(\.dismiss) private var dismiss

    @State private var joinCode = ""
    @State private var busy = false
    @State private var giftFor: Friend?
    @State private var lastLine: String?

    var body: some View {
        ZStack {
            MenuBackdrop()
            ScrollView {
                VStack(spacing: 14) {
                    MenuHeader(title: "Freunde", subtitle: "Slimes können sich verlieben") { dismiss() }

                    if case .unavailable(let reason) = friends.status {
                        PixelWindow(fill: Color(hex: 0xE3F2FA)) {
                            Text(reason).font(Theme.text(14)).foregroundStyle(Theme.ink)
                        }
                    }

                    if friends.ownerName.isEmpty {
                        PixelWindow(title: "Dein Name") {
                            Text("Freunde sehen ihn neben deinem Slime.").font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
                            TextField("Dein Name", text: $friends.ownerName)
                                .font(Theme.label(17))
                                .padding(10)
                                .background(Theme.paperShade)
                                .overlay(Rectangle().strokeBorder(Theme.border, lineWidth: 2))
                        }
                    }

                    pairCard

                    ForEach(friends.allFriends) { friend in
                        FriendCard(friend: friend,
                                   isPartner: friends.partner?.id == friend.id,
                                   onAction: { kind in act(friend, kind) },
                                   onGift: { giftFor = friend },
                                   onPartner: { friends.partnerID = friend.id },
                                   onRemove: { Task { await friends.remove(friend) } })
                    }

                    if let lastLine {
                        Text(lastLine).font(Theme.label(16)).foregroundStyle(Theme.love)
                    }
                    if let error = friends.lastError {
                        Text(error).font(Theme.text(14)).foregroundStyle(Theme.danger)
                    }

                    PixelWindow {
                        Toggle(isOn: $friends.demoEnabled) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Test-Freund Mochi").font(Theme.label(15)).foregroundStyle(Theme.ink)
                                Text("Ein Übungs-Slime nur auf diesem iPhone. Zum Ausprobieren, solange der Server nicht läuft.")
                                    .font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
                            }
                        }
                    }
                }
                .padding(16)
            }
        }
        .task {
            friends.hasNews = false
            await friends.refresh()
        }
        .confirmationDialog("Geschenk schicken", isPresented: Binding(get: { giftFor != nil }, set: { if !$0 { giftFor = nil } })) {
            ForEach(Food.allCases.filter { $0.price > 0 }) { food in
                Button("\(food.title) · \(food.price) Münzen") {
                    if let friend = giftFor { act(friend, .gift, food: food) }
                }
            }
        }
        .presentationDetents([.large])
    }

    private var pairCard: some View {
        PixelWindow(title: "Zusammentun") {
            Text("Verbinde dich mit jemandem, der auch Slimy hat. Umarmen, besuchen und Geschenke füllen die Liebe auf. Bei 100 % sitzen eure Slimes zusammen in der Insel.")
                .font(Theme.text(14)).foregroundStyle(Theme.ink)
            if let code = friends.pairCode {
                Text(code.code).font(Theme.title(34)).tracking(6).foregroundStyle(Theme.accentInk).textSelection(.enabled)
                Text("Gültig bis \(code.expiresAt.formatted(date: .omitted, time: .shortened)).")
                    .font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
                ShareLink(item: "Werd Slime-Freund mit mir in Slimy. Code: \(code.code) · slime.hyprnode.de/f/\(code.code)") {
                    Text("Code teilen").font(Theme.label(15)).foregroundStyle(Theme.accentInk)
                }
            } else {
                PixelButton(title: busy ? "Moment …" : "Meinen Code holen") {
                    busy = true
                    Task { await friends.makeCode(); busy = false }
                }
            }
            HStack(spacing: 10) {
                TextField("Code von Freund", text: $joinCode)
                    .font(Theme.label(17))
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .padding(10)
                    .background(Theme.paperShade)
                    .overlay(Rectangle().strokeBorder(Theme.border, lineWidth: 2))
                PixelButton(title: "Verbinden", compact: true) {
                    Task { if await friends.join(code: joinCode) { joinCode = "" } }
                }
                .disabled(joinCode.count < 6)
            }
        }
    }

    private func act(_ friend: Friend, _ kind: InteractionKind, food: Food? = nil) {
        Task {
            if let line = await friends.interact(with: friend, kind, food: food) { lastLine = line }
        }
    }
}

private struct FriendCard: View {
    let friend: Friend
    let isPartner: Bool
    let onAction: (InteractionKind) -> Void
    let onGift: () -> Void
    let onPartner: () -> Void
    let onRemove: () -> Void

    var body: some View {
        PixelWindow {
            HStack(spacing: 12) {
                PixelSlime(frame: SlimeSprites.frames(Stage.forLevel(friend.slime.level), SpriteAnim(friend.slime.mood))[0],
                           skin: friend.slime.skin)
                    .frame(width: 58, height: 48)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(friend.slime.name).font(Theme.title(18)).foregroundStyle(Theme.ink)
                        if friend.isDemo {
                            Text("TEST").font(Theme.label(10)).foregroundStyle(.white).padding(.horizontal, 4).background(Theme.sky)
                        }
                    }
                    Text("von \(friend.ownerName) · LV \(friend.slime.level)").font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
                }
                Spacer()
                Menu {
                    Button(isPartner ? "Ist in deiner Insel" : "In meiner Insel zeigen", action: onPartner).disabled(isPartner)
                    Button("Freund entfernen", role: .destructive, action: onRemove)
                } label: {
                    Image(systemName: "ellipsis").font(.system(size: 15, weight: .bold)).foregroundStyle(Theme.ink)
                        .frame(width: 40, height: 40)
                }
                .accessibilityLabel("Mehr")
            }
            HStack(spacing: 8) {
                Text(friend.inLove ? "VERLIEBT" : "LIEBE").font(Theme.label(12)).foregroundStyle(Theme.love)
                BlockBar(value: Double(friend.love) / 100, color: Theme.love, height: 8)
                Text("\(friend.love) %").font(Theme.label(12)).foregroundStyle(Theme.love)
            }
            HStack(spacing: 6) {
                PixelButton(title: "Umarmen", kind: .secondary, compact: true) { onAction(.hug) }
                PixelButton(title: "Besuch", kind: .secondary, compact: true) { onAction(.visit) }
                PixelButton(title: "Geschenk", kind: .secondary, compact: true, action: onGift)
                PixelButton(title: "Kuss", kind: .secondary, compact: true) { onAction(.kiss) }
                    .opacity(friend.love >= 50 ? 1 : 0.4)
            }
        }
    }
}
