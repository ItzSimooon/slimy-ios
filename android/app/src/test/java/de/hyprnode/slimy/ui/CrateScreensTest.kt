package de.hyprnode.slimy.ui

import de.hyprnode.slimy.model.CrateKind
import de.hyprnode.slimy.model.CrateReward
import de.hyprnode.slimy.model.Food
import de.hyprnode.slimy.model.Rarity
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.ui.screens.crates.CrateCopy
import de.hyprnode.slimy.ui.screens.crates.CrateOpening
import de.hyprnode.slimy.ui.screens.crates.CrateRewards
import de.hyprnode.slimy.ui.screens.crates.DropRates
import de.hyprnode.slimy.ui.screens.crates.OpenPhase
import de.hyprnode.slimy.ui.screens.crates.SparkMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class CrateScreensTest {
    private fun coins(amount: Int) = CrateReward(CrateReward.Kind.coins(amount), Rarity.common, "$amount coins", "coin", null)

    @Test
    fun crateNamesSayCrateOnce() {
        assertEquals("Wooden crate", CrateCopy.name(CrateKind.wooden))
        assertEquals("Mystery crate", CrateCopy.name(CrateKind.mystery))
        assertEquals("1 coin", CrateCopy.coins(1))
        assertEquals("80 coins", CrateCopy.coins(80))
        assertEquals("You need 1 more coin.", CrateCopy.needMore(1))
        assertEquals("You need 12 more coins.", CrateCopy.needMore(12))
    }

    @Test
    fun crateCopyNeverMentionsRealMoneyForSale() {
        assertTrue(CrateCopy.noRealMoney.contains("never sold for real money"))
    }

    @Test
    fun woodenRatesHaveNoLegendary() {
        val rows = DropRates.rows(CrateKind.wooden, Locale.US)
        assertEquals(listOf(Rarity.common, Rarity.rare, Rarity.epic), rows.map { it.rarity })
        assertEquals(listOf("72%", "24%", "4%"), rows.map { it.text })
    }

    @Test
    fun legendaryOddsStayASecret() {
        for (kind in listOf(CrateKind.golden, CrateKind.mystery)) {
            val rows = DropRates.rows(kind, Locale.US)
            val legendary = rows.last()
            assertEquals(Rarity.legendary, legendary.rarity)
            assertEquals("???", legendary.text)
            assertEquals("Secret", legendary.spoken)
        }
        assertEquals(listOf("35%", "44.4%", "20%", "???"), DropRates.rows(CrateKind.golden, Locale.US).map { it.text })
    }

    @Test
    fun dropRateFootersNamePriceOrMystery() {
        assertEquals("80 coins in the shop, or as a reward in the game.", DropRates.footer(CrateKind.wooden))
        assertEquals(CrateCopy.mysteryHint, DropRates.footer(CrateKind.mystery))
    }

    @Test
    fun tapHintCountsDown() {
        assertEquals("Tap the crate to crack it open.", CrateOpening.tapHint(OpenPhase.closed, 0, 4))
        assertEquals("3 more taps.", CrateOpening.tapHint(OpenPhase.closed, 1, 4))
        assertEquals("One more tap!", CrateOpening.tapHint(OpenPhase.closed, 3, 4))
        assertEquals("Here it comes!", CrateOpening.tapHint(OpenPhase.cracking, 4, 4))
    }

    @Test
    fun extraCratesAreWhatTheRewardDoesNotExplain() {
        // An achievement finished by opening brought a mystery crate along.
        val extras = CrateOpening.extraCrates(
            before = mapOf(CrateKind.wooden to 2),
            after = mapOf(CrateKind.wooden to 1, CrateKind.mystery to 1),
            opened = CrateKind.wooden,
            reward = coins(40),
        )
        assertEquals(listOf(CrateKind.mystery), extras)

        // A crate inside the crate is the reward itself, not an extra.
        val won = CrateReward(CrateReward.Kind.crate(CrateKind.golden), Rarity.epic, "Golden crate", "crate.golden", null)
        assertEquals(
            emptyList<CrateKind>(),
            CrateOpening.extraCrates(mapOf(CrateKind.wooden to 1), mapOf(CrateKind.golden to 1), CrateKind.wooden, won),
        )

        // The secret colour found again brings coins and a bonus golden crate.
        val again = CrateReward(CrateReward.Kind.skin(Skin.axolotl), Rarity.legendary, "Axolotl", "axolotl", 500)
        assertEquals(CrateKind.golden, again.bonusCrate)
        assertEquals(
            emptyList<CrateKind>(),
            CrateOpening.extraCrates(mapOf(CrateKind.mystery to 1), mapOf(CrateKind.golden to 1), CrateKind.mystery, again),
        )
    }

    @Test
    fun extrasTextNamesEachKindOnce() {
        assertNull(CrateOpening.extrasText(emptyList()))
        assertEquals(
            "You also got a wooden crate and 2 mystery crates.",
            CrateOpening.extrasText(listOf(CrateKind.mystery, CrateKind.wooden, CrateKind.mystery)),
        )
    }

    @Test
    fun nextCratePrefersTheSameKindThenWhatCameOut() {
        val won = CrateReward(CrateReward.Kind.crate(CrateKind.golden), Rarity.epic, "Golden crate", "crate.golden", null)
        val counts = mutableMapOf(CrateKind.wooden to 1, CrateKind.golden to 1)
        assertEquals(CrateKind.wooden, CrateOpening.nextCrate(CrateKind.wooden, won, emptyList()) { counts[it] ?: 0 })
        counts[CrateKind.wooden] = 0
        assertEquals(CrateKind.golden, CrateOpening.nextCrate(CrateKind.wooden, won, emptyList()) { counts[it] ?: 0 })
        counts[CrateKind.golden] = 0
        assertNull(CrateOpening.nextCrate(CrateKind.wooden, won, emptyList()) { counts[it] ?: 0 })
    }

    @Test
    fun rewardWordsSayWhereToFindIt() {
        assertEquals("Added to your coins.", CrateOpening.detail(coins(10)))
        val food = CrateReward(CrateReward.Kind.food(Food.cookie, 3), Rarity.common, "Cookie × 3", "cookie", null)
        assertEquals("Free servings, waiting in the kitchen.", CrateOpening.detail(food))
        val crown = CrateReward(CrateReward.Kind.item("hat.crown"), Rarity.epic, "Crown", "hat.crown", null)
        assertEquals("Something new to wear. Try it on in the shop.", CrateOpening.detail(crown))
        val bed = CrateReward(CrateReward.Kind.item("furn.bed"), Rarity.rare, "Little bed", "furn.bed", null)
        assertEquals("New furniture. Place it in the room from the shop.", CrateOpening.detail(bed))
        val owned = CrateReward(CrateReward.Kind.item("furn.bed"), Rarity.rare, "Little bed", "furn.bed", 30)
        assertNull(CrateOpening.detail(owned))
        assertEquals("Already yours, so you get 30 coins instead.", CrateOpening.convertedText(owned))
        assertEquals("Rare: Little bed. Already yours, so you get 30 coins instead.", CrateOpening.spoken(owned, emptyList()))
    }

    @Test
    fun legendaryAlwaysOffersTheAxolotlOnceUnlocked() {
        val legendary = CrateReward(CrateReward.Kind.skin(Skin.axolotl), Rarity.legendary, "Axolotl", "axolotl", null)
        assertEquals(Skin.axolotl, CrateRewards.shownSkin(legendary))
        assertEquals(Skin.axolotl, CrateOpening.offeredSkin(legendary) { true })
        assertNull(CrateOpening.offeredSkin(legendary) { false })
        assertNull(CrateOpening.offeredSkin(coins(5)) { true })
        assertNull(CrateRewards.shownSkin(coins(5)))
    }

    @Test
    fun rewardIconFallsBackToWhatItStandsFor() {
        val food = CrateReward(CrateReward.Kind.food(Food.cookie, 3), Rarity.common, "Cookie × 3", "pantry.unknown", null)
        assertEquals("cookie", CrateRewards.spriteName(food) { it == "cookie" })
        assertEquals("coin", CrateRewards.spriteName(coins(5)) { it == "coin" })
        assertEquals("coin", CrateRewards.spriteName(food) { false })
        val bubbles = CrateReward(CrateReward.Kind.item("effect.bubbles"), Rarity.rare, "Soap bubbles", "effect.bubbles", null)
        assertEquals("effect.bubbles", CrateRewards.effectID(bubbles))
        assertNull(CrateRewards.effectID(food))
    }

    @Test
    fun wobbleRestsBetweenJiggles() {
        assertEquals(0.0, CrateOpening.wobble(1.0, OpenPhase.closed), 0.0)
        assertEquals(0.0, CrateOpening.wobble(2.3, OpenPhase.closed), 0.0)
        assertTrue(kotlin.math.abs(CrateOpening.wobble(0.05, OpenPhase.closed)) > 0)
        assertTrue(kotlin.math.abs(CrateOpening.wobble(1.0, OpenPhase.charging)) <= 7.0)
    }

    @Test
    fun sparksAreSteady() {
        val a = SparkMath.unit(42, 3, 1)
        assertEquals(a, SparkMath.unit(42, 3, 1), 0.0)
        assertFalse(a == SparkMath.unit(42, 4, 1))
        for (i in 0 until 200) {
            val v = SparkMath.unit(7, i, 2)
            assertTrue(v >= 0.0 && v < 1.0)
        }
    }
}
