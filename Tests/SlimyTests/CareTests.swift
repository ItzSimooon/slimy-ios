import XCTest
@testable import Slimy

private let berlin: Calendar = {
    var c = Calendar(identifier: .gregorian)
    c.timeZone = TimeZone(identifier: "Europe/Berlin")!
    return c
}()

/// 4 May 2026, noon in Berlin: no season, holiday or birthday.
private let noon = berlin.date(from: DateComponents(year: 2026, month: 5, day: 4, hour: 12))!

private func at(_ seconds: Double) -> Date { noon.addingTimeInterval(seconds) }

/// Anna's phone: Blob is the slime that gets shared.
private func annasPhone() -> GameState {
    var state = GameState()
    state.hatch(name: "Blob", skin: .ocean, seed: 7, now: noon)
    state.settings.sleep.auto = false
    state.player.name = "Anna"
    return state
}

/// Ben's phone: his own slime Mochi waits while he cares for Blob.
private func bensPhone() -> GameState {
    var state = GameState()
    state.hatch(name: "Mochi", skin: .lime, seed: 9, now: noon.addingTimeInterval(-86400))
    state.settings.sleep.auto = false
    state.player.name = "Ben"
    state.wallet.coins = 250
    return state
}

private let annasLink = CareLink(partnerID: "dev_ben", partnerName: "Ben", role: CareLink.host, cursor: nil, version: 1)
private let bensLink = CareLink(partnerID: "dev_anna", partnerName: "Anna", role: CareLink.joiner, cursor: nil, version: 1)

/// Anna hosts, Ben joined with her slime.
private func paired() -> (anna: GameState, ben: GameState) {
    var anna = annasPhone()
    anna.startHosting(annasLink, now: at(60))
    var ben = bensPhone()
    ben.adoptShared(anna.sharedSnapshot(at: at(60)), link: bensLink, now: at(120))
    return (anna, ben)
}

final class CareActionTests: XCTestCase {
    func testLocalCareIsOnlySentWhileSharing() {
        var state = annasPhone()
        _ = state.pet(now: at(30))
        XCTAssertTrue(state.careBook.outbox.isEmpty)
        XCTAssertFalse(state.isSharing)

        state.startHosting(annasLink, now: at(60))
        XCTAssertTrue(state.isSharing)
        XCTAssertEqual(state.care?.partnerName, "Ben")
        XCTAssertNotNil(state.progress.achievements["careTogether"])
        state.slime.needs.hunger = 40
        XCTAssertTrue(state.feed(.apple, now: at(180)).ok)
        XCTAssertTrue(state.pet(now: at(240)).ok)
        XCTAssertTrue(state.finish(GameResult(game: .bubblePop, score: 200, session: UUID(), finishedAt: at(300))).ok)
        state.slime.needs.clean = 20
        XCTAssertTrue(state.bath(now: at(360)).ok)
        state.slime.needs.energy = 40
        XCTAssertTrue(state.sleep(now: at(420)).ok)
        XCTAssertTrue(state.wake(now: at(480)).ok)
        XCTAssertNotNil(state.claimDailyGift(now: at(540), calendar: berlin))

        let sent = state.takeCareOutbox()
        XCTAssertEqual(sent.map(\.kind), [.feed, .pet, .play, .bath, .sleep, .wake, .gift])
        XCTAssertEqual(sent[0].food, .apple)
        XCTAssertGreaterThan(sent[1].amount ?? 0, 0)
        XCTAssertEqual(sent[2].game, .bubblePop)
        XCTAssertEqual(sent[2].amount ?? 0, 50, accuracy: 0.001)
        XCTAssertTrue(sent.allSatisfy { $0.by == "Anna" })
        XCTAssertEqual(Set(sent.map(\.id)).count, sent.count)
        XCTAssertTrue(state.careBook.outbox.isEmpty)
        // Failed actions send nothing.
        state.slime.needs.hunger = 99
        XCTAssertFalse(state.feed(.apple, now: at(600)).ok)
        XCTAssertTrue(state.careBook.outbox.isEmpty)
    }

