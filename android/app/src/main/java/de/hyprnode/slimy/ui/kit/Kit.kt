package de.hyprnode.slimy.ui.kit

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hyprnode.slimy.art.PixelItem
import de.hyprnode.slimy.core.Feedback
import de.hyprnode.slimy.core.Toast
import de.hyprnode.slimy.model.Need
import de.hyprnode.slimy.model.Sound
import java.text.NumberFormat
import kotlin.math.roundToInt

// The building blocks of Slimy's UI: Material 3 controls wherever Android
// has one, and a few small views for things it doesn't (the counterpart of
// the iPhone app's Kit).

/** A grouped card for content that isn't a list. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    title: String? = null,
    padding: Dp = 16.dp,
    fill: Color = Theme.card,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Theme.radius))
            .background(fill)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Theme.ink,
                modifier = Modifier.semantics { heading() },
            )
        }
        content()
    }
}

enum class AppButtonKind { primary, secondary, danger, quiet }

/**
 * A standard Material button with an optional pixel item ([icon]) or a
 * Material icon ([systemIcon]). Full width unless [compact]. Plays the tap
 * sound.
 */
@Composable
fun AppButton(
    title: String,
    modifier: Modifier = Modifier,
    icon: String? = null,
    systemIcon: ImageVector? = null,
    kind: AppButtonKind = AppButtonKind.primary,
    tint: Color? = null,
    compact: Boolean = false,
    enabled: Boolean = true,
    action: () -> Unit,
) {
    val onClick = {
        Feedback.shared.play(Sound.tap)
        action()
    }
    val sized = (if (compact) modifier else modifier.fillMaxWidth())
        .heightIn(min = if (compact) 40.dp else 50.dp)
    val label: @Composable RowScope.() -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) PixelItem(icon, Modifier.size(20.dp))
            if (systemIcon != null) Icon(systemIcon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge)
        }
    }
    when (kind) {
        AppButtonKind.primary -> Button(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = tint ?: Theme.accent,
                contentColor = if (tint == null) Theme.onAccent else Color.White,
            ),
            content = label,
        )
        AppButtonKind.secondary -> FilledTonalButton(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = (tint ?: Theme.accent).copy(alpha = 0.15f),
                contentColor = tint ?: Theme.accent,
            ),
            content = label,
        )
        AppButtonKind.danger -> Button(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = Theme.danger, contentColor = Color.White),
            content = label,
        )
        AppButtonKind.quiet -> FilledTonalButton(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Theme.well, contentColor = Theme.ink),
            content = label,
        )
    }
}

/**
 * A tappable tile: a filled rounded rectangle that dims a little when
 * pressed and fades when disabled (the Swift `TileStyle`).
 */
@Composable
fun TileButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = Theme.card,
    radius: Dp = Theme.radius,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val alpha by animateFloatAsState(if (!enabled) 0.45f else if (pressed) 0.7f else 1f, tween(120), label = "tileAlpha")
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(120), label = "tileScale")
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .scale(scale)
            .alpha(alpha)
            .clip(shape)
            .background(fill)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClickLabel = onClickLabel,
                role = Role.Button,
                onClick = onClick,
            ),
        content = content,
    )
}

/** Round icon button floating over the slime's home. 48 dp, labelled for TalkBack. */
@Composable
fun HUDButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    badge: Boolean = false,
    action: () -> Unit,
) {
    val card = Theme.card
    val love = Theme.love
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f))
            .clickable(role = Role.Button) {
                Feedback.shared.play(Sound.tap)
                action()
            }
            .semantics {
                contentDescription = label
                if (badge) stateDescription = "New"
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Theme.ink, modifier = Modifier.size(24.dp))
        if (badge) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-5).dp, y = 5.dp)
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(love)
                    .border(2.dp, card, CircleShape),
            )
        }
    }
}

