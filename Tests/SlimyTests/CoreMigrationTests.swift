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

/// A save as Slimy 1.0 wrote it: with love between slimes, the seventh-day
/// item of the old gift calendar, and none of the 1.1 fields.
private let saveFrom10 = """
{"version": 3, "hatched": true,
 "slime": {"name": "Gloop", "skin": "ocean", "xp": 500, "bond": 62, "isAsleep": false,
           "needs": {"hunger": 40, "fun": 60, "energy": 70, "clean": 80, "love": 90},
           "personality": {"playful": 0.7, "cuddly": 0.2, "foodie": 0.4, "sleepy": 0.3, "curious": 0.9},
           "equipped": {"hat": "hat.party"}, "favorite": "honey", "favoriteGame": "bubblePop",
           "lastUpdate": 1777888800, "birthday": 1767225600, "reaction": "love", "reactionAt": 1777888800},
 "player": {"name": "Sam", "xp": 300, "createdAt": 1767225600, "lastSeen": 1777888800,
            "tips": ["onboarded"], "daysActive": ["2026-05-03"], "seasonsSeen": ["spring"]},
 "wallet": {"coins": 240, "earnedTotal": 900,
            "ledger": {"level:2": 1777888800, "achievement:inLove": 1777888800, "buy:hat.party": 1777888800}},
 "inventory": {"owned": ["hat.party", "furn.chest"], "foodsTasted": ["apple", "honey"], "favoriteFound": true,
               "habitat": "room", "habitats": ["room"], "furniture": ["floorRight", "furn.chest"]},
 "progress": {"stats": {"feeds": 12, "plays": 4, "baths": 2, "pets": 30, "sleeps": 1, "giftsSent": 0,
                        "giftsReceived": 1, "visits": 0, "hugs": 3, "friends": 2, "eventsSeen": 5,
                        "purchases": 1, "goodGames": 1},
              "achievements": {"firstMeal": 1777888800, "inLove": 1777888800}},
 "memory": {"entries": [
     {"id": "7D3D2A1E-8B9A-4C7E-9C51-2E9B4F0A1B2C", "kind": "hatched", "at": 1767225600, "detail": "Gloop", "keepsake": true},
     {"id": "1A2B3C4D-1111-2222-3333-444455556666", "kind": "inLove", "at": 1777888800, "detail": "Mochi", "keepsake": true},
     {"id": "2B3C4D5E-1111-2222-3333-444455556666", "kind": "friendMade", "at": 1777888800, "detail": "Mochi", "keepsake": true}
   ], "recentLines": ["idle.0", "inLove.1"], "lineUsedAt": {"idle.0": 1777888800}},
 "settings": {"sound": false, "music": false, "haptics": true, "liveEvents": true,
              "notifications": {"needs": true, "daily": false, "friends": true, "gifts": true, "world": false,
                                "quietFrom": 22, "quietUntil": 8, "asked": true},
              "sleep": {"bedtime": 23, "wake": 7, "auto": false}},
 "streak": {"lastDay": "2026-05-03", "count": 6, "best": 9, "opened": 13, "lastItem": "furn.chest"}}
"""

