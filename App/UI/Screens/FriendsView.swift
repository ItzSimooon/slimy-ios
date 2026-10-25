import SwiftUI

struct FriendsView: View {
    @EnvironmentObject private var friends: FriendsStore
    @EnvironmentObject private var store: GameStore
    @Environment(\.dismiss) private var dismiss

    @State private var joinCode = ""
    @State private var busy = false
    @State private var giftFor: Friend?
    @State private var greetFor: Friend?
    @State private var detailFor: Friend?
    @State private var confirmBlock: (id: String, name: String)?
    @State private var lastLine: String?

    var body: some View {
        NavigationStack {
            ZStack {
                MenuBackdrop()
                ScrollView {
                    VStack(spacing: 14) {
                        MenuHeader(title: "Freunde", subtitle: "Slimes können sich verlieben") { dismiss() }

                        if case .unavailable(let reason) = friends.status {
                            PixelWindow(fill: Theme.paperShade) {
                                Text(reason).font(Theme.text(14)).foregroundStyle(Theme.ink)
                                if !friends.queue.isEmpty {
                                    Text("\(friends.queue.count) Sachen warten aufs Senden.").font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
                                }
                            }
                        }

                        if friends.ownerName.isEmpty {
                            PixelWindow(title: "Dein Name") {
                                Text("Freunde sehen ihn neben deinem Slime.").font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
                                PixelField(placeholder: "Dein Name", text: $friends.ownerName)
                            }
                        }

                        if let line = lastLine {
                            Text(line).font(Theme.label(16)).foregroundStyle(Theme.love)
                                .frame(maxWidth: .infinity)
                                .accessibilityAddTraits(.updatesFrequently)
                        }
                        if let error = friends.lastError {
                            Text(error).font(Theme.text(14)).foregroundStyle(Theme.danger)
                        }

                        ForEach(friends.requests.incoming) { request in
                            RequestCard(request: request,
                                        accept: { Task { await friends.accept(request) } },
                                        decline: { Task { await friends.decline(request) } },
                                        block: { confirmBlock = (request.deviceId, request.ownerName) })
                        }

                        ForEach(friends.openChallenges) { challenge in
                            ChallengeCard(challenge: challenge) {
                                friends.playChallenge = challenge
                                dismiss()
                            }
                        }

                        ForEach(friends.allFriends) { friend in
                            FriendCard(friend: friend,
                                       isPartner: friends.partner?.id == friend.id,
                                       onAction: { kind in act(friend, kind) },
                                       onGift: { giftFor = friend },
                                       onGreet: { greetFor = friend },
                                       onDetail: { detailFor = friend },
                                       onPartner: { friends.partnerID = friend.id },
                                       onRemove: { Task { await friends.remove(friend) } },
                                       onBlock: { confirmBlock = (friend.id, friend.slime.name) })
                        }

                        pairCard

                        if !friends.requests.outgoing.isEmpty {
                            PixelWindow(title: "Wartet auf Antwort") {
                                ForEach(friends.requests.outgoing) { request in
                                    HStack {
                                        Text(request.slime?.name ?? request.ownerName).font(Theme.label(15)).foregroundStyle(Theme.ink)
                                        Spacer()
                                        Button("Zurückziehen") { Task { await friends.decline(request) } }
                                            .font(Theme.label(13)).foregroundStyle(Theme.accentInk)
                                    }
                                }
                            }
                        }

                        NavigationLink { FriendPrivacyView() } label: {
                            HStack {
                                PixelItem(name: "gear").frame(width: 20, height: 20)
                                Text("Privatsphäre und Blockierte").font(Theme.label(15)).foregroundStyle(Theme.ink)
                                Spacer()
                                Image(systemName: "chevron.right").foregroundStyle(Theme.inkSoft)
                            }
                            .padding(12)
                            .background(NotchedRect().fill(Theme.paper))
                            .overlay(NotchedRect().strokeBorder(Theme.border, lineWidth: Theme.px))
                        }
                        .buttonStyle(.plain)

                        PixelWindow {
                            Toggle(isOn: $friends.demoEnabled) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Test-Freund Mochi").font(Theme.label(15)).foregroundStyle(Theme.ink)
                                    Text("Ein Übungs-Slime nur auf diesem iPhone, zum Ausprobieren.")
                                        .font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
                                }
                            }
                        }
                    }
                    .padding(16)
                }
                .refreshable { await friends.refresh() }
            }
            .toolbar(.hidden, for: .navigationBar)
        }
        .tint(Theme.accent)
        .task {
            friends.hasNews = false
            await friends.refresh()
        }
        .confirmationDialog("Geschenk schicken", isPresented: present($giftFor)) {
            ForEach(Food.allCases.filter { $0.price > 0 && store.state.slime.level >= $0.unlockLevel }) { food in
                Button("\(food.title) · \(food.price) Münzen") {
                    if let friend = giftFor { act(friend, .gift, food: food) }
                }
            }
        }
        .confirmationDialog("Gruß schicken", isPresented: present($greetFor)) {
            ForEach(MessagePreset.allCases) { preset in
                Button(preset.text) {
                    guard let friend = greetFor else { return }
                    Task { if let line = await friends.send(preset, to: friend) { lastLine = line } }
                }
            }
        }
        .confirmationDialog(confirmBlock.map { "\($0.name) blockieren?" } ?? "",
                            isPresented: Binding(get: { confirmBlock != nil }, set: { if !$0 { confirmBlock = nil } }),
                            titleVisibility: .visible) {
            Button("Blockieren", role: .destructive) {
                if let id = confirmBlock?.id { Task { await friends.block(deviceID: id) } }
            }
        } message: {
            Text("Ihr seid dann keine Freunde mehr und es kommt nichts mehr von diesem Gerät an. Rückgängig machen kannst du es unter Privatsphäre.")
        }
        .sheet(item: $detailFor) { friend in
            FriendDetailView(friend: friend)
                .presentationBackground(Theme.backdrop)
        }
        .presentationDetents([.large])
    }

    private func present(_ item: Binding<Friend?>) -> Binding<Bool> {
        Binding(get: { item.wrappedValue != nil }, set: { if !$0 { item.wrappedValue = nil } })
    }

    private var pairCard: some View {
        PixelWindow(title: "Zusammentun") {
            Text("Verbinde dich mit jemandem, der auch Slimy hat. Gib ihm deinen Code oder tipp seinen ein. Umarmen, Besuche und Geschenke füllen die Liebe. Bei 100 % sind eure Slimes verliebt.")
                .font(Theme.text(14)).foregroundStyle(Theme.ink)
            if let code = friends.pairCode {
                Text(code.code).font(Theme.title(34)).tracking(6).foregroundStyle(Theme.accentInk).textSelection(.enabled)
                    .accessibilityLabel("Dein Code: \(code.code.map(String.init).joined(separator: " "))")
                Text("Gültig bis \(code.expiresAt.formatted(date: .omitted, time: .shortened)), nur einmal.")
                    .font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
                ShareLink(item: "Werd Slime-Freund mit mir in Slimy. Code: \(code.code)") {
                    Text("Code teilen").font(Theme.label(15)).foregroundStyle(Theme.accentInk)
                }
            } else {
                PixelButton(title: busy ? "Moment …" : "Meinen Code holen") {
                    busy = true
                    Task { await friends.makeCode(); busy = false }
                }
            }
            HStack(spacing: 10) {
                PixelField(placeholder: "Code von Freund", text: $joinCode, code: true)
                PixelButton(title: "Verbinden", compact: true) {
                    Task {
                        if let line = await friends.join(code: joinCode) {
                            joinCode = ""
                            lastLine = line
                        }
                    }
                }
                .disabled(joinCode.trimmingCharacters(in: .whitespaces).count < 6)
            }
        }
    }

    private func act(_ friend: Friend, _ kind: InteractionKind, food: Food? = nil) {
        Task {
            if let line = await friends.interact(with: friend, kind, food: food) { lastLine = line }
        }
    }
}

