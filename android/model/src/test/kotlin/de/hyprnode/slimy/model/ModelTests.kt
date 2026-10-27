package de.hyprnode.slimy.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Fixed clock for every test: 25 Oct 2026, 12:00 in Berlin. */
private val berlin = SlimyCalendar.gregorian("Europe/Berlin")
private val noon: Instant = berlin.date(2026, 10, 25, 12)

private fun hatchedState(now: Instant = noon): GameState {
    val state = GameState()
    state.hatch(name = "Testi", skin = Skin.lime, seed = 42uL, now = now)
    state.settings.sleep.auto = false
    return state
}

class NeedsTests {
    @Test
    fun testValuesStayInRange() {
        val needs = Needs()
        needs.add(Need.hunger, 500.0)
        needs.add(Need.`fun`, -500.0)
        needs[Need.love] = Double.NaN
        assertEquals(100.0, needs.hunger, 0.0)
        assertEquals(0.0, needs.`fun`, 0.0)
        assertEquals(50.0, needs.love, 0.0)
    }

    @Test
    fun testLowest() {
        val needs = Needs()
        needs.clean = 3.0
        assertEquals(Need.clean, needs.lowest.need)
    }
}

class TimeTests {
    @Test
    fun testNeedsDecayWhileAway() {
        val slime = hatchedState().slime
        val before = slime.needs.copy()
        slime.advance(noon.addingTimeInterval(3.0 * 3600), SleepSchedule(bedtime = 22, wake = 7, auto = false), berlin)
        assertTrue(slime.needs.hunger < before.hunger)
        assertTrue(slime.needs.`fun` < before.`fun`)
    }

    @Test
    fun testLongAbsenceNeverDropsBelowRestingFloor() {
        val slime = hatchedState().slime
        slime.advance(noon.addingTimeInterval(90.0 * 24 * 3600), SleepSchedule(), berlin)
        for (need in Need.entries) {
            assertTrue("$need", slime.needs[need] >= Slime.restingFloor - 0.001)
        }
    }

    @Test
    fun testClockMovingBackwardsChangesNothing() {
        val slime = hatchedState().slime
        val before = slime.needs.copy()
        slime.advance(noon.addingTimeInterval(-6.0 * 3600), SleepSchedule(), berlin)
        assertEquals(before, slime.needs)
        assertEquals(noon.addingTimeInterval(-6.0 * 3600), slime.lastUpdate)
        // And going forward again from there works normally.
        slime.advance(noon, SleepSchedule(auto = false), berlin)
        assertTrue(slime.needs.hunger < before.hunger)
    }

    @Test
    fun testFallsAsleepAtBedtimeAndWakesInTheMorning() {
        val slime = hatchedState().slime
        val evening = berlin.date(2026, 10, 25, 21, 50)
        slime.lastUpdate = evening
        slime.advance(evening.addingTimeInterval(30.0 * 60), SleepSchedule(bedtime = 22, wake = 7), berlin)
        assertTrue(slime.isAsleep)
        val morning = berlin.date(2026, 10, 26, 7, 20)
        slime.advance(morning, SleepSchedule(bedtime = 22, wake = 7), berlin)
        assertFalse(slime.isAsleep)
    }
}

class ProgressionTests {
    @Test
    fun testLevelCurve() {
        assertEquals(1, Slime.level(forXP = 0))
        assertEquals(1, Slime.level(forXP = 39))
        assertEquals(2, Slime.level(forXP = 40))
        assertEquals(2, Slime.level(forXP = 119))
        assertEquals(3, Slime.level(forXP = 120))
        for (level in 1..30) {
            assertEquals(level, Slime.level(forXP = Slime.xpNeeded(level)))
        }
    }

    @Test
    fun testStages() {
        assertEquals(Stage.baby, Stage.forLevel(1))
        assertEquals(Stage.baby, Stage.forLevel(3))
        assertEquals(Stage.young, Stage.forLevel(4))
        assertEquals(Stage.young, Stage.forLevel(11))
        assertEquals(Stage.grown, Stage.forLevel(12))
    }

    @Test
    fun testLevelRewardIsPaidOnce() {
        val state = hatchedState()
        val coins = state.wallet.coins
        state.slime.xp = Slime.xpNeeded(2) - 1
        state.pet(noon)
        assertTrue(state.slime.level >= 2)
        assertTrue(state.wallet.hasClaimed("level:2"))
        val afterLevel = state.wallet.coins
        assertTrue(afterLevel > coins)
        assertFalse(state.wallet.claim("level:2", coins = 20, at = noon))
        assertEquals(afterLevel, state.wallet.coins)
    }
}

