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

private fun hatchedState(now: Instant): GameState {
    val state = GameState()
    state.hatch(name = "Testi", skin = Skin.lime, seed = 42uL, now = now)
    state.settings.sleep.auto = false
    return state
}

class CoreSeasonTests {
    @Test
    fun testSeasonsFollowTheCalendar() {
        assertEquals(Season.halloween, Season.at(date(2026, 10, 3), berlin))
        assertEquals(Season.halloween, Season.at(date(2026, 10, 31, 23), berlin))
        assertEquals(Season.autumn, Season.at(date(2026, 11, 1), berlin))
        assertEquals(Season.christmas, Season.at(date(2026, 12, 10), berlin))
        assertEquals(Season.christmas, Season.at(date(2026, 12, 31, 17), berlin))
        assertEquals(Season.newYear, Season.at(date(2026, 12, 31, 19), berlin))
        assertEquals(Season.newYear, Season.at(date(2027, 1, 1, 15), berlin))
        assertEquals(Season.winter, Season.at(date(2027, 1, 2), berlin))
        assertEquals(Season.spring, Season.at(date(2027, 4, 2), berlin))
        assertEquals(Season.summer, Season.at(date(2027, 7, 2), berlin))
    }

    // iOS also checks the Islamic and Japanese calendars here. SlimyCalendar is always Gregorian
    // (java.time), so only the Gregorian part of the test applies on Android.
    @Test
    fun testSeasonsUseGregorianMonthsWhateverTheCalendar() {
        assertEquals(Season.halloween, Season.at(date(2026, 10, 3), SlimyCalendar.gregorian(like = berlin)))
        assertEquals(Holiday.holidays, Holiday.at(date(2026, 12, 25), berlin))
        val state = hatchedState(date(2026, 10, 2))
        state.slime.xp = 0
        assertTrue(state.isAvailable(Unlock.season(month = 10), date(2026, 10, 3), berlin))
        assertFalse(state.isAvailable(Unlock.season(month = 12), date(2026, 10, 3), berlin))
        // Day keys (daily challenges, today's game) are the same date for everyone.
        assertEquals("2026-10-03", Challenges.dayKey(date(2026, 10, 3), berlin))
    }

    @Test
    fun testHolidays() {
        assertEquals(Holiday.halloween, Holiday.at(date(2026, 10, 31), berlin))
        assertEquals(Holiday.holidays, Holiday.at(date(2026, 12, 24), berlin))
        assertEquals(Holiday.holidays, Holiday.at(date(2026, 12, 26), berlin))
        assertEquals(Holiday.newYear, Holiday.at(date(2027, 1, 1), berlin))
        assertNull(Holiday.at(date(2026, 10, 30), berlin))
        assertNull(Holiday.at(date(2026, 12, 27), berlin))
    }

    @Test
    fun testSeasonalShopItemsOnlyInTheirMonth() {
        val state = hatchedState(date(2026, 11, 1))
        val stars = requireNotNull(Catalog.item("furn.stars"))
        assertFalse(state.isAvailable(stars.unlock, date(2026, 11, 20), berlin))
        assertTrue(state.isAvailable(stars.unlock, date(2026, 12, 2), berlin))
        val lantern = requireNotNull(Catalog.item("furn.jackolantern"))
        assertTrue(state.isAvailable(lantern.unlock, date(2026, 10, 2), berlin))
    }

    @Test
    fun testVisitingInASeasonCounts() {
        val state = hatchedState(date(2026, 9, 30))
        state.tick(date(2026, 10, 1, 9), berlin)
        assertTrue(state.player.seasonsSeen.contains(Season.halloween.rawValue))
        assertNotNull(state.progress.achievements["spooky"])
        assertNull(state.progress.achievements["cozy"])
    }
}

class CoreBirthdayTests {
    @Test
    fun testBirthdayYears() {
        val born = date(2025, 10, 26, 10)
        assertNull(Birthday.years(born, date(2025, 10, 26, 20), berlin))
        assertNull(Birthday.years(born, date(2026, 10, 25), berlin))
        assertEquals(1, Birthday.years(born, date(2026, 10, 26, 0, 5), berlin))
        assertEquals(2, Birthday.years(born, date(2027, 10, 26), berlin))
        assertNull(Birthday.years(born, date(2027, 10, 27), berlin))
    }

    @Test
    fun testLeapDayBirthdaysFallOnThe28th() {
        val born = date(2024, 2, 29)
        assertEquals(1, Birthday.years(born, date(2025, 2, 28), berlin))
        assertNull(Birthday.years(born, date(2025, 3, 1), berlin))
        assertEquals(4, Birthday.years(born, date(2028, 2, 29), berlin))
        assertNull(Birthday.years(born, date(2028, 2, 28), berlin))
    }