/** A slim progress bar. [value] is 0...1. */
@Composable
fun Meter(value: Double, modifier: Modifier = Modifier, color: Color = Theme.accent, height: Dp = 6.dp) {
    val v = value.coerceIn(0.0, 1.0).let { if (it.isNaN()) 0.0 else it }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.2f))
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(v.toFloat(), 0f..1f)
                stateDescription = "${(v * 100).roundToInt()} percent"
            },
    ) {
        if (v > 0) {
            val w = maxOf(height, maxWidth * v.toFloat())
            Box(
                Modifier
                    .width(w)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/** A need as a ring around its pixel icon. [value] is 0...100. */
@Composable
fun NeedRing(need: Need, value: Double, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val v = (value / 100).coerceIn(0.0, 1.0).toFloat()
    val shown by animateFloatAsState(v, tween(300), label = "needRing")
    val color = if (value < 25) Theme.danger else Theme.color(need)
    Box(
        modifier
            .size(size)
            .semantics {
                contentDescription = need.title
                stateDescription = "${value.coerceIn(0.0, 100.0).roundToInt()} percent"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(color.copy(alpha = 0.2f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (shown > 0f) {
                drawArc(color, -90f, 360f * shown, false, Offset(inset, inset), arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        PixelItem(Theme.icon(need), Modifier.size(size * 0.42f))
    }
}

/** The coin balance with its pixel coin. */
@Composable
fun CoinCounter(
    coins: Int,
    modifier: Modifier = Modifier,
    /** Floating over the slime's home rather than sitting in a card. */
    floating: Boolean = false,
    /** In a top app bar: no background of its own. */
    inToolbar: Boolean = false,
) {
    val background = when {
        floating -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)
        inToolbar -> Color.Transparent
        else -> Theme.well
    }
    val format = remember { NumberFormat.getIntegerInstance() }
    Row(
        modifier
            .heightIn(min = if (floating) 48.dp else 32.dp)
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 12.dp)
            .clearAndSetSemantics { contentDescription = "$coins coins" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PixelItem("coin", Modifier.size(16.dp))
        AnimatedContent(
            targetState = coins,
            transitionSpec = {
                val up = targetState > initialState
                (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith
                    (slideOutVertically { if (up) -it else it } + fadeOut())
            },
            label = "coins",
        ) { value ->
            Text(
                text = format.format(value),
                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                color = Theme.ink,
            )
        }
    }
}

/**
 * What the slime says, in a bubble with a little tail. The slime's own
 * voice is the one place the pixel font appears.
 */
@Composable
fun SpeechBox(text: String, modifier: Modifier = Modifier) {
    val card = Theme.card
    Column(
        modifier.semantics(mergeDescendants = true) { contentDescription = "Slime says: $text" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = Theme.voice(16.sp),
            color = Theme.ink,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(card)
                .padding(horizontal = 14.dp, vertical = 9.dp)
                .clearAndSetSemantics { },
        )
        Spacer(
            Modifier
                .offset(y = (-1).dp)
                .size(width = 16.dp, height = 8.dp)
                .background(card, BubbleTail),
        )
    }
}

private val BubbleTail = GenericShape { size, _ ->
    moveTo(0f, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width / 2, size.height)
    close()
}

/**
 * A row of filter chips that scrolls sideways. 48 dp to tap, 36 dp to see.
 * [bleed] is the margin around the row: the chips start in line with the
 * content but scroll on through that margin to the edge, instead of being
 * cut off at an invisible line.
 */
@Composable
fun <T> ChipTabs(
    options: List<Pair<T, String>>,
    selection: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    bleed: Dp = 0.dp,
) {
    Row(
        modifier
            .layout { measurable, constraints ->
                val extra = bleed.roundToPx() * 2
                val wide = if (constraints.hasBoundedWidth) {
                    constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra)
                } else {
                    constraints
                }
                val placeable = measurable.measure(wide)
                val width = (placeable.width - extra).coerceAtLeast(constraints.minWidth)
                layout(width, placeable.height) { placeable.place(-extra / 2, 0) }
            }
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = bleed, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((value, title) in options) {
            val selected = selection == value
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .selectable(selected = selected, role = Role.Tab) {
                        Feedback.shared.play(Sound.tap)
                        onSelect(value)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) Theme.onAccent else Theme.ink,
                    modifier = Modifier
                        .heightIn(min = 36.dp)
                        .clip(CircleShape)
                        .background(if (selected) Theme.accent else Theme.well)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** Floating confirmation at the top of the screen. TalkBack reads it when it appears. */
@Composable
fun ToastBanner(text: String, good: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        tonalElevation = 2.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (good) Icons.Filled.CheckCircle else Icons.Filled.Error,
                contentDescription = null,
                tint = if (good) Theme.accent else Theme.danger,
                modifier = Modifier.size(20.dp),
            )
            Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Theme.ink)
        }
    }
}

/** Floating confirmation for a store [Toast]. */
@Composable
fun ToastBanner(toast: Toast, modifier: Modifier = Modifier) = ToastBanner(toast.text, toast.good, modifier)

/** A pixel item on a soft square, the icon of a list row. */
@Composable
fun ItemIcon(name: String, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.26f))
            .background(Theme.well)
            .padding(size * 0.18f),
        contentAlignment = Alignment.Center,
    ) {
        PixelItem(name, Modifier.fillMaxWidth().fillMaxHeight())
    }
}

/** A small capsule label like "NEW" or "RARE". */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, color: Color = Theme.accent) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// MARK: - Grouped lists

/**
 * An inset-grouped section: an optional header, rows on a rounded card on
 * the backdrop, an optional footer. Put [GroupedRow]s inside, with
 * [GroupedDivider]s between them.
 */
@Composable
fun GroupedSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                text = header,
                style = MaterialTheme.typography.labelLarge,
                color = Theme.inkSoft,
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                    .semantics { heading() },
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Theme.radius))
                .background(Theme.card),
            content = content,
        )
        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = Theme.inkSoft,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
    }
}

