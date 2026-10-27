package de.hyprnode.slimy.art

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import de.hyprnode.slimy.model.Catalog
import de.hyprnode.slimy.model.ItemSprite
import de.hyprnode.slimy.model.SlimeSprites

/**
 * Pixel art that only lives in the slime's home and on the gift screen:
 * seasonal pieces, the gift box, and scene versions of some furniture.
 * Same format as the sprite data: "." is empty, every other character is a
 * colour from the palette (`0xRRGGBB`).
 */
object WorldArt {
    private val giftPalette: Map<String, Long> = mapOf(
        "r" to 0xF07A9A, "e" to 0xC2547A,
        "l" to 0x5FB0E8, "m" to 0x3A86D9,
        "b" to 0x7CC8FF, "s" to 0x3A86D9, "w" to 0xEFF8FF,
        "k" to 0x24507E, "y" to 0xFFD166,
    )

    val sprites: Map<String, ItemSprite> = mapOf(
        // A carved pumpkin. "f" is the face: dark by day, lit at night.
        "furn.jackolantern" to ItemSprite(
            palette = mapOf("o" to 0xF08A3C, "d" to 0xB85A1E, "g" to 0x6CC65A, "f" to 0x3A1E0A),
            rows = listOf(
                ".....gg....",
                "...oogooo..",
                ".ooooodood.",
                "ooffodoffod",
                "ooffodoffod",
                "ooooofooood",
                "offofffoffd",
                ".offfffffd.",
                "..ooooood..",
                "...ddddd...",
            ),
        ),
        // Stars on a string, one 32-pixel length; repeats across the ceiling.
        "furn.stars" to ItemSprite(
            palette = mapOf("k" to 0x2E3A32, "y" to 0xFFD166, "w" to 0xFFF5D6, "o" to 0xE8A23A),
            rows = listOf(
                "kk..kk..kk..kk..kk..kk..kk..kk..",
                "..kk..kk..kk..kk..kk..kk..kk..kk",
                "...k.......k.......k.......k....",
                "...y.......w.......y.......w....",
                "..yyy.....www.....yyy.....www...",
                "...o.......y.......o.......y....",
            ),
        ),
        "gift.closed" to ItemSprite(
            palette = giftPalette,
            rows = listOf(
                "..rrr..rrr..",
                ".r...rr...r.",
                "..rrrrrrrr..",
                "llllleelllll",
                "lwlllrrllllm",
                "mmmmmrrmmmmm",
                ".bbbbrrbbbb.",
                ".bwbbrrbbbs.",
                ".bbbbrrbbbs.",
                ".bbbbrrbbbs.",
                ".bbbbrrbbbs.",
                ".sssseessss.",
            ),
        ),
        "gift.open" to ItemSprite(
            palette = giftPalette,
            rows = listOf(
                "..y......y..",
                "y....y......",
                "..llllrrllll",
                "..mmmmrrmmmm",
                ".....y......",
                "...y....y...",
                ".skkkkkkkks.",
                ".bwbbrrbbbs.",
                ".bbbbrrbbbs.",
                ".bbbbrrbbbs.",
                ".bbbbrrbbbs.",
                ".sssseessss.",
            ),
        ),
        "bat.up" to ItemSprite(
            palette = mapOf("k" to 0x2A1E3A),
            rows = listOf(
                "k.....k",
                "kk.k.kk",
                ".kkkkk.",
                "...k...",
            ),
        ),
        "bat.down" to ItemSprite(
            palette = mapOf("k" to 0x2A1E3A),
            rows = listOf(
                ".......",
                "..kkk..",
                "kkkkkkk",
                "k.....k",
            ),
        ),
    )

    /**
     * Scene art first, then the shared sprites; furniture ids fall back to
     * the sprite their catalog entry borrows.
     */
    fun sprite(name: String): ItemSprite? {
        sprites[name]?.let { return it }
        SlimeSprites.items[name]?.let { return it }
        val borrowed = Catalog.item(name)?.sprite ?: return null
        return SlimeSprites.items[borrowed]
    }
}

/** Draws a sprite from [WorldArt] or the shared sprite data, crisp at any size. Nothing for an unknown name. */
@Composable
fun ArtView(name: String, modifier: Modifier = Modifier) {
    val sprite = WorldArt.sprite(name)
    if (sprite == null) {
        Spacer(modifier)
        return
    }
    PixelSpriteImage(name, sprite, modifier)
}
