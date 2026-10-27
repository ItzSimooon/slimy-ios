package de.hyprnode.slimy.platform.island

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Where the island goes: pure geometry, no Android types, so every phone
// shape (centred, left and right punch-holes, notches, no cutout) can be
// tested on the JVM. Everything is in screen pixels; dp values are turned
// into pixels with the screen's density.

/** A rectangle in screen pixels. */
data class PxRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    fun offset(dx: Float, dy: Float) = PxRect(left + dx, top + dy, right + dx, bottom + dy)

    fun union(other: PxRect) = PxRect(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))

    /** Grown by [x] on the sides and [bottom] below (never above: the island hangs from the top). */
    fun inflate(x: Float, bottom: Float) = PxRect(left - x, top, right + x, this.bottom + bottom)

    fun contains(other: PxRect): Boolean =
        other.left >= left - 0.5f && other.top >= top - 0.5f && other.right <= right + 0.5f && other.bottom <= bottom + 0.5f

    /** Whole pixels, outward, for a window. */
    fun rounded(): PxRect = PxRect(floor(left), floor(top), kotlin.math.ceil(right), kotlin.math.ceil(bottom))

    companion object {
        /** The rectangle between [a] and [b] at [t] (0 = a, 1 = b; beyond 1 overshoots). */
        fun lerp(a: PxRect, b: PxRect, t: Float) = PxRect(
            a.left + (b.left - a.left) * t,
            a.top + (b.top - a.top) * t,
            a.right + (b.right - a.right) * t,
            a.bottom + (b.bottom - a.bottom) * t,
        )
    }
}

/** The screen as the overlay sees it: size, density, status bar and the camera cutouts. */
data class IslandScreen(
    val width: Int,
    val height: Int,
    val density: Float,
    /** Height of the status bar in pixels, whether it is showing or not. */
    val statusBar: Int,
    /** `DisplayCutout.getBoundingRects()`, in screen pixels. Empty without a cutout. */
    val cutouts: List<PxRect> = emptyList(),
) {
    val isPortrait: Boolean get() = height >= width

    /** The same screen [factor] times as big, density included (for small previews of the island). */
    fun scaled(factor: Float) = IslandScreen(
        (width * factor).roundToInt(),
        (height * factor).roundToInt(),
        density * factor,
        (statusBar * factor).roundToInt(),
        cutouts.map { PxRect(it.left * factor, it.top * factor, it.right * factor, it.bottom * factor) },
    )
}

/**
 * The person's fine-tuning for phones whose cutout report is a little off:
 * a nudge in dp and a size factor. Saved by `IslandController`.
 */
data class IslandTuning(val offsetX: Float = 0f, val offsetY: Float = 0f, val scale: Float = 1f) {
    /** The same tuning inside the allowed ranges. */
    fun clamped() = IslandTuning(
        offsetX.coerceIn(-maxOffsetX, maxOffsetX).finiteOr(0f),
        offsetY.coerceIn(-maxOffsetUp, maxOffsetDown).finiteOr(0f),
        scale.coerceIn(minScale, maxScale).finiteOr(1f),
    )

    val isDefault: Boolean get() = this == IslandTuning()

    companion object {
        const val maxOffsetX = 60f
        const val maxOffsetUp = 20f
        const val maxOffsetDown = 40f
        const val minScale = 0.8f
        const val maxScale = 1.3f

        private fun Float.finiteOr(fallback: Float) = if (isFinite()) this else fallback
    }
}

/** What kind of camera the island wraps. */
enum class CameraKind {
    /** A round punch-hole: the island is a pill around it. */
    punchHole,

    /** A wide notch: the island hangs from the top edge around it. */
    notch,

    /** No cutout reported: the island floats just below the status bar. */
    none,
}

/** Where the slime and the badge sit around the camera. */
enum class IslandLayout {
    /** Slime left of the camera, badge right of it (centred punch-holes, notches, no cutout). */
    centered,

