package de.hyprnode.slimy.art

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import de.hyprnode.slimy.model.Challenges
import de.hyprnode.slimy.model.FurnitureSlot
import de.hyprnode.slimy.model.Habitat
import de.hyprnode.slimy.model.ItemSprite
import de.hyprnode.slimy.model.Season
import de.hyprnode.slimy.model.SlimyCalendar
import de.hyprnode.slimy.model.SplitMix
import de.hyprnode.slimy.model.argb
import de.hyprnode.slimy.model.rounded
import java.time.Instant
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** `0xRRGGBB` as an opaque Compose colour. */
internal fun rgb(hex: Long): Color = Color(argb(hex))

/** Like SwiftUI's `Color.opacity(_:)`: multiplies the colour's alpha. */
internal fun Color.opacity(amount: Double): Color =
    copy(alpha = (alpha * amount.toFloat()).coerceIn(0f, 1f))

/** Weather of the day, the same for everyone at the same hour (and on iPhone). */
enum class Weather {
    clear, cloudy, rain, snow;

    companion object {
        fun at(date: Instant, calendar: SlimyCalendar = SlimyCalendar.current): Weather {
            val g = SlimyCalendar.gregorian(like = calendar)
            val block = g.hour(date) / 3
            val rng = SplitMix(SplitMix.seed(Challenges.dayKey(date, g) + "w$block"))
            val roll = rng.unit()
            return when (g.month(date)) {
                12 -> {
                    // December snows more often.
                    if (roll < 0.4) clear else if (roll < 0.55) cloudy else snow
                }
                1, 2 -> if (roll < 0.6) clear else if (roll < 0.8) cloudy else snow
                else -> if (roll < 0.6) clear else if (roll < 0.8) cloudy else rain
            }
        }
    }
}

/** Light over the course of a day: 1 at noon, ~0.25 at night. */
class DayLight(date: Instant, calendar: SlimyCalendar = SlimyCalendar.current) {
    val hour: Double = calendar.hour(date) + calendar.minute(date) / 60.0

    val isNight: Boolean get() = hour < 6 || hour >= 20.5

    val level: Double
        get() = when {
            hour >= 7 && hour < 18 -> 1.0
            hour >= 5 && hour < 7 -> 0.25 + (hour - 5) / 2 * 0.75
            hour >= 18 && hour < 21 -> 1 - (hour - 18) / 3 * 0.75
            else -> 0.25
        }

    val sky: Color
        get() = when {
            hour >= 5 && hour < 7 -> rgb(0xF2A98A)
            hour >= 7 && hour < 17 -> rgb(0x8FD3F2)
            hour >= 17 && hour < 19 -> rgb(0xF2B37A)
            hour >= 19 && hour < 21 -> rgb(0x7A5A9A)
            else -> rgb(0x1C2240)
        }

    val skyLow: Color
        get() = when {
            hour >= 5 && hour < 7 -> rgb(0xF7D2A6)
            hour >= 7 && hour < 17 -> rgb(0xC6EBF7)
            hour >= 17 && hour < 19 -> rgb(0xF7D7A0)
            hour >= 19 && hour < 21 -> rgb(0xC27A8A)
            else -> rgb(0x2B3260)
        }
}

/**
 * The slime's world, drawn as pixel art on a 96-pixel-wide grid and scaled
 * by whole device pixels so every pixel stays crisp. Screens that aren't a
 * whole multiple of 96 pixels wide see a little more of the world at the
 * sides, so the scene always reaches from edge to edge.
 */
object HabitatScene {
    const val gridWidth = 96

    /** Device pixels per world pixel for a scene this wide (in pixels); never under 2 dp. */
    fun scale(forWidth: Float, density: Float): Float = max(floor(2f * density).coerceAtLeast(1f), floor(forWidth / gridWidth))

    /** The row where the floor or ground begins, in a scene this many rows tall. */
    fun floorRow(rows: Int): Int = (rows.toDouble() * 0.66).toInt()

    /** A rectangle on the pixel grid. */
    data class Box(val x: Int, val y: Int, val w: Int, val h: Int)

    /** World facts for one moment. */
    class WorldInfo(
        val date: Instant,
        val t: Double,
        val rows: Int,
        /** First column on screen and one past the last; wider than the 96-pixel world on screens with room to spare. */
        val minX: Int,
        val maxX: Int,
    ) {
        val light = DayLight(date)
        val weather = Weather.at(date)
        val season = Season.at(date)

        val span: Int get() = maxX - minX

        /** Row where the floor or ground begins. */
        val floorY: Int get() = floorRow(rows)
    }
}

/**
 * The slime's home. A scene of "now" follows the clock (daylight, the
 * season); a scene of another moment stays at that moment. [animate] moves
 * waves, weather and critters ten times a second.
 */
@Composable
fun HabitatScene(
    habitat: Habitat,
    furniture: Map<FurnitureSlot, String>,
    date: Instant,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    /** The slime's birthday: bunting, a cake and confetti. */
    party: Boolean = false,
) {
    val live = abs(date.toEpochMilli() - System.currentTimeMillis()) < 60_000
    val clock: State<Long>? = when {
        animate -> rememberClock(100L)
        live -> rememberClock(60_000L)
        else -> null
    }
    val scene = HabitatPainter(habitat, furniture, party)
    Canvas(modifier.clearAndSetSemantics { }) {
        val now = clock?.value
        val at = if (live && now != null) Instant.ofEpochMilli(now) else date
        val t = if (animate && now != null) referenceSeconds(now) else 0.0
        scene.draw(this, at, t)
    }
}

