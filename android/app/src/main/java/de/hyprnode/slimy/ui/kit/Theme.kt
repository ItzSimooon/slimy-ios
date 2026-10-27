package de.hyprnode.slimy.ui.kit

import android.content.Context
import android.graphics.Typeface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hyprnode.slimy.model.Need
import kotlin.math.min

/**
 * Slimy's look on Android: Material 3 around a pixel-art slime (the
 * counterpart of the iPhone app's `Theme`). Colours come from the app's
 * Material theme (`SlimyTheme`) and follow light and dark mode; pixel art is
 * kept for the slime's own world, and Pixelify Sans is the slime's voice,
 * nothing else.
 */
object Theme {
    // MARK: Surfaces

    /** Behind grouped content. */
    val backdrop: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background

    /** Cards and rows on the backdrop. */
    val card: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainer

    /** Wells inside a card: inputs, quiet tiles, icon backgrounds. */
    val well: Color @Composable @ReadOnlyComposable get() = dynamic(light = 0x1F767680, dark = 0x3D767680)

    val separator: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outlineVariant

    // MARK: Text

    val ink: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface
    val inkSoft: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant

    // MARK: Colour

    /** Slime green: deep in light mode, bright in dark mode. */
    val accent: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary

    /** Text and symbols on an accent fill (both pass 4.5:1). */
    val onAccent: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onPrimary

    val gold: Color @Composable @ReadOnlyComposable get() = dynamic(light = 0xFFB7791F, dark = 0xFFFFD166)
    val love: Color @Composable @ReadOnlyComposable get() = dynamic(light = 0xFFFF2D55, dark = 0xFFFF375F)
    val sky: Color @Composable @ReadOnlyComposable get() = dynamic(light = 0xFF007AFF, dark = 0xFF0A84FF)
    val danger: Color @Composable @ReadOnlyComposable get() = dynamic(light = 0xFFFF3B30, dark = 0xFFFF453A)

    // MARK: Shape

    val radius: Dp = 16.dp
    val smallRadius: Dp = 12.dp

    // MARK: Pixel art (games only)

    /** Size of one chrome pixel inside game boards. */
    val px: Dp = 3.dp

    /** Outline colour for pixel art drawn inside games. */
    val border: Color @Composable @ReadOnlyComposable get() = dynamic(light = 0xFF3A2B2E, dark = 0xFF0D0A12)

    // MARK: Type (sp, so they follow the system font size)

    fun title(size: TextUnit = 22.sp): TextStyle = TextStyle(fontSize = size, fontWeight = FontWeight.Bold)
    fun label(size: TextUnit = 15.sp): TextStyle = TextStyle(fontSize = size, fontWeight = FontWeight.SemiBold)
    fun text(size: TextUnit = 15.sp): TextStyle = TextStyle(fontSize = size)

    /** The slime speaking: Pixelify Sans Bold, scaled with the system font size. */
    @Composable
    fun voice(size: TextUnit = 16.sp): TextStyle =
        TextStyle(fontFamily = Fonts.pixel, fontWeight = FontWeight.Bold, fontSize = size, lineHeight = size * 1.25f)

    // MARK: Needs

    /** A need's colour in the app's chrome (rings, bars), adapting to light and dark. */
    @Composable
    @ReadOnlyComposable
    fun color(need: Need): Color = color(need, isDark())

    /** A need's colour for light or dark surfaces (widgets, notifications). */
    fun color(need: Need, dark: Boolean): Color = Color(
        when (need) {
            Need.hunger -> if (dark) 0xFFFF9F0A else 0xFFFF9500
            Need.`fun` -> if (dark) 0xFF0A84FF else 0xFF007AFF
            Need.energy -> if (dark) 0xFF5E5CE6 else 0xFF5856D6
            Need.clean -> if (dark) 0xFF40C8E0 else 0xFF30B0C7
            Need.love -> if (dark) 0xFFFF375F else 0xFFFF2D55
        },
    )

    /** Ring or bar colour for a need at [value] (0...100): red when it runs low. */
    @Composable
    @ReadOnlyComposable
    fun tint(need: Need, value: Double): Color = if (value < 25) danger else color(need)

    /** The pixel icon of a need. */
    fun icon(need: Need): String = when (need) {
        Need.hunger -> "apple"
        Need.`fun` -> "ball"
        Need.energy -> "starJelly"
        Need.clean -> "bubbleItem"
        Need.love -> "heart"
    }

    /** Whether the current Material theme is a dark one. */
    @Composable
    @ReadOnlyComposable
    fun isDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

    @Composable
    @ReadOnlyComposable
    private fun dynamic(light: Long, dark: Long): Color = Color(if (isDark()) dark else light)
}

/** The bundled Pixelify Sans (OFL), loaded from the app's assets. */
object Fonts {
    const val regularFile = "PixelifySans-Regular.ttf"
    const val boldFile = "PixelifySans-Bold.ttf"

    @Volatile
    private var family: FontFamily? = null

    @Volatile
    private var typefaces: Pair<Typeface, Typeface>? = null

    /** Pixelify Sans, regular and bold, for Compose. */
    fun pixelFamily(context: Context): FontFamily = family ?: synchronized(this) {
        family ?: FontFamily(
            Font(regularFile, context.applicationContext.assets, FontWeight.Normal),
            Font(boldFile, context.applicationContext.assets, FontWeight.Bold),
        ).also { family = it }
    }

    /** Pixelify Sans in a composable: only for the slime's voice and the wordmark. */
    val pixel: FontFamily @Composable get() = pixelFamily(LocalContext.current)

    /** Pixelify Sans as an Android typeface, for bitmaps, notifications and widgets. */
    fun pixelTypeface(context: Context, bold: Boolean = true): Typeface {
        val pair = typefaces ?: synchronized(this) {
            typefaces ?: Pair(
                Typeface.createFromAsset(context.applicationContext.assets, regularFile),
                Typeface.createFromAsset(context.applicationContext.assets, boldFile),
            ).also { typefaces = it }
        }
        return if (bold) pair.second else pair.first
    }
}

/**
 * A rectangle with its corners cut by one pixel: the pixel-art "rounded"
 * corner. Only for tiles drawn inside games.
 */
class NotchedRectShape(private val notch: Dp = Theme.px) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val nPx = with(density) { notch.toPx() }
        val n = min(nPx, min(size.width / 4, size.height / 4))
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(n, 0f)
            lineTo(w - n, 0f)
            lineTo(w - n, n)
            lineTo(w, n)
            lineTo(w, h - n)
            lineTo(w - n, h - n)
            lineTo(w - n, h)
            lineTo(n, h)
            lineTo(n, h - n)
            lineTo(0f, h - n)
            lineTo(0f, n)
            lineTo(n, n)
            close()
        }
        return Outline.Generic(path)
    }
}