/// Text field in the window style.
struct PixelField: View {
    let placeholder: String
    @Binding var text: String
    var code = false

    var body: some View {
        TextField(placeholder, text: $text)
            .font(Theme.label(17))
            .foregroundStyle(Theme.ink)
            .textInputAutocapitalization(code ? .characters : .words)
            .autocorrectionDisabled(code)
            .padding(10)
            .background(Theme.paperShade)
            .overlay(Rectangle().strokeBorder(Theme.border, lineWidth: 2))
    }
}

private struct RequestCard: View {
    let request: FriendRequest
    let accept: () -> Void
    let decline: () -> Void
    let block: () -> Void

    var body: some View {
        PixelWindow(title: "Freundschaftsanfrage", fill: Color.dynamic(light: 0xFDEAF0, dark: 0x3A2436)) {
            HStack(spacing: 12) {
                if let slime = request.slime {
                    FriendPortrait(skin: slime.skin, stage: slime.stage ?? .forLevel(slime.level), mood: slime.mood)
                        .frame(width: 52, height: 44)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(request.slime?.name ?? "Ein Slime").font(Theme.title(17)).foregroundStyle(Theme.ink)
                    Text("von \(request.ownerName)").font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
                }
                Spacer()
            }
            HStack(spacing: 8) {
                PixelButton(title: "Annehmen", compact: true, action: accept)
                PixelButton(title: "Ablehnen", kind: .secondary, compact: true, action: decline)
                Spacer()
                Button("Blockieren", action: block).font(Theme.label(13)).foregroundStyle(Theme.danger)
            }
        }
    }
}

