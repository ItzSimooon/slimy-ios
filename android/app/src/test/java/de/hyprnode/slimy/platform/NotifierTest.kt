package de.hyprnode.slimy.platform

import android.content.SharedPreferences
import de.hyprnode.slimy.model.GameState
import de.hyprnode.slimy.model.Need
import de.hyprnode.slimy.model.NotificationPrefs
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.SlimyCalendar
import de.hyprnode.slimy.model.hatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifierTest {
    private val berlin = SlimyCalendar.gregorian("Europe/Berlin")
    private val noon = berlin.date(2026, 10, 25, 12)

    private fun hatched(): GameState {
        val state = GameState()
        state.hatch("Testi", Skin.lime, 42uL, noon)
        return state
    }

    @Test
    fun quietHoursMoveToTheirEnd() {
        val prefs = NotificationPrefs(quietFrom = 22, quietUntil = 8)
        assertEquals(noon, Notifier.outsideQuietHours(noon, prefs, berlin))
        assertEquals(
            berlin.date(2026, 10, 26, 8),
            Notifier.outsideQuietHours(berlin.date(2026, 10, 25, 23, 15), prefs, berlin),
        )
        assertEquals(
            berlin.date(2026, 10, 26, 8),
            Notifier.outsideQuietHours(berlin.date(2026, 10, 26, 3), prefs, berlin),
        )
    }

    @Test
    fun morningNoteAtHalfPastEight() {
        val prefs = NotificationPrefs()
        assertEquals(berlin.date(2026, 10, 25, 8, 30), Notifier.nextMorning(berlin.date(2026, 10, 25, 7), prefs, berlin))
        assertEquals(berlin.date(2026, 10, 26, 8, 30), Notifier.nextMorning(noon, prefs, berlin))
        // Quiet until nine: half past nine.
        assertEquals(
            berlin.date(2026, 10, 26, 9, 30),
            Notifier.nextMorning(noon, NotificationPrefs(quietUntil = 9), berlin),
        )
    }

    @Test
    fun nextNeedIsTheSoonestToFallLow() {
        val state = hatched()
        state.slime.needs.hunger = 30.0
        state.slime.needs.`fun` = 95.0
        state.slime.needs.clean = 95.0
        state.slime.needs.love = 95.0
        val next = Notifier.nextNeed(state, noon)
        assertNotNull(next)
        assertEquals(Need.hunger, next!!.first)
        assertTrue(next.second.isAfter(noon))
        state.slime.isAsleep = true
        assertNull(Notifier.nextNeed(state, noon))
    }

    @Test
    fun planFollowsTheSwitches() {
        assertTrue(Notifier.plan(GameState(), noon, berlin).isEmpty())

        val state = hatched()
        val plan = Notifier.plan(state, noon, berlin)
        val ids = plan.map { it.id }.toSet()
        assertTrue("slimy.daily" in ids)
        assertFalse("slimy.thinking" in ids)
        val daily = plan.first { it.id == "slimy.daily" }
        assertEquals(berlin.date(2026, 10, 26, 8, 30), daily.at)
        assertEquals(Notifier.Category.daily, daily.category)

        state.settings.notifications.world = true
        state.settings.notifications.needs = false
        state.settings.notifications.daily = false
        val other = Notifier.plan(state, noon, berlin)
        assertEquals(listOf("slimy.thinking"), other.map { it.id })
        assertEquals(berlin.date(2026, 10, 27, 12), other[0].at)
        assertFalse(state.settings.notifications.isQuiet(berlin.hour(other[0].at)))
    }

    @Test
    fun needReminderNeverFallsIntoQuietHours() {
        val state = hatched()
        state.slime.needs.hunger = 30.0
        val evening = berlin.date(2026, 10, 25, 21, 55)
        val need = Notifier.plan(state, evening, berlin).firstOrNull { it.id == "slimy.need" }
        assertNotNull(need)
        assertFalse(state.settings.notifications.isQuiet(berlin.hour(need!!.at)))
        assertEquals(Notifier.Category.needs, need.category)
        assertEquals("Testi", need.title)
        assertTrue(need.body.isNotBlank())
    }

    @Test
    fun atMostFourADay() {
        val prefs = FakePrefs()
        repeat(Notifier.dailyLimit) { assertTrue(Notifier.takeSlot(prefs, noon, berlin)) }
        assertFalse(Notifier.takeSlot(prefs, noon, berlin))
        // A new day, a new budget.
        assertTrue(Notifier.takeSlot(prefs, berlin.date(2026, 10, 26, 9), berlin))
        assertEquals("2026-10-26", prefs.getString("slimy.interruptions.day", null))
    }

    @Test
    fun categoriesMapToTheirSwitches() {
        val prefs = NotificationPrefs(needs = true, daily = false, friends = true, gifts = false, world = true)
        assertTrue(Notifier.isOn(Notifier.Category.needs, prefs))
        assertFalse(Notifier.isOn(Notifier.Category.daily, prefs))
        assertTrue(Notifier.isOn(Notifier.Category.friends, prefs))
        assertFalse(Notifier.isOn(Notifier.Category.gifts, prefs))
        assertTrue(Notifier.isOn(Notifier.Category.world, prefs))
        assertEquals("friends", Notifier.Category.gifts.place)
        assertEquals("needs", Notifier.Category.needs.channel)
    }

    /** SharedPreferences in memory. */
    private class FakePrefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = key in values
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        inner class Editor : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            override fun putString(key: String, value: String?) = apply { changes[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes[key] = values }
            override fun putInt(key: String, value: Int) = apply { changes[key] = value }
            override fun putLong(key: String, value: Long) = apply { changes[key] = value }
            override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
            override fun remove(key: String) = apply { changes[key] = null }
            override fun clear() = apply { values.clear() }
            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                for ((k, v) in changes) if (v == null) values.remove(k) else values[k] = v
                changes.clear()
            }
        }
    }
}
