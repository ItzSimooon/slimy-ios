package de.hyprnode.slimy.art

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import de.hyprnode.slimy.model.Accessories
import de.hyprnode.slimy.model.EmoteReel
import de.hyprnode.slimy.model.FurnitureSlot
import de.hyprnode.slimy.model.Habitat
import de.hyprnode.slimy.model.Mood
import de.hyprnode.slimy.model.Slime
import de.hyprnode.slimy.model.SlimeSprites
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.model.Stage
import java.time.Instant

/**
 * The pixel art as Android bitmaps, for places Compose doesn't draw: the
 * slime notification, home-screen widgets and share cards. Every art pixel
 * becomes `scale` × `scale` bitmap pixels, never smoothed. Bitmaps are new
 * each call; cache them where they are drawn repeatedly.
 */
object SlimeBitmap {
    /** Width of a slime bitmap at [scale]. */
    fun width(scale: Int): Int = SlimeSprites.width * scale.coerceAtLeast(1)

    /** Height of a slime bitmap at [scale]. */
    fun height(scale: Int): Int = SlimeSprites.height * scale.coerceAtLeast(1)

    /** The art pixels of one frame of an animation (no scaling). */
    fun pixels(
        stage: Stage,
        anim: SpriteAnim,
        frameIndex: Int,
        skin: Skin,
        accessories: Accessories = Accessories(),
        effects: Boolean = true,
    ): PixelGrid {
        val frames = SlimeSprites.frames(stage, anim)
        val anchors = SlimeSprites.anchors(stage, anim)
        val i = if (frames.isEmpty()) 0 else ((frameIndex % frames.size) + frames.size) % frames.size
        val frame = frames.getOrNull(i) ?: SlimeSprites.blank
        return PixelPainter.slime(frame, skin, accessories, anchors.getOrNull(i), effects)
    }

    /** One frame of the slime (with its skin's overlay and accessories) at an integer [scale]. */
    fun frame(
        stage: Stage,
        anim: SpriteAnim,
        frameIndex: Int,
        skin: Skin,
        scale: Int,
        accessories: Accessories = Accessories(),
        effects: Boolean = true,
    ): Bitmap = pixels(stage, anim, frameIndex, skin, accessories, effects).toBitmap(scale)

    /** Every frame of an animation at [scale], in order (for a notification or widget flip-book). */
    fun frames(
        stage: Stage,
        anim: SpriteAnim,
        skin: Skin,
        scale: Int,
        accessories: Accessories = Accessories(),
    ): List<Bitmap> {
        val count = SlimeSprites.frames(stage, anim).size.coerceAtLeast(1)
        return List(count) { frame(stage, anim, it, skin, scale, accessories) }
    }

    /**
     * The animation a slime shows right now: a reaction from the last few
     * seconds, else its mood (idle, sad, asleep).
     */
    fun anim(slime: Slime, now: Instant = Instant.now()): SpriteAnim =
        SpriteAnim.of(slime.activeReaction(now)) ?: SpriteAnim.of(slime.mood)

    /** The slime as it is right now, in the single pose that best shows it, at [scale]. */
    fun slime(slime: Slime, scale: Int, now: Instant = Instant.now(), anim: SpriteAnim? = null): Bitmap {
        val a = anim ?: anim(slime, now)
        return frame(slime.stage, a, a.posterIndex(now), slime.skin, scale, slime.equipped)
    }

    /** A sleeping, sad or idle pose for a mood, at [scale]. */
    fun mood(stage: Stage, mood: Mood, skin: Skin, scale: Int, accessories: Accessories = Accessories()): Bitmap =
        frame(stage, SpriteAnim.of(mood), 0, skin, scale, accessories)

    /** An item sprite (food, coin, furniture … or WorldArt) at [scale]; null for an unknown name. */
    fun item(name: String, scale: Int): Bitmap? {
        val sprite = WorldArt.sprite(name) ?: return null
        return PixelPainter.item(sprite).toBitmap(scale)
    }

    /** A one-colour emote icon (`SlimeSprites.emoteIcons`) in an ARGB colour at [scale]; null for an unknown name. */
    fun emoteIcon(name: String, color: Long, scale: Int): Bitmap? {
        val rows = SlimeSprites.emoteIcons[name] ?: return null
        return PixelPainter.icon(rows, color).toBitmap(scale)
    }

    /** The first icon of an emote reel, in its colour, at [scale]. */
    fun emote(reel: EmoteReel, scale: Int): Bitmap = PixelPainter.icon(reel.poster, reel.color).toBitmap(scale)

    /** The hatching egg with [cracks] cracks, at [scale]. */
    fun egg(cracks: Int, scale: Int): Bitmap = PixelPainter.layered(PixelEggArt.grid(cracks), PixelEggArt.colors).toBitmap(scale)

    /**
     * The slime's home at one moment, still (no motion), [width] × [height]
     * pixels, drawn at [density] (for share cards and photos).
     */
    fun habitat(
        habitat: Habitat,
        furniture: Map<FurnitureSlot, String>,
        width: Int,
        height: Int,
        density: Float,
        at: Instant = Instant.now(),
        party: Boolean = false,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap.asImageBitmap())
        val painter = HabitatPainter(habitat, furniture, party)
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, canvas,
            Size(bitmap.width.toFloat(), bitmap.height.toFloat())) {
            painter.draw(this, at, 0.0)
        }
        return bitmap
    }
}
