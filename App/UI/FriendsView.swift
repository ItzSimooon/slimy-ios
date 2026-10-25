import SwiftUI

struct FriendsView: View {
    @EnvironmentObject private var friends: FriendsStore
    @EnvironmentObject private var store: SlimeStore
    @Environment(\.dismiss) private var dismiss

    @State private var joinCode = ""
    @State private var busy = false
    @State private var giftFor: Friend?
    @State private var lastLine: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                SheetHeader(eyebrow: "freunde", title: "Slime-Freunde") { dismiss() }

                if case .unavailable(let reason) = friends.status {
                    ServerNotice(reason: reason)
                }

                if friends.ownerName.isEmpty {
                    nameCard
                }

                pairCard

                if !friends.allFriends.isEmpty {
                    Eyebrow(text: "deine freunde")
                    ForEach(friends.allFriends) { friend in
                        FriendCard(friend: friend,
                                   isPartner: friends.partner?.id == friend.id,
                                   onAction: { kind in act(friend, kind) },
                                   onGift: { giftFor = friend },
                                   onPartner: { friends.partnerID = friend.id },
                                   onRemove: { Task { await friends.remove(friend) } })
                    }
                }

                if let lastLine {
                    Text(lastLine)
                        .font(Brand.pixel(16))
                        .foregroundStyle(Brand.warm)
                        .frame(maxWidth: .infinity)
                }
                if let error = friends.lastError {
                    Text(error)
                        .font(Brand.body(14, .medium))
                        .foregroundStyle(Brand.warm)
                }

                demoToggle
            }
            .padding(18)
        }
        .task { await friends.refresh() }
        .confirmationDialog("Geschenk schicken", isPresented: Binding(get: { giftFor != nil }, set: { if !$0 { giftFor = nil } })) {
            ForEach(Food.allCases.filter { $0.price > 0 }) { food in
                Button("\(food.title) · \(food.price) Münzen") {
                    if let friend = giftFor { act(friend, .gift, food: food) }
                }
            }
        }
        .presentationDetents([.large])
    }

    private var nameCard: some View {
        Panel {
            VStack(alignment: .leading, spacing: 10) {
                Eyebrow(text: "dein name")
                Text("Deine Freunde sehen ihn neben deinem Slime.")
                    .font(Brand.body(14))
                    .foregroundStyle(Brand.muted)
                TextField("Dein Name", text: $friends.ownerName)
                    .font(Brand.pixel(18))
                    .textInputAutocapitalization(.words)
                    .padding(12)
                    .background(Brand.raised)
                    .overlay(Rectangle().strokeBorder(Brand.line, lineWidth: 2))
            }
        }
    }

    private var pairCard: some View {
        Panel {
            VStack(alignment: .leading, spacing: 14) {
                Eyebrow(text: "zusammentun", color: Brand.warm)
                Text("Verbinde dich mit jemandem, der auch Slimy hat. Umarmen, besuchen und Geschenke schicken füllen die Liebe auf. Bei 100 % sind eure Slimes verliebt und sitzen zusammen in der Insel.")
                    .font(Brand.body(15))
                    .foregroundStyle(Brand.text)

                if let code = friends.pairCode {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(code.code)
                            .font(Brand.pixel(36))
                            .tracking(6)
                            .foregroundStyle(Brand.slime)
                            .textSelection(.enabled)
                        Text("Dein Freund gibt den Code in seiner App ein. Gültig bis \(code.expiresAt.formatted(date: .omitted, time: .shortened)).")
                            .font(Brand.body(13))
                            .foregroundStyle(Brand.muted)
                    }
                    ShareLink(item: "Werd Slime-Freund mit mir in Slimy. Code: \(code.code) · slime.hyprnode.de") {
                        Label("Code teilen", systemImage: "square.and.arrow.up")
                            .font(Brand.pixel(15))
                            .foregroundStyle(Brand.slime)
                    }
                } else {
                    PixelButton(title: busy ? "Moment …" : "Meinen Code holen", symbol: "qrcode") {
                        busy = true
                        Task { await friends.makeCode(); busy = false }
                    }
                }

                HStack(spacing: 10) {
                    TextField("Code von Freund", text: $joinCode)
                        .font(Brand.pixel(18))
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .padding(12)
                        .background(Brand.raised)
                        .overlay(Rectangle().strokeBorder(Brand.line, lineWidth: 2))
                    Button("Verbinden") {
                        Task {
                            if await friends.join(code: joinCode) { joinCode = "" }
                        }
                    }
                    .font(Brand.pixel(16))
                    .foregroundStyle(joinCode.count < 6 ? Brand.muted : Brand.slime)
                    .disabled(joinCode.count < 6)
                }
            }
        }
    }

    private var demoToggle: some View {
        Toggle(isOn: $friends.demoEnabled) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Test-Freund")
                    .font(Brand.pixel(15))
                    .foregroundStyle(Brand.text)
                Text("Fügt Mochi hinzu, einen Übungs-Slime, den es nur auf diesem iPhone gibt. So kannst du Liebe und die Pärchen-Insel ausprobieren, bevor der Server läuft.")
                    .font(Brand.body(13))
                    .foregroundStyle(Brand.muted)
            }
        }
        .tint(Brand.warm)
        .padding(.top, 8)
    }

    private func act(_ friend: Friend, _ kind: InteractionKind, food: Food? = nil) {
        Task {
            if let line = await friends.interact(with: friend, kind, food: food) {
                lastLine = line
            }
        }
    }
}

