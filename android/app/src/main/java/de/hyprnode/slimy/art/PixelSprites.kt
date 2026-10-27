package de.hyprnode.slimy.art

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import de.hyprnode.slimy.model.AccessoryPlacer
import de.hyprnode.slimy.model.Accessories
import de.hyprnode.slimy.model.ItemSprite
import de.hyprnode.slimy.model.SlimeSprites
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.SpriteLayer
import de.hyprnode.slimy.model.Stage
import de.hyprnode.slimy.model.argb
import kotlin.math.floor
import kotlin.math.min

// Pixel art, drawn crisp: every sprite becomes a small bitmap (one bitmap
// pixel per art pixel, cached), drawn scaled by a whole number of device
// pixels with nearest-neighbour sampling. Only a box smaller than the art
// itself gets a fractional scale.

/** A pixel grid as an ARGB array, `width` × `height`. */
class PixelGrid(val width: Int, val height: Int, val pixels: IntArray) {
    /** As an Android bitmap, one bitmap pixel per art pixel. */
    fun toBitmap(): Bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

    /** As an Android bitmap, every art pixel `scale` × `scale` device pixels. */
    fun toBitmap(scale: Int): Bitmap {
        val s = scale.coerceAtLeast(1)
        if (s == 1) return toBitmap()
        val w = width * s
        val out = IntArray(w * height * s)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = pixels[y * width + x]
                if (c == 0) continue
                for (dy in 0 until s) {
                    val row = (y * s + dy) * w + x * s
                    for (dx in 0 until s) out[row + dx] = c
                }
            }
        }
        return Bitmap.createBitmap(out, w, height * s, Bitmap.Config.ARGB_8888)
    }

    fun toImageBitmap(): ImageBitmap = toBitmap().asImageBitmap()
}

/** Builds the pixel grids of slimes, items and icons. Pure; the caches live in [PixelCache]. */
object PixelPainter {
    /** Paints every cell of [rows] whose character has a colour in [palette] (ARGB) onto [into]. */
    fun paint(into: IntArray, width: Int, rows: List<String>, palette: (Char) -> Long?) {
        for ((y, row) in rows.withIndex()) {
            if (y * width >= into.size) break
            for ((x, ch) in row.withIndex()) {
                if (x >= width || ch == '.') continue
                val c = palette(ch) ?: continue
                into[y * width + x] = c.toInt()
            }
        }
    }

    /**
     * One frame of the slime with its skin's extra pixels (the axolotl's gills
     * and cheeks) and its accessories (scarf, glasses, hat, in that order).
     * `effects` off leaves out the warm and cool layers (hearts, zzz …).
     */
    fun slime(
        frame: List<String>,
        skin: Skin,
        accessories: Accessories = Accessories(),
        anchor: List<Int>? = null,
        effects: Boolean = true,
    ): PixelGrid {
        val w = SlimeSprites.width
        val h = SlimeSprites.height
        val px = IntArray(w * h)
        val layers = SpriteLayer.entries.associateBy { it.code }
        paint(px, w, frame) { ch ->
            val layer = layers[ch] ?: return@paint null
            if (!effects && (layer == SpriteLayer.warm || layer == SpriteLayer.cool)) null else skin.color(layer)
        }
        val overlay = skin.overlay
        if (overlay != null) {
            val grid = SlimeSprites.overlay(skin, frame)
            if (grid != null) paint(px, w, grid) { ch -> overlay.palette[ch.toString()]?.let { argb(it) } }
        }
        if (anchor != null && anchor.size >= 4) {
            for (item in listOfNotNull(accessories.scarf, accessories.glasses, accessories.hat)) {
                val sprite = SlimeSprites.data.accessories[item] ?: continue
                val grid = AccessoryPlacer.place(sprite, frame, anchor)
                paint(px, w, grid) { ch -> sprite.palette[ch.toString()]?.let { argb(it) } }
            }
        }
        return PixelGrid(w, h, px)
    }

    /** A multi-colour item sprite. */
    fun item(sprite: ItemSprite): PixelGrid {
        val w = sprite.width.coerceAtLeast(1)
        val h = sprite.height.coerceAtLeast(1)
        val px = IntArray(w * h)
        paint(px, w, sprite.rows) { ch -> sprite.color(ch) }
        return PixelGrid(w, h, px)
    }

    /** A one-colour icon: '#' is filled. */
    fun icon(rows: List<String>, color: Long): PixelGrid {
        val w = (rows.maxOfOrNull { it.length } ?: 1).coerceAtLeast(1)
        val h = rows.size.coerceAtLeast(1)
        val px = IntArray(w * h)
        paint(px, w, rows) { ch -> if (ch == '#') color else null }
        return PixelGrid(w, h, px)
    }

    /** Several one-colour layers of the same grid: each character drawn in its own colour. */
    fun layered(rows: List<String>, colors: Map<Char, Long>): PixelGrid {
        val w = (rows.maxOfOrNull { it.length } ?: 1).coerceAtLeast(1)
        val h = rows.size.coerceAtLeast(1)
        val px = IntArray(w * h)
        paint(px, w, rows) { ch -> colors[ch] }
        return PixelGrid(w, h, px)
    }
}

/** Cached bitmaps of the pixel art, shared by every renderer in the process. */
object PixelCache {
    private data class SlimeKey(
        val frame: List<String>,
        val skin: Skin,
        val accessories: Accessories,
        val anchor: List<Int>?,
        val effects: Boolean,
        val sprites: Int,
    )

