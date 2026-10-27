package de.hyprnode.slimy.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private val berlin = SlimyCalendar.gregorian("Europe/Berlin")

private fun date(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Instant =
    berlin.date(year, month, day, hour, minute)

/**
 * A save as Slimy 1.0 wrote it: with love between slimes, the seventh-day
 * item of the old gift calendar, and none of the 1.1 fields.
 */
private const val saveFrom10 = """
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

private fun decode(json: String): GameState = requireNotNull(GameStorage.decode(json)) { "didn't decode" }

class CoreMigrationTests {
    @Test
    fun testASaveFrom10LoadsWithEverythingNewAtItsDefault() {
        val state = decode(saveFrom10)
        val now = date(2026, 5, 4, 15)
        state.sanitize(now)

        // Everything that was there is still there.
        assertTrue(state.hatched)
        assertEquals("Gloop", state.slime.name)
        assertEquals(Skin.ocean, state.slime.skin)
        assertEquals(500, state.slime.xp)
        assertEquals(62.0, state.slime.bond, 0.0)
        assertEquals(0.9, state.slime.personality.curious, 0.0)
        assertEquals(Food.honey, state.slime.favorite)
        assertEquals("hat.party", state.slime.equipped.hat)
        assertEquals("Sam", state.player.name)
        assertEquals(240, state.wallet.coins)
        assertEquals(900, state.wallet.earnedTotal)
        assertTrue(state.owns("furn.chest"))
        assertEquals("furn.chest", state.inventory.furniture[FurnitureSlot.floorRight])
        assertEquals(12, state.progress.stats.feeds)
        assertEquals(1, state.progress.stats.goodGames)
        assertFalse(state.settings.sound)
        assertEquals(23, state.settings.sleep.bedtime)
        assertFalse(state.settings.notifications.daily)
        assertEquals(6, state.streak.count)
        assertEquals(9, state.streak.best)
        assertEquals(13, state.streak.opened)

        // What's new in 1.1 starts at its default.
        assertEquals(Emotions(), state.slime.emotions)
        assertTrue(state.slime.colours.isEmpty())
        assertTrue(state.crates.isEmpty())
        assertTrue(state.inventory.pantry.isEmpty())
        assertNull(state.care)
        assertTrue(state.careBook.outbox.isEmpty())
        assertFalse(state.careBook.me.isEmpty())
        assertTrue(state.settings.chatty)
        assertEquals(0, state.progress.stats.cratesOpened)
        assertEquals(0, state.progress.stats.partnerCare)

        // Love between slimes is gone; the rest of the memory book stays.
        assertEquals(listOf(MemoryEntry.Kind.hatched, MemoryEntry.Kind.friendMade), state.memory.entries.map { it.kind })
        assertNull(Achievements.achievement("inLove"))

        // The seventh gift of the old calendar is now a mystery crate.
        val info = state.streakInfo(now, berlin)
        assertTrue(info.canClaim)
        assertEquals(7, info.today)
        assertEquals(CrateKind.mystery, info.todayGift.crate)
        val claimed = requireNotNull(state.claimDailyGift(now, berlin))
        assertEquals(CrateKind.mystery, claimed.gift.crate)
        assertEquals(1, state.crateCount(CrateKind.mystery))
        assertEquals(7, state.streak.count)

        // It talks, feels and opens crates like any 1.1 slime.
        val line = state.chatter(now.addingTimeInterval(60.0), berlin)
        assertFalse(line.isEmpty())
        assertFalse(state.memory.recentLines.takeLast(1).any { it.startsWith("inLove") })
        assertNotNull(state.openCrate(CrateKind.mystery, now.addingTimeInterval(120.0), seed = 1uL))
        assertEquals(0, state.crateCount(CrateKind.mystery))
    }

    @Test
    fun testTheMigratedSaveExportsAsTheCurrentVersion() {
        val old = decode(saveFrom10)
        val file = SaveFile.encode(old, date(2026, 5, 4, 15), appVersion = "1.0.0")
        val state = SaveFile.decode(file.toByteArray(), date(2026, 5, 4, 15))
        assertEquals(GameState.currentVersion, state.version)
        assertEquals(4, GameState.currentVersion)
        assertEquals("Gloop", state.slime.name)

        // And a 1.1 save reads back whole.
        val current = state.deepCopy()
        current.addCrate(CrateKind.golden, 2)
        current.inventory.pantry = current.inventory.pantry + (Food.cookie to 3)
        current.slime.colours = current.slime.colours + Skin.axolotl
        current.slime.emotions[Emotion.curiosity] = 0.6
        current.settings.chatty = false
        val data = GameStorage.encode(current)
        val back = decode(data)
        assertEquals(2, back.crateCount(CrateKind.golden))
        assertEquals(3, back.servings(Food.cookie))
        assertTrue(back.slime.isUnlocked(Skin.axolotl))
        assertEquals(current.slime.emotions, back.slime.emotions)
        assertFalse(back.settings.chatty)
    }

    @Test
    fun testDamagedNewFieldsFallBack() {
        val json = """
        {"version": 4, "hatched": true, "slime": {"name": "Blub", "emotions": "happy", "colours": ["axolotl", 7]},
         "crates": "many", "care": {"partnerID": 12}, "careBook": [1, 2], "settings": {"chatty": "yes"},
         "inventory": {"pantry": {"pizza": 3}}}
        """
        val state = decode(json)
        state.sanitize(date(2026, 5, 4))
        assertEquals("Blub", state.slime.name)
        assertEquals(Emotions(), state.slime.emotions)
        assertTrue(state.crates.isEmpty())
        assertTrue(state.settings.chatty)
        assertTrue(state.inventory.pantry.isEmpty())
        assertTrue(state.careBook.outbox.isEmpty())
        assertNull(state.care)
    }
}
