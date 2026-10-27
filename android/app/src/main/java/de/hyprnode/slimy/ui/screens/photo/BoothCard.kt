package de.hyprnode.slimy.ui.screens.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.toArgb
import de.hyprnode.slimy.art.DayLight
import de.hyprnode.slimy.art.HabitatScene
import de.hyprnode.slimy.art.SlimeBitmap
import de.hyprnode.slimy.model.FurnitureSlot
import de.hyprnode.slimy.model.Habitat
import de.hyprnode.slimy.model.Slime
import de.hyprnode.slimy.model.SlimeSprites
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.ui.kit.Fonts
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// The photo booth's picture, drawn straight onto an Android bitmap: the
// counterpart of the iPhone app's BoothCard rendered with ImageRenderer.
// Sizes are in points of the iPhone card; the bitmap has three pixels per
// point (1152 pixels wide), so every pixel of the slime stays sharp.

enum class BoothPose(val title: String, val doing: String, val anim: SpriteAnim, val frame: Int) {
    wave("Wave", "waving", SpriteAnim.wave, 1),
    idle("Chill", "relaxing", SpriteAnim.idle, 0),
    love("Hearts", "sending hearts", SpriteAnim.love, 3),
    celebrate("Party", "celebrating", SpriteAnim.celebrate, 1),
    play("Jump", "jumping", SpriteAnim.play, 1),
    blush("Shy", "blushing", SpriteAnim.blush, 2),
    curious("Curious", "looking curious", SpriteAnim.curious, 3),
    eat("Snack", "having a snack", SpriteAnim.eat, 0),
    stretch("Stretch", "stretching", SpriteAnim.stretch, 0),
    sleep("Sleepy", "sleeping", SpriteAnim.sleep, 1),
}

enum class BoothLight(val title: String, private val hour: Int, private val minute: Int) {
    day("Day", 12, 0),
    evening("Evening", 18, 0),
    night("Night", 22, 30);

    /** Today at a time of day that shows this light. */
    fun date(on: Instant, zone: ZoneId = ZoneId.systemDefault()): Instant =
        on.atZone(zone).toLocalDate().atTime(hour, minute).atZone(zone).toInstant()
}

enum class BoothFormat(val title: String, val width: Float, val height: Float, val slimeHeight: Float) {
    portrait("Portrait", 384f, 480f, 150f),
    square("Square", 384f, 384f, 118f),
    story("Story", 384f, 683f, 170f);

    val aspect: Float get() = width / height
    val plateHeight: Float get() = if (this == square) 64f else 76f
}

data class BoothOptions(
    val pose: BoothPose,
    val habitat: Habitat,
    val light: BoothLight,
    val format: BoothFormat,
    val caption: String,
    val showDetails: Boolean,
)

/** Draws the card. Fixed sizes and colours: it is an image, so it looks the same in light and dark mode. */
object BoothCard {
    /** Pixels per point of the card. */
    const val pixelsPerPoint = 3f

    private const val paper = 0xFFFFFFFF.toInt()
    private const val ink = 0xFF1C1C1E.toInt()
    private const val inkSoft = 0xFF636366.toInt()
    private const val green = 0xFF1E7F37.toInt()

    /** Where effect particles float, from the slime's feet, in slime heights. */
    private val spots = listOf(
        Triple(-0.62f, -0.86f, 1f), Triple(0.6f, -0.74f, 0.8f), Triple(-0.5f, -0.36f, 0.7f),
        Triple(0.68f, -0.3f, 1f), Triple(0.08f, -1.1f, 0.75f),
    )

    fun render(
        context: Context,
        slime: Slime,
        furniture: Map<FurnitureSlot, String>,
        options: BoothOptions,
        date: Instant,
    ): Bitmap {
        val u = pixelsPerPoint
        val format = options.format
        val w = (format.width * u).toInt()
        val h = (format.height * u).toInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        // The sky of that hour under the scene, so the card never has a hole.
        canvas.drawColor(DayLight(date).sky.toArgb())
        val scene = SlimeBitmap.habitat(options.habitat, furniture, w, h, density = u, at = date)
        canvas.drawBitmap(scene, 0f, 0f, null)
        scene.recycle()

        val scale = HabitatScene.scale(w.toFloat(), u)
        val rows = ceil(h / scale).toInt()
        val floorY = HabitatScene.floorRow(rows) * scale
        val plate = format.plateHeight * u
        val feet = min(floorY + 10 * scale, h - 14 * u - plate - 10 * u)
        val slimeHeight = format.slimeHeight * u
        val cx = w / 2f

        // Shadow, slime, effect.
        val shadow = Paint().apply { color = 0x2E000000 }
        val shadowW = slimeHeight * 0.8f
        val shadowH = max(3f * u, slimeHeight * 0.04f)
        canvas.drawRect(cx - shadowW / 2, feet - shadowH, cx + shadowW / 2, feet, shadow)

        val pixel = max(1, floor(slimeHeight / SlimeSprites.height).toInt())
        val frames = SlimeSprites.frames(slime.stage, options.pose.anim)
        val index = min(options.pose.frame, max(0, frames.size - 1))
        val sprite = SlimeBitmap.frame(slime.stage, options.pose.anim, index, slime.skin, pixel, slime.equipped)
        val crisp = Paint().apply {
            isFilterBitmap = false
            isAntiAlias = false
        }
        canvas.drawBitmap(sprite, cx - sprite.width / 2f, feet - sprite.height, crisp)
        sprite.recycle()

        slime.equipped.effect?.let { effect -> drawParticles(canvas, effect, cx, feet, slimeHeight, crisp) }

        // The speech bubble above the slime.
        if (options.caption.isNotEmpty()) {
            drawBubble(context, canvas, options.caption, cx, feet - slimeHeight - 6 * u, w * 0.76f)
        }

        // The nameplate at the bottom.
        val plateRect = RectF(14 * u, h - 14 * u - plate, w - 14 * u, h - 14 * u)
        drawNameplate(context, canvas, plateRect, slime, options.showDetails)
        return out
    }