    private val slimes = LruCache<SlimeKey, ImageBitmap>(160)
    private val things = LruCache<Any, ImageBitmap>(256)

    fun slime(frame: List<String>, skin: Skin, accessories: Accessories, anchor: List<Int>?, effects: Boolean): ImageBitmap {
        val key = SlimeKey(frame, skin, accessories.copy(), anchor, effects, System.identityHashCode(SlimeSprites.data))
        slimes.get(key)?.let { return it }
        val image = PixelPainter.slime(frame, skin, accessories, anchor, effects).toImageBitmap()
        slimes.put(key, image)
        return image
    }

    fun item(name: String, sprite: ItemSprite): ImageBitmap {
        val key = Triple("item", name, sprite)
        things.get(key)?.let { return it }
        val image = PixelPainter.item(sprite).toImageBitmap()
        things.put(key, image)
        return image
    }

    fun icon(rows: List<String>, color: Long): ImageBitmap {
        val key = Triple("icon", rows, color)
        things.get(key)?.let { return it }
        val image = PixelPainter.icon(rows, color).toImageBitmap()
        things.put(key, image)
        return image
    }

    fun layered(rows: List<String>, colors: Map<Char, Long>): ImageBitmap {
        val key = Triple("layered", rows, colors)
        things.get(key)?.let { return it }
        val image = PixelPainter.layered(rows, colors).toImageBitmap()
        things.put(key, image)
        return image
    }
}

/**
 * Draws a pixel bitmap fitted into the draw area: a whole number of device
 * pixels per art pixel (fractional only when the area is smaller than the
 * art), centred horizontally and aligned to the bottom (slimes) or centred
 * (items), never smoothed.
 */
fun DrawScope.drawPixels(image: ImageBitmap, bottom: Boolean = false, alpha: Float = 1f) {
    val cols = image.width
    val rows = image.height
    if (cols <= 0 || rows <= 0 || size.width <= 0f || size.height <= 0f) return
    val fit = min(size.width / cols, size.height / rows)
    val px = if (fit >= 1f) floor(fit) else fit
    val w = cols * px
    val h = rows * px
    val x = (size.width - w) / 2
    val y = if (bottom) size.height - h else (size.height - h) / 2
    if (px >= 1f) {
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(cols, rows),
            dstOffset = IntOffset(x.toInt(), y.toInt()),
            dstSize = IntSize(w.toInt(), h.toInt()),
            alpha = alpha,
            filterQuality = FilterQuality.None,
        )
    } else {
        translate(x, y) {
            scale(px, px, pivot = Offset.Zero) {
                drawImage(image, alpha = alpha)
            }
        }
    }
}

/**
 * One frame of the slime, with accessories, drawn crisply at any size. A
 * skin with an overlay (the axolotl) adds its gills and cheeks on top.
 * `squashX`/`squashY` stretch it around its bottom centre and `shiftX` moves
 * it sideways (in pixels), for the live slime's jiggle.
 */
@Composable
fun PixelSlime(
    frame: List<String>,
    skin: Skin,
    modifier: Modifier = Modifier,
    accessories: Accessories = Accessories(),
    @Suppress("UNUSED_PARAMETER") stage: Stage = Stage.young,
    anchor: List<Int>? = null,
    effects: Boolean = true,
    squashX: Float = 1f,
    squashY: Float = 1f,
    shiftX: Float = 0f,
) {
    val image = remember(frame, skin, accessories, anchor, effects, SlimeSprites.data) {
        PixelCache.slime(frame, skin, accessories, anchor, effects)
    }
    val ratio = SlimeSprites.width.toFloat() / SlimeSprites.height.toFloat()
    Spacer(
        modifier
            .aspectRatio(ratio)
            .clearAndSetSemantics { }
            .drawBehind {
                if (squashX == 1f && squashY == 1f && shiftX == 0f) {
                    drawPixels(image, bottom = true)
                } else {
                    translate(left = shiftX) {
                        scale(squashX, squashY, pivot = Offset(size.width / 2, size.height)) {
                            drawPixels(image, bottom = true)
                        }
                    }
                }
            },
    )
}

/** A small multi-colour sprite (food, coin, furniture …) from the sprite data. Draws nothing for an unknown name. */
@Composable
fun PixelItem(name: String, modifier: Modifier = Modifier, alpha: Float = 1f) {
    val sprite = SlimeSprites.items[name]
    if (sprite == null) {
        Spacer(modifier)
        return
    }
    PixelSpriteImage(name, sprite, modifier, alpha)
}

/** An [ItemSprite] (from the sprite data or [WorldArt]), crisp, keeping its aspect ratio. */
@Composable
internal fun PixelSpriteImage(name: String, sprite: ItemSprite, modifier: Modifier, alpha: Float = 1f) {
    val image = remember(name, sprite) { PixelCache.item(name, sprite) }
    Spacer(
        modifier
            .aspectRatio(image.width.toFloat() / image.height.coerceAtLeast(1).toFloat())
            .clearAndSetSemantics { }
            .drawBehind { drawPixels(image, alpha = alpha) },
    )
}

/** A one-colour pixel icon ('#' = filled), like the Swift `IconShape` filled with a colour. */
@Composable
fun PixelIcon(rows: List<String>, color: Color, modifier: Modifier = Modifier) {
    val argb = color.toArgb().toLong() and 0xFFFFFFFFL
    val image = remember(rows, argb) { PixelCache.icon(rows, argb) }
    Spacer(
        modifier
            .clearAndSetSemantics { }
            .drawBehind { drawPixels(image) },
    )
}