private struct ChallengeCard: View {
    let challenge: Challenge
    let play: () -> Void

    var body: some View {
        PixelWindow(title: "Herausforderung", fill: Color.dynamic(light: 0xFFF1C9, dark: 0x3A3220)) {
            HStack(spacing: 12) {
                PixelItem(name: challenge.game.icon).frame(width: 32, height: 32)
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(challenge.friendName): \(challenge.game.title)").font(Theme.label(15)).foregroundStyle(Theme.ink)
                    Text("Schlag \(challenge.theirScore ?? 0) Punkte auf demselben Feld.").font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
                }
                Spacer()
                PixelButton(title: "Spielen", compact: true, action: play)
            }
        }
    }
}

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

private struct FriendCard: View {
    let friend: Friend
    let isPartner: Bool
    let onAction: (InteractionKind) -> Void
    let onGift: () -> Void
    let onGreet: () -> Void
    let onDetail: () -> Void
    let onPartner: () -> Void
    let onRemove: () -> Void
    let onBlock: () -> Void

    var body: some View {
        PixelWindow {
            HStack(spacing: 12) {
                FriendPortrait(skin: friend.slime.skin, stage: friend.stage, mood: friend.slime.mood,
                               accessories: friend.slime.equipped ?? Accessories())
                    .frame(width: 58, height: 48)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(friend.slime.name).font(Theme.title(18)).foregroundStyle(Theme.ink)
                        if friend.isDemo {
                            Text("TEST").font(Theme.label(10)).foregroundStyle(.white).padding(.horizontal, 4).background(Theme.sky)
                        }
                        if isPartner {
                            PixelItem(name: "heart").frame(width: 12, height: 9).accessibilityLabel("in deiner Insel")
                        }
                    }
                    Text("von \(friend.ownerName) · LV \(friend.slime.level)").font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
                }
                Spacer()
                Menu {
                    Button(isPartner ? "Ist in deiner Insel" : "In meiner Insel zeigen", action: onPartner).disabled(isPartner)
                    if !friend.isDemo {
                        Button("Erinnerungen und Garten", action: onDetail)
                        Button("Freund entfernen", role: .destructive, action: onRemove)
                        Button("Blockieren", role: .destructive, action: onBlock)
                    } else {
                        Button("Test-Freund ausblenden", role: .destructive, action: onRemove)
                    }
                } label: {
                    Image(systemName: "ellipsis").font(.system(size: 15, weight: .bold)).foregroundStyle(Theme.ink)
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("Mehr zu \(friend.slime.name)")
            }
            HStack(spacing: 8) {
                Text(friend.inLove ? "VERLIEBT" : "LIEBE").font(Theme.label(12)).foregroundStyle(Theme.love)
                BlockBar(value: Double(friend.love) / 100, color: Theme.love, height: 8)
                Text("\(friend.love) %").font(Theme.label(12)).foregroundStyle(Theme.love)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    PixelButton(title: "Umarmen", kind: .secondary, compact: true) { onAction(.hug) }
                    PixelButton(title: "Besuchen", kind: .secondary, compact: true) { onAction(.visit) }
                    PixelButton(title: "Geschenk", kind: .secondary, compact: true, action: onGift)
                    PixelButton(title: "Grüßen", kind: .secondary, compact: true, action: onGreet)
                    if friend.love >= 50 {
                        PixelButton(title: "Kuss", kind: .secondary, compact: true) { onAction(.kiss) }
                    }
                }
                .padding(.bottom, 4)
            }
        }
    }
}

