package de.hyprnode.slimy.ui

import de.hyprnode.slimy.model.Catalog
import de.hyprnode.slimy.model.ChallengeTemplate
import de.hyprnode.slimy.model.Food
import de.hyprnode.slimy.model.FurnitureSlot
import de.hyprnode.slimy.model.GameID
import de.hyprnode.slimy.model.GameState
import de.hyprnode.slimy.model.Habitat
import de.hyprnode.slimy.model.ItemKind
import de.hyprnode.slimy.model.MemoryEntry
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.StatKey
import de.hyprnode.slimy.model.Unlock
import de.hyprnode.slimy.model.hatch
import de.hyprnode.slimy.ui.kit.AppButtonKind
import de.hyprnode.slimy.ui.screens.journal.JournalText
import de.hyprnode.slimy.ui.screens.kitchen.KitchenText
import de.hyprnode.slimy.ui.screens.shop.ShopPick
import de.hyprnode.slimy.ui.screens.shop.ShopPreview
import de.hyprnode.slimy.ui.screens.shop.ShopSeen
import de.hyprnode.slimy.ui.screens.shop.ShopTab
import de.hyprnode.slimy.ui.screens.shop.ShopText
import de.hyprnode.slimy.ui.screens.shop.TileStatus
import de.hyprnode.slimy.ui.screens.shop.UnlockText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ShopKitchenJournalTest {
    private val now: Instant = Instant.parse("2026-10-25T12:00:00Z")

    private fun hatched(): GameState = GameState().apply { hatch("Testi", Skin.lime, 42uL, now) }

    // MARK: Shop

    @Test
    fun tabsStartWithCrates() {
        val tabs = ShopTab.all
        assertEquals(ShopTab.crates, tabs.first().first)
        assertEquals("Crates", tabs.first().second)
        assertEquals(listOf("Crates", "Hats", "Glasses", "Scarves", "Effects", "Furniture", "Places", "Colours"), tabs.map { it.second })
    }

    @Test
    fun theSecretColourIsNeverShownLocked() {
        val s = hatched()
        assertFalse(ShopText.shownSkins(s).contains(Skin.axolotl))
        s.slime.colours = s.slime.colours + Skin.axolotl
        assertTrue(ShopText.shownSkins(s).contains(Skin.axolotl))
    }

    @Test
    fun itemModelFollowsWhatYouHave() {
        val s = hatched()
        s.wallet.coins = 30
        val hat = Catalog.item("hat.party")!!
        val buy = ShopText.itemModel(hat, s)
        assertEquals("Buy", buy.buttonTitle)
        assertEquals("coin", buy.buttonIcon)
        assertEquals("25 coins", buy.detail)
        assertTrue(buy.enabled)
        assertNotNull(buy.quickAction)

        s.wallet.coins = 20
        val short = ShopText.itemModel(hat, s)
        assertEquals("You need 5 more coins.", short.detail)
        assertTrue(short.warning)
        assertFalse(short.enabled)
        assertNull(short.quickAction)

        s.inventory.owned = setOf("hat.party")
        assertEquals("Wear", ShopText.itemModel(hat, s).buttonTitle)
        assertEquals("Owned", ShopText.itemModel(hat, s).detail)

        s.slime.equipped.hat = "hat.party"
        val worn = ShopText.itemModel(hat, s)
        assertEquals("Take off", worn.buttonTitle)
        assertEquals("Wearing it now", worn.detail)
        assertEquals(AppButtonKind.quiet, worn.buttonKind)

        val crown = ShopText.itemModel(Catalog.item("hat.crown")!!, s)
        assertEquals("Locked", crown.buttonTitle)
        assertTrue(crown.buttonLocked)
        assertEquals("Unlocks at level 8.", crown.detail)
        assertNull(crown.quickAction)
    }

    @Test
    fun furnitureSaysWhatItReplaces() {
        val s = hatched()
        s.inventory.owned = setOf("furn.plant", "furn.lamp")
        s.inventory.furniture = mapOf(FurnitureSlot.floorRight to "furn.plant")
        val lamp = ShopText.itemModel(Catalog.item("furn.lamp")!!, s)
        assertEquals("Place", lamp.buttonTitle)
        assertEquals("Floor right · replaces potted plant", lamp.detail)
        val plant = ShopText.itemModel(Catalog.item("furn.plant")!!, s)
        assertEquals("Remove", plant.buttonTitle)
        assertEquals("In the room · Floor right", plant.detail)
    }

    @Test
    fun placesAndColours() {
        val s = hatched()
        assertEquals("Testi lives here", ShopText.habitatModel(Habitat.room, s).detail)
        val garden = ShopText.habitatModel(Habitat.garden, s)
        assertEquals("Locked", garden.buttonTitle)
        assertEquals("Unlocks at level 5.", garden.detail)
        val cave = ShopText.habitatModel(Habitat.crystal, s)
        assertTrue(cave.detail.startsWith("Unlocks with the achievement"))

        assertEquals("Testi's colour right now", ShopText.skinModel(Skin.lime, s).detail)
        assertEquals("???", ShopText.skinModel(Skin.axolotl, s).title)
        assertEquals("Only found in crates.", ShopText.skinModel(Skin.axolotl, s).detail)
        assertEquals("Unlocks at level 8.", ShopText.skinModel(Skin.gold, s).detail)
        assertEquals("Use colour", ShopText.skinModel(Skin.ocean, s).buttonTitle)
    }

    @Test
    fun tileAccessibilityReadsLikeTheIPhone() {
        assertEquals("Crown, rare, 150 coins, not enough coins", ShopText.accessibility("Crown", true, TileStatus.price(150, false)))
        assertEquals("Rug, owned", ShopText.accessibility("Rug", false, TileStatus.owned))
        assertEquals("Room, home", ShopText.accessibility("Room", false, TileStatus.active("Home")))
        assertEquals("Garden, locked, Level 5", ShopText.accessibility("Garden", false, TileStatus.locked("Level 5")))
    }

    @Test
    fun shopNotes() {
        assertEquals("Furniture goes in the room, one piece per spot.", ShopText.note(ShopTab.kind(ItemKind.furniture), "Testi"))
        assertEquals("Effects float around Testi wherever it goes.", ShopText.note(ShopTab.kind(ItemKind.effect), "Testi"))
        assertNull(ShopText.note(ShopTab.kind(ItemKind.hat), "Testi"))
        assertEquals("New colours unlock as Testi levels up. They're free.", ShopText.note(ShopTab.colours, "Testi"))
    }

    @Test
    fun tryingOnLeavesTheRealSlimeAlone() {
        val s = hatched()
        val preview = ShopPreview.slime(s, ShopPick.item("hat.crown"))
        assertEquals("hat.crown", preview.equipped.hat)
        assertNull(s.slime.equipped.hat)
        assertEquals(Skin.grape, ShopPreview.slime(s, ShopPick.skin(Skin.grape)).skin)
        assertEquals(Skin.lime, s.slime.skin)
        assertEquals("furn.bed", ShopPreview.furniture(s, ShopPick.item("furn.bed"))[FurnitureSlot.floorLeft])
        assertTrue(s.inventory.furniture.isEmpty())
        assertEquals(Habitat.room, ShopPreview.habitat(s, null, ShopTab.kind(ItemKind.furniture)))
        assertEquals(Habitat.beach, ShopPreview.habitat(s, ShopPick.habitat(Habitat.beach), ShopTab.places))
    }

    @Test
    fun newTagsStartOnTheSecondVisit() {
        val (first, stored) = ShopSeen.update("", setOf("hat.party", "furn.bed"))
        assertTrue(first.isEmpty())
        assertEquals("v1,furn.bed,hat.party", stored)
        val (second, again) = ShopSeen.update(stored, setOf("hat.party", "furn.bed", "hat.beanie"))
        assertEquals(setOf("hat.beanie"), second)
        assertEquals("v1,furn.bed,hat.beanie,hat.party", again)
    }

    @Test
    fun forSaleListsAvailableThingsNotOwned() {
        val s = hatched()
        val keys = ShopSeen.forSale(s)
        assertTrue(keys.contains("hat.party"))
        assertFalse(keys.contains("hat.crown"))
        assertTrue(keys.contains("skin.lime"))
        assertFalse(keys.contains("habitat.room"))
    }

    @Test
    fun unlockWords() {
        assertEquals("Level 3", UnlockText.short(Unlock.level(3)))
        assertEquals("Bond 50", UnlockText.short(Unlock.bond(50)))
        assertEquals("October", UnlockText.short(Unlock.season(10)))
        assertEquals("Only for sale in December.", UnlockText.long(Unlock.season(12)))
        assertEquals("Unlocks with the achievement “Water baby”.", UnlockText.long(Unlock.achievement("bather")))
        assertEquals("Unlocks at a bond of 50.", UnlockText.long(Unlock.bond(50)))
    }

    // MARK: Kitchen

    @Test
    fun foodTilesReadEverything() {
        assertEquals(
            "Cookie, locked, unlocks at level 2, fills 26",
            KitchenText.accessibility(Food.cookie, locked = true, affordable = false, tasted = false, favorite = false, servings = 0),
        )
        assertEquals(
            "Strawberry, favourite, 1 free serving, not tried yet, fills 22",
            KitchenText.accessibility(Food.berry, locked = false, affordable = true, tasted = false, favorite = true, servings = 1),
        )
        assertEquals(
            "Apple, free, fills 16",
            KitchenText.accessibility(Food.apple, locked = false, affordable = true, tasted = true, favorite = false, servings = 0),
        )
        assertEquals(
            "Cake, 14 coins, not enough coins, fills 45",
            KitchenText.accessibility(Food.cake, locked = false, affordable = false, tasted = true, favorite = false, servings = 0),
        )
        assertEquals("Every food tried. A true gourmet.", KitchenText.footerHint(10, 10, "Testi"))
        assertTrue(KitchenText.footerHint(3, 10, "Testi").endsWith("as Testi levels up."))
    }

    // MARK: Journal

    @Test
    fun bondStagesCountUp() {
        assertEquals("Next: Familiar" to 20, JournalText.nextBond(0.0))
        assertEquals("Next: Close" to 50, JournalText.nextBond(20.0))
        assertEquals("Next: Inseparable" to 80, JournalText.nextBond(79.9))
        assertNull(JournalText.nextBond(80.0))
        assertEquals("1 day", JournalText.days(1))
        assertEquals("12 days", JournalText.days(12))
    }

    @Test
    fun challengeIconsComeFromTheGoal() {
        assertEquals("coin", JournalText.challengeIcon(null))
        val stat = ChallengeTemplate("t", "Feed it", ChallengeTemplate.Goal.stat(StatKey.feeds, 3), 10)
        assertEquals("apple", JournalText.challengeIcon(stat))
        val score = ChallengeTemplate("s", "Score", ChallengeTemplate.Goal.score(GameID.snackCatcher, 30), 10)
        assertEquals(GameID.snackCatcher.icon, JournalText.challengeIcon(score))
    }

    @Test
    fun memoriesGroupByMonthInOrder() {
        val utc = ZoneId.of("UTC")
        val entries = listOf(
            MemoryEntry(kind = MemoryEntry.Kind.levelUp, at = Instant.parse("2026-10-20T10:00:00Z"), detail = "3", keepsake = true),
            MemoryEntry(kind = MemoryEntry.Kind.levelUp, at = Instant.parse("2026-10-02T10:00:00Z"), detail = "2", keepsake = true),
            MemoryEntry(kind = MemoryEntry.Kind.hatched, at = Instant.parse("2026-09-30T10:00:00Z"), detail = "Testi", keepsake = true),
        )
        val months = JournalText.months(entries, utc)
        assertEquals(2, months.size)
        assertEquals(2, months[0].second.size)
        assertEquals("September 2026", JournalText.monthTitle(months[1].first, java.util.Locale.US))
    }

    @Test
    fun achievementRewardsMentionTheirCrate() {
        val withCrate = de.hyprnode.slimy.model.Achievements.all.firstOrNull { it.crate != null }
        if (withCrate != null) {
            assertTrue(JournalText.achievementReward(withCrate).contains(" and a ${withCrate.crate!!.title.lowercase()}"))
        }
        val plain = de.hyprnode.slimy.model.Achievements.all.first { it.crate == null }
        assertEquals("${plain.coins} coins", JournalText.achievementReward(plain))
    }
}