    func testTodaysGameIsSentWithoutAGame() {
        var state = annasPhone()
        state.startHosting(annasLink, now: at(60))
        _ = state.finish(GameResult(game: .daily, score: 50, session: UUID(), finishedAt: at(120), target: 100, day: "2026-05-04"))
        let sent = state.takeCareOutbox()
        XCTAssertEqual(sent.map(\.kind), [.play])
        XCTAssertNil(sent[0].game)
    }

    func testPartnerCareAppliesWithoutCoins() {
        var state = annasPhone()
        state.startHosting(annasLink, now: at(60))
        state.slime.needs.hunger = 30
        state.slime.emotions = Emotions()
        let coins = state.wallet.coins
        let feeds = state.progress.stats.feeds
        let xp = state.slime.xp
        let fed = CareEvent(id: "e1", kind: .feed, food: .carrot, game: nil, amount: nil, by: "Ben", at: at(100))
        XCTAssertEqual(state.applyPartnerCare(fed, now: at(120)), "Ben fed Blob")
        XCTAssertGreaterThan(state.slime.needs.hunger, 45)
        XCTAssertEqual(state.wallet.coins, coins)
        XCTAssertEqual(state.progress.stats.feeds, feeds)
        XCTAssertEqual(state.progress.stats.partnerCare, 1)
        XCTAssertEqual(state.slime.xp, xp + 6)
        XCTAssertGreaterThan(state.slime.emotions[.love], 0.1)
        XCTAssertGreaterThan(state.slime.emotions[.joy], 0.4)
        XCTAssertEqual(state.memory.last(.partnerCare)?.summary, "Ben fed Blob")
        XCTAssertTrue(state.memory.recentLines.last?.hasPrefix("partnerCared.") ?? false)
        XCTAssertTrue(state.careBook.outbox.isEmpty, "a partner's action is never sent back")
        XCTAssertTrue(state.chatterTopics(now: at(180), calendar: berlin).map(\.category).contains(.partnerCared))

        // The same action again changes nothing.
        let hunger = state.slime.needs.hunger
        XCTAssertNil(state.applyPartnerCare(fed, now: at(130)))
        XCTAssertEqual(state.slime.needs.hunger, hunger)
        XCTAssertEqual(state.progress.stats.partnerCare, 1)
    }

    func testEveryKindOfPartnerCare() {
        var state = annasPhone()
        state.startHosting(annasLink, now: at(60))
        state.slime.needs.fun = 20
        _ = state.applyPartnerCare(CareEvent(id: "p", kind: .play, food: nil, game: .bubblePop, amount: 100, by: "Ben",
                                             at: at(100)), now: at(100))
        XCTAssertGreaterThan(state.slime.needs.fun, 40)

        state.slime.needs.clean = 10
        _ = state.applyPartnerCare(CareEvent(id: "b", kind: .bath, food: nil, game: nil, amount: nil, by: "Ben",
                                             at: at(110)), now: at(110))
        XCTAssertEqual(state.slime.needs.clean, 100, accuracy: 0.001)

        state.slime.needs.love = 30
        _ = state.applyPartnerCare(CareEvent(id: "pe", kind: .pet, food: nil, game: nil, amount: 12, by: "Ben",
                                             at: at(120)), now: at(120))
        XCTAssertEqual(state.slime.needs.love, 42, accuracy: 0.5)

        XCTAssertEqual(state.applyPartnerCare(CareEvent(id: "s", kind: .sleep, food: nil, game: nil, amount: nil, by: "Ben",
                                                        at: at(130)), now: at(130)), "Ben put Blob to bed")
        XCTAssertTrue(state.slime.isAsleep)
        _ = state.applyPartnerCare(CareEvent(id: "w", kind: .wake, food: nil, game: nil, amount: nil, by: "Ben",
                                             at: at(140)), now: at(140))
        XCTAssertFalse(state.slime.isAsleep)

        // A bedtime long past is old news.
        XCTAssertNil(state.applyPartnerCare(CareEvent(id: "old", kind: .sleep, food: nil, game: nil, amount: nil, by: "Ben",
                                                      at: at(-3 * 3600)), now: at(150)))
        XCTAssertFalse(state.slime.isAsleep)

        let love = state.slime.needs.love
        XCTAssertEqual(state.applyPartnerCare(CareEvent(id: "g", kind: .gift, food: nil, game: nil, amount: nil, by: "",
                                                        at: at(160)), now: at(160)), "Ben gave Blob a present")
        XCTAssertGreaterThan(state.slime.needs.love, love)
        XCTAssertEqual(state.progress.stats.partnerCare, 6)
    }

