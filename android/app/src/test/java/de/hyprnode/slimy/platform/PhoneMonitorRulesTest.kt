package de.hyprnode.slimy.platform

import de.hyprnode.slimy.model.PhoneEvent
import de.hyprnode.slimy.model.SleepSchedule
import de.hyprnode.slimy.model.SlimyCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The PhoneEvent rules of App/Core/PhoneMonitor.swift, as PhoneMonitor's companion applies them. */
class PhoneMonitorRulesTest {
    private val berlin = SlimyCalendar.gregorian("Europe/Berlin")
    private val noon = berlin.date(2026, 10, 25, 12)
    private val schedule = SleepSchedule(bedtime = 22, wake = 7)

    // Half past twelve, so a minute or two earlier is still the same hour.
    private val half = noon.plusSeconds(1800)

    private fun snap(
        battery: Float = 0.5f,
        charging: Boolean = false,
        full: Boolean = false,
        lowPower: Boolean = false,
        online: Boolean = true,
        wifi: Boolean = true,
        cellular: Boolean = false,
        netKnown: Boolean = true,
        at: Instant = half,
    ) = PhoneMonitor.Snapshot(battery, charging, full, lowPower, online, wifi, cellular, netKnown, at)

    private fun events(
        previous: PhoneMonitor.Snapshot?,
        snapshot: PhoneMonitor.Snapshot,
        settling: Boolean = false,
        background: Boolean = false,
        now: Instant = snapshot.at,
        fired: Set<PhoneEvent> = emptySet(),
    ) = PhoneMonitor.events(previous, snapshot, settling, background, schedule, now, berlin) { it in fired }

    @Test
    fun chargerAndPowerModeCountOnlyWithState() {
        val old = snap()
        val plugged = snap(charging = true)
        assertEquals(listOf(PhoneEvent.chargerConnected), PhoneMonitor.powerEvents(old, plugged, includeState = true))
        assertEquals(listOf(PhoneEvent.chargerDisconnected), PhoneMonitor.powerEvents(plugged, old, includeState = true))
        assertTrue(PhoneMonitor.powerEvents(old, plugged, includeState = false).isEmpty())
        assertEquals(
            listOf(PhoneEvent.batteryFull),
            PhoneMonitor.powerEvents(snap(charging = true), snap(charging = true, full = true), includeState = true),
        )
        assertEquals(listOf(PhoneEvent.lowPowerOn), PhoneMonitor.powerEvents(old, snap(lowPower = true), includeState = true))
        assertEquals(listOf(PhoneEvent.lowPowerOff), PhoneMonitor.powerEvents(snap(lowPower = true), old, includeState = true))
    }

    @Test
    fun batteryThresholdsAreCrossings() {
        assertEquals(
            listOf(PhoneEvent.batteryLow),
            PhoneMonitor.powerEvents(snap(battery = 0.21f), snap(battery = 0.19f), includeState = false),
        )
        assertEquals(
            listOf(PhoneEvent.batteryCritical),
            PhoneMonitor.powerEvents(snap(battery = 0.30f), snap(battery = 0.04f), includeState = false),
        )
        // Already below: no news. Charging: no news. Unknown: no news.
        assertTrue(PhoneMonitor.powerEvents(snap(battery = 0.18f), snap(battery = 0.17f), includeState = false).isEmpty())
        assertTrue(PhoneMonitor.powerEvents(snap(battery = 0.21f), snap(battery = 0.19f, charging = true), includeState = false).isEmpty())
        assertTrue(PhoneMonitor.powerEvents(snap(battery = -1f), snap(battery = 0.1f), includeState = false).isEmpty())
    }

    @Test
    fun networkTransitions() {
        val wifi = snap(online = true, wifi = true)
        val cell = snap(online = true, wifi = false, cellular = true)
        val off = snap(online = false, wifi = false, cellular = false)
        assertEquals(listOf(PhoneEvent.offline), PhoneMonitor.networkEvents(wifi, off))
        assertEquals(listOf(PhoneEvent.wifiConnected), PhoneMonitor.networkEvents(off, wifi))
        assertEquals(listOf(PhoneEvent.backOnline), PhoneMonitor.networkEvents(off, cell))
        assertEquals(listOf(PhoneEvent.wifiConnected), PhoneMonitor.networkEvents(cell, wifi))
        assertEquals(listOf(PhoneEvent.cellular), PhoneMonitor.networkEvents(wifi, cell))
        assertTrue(PhoneMonitor.networkEvents(wifi, wifi).isEmpty())
    }

    @Test
    fun morningNightAndNewHour() {
        val morning = berlin.date(2026, 10, 25, 8)
        assertEquals(
            listOf(PhoneEvent.goodMorning),
            PhoneMonitor.clockEvents(null, morning, schedule, foreground = false, berlin) { false },
        )
        // Once a day.
        assertTrue(PhoneMonitor.clockEvents(null, morning, schedule, false, berlin) { it == PhoneEvent.goodMorning }.isEmpty())
        // Three hours after waking, the morning is over.
        assertTrue(PhoneMonitor.clockEvents(null, berlin.date(2026, 10, 25, 10), schedule, false, berlin) { false }.isEmpty())
        val night = berlin.date(2026, 10, 25, 22, 10)
        assertEquals(listOf(PhoneEvent.goodNight), PhoneMonitor.clockEvents(null, night, schedule, false, berlin) { false })

        // A new hour only while watching, and only if the last check was within the hour.
        val before = berlin.date(2026, 10, 25, 11, 58)
        assertEquals(listOf(PhoneEvent.newHour), PhoneMonitor.clockEvents(before, noon, schedule, foreground = true, berlin) { false })
        assertTrue(PhoneMonitor.clockEvents(before, noon, schedule, foreground = false, berlin) { false }.isEmpty())
        val long = berlin.date(2026, 10, 25, 10, 30)
        assertTrue(PhoneMonitor.clockEvents(long, noon, schedule, foreground = true, berlin) { false }.isEmpty())
    }

    @Test
    fun firstReadingsOnlySetTheBaseline() {
        val old = snap(at = half.minusSeconds(60))
        val now = snap(charging = true, online = false, wifi = false)
        assertTrue(events(old, now, settling = true).isEmpty())
        assertTrue(events(null, now).isEmpty())
    }

    @Test
    fun oldReadingsDontCountForStateOrNetwork() {
        // Hours later in the foreground: the charger went in long ago, the network changed long ago.
        val old = snap(at = half.minusSeconds(2 * 3600))
        val now = snap(charging = true, online = false, wifi = false)
        assertTrue(events(old, now, background = false).isEmpty())
        // A background run is the reaction to charging; the network is still too old.
        assertEquals(listOf(PhoneEvent.chargerConnected), events(old, now, background = true))
        // Unknown network on either side is never compared.
        val recent = snap(at = half.minusSeconds(60), netKnown = false)
        assertTrue(events(recent, snap(online = false, wifi = false)).isEmpty())
    }

    @Test
    fun theTwoThatMatterMostWin() {
        val old = snap(battery = 0.30f, charging = true, at = half.minusSeconds(120))
        val now = snap(battery = 0.04f, charging = false, online = false, wifi = false)
        assertEquals(listOf(PhoneEvent.batteryCritical, PhoneEvent.offline), events(old, now))
    }

    @Test
    fun priorities() {
        assertEquals(9, PhoneEvent.batteryCritical.priority)
        assertEquals(6, PhoneEvent.chargerConnected.priority)
        assertEquals(1, PhoneEvent.newHour.priority)
        for (event in PhoneEvent.allCases) assertTrue(event.priority in 1..9)
    }
}
