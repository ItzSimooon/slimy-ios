package de.hyprnode.slimy.screenshots

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.hyprnode.slimy.ui.screens.onboarding.OnboardingScreen
import de.hyprnode.slimy.model.Accessories
import de.hyprnode.slimy.model.CrateKind
import de.hyprnode.slimy.model.GameState
import de.hyprnode.slimy.model.Mood
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.Stage
import de.hyprnode.slimy.model.Utterance
import de.hyprnode.slimy.platform.FriendSlime
import de.hyprnode.slimy.platform.SharedMoment
import de.hyprnode.slimy.platform.island.IslandMetrics
import de.hyprnode.slimy.platform.island.IslandMode
import de.hyprnode.slimy.platform.island.IslandNews
import de.hyprnode.slimy.platform.island.IslandPlacement
import de.hyprnode.slimy.platform.island.IslandScene
import de.hyprnode.slimy.platform.island.IslandScreen
import de.hyprnode.slimy.platform.island.IslandTextScale
import de.hyprnode.slimy.platform.island.IslandTuning
import de.hyprnode.slimy.platform.island.IslandView
import de.hyprnode.slimy.platform.island.PxRect
import de.hyprnode.slimy.ui.screens.settings.IslandPositionControls
import de.hyprnode.slimy.ui.screens.settings.IslandSection
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
import org.robolectric.shadows.ShadowSettings
import java.time.Instant