    /** The camera in the pill's left end, slime and badge to its right (a punch-hole near the left edge). */
    cameraStart,

    /** The camera in the pill's right end, slime and badge to its left (a punch-hole near the right edge). */
    cameraEnd,
}

/** The three shapes the island takes. */
enum class IslandMode { compact, expanded, menu }

/**
 * Where everything goes, in screen pixels: the compact pill around the
 * camera, the slime's and the badge's spots inside it, the window that takes
 * touches, and the expanded panel and the menu that grow from the pill.
 */
data class IslandPlacement(
    val camera: CameraKind,
    val layout: IslandLayout,
    /** The camera lens as the island keeps clear of it; null without a cutout. */
    val hole: PxRect?,
    /** The compact island. */
    val pill: PxRect,
    /** Square top corners: the island hangs from the top edge (a notch). */
    val flushTop: Boolean,
    /** Corner radius of the compact island. */
    val pillRadius: Float,
    /** The slime's box in the compact island (bottom-aligned art). */
    val leading: PxRect,
    /** The badge's box: an emote, a pixel item, or the friend sharing a moment. */
    val trailing: PxRect,
    /** Height of the compact slime in pixels: a whole multiple of the art's height, so it stays crisp. */
    val slimePx: Int,
    /** The window while compact: the pill, reaching below the status bar so a tap lands. */
    val touch: PxRect,
    /** The expanded panel. */
    val expanded: PxRect,
    /** Where the expanded panel's own content starts (below the camera). */
    val expandedBodyTop: Float,
    /** Corner radius of the expanded panel and the menu. */
    val panelRadius: Float,
    /** The long-press menu. */
    val menu: PxRect,
    /** Where the menu's rows start. */
    val menuBodyTop: Float,
    val density: Float,
) {
    /** The open shape for a mode (the pill when compact). */
    fun target(mode: IslandMode): PxRect = when (mode) {
        IslandMode.compact -> pill
        IslandMode.expanded -> expanded
        IslandMode.menu -> menu
    }

    /**
     * The window for a mode: the open shape plus room for the spring's
     * overshoot, always including the compact touch area so nothing jumps
     * while it grows or shrinks.
     */
    fun window(mode: IslandMode): PxRect {
        if (mode == IslandMode.compact) return touch.rounded()
        val slack = overshoot * density
        return target(mode).inflate(slack, slack).union(touch).rounded()
    }

    companion object {
        /** Extra window room around an open island for the spring's overshoot (dp). */
        const val overshoot = 8f

        /** The slime art's height in art pixels (SlimeSprites.height). */
        const val artHeight = 20

        /** The slime art's width over its height (24 × 20). */
        const val artAspect = 24f / 20f

        // Sizes in dp, close to the Dynamic Island's proportions.
        private const val ringAroundLens = 7f
        private const val minHeight = 24f
        private const val maxHeight = 30f
        private const val floorHeight = 22f
        /** How far the island stays inside the status bar's height (dp). */
        private const val statusBarInset = 4f
        private const val topGap = 2f
        private const val freeHeight = 28f
        private const val belowStatusBar = 6f
        private const val edgeMargin = 6f
        private const val contentGap = 7f
        private const val touchReach = 14f
        private const val panelMargin = 8f
        private const val panelMaxWidth = 420f
        private const val menuWidth = 224f
        private const val panelRadiusDp = 36f

        /**
         * Places the island on [screen]. [expandedBody] and [menuBody] are the
         * heights (dp) of the expanded panel's and the menu's content below
         * the camera strip.
         */
        fun of(
            screen: IslandScreen,
            tuning: IslandTuning = IslandTuning(),
            expandedBody: Float = IslandMetrics.expandedBody,
            menuBody: Float = IslandMetrics.menuBody,
        ): IslandPlacement {
            val d = screen.density
            fun dp(v: Float) = v * d
            val t = tuning.clamped()
            val w = screen.width.toFloat()
            val camera = pickCutout(screen)
            val kind = when {
                camera == null -> CameraKind.none
                camera.width >= camera.height * 2.2f -> CameraKind.notch
                else -> CameraKind.punchHole
            }
            val dx = dp(t.offsetX)
            val dy = dp(t.offsetY)

            // The lens and the island's height around it.
            val hole: PxRect?
            var height: Float
            var top: Float
            when (kind) {
                CameraKind.punchHole -> {
                    val c = camera!!
                    // Some phones report the hole as a bar from the top edge down to it.
                    val diameter: Float
                    val cy: Float
                    if (c.top <= dp(2f) && c.height > c.width * 1.3f) {
                        diameter = c.width
                        cy = c.bottom - diameter / 2
                    } else {
                        diameter = max(c.width, c.height)
                        cy = c.centerY
                    }
                    val cx = c.centerX + dx
                    val y = cy + dy
                    hole = PxRect(cx - diameter / 2, y - diameter / 2, cx + diameter / 2, y + diameter / 2)
                    height = (diameter + dp(2 * ringAroundLens)).coerceIn(dp(minHeight), dp(maxHeight))
                    // Like the Dynamic Island, it stays within the status bar's
                    // height instead of hanging below it.
                    if (screen.statusBar > 0) height = min(height, screen.statusBar - dp(statusBarInset))
                    // Centred on the lens, but not glued to the top edge.
                    height = min(height, 2 * (y - dp(topGap)))
                    height = max(height, max(dp(floorHeight), diameter + dp(8f)))
                    height *= t.scale
                    top = y - height / 2
                }
                CameraKind.notch -> {
                    val c = camera!!.offset(dx, 0f)
                    hole = c
                    // Never shorter than the notch itself; nudging down makes it taller.
                    height = max(c.bottom, dp(24f)) * max(1f, t.scale) + dy.coerceAtLeast(0f)
                    top = 0f
                }
                CameraKind.none -> {
                    hole = null
                    height = dp(freeHeight) * t.scale
                    top = screen.statusBar + dp(belowStatusBar) + dy
                }
            }
            top = max(0f, top)
            val bottom = top + height

            // The slime: about 60 % of the island's height, whole art pixels only.
            val slimePx = crispSlime(height)
            val slimeW = slimePx * artAspect
            val pad = height * 0.3f
            val gap = dp(contentGap)
            val zone = pad + slimeW + gap
            // The black space around the camera, like the Dynamic Island's sensor housing.
            val clear = when (kind) {
                CameraKind.punchHole -> max(hole!!.width + dp(8f), height * 1.6f)
                CameraKind.notch -> hole!!.width
                CameraKind.none -> height * 1.2f
            }
            val centerX = when (kind) {
                CameraKind.none -> w / 2 + dx
                else -> hole!!.centerX
            }

            val margin = dp(edgeMargin)
            val fitsCentered = centerX - clear / 2 - zone >= margin && centerX + clear / 2 + zone <= w - margin
            val layout = when {
                kind != CameraKind.punchHole || fitsCentered -> IslandLayout.centered
                centerX < w / 2 -> IslandLayout.cameraStart
                else -> IslandLayout.cameraEnd
            }

            // The art is bottom-aligned with empty rows on top: centring the frame a little
            // above its middle puts the slime's body (and its hat) in the middle of the pill.
            val slimeTop = (top + height / 2 - slimePx * 0.6f).coerceAtLeast(top)
            val pill: PxRect
            val leading: PxRect
            val trailing: PxRect
            when (layout) {
                IslandLayout.centered -> {
                    pill = PxRect(centerX - clear / 2 - zone, top, centerX + clear / 2 + zone, bottom)
                    leading = PxRect(pill.left + pad, slimeTop, pill.left + pad + slimeW, slimeTop + slimePx)
                    trailing = PxRect(pill.right - pad - slimeW, slimeTop, pill.right - pad, slimeTop + slimePx)
                }
                IslandLayout.cameraStart -> {
                    // The lens sits in the rounded left end.
                    val left = max(dp(2f), centerX - height / 2)
                    val contentLeft = max(left + height, hole!!.right + gap)
                    pill = PxRect(left, top, contentLeft + slimeW + gap + slimeW + pad, bottom)
                    leading = PxRect(contentLeft, slimeTop, contentLeft + slimeW, slimeTop + slimePx)
                    trailing = PxRect(pill.right - pad - slimeW, slimeTop, pill.right - pad, slimeTop + slimePx)
                }
                IslandLayout.cameraEnd -> {
                    val right = min(w - dp(2f), centerX + height / 2)
                    val contentRight = min(right - height, hole!!.left - gap)
                    pill = PxRect(contentRight - slimeW - gap - slimeW - pad, top, right, bottom)
                    leading = PxRect(pill.left + pad, slimeTop, pill.left + pad + slimeW, slimeTop + slimePx)
                    trailing = PxRect(contentRight - slimeW, slimeTop, contentRight, slimeTop + slimePx)
                }
            }

            // Android draws the status bar above other apps' windows and gives it
            // the touches in that strip. With the island inside it, the window
            // reaches a little below the status bar, so a tap on the island's
            // lower edge still opens it.
            val reach = if (kind == CameraKind.none) bottom else max(bottom, screen.statusBar + dp(touchReach))
            val touch = PxRect(pill.left, pill.top, pill.right, min(reach, bottom + dp(48f)))

            // The panel and the menu grow from the pill, their content below the camera.
            val strip = max(bottom, (hole?.bottom ?: bottom) + dp(4f))
            val panelWidth = min(w - 2 * dp(panelMargin), dp(panelMaxWidth))
            val panelLeft = ((w - panelWidth) / 2).coerceIn(0f, w - panelWidth)
            val bodyTop = strip + dp(2f)
            val expanded = PxRect(panelLeft, top, panelLeft + panelWidth, bodyTop + dp(expandedBody))
            val menuW = max(pill.width, dp(menuWidth)).coerceAtMost(w - 2 * dp(panelMargin))
            val menuLeft = (pill.centerX - menuW / 2).coerceIn(dp(panelMargin), w - dp(panelMargin) - menuW)
            val menu = PxRect(menuLeft, top, menuLeft + menuW, bodyTop + dp(menuBody))

            return IslandPlacement(
                camera = kind,
                layout = layout,
                hole = hole,
                pill = pill,
                flushTop = kind == CameraKind.notch,
                pillRadius = if (kind == CameraKind.notch) min(height / 2, dp(18f)) else height / 2,
                leading = leading,
                trailing = trailing,
                slimePx = slimePx,
                touch = touch,
                expanded = expanded,
                expandedBodyTop = bodyTop,
                panelRadius = dp(panelRadiusDp),
                menu = menu,
                menuBodyTop = bodyTop,
                density = d,
            )
        }

        /**
         * The cutout the island wraps: one at the top of the screen, the one
         * nearest the middle if there are several.
         */
        fun pickCutout(screen: IslandScreen): PxRect? {
            val w = screen.width.toFloat()
            return screen.cutouts
                .filter { !it.isEmpty && it.top < screen.height * 0.15f && it.width < w * 0.9f && it.left < w && it.right > 0 }
                .minByOrNull { abs(it.centerX - w / 2) }
        }

        /**
         * The compact slime's height for an island [height] pixels tall: about
         * 72 % (the frame has empty rows above a young slime), in whole art pixels.
         */
        fun crispSlime(height: Float): Int {
            val scale = (height * 0.72f / artHeight).roundToInt()
            val fitting = min(scale, floor(height * 0.8f / artHeight).toInt())
            return max(1, fitting) * artHeight
        }
    }
}
