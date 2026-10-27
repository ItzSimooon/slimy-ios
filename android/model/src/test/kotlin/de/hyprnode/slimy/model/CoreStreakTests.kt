package de.hyprnode.slimy.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Clock for the streak tests: Berlin, which has daylight saving. */
private val berlin = SlimyCalendar.gregorian("Europe/Berlin")

private fun date(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0, calendar: SlimyCalendar = berlin): Instant =
    calendar.date(year, month, day, hour, minute)

/** Hatched in May, far from any season, holiday or birthday. */
private fun hatchedState(now: Instant = date(2026, 5, 1)): GameState {
    val state = GameState()
    state.hatch(name = "Testi", skin = Skin.lime, seed = 42uL, now = now)
    state.settings.sleep.auto = false
    return state
}

private fun decode(json: String): GameState = requireNotNull(GameStorage.decode(json)) { "didn't decode" }

class CoreStreakTests {
    @Test
    fun testDayDistanceCountsDatesNotHours() {
        assertEquals(1, Streak.days("2026-05-04", "2026-05-05"))
        assertEquals(-1, Streak.days("2026-05-05", "2026-05-04"))
        // Daylight saving starts and ends in Berlin.
        assertEquals(1, Streak.days("2026-03-28", "2026-03-29"))
        assertEquals(2, Streak.days("2026-10-24", "2026-10-26"))
        assertEquals(1, Streak.days("2026-12-31", "2027-01-01"))
        assertEquals(2, Streak.days("2028-02-28", "2028-03-01"))
        assertNull(Streak.days("garbage", "2026-05-04"))
        assertNull(Streak.days("2026-13-01", "2026-05-04"))
    }

    @Test
    fun testGiftOpensOncePerDay() {
        val state = hatchedState()
        val now = date(2026, 5, 4, 9)
        val info = state.streakInfo(now, berlin)
        assertTrue(info.canClaim)
        assertFalse(info.claimedToday)
        assertEquals(1, info.today)
        assertEquals(0, info.current)
        assertNull(info.nextGift)

        val coins = state.wallet.coins
        val first = requireNotNull(state.claimDailyGift(now, berlin))
        assertEquals(1, first.gift.day)
        assertEquals(Streak.coins[0], first.gift.coins)
        assertTrue(first.outcome.ok)
        assertTrue(state.wallet.coins >= coins + Streak.coins[0])
        assertTrue(state.wallet.hasClaimed(Streak.receipt("2026-05-04")))
        assertNotNull(state.slime.say)

        // Later the same day: nothing more to open.
        val evening = date(2026, 5, 4, 23, 50)
        assertNull(state.claimDailyGift(evening, berlin))
        val after = state.streakInfo(evening, berlin)
        assertFalse(after.canClaim)
        assertTrue(after.claimedToday)
        assertEquals(1, after.current)
        assertEquals(1, after.today)
        assertTrue(after.isOpened(after.week[0]))
        assertEquals(date(2026, 5, 5, 0), after.nextGift)
    }

    @Test
    fun testConsecutiveDaysAcrossDaylightSaving() {
        val state = hatchedState(date(2026, 3, 20))
        // 23:30 on Saturday and 00:30 on Sunday, the night the clocks go forward.
        assertNotNull(state.claimDailyGift(date(2026, 3, 28, 23, 30), berlin))
        assertNotNull(state.claimDailyGift(date(2026, 3, 29, 0, 30), berlin))
        assertNotNull(state.claimDailyGift(date(2026, 3, 30, 21), berlin))
        assertEquals(3, state.streak.count)
        assertEquals(3, state.streak.best)
        assertEquals(3, state.streak.opened)
    }