    func testNothingAppliesWhenNotSharing() {
        var state = annasPhone()
        let event = CareEvent(id: "x", kind: .feed, food: .apple, game: nil, amount: nil, by: "Ben", at: at(10))
        XCTAssertNil(state.applyPartnerCare(event, now: at(20)))
        XCTAssertEqual(state.progress.stats.partnerCare, 0)
    }
}

final class CareSharingTests: XCTestCase {
    func testAdoptAndRestoreRoundTrip() throws {
        var anna = annasPhone()
        anna.startHosting(annasLink, now: at(60))
        let snapshot = anna.sharedSnapshot(at: at(60))
        XCTAssertEqual(snapshot.name, "Blob")
        XCTAssertEqual(snapshot.author, anna.careBook.me)
        XCTAssertEqual(snapshot.level, anna.slime.level)

        var ben = bensPhone()
        let coins = ben.wallet.coins
        ben.adoptShared(snapshot, link: bensLink, now: at(120))
        XCTAssertTrue(ben.isSharing)
        XCTAssertEqual(ben.slime.name, "Blob")
        XCTAssertEqual(ben.slime.skin, .ocean)
        XCTAssertEqual(ben.slime.xp, anna.slime.xp)
        XCTAssertEqual(ben.slime.personality, anna.slime.personality)
        XCTAssertEqual(ben.slime.favorite, anna.slime.favorite)
        XCTAssertEqual(ben.slime.birthday, anna.slime.birthday)
        let backup = try XCTUnwrap(ben.care?.ownBackup)
        XCTAssertEqual(backup.name, "Mochi")
        XCTAssertGreaterThanOrEqual(ben.wallet.coins, coins)
        XCTAssertEqual(ben.memory.last(.careStarted)?.detail, "Anna")
        XCTAssertNotEqual(ben.careBook.me, anna.careBook.me)

        // Three days later Ben stops: Mochi is back as it was left.
        ben.stopSharing(now: at(3 * 86400))
        XCTAssertFalse(ben.isSharing)
        XCTAssertEqual(ben.slime.name, "Mochi")
        XCTAssertEqual(ben.slime.xp, backup.xp)
        XCTAssertEqual(ben.slime.needs, backup.needs)
        XCTAssertEqual(ben.slime.personality, backup.personality)
        XCTAssertEqual(ben.memory.last(.careEnded)?.detail, "Anna")
        XCTAssertEqual(ben.careBook, CareBook(me: ben.careBook.me))

        // Anna keeps her slime.
        anna.stopSharing(now: at(200))
        XCTAssertNil(anna.care)
        XCTAssertEqual(anna.slime.name, "Blob")
    }

    func testJoiningAgainKeepsTheFirstBackup() throws {
        var (_, ben) = paired()
        let other = annasPhone().sharedSnapshot(at: at(300))
        ben.adoptShared(other, link: bensLink, now: at(400))
        XCTAssertEqual(try XCTUnwrap(ben.care?.ownBackup).name, "Mochi")
    }

    func testAColourFoundWhileSharingComesHome() {
        var (_, ben) = paired()
        ben.receive(Crates.legendary(for: ben))
        XCTAssertEqual(ben.slime.skin, .axolotl)
        ben.stopSharing(now: at(500))
        XCTAssertEqual(ben.slime.name, "Mochi")
        XCTAssertTrue(ben.slime.isUnlocked(.axolotl))
        XCTAssertEqual(ben.slime.skin, .lime)
    }