final class CoreMigrationTests: XCTestCase {
    func testASaveFrom10LoadsWithEverythingNewAtItsDefault() throws {
        var state = try GameStorage.decoder.decode(GameState.self, from: Data(saveFrom10.utf8))
        let now = date(2026, 5, 4, 15)
        state.sanitize(now: now)

        // Everything that was there is still there.
        XCTAssertTrue(state.hatched)
        XCTAssertEqual(state.slime.name, "Gloop")
        XCTAssertEqual(state.slime.skin, .ocean)
        XCTAssertEqual(state.slime.xp, 500)
        XCTAssertEqual(state.slime.bond, 62)
        XCTAssertEqual(state.slime.personality.curious, 0.9)
        XCTAssertEqual(state.slime.favorite, .honey)
        XCTAssertEqual(state.slime.equipped.hat, "hat.party")
        XCTAssertEqual(state.player.name, "Sam")
        XCTAssertEqual(state.wallet.coins, 240)
        XCTAssertEqual(state.wallet.earnedTotal, 900)
        XCTAssertTrue(state.owns("furn.chest"))
        XCTAssertEqual(state.inventory.furniture[.floorRight], "furn.chest")
        XCTAssertEqual(state.progress.stats.feeds, 12)
        XCTAssertEqual(state.progress.stats.goodGames, 1)
        XCTAssertFalse(state.settings.sound)
        XCTAssertEqual(state.settings.sleep.bedtime, 23)
        XCTAssertFalse(state.settings.notifications.daily)
        XCTAssertEqual(state.streak.count, 6)
        XCTAssertEqual(state.streak.best, 9)
        XCTAssertEqual(state.streak.opened, 13)

        // What's new in 1.1 starts at its default.
        XCTAssertEqual(state.slime.emotions, Emotions())
        XCTAssertTrue(state.slime.colours.isEmpty)
        XCTAssertTrue(state.crates.isEmpty)
        XCTAssertTrue(state.inventory.pantry.isEmpty)
        XCTAssertNil(state.care)
        XCTAssertTrue(state.careBook.outbox.isEmpty)
        XCTAssertFalse(state.careBook.me.isEmpty)
        XCTAssertTrue(state.settings.chatty)
        XCTAssertEqual(state.progress.stats.cratesOpened, 0)
        XCTAssertEqual(state.progress.stats.partnerCare, 0)

        // Love between slimes is gone; the rest of the memory book stays.
        XCTAssertEqual(state.memory.entries.map(\.kind), [.hatched, .friendMade])
        XCTAssertNil(Achievements.achievement("inLove"))

        // The seventh gift of the old calendar is now a mystery crate.
        let info = state.streakInfo(at: now, calendar: berlin)
        XCTAssertTrue(info.canClaim)
        XCTAssertEqual(info.today, 7)
        XCTAssertEqual(info.todayGift.crate, .mystery)
        let claimed = try XCTUnwrap(state.claimDailyGift(now: now, calendar: berlin))
        XCTAssertEqual(claimed.gift.crate, .mystery)
        XCTAssertEqual(state.crateCount(.mystery), 1)
        XCTAssertEqual(state.streak.count, 7)

        // It talks, feels and opens crates like any 1.1 slime.
        let line = state.chatter(now: now.addingTimeInterval(60), calendar: berlin)
        XCTAssertFalse(line.isEmpty)
        XCTAssertFalse(state.memory.recentLines.suffix(1).contains { $0.hasPrefix("inLove") })
        XCTAssertNotNil(state.openCrate(.mystery, now: now.addingTimeInterval(120), seed: 1))
        XCTAssertEqual(state.crateCount(.mystery), 0)
    }

    func testTheMigratedSaveExportsAsTheCurrentVersion() throws {
        let old = try GameStorage.decoder.decode(GameState.self, from: Data(saveFrom10.utf8))
        let file = try SaveFile.encode(old, at: date(2026, 5, 4, 15), appVersion: "1.0.0")
        let state = try SaveFile.decode(file, now: date(2026, 5, 4, 15))
        XCTAssertEqual(state.version, GameState.currentVersion)
        XCTAssertEqual(GameState.currentVersion, 4)
        XCTAssertEqual(state.slime.name, "Gloop")

        // And a 1.1 save reads back whole.
        var current = state
        current.addCrate(.golden, 2)
        current.inventory.pantry[.cookie] = 3
        current.slime.colours.insert(.axolotl)
        current.slime.emotions[.curiosity] = 0.6
        current.settings.chatty = false
        let data = try GameStorage.encoder.encode(current)
        let back = try GameStorage.decoder.decode(GameState.self, from: data)
        XCTAssertEqual(back.crateCount(.golden), 2)
        XCTAssertEqual(back.servings(of: .cookie), 3)
        XCTAssertTrue(back.slime.isUnlocked(.axolotl))
        XCTAssertEqual(back.slime.emotions, current.slime.emotions)
        XCTAssertFalse(back.settings.chatty)
    }

    func testDamagedNewFieldsFallBack() throws {
        let json = """
        {"version": 4, "hatched": true, "slime": {"name": "Blub", "emotions": "happy", "colours": ["axolotl", 7]},
         "crates": "many", "care": {"partnerID": 12}, "careBook": [1, 2], "settings": {"chatty": "yes"},
         "inventory": {"pantry": {"pizza": 3}}}
        """
        var state = try GameStorage.decoder.decode(GameState.self, from: Data(json.utf8))
        state.sanitize(now: date(2026, 5, 4))
        XCTAssertEqual(state.slime.name, "Blub")
        XCTAssertEqual(state.slime.emotions, Emotions())
        XCTAssertTrue(state.crates.isEmpty)
        XCTAssertTrue(state.settings.chatty)
        XCTAssertTrue(state.inventory.pantry.isEmpty)
        XCTAssertTrue(state.careBook.outbox.isEmpty)
        XCTAssertNil(state.care)
    }
}
