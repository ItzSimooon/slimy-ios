import XCTest
@testable import Slimy

/// Clock for the streak tests: Berlin, which has daylight saving.
private let berlin: Calendar = {
    var c = Calendar(identifier: .gregorian)
    c.timeZone = TimeZone(identifier: "Europe/Berlin")!
    return c
}()

private func date(_ year: Int, _ month: Int, _ day: Int, _ hour: Int = 12, _ minute: Int = 0,
                  in calendar: Calendar = berlin) -> Date {
    calendar.date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute))!
}

/// Hatched in May, far from any season, holiday or birthday.
private func hatchedState(at now: Date = date(2026, 5, 1)) -> GameState {
    var state = GameState()
    state.hatch(name: "Testi", skin: .lime, seed: 42, now: now)
    state.settings.sleep.auto = false
    return state
}

final class CoreStreakTests: XCTestCase {
    func testDayDistanceCountsDatesNotHours() {
        XCTAssertEqual(Streak.days(from: "2026-05-04", to: "2026-05-05"), 1)
        XCTAssertEqual(Streak.days(from: "2026-05-05", to: "2026-05-04"), -1)
        // Daylight saving starts and ends in Berlin.
        XCTAssertEqual(Streak.days(from: "2026-03-28", to: "2026-03-29"), 1)
        XCTAssertEqual(Streak.days(from: "2026-10-24", to: "2026-10-26"), 2)
        XCTAssertEqual(Streak.days(from: "2026-12-31", to: "2027-01-01"), 1)
        XCTAssertEqual(Streak.days(from: "2028-02-28", to: "2028-03-01"), 2)
        XCTAssertNil(Streak.days(from: "garbage", to: "2026-05-04"))
        XCTAssertNil(Streak.days(from: "2026-13-01", to: "2026-05-04"))
    }

    func testGiftOpensOncePerDay() throws {
        var state = hatchedState()
        let now = date(2026, 5, 4, 9)
        let info = state.streakInfo(at: now, calendar: berlin)
        XCTAssertTrue(info.canClaim)
        XCTAssertFalse(info.claimedToday)
        XCTAssertEqual(info.today, 1)
        XCTAssertEqual(info.current, 0)
        XCTAssertNil(info.nextGift)

        let coins = state.wallet.coins
        let first = try XCTUnwrap(state.claimDailyGift(now: now, calendar: berlin))
        XCTAssertEqual(first.gift.day, 1)
        XCTAssertEqual(first.gift.coins, Streak.coins[0])
        XCTAssertTrue(first.outcome.ok)
        XCTAssertGreaterThanOrEqual(state.wallet.coins, coins + Streak.coins[0])
        XCTAssertTrue(state.wallet.hasClaimed(Streak.receipt("2026-05-04")))
        XCTAssertNotNil(state.slime.say)

        // Later the same day: nothing more to open.
        let evening = date(2026, 5, 4, 23, 50)
        XCTAssertNil(state.claimDailyGift(now: evening, calendar: berlin))
        let after = state.streakInfo(at: evening, calendar: berlin)
        XCTAssertFalse(after.canClaim)
        XCTAssertTrue(after.claimedToday)
        XCTAssertEqual(after.current, 1)
        XCTAssertEqual(after.today, 1)
        XCTAssertTrue(after.isOpened(after.week[0]))
        XCTAssertEqual(after.nextGift, date(2026, 5, 5, 0))
    }

    func testConsecutiveDaysAcrossDaylightSaving() {
        var state = hatchedState(at: date(2026, 3, 20))
        // 23:30 on Saturday and 00:30 on Sunday, the night the clocks go forward.
        XCTAssertNotNil(state.claimDailyGift(now: date(2026, 3, 28, 23, 30), calendar: berlin))
        XCTAssertNotNil(state.claimDailyGift(now: date(2026, 3, 29, 0, 30), calendar: berlin))
        XCTAssertNotNil(state.claimDailyGift(now: date(2026, 3, 30, 21), calendar: berlin))
        XCTAssertEqual(state.streak.count, 3)
        XCTAssertEqual(state.streak.best, 3)
        XCTAssertEqual(state.streak.opened, 3)
    }

