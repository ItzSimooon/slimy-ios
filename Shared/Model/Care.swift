import Foundation

// MARK: - Caring together
//
// Two people care for one slime. The host's slime is the shared one; the
// joiner's own slime waits safely on their phone (`CareLink.ownBackup`).
// Every care action on one phone becomes a `CareEvent` that the other phone
// applies; snapshots of the shared slime settle any drift: a snapshot is
// taken over when the phone that wrote it knew of a newer change.
// Coins, things owned, records, challenges and settings stay personal.

/// A care action that reaches the other phone.
enum CareKind: String, Codable, CaseIterable {
    case feed, play, bath, pet, sleep, wake, gift

    /// Item sprite for the memory book.
    var icon: String {
        switch self {
        case .feed: "apple"
        case .play: "ball"
        case .bath: "bubbleItem"
        case .pet: "heart"
        case .sleep: "starJelly"
        case .wake: "flower"
        case .gift: "chest"
        }
    }

    /// "Anna fed Blob".
    func sentence(by person: String, slime: String) -> String {
        switch self {
        case .feed: "\(person) fed \(slime)"
        case .play: "\(person) played with \(slime)"
        case .bath: "\(person) gave \(slime) a bath"
        case .pet: "\(person) petted \(slime)"
        case .sleep: "\(person) put \(slime) to bed"
        case .wake: "\(person) woke \(slime) up"
        case .gift: "\(person) gave \(slime) a present"
        }
    }
}

/// One care action. `amount` carries what the kind needs: for `pet` the love
/// it gave, for `play` the score as a percentage of a good round (0...200).
/// The other kinds leave it nil.
struct CareEvent: Codable, Equatable, Identifiable {
    let id: String
    let kind: CareKind
    let food: Food?
    let game: GameID?
    let amount: Double?
    /// Who did it: the partner's name (your own on actions you send).
    let by: String
    let at: Date

    /// "Anna fed Blob".
    func summary(slime name: String) -> String {
        let person = by.trimmingCharacters(in: .whitespacesAndNewlines)
        return kind.sentence(by: person.isEmpty ? "Your partner" : person, slime: name)
    }
}

/// The shared slime as it travels between the two phones.
struct SlimeSnapshot: Codable, Equatable {
    var name: String
    var skin: Skin
    var level: Int
    var xp: Int
    var needs: Needs
    var bond: Double
    var personality: Personality
    var equipped: Accessories
    var emotions: Emotions
    var asleep: Bool
    var favorite: Food
    var favoriteGame: GameID
    var hatchedAt: Date
    /// When the slime last changed on the phone that wrote it (a care
    /// action, a new look, an action from the other phone). The newer wins.
    var updatedAt: Date
    /// When its needs and feelings were as written here.
    var takenAt: Date
    /// Colours it has besides the ones its level opens.
    var colours: Set<Skin>
    /// Which phone wrote it (`CareBook.me`), so a phone skips its own.
    var author: String?
}

/// Who you care for the slime with. The online code keeps `cursor` and
/// `version` up to date and may replace the whole link.
struct CareLink: Codable, Equatable {
    var partnerID: String
    var partnerName: String
    /// "host" or "joiner".
    var role: String
    var cursor: String?
    var version: Int
    /// The joiner's own slime, kept safe on this phone while the shared one
    /// is here. Comes back when caring together ends.
    var ownBackup: Slime?

    static let host = "host"
    static let joiner = "joiner"

    var isHost: Bool { role == Self.host }
}

/// This phone's side of caring together. Kept apart from `CareLink`, which
/// the online code replaces as a whole.
struct CareBook: Codable, Equatable {
    /// This phone's mark on the snapshots it writes; new for every pairing.
    var me = UUID().uuidString
    /// When the shared slime last changed here: a care action, the
    /// partner's action, a new look, or a newer snapshot taken over.
    var changedAt = Date.distantPast
    /// The partner's actions up to this time are in the snapshot taken over.
    var adoptedAt: Date?
    /// The partner's actions already applied (ids), newest last.
    var seen: [String] = []
    /// Care actions done on this phone, waiting to be sent.
    var outbox: [CareEvent] = []
    /// Colours found in crates while caring together: they come home with
    /// the joiner's own slime.
    var coloursFound: Set<Skin> = []

    static let seenLimit = 300
    static let outboxLimit = 50