class EconomyTests {
    @Test
    fun testWalletClaimIsIdempotent() {
        val wallet = Wallet()
        assertTrue(wallet.claim("x", coins = 10, at = noon))
        assertFalse(wallet.claim("x", coins = 10, at = noon))
        assertEquals(40, wallet.coins)
        assertEquals(10, wallet.earnedTotal)
    }

    @Test
    fun testCannotSpendMoreThanYouHaveOrNegative() {
        val wallet = Wallet()
        assertFalse(wallet.spend(31))
        assertFalse(wallet.spend(-5))
        assertTrue(wallet.spend(30))
        assertEquals(0, wallet.coins)
    }

    @Test
    fun testPruneKeepsPermanentReceipts() {
        val wallet = Wallet()
        val old = noon.addingTimeInterval(-90.0 * 24 * 3600)
        wallet.claim("game:old", coins = 1, at = old)
        wallet.claim("level:3", coins = 1, at = old)
        wallet.claim("buy:hat.party", coins = 0, at = old)
        wallet.prune(noon)
        assertFalse(wallet.hasClaimed("game:old"))
        assertTrue(wallet.hasClaimed("level:3"))
        assertTrue(wallet.hasClaimed("buy:hat.party"))
    }

    @Test
    fun testGameRewardsAreBounded() {
        for (game in GameID.entries) {
            val none = GameResult(game = game, score = 0, session = UUID.randomUUID(), finishedAt = noon)
            val negative = GameResult(game = game, score = -50, session = UUID.randomUUID(), finishedAt = noon)
            val huge = GameResult(game = game, score = 1_000_000, session = UUID.randomUUID(), finishedAt = noon)
            val good = GameResult(game = game, score = game.goodScore, session = UUID.randomUUID(), finishedAt = noon)
            assertEquals(2, none.coins)
            assertEquals(2, negative.coins)
            assertEquals(30, huge.coins)
            assertEquals(14, good.coins)
            assertTrue(huge.xp <= 30)
        }
    }

    @Test
    fun testFinishedRoundPaysExactlyOnce() {
        val state = hatchedState()
        val result = GameResult(game = GameID.bubblePop, score = 420, session = UUID.randomUUID(), finishedAt = noon)
        val first = state.finish(result)
        assertTrue(first.ok)
        val coins = state.wallet.coins
        val xp = state.slime.xp
        val plays = state.progress.stats.plays
        val second = state.finish(result)
        assertFalse(second.ok)
        assertEquals(coins, state.wallet.coins)
        assertEquals(xp, state.slime.xp)
        assertEquals(plays, state.progress.stats.plays)
        assertEquals(420, state.record(GameID.bubblePop).best)
        assertEquals(1, state.record(GameID.bubblePop).plays)
    }

    @Test
    fun testAchievementPaysOnce() {
        val state = hatchedState()
        state.finish(GameResult(game = GameID.colorMatch, score = 5, session = UUID.randomUUID(), finishedAt = noon))
        assertNotNull(state.progress.achievements["arcadeRookie"])
        val coins = state.wallet.coins
        state.checkAchievements(noon)
        assertEquals(coins, state.wallet.coins)
    }

    @Test
    fun testDailyChallengesAreDeterministicAndDistinct() {
        val a = Challenges.pick(Challenges.dailyPool, 3, "2026-10-25").map { it.id }
        val b = Challenges.pick(Challenges.dailyPool, 3, "2026-10-25").map { it.id }
        assertEquals(a, b)
        assertEquals(3, a.toSet().size)
    }

    @Test
    fun testChallengeClaimsOnlyWhenDoneAndOnlyOnce() {
        val state = hatchedState()
        state.refreshChallenges(noon, berlin)
        val challenge = state.progress.challenges.daily.firstOrNull() ?: return fail("no challenge")
        val template = Challenges.template(challenge.id) ?: return fail("no template")
        assertFalse(state.claimChallenge(challenge.id, noon).ok)
        when (val goal = template.goal) {
            is ChallengeTemplate.Goal.stat -> state.progress.stats[goal.key] = state.progress.stats[goal.key] + goal.target
            is ChallengeTemplate.Goal.score -> state.progress.challenges.daily[0].best = goal.target
        }
        val coins = state.wallet.coins
        assertTrue(state.claimChallenge(challenge.id, noon).ok)
        assertEquals(coins + template.coins, state.wallet.coins)
        assertFalse(state.claimChallenge(challenge.id, noon).ok)
        assertEquals(coins + template.coins, state.wallet.coins)
    }
}