/**
 * One row of a [GroupedSection]: an optional leading icon, a title with an
 * optional subtitle, and optional trailing content (a value, a switch, a
 * chevron). Tappable when [onClick] is set. At least 56 dp tall.
 */
@Composable
fun GroupedRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleColor: Color = Theme.ink,
    icon: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val base = if (onClick != null) {
        modifier.clickable(enabled = enabled, onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
    } else {
        modifier
    }
    Row(
        base
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) icon()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft)
            }
        }
        if (trailing != null) trailing()
    }
}

/** The hairline between two [GroupedRow]s, inset like a list's separator. */
@Composable
fun GroupedDivider(modifier: Modifier = Modifier, inset: Dp = 16.dp) {
    HorizontalDivider(modifier.padding(start = inset), thickness = 0.5.dp, color = Theme.separator)
}

// MARK: - Sheets

/**
 * The root of a sheet or a pushed screen: its title in a top app bar (large
 * and collapsing unless [inline]) and a Done button, on the backdrop.
 * Content gets the scaffold's padding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetScaffold(
    title: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    inline: Boolean = false,
    doneLabel: String = "Done",
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = Theme.backdrop,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
    val doneButton: @Composable RowScope.() -> Unit = {
        actions()
        TextButton(onClick = onDone) { Text(doneLabel, fontWeight = FontWeight.SemiBold) }
    }
    if (inline) {
        val behavior = TopAppBarDefaults.pinnedScrollBehavior()
        Scaffold(
            modifier = modifier.nestedScroll(behavior.nestedScrollConnection),
            containerColor = Theme.backdrop,
            topBar = {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = navigationIcon,
                    actions = doneButton,
                    colors = colors,
                    scrollBehavior = behavior,
                )
            },
            content = content,
        )
    } else {
        val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        Scaffold(
            modifier = modifier.nestedScroll(behavior.nestedScrollConnection),
            containerColor = Theme.backdrop,
            topBar = {
                LargeTopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = navigationIcon,
                    actions = doneButton,
                    colors = colors,
                    scrollBehavior = behavior,
                )
            },
            content = content,
        )
    }
}