    @Test
    fun testMissedDayRestartsStreakButKeepsGifts() {
        val state = hatchedState()
        assertNotNull(state.claimDailyGift(date(2026, 5, 4), berlin))
        assertNotNull(state.claimDailyGift(date(2026, 5, 5), berlin))
        // The 6th is missed. The streak is over the day after it.
        assertEquals(2, state.streakInfo(date(2026, 5, 6), berlin).current)
        val back = state.streakInfo(date(2026, 5, 7), berlin)
        assertEquals(0, back.current)
        assertTrue(back.canClaim)
        // The calendar didn't go back: it's the third gift.
        assertEquals(3, back.today)
        val third = state.claimDailyGift(date(2026, 5, 7), berlin)
        assertEquals(3, third?.gift?.day)
        assertEquals(1, state.streak.count)
        assertEquals(2, state.streak.best)
        assertEquals(3, state.streak.opened)
    }

    @Test
    fun testSeventhGiftIsAMysteryCrateAndItsCoins() {
        val state = hatchedState()
        var last: DailyGift? = null
        var coinsBefore = state.wallet.coins
        for (day in 4..10) {
            coinsBefore = state.wallet.coins
            last = state.claimDailyGift(date(2026, 5, day), berlin)?.gift
        }
        val seventh = requireNotNull(last)
        assertEquals(7, seventh.day)
        assertTrue(seventh.isSpecial)
        assertEquals(CrateKind.mystery, seventh.crate)
        assertNull(seventh.item)
        assertEquals(Streak.coins[6], seventh.coins)
        assertEquals("Mystery crate and ${Streak.coins[6]} coins", seventh.summary)
        assertEquals(CrateKind.mystery.sprite, seventh.icon)
        assertEquals(1, state.crateCount(CrateKind.mystery))
        assertTrue(state.wallet.coins >= coinsBefore + Streak.coins[6])
        for (id in listOf("streak3", "streak7", "gifts7")) {
            assertNotNull(id, state.progress.achievements[id])
        }
        // The furniture that used to come on day seven is free in the shop once its achievement is earned.
        val chest = requireNotNull(Catalog.item("furn.chest"))
        assertTrue(state.isAvailable(chest.unlock, date(2026, 5, 10), berlin))

        // Still the 7th: the tile shows what it held.
        val sameDay = state.streakInfo(date(2026, 5, 10, 20), berlin)
        assertEquals(7, sameDay.today)
        assertEquals(CrateKind.mystery, sameDay.week[6].crate)

        // A new week begins, with another crate waiting at its end.
        val nextWeek = state.streakInfo(date(2026, 5, 11), berlin)
        assertEquals(1, nextWeek.today)
        assertEquals(7, nextWeek.current)
        assertEquals(CrateKind.mystery, nextWeek.week[6].crate)
    }

    @Test
    fun testOnlyTheSeventhGiftHoldsACrate() {
        val state = hatchedState()
        val week = state.streakInfo(date(2026, 5, 4), berlin).week
        assertEquals((1..7).toList(), week.map { it.day })
        assertEquals(listOf(CrateKind.mystery), week.mapNotNull { it.crate })
        assertNull(week[0].crate)
        assertEquals(Streak.coins, week.map { it.coins })
        assertEquals("${Streak.coins[0]} coins", week[0].title)
    }

    @Test
    fun testClockOneDayBackWaitsForTheDayAfter() {
        val state = hatchedState()
        assertNotNull(state.claimDailyGift(date(2026, 5, 5), berlin))
        val earlier = state.streakInfo(date(2026, 5, 4, 15), berlin)
        assertFalse(earlier.canClaim)
        assertFalse(earlier.claimedToday)
        assertEquals(1, earlier.current)
        assertEquals(date(2026, 5, 6, 0), earlier.nextGift)
        assertNull(state.claimDailyGift(date(2026, 5, 4, 15), berlin))
    }

    @Test
    fun testClockSetFarAheadThenBackStartsAgain() {
        val state = hatchedState()
        assertNotNull(state.claimDailyGift(date(2026, 8, 1), berlin))
        val back = date(2026, 5, 10)
        assertTrue(state.streakInfo(back, berlin).canClaim)
        assertNotNull(state.claimDailyGift(back, berlin))
        assertEquals(1, state.streak.count)
        assertEquals("2026-05-10", state.streak.lastDay)
        // The same date never pays twice, whatever the clock does.
        state.streak.lastDay = "2026-05-08"
        assertFalse(state.streakInfo(back, berlin).canClaim)
        assertNull(state.claimDailyGift(back, berlin))
    }