/// Shared memories and the co-op garden with one friend.
struct FriendDetailView: View {
    let friend: Friend
    @EnvironmentObject private var friends: FriendsStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            MenuBackdrop()
            ScrollView {
                VStack(spacing: 14) {
                    MenuHeader(title: friend.slime.name, subtitle: "befreundet seit \(friend.since.formatted(date: .abbreviated, time: .omitted))") { dismiss() }
                    if let detail = friends.details[friend.id] {
                        PixelWindow(title: "Gemeinsamer Garten") {
                            Text("Jede Spielrunde von euch beiden lässt den Garten wachsen. Ist er diese Woche voll, freuen sich beide Slimes.")
                                .font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
                            BlockBar(value: min(1, Double(detail.coop.total) / Double(max(1, detail.coop.goal))), color: Theme.accent, blocks: 12, height: 10)
                            Text(detail.coop.done ? "Diese Woche geschafft." : "\(detail.coop.total) von \(detail.coop.goal) · du \(detail.coop.mine), \(friend.slime.name) \(detail.coop.theirs)")
                                .font(Theme.label(14)).foregroundStyle(Theme.ink)
                        }
                        PixelWindow(title: "Heute noch") {
                            ForEach(InteractionKind.allCases, id: \.self) { kind in
                                HStack {
                                    Text(kind.title).font(Theme.label(14)).foregroundStyle(Theme.ink)
                                    Spacer()
                                    Text("\(max(0, detail.today[kind.rawValue] ?? 0))×").font(Theme.label(14)).foregroundStyle(Theme.inkSoft)
                                }
                            }
                        }
                        PixelWindow(title: "Erinnerungen") {
                            if detail.memories.isEmpty {
                                Text("Noch keine. Umarmt euch mal.").font(Theme.text(14)).foregroundStyle(Theme.inkSoft)
                            }
                            ForEach(Array(detail.memories.enumerated()), id: \.offset) { _, memory in
                                HStack(alignment: .firstTextBaseline) {
                                    Text(memory.title).font(Theme.label(14)).foregroundStyle(Theme.ink)
                                    Spacer()
                                    Text(memory.at.formatted(date: .abbreviated, time: .omitted)).font(Theme.text(12)).foregroundStyle(Theme.inkSoft)
                                }
                            }
                        }
                    } else if let error = friends.lastError {
                        Text(error).font(Theme.text(14)).foregroundStyle(Theme.danger)
                    } else {
                        ProgressView().tint(Theme.ink)
                    }
                }
                .padding(16)
            }
        }
        .task { await friends.loadDetail(friend) }
    }
}

