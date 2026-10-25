import XCTest
@testable import Slimy

/// Fixed clock for every test: 25 Oct 2026, 12:00 in Berlin.
private let berlin: Calendar = {
    var c = Calendar(identifier: .gregorian)
    c.timeZone = TimeZone(identifier: "Europe/Berlin")!
    return c
}()
private let noon = berlin.date(from: DateComponents(year: 2026, month: 10, day: 25, hour: 12))!

private func hatchedState(at now: Date = noon) -> GameState {
    var state = GameState()
    state.hatch(name: "Testi", skin: .lime, seed: 42, now: now)
    state.settings.sleep.auto = false
    return state
}

final class NeedsTests: XCTestCase {
    func testValuesStayInRange() {
        var needs = Needs()
        needs.add(.hunger, 500)
        needs.add(.fun, -500)
        needs[.love] = .nan
        XCTAssertEqual(needs.hunger, 100)
        XCTAssertEqual(needs.fun, 0)
        XCTAssertEqual(needs.love, 50)
    }

    func testLowest() {
        var needs = Needs()
        needs.clean = 3
        XCTAssertEqual(needs.lowest.need, .clean)
    }
}

final class TimeTests: XCTestCase {
    func testNeedsDecayWhileAway() {
        var slime = hatchedState().slime
        let before = slime.needs
        slime.advance(to: noon.addingTimeInterval(3 * 3600), schedule: SleepSchedule(bedtime: 22, wake: 7, auto: false), calendar: berlin)
        XCTAssertLessThan(slime.needs.hunger, before.hunger)
        XCTAssertLessThan(slime.needs.fun, before.fun)
    }

    func testLongAbsenceNeverDropsBelowRestingFloor() {
        var slime = hatchedState().slime
        slime.advance(to: noon.addingTimeInterval(90 * 24 * 3600), schedule: SleepSchedule(), calendar: berlin)
        for need in Need.allCases {
            XCTAssertGreaterThanOrEqual(slime.needs[need], Slime.restingFloor - 0.001, "\(need)")
        }
    }

    func testClockMovingBackwardsChangesNothing() {
        var slime = hatchedState().slime
        let before = slime.needs
        slime.advance(to: noon.addingTimeInterval(-6 * 3600), schedule: SleepSchedule(), calendar: berlin)
        XCTAssertEqual(slime.needs, before)
        XCTAssertEqual(slime.lastUpdate, noon.addingTimeInterval(-6 * 3600))
        // And going forward again from there works normally.
        slime.advance(to: noon, schedule: SleepSchedule(auto: false), calendar: berlin)
        XCTAssertLessThan(slime.needs.hunger, before.hunger)
    }

    func testFallsAsleepAtBedtimeAndWakesInTheMorning() {
        var slime = hatchedState().slime
        let evening = berlin.date(from: DateComponents(year: 2026, month: 10, day: 25, hour: 21, minute: 50))!
        slime.lastUpdate = evening
        slime.advance(to: evening.addingTimeInterval(30 * 60), schedule: SleepSchedule(bedtime: 22, wake: 7), calendar: berlin)
        XCTAssertTrue(slime.isAsleep)
        let morning = berlin.date(from: DateComponents(year: 2026, month: 10, day: 26, hour: 7, minute: 20))!
        slime.advance(to: morning, schedule: SleepSchedule(bedtime: 22, wake: 7), calendar: berlin)
        XCTAssertFalse(slime.isAsleep)
    }
}

final class ProgressionTests: XCTestCase {
    func testLevelCurve() {
        XCTAssertEqual(Slime.level(forXP: 0), 1)
        XCTAssertEqual(Slime.level(forXP: 39), 1)
        XCTAssertEqual(Slime.level(forXP: 40), 2)
        XCTAssertEqual(Slime.level(forXP: 119), 2)
        XCTAssertEqual(Slime.level(forXP: 120), 3)
        for level in 1...30 {
            XCTAssertEqual(Slime.level(forXP: Slime.xpNeeded(for: level)), level)
        }
    }

