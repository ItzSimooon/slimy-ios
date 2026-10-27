package de.hyprnode.slimy.platform.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the island goes on every kind of phone: pure geometry, in screen pixels. */
class IslandPlacementTest {
    private val d = 3f
    private val width = 1236
    private val height = 2745
    private val statusBar = (44 * d).toInt()

    private fun dp(v: Float) = v * d

    /** A round punch-hole [diameter] dp wide, centred at ([cx], [cy]) dp. */
    private fun punchHole(cx: Float, cy: Float = 22f, diameter: Float = 14f): IslandScreen {
        val r = dp(diameter) / 2
        return IslandScreen(width, height, d, statusBar, listOf(PxRect(dp(cx) - r, dp(cy) - r, dp(cx) + r, dp(cy) + r)))
    }

    private fun assertNear(expected: Float, actual: Float, tolerance: Float = 1.5f) =
        assertEquals(expected, actual, tolerance)

    private fun assertInsideScreen(rect: PxRect) {
        assertTrue("left ${rect.left}", rect.left >= -0.5f)
        assertTrue("top ${rect.top}", rect.top >= -0.5f)
        assertTrue("right ${rect.right}", rect.right <= width + 0.5f)
    }

    @Test
    fun centredPunchHole() {
        val p = IslandPlacement.of(punchHole(206f))
        assertEquals(CameraKind.punchHole, p.camera)
        assertEquals(IslandLayout.centered, p.layout)
        val hole = p.hole!!
        // Around the lens: centred on it both ways, and the lens is inside.
        assertNear(hole.centerX, p.pill.centerX)
        assertNear(hole.centerY, p.pill.centerY)
        assertTrue(p.pill.contains(hole))
        // Slim like the Dynamic Island, a perfect capsule within the status bar.
        assertTrue(p.pill.height in dp(24f)..dp(30f))
        assertTrue(p.pill.height <= statusBar - dp(4f) + 0.5f)
        assertEquals(p.pill.height / 2, p.pillRadius, 0.01f)
        assertFalse(p.flushTop)
        // Slime left of the camera, badge right of it, both clear of the lens.
        assertTrue(p.leading.right < hole.left)
        assertTrue(p.trailing.left > hole.right)
        assertTrue(p.pill.contains(p.leading))
        assertTrue(p.pill.contains(p.trailing))
        // Balanced: as much room on each side.
        assertNear(hole.left - p.pill.left, p.pill.right - hole.right)
        assertInsideScreen(p.pill)
    }

    @Test
    fun slimeIsWholeArtPixels() {
        val p = IslandPlacement.of(punchHole(206f))
        assertEquals(0, p.slimePx % IslandPlacement.artHeight)
        assertNear(p.slimePx.toFloat(), p.leading.height, 0.01f)
        assertNear(p.slimePx * IslandPlacement.artAspect, p.leading.width, 0.01f)
        for (h in 60..140) {
            val s = IslandPlacement.crispSlime(h.toFloat())
            assertEquals(0, s % IslandPlacement.artHeight)
            assertTrue("slime $s in island $h", s <= h * 0.8f + 0.01f)
        }
    }

    @Test
    fun touchReachesBelowTheStatusBar() {
        // Android keeps the status bar above other apps' windows: the island's
        // window reaches a little below it so a tap lands.
        val p = IslandPlacement.of(punchHole(206f))
        assertTrue(p.pill.bottom < statusBar)
        assertEquals(p.pill.left, p.touch.left, 0f)
        assertEquals(p.pill.right, p.touch.right, 0f)
        assertEquals(p.pill.top, p.touch.top, 0f)
        assertTrue(p.touch.bottom > statusBar)
        assertTrue(p.touch.bottom <= statusBar + dp(14f) + 0.5f)
        // The compact window is exactly that area, so touches elsewhere pass through.
        assertEquals(p.touch.rounded(), p.window(IslandMode.compact))
    }

    @Test
    fun leftPunchHole() {
        val p = IslandPlacement.of(punchHole(30f))
        assertEquals(IslandLayout.cameraStart, p.layout)
        val hole = p.hole!!
        // The lens sits in the rounded left end; slime and badge follow to its right.
        assertTrue(p.pill.contains(hole))
        assertNear(hole.centerX, p.pill.left + p.pill.height / 2)
        assertTrue(p.leading.left > hole.right)
        assertTrue(p.trailing.left >= p.leading.right)
        assertInsideScreen(p.pill)
    }