    /// A new pairing's book.
    static func fresh(changedAt: Date) -> CareBook {
        var book = CareBook()
        book.changedAt = changedAt
        return book
    }

    /// Keeps the lists short and the times out of the future, so a clock
    /// set back can't block the other phone's snapshots.
    mutating func sanitize(now: Date) {
        if seen.count > Self.seenLimit { seen.removeFirst(seen.count - Self.seenLimit) }
        if outbox.count > Self.outboxLimit { outbox.removeFirst(outbox.count - Self.outboxLimit) }
        if changedAt > now { changedAt = now }
        if let adopted = adoptedAt, adopted > now.addingTimeInterval(24 * 3600) { adoptedAt = now }
    }
}

/// A partner's care action as the memory book keeps it: "Anna|feed|Blob".
struct PartnerCareNote: Hashable {
    var person: String
    var kind: CareKind
    var slime: String

    init(person: String, kind: CareKind, slime: String) {
        self.person = person.replacingOccurrences(of: "|", with: "/")
        self.kind = kind
        self.slime = slime.replacingOccurrences(of: "|", with: "/")
    }

    init?(detail: String) {
        let parts = detail.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 3, let kind = CareKind(rawValue: parts[1]), !parts[0].isEmpty else { return nil }
        self.init(person: parts[0], kind: kind, slime: parts[2].isEmpty ? "your slime" : parts[2])
    }

    var detail: String { "\(person)|\(kind.rawValue)|\(slime)" }

    /// "Anna fed Blob".
    var summary: String { kind.sentence(by: person, slime: slime) }
}

// MARK: - Snapshots

extension SlimeSnapshot {
    /// The server checks the name but doesn't shorten it: it goes out at
    /// most 16 characters long, like everywhere else.
    init(_ slime: Slime, updatedAt: Date, author: String?) {
        self.init(name: String(slime.name.prefix(16)), skin: slime.skin, level: slime.level, xp: slime.xp, needs: slime.needs,
                  bond: slime.bond, personality: slime.personality, equipped: slime.equipped,
                  emotions: slime.emotions, asleep: slime.isAsleep, favorite: slime.favorite,
                  favoriteGame: slime.favoriteGame, hatchedAt: slime.birthday, updatedAt: updatedAt,
                  takenAt: slime.lastUpdate, colours: slime.colours, author: author)
    }

    /// `slime` with the shared fields from here, checked; what it's saying
    /// and its reaction stay.
    func applied(to slime: Slime) -> Slime {
        var s = slime
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { s.name = String(trimmed.prefix(16)) }
        s.skin = skin
        s.xp = max(0, xp)
        s.needs = needs
        s.needs.sanitize()
        s.bond = min(100, max(0, bond.isFinite ? bond : 0))
        var p = personality
        for trait in Personality.Trait.allCases { p[trait] = p[trait].isFinite ? p[trait] : 0.5 }
        s.personality = p
        s.equipped = Accessories(hat: Self.wearable(equipped.hat, .hat),
                                 glasses: Self.wearable(equipped.glasses, .glasses),
                                 scarf: Self.wearable(equipped.scarf, .scarf),
                                 effect: Self.wearable(equipped.effect, .effect))
        s.emotions = emotions
        s.isAsleep = asleep
        s.favorite = favorite
        s.favoriteGame = favoriteGame == .daily ? s.favoriteGame : favoriteGame
        s.birthday = hatchedAt
        s.colours = colours
        return s
    }

    /// An accessory id only if this app knows it as that kind.
    private static func wearable(_ id: String?, _ kind: ItemKind) -> String? {
        guard let id, Catalog.item(id)?.kind == kind else { return nil }
        return id
    }
}

// MARK: - Tolerant decoding
//
// Snapshots and events come from the other phone, which may run another
// version: unknown or missing values fall back instead of failing.

extension CareEvent {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        kind = try c.decode(CareKind.self, forKey: .kind)
        food = c.value(.food, nil)
        game = c.value(.game, nil)
        let amount: Double? = c.value(.amount, nil)
        self.amount = amount.flatMap { $0.isFinite ? $0 : nil }
        by = c.value(.by, "")
        at = try c.decode(Date.self, forKey: .at)
    }
}