/**
 * The island on its own, over a wallpaper, the way it sits around the camera
 * at the top of a 412 dp phone: compact, open, asleep, with a friend, the
 * axolotl, the menu, and the camera shapes (left punch-hole, notch, none).
 * `gradle :app:recordRoborazziDebug` writes `<light|dark>-island-*.png`.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class IslandScreenshotSuite(private val appearance: String) {
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(object : TestWatcher() {
            override fun starting(description: Description) {
                val app = ApplicationProvider.getApplicationContext<android.app.Application>()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
            }
        })
        .around(compose)

    private val dark get() = appearance == "dark"

    // MARK: Phones

    /** A 412 dp phone at 3x with a centred punch-hole, like most Android phones. */
    private fun centered(): IslandScreen = IslandPhone.punchHole(centerXdp = 206f)

    // MARK: Scenes

    private fun scene(
        now: Instant = Instant.now(),
        change: (GameState) -> Unit = {},
        moment: SharedMoment? = null,
        news: IslandNews? = null,
    ): IslandScene {
        val state = Demo.state(now)
        state.slime.say = Utterance(text = "hungry. very hungry.", at = now)
        change(state)
        val friends = if (moment != null) IslandPhone.friends() else emptyList()
        return IslandScene.of(state, now, friends, moment, friends.firstOrNull()?.id, animated = false, news = news)
    }

    private fun shoot(
        name: String,
        scene: IslandScene,
        mode: IslandMode = IslandMode.compact,
        screen: IslandScreen = centered(),
        tuning: IslandTuning = IslandTuning(),
        heightDp: Int = 260,
    ) {
        val placement = IslandPlacement.of(screen, tuning, IslandMetrics.expandedBody, IslandMetrics.menuBody)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(
                Modifier
                    .testTag("island")
                    .size(412.dp, heightDp.dp)
                    .background(if (dark) Color(0xFF0E1426) else Color(0xFFE9E4DA)),
            ) {
                Wallpaper(dark)
                StatusBar(dark, screen)
                IslandTextScale {
                    IslandView(
                        scene = scene,
                        placement = placement,
                        mode = mode,
                        window = PxRect(0f, 0f, screen.width.toFloat(), screen.height.toFloat()),
                        animated = false,
                        still = true,
                    )
                }
                placement.hole?.takeIf { placement.camera != de.hyprnode.slimy.platform.island.CameraKind.notch }?.let { Lens(it, screen.density) }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithTag("island").captureRoboImage("${ScreenshotSuite.outputDir}/$appearance-island-$name.png")
    }

    // MARK: Shots

    @Test
    fun compact() = shoot("compact", scene())

    @Test
    fun expanded() = shoot("expanded", scene(), IslandMode.expanded)

    @Test
    fun menu() = shoot("menu", scene(), IslandMode.menu)

    @Test
    fun sleeping() = shoot("sleeping", scene(change = { s ->
        s.slime.isAsleep = true
        s.slime.say = null
    }))

    @Test
    fun sleepingExpanded() = shoot("sleeping-expanded", scene(change = { s ->
        s.slime.isAsleep = true
        s.slime.say = null
    }), IslandMode.expanded)

    @Test
    fun sharedMoment() = shoot("moment", scene(moment = SharedMoment.hug))

    @Test
    fun sharedMomentExpanded() = shoot("moment-expanded", scene(moment = SharedMoment.meetup), IslandMode.expanded)

    @Test
    fun axolotl() = shoot("axolotl", scene(change = { s ->
        s.slime.skin = Skin.axolotl
        s.slime.equipped = Accessories()
    }))

    @Test
    fun axolotlExpanded() = shoot("axolotl-expanded", scene(change = { s ->
        s.slime.skin = Skin.axolotl
        s.slime.equipped = Accessories(hat = "hat.crown")
    }), IslandMode.expanded)

    @Test
    fun crate() {
        val now = Instant.now()
        shoot("crate", scene(now, news = IslandNews.Crate(CrateKind.mystery, now)))
    }

    @Test
    fun leftPunchHole() = shoot("left-hole", scene(), screen = IslandPhone.punchHole(centerXdp = 34f))

    @Test
    fun leftPunchHoleExpanded() = shoot("left-hole-expanded", scene(), IslandMode.expanded, screen = IslandPhone.punchHole(centerXdp = 34f))

    @Test
    fun rightPunchHole() = shoot("right-hole", scene(), screen = IslandPhone.punchHole(centerXdp = 378f))

    @Test
    fun notch() = shoot("notch", scene(), screen = IslandPhone.notch())

    @Test
    fun noCutout() = shoot("no-cutout", scene(), screen = IslandPhone.plain())

    @Test
    fun tuned() = shoot("tuned", scene(), tuning = IslandTuning(offsetX = 0f, offsetY = 4f, scale = 1.2f))

    // MARK: Settings

    /** [content] in the app's theme and stores, on the Settings backdrop, recorded at its own height. */
    private fun shootSettings(name: String, allowed: Boolean, content: @Composable () -> Unit) {
        ShadowSettings.setCanDrawOverlays(allowed)
        val context = ApplicationProvider.getApplicationContext<ScreenshotApp>()
        val world = DemoWorld(context, Demo.state(Instant.now()))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            world.Show {
                Column(
                    Modifier
                        .testTag("settings")
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onNodeWithTag("settings").captureRoboImage("${ScreenshotSuite.outputDir}/$appearance-island-$name.png")
    }

    @Test
    fun settingsSection() = shootSettings("settings", allowed = true) {
        IslandSection(onPosition = {}, screen = centered())
    }

    @Test
    fun settingsSectionNotAllowed() = shootSettings("settings-permission", allowed = false) {
        IslandSection(onPosition = {}, screen = centered())
    }

    /** Onboarding's "Always with you" step, which offers the island first. */
    @Test
    fun onboardingStep() {
        ShadowSettings.setCanDrawOverlays(false)
        val context = ApplicationProvider.getApplicationContext<ScreenshotApp>()
        val state = Demo.state(Instant.now()).also { it.player.tips = it.player.tips - "onboarded" }
        val world = DemoWorld(context, state)
        compose.mainClock.autoAdvance = false
        compose.setContent { world.Show { OnboardingScreen() } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Next").performClick()
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithText("Give an Apple").performClick()
        compose.mainClock.advanceTimeBy(2_500)
        compose.waitForIdle()
        captureScreenRoboImage("${ScreenshotSuite.outputDir}/$appearance-island-onboarding.png")
    }

    @Test
    fun positionSheet() = shootSettings("position", allowed = true) {
        IslandPositionControls(
            tuning = IslandTuning(offsetX = 3f, offsetY = -1f, scale = 1.1f),
            onChange = {},
            onDone = {},
            screen = centered(),
        )
    }
}

/** Phone shapes for the island's tests: 412 × 915 dp at 3x, with a 44 dp status bar. */
internal object IslandPhone {
    const val density = 3f
    const val widthDp = 412f
    const val heightDp = 915f
    const val statusBarDp = 44f

    fun punchHole(centerXdp: Float, centerYdp: Float = 22f, diameterDp: Float = 14f): IslandScreen {
        val d = density
        val r = diameterDp * d / 2
        val cx = centerXdp * d
        val cy = centerYdp * d
        return IslandScreen(
            (widthDp * d).toInt(), (heightDp * d).toInt(), d, (statusBarDp * d).toInt(),
            listOf(PxRect(cx - r, cy - r, cx + r, cy + r)),
        )
    }

    fun notch(): IslandScreen {
        val d = density
        return IslandScreen(
            (widthDp * d).toInt(), (heightDp * d).toInt(), d, (statusBarDp * d).toInt(),
            listOf(PxRect((206f - 78f) * d, 0f, (206f + 78f) * d, 32f * d)),
        )
    }

    fun plain(): IslandScreen {
        val d = density
        return IslandScreen((widthDp * d).toInt(), (heightDp * d).toInt(), d, (24f * d).toInt())
    }

    fun friends(): List<FriendSlime> = listOf(
        FriendSlime("f-lina", "Pip", "Lina", Skin.bubblegum, Stage.young, Mood.idle, Accessories(hat = "hat.party")),
        FriendSlime("f-noah", "Bloop", "Noah", Skin.ocean, Stage.baby, Mood.idle),
    )
}

/** A calm wallpaper: flat shapes, no gradients. */
@Composable
private fun Wallpaper(dark: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        val a = if (dark) Color(0xFF1B2850) else Color(0xFFD7CFC0)
        val b = if (dark) Color(0xFF2B1F4A) else Color(0xFFC9D8CC)
        val c = if (dark) Color(0xFF16324A) else Color(0xFFE2C9B8)
        drawCircle(a, radius = size.width * 0.55f, center = Offset(size.width * 0.05f, size.height * 0.95f))
        drawCircle(b, radius = size.width * 0.42f, center = Offset(size.width * 0.95f, size.height * 0.15f))
        drawCircle(c, radius = size.width * 0.28f, center = Offset(size.width * 0.75f, size.height * 1.05f))
    }
}

/** The status bar's clock and icons, beside the camera. */
@Composable
private fun StatusBar(dark: Boolean, screen: IslandScreen) {
    val ink = if (dark) Color.White else Color(0xFF1C1B1F)
    Row(
        Modifier
            .fillMaxWidth()
            .height((screen.statusBar / screen.density).dp)
            .padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("9:41", color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Box(Modifier.weight(1f))
        Canvas(Modifier.size(width = 52.dp, height = 14.dp)) {
            val u = size.height
            // Signal bars.
            for (i in 0 until 4) {
                val h = u * (0.35f + i * 0.2f)
                drawRoundRect(ink, Offset(i * u * 0.32f, u - h), Size(u * 0.22f, h), CornerRadius(u * 0.06f))
            }
            // Battery.
            val left = u * 1.75f
            drawRoundRect(ink, Offset(left, u * 0.12f), Size(u * 1.7f, u * 0.76f), CornerRadius(u * 0.2f), style = Stroke(u * 0.1f))
            drawRoundRect(ink, Offset(left + u * 0.16f, u * 0.28f), Size(u * 1.15f, u * 0.44f), CornerRadius(u * 0.1f))
        }
    }
}

/** The front camera itself, drawn over the island like the real lens. */
@Composable
private fun Lens(hole: PxRect, density: Float) {
    val d = 11f
    Box(
        Modifier.padding(
            start = (hole.centerX / density - d / 2).dp,
            top = (hole.centerY / density - d / 2).dp,
        ),
    ) {
        Canvas(Modifier.size(d.dp)) {
            drawCircle(Color(0xFF0B0B0F))
            drawCircle(Color(0xFF1C2230), radius = size.minDimension * 0.32f)
            drawCircle(Color(0xFF2E3A55), radius = size.minDimension * 0.1f, center = Offset(size.width * 0.38f, size.height * 0.38f))
        }
    }
}

@Config(application = ScreenshotApp::class, sdk = [35], qualifiers = "$PHONE-notnight-xxhdpi")
class LightIslandScreenshots : IslandScreenshotSuite("light")

@Config(application = ScreenshotApp::class, sdk = [35], qualifiers = "$PHONE-night-xxhdpi")
class DarkIslandScreenshots : IslandScreenshotSuite("dark")