    @Test
    fun testBirthdayIsCelebratedOncePerYear() {
        TestSprites.ensureLoaded()
        val state = hatchedState(date(2025, 5, 4, 9))
        val coins = state.wallet.coins
        val events = state.tick(date(2026, 5, 4, 10), berlin)
        assertTrue(events.contains(GameEvent.birthday(1)))
        assertTrue(events.contains(GameEvent.achievement("birthday")))
        assertTrue(state.isBirthday(date(2026, 5, 4, 10), berlin))
        assertTrue(state.owns("furn.cake"))
        assertEquals("1", state.memory.last(MemoryEntry.Kind.birthday)?.detail)
        assertEquals(true, state.memory.last(MemoryEntry.Kind.birthday)?.keepsake)
        assertTrue(state.wallet.coins >= coins + Birthday.coins(1))
        assertEquals(Reaction.celebrate, state.slime.reaction)

        val later = state.tick(date(2026, 5, 4, 18), berlin)
        assertFalse(later.contains(GameEvent.birthday(1)))
        assertEquals(1, state.memory.entries.count { it.kind == MemoryEntry.Kind.birthday })

        // The receipt outlives ledger pruning, so the clock can't replay it.
        state.wallet.prune(date(2026, 9, 1))
        assertTrue(state.wallet.hasClaimed(Birthday.receipt(2026)))

        val next = state.tick(date(2027, 5, 4, 10), berlin)
        assertTrue(next.contains(GameEvent.birthday(2)))
    }

    @Test
    fun testBirthdayAtMidnightDoesNotWakeIt() {
        val state = hatchedState(date(2025, 5, 4, 9))
        state.slime.isAsleep = true
        state.slime.lastUpdate = date(2026, 5, 3, 23)
        state.tick(date(2026, 5, 4, 0, 10), berlin)
        assertTrue(state.slime.isAsleep)
        assertNotNull(state.memory.last(MemoryEntry.Kind.birthday))
    }

    @Test
    fun testHolidayGiftOncePerYear() {
        val state = hatchedState(date(2026, 12, 20))
        val coins = state.wallet.coins
        val events = state.tick(date(2026, 12, 24, 10), berlin)
        assertTrue(events.contains(GameEvent.holiday(Holiday.holidays)))
        assertTrue(state.wallet.coins >= coins + Holiday.holidays.coins)
        assertEquals(Holiday.holidays.rawValue, state.memory.last(MemoryEntry.Kind.holiday)?.detail)
        assertFalse(state.tick(date(2026, 12, 25, 10), berlin).contains(GameEvent.holiday(Holiday.holidays)))
        assertTrue(state.tick(date(2027, 1, 1, 10), berlin).contains(GameEvent.holiday(Holiday.newYear)))
        assertTrue(state.tick(date(2027, 12, 24, 10), berlin).contains(GameEvent.holiday(Holiday.holidays)))
    }

    @Test
    fun testMemoryBookDescribesEveryKind() {
        TestSprites.ensureLoaded()
        val kinds = listOf(
            MemoryEntry.Kind.hatched, MemoryEntry.Kind.fed, MemoryEntry.Kind.favoriteFound, MemoryEntry.Kind.played,
            MemoryEntry.Kind.highScore, MemoryEntry.Kind.bath, MemoryEntry.Kind.levelUp, MemoryEntry.Kind.stageUp,
            MemoryEntry.Kind.achievement, MemoryEntry.Kind.gift, MemoryEntry.Kind.giftReceived, MemoryEntry.Kind.visit,
            MemoryEntry.Kind.friendMade, MemoryEntry.Kind.event, MemoryEntry.Kind.reunion, MemoryEntry.Kind.birthday,
            MemoryEntry.Kind.streak, MemoryEntry.Kind.holiday, MemoryEntry.Kind.crate, MemoryEntry.Kind.partnerCare,
            MemoryEntry.Kind.careStarted, MemoryEntry.Kind.careEnded,
        )
        assertEquals(MemoryEntry.Kind.entries.toSet(), kinds.toSet())
        for (kind in kinds) {
            val entry = MemoryEntry(kind = kind, at = date(2026, 5, 4), detail = "1")
            assertFalse(kind.rawValue, entry.summary.isEmpty())
            assertNotNull("${kind.rawValue}: ${entry.icon}", SlimeSprites.items[entry.icon])
        }
        assertEquals("First birthday", MemoryEntry(kind = MemoryEntry.Kind.birthday, at = Instant.now(), detail = "1").summary)
        assertEquals("30 days in a row", MemoryEntry(kind = MemoryEntry.Kind.streak, at = Instant.now(), detail = "30").summary)
    }