    @Test
    fun testFlyingEastCountsAsTheNextDay() {
        val tokyo = SlimyCalendar.gregorian("Asia/Tokyo")
        val state = hatchedState()
        val lateInBerlin = date(2026, 5, 4, 23)
        assertNotNull(state.claimDailyGift(lateInBerlin, berlin))
        // An hour later it's already the 5th in Tokyo.
        val landed = lateInBerlin.addingTimeInterval(3600.0)
        assertTrue(state.streakInfo(landed, tokyo).canClaim)
        assertNotNull(state.claimDailyGift(landed, tokyo))
        assertEquals(2, state.streak.count)
    }

    @Test
    fun testNotHatchedHasNothingToOpen() {
        val state = GameState()
        val now = date(2026, 5, 4)
        assertFalse(state.streakInfo(now, berlin).canClaim)
        assertNull(state.claimDailyGift(now, berlin))
        assertEquals(StreakState(), state.streak)
    }

    @Test
    fun testStreakDecodesFromOlderAndDamagedSaves() {
        val a = decode("""{"version": 2, "hatched": true, "slime": {"name": "Blub"}}""")
        assertEquals(StreakState(), a.streak)
        assertTrue(a.player.seasonsSeen.isEmpty())

        val b = decode("""{"version": 3, "hatched": true, "streak": "lots"}""")
        assertEquals(StreakState(), b.streak)

        val c = decode("""{"version": 3, "hatched": true, "streak": {"count": -4, "best": 3, "opened": 9, "lastDay": 17}}""")
        c.sanitize(date(2026, 5, 4))
        assertEquals(0, c.streak.count)
        assertEquals(3, c.streak.best)
        assertEquals(9, c.streak.opened)
        assertNull(c.streak.lastDay)
    }

    @Test
    fun testStreakSurvivesASaveRoundTrip() {
        val state = hatchedState()
        for (day in 4..6) state.claimDailyGift(date(2026, 5, day), berlin)
        val back = decode(GameStorage.encode(state))
        assertEquals(state.streak, back.streak)
        assertEquals(state.streakInfo(date(2026, 5, 7), berlin), back.streakInfo(date(2026, 5, 7), berlin))
    }
}

