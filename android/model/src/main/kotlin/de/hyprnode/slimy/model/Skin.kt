package de.hyprnode.slimy.model

import kotlinx.serialization.Serializable

/**
 * Jelly colours. New ones unlock as the slime levels up; the secret ones
 * (`isSecret`) only come out of crates.
 *
 * Colours are ARGB longs (`0xFFRRGGBB`), ready for Compose's `Color(Long)`.
 */
@Serializable
enum class Skin {
    lime, bubblegum, ocean, grape, lava, gold, ghost, void, axolotl;

    val id: String get() = name

    val title: String
        get() = when (this) {
            lime -> "Lime"
            bubblegum -> "Bubblegum"
            ocean -> "Ocean"
            grape -> "Grape"
            lava -> "Lava"
            gold -> "Gold"
            ghost -> "Ghost"
            void -> "Hypr Void"
            axolotl -> "Axolotl"
        }

    /**
     * The level that unlocks this colour. Secret colours never unlock by
     * level (`Int.MAX_VALUE`); check `isSecret` before showing a level for one.
     */
    val unlockLevel: Int
        get() = when (this) {
            lime -> 1
            bubblegum -> 2
            ocean -> 3
            grape -> 4
            lava -> 6
            gold -> 8
            ghost -> 10
            void -> 15
            axolotl -> Int.MAX_VALUE
        }

    /** A colour that is never listed in the shop or among the colours still to unlock. */
    val isSecret: Boolean get() = this == axolotl

    private val hexes: LongArray
        get() = when (this) {
            lime -> longArrayOf(0x7DE67A, 0x3DAA5A, 0x143028, 0xF0FFF0)
            bubblegum -> longArrayOf(0xFF9FCB, 0xE0609A, 0x3A1228, 0xFFF0F7)
            ocean -> longArrayOf(0x7CC8FF, 0x3A86D9, 0x0F2240, 0xEFF8FF)
            grape -> longArrayOf(0xB59BFF, 0x7A5BE0, 0x22164A, 0xF4F0FF)
            lava -> longArrayOf(0xFF9A5C, 0xE0532F, 0x3A140A, 0xFFF3E8)
            gold -> longArrayOf(0xFFD86B, 0xE0A42F, 0x3A2A08, 0xFFFBEA)
            ghost -> longArrayOf(0xE8F0FF, 0xA9B6D8, 0x2A3048, 0xFFFFFF)
            void -> longArrayOf(0x2A2540, 0x1A1630, 0x9B7BFF, 0xD9CCFF)
            // tools/art_items.py, AXOLOTL_SKIN
            axolotl -> longArrayOf(0xFFC4D8, 0xF08DB0, 0x4A1736, 0xFFFFFF)
        }

    val body: Long get() = argb(hexes[0])
    val shade: Long get() = argb(hexes[1])
    val ink: Long get() = argb(hexes[2])
    val shine: Long get() = argb(hexes[3])

    /** Colour for one paint layer of the sprite. */
    fun color(layer: SpriteLayer): Long = when (layer) {
        SpriteLayer.body -> body
        SpriteLayer.shade -> shade
        SpriteLayer.ink -> ink
        SpriteLayer.shine -> shine
        SpriteLayer.warm -> Brand.warm
        SpriteLayer.cool -> Brand.cool
    }

    /** Pixels this colour adds on top of every frame (the axolotl's gills and cheeks). Null for plain colours. */
    val overlay: SkinOverlaySprite? get() = SlimeSprites.data.skinOverlays?.get(name)

    companion object {
        val allCases: List<Skin> get() = entries

        /** Every colour that unlocks with levels, in order: all but the secret ones. */
        val regular: List<Skin> get() = entries.filter { !it.isSecret }

        /** The colours to pick from when the egg hatches. */
        val hatchChoices: List<Skin> = listOf(lime, bubblegum, ocean)

        /** Colours from their names, leaving out any this version doesn't know. */
        fun lossySet(names: List<String>): Set<Skin> = names.mapNotNull { enumOf<Skin>(it) }.toSet()
    }
}

/** Paint layers, in the same order the font generator draws them. */
enum class SpriteLayer {
    body, shade, ink, shine, warm, cool;

    /** Character used for this layer in `SlimeSprites`. */
    val code: Char
        get() = when (this) {
            body -> 'b'
            shade -> 's'
            ink -> 'k'
            shine -> 'w'
            warm -> 'r'
            cool -> 'c'
        }

    companion object {
        val allCases: List<SpriteLayer> get() = entries
    }
}

/** `0xRRGGBB` → opaque `0xFFRRGGBB`. */
fun argb(rgb: Long): Long = 0xFF000000L or (rgb and 0xFFFFFFL)

/** `0xRRGGBB` → opaque ARGB with an alpha of [opacity] (0...1). */
fun argb(rgb: Long, opacity: Double): Long {
    val a = (opacity.coerceIn(0.0, 1.0) * 255).rounded().toLong()
    return (a shl 24) or (rgb and 0xFFFFFFL)
}
