import XCTest
@testable import Slimy

private let berlin: Calendar = {
    var c = Calendar(identifier: .gregorian)
    c.timeZone = TimeZone(identifier: "Europe/Berlin")!
    return c
}()

private func date(_ year: Int, _ month: Int, _ day: Int, _ hour: Int = 12, _ minute: Int = 0) -> Date {
    berlin.date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute))!
}

private func hatchedState(at now: Date) -> GameState {
    var state = GameState()
    state.hatch(name: "Testi", skin: .lime, seed: 42, now: now)
    state.settings.sleep.auto = false
    return state
}

final class CoreSeasonTests: XCTestCase {
    func testSeasonsFollowTheCalendar() {
        XCTAssertEqual(Season.at(date(2026, 10, 3), calendar: berlin), .halloween)
        XCTAssertEqual(Season.at(date(2026, 10, 31, 23), calendar: berlin), .halloween)
        XCTAssertEqual(Season.at(date(2026, 11, 1), calendar: berlin), .autumn)
        XCTAssertEqual(Season.at(date(2026, 12, 10), calendar: berlin), .christmas)
        XCTAssertEqual(Season.at(date(2026, 12, 31, 17), calendar: berlin), .christmas)
        XCTAssertEqual(Season.at(date(2026, 12, 31, 19), calendar: berlin), .newYear)
        XCTAssertEqual(Season.at(date(2027, 1, 1, 15), calendar: berlin), .newYear)
        XCTAssertEqual(Season.at(date(2027, 1, 2), calendar: berlin), .winter)
        XCTAssertEqual(Season.at(date(2027, 4, 2), calendar: berlin), .spring)
        XCTAssertEqual(Season.at(date(2027, 7, 2), calendar: berlin), .summer)
    }

    func testSeasonsUseGregorianMonthsWhateverTheCalendar() {
        var islamic = Calendar(identifier: .islamicUmmAlQura)
        islamic.timeZone = berlin.timeZone
        XCTAssertEqual(Season.at(date(2026, 10, 3), calendar: islamic), .halloween)
        XCTAssertEqual(Holiday.at(date(2026, 12, 25), calendar: islamic), .holidays)
        var state = hatchedState(at: date(2026, 10, 2))
        state.slime.xp = 0
        XCTAssertTrue(state.isAvailable(.season(month: 10), now: date(2026, 10, 3), calendar: islamic))
        XCTAssertFalse(state.isAvailable(.season(month: 12), now: date(2026, 10, 3), calendar: islamic))
        // Day keys (daily challenges, today's game) are the same date for everyone.
        var japanese = Calendar(identifier: .japanese)
        japanese.timeZone = berlin.timeZone
        XCTAssertEqual(Challenges.dayKey(date(2026, 10, 3), calendar: islamic), "2026-10-03")
        XCTAssertEqual(Challenges.dayKey(date(2026, 10, 3), calendar: japanese), "2026-10-03")
    }

    func testHolidays() {
        XCTAssertEqual(Holiday.at(date(2026, 10, 31), calendar: berlin), .halloween)
        XCTAssertEqual(Holiday.at(date(2026, 12, 24), calendar: berlin), .holidays)
        XCTAssertEqual(Holiday.at(date(2026, 12, 26), calendar: berlin), .holidays)
        XCTAssertEqual(Holiday.at(date(2027, 1, 1), calendar: berlin), .newYear)
        XCTAssertNil(Holiday.at(date(2026, 10, 30), calendar: berlin))
        XCTAssertNil(Holiday.at(date(2026, 12, 27), calendar: berlin))
    }

    func testSeasonalShopItemsOnlyInTheirMonth() throws {
        let state = hatchedState(at: date(2026, 11, 1))
        let stars = try XCTUnwrap(Catalog.item("furn.stars"))
        XCTAssertFalse(state.isAvailable(stars.unlock, now: date(2026, 11, 20), calendar: berlin))
        XCTAssertTrue(state.isAvailable(stars.unlock, now: date(2026, 12, 2), calendar: berlin))
        let lantern = try XCTUnwrap(Catalog.item("furn.jackolantern"))
        XCTAssertTrue(state.isAvailable(lantern.unlock, now: date(2026, 10, 2), calendar: berlin))
    }

    func testVisitingInASeasonCounts() {
        var state = hatchedState(at: date(2026, 9, 30))
        state.tick(now: date(2026, 10, 1, 9), calendar: berlin)
        XCTAssertTrue(state.player.seasonsSeen.contains(Season.halloween.rawValue))
        XCTAssertNotNil(state.progress.achievements["spooky"])
        XCTAssertNil(state.progress.achievements["cozy"])
    }
}

