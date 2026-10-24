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
            VStack(alignment: .leading, spacing: 18) {
                SheetHeader(eyebrow: "friends", title: "Slime buddies") { dismiss() }

                if case .unavailable(let reason) = friends.status {
                    ServerNotice(reason: reason)
                }

                if friends.ownerName.isEmpty {
                    nameCard
                }

                pairCard

                if !friends.allFriends.isEmpty {
                    Eyebrow(text: "your friends")
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
                        .font(Brand.display(15, .bold))
                        .foregroundStyle(Brand.warm)
                        .frame(maxWidth: .infinity)
                }
                if let error = friends.lastError {
                    Text(error)
                        .font(Brand.display(13, .medium))
                        .foregroundStyle(Brand.warm)
                }

                demoToggle
            }
            .padding(20)
        }
        .task { await friends.refresh() }
        .confirmationDialog("Send a gift", isPresented: Binding(get: { giftFor != nil }, set: { if !$0 { giftFor = nil } })) {
            ForEach(Food.allCases.filter { $0.price > 0 }) { food in
                Button("\(food.emoji) \(food.title) · \(food.price) coins") {
                    if let friend = giftFor { act(friend, .gift, food: food) }
                }
            }
        }
        .presentationDetents([.large])
    }

    private var nameCard: some View {
        Panel {
            VStack(alignment: .leading, spacing: 10) {
                Eyebrow(text: "your name")
                Text("Friends see this next to your slime.")
                    .font(Brand.display(14, .medium))
                    .foregroundStyle(Brand.muted)
                TextField("Your name", text: $friends.ownerName)
                    .font(Brand.display(17, .bold))
                    .textInputAutocapitalization(.words)
                    .padding(12)
                    .background(Brand.raised, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
        }
    }

    private var pairCard: some View {
        Panel {
            VStack(alignment: .leading, spacing: 14) {
                Eyebrow(text: "team up", color: Brand.warm)
                Text("Pair with a friend who has Slimy. Hug, visit and send gifts, and your slimes fall in love at 100%. Then they show up together in your Dynamic Island.")
                    .font(Brand.display(14, .medium))
                    .foregroundStyle(Brand.text)

                if let code = friends.pairCode {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(code.code)
                            .font(Brand.mono(34, .black))
                            .tracking(6)
                            .foregroundStyle(Brand.hyprGlow)
                            .textSelection(.enabled)
                        Text("Your friend enters this in their app. Expires \(code.expiresAt.formatted(date: .omitted, time: .shortened)).")
                            .font(Brand.display(12, .medium))
                            .foregroundStyle(Brand.muted)
                    }
                    ShareLink(item: "Add my slime on Slimy! Code: \(code.code) · slime.hyprnode.de") {
                        Label("Share code", systemImage: "square.and.arrow.up")
                            .font(Brand.display(14, .bold))
                    }
                } else {
                    JellyButton(title: busy ? "Making code…" : "Get my friend code", symbol: "qrcode", tint: Brand.hypr) {
                        busy = true
                        Task { await friends.makeCode(); busy = false }
                    }
                }

                HStack(spacing: 10) {
                    TextField("Friend's code", text: $joinCode)
                        .font(Brand.mono(17, .bold))
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .padding(12)
                        .background(Brand.raised, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    Button("Join") {
                        Task {
                            if await friends.join(code: joinCode) { joinCode = "" }
                        }
                    }
                    .font(Brand.display(16, .bold))
                    .disabled(joinCode.count < 6)
                }
            }
        }
    }

    private var demoToggle: some View {
        Toggle(isOn: $friends.demoEnabled) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Demo friend")
                    .font(Brand.display(15, .bold))
                    .foregroundStyle(Brand.text)
                Text("Adds Mochi, a pretend slime that lives only on this phone, so you can try love and the couple island before the server is live.")
                    .font(Brand.display(12, .medium))
                    .foregroundStyle(Brand.muted)
            }
        }
        .tint(Brand.warm)
        .padding(.top, 8)
    }

    private func act(_ friend: Friend, _ kind: InteractionKind, food: Food? = nil) {
        Task {
            if let line = await friends.interact(with: friend, kind, food: food) {
                withAnimation(.spring) { lastLine = line }
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
                .font(Brand.display(13, .medium))
                .foregroundStyle(Brand.text)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Brand.cool.opacity(0.1), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
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
                    PixelSlime(frame: SpriteAnim(friend.inLove ? Mood.idle : friend.slime.mood).frames[0], skin: friend.slime.skin)
                        .frame(height: 52)
                    VStack(alignment: .leading, spacing: 3) {
                        HStack(spacing: 6) {
                            Text(friend.slime.name)
                                .font(Brand.display(18, .black))
                                .foregroundStyle(Brand.text)
                            if friend.isDemo {
                                Text("DEMO")
                                    .font(Brand.mono(9, .black))
                                    .foregroundStyle(Brand.void)
                                    .padding(.horizontal, 5).padding(.vertical, 2)
                                    .background(Brand.cool, in: Capsule())
                            }
                        }
                        Text("\(friend.ownerName)'s slime · lv \(friend.slime.level)")
                            .font(Brand.display(12, .medium))
                            .foregroundStyle(Brand.muted)
                    }
                    Spacer()
                    Menu {
                        Button(isPartner ? "Shown in your island" : "Show in my island", systemImage: "capsule", action: onPartner)
                            .disabled(isPartner)
                        Button("Remove friend", systemImage: "person.badge.minus", role: .destructive, action: onRemove)
                    } label: {
                        Image(systemName: "ellipsis")
                            .font(.system(size: 16, weight: .bold))
                            .frame(width: 36, height: 36)
                            .background(Brand.raised, in: Circle())
                    }
                }

                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Eyebrow(text: friend.inLove ? "in love 💞" : "love", color: Brand.warm)
                        Spacer()
                        Text("\(friend.love)%")
                            .font(Brand.mono(12, .bold))
                            .foregroundStyle(Brand.warm)
                    }
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(Brand.warm.opacity(0.15))
                            Capsule()
                                .fill(LinearGradient(colors: [Brand.warm, Brand.hypr], startPoint: .leading, endPoint: .trailing))
                                .frame(width: max(6, geo.size.width * CGFloat(friend.love) / 100))
                        }
                    }
                    .frame(height: 8)
                }

                HStack(spacing: 8) {
                    LoveButton(title: "Hug", symbol: "hands.and.sparkles.fill") { onAction(.hug) }
                    LoveButton(title: "Visit", symbol: "house.fill") { onAction(.visit) }
                    LoveButton(title: "Gift", symbol: "gift.fill", action: onGift)
                    LoveButton(title: "Kiss", symbol: "heart.fill") { onAction(.kiss) }
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
                Image(systemName: symbol).font(.system(size: 15, weight: .bold))
                Text(title).font(Brand.display(11, .bold))
            }
            .foregroundStyle(Brand.warm)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 9)
            .background(Brand.warm.opacity(0.12), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(SquishStyle())
    }
}