/** Draws one habitat at one moment onto a canvas of any size. Also used for photos and share cards. */
class HabitatPainter(
    val habitat: Habitat,
    val furniture: Map<FurnitureSlot, String>,
    val party: Boolean = false,
) {
    /** The scene at one moment. [t] drives the motion; 0 holds it still. */
    fun draw(scope: DrawScope, at: Instant, t: Double) {
        val s = HabitatScene.scale(scope.size.width, scope.density)
        val rows = max(1, ceil(scope.size.height / s).toInt())
        val ox = floor((scope.size.width - s * HabitatScene.gridWidth) / 2)
        val margin = max(0, ceil(ox / s).toInt())
        val p = Painter(scope, s, ox, HabitatScene.gridWidth, rows)
        val world = HabitatScene.WorldInfo(at, t, rows, -margin, HabitatScene.gridWidth + margin)
        draw(p, world, scope)
    }

    private fun draw(p: Painter, w: HabitatScene.WorldInfo, scope: DrawScope) {
        when (habitat) {
            Habitat.room -> drawRoom(p, w)
            Habitat.garden -> drawGarden(p, w)
            Habitat.beach -> drawBeach(p, w)
            Habitat.space -> drawSpace(p, w)
            Habitat.crystal -> drawCrystal(p, w)
        }
        if (w.season == Season.halloween) drawBats(p, w)
        if (party) drawBunting(p, w)
        // Night falls over everything except things that glow.
        val light = w.light.level
        if (light < 1 && habitat != Habitat.space) {
            scope.drawRect(rgb(0x14183A).opacity((1 - light) * 0.55))
        }
        drawGlow(p, w)
        if (party) drawConfetti(p, w)
    }

    // MARK: Room

    private fun roomWindow(w: HabitatScene.WorldInfo) = HabitatScene.Box(54, max(8, w.floorY / 6), 32, 26)

    private class Spot(val x: Int, val y: Int, val standing: Boolean)

    /** Where a piece of furniture stands, and whether it stands on that row. */
    private fun spot(slot: FurnitureSlot, w: HabitatScene.WorldInfo): Spot {
        val window = roomWindow(w)
        return when (slot) {
            FurnitureSlot.ceiling -> Spot(0, 1, false)
            FurnitureSlot.wallLeft -> Spot(12, max(10, w.floorY / 3), false)
            FurnitureSlot.wallRight -> Spot(60, window.y + window.h + 6, false)
            FurnitureSlot.rug -> Spot(36, w.floorY + 14, false)
            FurnitureSlot.floorLeft -> Spot(3, w.floorY + 10, true)
            FurnitureSlot.floorRight -> Spot(82, w.floorY + 10, true)
        }
    }

    private fun drawRoom(p: Painter, w: HabitatScene.WorldInfo) {
        val floorY = w.floorY
        // Wall with a soft stripe
        p.rect(w.minX, 0, w.span, floorY, rgb(0xF0D2A8))
        for (x in Painter.stride(Painter.first(3, 8, w.minX), w.maxX, 8)) p.rect(x, 0, 3, floorY, rgb(0xE8C595))
        // Window with today's sky
        val win = roomWindow(w)
        drawSky(p, w, HabitatScene.Box(win.x + 2, win.y + 2, win.w - 4, win.h - 4))
        if (w.season == Season.christmas) {
            // Snow piled against the glass.
            for (x in (win.x + 2) until (win.x + win.w - 2)) {
                val h = 1 + (if (x * 7 % 5 == 0) 1 else 0)
                p.rect(x, win.y + win.h - 2 - h, 1, h, rgb(0xF4F8FF))
            }
        }
        p.frame(win.x, win.y, win.w, win.h, rgb(0x8A5A36), thickness = 2)
        p.rect(win.x + win.w / 2 - 1, win.y, 2, win.h, rgb(0x8A5A36))
        p.rect(win.x, win.y + win.h / 2 - 1, win.w, 2, rgb(0x8A5A36))
        p.rect(win.x - 2, win.y + win.h, win.w + 4, 2, rgb(0x6B4423))
        // Floor planks
        p.rect(w.minX, floorY, w.span, w.rows - floorY, rgb(0xC48A58))
        var row = 0
        for (y in Painter.stride(floorY + 3, w.rows, 5)) {
            p.rect(w.minX, y, w.span, 1, rgb(0xA8733F))
            val offset = if (row % 2 == 0) 0 else 12
            for (x in Painter.stride(Painter.first(offset, 24, w.minX), w.maxX, 24)) p.rect(x, y - 4, 1, 4, rgb(0xA8733F))
            row += 1
        }
        p.rect(w.minX, floorY - 2, w.span, 3, rgb(0x8A5A36))

        // Furniture
        for (slot in listOf(FurnitureSlot.ceiling, FurnitureSlot.wallLeft, FurnitureSlot.wallRight, FurnitureSlot.rug,
            FurnitureSlot.floorLeft, FurnitureSlot.floorRight)) {
            val id = furniture[slot] ?: continue
            if (slot == FurnitureSlot.ceiling) {
                for (x in Painter.stride(Painter.first(0, 32, w.minX), w.maxX, 32)) p.sprite(id, x, 1)
            } else {
                val at = spot(slot, w)
                if (at.standing) p.spriteBottom(id, at.x, at.y) else p.sprite(id, at.x, at.y)
            }
        }

        // Season, and the birthday cake, each in a spot of its own
        val leftFree = furniture[FurnitureSlot.floorLeft] == null
        val rightFree = furniture[FurnitureSlot.floorRight] == null
        val tree: Int? = if (w.season == Season.christmas) treeX() else null
        val cake: Int? = if (party && furniture[FurnitureSlot.floorRight] != "furn.cake") cakeX(tree) else null
        when (w.season) {
            Season.halloween -> {
                drawWeb(p, w.minX, 0)
                val left = if (leftFree && cake != 6) 7 else (if (cake == 22) 72 else 21)
                p.spriteBottom("hat.pumpkin", left, floorY + 11)
                if (rightFree && cake != 84) p.spriteBottom("hat.pumpkin", 84, floorY + 12)
            }
            Season.christmas -> drawTree(p, treeX(), floorY + 10)
            Season.newYear -> if (rightFree && cake != 84) p.spriteBottom("hat.party", 85, floorY + 11)
            Season.spring -> if (cake != 22) p.spriteBottom("flower", 26, floorY + 9)
            else -> Unit
        }
        if (cake != null) p.spriteBottom("cake", cake, floorY + 12)
    }

    /** Where the holiday tree stands: in a free corner if there is one. */
    private fun treeX(): Int {
        if (furniture[FurnitureSlot.floorLeft] == null) return 10
        if (furniture[FurnitureSlot.floorRight] == null) return 88
        return 22
    }

    /** Where the birthday cake stands: a free corner the tree didn't take, otherwise beside the slime. */
    private fun cakeX(tree: Int?): Int {
        if (furniture[FurnitureSlot.floorRight] == null && tree != 88) return 84
        if (furniture[FurnitureSlot.floorLeft] == null && tree != 10) return 6
        return if (tree == 22) 72 else 22
    }

    private fun drawTree(p: Painter, x: Int, bottom: Int) {
        val green = rgb(0x3C8F3A)
        val dark = rgb(0x2E7030)
        for (i in 0 until 5) {
            val width = 3 + i * 3
            p.rect(x - width / 2, bottom - 18 + i * 3, width, 3, green)
            p.rect(x - width / 2, bottom - 16 + i * 3, width, 1, dark)
        }
        p.rect(x - 1, bottom - 3, 3, 3, rgb(0x6B4423))
        p.rect(x, bottom - 21, 1, 3, rgb(0xFFD166))
        p.rect(x - 1, bottom - 20, 3, 1, rgb(0xFFD166))
    }

    private fun treeLights(p: Painter, x: Int, bottom: Int, t: Double, night: Boolean) {
        val lights = listOf(-2 to -14, 2 to -11, -4 to -8, 4 to -6, 0 to -9, -5 to -5, 5 to -12, 1 to -4)
        val colors = listOf(rgb(0xF07A9A), rgb(0xFFD166), rgb(0x7FD6F0))
        for ((i, d) in lights.withIndex()) {
            val (dx, dy) = d
            val on = (t * 2 + i).toInt() % 2 == 0
            val color = colors[(i + (if (on) 0 else 1)) % colors.size]
            if (night) p.glow(x + dx, bottom + dy, color) else p.rect(x + dx, bottom + dy, 1, 1, color)
        }
        if (night) p.glow(x, bottom - 20, rgb(0xFFD166))
    }

    /** A cobweb in the top corner, for October. */
    private fun drawWeb(p: Painter, x: Int, y: Int) {
        val silk = rgb(0xF4F1EA).opacity(0.8)
        for (dy in 0 until 10) {
            for (dx in 0 until 10) {
                val d = sqrt((dx * dx + dy * dy).toDouble())
                if (d >= 9.5) continue
                val angle = atan2(dy.toDouble(), dx.toDouble())
                val spoke = listOf(0.0, PI / 6, PI / 3, PI / 2).any { abs(angle - it) * d < 0.6 }
                val ring = abs(d - 3.5) < 0.5 || abs(d - 6.8) < 0.5
                if (spoke || ring) p.rect(x + dx, y + dy, 1, 1, silk)
            }
        }
        // A small spider, hanging by a thread.
        p.rect(x + 7, y + 7, 1, 3, silk)
        p.rect(x + 6, y + 10, 3, 2, rgb(0x2A1E3A))
    }

    // MARK: Garden

    private fun drawGarden(p: Painter, w: HabitatScene.WorldInfo) {
        val floorY = w.floorY
        val winter = w.season == Season.christmas
        drawSky(p, w, HabitatScene.Box(w.minX, 0, w.span, floorY))
        // Hills, snowed over in December
        val farHill = if (winter) rgb(0xDCE6F2) else rgb(0x8ACB6A)
        val nearHill = if (winter) rgb(0xC9D7E8) else rgb(0x6BB45A)
        for (x in w.minX until w.maxX) {
            val h1 = (8 + 5 * sin(x / 11.0)).toInt()
            p.rect(x, floorY - h1 - 6, 1, h1 + 6, farHill)
            val h2 = (5 + 3 * sin(x / 7.0 + 2)).toInt()
            p.rect(x, floorY - h2, 1, h2, nearHill)
        }
        // Tree
        p.rect(10, floorY - 22, 4, 22, rgb(0x7A4E2A))
        val leaves = when (w.season) {
            Season.autumn, Season.halloween -> rgb(0xE0893A)
            Season.christmas, Season.winter, Season.newYear -> rgb(0x3C7A44)
            else -> rgb(0x4FA34A)
        }
        for ((dx, dy, r) in listOf(Triple(-6, -30, 7), Triple(2, -34, 8), Triple(8, -28, 6))) {
            p.disc(12 + dx, floorY + dy, r, leaves)
            if (winter) p.rect(12 + dx - r / 2, floorY + dy - r, r, 1, rgb(0xF4F8FF))
        }
        // Fence
        for (x in Painter.stride(30, w.maxX, 7)) p.rect(x, floorY - 9, 2, 9, rgb(0xE8D9C0))
        p.rect(30, floorY - 7, w.maxX - 30, 1, rgb(0xD9C6A6))
        p.rect(30, floorY - 4, w.maxX - 30, 1, rgb(0xD9C6A6))
        // Grass, or snow in December
        p.rect(w.minX, floorY, w.span, w.rows - floorY, if (winter) rgb(0xEEF3FA) else rgb(0x79C05A))
        val rng = SplitMix(42uL)
        repeat(90 * w.span / 96) {
            val x = rng.int(w.minX..(w.maxX - 1))
            val y = rng.int(floorY..max(floorY, w.rows - 1))
            p.rect(x, y, if (winter) 2 else 1, 1, if (winter) rgb(0xD6E1EE) else rgb(0x95D370))
        }
        if (winter) {
            // A few tufts of grass poke through.
            repeat(12 * w.span / 96) {
                val x = rng.int(w.minX..(w.maxX - 1))
                val y = rng.int((floorY + 3)..max(floorY + 3, w.rows - 2))
                p.rect(x, y, 1, 1, rgb(0x6BB45A))
                p.rect(x + 1, y - 1, 1, 2, rgb(0x6BB45A))
            }
        } else {
            for (i in 0 until 9) {
                val x = rng.int(2..93)
                val y = rng.int((floorY + 4)..max(floorY + 4, w.rows - 4))
                val c = listOf(rgb(0xF07A9A), rgb(0xFFD166), rgb(0xFFFFFF))[i % 3]
                p.rect(x, y, 1, 1, c)
                p.rect(x - 1, y + 1, 3, 1, c)
                p.rect(x, y + 2, 1, 1, rgb(0x4FA34A))
            }
        }
        when (w.season) {
            Season.halloween -> {
                p.spriteBottom("hat.pumpkin", 70, floorY + 8)
                p.spriteBottom("hat.pumpkin", 20, floorY + 12)
            }
            Season.christmas -> drawSnowman(p, 86, floorY + 9)
            else -> Unit
        }
        if (party) p.spriteBottom("cake", 76, floorY + 12)
        // A butterfly by day, fireflies at night
        if (w.light.isNight) {
            if (!winter) {
                for (i in 0 until 5) {
                    val x = 20 + (36 * (1 + sin(w.t * 0.3 + i * 1.7))).toInt()
                    val y = floorY - 6 - (10 * (1 + sin(w.t * 0.5 + i))).toInt()
                    if ((w.t * 3 + i).toInt() % 3 != 0) p.glow(x, y, rgb(0xEAF77A))
                }
            }
        } else if (w.weather == Weather.clear && !winter) {
            val x = (48 + 40 * sin(w.t * 0.13)).toInt()
            val y = (floorY - 18 + 6 * sin(w.t * 0.7)).toInt()
            val flap = (w.t * 6).toInt() % 2 == 0
            p.rect(x, y, 1, 2, rgb(0x3A2B2E))
            p.rect(x - 2, y - (if (flap) 1 else 0), 2, 2, rgb(0xF07A9A))
            p.rect(x + 1, y - (if (flap) 1 else 0), 2, 2, rgb(0xF07A9A))
        }
        drawWeather(p, w, HabitatScene.Box(w.minX, 0, w.span, w.rows))
    }

    private fun drawSnowman(p: Painter, x: Int, bottom: Int) {
        val snow = rgb(0xFFFFFF)
        val shade = rgb(0xA9BCD3)
        // Outlined, so it stands out against the snow.
        p.disc(x, bottom - 5, 5, shade)
        p.disc(x, bottom - 5, 4, snow)
        p.rect(x + 1, bottom - 3, 3, 1, rgb(0xDCE6F2))
        p.disc(x, bottom - 11, 4, shade)
        p.disc(x, bottom - 11, 3, snow)
        p.rect(x - 1, bottom - 12, 1, 1, rgb(0x2A1E3A))
        p.rect(x + 1, bottom - 12, 1, 1, rgb(0x2A1E3A))
        p.rect(x, bottom - 11, 2, 1, rgb(0xF08A3C))
        p.rect(x - 3, bottom - 8, 7, 1, rgb(0xE85D5D))
        p.rect(x + 2, bottom - 7, 1, 2, rgb(0xE85D5D))
    }

    // MARK: Beach

    private fun drawBeach(p: Painter, w: HabitatScene.WorldInfo) {
        val floorY = w.floorY
        drawSky(p, w, HabitatScene.Box(w.minX, 0, w.span, floorY - 10))
        // Sea with moving waves
        p.rect(w.minX, floorY - 12, w.span, 12, rgb(0x3F8FCB))
        for (x in w.minX until w.maxX) {
            val wave = (1.5 * sin(x / 5.0 + w.t * 1.4)).toInt()
            p.rect(x, floorY - 12 + wave, 1, 1, rgb(0x7FC6EE))
            if (Painter.mod(x + (w.t * 4).toInt(), 13) == 0) p.rect(x, floorY - 2 + wave, 2, 1, Color.White)
        }
        // Sand
        p.rect(w.minX, floorY, w.span, w.rows - floorY, rgb(0xF2D49B))
        val rng = SplitMix(7uL)
        repeat(60 * w.span / 96) {
            val x = rng.int(w.minX..(w.maxX - 1))
            val y = rng.int(floorY..max(floorY, w.rows - 1))
            p.rect(x, y, 1, 1, rgb(0xE0BD7E))
        }
        // Palm
        for (i in 0 until 20) p.rect(84 - i / 4, floorY - i, 3, 1, rgb(0x9A6A3A))
        for ((dx, dy, len) in listOf(Triple(-10, -1, 10), Triple(0, -2, 10), Triple(-6, 2, 8), Triple(2, 2, 7))) {
            for (i in 0 until len) p.rect(79 + dx + i, floorY - 21 + dy + abs(i - len / 2) / 2, 1, 2, rgb(0x4FA34A))
        }
        p.sprite("bubbleItem", 14, floorY + 10)
        if (w.season == Season.halloween) p.spriteBottom("hat.pumpkin", 26, floorY + 12)
        if (party) p.spriteBottom("cake", 72, floorY + 12)
        // A crab walks by now and then
        val travel = (w.span + 60).toDouble()
        val cx = w.minX - 20 + ((w.t * 4) % travel).toInt()
        if (cx > w.minX - 4 && cx < w.maxX) {
            p.rect(cx, floorY + 18, 5, 2, rgb(0xE8524A))
            p.rect(cx - 1, floorY + 17, 1, 1, rgb(0xE8524A))
            p.rect(cx + 5, floorY + 17, 1, 1, rgb(0xE8524A))
        }
        drawWeather(p, w, HabitatScene.Box(w.minX, 0, w.span, w.rows))
    }

    // MARK: Space

    private fun drawSpace(p: Painter, w: HabitatScene.WorldInfo) {
        val floorY = w.floorY
        p.rect(w.minX, 0, w.span, w.rows, rgb(0x0B0E24))
        val rng = SplitMix(99uL)
        for (i in 0 until (70 * w.span / 96)) {
            val x = rng.int(w.minX..(w.maxX - 1))
            val y = rng.int(0..max(0, floorY))
            val twinkle = (w.t * 2 + i).toInt() % 5 == 0
            p.rect(x, y, 1, 1, Color.White.opacity(if (twinkle) 0.3 else 0.85))
        }
        // Ringed planet
        p.disc(72, 18, 9, rgb(0xB59BFF))
        p.disc(70, 16, 3, rgb(0xD4C6FF))
        p.rect(58, 18, 28, 2, rgb(0xF2D49B))
        // Shooting star, every so often
        val cycle = w.t % 12
        if (cycle < 1.2 && w.t > 0) {
            val k = (cycle * 40).toInt()
            for (i in 0 until 6) p.rect(10 + k - i, 6 + k / 2 - i / 2, 1, 1, Color.White.opacity(1 - i / 6.0))
        }
        // Moon ground
        p.rect(w.minX, floorY, w.span, w.rows - floorY, rgb(0x9AA0A8))
        for ((x, y, r) in listOf(Triple(14, floorY + 8, 4), Triple(52, floorY + 16, 3), Triple(80, floorY + 6, 2), Triple(30, floorY + 24, 3))) {
            p.disc(x, y, r, rgb(0x7C828A))
        }
        if (w.season == Season.halloween) p.spriteBottom("hat.pumpkin", 82, floorY + 12)
        if (party) p.spriteBottom("cake", 74, floorY + 12)
    }

    // MARK: Crystal cave

    private fun drawCrystal(p: Painter, w: HabitatScene.WorldInfo) {
        val floorY = w.floorY
        p.rect(w.minX, 0, w.span, w.rows, rgb(0x2A1E3A))
        val rng = SplitMix(5uL)
        repeat(120 * w.span / 96) {
            val x = rng.int(w.minX..(w.maxX - 1))
            val y = rng.int(0..max(0, w.rows - 1))
            p.rect(x, y, 2, 1, rgb(0x352848))
        }
        p.rect(w.minX, floorY, w.span, w.rows - floorY, rgb(0x3A2E4A))
        val pulse = 0.6 + 0.4 * sin(w.t * 1.5)
        for ((i, xh) in listOf(8 to 14, 20 to 9, 70 to 16, 86 to 10, 44 to 7).withIndex()) {
            val (x, h) = xh
            val c = if (i % 2 == 0) rgb(0xB59BFF) else rgb(0x7FD6F0)
            for (j in 0 until h) {
                val width = max(1, 4 - j / 4)
                p.rect(x - width / 2, floorY - j, width, 1, c.opacity(0.7 + 0.3 * pulse))
            }
        }
        for (x in listOf(30, 60)) {
            p.rect(x, floorY - 3, 1, 3, rgb(0xE9F1E4))
            p.rect(x - 2, floorY - 5, 5, 2, rgb(0x7FD6F0).opacity(pulse))
        }
        if (party) p.spriteBottom("cake", 76, floorY + 12)
    }

    // MARK: Sky, weather, seasons

    /** The open sky of this habitat, for bats and fireworks. */
    private fun skyBox(w: HabitatScene.WorldInfo): HabitatScene.Box = when (habitat) {
        Habitat.room -> {
            val win = roomWindow(w)
            HabitatScene.Box(win.x + 2, win.y + 2, win.w - 4, win.h - 4)
        }
        Habitat.garden -> HabitatScene.Box(w.minX, 0, w.span, max(6, w.floorY - 16))
        Habitat.beach -> HabitatScene.Box(w.minX, 0, w.span, max(6, w.floorY - 13))
        Habitat.space -> HabitatScene.Box(w.minX, 0, w.span, max(6, w.floorY - 2))
        Habitat.crystal -> HabitatScene.Box(w.minX, 0, w.span, max(6, w.floorY / 2))
    }

    private fun drawSky(p: Painter, w: HabitatScene.WorldInfo, box: HabitatScene.Box) {
        val light = w.light
        val x = box.x
        val y = box.y
        val width = box.w
        val height = box.h
        p.rect(x, y, width, height, light.sky)
        p.rect(x, y + height * 2 / 3, width, height - height * 2 / 3, light.skyLow)
        if (light.isNight) {
            val rng = SplitMix(3uL)
            repeat(max(4, width * height / 60)) {
                val sx = x + rng.int(0..max(0, width - 1))
                val sy = y + rng.int(0..max(0, height * 2 / 3))
                p.rect(sx, sy, 1, 1, Color.White.opacity(0.8))
            }
            p.disc(x + width - 8, y + 6, 3, rgb(0xF4EBD9))
            p.disc(x + width - 7, y + 5, 3, light.sky)
        } else if (w.weather == Weather.clear || w.weather == Weather.cloudy) {
            p.disc(x + 7, y + 7, 3, rgb(0xFFE38A))
        }
        if (w.weather != Weather.clear) {
            val drift = ((w.t * 1.5) % (width + 30).toDouble()).toInt() - 15
            val cloud = Color.White.opacity(if (w.weather == Weather.cloudy) 0.9 else 0.6)
            for ((cx, cy) in listOf(drift to 5, (drift + width / 2) to 9)) {
                val gx = x + Painter.mod(cx, width + 20) - 10
                p.rect(gx, y + cy, 10, 3, cloud)
                p.rect(gx + 2, y + cy - 2, 6, 2, cloud)
            }
        }
        if (habitat == Habitat.room) drawWeather(p.clipped(box), w, box)
    }

    private fun drawWeather(p: Painter, w: HabitatScene.WorldInfo, box: HabitatScene.Box) {
        val x = box.x
        val y = box.y
        val width = box.w
        val height = box.h
        when (w.weather) {
            Weather.rain -> for (i in 0 until (width * height / 50)) {
                val px = x + (i * 37) % max(1, width)
                val py = y + ((i * 53.0 + w.t * 60) % max(1, height).toDouble()).toInt()
                p.rect(px, py, 1, 2, rgb(0x9FD4F2).opacity(0.8))
            }
            Weather.snow -> for (i in 0 until (width * height / 70)) {
                val px = x + Painter.mod(i * 29 + (2 * sin(w.t + i)).toInt(), max(1, width))
                val py = y + ((i * 41.0 + w.t * 12) % max(1, height).toDouble()).toInt()
                p.rect(px, py, 1, 1, Color.White)
            }
            else -> Unit
        }
    }

    /** Bats flitting across the sky (or the cave) in October. */
    private fun drawBats(p: Painter, w: HabitatScene.WorldInfo) {
        val box = skyBox(w)
        val sky = p.clipped(box)
        val count = if (box.w < 40) 2 else 3
        for (i in 0 until count) {
            val speed = 3.0 + i * 1.3
            val lane = (box.w + 14).toDouble()
            val offset = i * lane / count
            val x = box.x - 7 + ((w.t * speed + offset) % lane).toInt()
            val y = box.y + (box.h * (0.15 + 0.22 * i)).toInt() + (2 * sin(w.t * 2.2 + i)).toInt()
            val up = (w.t * 6 + i).toInt() % 2 == 0
            sky.sprite(if (up) "bat.up" else "bat.down", x, y)
        }
    }

    // MARK: Party

    /** Bunting across the top of the scene. */
    private fun drawBunting(p: Painter, w: HabitatScene.WorldInfo) {
        val colors = listOf(rgb(0xF07A9A), rgb(0xFFD166), rgb(0x7FD6F0), rgb(0x7DE67A))
        fun sag(x: Int): Int = 1 + (3 * sin(PI * (x - w.minX) / max(1, w.span).toDouble())).rounded().toInt()
        for (x in w.minX until w.maxX) p.rect(x, sag(x), 1, 1, rgb(0x6B4423))
        for ((i, x) in Painter.stride(Painter.first(1, 6, w.minX), w.maxX, 6).withIndex()) {
            val top = sag(x) + 1
            val color = colors[i % colors.size]
            p.rect(x, top, 4, 1, color)
            p.rect(x, top + 1, 4, 1, color)
            p.rect(x + 1, top + 2, 2, 1, color)
        }
    }

    private fun drawConfetti(p: Painter, w: HabitatScene.WorldInfo) {
        val colors = listOf(rgb(0xF07A9A), rgb(0xFFD166), rgb(0x7FD6F0), rgb(0xB59BFF))
        val rng = SplitMix(77uL)
        for (i in 0 until (28 * w.span / 96)) {
            val x = rng.int(w.minX..(w.maxX - 1))
            val speed = 4 + rng.unit() * 6
            val start = rng.unit() * w.rows
            val y = ((start + w.t * speed) % max(1, w.rows).toDouble()).toInt()
            val sway = (1.5 * sin(w.t * 2 + i)).toInt()
            p.rect(x + sway, y, 1, 1, colors[i % colors.size])
        }
    }

    // MARK: Glow

    /** Things that keep shining at night, drawn over the darkness. */
    private fun drawGlow(p: Painter, w: HabitatScene.WorldInfo) {
        val night = w.light.isNight
        if (habitat == Habitat.room) {
            if (w.season == Season.christmas) treeLights(p, treeX(), w.floorY + 10, w.t, night)
            if (night) {
                if (furniture[FurnitureSlot.floorRight] == "furn.lamp") p.glowCone(85, w.floorY - 12, rgb(0xFFE7A0))
                if (furniture[FurnitureSlot.ceiling] == "furn.lights") {
                    val colors = listOf(rgb(0xF07A9A), rgb(0xFFD166), rgb(0x7FD6F0))
                    for (x in Painter.stride(Painter.first(2, 4, w.minX), w.maxX, 4)) {
                        if (Painter.mod(x / 4 + w.t.toInt(), 2) == 0) p.glow(x, 3, colors[Painter.mod(x, 3)])
                    }
                }
                if (furniture[FurnitureSlot.ceiling] == "furn.stars") {
                    for (tile in Painter.stride(Painter.first(0, 32, w.minX), w.maxX, 32)) {
                        for ((i, dx) in listOf(4, 12, 20, 28).withIndex()) {
                            if (Painter.mod(i + w.t.toInt(), 3) == 0) continue
                            p.glow(tile + dx, 6, if (i % 2 == 0) rgb(0xFFD166) else rgb(0xFFF5D6))
                        }
                    }
                }
                for (slot in listOf(FurnitureSlot.floorLeft, FurnitureSlot.floorRight)) {
                    val id = furniture[slot] ?: continue
                    val at = spot(slot, w)
                    when (id) {
                        "furn.jackolantern" -> {
                            val flicker = 0.85 + 0.15 * sin(w.t * 9)
                            p.layerBottom(id, at.x, at.y, 'f', rgb(0xFFD166).opacity(flicker))
                            p.rect(at.x - 1, at.y - 7, 13, 6, rgb(0xFFB347).opacity(0.15))
                        }
                        "furn.crystal" -> {
                            p.layerBottom(id, at.x, at.y, 'c', rgb(0x7FD6F0))
                            p.layerBottom(id, at.x, at.y, 'w', Color.White)
                            p.rect(at.x - 1, at.y - 9, 12, 9, rgb(0x7FD6F0).opacity(0.14))
                        }
                    }
                }
            }
        }
        if (habitat == Habitat.garden && w.season == Season.christmas) {
            // Lights along the fence.
            val colors = listOf(rgb(0xF07A9A), rgb(0xFFD166), rgb(0x7FD6F0), rgb(0x7DE67A))
            for ((i, x) in Painter.stride(31, w.maxX, 4).withIndex()) {
                val on = Painter.mod(i + (w.t * 2).toInt(), 3) != 0
                val color = colors[i % colors.size].opacity(if (on) 1.0 else 0.45)
                if (night) p.glow(x, w.floorY - 10, color) else p.rect(x, w.floorY - 10, 1, 1, color)
            }
        }
        if (w.season == Season.newYear && habitat != Habitat.crystal) drawFireworks(p, skyBox(w), w.t)
    }

    private fun drawFireworks(p: Painter, box: HabitatScene.Box, t: Double) {
        val sky = p.clipped(box)
        val colors = listOf(0xFFD166L, 0xF07A9AL, 0x7FD6F0L, 0xB59BFFL, 0x7DE67AL)
        val reach = max(3, min(10, min(box.w, box.h) / 3))
        val period = 2.6
        for (i in 0 until 3) {
            val shifted = t / period + i / 3.0
            // Without motion, show the bursts open.
            val phase = if (t == 0.0) 0.55 + i * 0.1 else shifted % 1
            val cycle = if (t == 0.0) i else shifted.toInt()
            val rng = SplitMix(SplitMix.seed("firework$cycle-$i"))
            val cx = box.x + 3 + rng.int(0..max(0, box.w - 7))
            val cy = box.y + 2 + rng.int(0..max(0, box.h / 2))
            val color = rgb(colors[rng.int(0..(colors.size - 1))])
            if (phase < 0.2) {
                val k = phase / 0.2
                val bottom = box.y + box.h
                val y = bottom - ((bottom - cy) * k).toInt()
                sky.rect(cx, y, 1, 2, color.opacity(0.9))
            } else {
                val k = (phase - 0.2) / 0.8
                val r = 1 + reach * k
                val fall = (k * k * 3).toInt()
                for (a in 0 until 12) {
                    val angle = a * PI / 6
                    sky.rect(cx + (cos(angle) * r).rounded().toInt(), cy + fall + (sin(angle) * r).rounded().toInt(), 1, 1,
                        color.opacity(1 - k))
                    if (a % 2 == 0) {
                        val inner = r * 0.55
                        sky.rect(cx + (cos(angle) * inner).rounded().toInt(), cy + fall + (sin(angle) * inner).rounded().toInt(),
                            1, 1, Color.White.opacity(0.8 * (1 - k)))
                    }
                }
            }
        }
    }
}

