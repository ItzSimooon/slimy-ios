package de.hyprnode.slimy.art

import de.hyprnode.slimy.model.Accessories
import de.hyprnode.slimy.model.SlimeSprites
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.model.Stage
import de.hyprnode.slimy.model.argb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class PixelPainterTest {
    @Before
    fun loadSprites() {
        val file = listOf("../../Shared/Sprites/sprites.json", "../Shared/Sprites/sprites.json")
            .map(::File).firstOrNull { it.exists() }
        assumeTrue("sprites.json not found", file != null)
        SlimeSprites.install(SlimeSprites.parse(file!!.readText()))
    }

    private fun frame(anim: SpriteAnim = SpriteAnim.idle, i: Int = 0) = SlimeSprites.frames(Stage.young, anim)[i]

    @Test
    fun everyLayerCharacterBecomesItsSkinColour() {
        val f = frame()
        val grid = PixelPainter.slime(f, Skin.lime)
        assertEquals(SlimeSprites.width * SlimeSprites.height, grid.pixels.size)
        for ((y, row) in f.withIndex()) {
            for ((x, ch) in row.withIndex()) {
                val px = grid.pixels[y * grid.width + x]
                when (ch) {
                    '.' -> assertEquals("transparent at $x,$y", 0, px)
                    'b' -> assertEquals(Skin.lime.body.toInt(), px)
                    's' -> assertEquals(Skin.lime.shade.toInt(), px)
                    'k' -> assertEquals(Skin.lime.ink.toInt(), px)
                    'w' -> assertEquals(Skin.lime.shine.toInt(), px)
                }
            }
        }
    }

    @Test
    fun effectsOffLeavesOutWarmAndCool() {
        val f = SlimeSprites.frames(Stage.young, SpriteAnim.love).first { rows -> rows.any { 'r' in it } }
        val on = PixelPainter.slime(f, Skin.lime, effects = true)
        val off = PixelPainter.slime(f, Skin.lime, effects = false)
        val warm = Skin.lime.color(de.hyprnode.slimy.model.SpriteLayer.warm).toInt()
        assertTrue(on.pixels.any { it == warm })
        assertTrue(off.pixels.none { it == warm })
    }

    @Test
    fun axolotlDrawsItsGills() {
        val f = frame()
        val overlay = SlimeSprites.overlay(Skin.axolotl, f)
        assertTrue("overlay for young.idle", overlay != null)
        val plain = PixelPainter.slime(f, Skin.lime)
        val axolotl = PixelPainter.slime(f, Skin.axolotl)
        val gill = argb(Skin.axolotl.overlay!!.palette.getValue("g")).toInt()
        assertTrue(axolotl.pixels.any { it == gill })
        assertTrue(plain.pixels.none { it == gill })
    }

    @Test
    fun hatSitsOnTopOfTheHead() {
        val anchors = SlimeSprites.anchors(Stage.young, SpriteAnim.idle)
        val f = frame()
        val bare = PixelPainter.slime(f, Skin.lime, Accessories(), anchors[0])
        val hatted = PixelPainter.slime(f, Skin.lime, Accessories(hat = "hat.crown"), anchors[0])
        assertNotEquals(bare.pixels.toList(), hatted.pixels.toList())
        // Everything the hat adds is at or above the head's top row.
        val top = anchors[0][0]
        for (i in bare.pixels.indices) {
            if (bare.pixels[i] != hatted.pixels[i]) assertTrue(i / bare.width <= top)
        }
    }

    @Test
    fun itemsAndIconsKeepTheirSize() {
        val apple = SlimeSprites.items.getValue("apple")
        val grid = PixelPainter.item(apple)
        assertEquals(apple.rows[0].length, grid.width)
        assertEquals(apple.rows.size, grid.height)
        val heart = SlimeSprites.emoteIcons.getValue("heart")
        val icon = PixelPainter.icon(heart, 0xFFFF0000L)
        assertEquals(heart.size, icon.height)
        assertEquals(heart.sumOf { row -> row.count { it == '#' } }, icon.pixels.count { it != 0 })
    }

    @Test
    fun eggCracksSpread() {
        val whole = PixelEggArt.grid(0)
        val cracked = PixelEggArt.grid(3)
        assertTrue(whole.none { 'c' in it })
        assertEquals(6, cracked.sumOf { row -> row.count { it == 'c' } })
    }
}
