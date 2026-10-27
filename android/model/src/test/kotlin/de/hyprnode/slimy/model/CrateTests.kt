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
import kotlin.math.max

private val berlin = SlimyCalendar.gregorian("Europe/Berlin")

/** 4 May 2026, noon in Berlin: no season, holiday or birthday. */
private val noon: Instant = berlin.date(2026, 5, 4, 12)

private fun hatchedState(): GameState {
    val state = GameState()
    state.hatch(name = "Testi", skin = Skin.lime, seed = 42uL, now = noon)
    state.settings.sleep.auto = false
    return state
}

/** Round sessions that are the same on every run. */
private fun session(i: Int): UUID = UUID.fromString(String.format("00000000-0000-4000-8000-%012X", i))

class CrateRateTests {
    @Test
    fun testDropRatesAddUpToOneRarestLast() {
        for (kind in CrateKind.entries) {
            val rates = Crates.dropRates(kind)
            assertEquals(kind.rawValue, 1.0, rates.sumOf { it.chance }, 1e-9)
            for (row in rates) assertTrue("$kind ${row.rarity}", row.chance > 0)
            val order = rates.map { it.rarity }
            assertEquals(kind.rawValue, order.sorted(), order)
            assertEquals(kind.rawValue, order.size, order.toSet().size)
        }
    }

    @Test
    fun testLegendaryOnlyFromGoldenAndMysteryCrates() {
        fun legendary(kind: CrateKind): Double = Crates.dropRates(kind).firstOrNull { it.rarity == Rarity.legendary }?.chance ?: 0.0
        assertEquals(0.0, legendary(CrateKind.wooden), 0.0)
        assertEquals(0.006, legendary(CrateKind.golden), 1e-12)
        assertEquals(0.02, legendary(CrateKind.mystery), 1e-12)
    }

    @Test
    fun testPricesAndNames() {
        assertEquals(80, CrateKind.wooden.price)
        assertEquals(300, CrateKind.golden.price)
        assertNull(CrateKind.mystery.price)
        for (kind in CrateKind.entries) {
            assertEquals("crate.${kind.rawValue}", kind.sprite)
            assertFalse(kind.title.isEmpty())
            assertTrue(kind.blurb.endsWith("."))
        }
        assertTrue(Rarity.common < Rarity.rare)
        assertTrue(Rarity.epic < Rarity.legendary)
        assertEquals(Rarity.entries.size, Rarity.entries.map { it.tint }.toSet().size)
    }

    @Test
    fun testRollsLandOnTheRatesAndOnlyTheAxolotlIsLegendary() {
        val state = hatchedState()
        val rolls = 50_000
        for (kind in CrateKind.entries) {
            val rng = SplitMix(SplitMix.seed("rates-${kind.rawValue}"))
            val counts = HashMap<Rarity, Int>()
            repeat(rolls) {
                val reward = Crates.roll(kind, state, rng)
                counts[reward.rarity] = (counts[reward.rarity] ?: 0) + 1
                if (reward.rarity == Rarity.legendary) {
                    assertEquals(CrateReward.Kind.skin(Skin.axolotl), reward.kind)
                }
                val k = reward.kind
                if (k is CrateReward.Kind.skin && k.skin.isSecret) {
                    assertEquals(Rarity.legendary, reward.rarity)
                }
            }
            for (row in Crates.dropRates(kind)) {
                val seen = (counts[row.rarity] ?: 0).toDouble() / rolls
                assertEquals("$kind ${row.rarity}", row.chance, seen, max(0.004, row.chance * 0.1))
            }
            if (kind == CrateKind.wooden) assertNull(counts[Rarity.legendary])
        }
    }

    @Test
    fun testTheSameSeedGivesTheSameReward() {
        val state = hatchedState()
        val a = SplitMix(7uL)
        val b = SplitMix(7uL)
        repeat(50) {
            assertEquals(Crates.roll(CrateKind.golden, state, a), Crates.roll(CrateKind.golden, state, b))
        }
        val first = state.deepCopy()
        first.addCrate(CrateKind.mystery)
        val second = first.deepCopy()
        val x = first.openCrate(CrateKind.mystery, noon, seed = 99uL)?.reward
        val y = second.openCrate(CrateKind.mystery, noon, seed = 99uL)?.reward
        assertNotNull(x)
        assertEquals(x, y)
    }

