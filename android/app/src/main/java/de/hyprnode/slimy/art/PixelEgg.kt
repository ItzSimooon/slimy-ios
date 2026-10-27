package de.hyprnode.slimy.art

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.clearAndSetSemantics

/** The egg, drawn as pixels: speckled shell, cracks spreading across as [cracks] grows. */
@Composable
fun PixelEgg(cracks: Int, modifier: Modifier = Modifier) {
    val image = remember(cracks) { PixelCache.layered(PixelEggArt.grid(cracks), PixelEggArt.colors) }
    Spacer(
        modifier
            .aspectRatio(12f / 15f)
            .clearAndSetSemantics { }
            .drawBehind { drawPixels(image) },
    )
}

/** The egg's pixels, shared with bitmap renderers. */
object PixelEggArt {
    private val shell = listOf(
        "....xxxx....",
        "...xxxxxx...",
        "..xxxxxxxx..",
        "..xxoxxxxx..",
        ".xxxxxxxxxx.",
        ".xxxxxxoxxx.",
        "xxxxxxxxxxxx",
        "xxxoxxxxxxxx",
        "xxxxxxxxxoxx",
        "xxxxxxxxxxxx",
        "xxxxxoxxxxxx",
        ".xxxxxxxxxx.",
        ".xxxxxxxxxx.",
        "..xxxxxxxx..",
        "...xxxxxx...",
    )

    private val crackPath = listOf(1 to 7, 2 to 6, 3 to 7, 4 to 6, 5 to 7, 6 to 6, 7 to 7, 8 to 6, 9 to 7, 10 to 6)

    /** Shell, shade, speckles and cracks (ARGB). */
    val colors: Map<Char, Long> = mapOf(
        'x' to 0xFFF7F0E2L,
        's' to 0xFFDCCFB8L,
        'o' to 0xFF7DE67AL,
        'c' to 0xFF3A2B2EL,
    )

    /** The shell with the first `cracks * 2` steps of the crack drawn in. */
    fun grid(cracks: Int): List<String> {
        val shown = crackPath.take((cracks * 2).coerceAtLeast(0)).toSet()
        return shell.mapIndexed { y, row ->
            String(CharArray(row.length) { x ->
                val ch = row[x]
                when {
                    ch == '.' -> '.'
                    shown.contains(x to y) -> 'c'
                    ch == 'o' -> 'o'
                    x >= 9 || y >= 13 -> 's'
                    else -> 'x'
                }
            })
        }
    }
}