extension SlimeSnapshot {
    /// A snapshot needs at least a name and a time; everything else falls
    /// back to a newborn's values.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = Slime()
        let stamp = try c.decode(Date.self, forKey: .updatedAt)
        let points = max(0, c.value(.xp, d.xp))
        name = try c.decode(String.self, forKey: .name)
        updatedAt = stamp
        skin = c.value(.skin, d.skin)
        xp = points
        level = c.value(.level, Slime.level(forXP: points))
        needs = c.value(.needs, d.needs)
        bond = c.value(.bond, d.bond)
        personality = c.value(.personality, d.personality)
        equipped = c.value(.equipped, d.equipped)
        emotions = c.value(.emotions, d.emotions)
        asleep = c.value(.asleep, d.isAsleep)
        favorite = c.value(.favorite, d.favorite)
        favoriteGame = c.value(.favoriteGame, d.favoriteGame)
        hatchedAt = c.value(.hatchedAt, stamp)
        takenAt = c.value(.takenAt, stamp)
        colours = Skin.lossySet(c.value(.colours, [String]()))
        author = c.value(.author, nil)
    }
}

extension CareLink {
    /// A link without a partner is no link: it fails, and the save loads
    /// as not caring together.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let partner = try c.decode(String.self, forKey: .partnerID)
        guard !partner.isEmpty else {
            throw DecodingError.dataCorruptedError(forKey: .partnerID, in: c, debugDescription: "No partner.")
        }
        let backup: Slime? = c.value(.ownBackup, nil)
        let decodedRole: String = c.value(.role, backup == nil ? CareLink.host : CareLink.joiner)
        partnerID = partner
        partnerName = c.value(.partnerName, "")
        role = [CareLink.host, CareLink.joiner].contains(decodedRole) ? decodedRole
            : (backup == nil ? CareLink.host : CareLink.joiner)
        cursor = c.value(.cursor, nil)
        version = c.value(.version, 0)
        ownBackup = backup
    }
}

extension CareBook {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = CareBook()
        me = c.value(.me, d.me)
        changedAt = c.value(.changedAt, d.changedAt)
        adoptedAt = c.value(.adoptedAt, d.adoptedAt)
        seen = c.value(.seen, d.seen)
        let outbox: [LossyCareEvent]? = c.value(.outbox, nil)
        self.outbox = outbox?.compactMap(\.value) ?? d.outbox
        coloursFound = Skin.lossySet(c.value(.coloursFound, [String]()))
    }
}

/// One care event of a list, or nothing if it can't be read.
private struct LossyCareEvent: Decodable {
    let value: CareEvent?

    init(from decoder: Decoder) {
        value = try? CareEvent(from: decoder)
    }
}

// MARK: - Rules

extension GameState {
    var isSharing: Bool { care != nil }

    /// Notes a care action done on this phone, to be sent to the partner.
    /// Does nothing unless you care for the slime together.
    mutating func noteCare(_ kind: CareKind, food: Food? = nil, game: GameID? = nil, amount: Double? = nil,
                           now: Date) {
        guard care != nil else { return }
        let event = CareEvent(id: UUID().uuidString, kind: kind, food: food,
                              game: game.flatMap { $0 == .daily ? nil : $0 },
                              amount: amount.flatMap { $0.isFinite ? min(200, max(0, $0)) : nil },
                              by: player.name, at: now)
        careBook.outbox.append(event)
        if careBook.outbox.count > CareBook.outboxLimit {
            careBook.outbox.removeFirst(careBook.outbox.count - CareBook.outboxLimit)
        }
        careBook.changedAt = max(careBook.changedAt, now)
    }

    /// Hands over the care actions waiting to be sent, and forgets them.
    mutating func takeCareOutbox() -> [CareEvent] {
        let out = careBook.outbox
        careBook.outbox = []
        return out
    }

    /// The shared slime's look changed on this phone (name, colour,
    /// accessories): the next snapshot should win on the other phone.
    mutating func noteSharedChange(now: Date) {
        guard care != nil else { return }
        careBook.changedAt = max(careBook.changedAt, now)
    }

    /// The shared slime as the other phone should see it.
    func sharedSnapshot(at now: Date) -> SlimeSnapshot {
        let stamp = care == nil ? now : min(max(careBook.changedAt, slime.birthday), now)
        return SlimeSnapshot(slime, updatedAt: stamp, author: careBook.me)
    }