    @Test
    fun rightPunchHole() {
        val p = IslandPlacement.of(punchHole(382f))
        assertEquals(IslandLayout.cameraEnd, p.layout)
        val hole = p.hole!!
        assertTrue(p.pill.contains(hole))
        assertNear(hole.centerX, p.pill.right - p.pill.height / 2)
        assertTrue(p.trailing.right < hole.left)
        assertTrue(p.leading.right <= p.trailing.left)
        assertInsideScreen(p.pill)
    }

    @Test
    fun slightlyOffCentreStillCentred() {
        // A hole a little left of the middle still gets slime and badge on either side.
        val p = IslandPlacement.of(punchHole(170f))
        assertEquals(IslandLayout.centered, p.layout)
        assertNear(p.hole!!.centerX, p.pill.centerX)
    }

    @Test
    fun holeReportedAsABarFromTheTop() {
        // Some phones report the cutout from the top edge down to the lens.
        val cx = dp(206f)
        val r = dp(7f)
        val bar = PxRect(cx - r, 0f, cx + r, dp(30f))
        val p = IslandPlacement.of(IslandScreen(width, height, d, statusBar, listOf(bar)))
        assertEquals(CameraKind.punchHole, p.camera)
        assertNear(dp(30f) - r, p.hole!!.centerY)
        assertNear(p.hole!!.centerY, p.pill.centerY)
    }

    @Test
    fun lensCloseToTheTopKeepsTheIslandOnScreen() {
        val p = IslandPlacement.of(punchHole(206f, cy = 12f, diameter = 10f))
        assertTrue(p.pill.top >= 0f)
        assertTrue(p.pill.height >= dp(22f) - 0.5f)
        assertTrue(p.pill.contains(p.hole!!))
    }

    @Test
    fun staysWithinAShortStatusBar() {
        // A typical phone: a 28 dp status bar and a small lens. The island must
        // not hang below the status bar (it looked bulky when it did).
        val bar = (28 * d).toInt()
        val r = dp(6f)
        val lens = PxRect(dp(206f) - r, dp(14f) - r, dp(206f) + r, dp(14f) + r)
        val p = IslandPlacement.of(IslandScreen(width, height, d, bar, listOf(lens)))
        assertTrue(p.pill.height <= bar - dp(4f) + 0.5f)
        assertTrue(p.pill.bottom <= bar + 0.5f)
        assertTrue(p.pill.contains(p.hole!!))
    }

    @Test
    fun notch() {
        val notch = PxRect(dp(128f), 0f, dp(284f), dp(32f))
        val p = IslandPlacement.of(IslandScreen(width, height, d, statusBar, listOf(notch)))
        assertEquals(CameraKind.notch, p.camera)
        assertTrue(p.flushTop)
        assertEquals(0f, p.pill.top, 0f)
        assertTrue(p.pill.contains(notch))
        assertTrue(p.leading.right <= notch.left)
        assertTrue(p.trailing.left >= notch.right)
        assertNear(notch.centerX, p.pill.centerX)
    }

    @Test
    fun noCutout() {
        val p = IslandPlacement.of(IslandScreen(width, height, d, statusBar))
        assertEquals(CameraKind.none, p.camera)
        assertNull(p.hole)
        // Just below the status bar, in the middle of the screen.
        assertNear(statusBar + dp(6f), p.pill.top)
        assertNear(width / 2f, p.pill.centerX)
        // Below the status bar it gets every touch already.
        assertEquals(p.pill, p.touch)
    }

    @Test
    fun picksTheTopCutoutNearestTheMiddle() {
        val far = PxRect(dp(20f), dp(10f), dp(40f), dp(30f))
        val near = PxRect(dp(200f), dp(15f), dp(214f), dp(29f))
        val bottom = PxRect(dp(200f), height - dp(30f), dp(214f), height - dp(10f))
        val screen = IslandScreen(width, height, d, statusBar, listOf(far, bottom, near))
        assertEquals(near, IslandPlacement.pickCutout(screen))
        // Only a cutout at the bottom (upside down): no camera at the top.
        assertNull(IslandPlacement.pickCutout(IslandScreen(width, height, d, statusBar, listOf(bottom))))
    }

