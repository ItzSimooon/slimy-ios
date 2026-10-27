package de.hyprnode.slimy.platform.island

import de.hyprnode.slimy.model.CareKind
import de.hyprnode.slimy.model.CareLink
import de.hyprnode.slimy.model.CrateKind
import de.hyprnode.slimy.model.EmoteReel
import de.hyprnode.slimy.model.GameState
import de.hyprnode.slimy.model.MemoryEntry
import de.hyprnode.slimy.model.Mood
import de.hyprnode.slimy.model.Need
import de.hyprnode.slimy.model.PartnerCareNote
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.Slime
import de.hyprnode.slimy.model.SlimyCalendar
import de.hyprnode.slimy.model.Stage
import de.hyprnode.slimy.model.hatch
import de.hyprnode.slimy.platform.FriendSlime
import de.hyprnode.slimy.platform.SharedMoment
import de.hyprnode.slimy.ui.screens.settings.IslandCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the island shows for a game: badge, caption and news, like the Live Activity plus the island's own extras. */
class IslandSceneTest {
    private val berlin = SlimyCalendar.gregorian("Europe/Berlin")
    private val noon = berlin.date(2026, 10, 25, 12)

    private fun hatched(): GameState {
        val state = GameState()
        state.hatch("Testi", Skin.lime, 42uL, noon)
        return state
    }

    private val pip = FriendSlime("f1", "Pip", "Lina", Skin.bubblegum, Stage.young, Mood.idle)

    @Test
    fun soloShowsTheEmote() {
        val game = hatched()
        game.slime.needs.hunger = 20.0
        val s = IslandScene.of(game, noon)
        assertEquals(IslandBadge.Emote(EmoteReel.hunger), s.badge)
        assertEquals("Testi", s.name)
        assertEquals("Testi: ${s.line}", s.spoken)
        assertNull(s.partner)
        assertNull(s.news)
    }

    @Test
    fun asleep() {
        val game = hatched()
        game.slime.isAsleep = true
        val s = IslandScene.of(game, noon)
        assertTrue(s.isAsleep)
        assertEquals(IslandBadge.Emote(EmoteReel.sleepy), s.badge)
        assertEquals("Wake", s.state.actionTitle(Need.energy))
        assertEquals("zzz", s.line)
    }

    @Test
    fun aSharedMomentShowsTheFriend() {
        val game = hatched()
        val s = IslandScene.of(game, noon, listOf(pip), SharedMoment.hug, "f1")
        assertEquals(IslandBadge.Friend(pip), s.badge)
        assertEquals(pip, s.partner)
        assertEquals("Pip: hug!", s.line)
        assertEquals("With Lina's slime", s.caption)
    }

    @Test
    fun aCrateArriving() {
        val game = hatched()
        val news = IslandNews.Crate(CrateKind.mystery, noon)
        val s = IslandScene.of(game, noon.plusSeconds(2), news = news)
        assertEquals(IslandBadge.Item("crate.mystery"), s.badge)
        assertEquals("A mystery crate arrived", s.caption)
        // After the reaction window it's gone, like a reaction.
        val later = IslandScene.of(game, noon.plusSeconds(Slime.reactionWindow.toLong() + 1), news = news)
        assertNull(later.news)
        assertTrue(later.badge is IslandBadge.Emote)
    }

    @Test
    fun partnerCare() {
        val game = hatched()
        game.care = CareLink(partnerID = "p", partnerName = "Lina", role = CareLink.host, version = 1)
        val s = IslandScene.of(game, noon)
        assertEquals("Lina", s.careWith)
        assertEquals("Caring together with Lina", s.caption)
        val cared = IslandScene.of(game, noon, news = IslandNews.PartnerCare("Lina fed Testi", noon))
        assertEquals("Lina fed Testi", cared.caption)
        assertEquals(IslandBadge.Emote(EmoteReel.love), cared.badge)
    }

    @Test
    fun captionFallsBackToFriendsThenCrates() {
        val game = hatched()
        assertNull(IslandScene.of(game, noon).caption)
        game.crates = mapOf(CrateKind.wooden to 2)
        assertEquals("2 crates to open", IslandScene.of(game, noon).caption)
        assertEquals(2, IslandScene.of(game, noon).cratesWaiting)
        assertEquals("1 friend nearby", IslandScene.of(game, noon, listOf(pip)).caption)
    }

    @Test
    fun trackerNoticesNewCrates() {
        val tracker = IslandNewsTracker()
        val game = hatched()
        game.crates = mapOf(CrateKind.wooden to 1)
        // The first state is only the baseline.
        assertNull(tracker.update(game, noon))
        val more = hatched().also { it.crates = mapOf(CrateKind.wooden to 1, CrateKind.golden to 1) }
        val news = tracker.update(more, noon.plusSeconds(1))
        assertEquals(IslandNews.Crate(CrateKind.golden, noon.plusSeconds(1)), news)
        // The same state again: no new news, and the old one fades after the window.
        assertEquals(news, tracker.update(more, noon.plusSeconds(3)))
        assertNull(tracker.update(more, noon.plusSeconds(20)))
        // Opening a crate (fewer) isn't news.
        val fewer = hatched().also { it.crates = mapOf(CrateKind.golden to 1) }
        assertNull(tracker.update(fewer, noon.plusSeconds(30)))
    }

    @Test
    fun trackerNoticesPartnerCare() {
        val tracker = IslandNewsTracker()
        val game = hatched()
        game.care = CareLink(partnerID = "p", partnerName = "Lina", role = CareLink.host, version = 1)
        assertNull(tracker.update(game, noon))
        game.careBook.seen = listOf("e1")
        game.memory.add(MemoryEntry(kind = MemoryEntry.Kind.partnerCare, at = noon, detail = PartnerCareNote("Lina", CareKind.feed, "Testi").detail))
        assertEquals(IslandNews.PartnerCare("Lina fed Testi", noon.plusSeconds(1)), tracker.update(game, noon.plusSeconds(1)))
    }

    @Test
    fun settingsCopy() {
        assertEquals("Centered", IslandCopy.horizontal(0.2f))
        assertEquals("6 dp right", IslandCopy.horizontal(6f))
        assertEquals("4 dp left", IslandCopy.horizontal(-4f))
        assertEquals("3 dp lower", IslandCopy.vertical(3f))
        assertEquals("2 dp higher", IslandCopy.vertical(-2f))
        assertEquals("115 %", IslandCopy.size(1.15f))
        assertEquals("Not allowed yet", IslandCopy.status(supported = true, allowed = false, show = true, running = false))
        assertEquals("Off", IslandCopy.status(supported = true, allowed = true, show = false, running = false))
        assertEquals("On", IslandCopy.status(supported = true, allowed = true, show = true, running = true))
        assertEquals("Not available", IslandCopy.status(supported = false, allowed = false, show = true, running = false))
        // The island is never called by Apple's name.
        for (text in listOf(IslandCopy.settingsFooter(true), IslandCopy.settingsFooter(false), IslandCopy.unsupported, IslandCopy.positionHint)) {
            assertTrue(!text.contains("Dynamic"))
        }
    }
}