final class CoreBirthdayTests: XCTestCase {
    func testBirthdayYears() {
        let born = date(2025, 10, 26, 10)
        XCTAssertNil(Birthday.years(born: born, at: date(2025, 10, 26, 20), calendar: berlin))
        XCTAssertNil(Birthday.years(born: born, at: date(2026, 10, 25), calendar: berlin))
        XCTAssertEqual(Birthday.years(born: born, at: date(2026, 10, 26, 0, 5), calendar: berlin), 1)
        XCTAssertEqual(Birthday.years(born: born, at: date(2027, 10, 26), calendar: berlin), 2)
        XCTAssertNil(Birthday.years(born: born, at: date(2027, 10, 27), calendar: berlin))
    }

    func testLeapDayBirthdaysFallOnThe28th() {
        let born = date(2024, 2, 29)
        XCTAssertEqual(Birthday.years(born: born, at: date(2025, 2, 28), calendar: berlin), 1)
        XCTAssertNil(Birthday.years(born: born, at: date(2025, 3, 1), calendar: berlin))
        XCTAssertEqual(Birthday.years(born: born, at: date(2028, 2, 29), calendar: berlin), 4)
        XCTAssertNil(Birthday.years(born: born, at: date(2028, 2, 28), calendar: berlin))
    }

    func testBirthdayIsCelebratedOncePerYear() {
        var state = hatchedState(at: date(2025, 5, 4, 9))
        let coins = state.wallet.coins
        let events = state.tick(now: date(2026, 5, 4, 10), calendar: berlin)
        XCTAssertTrue(events.contains(.birthday(1)))
        XCTAssertTrue(events.contains(.achievement("birthday")))
        XCTAssertTrue(state.isBirthday(at: date(2026, 5, 4, 10), calendar: berlin))
        XCTAssertTrue(state.owns("furn.cake"))
        XCTAssertEqual(state.memory.last(.birthday)?.detail, "1")
        XCTAssertEqual(state.memory.last(.birthday)?.keepsake, true)
        XCTAssertGreaterThanOrEqual(state.wallet.coins, coins + Birthday.coins(years: 1))
        XCTAssertEqual(state.slime.reaction, .celebrate)

        let later = state.tick(now: date(2026, 5, 4, 18), calendar: berlin)
        XCTAssertFalse(later.contains(.birthday(1)))
        XCTAssertEqual(state.memory.entries.filter { $0.kind == .birthday }.count, 1)

        // The receipt outlives ledger pruning, so the clock can't replay it.
        state.wallet.prune(now: date(2026, 9, 1))
        XCTAssertTrue(state.wallet.hasClaimed(Birthday.receipt(year: 2026)))

        let next = state.tick(now: date(2027, 5, 4, 10), calendar: berlin)
        XCTAssertTrue(next.contains(.birthday(2)))
    }

    func testBirthdayAtMidnightDoesNotWakeIt() {
        var state = hatchedState(at: date(2025, 5, 4, 9))
        state.slime.isAsleep = true
        state.slime.lastUpdate = date(2026, 5, 3, 23)
        _ = state.tick(now: date(2026, 5, 4, 0, 10), calendar: berlin)
        XCTAssertTrue(state.slime.isAsleep)
        XCTAssertNotNil(state.memory.last(.birthday))
    }

    func testHolidayGiftOncePerYear() {
        var state = hatchedState(at: date(2026, 12, 20))
        let coins = state.wallet.coins
        let events = state.tick(now: date(2026, 12, 24, 10), calendar: berlin)
        XCTAssertTrue(events.contains(.holiday(.holidays)))
        XCTAssertGreaterThanOrEqual(state.wallet.coins, coins + Holiday.holidays.coins)
        XCTAssertEqual(state.memory.last(.holiday)?.detail, Holiday.holidays.rawValue)
        XCTAssertFalse(state.tick(now: date(2026, 12, 25, 10), calendar: berlin).contains(.holiday(.holidays)))
        XCTAssertTrue(state.tick(now: date(2027, 1, 1, 10), calendar: berlin).contains(.holiday(.newYear)))
        XCTAssertTrue(state.tick(now: date(2027, 12, 24, 10), calendar: berlin).contains(.holiday(.holidays)))
    }

    func testMemoryBookDescribesEveryKind() {
        let kinds: [MemoryEntry.Kind] = [.hatched, .fed, .favoriteFound, .played, .highScore, .bath, .levelUp, .stageUp,
                                         .achievement, .gift, .giftReceived, .visit, .friendMade, .event,
                                         .reunion, .birthday, .streak, .holiday, .crate, .partnerCare,
                                         .careStarted, .careEnded]
        for kind in kinds {
            let entry = MemoryEntry(kind: kind, at: date(2026, 5, 4), detail: "1")
            XCTAssertFalse(entry.summary.isEmpty, kind.rawValue)
            XCTAssertNotNil(SlimeSprites.items[entry.icon], "\(kind.rawValue): \(entry.icon)")
        }
        XCTAssertEqual(MemoryEntry(kind: .birthday, at: .now, detail: "1").summary, "First birthday")
        XCTAssertEqual(MemoryEntry(kind: .streak, at: .now, detail: "30").summary, "30 days in a row")
    }

