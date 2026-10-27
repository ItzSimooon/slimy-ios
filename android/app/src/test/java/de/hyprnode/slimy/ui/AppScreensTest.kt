package de.hyprnode.slimy.ui

import de.hyprnode.slimy.model.CrateKind
import de.hyprnode.slimy.model.DailyGift
import de.hyprnode.slimy.model.GameState
import de.hyprnode.slimy.model.Habitat
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.StreakInfo
import de.hyprnode.slimy.model.hatch
import de.hyprnode.slimy.platform.IslandController
import de.hyprnode.slimy.ui.screens.about.AppVersion
import de.hyprnode.slimy.ui.screens.about.PixelifyLicense
import de.hyprnode.slimy.ui.screens.about.WhatsNew
import de.hyprnode.slimy.ui.screens.common.gridColumns
import de.hyprnode.slimy.ui.screens.help.HelpTopic
import de.hyprnode.slimy.ui.screens.home.HomeLayout
import de.hyprnode.slimy.ui.screens.onboarding.OnboardingRules
import de.hyprnode.slimy.ui.screens.photo.BoothFiles
import de.hyprnode.slimy.ui.screens.photo.BoothFormat
import de.hyprnode.slimy.ui.screens.photo.BoothLight
import de.hyprnode.slimy.ui.screens.photo.BoothOptions
import de.hyprnode.slimy.ui.screens.photo.BoothPose
import de.hyprnode.slimy.ui.screens.photo.PhotoText
import de.hyprnode.slimy.ui.screens.root.ToastTiming
import de.hyprnode.slimy.ui.screens.settings.NotificationStatus
import de.hyprnode.slimy.ui.screens.settings.SettingsSystem
import de.hyprnode.slimy.ui.screens.streak.DayStatus
import de.hyprnode.slimy.ui.screens.streak.StreakText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class AppScreensTest {
    private val now: Instant = Instant.parse("2026-10-25T12:00:00Z")

    private fun hatched(): GameState = GameState().apply { hatch("Blob", Skin.lime, 7uL, now) }

    // MARK: Root and navigation

    @Test
    fun everyOpenRequestHasAPlace() {
        for (value in EXTRA_OPEN_VALUES) {
            val destination = Destination.forOpenRequest(value)
            assertEquals(value, destination?.route)
        }
        assertNull(Destination.forOpenRequest("gift"))
        assertNull(Destination.forOpenRequest("nowhere"))
        assertEquals("crateOpen/golden", Destination.crateOpenRoute(CrateKind.golden))
        assertTrue(Destination.covers.containsAll(listOf("arcade", "bath")))
    }

    @Test
    fun onboardedNeedsHatchAndTip() {
        assertFalse(AppRules.isOnboarded(hatched = false, tips = setOf("onboarded")))
        assertFalse(AppRules.isOnboarded(hatched = true, tips = emptySet()))
        assertTrue(AppRules.isOnboarded(hatched = true, tips = setOf("onboarded", "other")))
    }

    @Test
    fun toastsStayLongEnoughToRead() {
        assertEquals(2_400L, ToastTiming.millis("Yum!", talkBack = false))
        assertEquals(5_000L, ToastTiming.millis("x".repeat(200), talkBack = false))
        assertEquals(3_000L, ToastTiming.millis("x".repeat(50), talkBack = false))
        assertEquals(5_000L, ToastTiming.millis("Yum!", talkBack = true))
    }

    @Test
    fun whatsNewOncePerRelease() {
        assertTrue(WhatsNew.isDue("1.1.0", null))
        assertTrue(WhatsNew.isDue("1.1.2", "1.0"))
        assertFalse(WhatsNew.isDue("1.1.0", "1.1"))
        assertFalse(WhatsNew.isDue("1.2.0", null))
        assertEquals("1.1", AppVersion.marketing("1.1.3"))
        assertEquals("2", AppVersion.marketing("2"))
        for (item in WhatsNew.items) assertFalse(item.detail, item.detail.contains("iPhone"))
    }

    @Test
    fun licenseShipsWithTheFont() {
        assertTrue(PixelifyLicense.blocks.isNotEmpty())
        assertTrue(PixelifyLicense.blocks.any { it is PixelifyLicense.Block.heading && it.text.contains("SIL OPEN FONT LICENSE") })
    }

    @Test
    fun gridFitsAsManyCellsAsRoomAllows() {
        assertEquals(3, gridColumns(360f, 104f, 12f))
        assertEquals(1, gridColumns(100f, 150f, 12f))
        assertEquals(1, gridColumns(0f, 64f, 8f))
        assertEquals(2, gridColumns(312f, 150f, 12f))
    }

    // MARK: Home

    @Test
    fun slimeStandsOnTheFloorUnlessTheDockIsInTheWay() {
        assertEquals(448f, HomeLayout.feet(100, 4f, Float.POSITIVE_INFINITY, 6f), 0f)
        assertEquals(412f, HomeLayout.feet(100, 4f, 300f, 6f), 0f)
        assertEquals(430f, HomeLayout.feet(100, 4f, 436f, 6f), 0f)
    }

    @Test
    fun homeWords() {
        assertEquals("Level 3 · cheerful", HomeLayout.levelText(3, "cheerful"))
        assertEquals("Level 3", HomeLayout.levelText(3, null))
        assertEquals("1 crate to open", HomeLayout.cratesText(1))
        assertEquals("4 crates to open", HomeLayout.cratesText(4))
    }

    @Test
    fun cratePillCountsAndPicksTheBest() {
        val s = hatched()
        assertEquals(0, HomeLayout.crateCount(s))
        assertEquals(CrateKind.wooden, HomeLayout.bestCrate(s))
        s.crates = mapOf(CrateKind.wooden to 2, CrateKind.golden to 1, CrateKind.mystery to -1)
        assertEquals(3, HomeLayout.crateCount(s))
        assertEquals(CrateKind.golden, HomeLayout.bestCrate(s))
    }

    @Test
    fun chatterWaitsForCelebrationsAndFreshLines() {
        assertTrue(HomeLayout.mayChatter(false, null, now))
        assertFalse(HomeLayout.mayChatter(true, null, now))
        assertFalse(HomeLayout.mayChatter(false, now.minusSeconds(3), now))
        assertTrue(HomeLayout.mayChatter(false, now.minusSeconds(7), now))
    }

    // MARK: Onboarding

    @Test
    fun nameIdeasAreNeverTheSameTwice() {
        repeat(50) { assertNotEquals("Mochi", OnboardingRules.suggestion("Mochi")) }
        assertTrue(OnboardingRules.suggestion("").isNotEmpty())
    }

    @Test
    fun onboardingAsksForNotificationsOnlyWhereItMust() {
        assertTrue(OnboardingRules.notificationsUndecided(authorized = false, asked = false, sdk = 33))
        assertFalse(OnboardingRules.notificationsUndecided(authorized = false, asked = false, sdk = 31))
        assertFalse(OnboardingRules.notificationsUndecided(authorized = true, asked = false, sdk = 34))
        assertFalse(OnboardingRules.notificationsUndecided(authorized = false, asked = true, sdk = 34))
        assertTrue(OnboardingRules.skipIsland(IslandController.Presence.unavailable, asked = true))
        assertFalse(OnboardingRules.skipIsland(IslandController.Presence.unavailable, asked = false))
        assertFalse(OnboardingRules.skipIsland(IslandController.Presence.notification, asked = true))
        val text = OnboardingRules.islandText("Blob")
        assertTrue(text.startsWith("Blob can live in your notifications."))
        assertFalse(text.contains("Island"))
    }

    // MARK: Daily gift

    private fun week(): List<DailyGift> = (1..7).map { DailyGift(day = it, coins = 10 * it, item = null, crate = if (it == 7) CrateKind.mystery else null) }

    private fun info(today: Int, canClaim: Boolean, claimedToday: Boolean, current: Int = 1) = StreakInfo(
        current = current, best = current, opened = today - 1, canClaim = canClaim, claimedToday = claimedToday,
        today = today, week = week(), nextGift = null,
    )

    @Test
    fun giftWordsFollowTheDay() {
        assertEquals("Today's gift is here", StreakText.headline(info(3, canClaim = true, claimedToday = false)))
        assertEquals("The big gift is here", StreakText.headline(info(7, canClaim = true, claimedToday = false)))
        assertEquals("4 days in a row", StreakText.headline(info(3, canClaim = false, claimedToday = true, current = 4)))
        assertEquals("Gift opened", StreakText.headline(info(1, canClaim = false, claimedToday = true, current = 1)))
        assertEquals("See you tomorrow", StreakText.headline(info(2, canClaim = false, claimedToday = false)))
        assertEquals("Come back tomorrow for the next one.", StreakText.subline(info(2, false, true), week()[1], now))
    }

    @Test
    fun waitingIsSaidInRoundWords() {
        assertEquals("in a minute", StreakText.wait(now.plusSeconds(30), now))
        assertEquals("in 20 minutes", StreakText.wait(now.plusSeconds(20 * 60), now))
        assertEquals("in an hour", StreakText.wait(now.plusSeconds(60 * 60), now))
        assertEquals("in an hour", StreakText.wait(now.plusSeconds(89 * 60), now))
        assertEquals("in 5 hours", StreakText.wait(now.plusSeconds(5 * 3600), now))
    }

    @Test
    fun calendarTiles() {
        val open = info(3, canClaim = true, claimedToday = false)
        assertEquals(DayStatus.opened, StreakText.tileState(week()[0], open))
        assertEquals(DayStatus.ready, StreakText.tileState(week()[2], open))
        assertEquals(DayStatus.later, StreakText.tileState(week()[5], open))
        val done = info(3, canClaim = false, claimedToday = true)
        assertEquals(DayStatus.openedToday, StreakText.tileState(week()[2], done))
        assertEquals("Mystery crate + 70", StreakText.tileLabel(week()[6]))
        assertEquals("20 coins", StreakText.tileLabel(week()[1]))
        assertEquals("3 days", StreakText.days(3))
        assertEquals("1 day", StreakText.days(1))
    }

    // MARK: Photo booth

    @Test
    fun photoDescriptionSaysWhatIsInIt() {
        val s = hatched()
        val options = BoothOptions(BoothPose.wave, Habitat.beach, BoothLight.night, BoothFormat.square, "hi!", true)
        assertEquals("Photo of Blob waving on the beach, night, saying hi!", PhotoText.description(s.slime, options))
        assertEquals("in its room", PhotoText.whereAt(Habitat.room))
        assertEquals("Blob in Slimy.png", BoothFiles.fileName("Blob!"))
        assertEquals("Slimy in Slimy.png", BoothFiles.fileName("!!"))
    }

    @Test
    fun boothLightIsTodayAtThatHour() {
        val utc = ZoneId.of("UTC")
        assertEquals(Instant.parse("2026-10-25T18:00:00Z"), BoothLight.evening.date(now, utc))
        assertEquals(Instant.parse("2026-10-25T22:30:00Z"), BoothLight.night.date(now, utc))
        assertEquals(1f, BoothFormat.square.aspect, 0f)
        assertEquals(64f, BoothFormat.square.plateHeight, 0f)
        assertEquals(76f, BoothFormat.story.plateHeight, 0f)
    }

    // MARK: Settings

    @Test
    fun notificationStatusLikeTheSystemSees() {
        assertEquals(NotificationStatus.allowed, SettingsSystem.notificationStatus(true, asked = false, sdk = 34))
        assertEquals(NotificationStatus.notDetermined, SettingsSystem.notificationStatus(null, asked = false, sdk = 34))
        assertEquals(NotificationStatus.allowed, SettingsSystem.notificationStatus(null, asked = true, sdk = 34))
        assertEquals(NotificationStatus.notDetermined, SettingsSystem.notificationStatus(false, asked = false, sdk = 33))
        assertEquals(NotificationStatus.denied, SettingsSystem.notificationStatus(false, asked = false, sdk = 30))
        assertEquals(NotificationStatus.denied, SettingsSystem.notificationStatus(false, asked = true, sdk = 34))
    }

    @Test
    fun hourPickerNeverStartsWhereItEnds() {
        val choices = SettingsSystem.hourChoices(current = 22, avoiding = 8)
        assertEquals(23, choices.size)
        assertFalse(choices.contains(8))
        assertEquals(24, SettingsSystem.hourChoices(current = 8, avoiding = 8).size)
        assertEquals("22:00", SettingsSystem.hourLabel(22, is24 = true, locale = Locale.US))
        assertEquals("10:00 PM", SettingsSystem.hourLabel(22, is24 = false, locale = Locale.US))
        assertEquals("12:00 AM", SettingsSystem.hourLabel(0, is24 = false, locale = Locale.US))
        assertEquals("07:00", SettingsSystem.hourLabel(7, is24 = true, locale = Locale.US))
    }

    // MARK: Help

    @Test
    fun helpIsToldForAndroid() {
        val ids = HelpTopic.all.flatMap { topic -> topic.items.map { it.id } }
        assertEquals(ids.size, ids.toSet().size)
        for (topic in HelpTopic.all) {
            for (item in topic.items) {
                for (text in listOf(item.question, item.answer, topic.title)) {
                    assertFalse(text, text.contains("this iPhone") || text.contains("Dynamic Island") || text.contains("Control Center"))
                }
            }
        }
    }

    @Test
    fun helpSearchFindsQuestionsAndAnswers() {
        val crates = HelpTopic.filtered(HelpTopic.all, "  real money ")
        assertTrue(crates.isNotEmpty())
        assertTrue(crates.all { topic -> topic.items.all { it.question.contains("real money", true) || it.answer.contains("real money", true) } })
        assertTrue(HelpTopic.filtered(HelpTopic.all, "zzzzqqq").isEmpty())
        assertEquals(HelpTopic.all, HelpTopic.filtered(HelpTopic.all, " "))
    }
}