extension InteractionKind {
    var title: String {
        switch self {
        case .hug: "Umarmungen"
        case .visit: "Besuche"
        case .gift: "Geschenke"
        case .kiss: "Küsse"
        }
    }
}

extension FriendMemory {
    var title: String {
        switch kind {
        case "met": "Kennengelernt"
        case "first.hug": "Erste Umarmung"
        case "first.visit": "Erster Besuch"
        case "first.gift": "Erstes Geschenk"
        case "first.kiss": "Erster Kuss"
        case "love.25": "25 % Liebe"
        case "love.50": "Halb verliebt"
        case "love.75": "Fast verliebt"
        case "inLove": "Verliebt"
        case "firstChallengeWin": "Erste Herausforderung gewonnen"
        case "firstCoop": "Erster voller Garten"
        default: "Ein schöner Moment"
        }
    }
}

/// What friends can see, who may ask, and who is blocked.
struct FriendPrivacyView: View {
    @EnvironmentObject private var friends: FriendsStore
    @Environment(\.dismiss) private var dismiss
    @State private var confirmDelete = false

    var body: some View {
        ZStack {
            MenuBackdrop()
            ScrollView {
                VStack(spacing: 14) {
                    MenuHeader(title: "Privatsphäre") { dismiss() }
                    PixelWindow(title: "Was Freunde sehen") {
                        Toggle(isOn: Binding(get: { friends.privacy.shareMood },
                                             set: { v in Task { await friends.setPrivacy { $0.shareMood = v } } })) {
                            Text("Stimmung meines Slimes zeigen").font(Theme.label(15)).foregroundStyle(Theme.ink)
                        }
                        Toggle(isOn: Binding(get: { friends.privacy.acceptRequests },
                                             set: { v in Task { await friends.setPrivacy { $0.acceptRequests = v } } })) {
                            Text("Neue Anfragen erlauben").font(Theme.label(15)).foregroundStyle(Theme.ink)
                        }
                        Text("Freunde sehen den Namen deines Slimes, sein Aussehen, sein Level und deinen Namen. Nie deinen Standort, deine Kontakte oder was du am iPhone machst.")
                            .font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
                    }
                    PixelWindow(title: "Blockiert") {
                        if friends.blocked.isEmpty {
                            Text("Niemand.").font(Theme.text(14)).foregroundStyle(Theme.inkSoft)
                        }
                        ForEach(friends.blocked) { device in
                            HStack {
                                Text(device.ownerName).font(Theme.label(15)).foregroundStyle(Theme.ink)
                                Spacer()
                                Button("Freigeben") { Task { await friends.unblock(device) } }
                                    .font(Theme.label(13)).foregroundStyle(Theme.accentInk)
                            }
                        }
                    }
                    PixelWindow(title: "Daten auf dem Server") {
                        Text("Löscht dein Gerät, deine Freundschaften und alles, was Freunde dir geschickt haben, von slime.hyprnode.de. Dein Slime auf dem iPhone bleibt.")
                            .font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
                        if confirmDelete {
                            HStack(spacing: 10) {
                                PixelButton(title: "Abbrechen", kind: .secondary) { confirmDelete = false }
                                PixelButton(title: "Löschen", kind: .danger) {
                                    Task { if await friends.deleteAccount() { confirmDelete = false } }
                                }
                            }
                        } else {
                            PixelButton(title: "Server-Daten löschen …", kind: .quiet) { confirmDelete = true }
                        }
                    }
                    if let error = friends.lastError {
                        Text(error).font(Theme.text(14)).foregroundStyle(Theme.danger)
                    }
                }
                .padding(16)
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .task { await friends.loadBlocked() }
    }
}