    @Test
    fun testCratesHoldShopItemsAndPlainColoursOnly() {
        for (rarity in listOf(Rarity.common, Rarity.rare, Rarity.epic)) {
            assertFalse(rarity.rawValue, Crates.items(rarity).isEmpty())
            for (item in Crates.items(rarity)) {
                if (item.unlock !is Unlock.level) fail("${item.id} isn't sold by level")
            }
        }
        assertTrue(Crates.items(Rarity.legendary).isEmpty())
        assertFalse(Crates.colours.isEmpty())
        assertFalse(Crates.colours.contains(Skin.axolotl))
        assertFalse(Crates.colours.any { Skin.hatchChoices.contains(it) })
        assertTrue(Crates.foods(Rarity.rare, level = 1).isEmpty())
        assertFalse(Crates.foods(Rarity.common, level = 1).isEmpty())
        for (food in Crates.foods(Rarity.rare, level = 30)) assertTrue(food.unlockLevel <= 30)
    }
}

class CrateRewardTests {
    @Test
    fun testBuyingCostsCoinsAndMysteryCratesAreNotForSale() {
        val state = hatchedState()
        state.wallet.coins = 100
        assertTrue(state.buyCrate(CrateKind.wooden, noon))
        assertEquals(20, state.wallet.coins)
        assertEquals(1, state.crateCount(CrateKind.wooden))
        assertFalse(state.buyCrate(CrateKind.wooden, noon))
        assertFalse(state.buyCrate(CrateKind.golden, noon))
        assertFalse(state.buyCrate(CrateKind.mystery, noon))
        assertEquals(20, state.wallet.coins)
        assertEquals(1, state.cratesWaiting)
        assertEquals(1, state.progress.stats.purchases)
    }

    @Test
    fun testCratesStackUpTo99() {
        val state = hatchedState()
        assertTrue(state.addCrate(CrateKind.wooden, 150))
        assertEquals(Crates.maxStack, state.crateCount(CrateKind.wooden))
        assertFalse(state.addCrate(CrateKind.wooden))
        state.wallet.coins = 10_000
        assertFalse(state.buyCrate(CrateKind.wooden, noon))
        assertEquals(10_000, state.wallet.coins)
    }

    @Test
    fun testOpeningUsesACrateAndRemembersTheFind() {
        val state = hatchedState()
        assertNull(state.openCrate(CrateKind.wooden, noon, seed = 1uL))
        state.addCrate(CrateKind.wooden, 3)
        val opened = requireNotNull(state.openCrate(CrateKind.wooden, noon.addingTimeInterval(60.0), seed = 1uL))
        assertTrue(opened.outcome.ok)
        assertEquals(2, state.crateCount(CrateKind.wooden))
        assertEquals(1, state.progress.stats.cratesOpened)
        assertNotNull(state.progress.achievements["firstCrate"])
        assertTrue(opened.outcome.events.contains(GameEvent.achievement("firstCrate")))
        val entry = requireNotNull(state.memory.last(MemoryEntry.Kind.crate))
        val find = requireNotNull(CrateFind.parse(entry.detail))
        assertEquals(opened.reward.title, find.title)
        assertEquals(opened.reward.rarity, find.rarity)
        assertEquals(CrateKind.wooden, find.crate)
        assertTrue(state.slime.emotions[Emotion.excitement] > 0.25)
        assertNotNull(state.slime.say)

        // A wooden crate can hold another one, so open until none is left.
        state.addCrate(CrateKind.wooden, 97)
        var opens = 0
        while (state.crateCount(CrateKind.wooden) > 0 && opens < 1000) {
            state.openCrate(CrateKind.wooden, noon.addingTimeInterval(120.0 + opens), seed = (opens + 2).toULong())
            opens += 1
        }
        assertTrue(opens >= 99)
        assertEquals(0, state.crateCount(CrateKind.wooden))
        assertNull(state.crates[CrateKind.wooden])
        assertNotNull(state.progress.achievements["crates50"])
    }

    @Test
    fun testEveryKindOfRewardIsHandedOut() {
        val state = hatchedState()
        val coins = state.wallet.coins
        val earned = state.wallet.earnedTotal
        state.receive(CrateReward(CrateReward.Kind.coins(120), Rarity.rare, "120 coins", "coin", null))
        assertEquals(coins + 120, state.wallet.coins)
        assertEquals(earned + 120, state.wallet.earnedTotal)

        state.receive(CrateReward(CrateReward.Kind.food(Food.cookie, 3), Rarity.common, "Cookie × 3", "cookie", null))
        assertEquals(3, state.servings(Food.cookie))

        state.receive(CrateReward(CrateReward.Kind.item("hat.crown"), Rarity.epic, "Crown", "hat.crown", null))
        assertTrue(state.owns("hat.crown"))

        state.receive(CrateReward(CrateReward.Kind.skin(Skin.grape), Rarity.epic, "Grape colour", "gem", null))
        assertTrue(state.slime.isUnlocked(Skin.grape))
        assertEquals(Skin.lime, state.slime.skin)

        state.receive(CrateReward(CrateReward.Kind.crate(CrateKind.golden), Rarity.epic, "Golden crate", "crate.golden", null))
        assertEquals(1, state.crateCount(CrateKind.golden))
    }