    @Test
    fun testDamagedMemoryEntryDoesNotWipeTheBook() {
        val json = """
        {"entries": [
          {"id": "7D3D2A1E-8B9A-4C7E-9C51-2E9B4F0A1B2C", "kind": "hatched", "at": 1767225600, "detail": "Blub", "keepsake": true},
          {"id": "1A2B3C4D-1111-2222-3333-444455556666", "kind": "somethingNew", "at": 1767225600, "detail": "", "keepsake": true}
        ]}
        """
        val memory = SlimyJson.storage.decodeFromString(Memory.serializer(), json)
        assertEquals(1, memory.entries.size)
        assertEquals(MemoryEntry.Kind.hatched, memory.entries.first().kind)
    }
}

class CoreDialogueTests {
    private val newCategories = listOf(
        LineCategory.dailyGift, LineCategory.giftWeek, LineCategory.streak, LineCategory.streakFresh, LineCategory.spooky,
        LineCategory.cozy, LineCategory.newYear, LineCategory.birthday,
        LineCategory.feelJoy, LineCategory.feelSad, LineCategory.feelAngry, LineCategory.feelScared, LineCategory.feelLove,
        LineCategory.feelCurious, LineCategory.feelBored, LineCategory.feelExcited, LineCategory.feelSleepy,
        LineCategory.afterFood, LineCategory.afterGame, LineCategory.afterFriend, LineCategory.afterCrate,
        LineCategory.partnerCared, LineCategory.careStarted, LineCategory.careEnded,
        LineCategory.crateOpened, LineCategory.crateDuplicate, LineCategory.crateLegendary, LineCategory.bathGrumpy,
        LineCategory.spring, LineCategory.summer, LineCategory.autumn, LineCategory.winter, LineCategory.roomTalk,
        LineCategory.gardenTalk, LineCategory.beachTalk, LineCategory.spaceTalk, LineCategory.crystalTalk,
        LineCategory.rainTalk, LineCategory.snowTalk, LineCategory.cloudTalk,
    )

    @Test
    fun testNewCategoriesHaveLowercaseLines() {
        for (category in newCategories) {
            val lines = Dialogue.lines[category] ?: emptyList()
            assertTrue(category.rawValue, lines.size >= 3)
            for (line in lines) {
                assertEquals(line.id, line.text.lowercase(), line.text)
                assertTrue(line.id, line.text.codePointCount(0, line.text.length) <= 60)
            }
        }
    }

    @Test
    fun testLineIDsAreUnique() {
        val ids = Dialogue.lines.values.flatMap { lines -> lines.map { it.id } }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.size >= 400)
    }

    @Test
    fun testEveryCategoryHasLines() {
        for (category in LineCategory.entries) {
            assertFalse(category.rawValue, (Dialogue.lines[category] ?: emptyList()).isEmpty())
        }
    }

    @Test
    fun testFeelingsHabitatsAndSeasonsHaveTheirOwnTalk() {
        for (emotion in Emotion.entries) {
            assertTrue(emotion.rawValue, (Dialogue.lines[Dialogue.category(emotion)]?.size ?: 0) >= 10)
        }
        for (habitat in Habitat.entries) {
            assertTrue(habitat.rawValue, (Dialogue.lines[Dialogue.category(habitat)]?.size ?: 0) >= 5)
        }
        for (season in Season.entries) {
            assertTrue(season.rawValue, (Dialogue.lines[season.talk]?.size ?: 0) >= 5)
        }
    }

    @Test
    fun testLoveBetweenSlimesIsGone() {
        assertNull(enumOf<LineCategory>("inLove"))
        assertNull(enumOf<MemoryEntry.Kind>("inLove"))
        assertNull(Achievements.achievement("inLove"))
    }

    @Test
    fun testStreakAndYearPlaceholders() {
        val state = hatchedState(date(2026, 5, 4))
        assertEquals("day 4", Dialogue.fill("day {streak}", LineContext(streak = 4), state))
        assertEquals("1 year old", Dialogue.fill("{years} old", LineContext(years = 1), state))
        assertEquals("3 years old", Dialogue.fill("{years} old", LineContext(years = 3), state))
    }

    @Test
    fun testTimeOfDay() {
        assertEquals(LineCategory.morning, Dialogue.timeOfDay(7))
        assertEquals(LineCategory.day, Dialogue.timeOfDay(13))
        assertEquals(LineCategory.evening, Dialogue.timeOfDay(19))
        assertEquals(LineCategory.night, Dialogue.timeOfDay(2))
    }

    @Test
    fun testChatterMixesInTheSeason() {
        val state = hatchedState(date(2026, 10, 10, 11))
        for (i in 0 until 60) {
            state.chatter(date(2026, 10, 10, 11).addingTimeInterval(i * 30.0), berlin)
        }
        val said = state.memory.lineUsedAt.keys
        assertTrue(said.any { it.startsWith("spooky.") })
        assertTrue(said.any { !it.startsWith("spooky.") })
    }
}