    func testStages() {
        XCTAssertEqual(Stage.forLevel(1), .baby)
        XCTAssertEqual(Stage.forLevel(3), .baby)
        XCTAssertEqual(Stage.forLevel(4), .young)
        XCTAssertEqual(Stage.forLevel(11), .young)
        XCTAssertEqual(Stage.forLevel(12), .grown)
    }

    func testLevelRewardIsPaidOnce() {
        var state = hatchedState()
        let coins = state.wallet.coins
        state.slime.xp = Slime.xpNeeded(for: 2) - 1
        _ = state.pet(now: noon)
        XCTAssertGreaterThanOrEqual(state.slime.level, 2)
        XCTAssertTrue(state.wallet.hasClaimed("level:2"))
        let afterLevel = state.wallet.coins
        XCTAssertGreaterThan(afterLevel, coins)
        XCTAssertFalse(state.wallet.claim("level:2", coins: 20, at: noon))
        XCTAssertEqual(state.wallet.coins, afterLevel)
    }
}

final class EconomyTests: XCTestCase {
    func testWalletClaimIsIdempotent() {
        var wallet = Wallet()
        XCTAssertTrue(wallet.claim("x", coins: 10, at: noon))
        XCTAssertFalse(wallet.claim("x", coins: 10, at: noon))
        XCTAssertEqual(wallet.coins, 40)
        XCTAssertEqual(wallet.earnedTotal, 10)
    }

    func testCannotSpendMoreThanYouHaveOrNegative() {
        var wallet = Wallet()
        XCTAssertFalse(wallet.spend(31))
        XCTAssertFalse(wallet.spend(-5))
        XCTAssertTrue(wallet.spend(30))
        XCTAssertEqual(wallet.coins, 0)
    }

    func testPruneKeepsPermanentReceipts() {
        var wallet = Wallet()
        let old = noon.addingTimeInterval(-90 * 24 * 3600)
        wallet.claim("game:old", coins: 1, at: old)
        wallet.claim("level:3", coins: 1, at: old)
        wallet.claim("buy:hat.party", coins: 0, at: old)
        wallet.prune(now: noon)
        XCTAssertFalse(wallet.hasClaimed("game:old"))
        XCTAssertTrue(wallet.hasClaimed("level:3"))
        XCTAssertTrue(wallet.hasClaimed("buy:hat.party"))
    }

    func testGameRewardsAreBounded() {
        for game in GameID.allCases {
            let none = GameResult(game: game, score: 0, session: UUID(), finishedAt: noon)
            let negative = GameResult(game: game, score: -50, session: UUID(), finishedAt: noon)
            let huge = GameResult(game: game, score: 1_000_000, session: UUID(), finishedAt: noon)
            let good = GameResult(game: game, score: game.goodScore, session: UUID(), finishedAt: noon)
            XCTAssertEqual(none.coins, 2)
            XCTAssertEqual(negative.coins, 2)
            XCTAssertEqual(huge.coins, 30)
            XCTAssertEqual(good.coins, 14)
            XCTAssertLessThanOrEqual(huge.xp, 30)
        }
    }

    func testFinishedRoundPaysExactlyOnce() {
        var state = hatchedState()
        let result = GameResult(game: .bubblePop, score: 420, session: UUID(), finishedAt: noon)
        let first = state.finish(result)
        XCTAssertTrue(first.ok)
        let coins = state.wallet.coins
        let xp = state.slime.xp
        let plays = state.progress.stats.plays
        let second = state.finish(result)
        XCTAssertFalse(second.ok)
        XCTAssertEqual(state.wallet.coins, coins)
        XCTAssertEqual(state.slime.xp, xp)
        XCTAssertEqual(state.progress.stats.plays, plays)
        XCTAssertEqual(state.record(.bubblePop).best, 420)
        XCTAssertEqual(state.record(.bubblePop).plays, 1)
    }

    func testAchievementPaysOnce() {
        var state = hatchedState()
        _ = state.finish(GameResult(game: .colorMatch, score: 5, session: UUID(), finishedAt: noon))
        XCTAssertNotNil(state.progress.achievements["arcadeRookie"])
        let coins = state.wallet.coins
        _ = state.checkAchievements(now: noon)
        XCTAssertEqual(state.wallet.coins, coins)
    }