private struct ServerNotice: View {
    let reason: String

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "antenna.radiowaves.left.and.right.slash")
                .foregroundStyle(Brand.cool)
            Text(reason)
                .font(Brand.body(14))
                .foregroundStyle(Brand.text)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Brand.cool.opacity(0.08))
        .overlay(Rectangle().strokeBorder(Brand.cool.opacity(0.4), lineWidth: 2))
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
        Panel {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 12) {
                    PixelSlime(frame: SpriteAnim(friend.slime.mood).frames[0], skin: friend.slime.skin)
                        .frame(height: 48)
                    VStack(alignment: .leading, spacing: 3) {
                        HStack(spacing: 6) {
                            Text(friend.slime.name)
                                .font(Brand.pixel(19))
                                .foregroundStyle(Brand.text)
                            if friend.isDemo {
                                Text("TEST")
                                    .font(Brand.pixel(10))
                                    .foregroundStyle(Brand.bg)
                                    .padding(.horizontal, 4)
                                    .padding(.vertical, 1)
                                    .background(Brand.cool)
                            }
                        }
                        Text("Slime von \(friend.ownerName) · LV \(friend.slime.level)")
                            .font(Brand.body(13))
                            .foregroundStyle(Brand.muted)
                    }
                    Spacer()
                    Menu {
                        Button(isPartner ? "Ist in deiner Insel" : "In meiner Insel zeigen", systemImage: "capsule", action: onPartner)
                            .disabled(isPartner)
                        Button("Freund entfernen", systemImage: "person.badge.minus", role: .destructive, action: onRemove)
                    } label: {
                        Image(systemName: "ellipsis")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(Brand.text)
                            .frame(width: 36, height: 36)
                            .background(Brand.raised)
                            .overlay(Rectangle().strokeBorder(Brand.line, lineWidth: 2))
                    }
                    .accessibilityLabel("Mehr")
                }

                HStack(spacing: 10) {
                    Text(friend.inLove ? "VERLIEBT" : "LIEBE")
                        .font(Brand.pixel(12))
                        .foregroundStyle(Brand.warm)
                    PixelBar(value: friend.love, segments: 10, color: Brand.warm, height: 10)
                    Text("\(friend.love) %")
                        .font(Brand.pixel(13))
                        .foregroundStyle(Brand.warm)
                }

                HStack(spacing: 8) {
                    LoveButton(title: "Umarmen", symbol: "figure.2.arms.open") { onAction(.hug) }
                    LoveButton(title: "Besuchen", symbol: "house.fill") { onAction(.visit) }
                    LoveButton(title: "Schenken", symbol: "gift.fill", action: onGift)
                    LoveButton(title: "Küssen", symbol: "heart.fill") { onAction(.kiss) }
                        .opacity(friend.love >= 50 ? 1 : 0.4)
                }
            }
        }
    }
}

private struct LoveButton: View {
    let title: String
    let symbol: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: symbol).font(.system(size: 14, weight: .bold))
                Text(title).font(Brand.pixel(11))
            }
            .foregroundStyle(Brand.warm)
            .frame(maxWidth: .infinity)
            .frame(height: 50)
            .background(Brand.raised)
            .overlay(Rectangle().strokeBorder(Brand.warm.opacity(0.5), lineWidth: 2))
        }
        .buttonStyle(PressStyle())
    }
}