    func testDamagedMemoryEntryDoesNotWipeTheBook() throws {
        let json = """
        {"entries": [
          {"id": "7D3D2A1E-8B9A-4C7E-9C51-2E9B4F0A1B2C", "kind": "hatched", "at": 1767225600, "detail": "Blub", "keepsake": true},
          {"id": "1A2B3C4D-1111-2222-3333-444455556666", "kind": "somethingNew", "at": 1767225600, "detail": "", "keepsake": true}
        ]}
        """
        let memory = try GameStorage.decoder.decode(Memory.self, from: Data(json.utf8))
        XCTAssertEqual(memory.entries.count, 1)
        XCTAssertEqual(memory.entries.first?.kind, .hatched)
    }
}

final class CoreDialogueTests: XCTestCase {
    private let newCategories: [LineCategory] = [
        .dailyGift, .giftWeek, .streak, .streakFresh, .spooky, .cozy, .newYear, .birthday,
        .feelJoy, .feelSad, .feelAngry, .feelScared, .feelLove, .feelCurious, .feelBored, .feelExcited, .feelSleepy,
        .afterFood, .afterGame, .afterFriend, .afterCrate, .partnerCared, .careStarted, .careEnded,
        .crateOpened, .crateDuplicate, .crateLegendary, .bathGrumpy,
        .spring, .summer, .autumn, .winter, .roomTalk, .gardenTalk, .beachTalk, .spaceTalk, .crystalTalk,
        .rainTalk, .snowTalk, .cloudTalk,
    ]

    func testNewCategoriesHaveLowercaseLines() {
        for category in newCategories {
            let lines = Dialogue.lines[category] ?? []
            XCTAssertGreaterThanOrEqual(lines.count, 3, category.rawValue)
            for line in lines {
                XCTAssertEqual(line.text, line.text.lowercased(), line.id)
                XCTAssertLessThanOrEqual(line.text.count, 60, line.id)
            }
        }
    }

    func testLineIDsAreUnique() {
        let ids = Dialogue.lines.values.flatMap { $0.map(\.id) }
        XCTAssertEqual(Set(ids).count, ids.count)
        XCTAssertGreaterThanOrEqual(ids.count, 400)
    }

    func testEveryCategoryHasLines() {
        for category in LineCategory.allCases {
            XCTAssertFalse((Dialogue.lines[category] ?? []).isEmpty, category.rawValue)
        }
    }

    func testFeelingsHabitatsAndSeasonsHaveTheirOwnTalk() {
        for emotion in Emotion.allCases {
            XCTAssertGreaterThanOrEqual(Dialogue.lines[Dialogue.category(for: emotion)]?.count ?? 0, 10, emotion.rawValue)
        }
        for habitat in Habitat.allCases {
            XCTAssertGreaterThanOrEqual(Dialogue.lines[Dialogue.category(for: habitat)]?.count ?? 0, 5, habitat.rawValue)
        }
        for season in Season.allCases {
            XCTAssertGreaterThanOrEqual(Dialogue.lines[season.talk]?.count ?? 0, 5, season.rawValue)
        }
    }

    func testLoveBetweenSlimesIsGone() {
        XCTAssertNil(LineCategory(rawValue: "inLove"))
        XCTAssertNil(MemoryEntry.Kind(rawValue: "inLove"))
        XCTAssertNil(Achievements.achievement("inLove"))
    }

    func testStreakAndYearPlaceholders() {
        let state = hatchedState(at: date(2026, 5, 4))
        XCTAssertEqual(Dialogue.fill("day {streak}", context: LineContext(streak: 4), state: state), "day 4")
        XCTAssertEqual(Dialogue.fill("{years} old", context: LineContext(years: 1), state: state), "1 year old")
        XCTAssertEqual(Dialogue.fill("{years} old", context: LineContext(years: 3), state: state), "3 years old")
    }

    func testTimeOfDay() {
        XCTAssertEqual(Dialogue.timeOfDay(hour: 7), .morning)
        XCTAssertEqual(Dialogue.timeOfDay(hour: 13), .day)
        XCTAssertEqual(Dialogue.timeOfDay(hour: 19), .evening)
        XCTAssertEqual(Dialogue.timeOfDay(hour: 2), .night)
    }

    func testChatterMixesInTheSeason() {
        var state = hatchedState(at: date(2026, 10, 10, 11))
        for i in 0..<60 {
            _ = state.chatter(now: date(2026, 10, 10, 11).addingTimeInterval(Double(i) * 30), calendar: berlin)
        }
        let said = state.memory.lineUsedAt.keys
        XCTAssertTrue(said.contains { $0.hasPrefix("spooky.") })
        XCTAssertTrue(said.contains { !$0.hasPrefix("spooky.") })
    }
}