class DialogueTests {
    @Test
    fun testDoesNotRepeatItselfRightAway() {
        val state = hatchedState()
        // Only lines that fit the bond and the hour can be picked.
        val hour = berlin.hour(noon)
        val bank = (Dialogue.lines[LineCategory.pet] ?: emptyList()).count {
            it.minBond.toDouble() <= state.slime.bond && (it.hours?.contains(hour) ?: true)
        }
        assertTrue(bank >= 2)
        val said = mutableListOf<String>()
        for (i in 0 until minOf(5, bank)) {
            val line = Dialogue.pick(LineCategory.pet, state, noon.addingTimeInterval(i * 60.0), berlin)
            state.memory.used(line.id, noon)
            said.add(line.id)
        }
        assertEquals(said.size, said.toSet().size)
    }

    @Test
    fun testPlaceholdersAreFilled() {
        val state = hatchedState()
        val text = Dialogue.fill("{name} likes {food}", LineContext(food = "apples"), state)
        assertEquals("Testi likes apples", text)
    }

    @Test
    fun testEveryLineIsFilledCompletely() {
        val state = hatchedState()
        val ctx = LineContext(food = "a", favorite = "b", friend = "c", score = 1, game = "d", level = 2, days = 3, item = "e")
        for ((_, lines) in Dialogue.lines) {
            for (line in lines) {
                assertFalse(line.id, Dialogue.fill(line.text, ctx, state).contains("{"))
            }
        }
    }
}

class PersistenceTests {
    @Test
    fun testRoundTrip() {
        val state = hatchedState()
        state.finish(GameResult(game = GameID.treasureDig, score = 60, session = UUID.randomUUID(), finishedAt = noon))
        val data = GameStorage.encode(state)
        val back = GameStorage.decode(data) ?: return fail("didn't decode")
        assertEquals("Testi", back.slime.name)
        assertEquals(state.wallet, back.wallet)
        assertEquals(state.progress, back.progress)
        assertEquals(state.slime.needs, back.slime.needs)
    }

    @Test
    fun testDamagedSaveStillLoads() {
        val json = """
        {"version": 2, "hatched": true,
         "wallet": {"coins": "lots"},
         "slime": {"name": "Blub", "needs": {"hunger": 5000, "fun": -3, "energy": 50, "clean": 50, "love": 50}},
         "settings": 17, "somethingFromTheFuture": [1, 2, 3]}
        """
        val state = GameStorage.decode(json) ?: return fail("didn't decode")
        state.sanitize(noon)
        assertTrue(state.hatched)
        assertEquals("Blub", state.slime.name)
        assertEquals(Wallet().coins, state.wallet.coins)
        assertEquals(100.0, state.slime.needs.hunger, 0.0)
        assertEquals(0.0, state.slime.needs.`fun`, 0.0)
    }

    // iOS kept version 1 in UserDefaults; the Kotlin port takes that JSON and the hatched flag directly.
    @Test
    fun testMigratesVersionOne() {
        val old = """
        {"name": "Gloop", "skin": "ocean", "xp": 130, "coins": 77, "isAsleep": false,
         "needs": {"hunger": 10, "fun": 20, "energy": 30, "clean": 40, "love": 50},
         "counters": {"feeds": 4, "plays": 2, "bestScore": 33}}
        """
        val state = GameStorage.migrateV1(old, hatched = true, now = noon) ?: return fail("no migration")
        assertTrue(state.hatched)
        assertEquals("Gloop", state.slime.name)
        assertEquals(Skin.ocean, state.slime.skin)
        assertEquals(130, state.slime.xp)
        assertEquals(77, state.wallet.coins)
        assertEquals(10.0, state.slime.needs.hunger, 0.0)
        assertEquals(4, state.progress.stats.feeds)
        assertEquals(33, state.record(GameID.snackCatcher).best)
        assertTrue(state.player.tips.contains("onboarded"))
    }

    @Test
    fun testNoVersionOneDataMeansNoMigration() {
        assertNull(GameStorage.migrateV1(null, hatched = false, now = noon))
    }
}

// IslandStateTests: the meetup, Slimy 1.0 activity states and phone-event island tests need the iOS
// Live Activity types (SlimeActivityAttributes, FriendSlime), which belong to the Android notification
// worker; only the sprite-data test is ported here.
class IslandStateTests {
    @Test
    fun testMeetFramesSlideAndCarryAccessoryOffsets() {
        TestSprites.ensureLoaded()
        for (stage in Stage.entries) {
            val anchors = SlimeSprites.anchors(stage, SpriteAnim.meet)
            assertEquals(10, SlimeSprites.frames(stage, SpriteAnim.meet).size)
            assertEquals(10, anchors.size)
            assertEquals(5, anchors.first().size)
            assertTrue("$stage never slides", (anchors.maxOfOrNull { it[4] } ?: 0) > 0)
        }
    }
}