    func testActionsAlreadyInTheSnapshotAreSkipped() {
        var anna = annasPhone()
        anna.startHosting(annasLink, now: at(60))
        anna.slime.needs.hunger = 30
        XCTAssertTrue(anna.feed(.apple, now: at(100)).ok)
        let fed = anna.takeCareOutbox()[0]
        let snapshot = anna.sharedSnapshot(at: at(110))
        XCTAssertEqual(snapshot.updatedAt, at(100))

        var ben = bensPhone()
        ben.adoptShared(snapshot, link: bensLink, now: at(120))
        let hunger = ben.slime.needs.hunger
        XCTAssertNil(ben.applyPartnerCare(fed, now: at(130)))
        XCTAssertEqual(ben.slime.needs.hunger, hunger)

        let later = CareEvent(id: "later", kind: .pet, food: nil, game: nil, amount: 10, by: "Anna", at: at(200))
        XCTAssertEqual(ben.applyPartnerCare(later, now: at(210)), "Anna petted Blob")
    }

    func testReconcileTakesOnlyANewerSnapshot() {
        var (anna, ben) = paired()
        // Anna renames Blob and feeds it: her snapshot knows something newer.
        anna.slime.name = "Blobby"
        anna.noteSharedChange(now: at(300))
        anna.slime.needs.hunger = 30
        XCTAssertTrue(anna.feed(.apple, now: at(400)).ok)
        let newer = anna.sharedSnapshot(at: at(400))

        ben.slime.emotions = Emotions()
        ben.slime.emotions[.sadness] = 0.8
        XCTAssertTrue(ben.reconcile(with: newer, now: at(500)))
        XCTAssertEqual(ben.slime.name, "Blobby")
        XCTAssertEqual(ben.slime.xp, anna.slime.xp)
        // Feelings are blended, not replaced.
        XCTAssertGreaterThan(ben.slime.emotions[.sadness], 0.2)
        XCTAssertLessThan(ben.slime.emotions[.sadness], 0.7)

        // The same snapshot again is old news.
        XCTAssertFalse(ben.reconcile(with: newer, now: at(600)))

        // A change here after it: the older snapshot doesn't undo it.
        ben.slime.name = "Mine"
        ben.noteSharedChange(now: at(700))
        XCTAssertFalse(ben.reconcile(with: newer, now: at(800)))
        XCTAssertEqual(ben.slime.name, "Mine")

        // A snapshot this phone wrote itself is skipped.
        let own = ben.sharedSnapshot(at: at(700))
        var copy = ben
        copy.careBook.changedAt = .distantPast
        XCTAssertFalse(copy.reconcile(with: own, now: at(900)))
    }

    func testReconcileNeverLowersWhatOnlyGrows() {
        var (anna, ben) = paired()
        ben.slime.xp = 500
        ben.slime.bond = 70
        ben.slime.colours.insert(.grape)
        anna.noteSharedChange(now: at(300))
        XCTAssertTrue(ben.reconcile(with: anna.sharedSnapshot(at: at(300)), now: at(310)))
        XCTAssertEqual(ben.slime.xp, 500)
        XCTAssertEqual(ben.slime.bond, 70)
        XCTAssertTrue(ben.slime.colours.contains(.grape))
    }

    func testASharedSlimeKeepsWhatThePartnerDressedItIn() {
        var anna = annasPhone()
        anna.inventory.owned.insert("hat.crown")
        anna.equip("hat.crown", now: at(10))
        anna.startHosting(annasLink, now: at(60))
        var ben = bensPhone()
        ben.adoptShared(anna.sharedSnapshot(at: at(60)), link: bensLink, now: at(120))
        XCTAssertEqual(ben.slime.equipped.hat, "hat.crown")
        ben.sanitize(now: at(130))
        XCTAssertEqual(ben.slime.equipped.hat, "hat.crown")
        // Back on his own, Ben's slime wears only Ben's things.
        ben.stopSharing(now: at(200))
        XCTAssertNil(ben.slime.equipped.hat)
    }

    func testOnlyPersonalThingsStayPersonal() {
        var (_, ben) = paired()
        let coins = ben.wallet.coins
        let owned = ben.inventory.owned
        let records = ben.progress.records
        _ = ben.applyPartnerCare(CareEvent(id: "f", kind: .feed, food: .berry, game: nil, amount: nil, by: "Anna",
                                           at: at(300)), now: at(300))
        XCTAssertEqual(ben.wallet.coins, coins)
        XCTAssertEqual(ben.inventory.owned, owned)
        XCTAssertEqual(ben.progress.records, records)
    }
}