    @Test
    fun tuningMovesAndSizesTheIsland() {
        val base = IslandPlacement.of(punchHole(206f))
        val moved = IslandPlacement.of(punchHole(206f), IslandTuning(offsetX = 10f, offsetY = 4f))
        assertNear(base.pill.centerX + dp(10f), moved.pill.centerX)
        assertNear(base.pill.centerY + dp(4f), moved.pill.centerY)
        assertNear(base.pill.height, moved.pill.height)
        val bigger = IslandPlacement.of(punchHole(206f), IslandTuning(scale = 1.2f))
        assertNear(base.pill.height * 1.2f, bigger.pill.height)
        assertTrue(bigger.pill.width > base.pill.width)
        assertNear(base.pill.centerX, bigger.pill.centerX)
    }

    @Test
    fun tuningIsClamped() {
        val t = IslandTuning(offsetX = 500f, offsetY = -500f, scale = 9f).clamped()
        assertEquals(IslandTuning.maxOffsetX, t.offsetX, 0f)
        assertEquals(-IslandTuning.maxOffsetUp, t.offsetY, 0f)
        assertEquals(IslandTuning.maxScale, t.scale, 0f)
        // A broken saved value never moves the island off the screen.
        assertEquals(
            IslandTuning(0f, IslandTuning.maxOffsetDown, 1f),
            IslandTuning(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN).clamped(),
        )
        assertTrue(IslandTuning().isDefault)
        assertFalse(IslandTuning(scale = 1.1f).isDefault)
    }

    @Test
    fun panelsGrowFromThePill() {
        val p = IslandPlacement.of(punchHole(206f))
        // The expanded panel starts where the pill starts, is wider, centred, and fits the screen.
        assertEquals(p.pill.top, p.expanded.top, 0f)
        assertTrue(p.expanded.left <= p.pill.left && p.expanded.right >= p.pill.right)
        assertNear(width / 2f, p.expanded.centerX)
        assertInsideScreen(p.expanded)
        assertTrue(p.expanded.width <= dp(420f) + 0.5f)
        // Its content starts below the camera.
        assertTrue(p.expandedBodyTop >= p.hole!!.bottom)
        assertNear(p.expandedBodyTop + dp(IslandMetrics.expandedBody), p.expanded.bottom)
        // The menu hangs from the pill too.
        assertEquals(p.pill.top, p.menu.top, 0f)
        assertNear(p.pill.centerX, p.menu.centerX)
        assertInsideScreen(p.menu)
        // An open window holds the open shape and the compact touch area, with room for the spring.
        for (mode in listOf(IslandMode.expanded, IslandMode.menu)) {
            val w = p.window(mode)
            assertTrue(w.contains(p.target(mode)))
            assertTrue(w.contains(p.touch))
        }
        assertEquals(p.pill, p.target(IslandMode.compact))
    }

    @Test
    fun menuStaysOnScreenNextToASideHole() {
        val p = IslandPlacement.of(punchHole(30f))
        assertInsideScreen(p.menu)
        assertTrue(p.menu.left >= dp(8f) - 0.5f)
        assertInsideScreen(p.expanded)
    }

    @Test
    fun scaledScreen() {
        val screen = punchHole(206f)
        val half = screen.scaled(0.5f)
        assertEquals(width / 2, half.width)
        assertEquals(1.5f, half.density, 0f)
        assertEquals(screen.cutouts[0].centerX / 2, half.cutouts[0].centerX, 0.01f)
        // The same island, half as big.
        val p = IslandPlacement.of(screen)
        val q = IslandPlacement.of(half)
        assertNear(p.pill.centerX / 2, q.pill.centerX)
        assertNear(p.pill.height / 2, q.pill.height)
        assertNotNull(q.hole)
    }

    @Test
    fun portrait() {
        assertTrue(IslandScreen(1080, 2400, 3f, 100).isPortrait)
        assertFalse(IslandScreen(2400, 1080, 3f, 100).isPortrait)
    }

    @Test
    fun rectHelpers() {
        val a = PxRect(0f, 0f, 10f, 10f)
        val b = PxRect(10f, 10f, 30f, 20f)
        assertEquals(PxRect(5f, 5f, 20f, 15f), PxRect.lerp(a, b, 0.5f))
        assertEquals(PxRect(0f, 0f, 30f, 20f), a.union(b))
        assertEquals(PxRect(-2f, 0f, 12f, 13f), a.inflate(2f, 3f))
        assertEquals(PxRect(0f, 0f, 11f, 11f), PxRect(0.4f, 0.6f, 10.2f, 10.5f).rounded())
    }
}