    func testMissedDayRestartsStreakButKeepsGifts() {
        var state = hatchedState()
        XCTAssertNotNil(state.claimDailyGift(now: date(2026, 5, 4), calendar: berlin))
        XCTAssertNotNil(state.claimDailyGift(now: date(2026, 5, 5), calendar: berlin))
        // The 6th is missed. The streak is over the day after it.
        XCTAssertEqual(state.streakInfo(at: date(2026, 5, 6), calendar: berlin).current, 2)
        let back = state.streakInfo(at: date(2026, 5, 7), calendar: berlin)
        XCTAssertEqual(back.current, 0)
        XCTAssertTrue(back.canClaim)
        // The calendar didn't go back: it's the third gift.
        XCTAssertEqual(back.today, 3)
        let third = state.claimDailyGift(now: date(2026, 5, 7), calendar: berlin)
        XCTAssertEqual(third?.gift.day, 3)
        XCTAssertEqual(state.streak.count, 1)
        XCTAssertEqual(state.streak.best, 2)
        XCTAssertEqual(state.streak.opened, 3)
    }

    func testSeventhGiftBringsTheNextSpecialItem() throws {
        var state = hatchedState()
        var last: DailyGift?
        for day in 4...10 {
            last = state.claimDailyGift(now: date(2026, 5, day), calendar: berlin)?.gift
        }
        let seventh = try XCTUnwrap(last)
        XCTAssertEqual(seventh.day, 7)
        XCTAssertTrue(seventh.isSpecial)
        XCTAssertEqual(seventh.item, Streak.specialItems[0])
        XCTAssertTrue(state.owns(Streak.specialItems[0]))
        XCTAssertEqual(state.streak.lastItem, Streak.specialItems[0])
        for id in ["streak3", "streak7", "gifts7"] {
            XCTAssertNotNil(state.progress.achievements[id], id)
        }
        // The item is unlocked in the shop by that achievement, so it all agrees.
        let chest = try XCTUnwrap(Catalog.item(Streak.specialItems[0]))
        XCTAssertTrue(state.isAvailable(chest.unlock, now: date(2026, 5, 10), calendar: berlin))

        // Still the 7th: the tile shows what it held.
        let sameDay = state.streakInfo(at: date(2026, 5, 10, 20), calendar: berlin)
        XCTAssertEqual(sameDay.today, 7)
        XCTAssertEqual(sameDay.week[6].item, Streak.specialItems[0])

        // A new week begins, with the next item waiting at its end.
        let nextWeek = state.streakInfo(at: date(2026, 5, 11), calendar: berlin)
        XCTAssertEqual(nextWeek.today, 1)
        XCTAssertEqual(nextWeek.current, 7)
        XCTAssertEqual(nextWeek.week[6].item, Streak.specialItems[1])
    }

    func testBonusCoinsOnceEveryItemIsOwned() {
        var state = hatchedState()
        state.inventory.owned.formUnion(Streak.specialItems)
        let week = state.streakInfo(at: date(2026, 5, 4), calendar: berlin).week
        XCTAssertNil(week[6].item)
        XCTAssertEqual(week[6].coins, Streak.coins[6] + Streak.bonusWhenAllOwned)
        XCTAssertEqual(week.map(\.day), Array(1...7))
    }

    func testClockOneDayBackWaitsForTheDayAfter() {
        var state = hatchedState()
        XCTAssertNotNil(state.claimDailyGift(now: date(2026, 5, 5), calendar: berlin))
        let earlier = state.streakInfo(at: date(2026, 5, 4, 15), calendar: berlin)
        XCTAssertFalse(earlier.canClaim)
        XCTAssertFalse(earlier.claimedToday)
        XCTAssertEqual(earlier.current, 1)
        XCTAssertEqual(earlier.nextGift, date(2026, 5, 6, 0))
        XCTAssertNil(state.claimDailyGift(now: date(2026, 5, 4, 15), calendar: berlin))
    }

    func testClockSetFarAheadThenBackStartsAgain() {
        var state = hatchedState()
        XCTAssertNotNil(state.claimDailyGift(now: date(2026, 8, 1), calendar: berlin))
        let back = date(2026, 5, 10)
        XCTAssertTrue(state.streakInfo(at: back, calendar: berlin).canClaim)
        XCTAssertNotNil(state.claimDailyGift(now: back, calendar: berlin))
        XCTAssertEqual(state.streak.count, 1)
        XCTAssertEqual(state.streak.lastDay, "2026-05-10")
        // The same date never pays twice, whatever the clock does.
        state.streak.lastDay = "2026-05-08"
        XCTAssertFalse(state.streakInfo(at: back, calendar: berlin).canClaim)
        XCTAssertNil(state.claimDailyGift(now: back, calendar: berlin))
    }

