package de.hyprnode.slimy.art

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hyprnode.slimy.model.Brand
import de.hyprnode.slimy.model.SeededRandom
import de.hyprnode.slimy.model.SlimyCalendar
import java.time.Instant
import kotlin.math.ceil

/** A bar made of blocks, like an old handheld's battery meter. [value] is 0...100. */
@Composable
fun PixelBar(
    value: Int,
    color: Color,
    modifier: Modifier = Modifier,
    segments: Int = 10,
    height: Dp = 8.dp,
    gap: Dp = 2.dp,
) {
    val filled = ceil(value.toDouble() / 100 * segments).toInt()
    Row(
        modifier
            .height(height)
            .clearAndSetSemantics { stateDescription = "$value percent" },
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        repeat(segments) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (i < filled) color else color.copy(alpha = 0.18f)),
            )
        }
    }
}

/** What the slime says, in the island's look: flat box with a hard border, no tail. */
@Composable
fun SpeechBubble(text: String, modifier: Modifier = Modifier, size: TextUnit = 14.sp) {
    Text(
        text = text,
        style = TextStyle(fontSize = size, fontWeight = FontWeight.Medium, color = Color(Brand.text)),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(Color(Brand.raised))
            .border(2.dp, Color(Brand.line))
            .padding(horizontal = 9.dp, vertical = 5.dp)
            .clearAndSetSemantics { contentDescription = text },
    )
}

/** The view through the slime's window: one flat colour per time of day, pixel stars at night. */
@Composable
fun SkyBackground(date: Instant, modifier: Modifier = Modifier, calendar: SlimyCalendar = SlimyCalendar.current) {
    val hour = calendar.hour(date)
    val color = when (hour) {
        in 5 until 8 -> Color(0xFF3A2E3F)
        in 8 until 17 -> Color(0xFF223A4A)
        in 17 until 20 -> Color(0xFF4A3328)
        else -> Color(0xFF0E1316)
    }
    val night = hour >= 20 || hour < 5
    Canvas(modifier.fillMaxSize()) {
        drawRect(color)
        if (night) {
            val dot = 2.dp.toPx()
            for ((x, y) in SkyStars) {
                drawRect(
                    Color.White.copy(alpha = 0.55f),
                    topLeft = Offset((x * size.width - dot / 2).toFloat(), (y * size.height - dot / 2).toFloat()),
                    size = Size(dot, dot),
                )
            }
        }
    }
}

private val SkyStars: List<Pair<Double, Double>> = run {
    val rng = SeededRandom(7uL)
    List(22) { rng.next() to rng.next() * 0.6 }
}