    @Test
    fun testPantryServingsAreFreeAndComeFirst() {
        val state = hatchedState()
        state.progress.achievements = state.progress.achievements + ("firstMeal" to noon)
        state.slime.needs.hunger = 20.0
        state.inventory.pantry = state.inventory.pantry + (Food.cake to 2)
        val coins = state.wallet.coins
        // Cake opens at level 7 in the kitchen, but a serving can always be eaten.
        assertTrue(state.feed(Food.cake, noon.addingTimeInterval(60.0)).ok)
        assertEquals(coins, state.wallet.coins)
        assertEquals(1, state.servings(Food.cake))
        assertTrue(state.feed(Food.cake, noon.addingTimeInterval(120.0)).ok)
        assertEquals(0, state.servings(Food.cake))
        assertNull(state.inventory.pantry[Food.cake])
        state.slime.needs.hunger = 20.0
        assertFalse(state.feed(Food.cake, noon.addingTimeInterval(180.0)).ok)
    }

    @Test
    fun testDuplicatesTurnIntoCoins() {
        val state = hatchedState()
        for (item in Crates.items(Rarity.common)) state.inventory.owned = state.inventory.owned + item.id
        val rng = SplitMix(3uL)
        var items = 0
        repeat(300) {
            val reward = Crates.reward(CrateKind.wooden, Rarity.common, state, rng)
            val kind = reward.kind
            if (kind is CrateReward.Kind.item) {
                items += 1
                val price = Catalog.item(kind.id)?.price ?: 0
                assertEquals(kind.id, Crates.duplicateCoins(price, Rarity.common), reward.convertedCoins)
                assertTrue(reward.isDuplicate)
            } else {
                assertNull(reward.convertedCoins)
            }
        }
        assertTrue(items > 0)

        state.slime.colours = state.slime.colours + Skin.grape
        var colours = 0
        repeat(300) {
            val reward = Crates.reward(CrateKind.golden, Rarity.epic, state, rng)
            val kind = reward.kind as? CrateReward.Kind.skin ?: return@repeat
            colours += 1
            assertEquals(if (state.slime.isUnlocked(kind.skin)) Crates.duplicateColourCoins else null, reward.convertedCoins)
        }
        assertTrue(colours > 0)

        val coins = state.wallet.coins
        val twin = CrateReward(CrateReward.Kind.item("hat.party"), Rarity.common, "Party hat", "hat.party", 15)
        state.receive(twin)
        assertEquals(coins + 15, state.wallet.coins)
        assertTrue(twin.detail.contains("15 coins"))
        assertNull(twin.bonusCrate)
    }

    @Test
    fun testTheAxolotlTransformsTheSlimeOnceThenPaysAndBringsACrate() {
        val state = hatchedState()
        val first = Crates.legendary(state)
        assertEquals(CrateReward.Kind.skin(Skin.axolotl), first.kind)
        assertEquals(Rarity.legendary, first.rarity)
        assertNull(first.convertedCoins)
        assertNull(first.bonusCrate)
        state.receive(first)
        assertEquals(Skin.axolotl, state.slime.skin)
        assertTrue(state.slime.isUnlocked(Skin.axolotl))

        val again = Crates.legendary(state)
        assertEquals(500, again.convertedCoins)
        assertEquals(CrateKind.golden, again.bonusCrate)
        assertTrue(again.detail.contains("golden crate"))
        val coins = state.wallet.coins
        val golden = state.crateCount(CrateKind.golden)
        state.receive(again)
        assertEquals(coins + 500, state.wallet.coins)
        assertEquals(golden + 1, state.crateCount(CrateKind.golden))
    }

    @Test
    fun testFindingTheLegendaryInACrate() {
        val state = hatchedState()
        state.addCrate(CrateKind.mystery)
        val seed = requireNotNull((1uL..20_000uL).firstOrNull { seed ->
            val rng = SplitMix(seed)
            Crates.rarity(CrateKind.mystery, rng.unit()) == Rarity.legendary
        })
        val opened = requireNotNull(state.openCrate(CrateKind.mystery, noon.addingTimeInterval(60.0), seed = seed))
        assertEquals(Rarity.legendary, opened.reward.rarity)
        assertEquals(Skin.axolotl, state.slime.skin)
        assertNotNull(state.progress.achievements["legendary"])
        assertNotNull(state.progress.achievements["epicFind"])
        assertEquals(1.0, state.slime.emotions[Emotion.excitement], 0.001)
        assertTrue(state.memory.last(MemoryEntry.Kind.crate)?.keepsake ?: false)
        assertEquals("Legendary find: ${Skin.axolotl.title}", state.memory.last(MemoryEntry.Kind.crate)?.summary)
        assertTrue(state.memory.recentLines.any { it.startsWith("crateLegendary.") })
    }