class CoreAchievementTests {
    @Test
    fun testAchievementIDsAreUniqueAndPlenty() {
        val ids = Achievements.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.size >= 49)
        for (a in Achievements.all) {
            assertFalse(a.id, a.title.isEmpty())
            assertTrue(a.id, a.detail.endsWith("."))
            assertTrue(a.id, a.coins > 0)
        }
        // Exactly one secret: the legendary find.
        assertEquals(listOf("legendary"), Achievements.all.filter { it.hidden }.map { it.id })
        assertFalse(Achievements.all.none { it.crate != null })
    }

    @Test
    fun testFreshSlimeHasNoneOfTheNewOnes() {
        val state = hatchedState()
        for (id in listOf("streak3", "gifts7", "birthday", "stackStar", "dashStar", "dailyRegular", "dressedUp", "oldFriends")) {
            assertFalse(id, Achievements.achievement(id)?.check?.invoke(state) ?: true)
        }
    }

    @Test
    fun testItemsUnlockedByAchievementsPointToRealOnes() {
        for (item in Catalog.items) {
            val unlock = item.unlock
            if (unlock is Unlock.achievement) assertNotNull(item.id, Achievements.achievement(unlock.id))
        }
        for (id in listOf("furn.chest", "furn.crystal", "furn.flag", "furn.cake", "furn.jackolantern", "furn.stars")) {
            assertNotNull(id, Catalog.item(id))
        }
    }

    // The second half of the iOS test checks WorldArt (the habitat's furniture art), which lives in :app.
    @Test
    fun testEveryCatalogItemHasArt() {
        TestSprites.ensureLoaded()
        for (item in Catalog.items) {
            if (item.kind == ItemKind.effect) continue
            assertNotNull("${item.id} shows ${item.sprite}", SlimeSprites.items[item.sprite])
        }
    }

    @Test
    fun testNewGamesHaveAchievements() {
        val state = hatchedState()
        assertFalse(state.isGood(GameID.slimeStack))
        assertNull(Achievements.all.firstOrNull { it.id == "stackStar" && it.check(state) })
        state.progress.records = state.progress.records +
            (GameID.slimeStack to GameRecord(best = GameID.slimeStack.goodScore, plays = 1, lastPlayed = null, tutorialSeen = true))
        assertTrue(state.isGood(GameID.slimeStack))
        assertTrue(Achievements.achievement("stackStar")?.check?.invoke(state) ?: false)
        assertFalse(Achievements.achievement("dashStar")?.check?.invoke(state) ?: true)
    }

    @Test
    fun testDressedUpNeedsAllThree() {
        val state = hatchedState()
        state.slime.equipped = Accessories(hat = "hat.party", glasses = "glasses.round", scarf = null, effect = null)
        assertFalse(Achievements.achievement("dressedUp")?.check?.invoke(state) ?: true)
        state.slime.equipped.scarf = "scarf.red"
        assertTrue(Achievements.achievement("dressedUp")?.check?.invoke(state) ?: false)
    }

    @Test
    fun testUnlockHints() {
        assertEquals("October only", Unlock.season(month = 10).hint)
        assertEquals("December only", Unlock.season(month = 12).hint)
        assertEquals("From level 4", Unlock.level(4).hint)
        assertEquals("Achievement: Gift week", Unlock.achievement("gifts7").hint)
    }

    @Test
    fun testTriedThemAllNeedsAFinishedRoundOfEach() {
        val state = hatchedState()
        // Opening a game's tutorial creates its record, but isn't a round.
        for (game in GameID.classics) state.markTutorialSeen(game)
        assertFalse(Achievements.achievement("allGames")?.check?.invoke(state) ?: true)
        for (game in GameID.classics) {
            state.progress.records = state.progress.records + (game to state.record(game).copy(plays = 1))
        }
        assertTrue(Achievements.achievement("allGames")?.check?.invoke(state) ?: false)
    }

    @Test
    fun testDailyChallengesOnlyForGamesThatAreOpen() {
        val early = Challenges.dailyOptions(forLevel = 1)
        assertTrue(early.size >= 3)
        for (template in early) {
            val goal = template.goal
            if (goal is ChallengeTemplate.Goal.score) assertEquals(template.id, 1, goal.game.unlockLevel)
        }
        assertEquals(Challenges.dailyPool.size, Challenges.dailyOptions(forLevel = 20).size)

        val state = hatchedState()
        state.refreshChallenges(date(2026, 5, 2), berlin)
        assertEquals(3, state.progress.challenges.daily.size)
        for (challenge in state.progress.challenges.daily) {
            val goal = Challenges.template(challenge.id)?.goal
            if (goal is ChallengeTemplate.Goal.score) assertTrue(challenge.id, goal.game.unlockLevel <= state.slime.level)
        }
    }

    @Test
    fun testNewGamesHaveDailyChallenges() {
        val games = Challenges.dailyPool.mapNotNull { (it.goal as? ChallengeTemplate.Goal.score)?.game }
        assertTrue(games.contains(GameID.slimeStack))
        assertTrue(games.contains(GameID.jellyDash))
    }

    @Test
    fun testHatchColoursAreOpenFromTheStart() {
        val state = hatchedState()
        assertEquals(1, state.slime.level)
        for (skin in Skin.hatchChoices) assertTrue(skin.rawValue, state.slime.isUnlocked(skin))
        assertFalse(state.slime.isUnlocked(Skin.grape))
    }

    @Test
    fun testTheSecretColourNeverUnlocksByLevel() {
        val state = hatchedState()
        state.slime.xp = Slime.xpNeeded(200)
        assertTrue(state.slime.isUnlocked(Skin.void))
        assertFalse(state.slime.isUnlocked(Skin.axolotl))
        assertTrue(Skin.axolotl.isSecret)
        state.slime.colours = state.slime.colours + Skin.axolotl
        assertTrue(state.slime.isUnlocked(Skin.axolotl))
    }
}