    func testDailyChallengesAreDeterministicAndDistinct() {
        let a = Challenges.pick(Challenges.dailyPool, count: 3, key: "2026-10-25").map(\.id)
        let b = Challenges.pick(Challenges.dailyPool, count: 3, key: "2026-10-25").map(\.id)
        XCTAssertEqual(a, b)
        XCTAssertEqual(Set(a).count, 3)
    }

    func testChallengeClaimsOnlyWhenDoneAndOnlyOnce() {
        var state = hatchedState()
        state.refreshChallenges(now: noon, calendar: berlin)
        guard let challenge = state.progress.challenges.daily.first,
              let template = Challenges.template(challenge.id) else { return XCTFail("no challenge") }
        XCTAssertFalse(state.claimChallenge(challenge.id, now: noon).ok)
        switch template.goal {
        case .stat(let key, let target): state.progress.stats[key] += target
        case .score(_, let target): state.progress.challenges.daily[0].best = target
        }
        let coins = state.wallet.coins
        XCTAssertTrue(state.claimChallenge(challenge.id, now: noon).ok)
        XCTAssertEqual(state.wallet.coins, coins + template.coins)
        XCTAssertFalse(state.claimChallenge(challenge.id, now: noon).ok)
        XCTAssertEqual(state.wallet.coins, coins + template.coins)
    }
}

final class DialogueTests: XCTestCase {
    func testDoesNotRepeatItselfRightAway() {
        var state = hatchedState()
        // Only lines that fit the bond and the hour can be picked.
        let hour = berlin.component(.hour, from: noon)
        let bank = (Dialogue.lines[.pet] ?? []).filter {
            Double($0.minBond) <= state.slime.bond && ($0.hours?.contains(hour) ?? true)
        }.count
        XCTAssertGreaterThanOrEqual(bank, 2)
        var said: [String] = []
        for i in 0..<min(5, bank) {
            let line = Dialogue.pick(.pet, state: state, at: noon.addingTimeInterval(Double(i) * 60), calendar: berlin)
            state.memory.used(line: line.id, at: noon)
            said.append(line.id)
        }
        XCTAssertEqual(Set(said).count, said.count)
    }

    func testPlaceholdersAreFilled() {
        let state = hatchedState()
        let text = Dialogue.fill("{name} likes {food}", context: LineContext(food: "apples"), state: state)
        XCTAssertEqual(text, "Testi likes apples")
    }

    func testEveryLineIsFilledCompletely() {
        let state = hatchedState()
        let ctx = LineContext(food: "a", favorite: "b", friend: "c", score: 1, game: "d", level: 2, days: 3, item: "e")
        for (_, lines) in Dialogue.lines {
            for line in lines {
                XCTAssertFalse(Dialogue.fill(line.text, context: ctx, state: state).contains("{"), line.id)
            }
        }
    }
}

final class PersistenceTests: XCTestCase {
    func testRoundTrip() throws {
        var state = hatchedState()
        _ = state.finish(GameResult(game: .treasureDig, score: 60, session: UUID(), finishedAt: noon))
        let data = try GameStorage.encoder.encode(state)
        let back = try GameStorage.decoder.decode(GameState.self, from: data)
        XCTAssertEqual(back.slime.name, "Testi")
        XCTAssertEqual(back.wallet, state.wallet)
        XCTAssertEqual(back.progress, state.progress)
        XCTAssertEqual(back.slime.needs, state.slime.needs)
    }

    func testDamagedSaveStillLoads() throws {
        let json = """
        {"version": 2, "hatched": true,
         "wallet": {"coins": "lots"},
         "slime": {"name": "Blub", "needs": {"hunger": 5000, "fun": -3, "energy": 50, "clean": 50, "love": 50}},
         "settings": 17, "somethingFromTheFuture": [1, 2, 3]}
        """
        var state = try GameStorage.decoder.decode(GameState.self, from: Data(json.utf8))
        state.sanitize(now: noon)
        XCTAssertTrue(state.hatched)
        XCTAssertEqual(state.slime.name, "Blub")
        XCTAssertEqual(state.wallet.coins, Wallet().coins)
        XCTAssertEqual(state.slime.needs.hunger, 100)
        XCTAssertEqual(state.slime.needs.fun, 0)
    }