/** Draws on the pixel grid: one world pixel is [scale] device pixels, the grid starts at [ox]. */
class Painter(
    val scope: DrawScope,
    val scale: Float,
    val ox: Float,
    val width: Int,
    val height: Int,
    /** Only pixels inside this box are drawn, if set. */
    val clip: HabitatScene.Box? = null,
) {
    /** The same painter, drawing only inside [box]. */
    fun clipped(box: HabitatScene.Box): Painter = Painter(scope, scale, ox, width, height, box)

    fun rect(x: Int, y: Int, w: Int, h: Int, color: Color) {
        var x0 = x
        var y0 = y
        var x1 = x + w
        var y1 = y + h
        if (clip != null) {
            x0 = max(x0, clip.x)
            y0 = max(y0, clip.y)
            x1 = min(x1, clip.x + clip.w)
            y1 = min(y1, clip.y + clip.h)
        }
        if (x1 <= x0 || y1 <= y0) return
        scope.drawRect(
            color = color,
            topLeft = Offset(ox + x0 * scale, y0 * scale),
            size = Size((x1 - x0) * scale, (y1 - y0) * scale),
        )
    }

    fun frame(x: Int, y: Int, w: Int, h: Int, color: Color, thickness: Int = 1) {
        rect(x, y, w, thickness, color)
        rect(x, y + h - thickness, w, thickness, color)
        rect(x, y, thickness, h, color)
        rect(x + w - thickness, y, thickness, h, color)
    }

    fun disc(cx: Int, cy: Int, r: Int, color: Color) {
        for (dy in -r..r) {
            val half = sqrt((r * r - dy * dy).toDouble()).rounded().toInt()
            rect(cx - half, cy + dy, half * 2 + 1, 1, color)
        }
    }

    fun glow(x: Int, y: Int, color: Color) {
        rect(x - 1, y - 1, 3, 3, color.opacity(0.25))
        rect(x, y, 1, 1, color)
    }

    fun glowCone(x: Int, y: Int, color: Color) {
        for (i in 0 until 14) rect(x - i / 2 - 1, y + i, i + 2, 1, color.opacity(0.18 - i * 0.01))
    }

    /** An item sprite with its top-left at (x, y). Accepts item names, furniture ids and WorldArt names. */
    fun sprite(name: String, x: Int, y: Int) {
        val s = WorldArt.sprite(name) ?: return
        for ((dy, row) in s.rows.withIndex()) {
            for ((dx, ch) in row.withIndex()) {
                if (ch == '.') continue
                val c = s.color(ch) ?: continue
                rect(x + dx, y + dy, 1, 1, Color(c))
            }
        }
    }

    /** An item sprite standing on [bottom]: its lowest drawn row sits just above that row. */
    fun spriteBottom(name: String, x: Int, bottom: Int) {
        val s = WorldArt.sprite(name) ?: return
        sprite(name, x, bottom - usedHeight(s))
    }

    /** One colour of a standing sprite, redrawn in another colour (a lit face, a glowing crystal). */
    fun layerBottom(name: String, x: Int, bottom: Int, code: Char, color: Color) {
        val s = WorldArt.sprite(name) ?: return
        val top = bottom - usedHeight(s)
        for ((dy, row) in s.rows.withIndex()) {
            for ((dx, ch) in row.withIndex()) if (ch == code) rect(x + dx, top + dy, 1, 1, color)
        }
    }

    companion object {
        private fun usedHeight(s: ItemSprite): Int {
            val last = s.rows.indexOfLast { row -> row.any { it != '.' } }
            return if (last >= 0) last + 1 else s.rows.size
        }

        /** The first position of a repeating pattern at or before [minX], so it covers a scene that starts left of 0. */
        fun first(start: Int, every: Int, from: Int): Int {
            if (every <= 0) return start
            var x = start
            while (x > from) x -= every
            return x
        }

        /** Remainder that is never negative, for patterns left of 0. */
        fun mod(a: Int, n: Int): Int {
            if (n <= 0) return 0
            val r = a % n
            return if (r < 0) r + n else r
        }

        /** `stride(from:to:by:)`: from, from + by, … while below [to]. */
        fun stride(from: Int, to: Int, by: Int): IntProgression =
            if (from >= to) IntRange.EMPTY else (from until to step by)
    }
}
