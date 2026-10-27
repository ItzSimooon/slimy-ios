package de.hyprnode.slimy.screenshots

import android.Manifest
import android.content.ComponentName
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import de.hyprnode.slimy.arcade.ArcadeScreen
import de.hyprnode.slimy.arcade.BathScreen
import de.hyprnode.slimy.arcade.GameHost
import de.hyprnode.slimy.model.CrateKind
import de.hyprnode.slimy.model.GameID
import de.hyprnode.slimy.model.GameState
import de.hyprnode.slimy.ui.screens.about.WhatsNewScreen
import de.hyprnode.slimy.ui.screens.care.CareTogetherScreen
import de.hyprnode.slimy.ui.screens.chat.ChatScreen
import de.hyprnode.slimy.ui.screens.crates.CrateOpenScreen
import de.hyprnode.slimy.ui.screens.crates.CrateShelfScreen
import de.hyprnode.slimy.ui.screens.friends.FriendsScreen
import de.hyprnode.slimy.ui.screens.home.HomeScreen
import de.hyprnode.slimy.ui.screens.journal.JournalScreen
import de.hyprnode.slimy.ui.screens.kitchen.KitchenScreen
import de.hyprnode.slimy.ui.screens.onboarding.OnboardingScreen
import de.hyprnode.slimy.ui.screens.photo.PhotoBoothScreen
import de.hyprnode.slimy.ui.screens.settings.SettingsScreen
import de.hyprnode.slimy.ui.screens.shop.ShopScreen
import de.hyprnode.slimy.ui.screens.streak.StreakScreen
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone

/** A phone of 412 x 915 dp at 3x (1236 x 2745 px), portrait. */
const val PHONE = "w412dp-h915dp-normal-long-notround-port"