    func testMigratesVersionOne() throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "slimy.tests.\(UUID().uuidString)"))
        let old = """
        {"name": "Gloop", "skin": "ocean", "xp": 130, "coins": 77, "isAsleep": false,
         "needs": {"hunger": 10, "fun": 20, "energy": 30, "clean": 40, "love": 50},
         "counters": {"feeds": 4, "plays": 2, "bestScore": 33}}
        """
        defaults.set(Data(old.utf8), forKey: "slimy.state.v1")
        defaults.set(true, forKey: "slimy.hatched")
        let state = try XCTUnwrap(GameStorage.migrateV1(defaults: defaults, now: noon))
        XCTAssertTrue(state.hatched)
        XCTAssertEqual(state.slime.name, "Gloop")
        XCTAssertEqual(state.slime.skin, .ocean)
        XCTAssertEqual(state.slime.xp, 130)
        XCTAssertEqual(state.wallet.coins, 77)
        XCTAssertEqual(state.slime.needs.hunger, 10)
        XCTAssertEqual(state.progress.stats.feeds, 4)
        XCTAssertEqual(state.record(.snackCatcher).best, 33)
        XCTAssertTrue(state.player.tips.contains("onboarded"))
    }

    func testNoVersionOneDataMeansNoMigration() throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "slimy.tests.\(UUID().uuidString)"))
        XCTAssertNil(GameStorage.migrateV1(defaults: defaults, now: noon))
    }
}

final class IslandStateTests: XCTestCase {
    func testMeetupFallsBackToSoloWhenStale() {
        var game = GameState()
        game.hatch(name: "Testi", skin: .lime, seed: 1, now: noon)
        let friend = FriendSlime(id: "dev_a", name: "Mochi", owner: "Anna", skin: .bubblegum, stage: .baby,
                                 mood: .idle, love: 40, inLove: false, equipped: nil)
        let state = SlimeActivityAttributes.ContentState(game, at: noon, friends: [friend], moment: .meetup, momentFriend: "dev_a")
        XCTAssertEqual(state.anim, .meet)
        XCTAssertEqual(state.partner?.id, "dev_a")
        XCTAssertTrue(state.line.hasPrefix("Mochi"))

        let solo = state.solo
        XCTAssertNil(solo.moment)
        XCTAssertNil(solo.partner)          // not in love, so no partner once the moment ends
        XCTAssertNotEqual(solo.anim, .meet)
        XCTAssertEqual(solo.line, state.baseLine)
    }

    func testPhoneEventShowsBrieflyAndFallsBack() {
        var game = GameState()
        game.hatch(name: "Testi", skin: .lime, seed: 1, now: noon)
        _ = game.handle(.wifiConnected, now: noon)
        let fresh = SlimeActivityAttributes.ContentState(game, at: noon.addingTimeInterval(60))
        XCTAssertEqual(fresh.event, .wifiConnected)
        XCTAssertEqual(fresh.emote, .wifi)
        // Once the event's window is over, it's gone from the island.
        let later = SlimeActivityAttributes.ContentState(game, at: noon.addingTimeInterval(Slime.eventWindow + 1))
        XCTAssertNil(later.event)
        XCTAssertNotEqual(later.emote, .wifi)
        // And a stale activity drops it too.
        XCTAssertNil(fresh.solo.event)
        XCTAssertNotEqual(fresh.solo.emote, .wifi)
    }

    func testMeetFramesSlideAndCarryAccessoryOffsets() {
        for stage in Stage.allCases {
            let anchors = SlimeSprites.anchors(stage, .meet)
            XCTAssertEqual(SlimeSprites.frames(stage, .meet).count, 10)
            XCTAssertEqual(anchors.count, 10)
            XCTAssertEqual(anchors.first?.count, 5)
            XCTAssertGreaterThan(anchors.map { $0[4] }.max() ?? 0, 0, "\(stage) never slides")
        }
    }
}