    /// The host's side: someone joined with your code. Just keeps the link;
    /// your slime is the shared one.
    mutating func startHosting(_ link: CareLink, now: Date) {
        tick(now: now)
        let alreadySharing = care != nil
        care = link
        guard !alreadySharing else { return }
        careBook = .fresh(changedAt: now)
        memory.add(MemoryEntry(kind: .careStarted, at: now, detail: link.partnerName, keepsake: true))
        welcome(link.partnerName, now: now)
        checkAchievements(now: now)
    }

    /// The joiner's side: the shared slime moves in. Your own slime is kept
    /// in `link.ownBackup` (the first one, if you join again).
    mutating func adoptShared(_ snapshot: SlimeSnapshot, link: CareLink, now: Date) {
        tick(now: now)
        var link = link
        if link.ownBackup == nil {
            if let current = care {
                link.ownBackup = current.ownBackup ?? (current.isHost ? slime : nil)
            } else if hatched {
                link.ownBackup = slime
            }
        }
        var shared = snapshot.applied(to: Slime())
        shared.lastUpdate = min(snapshot.takenAt, now)
        slime = shared
        hatched = true
        care = link
        careBook = .fresh(changedAt: min(snapshot.updatedAt, now))
        careBook.adoptedAt = snapshot.updatedAt
        slime.advance(to: now, schedule: settings.sleep)
        memory.add(MemoryEntry(kind: .careStarted, at: now, detail: link.partnerName, keepsake: true))
        welcome(link.partnerName, now: now)
        refreshChallenges(now: now)
        checkAchievements(now: now)
    }

    /// Takes over a snapshot from the other phone if it knew of a newer
    /// change than this phone. Feelings are blended rather than replaced;
    /// what only ever grows (xp, bond, colours) never goes down.
    /// Returns whether anything changed.
    @discardableResult
    mutating func reconcile(with snapshot: SlimeSnapshot, now: Date) -> Bool {
        guard care != nil else { return false }
        if let author = snapshot.author, author == careBook.me { return false }
        let stamp = min(snapshot.updatedAt, now)
        guard stamp > careBook.changedAt else { return false }
        tick(now: now)
        let local = slime
        var s = snapshot.applied(to: slime)
        s.emotions = local.emotions.blended(with: snapshot.emotions, weight: 0.5)
        s.xp = max(local.xp, s.xp)
        s.bond = max(local.bond, s.bond)
        s.colours.formUnion(local.colours)
        s.lastUpdate = min(snapshot.takenAt, now)
        slime = s
        slime.advance(to: now, schedule: settings.sleep)
        careBook.changedAt = stamp
        careBook.adoptedAt = max(careBook.adoptedAt ?? .distantPast, snapshot.updatedAt)
        return true
    }