/**
 * Every main screen of the app with the demo slime, rendered on the JVM by
 * Robolectric's native graphics and recorded by Roborazzi:
 * `gradle :app:recordRoborazziDebug` writes `<light|dark>-<screen>.png` to
 * `app/build/outputs/roborazzi`. A plain `testDebugUnitTest` renders them
 * too (so a screen that crashes fails the build) without writing files.
 *
 * The world is put at about ten in the morning, wherever and whenever the
 * test runs, so the habitat's sky and the slime's bedtime look the same on
 * every run.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ScreenshotSuite(private val appearance: String) {
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * The compose rule starts a plain `ComponentActivity`, which the app's
     * manifest doesn't have; Robolectric gets to know it first, so no test
     * manifest has to go into the app.
     */
    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(object : TestWatcher() {
            override fun starting(description: Description) {
                val app = ApplicationProvider.getApplicationContext<android.app.Application>()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
            }
        })
        .around(compose)

    private val context get() = ApplicationProvider.getApplicationContext<ScreenshotApp>()
    private var zone: TimeZone? = null
    private var locale: Locale? = null

    @Before
    fun setUp() {
        zone = TimeZone.getDefault()
        locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val utcHour = ZonedDateTime.now(ZoneOffset.UTC).hour
        var offset = 10 - utcHour
        if (offset > 14) offset -= 24
        if (offset < -12) offset += 24
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.ofHours(offset)))
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        zone?.let { TimeZone.setDefault(it) }
        locale?.let { Locale.setDefault(it) }
    }

    // MARK: Worlds

    private fun demo(online: Boolean = false, chat: Boolean = false, consent: Boolean = false, state: GameState? = null): DemoWorld {
        val now = Instant.now()
        val world = DemoWorld(
            context,
            state ?: Demo.state(now),
            online = online,
            chat = if (chat) Demo.chat(now) else emptyList(),
            chatConsent = consent,
        )
        if (online) {
            // Friends, the request and the challenge arrive from the demo server before the screen opens.
            val job = world.friends.scope.launch { world.friends.refresh() }
            pumpUntil { job.isCompleted }
        }
        return world
    }

    /** Runs the main looper (where the stores live) until [done], for up to five seconds. */
    private fun pumpUntil(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!done() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    /**
     * Shows [content] in [world], lets entrance animations finish ([settle] ms
     * of the frame clock), does [act] and records the whole screen, dialogs
     * and sheets included.
     */
    private fun shoot(
        name: String,
        world: DemoWorld = demo(),
        settle: Long = 1_500,
        act: () -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent { world.Show(content) }
        compose.mainClock.advanceTimeBy(settle)
        act()
        compose.waitForIdle()
        captureScreenRoboImage("$outputDir/$appearance-$name.png")
    }

    // MARK: Screens

    @Test
    fun home() = shoot("home") { HomeScreen(open = {}) }

    @Test
    fun onboarding() = shoot("onboarding", demo(state = Demo.egg())) { OnboardingScreen() }

    @Test
    fun kitchen() = shoot("kitchen") { KitchenScreen(onClose = {}) }

    @Test
    fun shop() = shoot("shop") { ShopScreen(onClose = {}, onOpenCrate = {}, onDropRates = {}) }

    @Test
    fun crates() = shoot("crates") { CrateShelfScreen(onClose = {}, onOpen = {}, onDropRates = {}) }

    @Test
    fun crateReveal() = shoot(
        "crate-reveal",
        act = {
            val crate = SemanticsMatcher("the crate") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Crack it open" }
            // Three to five knocks crack it; after that the crate no longer takes taps.
            repeat(6) {
                if (compose.onAllNodes(crate).fetchSemanticsNodes().isNotEmpty()) compose.onNode(crate).performClick()
                compose.mainClock.advanceTimeBy(250)
            }
            compose.mainClock.advanceTimeBy(6_000)
        },
    ) { CrateOpenScreen(kind = CrateKind.golden, onDone = {}) }

    @Test
    fun journal() = shoot("journal") { JournalScreen(onClose = {}, onPhotoBooth = {}) }

    @Test
    fun gift() = shoot("gift") { StreakScreen(onClose = {}, onOpenCrate = {}) }

    @Test
    fun photoBooth() = shoot("photo-booth") { PhotoBoothScreen(onClose = {}) }

    @Test
    fun settings() = shoot("settings", demo(online = true)) {
        SettingsScreen(onClose = {}, onCareTogether = {}, onGoOnline = {}, onHelp = {}, onAbout = {})
    }

    @Test
    fun friendsOffline() = shoot("friends-offline") { FriendsScreen(onClose = {}) }

    @Test
    fun friends() = shoot("friends", demo(online = true)) { FriendsScreen(onClose = {}) }

    @Test
    fun chatEmpty() = shoot("chat-empty", demo(online = true)) { ChatScreen(onClose = {}) }

    @Test
    fun chat() = shoot("chat", demo(online = true, chat = true, consent = true)) { ChatScreen(onClose = {}) }

    @Test
    fun care() = shoot("care", demo(online = true)) { CareTogetherScreen(onClose = {}) }

    @Test
    fun arcade() = shoot("arcade", demo(online = true)) { ArcadeScreen(onClose = {}) }

    @Test
    fun snackCatcher() = shoot("game-snack-catcher", settle = 4_000) {
        GameHost(game = GameID.snackCatcher, close = {})
    }

    @Test
    fun bath() = shoot("bath") { BathScreen(onClose = {}) }

    @Test
    fun whatsNew() = shoot("whats-new") { WhatsNewScreen(onClose = {}) }

    companion object {
        /** Where Roborazzi's record task leaves the PNGs (relative to the app module). */
        val outputDir: String = System.getProperty("roborazzi.output.dir") ?: "build/outputs/roborazzi"
    }
}

@Config(application = ScreenshotApp::class, sdk = [35], qualifiers = "$PHONE-notnight-xxhdpi")
class LightScreenshots : ScreenshotSuite("light")

@Config(application = ScreenshotApp::class, sdk = [35], qualifiers = "$PHONE-night-xxhdpi")
class DarkScreenshots : ScreenshotSuite("dark")