    func testFlyingEastCountsAsTheNextDay() {
        var tokyo = Calendar(identifier: .gregorian)
        tokyo.timeZone = TimeZone(identifier: "Asia/Tokyo")!
        var state = hatchedState()
        let lateInBerlin = date(2026, 5, 4, 23)
        XCTAssertNotNil(state.claimDailyGift(now: lateInBerlin, calendar: berlin))
        // An hour later it's already the 5th in Tokyo.
        let landed = lateInBerlin.addingTimeInterval(3600)
        XCTAssertTrue(state.streakInfo(at: landed, calendar: tokyo).canClaim)
        XCTAssertNotNil(state.claimDailyGift(now: landed, calendar: tokyo))
        XCTAssertEqual(state.streak.count, 2)
    }

    func testNotHatchedHasNothingToOpen() {
        var state = GameState()
        let now = date(2026, 5, 4)
        XCTAssertFalse(state.streakInfo(at: now, calendar: berlin).canClaim)
        XCTAssertNil(state.claimDailyGift(now: now, calendar: berlin))
        XCTAssertEqual(state.streak, StreakState())
    }

    func testStreakDecodesFromOlderAndDamagedSaves() throws {
        let old = #"{"version": 2, "hatched": true, "slime": {"name": "Blub"}}"#
        let a = try GameStorage.decoder.decode(GameState.self, from: Data(old.utf8))
        XCTAssertEqual(a.streak, StreakState())
        XCTAssertTrue(a.player.seasonsSeen.isEmpty)

        let damaged = #"{"version": 3, "hatched": true, "streak": "lots"}"#
        let b = try GameStorage.decoder.decode(GameState.self, from: Data(damaged.utf8))
        XCTAssertEqual(b.streak, StreakState())

        let odd = #"{"version": 3, "hatched": true, "streak": {"count": -4, "best": 3, "opened": 9, "lastDay": 17}}"#
        var c = try GameStorage.decoder.decode(GameState.self, from: Data(odd.utf8))
        c.sanitize(now: date(2026, 5, 4))
        XCTAssertEqual(c.streak.count, 0)
        XCTAssertEqual(c.streak.best, 3)
        XCTAssertEqual(c.streak.opened, 9)
        XCTAssertNil(c.streak.lastDay)
    }

    func testStreakSurvivesASaveRoundTrip() throws {
        var state = hatchedState()
        for day in 4...6 { _ = state.claimDailyGift(now: date(2026, 5, day), calendar: berlin) }
        let data = try GameStorage.encoder.encode(state)
        let back = try GameStorage.decoder.decode(GameState.self, from: data)
        XCTAssertEqual(back.streak, state.streak)
        XCTAssertEqual(back.streakInfo(at: date(2026, 5, 7), calendar: berlin), state.streakInfo(at: date(2026, 5, 7), calendar: berlin))
    }
}

final class CoreAchievementTests: XCTestCase {
    func testAchievementIDsAreUniqueAndPlenty() {
        let ids = Achievements.all.map(\.id)
        XCTAssertEqual(Set(ids).count, ids.count)
        XCTAssertGreaterThanOrEqual(ids.count, 44)
        for a in Achievements.all {
            XCTAssertFalse(a.title.isEmpty, a.id)
            XCTAssertTrue(a.detail.hasSuffix("."), a.id)
            XCTAssertGreaterThan(a.coins, 0, a.id)
        }
    }

    func testFreshSlimeHasNoneOfTheNewOnes() {
        let state = hatchedState()
        for id in ["streak3", "gifts7", "birthday", "stackStar", "dashStar", "dailyRegular", "dressedUp", "oldFriends"] {
            XCTAssertFalse(Achievements.achievement(id)?.check(state) ?? true, id)
        }
    }