    @Test
    fun testWearingTheSecretColourDoesNotEarnTheSecretAchievement() {
        val state = hatchedState()
        // A shared slime can arrive wearing it: the secret is for finding it yourself.
        state.slime.colours = state.slime.colours + Skin.axolotl
        state.slime.skin = Skin.axolotl
        state.checkAchievements(noon)
        assertNull(state.progress.achievements["legendary"])
    }

    // testRewardsSurviveCoding: CrateReward is never saved or sent (neither on iOS nor here), so the
    // Kotlin type has no JSON form; its value equality is covered by the roll tests above.

    @Test
    fun testCrateMemoriesReadBack() {
        val find = CrateFind(crate = CrateKind.golden, rarity = Rarity.epic, icon = "hat.crown", title = "Crown")
        assertEquals(find, CrateFind.parse(find.detail))
        assertNull(CrateFind.parse("nonsense"))
        assertNull(CrateFind.parse("plastic|epic|x|Crown"))
        val entry = MemoryEntry(kind = MemoryEntry.Kind.crate, at = noon, detail = find.detail, keepsake = true)
        assertEquals("Golden crate: Crown", entry.summary)
        assertEquals("hat.crown", entry.icon)
    }
}

class CrateSourceTests {
    @Test
    fun testEveryFifthLevelBringsAMysteryCrateOnce() {
        val state = hatchedState()
        state.slime.xp = Slime.xpNeeded(5) - 1
        val line = StringRef("")
        val events = state.gainXP(1, noon, line)
        assertTrue(events.contains(GameEvent.levelUp(5)))
        assertTrue(events.contains(GameEvent.crate(CrateKind.mystery, CrateSource.levelUp)))
        assertEquals(1, state.crateCount(CrateKind.mystery))

        val six = state.gainXP(Slime.xpNeeded(6) - state.slime.xp, noon, line)
        assertTrue(six.contains(GameEvent.levelUp(6)))
        assertFalse(six.contains(GameEvent.crate(CrateKind.mystery, CrateSource.levelUp)))

        // Level 5 again (a shared slime's xp changed) pays nothing twice.
        state.slime.xp = Slime.xpNeeded(5) - 1
        state.gainXP(1, noon, line)
        assertEquals(1, state.crateCount(CrateKind.mystery))

        val ten = state.gainXP(Slime.xpNeeded(10) - state.slime.xp, noon, line)
        assertTrue(ten.contains(GameEvent.crate(CrateKind.mystery, CrateSource.levelUp)))
        assertEquals(2, state.crateCount(CrateKind.mystery))
    }

    @Test
    fun testGreatRoundsSometimesBringACrateDecidedByTheSession() {
        val sessions = (0 until 5000).map { session(it) }
        val lucky = sessions.filter { Crates.dropsAfterRound(it) }
        // About 4 in 100.
        assertTrue("${lucky.size}", lucky.size > 120)
        assertTrue("${lucky.size}", lucky.size < 290)

        val win = lucky.first()
        val dry = sessions.first { !Crates.dropsAfterRound(it) }
        val state = hatchedState()
        val great = GameResult(game = GameID.bubblePop, score = 500, session = win, finishedAt = noon.addingTimeInterval(60.0))
        val out = state.finish(great)
        assertTrue(out.events.contains(GameEvent.crate(CrateKind.mystery, CrateSource.greatRound)))
        assertEquals(1, state.crateCount(CrateKind.mystery))
        // The same round again changes nothing.
        assertFalse(state.finish(great).ok)
        assertEquals(1, state.crateCount(CrateKind.mystery))

        val other = hatchedState()
        // A lucky session but not a great round: nothing.
        other.finish(GameResult(game = GameID.bubblePop, score = 100, session = win, finishedAt = noon.addingTimeInterval(60.0)))
        assertEquals(0, other.crateCount(CrateKind.mystery))
        // A great round whose session doesn't drop: nothing.
        other.finish(GameResult(game = GameID.bubblePop, score = 500, session = dry, finishedAt = noon.addingTimeInterval(120.0)))
        assertEquals(0, other.crateCount(CrateKind.mystery))
    }

    @Test
    fun testSomeAchievementsBringACrate() {
        val state = hatchedState()
        state.progress.stats.goodGames = 25
        val events = state.checkAchievements(noon)
        assertTrue(events.contains(GameEvent.achievement("arcadeStar")))
        assertEquals(CrateKind.mystery, Achievements.achievement("arcadeStar")?.crate)
        assertEquals(1, state.crateCount(CrateKind.mystery))
        // Only once.
        state.checkAchievements(noon)
        assertEquals(1, state.crateCount(CrateKind.mystery))
    }

    @Test
    fun testCrateSourcesExplainThemselves() {
        for (source in CrateSource.entries) assertTrue(source.rawValue, source.reason.endsWith("."))
    }
}