    private fun drawParticles(canvas: Canvas, effect: String, cx: Float, feet: Float, height: Float, crisp: Paint) {
        for ((x, y, size) in spots) {
            val side = height * 0.11f * size
            val pixel = max(1, floor(side / 10).toInt())
            val bitmap = when (effect) {
                "effect.hearts" -> SlimeBitmap.item("heart", pixel)
                "effect.bubbles" -> SlimeBitmap.item("bubbleItem", pixel)
                else -> SlimeBitmap.emoteIcon("sparkle_small", 0xFFFFD166L, pixel)
            } ?: continue
            // Fitted into a square of `side`, its bottom at the spot.
            val fit = min(side / bitmap.width, side / bitmap.height)
            val dw = bitmap.width * fit
            val dh = bitmap.height * fit
            val left = cx + x * height - dw / 2
            val bottom = feet + y * height
            val top = bottom - side + (side - dh) / 2
            canvas.drawBitmap(bitmap, null, RectF(left, top, left + dw, top + dh), crisp)
            bitmap.recycle()
        }
    }

    /** The bubble with its tail; [bottom] is where the tail's padding ends (just above the slime). */
    private fun drawBubble(context: Context, canvas: Canvas, text: String, cx: Float, bottom: Float, maxWidth: Float) {
        val u = pixelsPerPoint
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Fonts.pixelTypeface(context, bold = true)
            textSize = 17 * u
            color = ink
        }
        val padX = 14 * u
        val padY = 9 * u
        val textMax = (maxWidth - 2 * padX).toInt().coerceAtLeast(1)
        val desired = ceil(Layout.getDesiredWidth(text, paint)).toInt()
        val layoutWidth = min(textMax, desired).coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, layoutWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(3)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build()
        val boxW = layout.width + 2 * padX
        val boxH = layout.height + 2 * padY
        val boxBottom = bottom - 8 * u
        val rect = RectF(cx - boxW / 2, boxBottom - boxH, cx + boxW / 2, boxBottom)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = paper }
        canvas.drawRoundRect(rect, 14 * u, 14 * u, fill)
        val tail = Path().apply {
            moveTo(cx - 8 * u, boxBottom - 1 * u)
            lineTo(cx + 8 * u, boxBottom - 1 * u)
            lineTo(cx, boxBottom + 7 * u)
            close()
        }
        canvas.drawPath(tail, fill)
        canvas.save()
        canvas.translate(rect.left + padX, rect.top + padY)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawNameplate(context: Context, canvas: Canvas, rect: RectF, slime: Slime, showDetails: Boolean) {
        val u = pixelsPerPoint
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = paper }
        canvas.drawRoundRect(rect, 18 * u, 18 * u, fill)

        val sans = Typeface.create("sans-serif", Typeface.NORMAL)
        val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(sans, Typeface.BOLD)
            textSize = 24 * u
            color = ink
        }
        val detailPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textSize = 13 * u
            color = inkSoft
        }
        val brandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Fonts.pixelTypeface(context, bold = true)
            textSize = 26 * u
            color = green
        }
        val urlPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textSize = 11 * u
            color = inkSoft
        }

        val brand = "slimy"
        val url = "slime.hyprnode.de"
        val rightW = max(brandPaint.measureText(brand), urlPaint.measureText(url))
        val left = rect.left + 16 * u
        val right = rect.right - 16 * u
        val leftMax = right - rightW - 12 * u - left

        val name = slime.name
        val details = "${slime.stage.title} · Level ${slime.level} · Day ${slime.ageDays() + 1}"
        fit(namePaint, name, leftMax, minScale = 0.5f)
        fit(detailPaint, details, leftMax, minScale = 0.7f)

        // Left: the name and the details, centred as a block.
        val leftLines = if (showDetails) listOf(name to namePaint, details to detailPaint) else listOf(name to namePaint)
        drawStack(canvas, leftLines, rect.centerY(), 2 * u) { text, paint -> left to ellipsize(text, paint, leftMax) }

        // Right: the wordmark and the website, aligned to the right edge.
        drawStack(canvas, listOf(brand to brandPaint, url to urlPaint), rect.centerY(), 0f) { text, paint ->
            (right - paint.measureText(text)) to text
        }
    }

    /** Shrinks the paint's text size (down to [minScale]) until [text] fits into [width]. */
    private fun fit(paint: TextPaint, text: String, width: Float, minScale: Float) {
        val measured = paint.measureText(text)
        if (measured <= width || measured <= 0f) return
        val scale = max(minScale, width / measured)
        paint.textSize *= scale
    }

    private fun ellipsize(text: String, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(text, paint, width.coerceAtLeast(1f), TextUtils.TruncateAt.END).toString()

    /** Draws lines one under the other, the block centred on [centerY]. */
    private fun drawStack(
        canvas: Canvas,
        lines: List<Pair<String, TextPaint>>,
        centerY: Float,
        spacing: Float,
        place: (String, TextPaint) -> Pair<Float, String>,
    ) {
        val heights = lines.map { (_, paint) -> paint.fontMetrics.let { it.descent - it.ascent } }
        val total = heights.sum() + spacing * (lines.size - 1)
        var y = centerY - total / 2
        for ((i, line) in lines.withIndex()) {
            val (text, paint) = line
            val (x, shown) = place(text, paint)
            canvas.drawText(shown, x, y - paint.fontMetrics.ascent, paint)
            y += heights[i] + spacing
        }
    }
}
