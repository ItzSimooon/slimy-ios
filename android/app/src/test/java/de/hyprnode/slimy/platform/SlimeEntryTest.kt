package de.hyprnode.slimy.platform

import de.hyprnode.slimy.model.EmoteReel
import de.hyprnode.slimy.model.GameState
import de.hyprnode.slimy.model.Need
import de.hyprnode.slimy.model.Needs
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.SlimyCalendar
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.model.WidgetSnapshot
import de.hyprnode.slimy.model.hatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the widgets show (the port of Widget/WidgetData.swift). */
class SlimeEntryTest {
    private val berlin = SlimyCalendar.gregorian("Europe/Berlin")
    private val noon = berlin.date(2026, 10, 25, 12)

    private fun snapshot(needs: Needs = Needs(80.0, 80.0, 80.0, 80.0, 80.0), asleep: Boolean = false): WidgetSnapshot {
        val state = GameState()
        state.hatch("Testi", Skin.lime, 42uL, noon)
        return WidgetSnapshot.of(state, noon).copy(needs = needs, isAsleep = asleep, line = "hi! blub.")
    }

    @Test
    fun beforeHatching() {
        val entry = SlimeEntry(null, noon, calendar = berlin)
        assertEquals("Slime", entry.name)
        assertEquals("Open Slimy", entry.status.title)
        assertEquals(SpriteAnim.idle, entry.anim)
        assertNull(entry.speech)
        assertEquals("Open Slimy. Open the app to bring your slime here.", entry.accessibilitySummary)
        assertEquals(entry.status.title, SlimeEntry.of(GameState(), noon, berlin).status.title)
    }

    @Test
    fun happyHungryAsleep() {
        val happy = SlimeEntry(snapshot(), noon, calendar = berlin)
        assertEquals("Happy", happy.status.title)
        assertEquals(EmoteReel.happyA, happy.status.reel)
        assertEquals("hi! blub.", happy.speech)
        assertEquals("Testi. Happy. All needs looked after.", happy.accessibilitySummary)

        val hungry = SlimeEntry(snapshot(Needs(20.0, 80.0, 80.0, 80.0, 80.0)), noon, calendar = berlin)
        assertEquals(Need.hunger, hungry.lowNeed)
        assertEquals("Hungry", hungry.status.title)
        assertEquals("apple", hungry.status.item)
        assertEquals(SpriteAnim.sad, hungry.anim)
        assertEquals("Testi is hungry", hungry.status.sentence)

        val asleep = SlimeEntry(snapshot(Needs(20.0, 80.0, 80.0, 80.0, 80.0), asleep = true), noon, calendar = berlin)
        assertTrue(asleep.isAsleep)
        assertNull(asleep.lowNeed)
        assertEquals("Asleep", asleep.status.title)
        assertEquals(SpriteAnim.sleep, asleep.anim)
        assertNull(asleep.speech)

        val fine = SlimeEntry(snapshot(Needs(50.0, 50.0, 50.0, 50.0, 50.0)), noon, calendar = berlin)
        assertEquals("Doing fine", fine.status.title)
    }

    @Test
    fun needsKeepMovingAndWordsGetOld() {
        val later = noon.plusSeconds(3 * 3600)
        val entry = SlimeEntry(snapshot(), later, calendar = berlin)
        assertTrue(entry.needs.hunger < 80.0)
        assertFalse(entry.isAsleep)
        // Saved three hours ago: the slime's words are no longer fresh.
        assertNull(entry.speech)
    }

    @Test
    fun sample() {
        val entry = SlimeEntry.sample(noon)
        assertEquals("Blob", entry.name)
        assertEquals(6, entry.snapshot!!.level)
    }
}