final class CareCodingTests: XCTestCase {
    func testSnapshotsSurviveTheTrip() throws {
        var (anna, _) = paired()
        anna.slime.colours.insert(.axolotl)
        let snapshot = anna.sharedSnapshot(at: at(60))
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let back = try decoder.decode(SlimeSnapshot.self, from: encoder.encode(snapshot))
        XCTAssertEqual(back, snapshot)
        let object = try XCTUnwrap(try JSONSerialization.jsonObject(with: encoder.encode(snapshot)) as? [String: Any])
        XCTAssertEqual(object["name"] as? String, "Blob")
    }

    func testSnapshotsFromOtherVersionsStillRead() throws {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let json = """
        {"name": "Blob", "updatedAt": "2026-05-04T10:00:00Z", "skin": "rainbow", "xp": 130,
         "needs": {"hunger": 50, "fun": 50, "energy": 50, "clean": 50, "love": 50},
         "colours": ["axolotl", "sparkly"], "favorite": "pizza", "somethingNew": true}
        """
        let odd = try decoder.decode(SlimeSnapshot.self, from: Data(json.utf8))
        XCTAssertEqual(odd.skin, .lime)
        XCTAssertEqual(odd.xp, 130)
        XCTAssertEqual(odd.level, Slime.level(forXP: 130))
        XCTAssertEqual(odd.colours, [.axolotl])
        XCTAssertEqual(odd.favorite, Slime().favorite)
        XCTAssertEqual(odd.takenAt, odd.updatedAt)
        XCTAssertNil(odd.author)
        // Without a name or a time it isn't a snapshot.
        XCTAssertThrowsError(try decoder.decode(SlimeSnapshot.self, from: Data(#"{"xp": 3}"#.utf8)))
        XCTAssertThrowsError(try decoder.decode(SlimeSnapshot.self, from: Data(#"{"name": "Blob"}"#.utf8)))
    }

    func testCareEventsReadTolerantly() throws {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let json = #"{"id": "e1", "kind": "feed", "food": "pizza", "amount": 12, "at": "2026-05-04T10:00:00Z"}"#
        let event = try decoder.decode(CareEvent.self, from: Data(json.utf8))
        XCTAssertEqual(event.kind, .feed)
        XCTAssertNil(event.food)
        XCTAssertEqual(event.amount, 12)
        XCTAssertEqual(event.by, "")
        XCTAssertEqual(event.summary(slime: "Blob"), "Your partner fed Blob")
        XCTAssertThrowsError(try decoder.decode(CareEvent.self, from: Data(#"{"id": "e2", "kind": "dance", "at": "2026-05-04T10:00:00Z"}"#.utf8)))
    }

    func testCaringTogetherSurvivesASaveRoundTrip() throws {
        var (_, ben) = paired()
        _ = ben.pet(now: at(300))
        XCTAssertEqual(ben.careBook.outbox.count, 1)
        let data = try GameStorage.encoder.encode(ben)
        let back = try GameStorage.decoder.decode(GameState.self, from: data)
        XCTAssertEqual(back.care, ben.care)
        XCTAssertEqual(back.careBook, ben.careBook)
        XCTAssertEqual(back.slime, ben.slime)
    }

    func testAnImportedSaveLeavesCaringTogetherBehind() throws {
        var (_, ben) = paired()
        _ = ben.pet(now: at(300))
        let data = try SaveFile.encode(ben, at: at(400), appVersion: "1.1.0")
        let back = try SaveFile.decode(data, now: at(500))
        XCTAssertNil(back.care)
        XCTAssertEqual(back.slime.name, "Mochi")
        XCTAssertTrue(back.careBook.outbox.isEmpty)
        XCTAssertNil(back.memory.last(.careEnded))
    }

    func testPartnerNotesReadBack() {
        let note = PartnerCareNote(person: "An|na", kind: .bath, slime: "Blob")
        XCTAssertEqual(PartnerCareNote(detail: note.detail), note)
        XCTAssertEqual(note.summary, "An/na gave Blob a bath")
        XCTAssertNil(PartnerCareNote(detail: "Anna|dance|Blob"))
        XCTAssertEqual(MemoryEntry(kind: .partnerCare, at: noon, detail: note.detail).icon, CareKind.bath.icon)
    }
}