    /// Applies one of the partner's care actions to the shared slime: the
    /// same effect as here, but no coins. Each action counts once; actions
    /// already in a snapshot taken over, and bedtimes that are long past,
    /// are skipped. Returns "Anna fed Blob", or nil if skipped.
    @discardableResult
    mutating func applyPartnerCare(_ event: CareEvent, now: Date) -> String? {
        guard let link = care, !careBook.seen.contains(event.id) else { return nil }
        careBook.seen.append(event.id)
        if careBook.seen.count > CareBook.seenLimit {
            careBook.seen.removeFirst(careBook.seen.count - CareBook.seenLimit)
        }
        if let adopted = careBook.adoptedAt, event.at <= adopted { return nil }
        if event.kind == .sleep || event.kind == .wake, now.timeIntervalSince(event.at) > 30 * 60 { return nil }
        tick(now: now)

        switch event.kind {
        case .feed:
            let food = event.food ?? .apple
            let favorite = food == slime.favorite
            slime.isAsleep = false
            slime.needs.add(.hunger, food.nutrition * (favorite ? 1.3 : 1))
            slime.needs.add(.fun, food.funBonus)
            if food == .starJelly {
                for need in Need.allCases { slime.needs.add(need, 20) }
            }
            if favorite {
                slime.needs.add(.love, 6)
                slime.feel(.love, 0.15)
            }
            slime.bond = min(100, slime.bond + 0.5)
            slime.personality.drift(.foodie)
            slime.feel(.joy, 0.2)
            slime.xp += 6
            slime.react(.eat, at: now)
        case .play:
            let game = event.game.flatMap { $0 == .daily ? nil : $0 } ?? slime.favoriteGame
            let ratio = max(0, min(2, (event.amount ?? 50) / 100))
            let favorite = game == slime.favoriteGame
            slime.isAsleep = false
            slime.needs.add(.fun, (14 + min(16, ratio * 10)) * (favorite ? 1.3 : 1))
            slime.needs.add(.energy, -game.energyCost)
            slime.needs.add(.clean, -5)
            slime.needs.add(.hunger, -4)
            if favorite { slime.bond = min(100, slime.bond + 1) }
            slime.personality.drift(.playful)
            slime.feel(.excitement, 0.25)
            slime.xp += min(30, 6 + Int((ratio * 14).rounded()))
            slime.react(.play, at: now)
        case .bath:
            slime.isAsleep = false
            slime.needs.clean = 100
            slime.needs.add(.love, 4)
            slime.bond = min(100, slime.bond + 0.5)
            slime.feel(.joy, 0.2)
            slime.xp += 6
            slime.react(.bath, at: now)
        case .pet:
            slime.needs.add(.love, max(0, min(14, event.amount ?? 8)))
            slime.needs.add(.fun, 2)
            slime.bond = min(100, slime.bond + 1)
            slime.personality.drift(.cuddly, by: 0.002)
            slime.feel(.love, 0.15)
            slime.xp += 3
            if !slime.isAsleep { slime.react(.love, at: now) }
        case .sleep:
            slime.isAsleep = true
            slime.reaction = .none
            slime.personality.drift(.sleepy, by: 0.002)
        case .wake:
            slime.isAsleep = false
            slime.react(.stretch, at: now)
        case .gift:
            slime.isAsleep = false
            slime.needs.add(.love, 5)
            slime.needs.add(.fun, 4)
            slime.bond = min(100, slime.bond + 0.5)
            slime.feel(.excitement, 0.15)
            slime.xp += Streak.xp
            slime.react(.celebrate, at: now)
        }
        // Being looked after by two people feels good.
        slime.feel(.love, 0.12)
        slime.feel(.joy, 0.12)
        progress.stats.partnerCare += 1

        let given = event.by.trimmingCharacters(in: .whitespacesAndNewlines)
        let named = link.partnerName.trimmingCharacters(in: .whitespacesAndNewlines)
        let person = given.isEmpty ? (named.isEmpty ? "Your partner" : named) : given
        let note = PartnerCareNote(person: person, kind: event.kind, slime: slime.name)
        memory.add(MemoryEntry(kind: .partnerCare, at: now, detail: note.detail))
        careBook.changedAt = max(careBook.changedAt, min(event.at, now))
        if !slime.isAsleep {
            let line = Dialogue.say(.partnerCared, in: &self, context: LineContext(friend: person), at: now)
            slime.say = Utterance(text: line, at: now)
        }
        checkAchievements(now: now)
        return note.summary
    }

    /// Ends caring together on this phone. The joiner gets their own slime
    /// back (with any colour they found meanwhile); the host keeps the
    /// slime. `quietly` leaves no memory or words (a save being imported).
    mutating func stopSharing(now: Date, quietly: Bool = false) {
        guard let link = care else { return }
        tick(now: now)
        if link.role == CareLink.joiner, var own = link.ownBackup {
            own.colours.formUnion(careBook.coloursFound)
            // It waited safely: no time passed for it.
            own.lastUpdate = now
            own.say = nil
            own.reaction = .none
            own.reactionAt = .distantPast
            slime = own
        }
        care = nil
        careBook = CareBook()
        // Back to wearing only your own things.
        for id in slime.equipped.all where !inventory.owned.contains(id) {
            unequip(id)
        }
        guard !quietly else { return }
        memory.add(MemoryEntry(kind: .careEnded, at: now, detail: link.partnerName))
        slime.feel(.joy, 0.2)
        slime.feel(.love, 0.15)
        if !slime.isAsleep {
            slime.react(.wave, at: now)
            let line = Dialogue.say(.careEnded, in: &self, context: LineContext(friend: link.partnerName), at: now)
            slime.say = Utterance(text: line, at: now)
        }
    }

    /// The slime meets its second caretaker.
    private mutating func welcome(_ partner: String, now: Date) {
        slime.feel(.joy, 0.3)
        slime.feel(.curiosity, 0.25)
        slime.feel(.love, 0.1)
        guard !slime.isAsleep else { return }
        slime.react(.wave, at: now)
        let line = Dialogue.say(.careStarted, in: &self, context: LineContext(friend: partner), at: now)
        slime.say = Utterance(text: line, at: now)
    }
}