    func testItemsUnlockedByAchievementsPointToRealOnes() {
        for item in Catalog.items {
            if case .achievement(let id) = item.unlock {
                XCTAssertNotNil(Achievements.achievement(id), item.id)
            }
        }
        for id in Streak.specialItems + ["furn.cake", "furn.jackolantern", "furn.stars"] {
            XCTAssertNotNil(Catalog.item(id), id)
        }
    }

    func testEveryCatalogItemHasArt() {
        for item in Catalog.items where item.kind != .effect {
            XCTAssertNotNil(SlimeSprites.items[item.sprite], "\(item.id) shows \(item.sprite)")
        }
        for item in Catalog.items(.furniture) {
            XCTAssertNotNil(WorldArt.sprite(item.id), item.id)
        }
    }

    func testNewGamesHaveAchievements() {
        var state = hatchedState()
        XCTAssertFalse(state.isGood(.slimeStack))
        XCTAssertNil(Achievements.all.first { $0.id == "stackStar" && $0.check(state) })
        state.progress.records[.slimeStack] = GameRecord(best: GameID.slimeStack.goodScore, plays: 1, lastPlayed: nil, tutorialSeen: true)
        XCTAssertTrue(state.isGood(.slimeStack))
        XCTAssertTrue(Achievements.achievement("stackStar")?.check(state) ?? false)
        XCTAssertFalse(Achievements.achievement("dashStar")?.check(state) ?? true)
    }

    func testDressedUpNeedsAllThree() {
        var state = hatchedState()
        state.slime.equipped = Accessories(hat: "hat.party", glasses: "glasses.round", scarf: nil, effect: nil)
        XCTAssertFalse(Achievements.achievement("dressedUp")?.check(state) ?? true)
        state.slime.equipped.scarf = "scarf.red"
        XCTAssertTrue(Achievements.achievement("dressedUp")?.check(state) ?? false)
    }

    func testUnlockHints() {
        XCTAssertEqual(Unlock.season(month: 10).hint, "October only")
        XCTAssertEqual(Unlock.season(month: 12).hint, "December only")
        XCTAssertEqual(Unlock.level(4).hint, "From level 4")
        XCTAssertEqual(Unlock.achievement("gifts7").hint, "Achievement: Gift week")
    }

    func testTriedThemAllNeedsAFinishedRoundOfEach() {
        var state = hatchedState()
        // Opening a game's tutorial creates its record, but isn't a round.
        for game in GameID.classics { state.markTutorialSeen(game) }
        XCTAssertFalse(Achievements.achievement("allGames")?.check(state) ?? true)
        for game in GameID.classics { state.progress.records[game, default: GameRecord()].plays = 1 }
        XCTAssertTrue(Achievements.achievement("allGames")?.check(state) ?? false)
    }

    func testDailyChallengesOnlyForGamesThatAreOpen() {
        let early = Challenges.dailyOptions(forLevel: 1)
        XCTAssertGreaterThanOrEqual(early.count, 3)
        for template in early {
            if case .score(let game, _) = template.goal {
                XCTAssertEqual(game.unlockLevel, 1, template.id)
            }
        }
        XCTAssertEqual(Challenges.dailyOptions(forLevel: 20).count, Challenges.dailyPool.count)

        var state = hatchedState()
        state.refreshChallenges(now: date(2026, 5, 2), calendar: berlin)
        XCTAssertEqual(state.progress.challenges.daily.count, 3)
        for challenge in state.progress.challenges.daily {
            if case .score(let game, _)? = Challenges.template(challenge.id)?.goal {
                XCTAssertLessThanOrEqual(game.unlockLevel, state.slime.level, challenge.id)
            }
        }
    }

    func testNewGamesHaveDailyChallenges() {
        let games = Challenges.dailyPool.compactMap { template -> GameID? in
            if case .score(let game, _) = template.goal { return game }
            return nil
        }
        XCTAssertTrue(games.contains(.slimeStack))
        XCTAssertTrue(games.contains(.jellyDash))
    }

    func testHatchColoursAreOpenFromTheStart() {
        let state = hatchedState()
        XCTAssertEqual(state.slime.level, 1)
        for skin in Skin.hatchChoices {
            XCTAssertTrue(state.slime.isUnlocked(skin), skin.rawValue)
        }
        XCTAssertFalse(state.slime.isUnlocked(.grape))
    }
}
