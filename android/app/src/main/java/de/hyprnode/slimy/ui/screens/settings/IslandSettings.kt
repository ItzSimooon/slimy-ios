package de.hyprnode.slimy.ui.screens.settings

import android.content.Context
import android.os.Build
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.core.LocalIsland
import de.hyprnode.slimy.platform.island.IslandMode
import de.hyprnode.slimy.platform.island.IslandPlacement
import de.hyprnode.slimy.platform.island.IslandScene
import de.hyprnode.slimy.platform.island.IslandScreen
import de.hyprnode.slimy.platform.island.IslandSystem
import de.hyprnode.slimy.platform.island.IslandTextScale
import de.hyprnode.slimy.platform.island.IslandTuning
import de.hyprnode.slimy.platform.island.IslandView
import de.hyprnode.slimy.platform.island.PxRect
import de.hyprnode.slimy.ui.kit.AppButton
import de.hyprnode.slimy.ui.kit.AppButtonKind
import de.hyprnode.slimy.ui.kit.GroupedDivider
import de.hyprnode.slimy.ui.kit.GroupedRow
import de.hyprnode.slimy.ui.kit.GroupedSection
import de.hyprnode.slimy.ui.kit.Theme
import de.hyprnode.slimy.ui.screens.common.findAnyActivity
import de.hyprnode.slimy.ui.screens.common.rememberIsResumed
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

// Settings for the island: whether Slimy may show it over other apps,
// whether it shows, where exactly it sits, and whether the slime moves.

/**
 * The Island section of Settings: a preview at this phone's camera, the
 * status, the way to the system's "over other apps" page, Show island,
 * Position and Animation. [screen] is this phone's screen (tests pass one).
 */
@Composable
fun IslandSection(onPosition: () -> Unit, screen: IslandScreen = rememberIslandScreen()) {
    val island = LocalIsland.current
    val context = LocalContext.current
    val resumed = rememberIsResumed()
    val allowed by island.overlayAllowed.collectAsStateWithLifecycle()
    val show by island.showIsland.collectAsStateWithLifecycle()
    val running by island.overlayRunning.collectAsStateWithLifecycle()
    val animated by island.animated.collectAsStateWithLifecycle()
    val tuning by island.tuning.collectAsStateWithLifecycle()
    val supported = remember { island.overlaySupported }
    // Set while the system page is open, so coming back with permission turns the island on.
    var asked by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        island.refreshStatus()
        if (asked && island.overlayPermitted) {
            asked = false
            island.setShowIsland(true)
        }
    }

    GroupedSection(header = "Island", footer = IslandCopy.settingsFooter(supported)) {
        IslandPreview(
            screen = screen,
            tuning = tuning,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
        )
        if (!supported) {
            Notice(IslandCopy.unsupported)
            GroupedDivider()
            SwitchRow("Animation", detail = IslandCopy.animation, checked = animated) { on -> island.animated.value = on }
            return@GroupedSection
        }
        ValueRow("Status", IslandCopy.status(supported, allowed, show, running))
        GroupedDivider()
        if (!allowed) {
            LinkRow("Allow over other apps", Icons.Filled.Layers, external = true) {
                asked = true
                IslandSystem.openPermissionPage(context)
            }
            GroupedDivider()
        }
        SwitchRow("Show island", checked = show && allowed, enabled = allowed) { on -> island.setShowIsland(on) }
        GroupedDivider()
        GroupedRow(
            title = "Position",
            subtitle = if (tuning.isDefault) "Right on your camera" else "Adjusted",
            icon = { Icon(Icons.Filled.OpenWith, contentDescription = null, tint = Theme.accent) },
            enabled = allowed && show,
            onClick = onPosition,
        )
        GroupedDivider()
        SwitchRow("Animation", detail = IslandCopy.animation, checked = animated) { on -> island.animated.value = on }
    }
}

/**
 * A bottom sheet to move and size the island. While it is open the island
 * shows over Slimy too, right where it sits, so every change is visible
 * live on the real camera; the preview above the sliders shows it as well.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IslandPositionSheet(onDismiss: () -> Unit) {
    val island = LocalIsland.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tuning by island.tuning.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        island.previewing.value = true
        onDispose { island.previewing.value = false }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Theme.backdrop) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IslandPositionControls(tuning = tuning, onChange = { island.tuning.value = it.clamped() }, onDone = onDismiss)
        }
    }
}

/** The sheet's content: title, preview, the three sliders and Reset. */
@Composable
fun IslandPositionControls(
    tuning: IslandTuning,
    onChange: (IslandTuning) -> Unit,
    onDone: () -> Unit,
    screen: IslandScreen = rememberIslandScreen(),
) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(
            "Island position",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Theme.ink,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        TextButton(onClick = onDone) { Text("Done", fontWeight = FontWeight.SemiBold) }
    }
    Text(IslandCopy.positionHint, style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft)
    IslandPreview(screen = screen, tuning = tuning, height = 84.dp)
    GroupedSection {
        SliderRow(
            "Left or right", IslandCopy.horizontal(tuning.offsetX), tuning.offsetX,
            -IslandTuning.maxOffsetX..IslandTuning.maxOffsetX,
        ) { onChange(tuning.copy(offsetX = it.roundToInt().toFloat())) }
        GroupedDivider()
        SliderRow(
            "Up or down", IslandCopy.vertical(tuning.offsetY), tuning.offsetY,
            -IslandTuning.maxOffsetUp..IslandTuning.maxOffsetDown,
        ) { onChange(tuning.copy(offsetY = it.roundToInt().toFloat())) }
        GroupedDivider()
        SliderRow(
            "Size", IslandCopy.size(tuning.scale), tuning.scale,
            IslandTuning.minScale..IslandTuning.maxScale,
        ) { onChange(tuning.copy(scale = (it * 20).roundToInt() / 20f)) }
    }
    AppButton("Reset", kind = AppButtonKind.quiet, enabled = !tuning.isDefault) { onChange(IslandTuning()) }
}

@Composable
private fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Theme.ink, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.semantics {
                contentDescription = title
                stateDescription = valueText
            },
        )
    }
}

/**
 * The top of this phone's screen in small, with the island where it will sit
 * around the camera (drawn crisp at the preview's own scale) and the lens
 * where Android says the camera is.
 */
@Composable
fun IslandPreview(screen: IslandScreen, tuning: IslandTuning, modifier: Modifier = Modifier, height: Dp = 64.dp) {
    val store = LocalGameStore.current
    val island = LocalIsland.current
    val game by store.state.collectAsStateWithLifecycle()
    val animated by island.animated.collectAsStateWithLifecycle()
    val now = remember(game) { Instant.now() }
    val scene = IslandScene.of(game, now, animated = animated)
    val dark = Theme.isDark()
    val wallpaper = if (dark) Color(0xFF26292E) else Color(0xFFE4E1DA)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 10.dp, bottomEnd = 10.dp))
            .background(wallpaper)
            .clearAndSetSemantics { contentDescription = "Preview: ${scene.name} in the island around your camera" },
    ) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = with(density) { height.toPx() }
        if (widthPx <= 0f || screen.width <= 0) return@BoxWithConstraints
        val scaled = screen.scaled(widthPx / screen.width)
        val placement = IslandPlacement.of(scaled, tuning)
        PreviewStatusBar(
            ink = if (dark) Color(0xFFE8E8EA) else Color(0xFF2B2B30),
            height = with(density) { (scaled.statusBar.toFloat()).toDp() },
        )
        IslandTextScale {
            IslandView(
                scene = scene,
                placement = placement,
                mode = IslandMode.compact,
                window = PxRect(0f, 0f, widthPx, heightPx),
                animated = animated,
                still = true,
            )
        }
        IslandPlacement.pickCutout(scaled)?.takeIf { placement.camera == de.hyprnode.slimy.platform.island.CameraKind.punchHole }?.let { cut ->
            val d = with(density) { 8.dp.toPx() }
            val cx = cut.centerX
            val cy = if (cut.top <= 2 * scaled.density && cut.height > cut.width * 1.3f) cut.bottom - cut.width / 2 else cut.centerY
            Canvas(
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset((cx - d / 2).roundToInt(), (cy - d / 2).roundToInt()) }
                    .size(with(density) { d.toDp() }),
            ) {
                drawCircle(Color(0xFF101114))
                drawCircle(Color(0xFF2A3142), radius = size.minDimension * 0.3f)
                drawCircle(Color(0xFF4A5675), radius = size.minDimension * 0.1f, center = Offset(size.width * 0.38f, size.height * 0.38f))
            }
        }
    }
}

/** The clock and a battery in small, so the preview reads as the top of the phone. */
@Composable
private fun PreviewStatusBar(ink: Color, height: Dp) {
    val context = LocalContext.current
    val time = remember {
        val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm"
        java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern(pattern))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 18.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(time, color = ink, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Box(Modifier.weight(1f))
        Canvas(Modifier.size(width = 30.dp, height = 9.dp)) {
            val u = size.height
            for (i in 0 until 4) {
                val h = u * (0.35f + i * 0.21f)
                drawRoundRect(ink, Offset(i * u * 0.36f, u - h), androidx.compose.ui.geometry.Size(u * 0.24f, h),
                    androidx.compose.ui.geometry.CornerRadius(u * 0.08f))
            }
            val left = u * 1.75f
            drawRoundRect(ink, Offset(left, u * 0.1f), androidx.compose.ui.geometry.Size(u * 1.6f, u * 0.8f),
                androidx.compose.ui.geometry.CornerRadius(u * 0.22f), style = androidx.compose.ui.graphics.drawscope.Stroke(u * 0.11f))
            drawRoundRect(ink, Offset(left + u * 0.18f, u * 0.28f), androidx.compose.ui.geometry.Size(u * 1.05f, u * 0.44f),
                androidx.compose.ui.geometry.CornerRadius(u * 0.1f))
        }
    }
}

/**
 * This phone's screen as the island sees it: size, status bar and camera
 * cutout, read from the activity's window (Slimy draws edge to edge).
 */
@Composable
fun rememberIslandScreen(): IslandScreen {
    val view = LocalView.current
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var insetsVersion by remember { mutableStateOf(0) }
    DisposableEffect(view) {
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> insetsVersion++ }
        view.addOnLayoutChangeListener(listener)
        onDispose { view.removeOnLayoutChangeListener(listener) }
    }
    return remember(configuration, insetsVersion) { readScreen(context, view) }
}

private fun readScreen(context: Context, view: View): IslandScreen {
    val metrics = context.resources.displayMetrics
    var width = metrics.widthPixels
    var height = metrics.heightPixels
    val activity = context.findAnyActivity()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && activity != null) {
        val bounds = activity.windowManager.currentWindowMetrics.bounds
        width = bounds.width()
        height = bounds.height()
    }
    return IslandSystem.screen(context, view.rootWindowInsets, width, height)
}

/** The island's words in Settings and onboarding. Never "Dynamic Island": that's Apple's name. */
object IslandCopy {
    const val unsupported =
        "This phone doesn't let apps show things over other apps, so your slime lives in your notifications."

    const val animation = "When it's off, your slime holds still in the island and the notification."

    const val positionHint =
        "Move the island until your camera sits in its middle. It shows over this screen while you adjust it."

    fun settingsFooter(supported: Boolean): String = if (supported) {
        "Your slime sits in a little black island around your front camera, on top of every app. Tap it to look after your slime, touch and hold it to open Slimy or hide the island. It steps aside on the lock screen, in landscape, in full-screen apps and while Slimy is open. Android shows the slime notification while the island is on."
    } else {
        "Animation also moves the slime in its notification."
    }

    fun status(supported: Boolean, allowed: Boolean, show: Boolean, running: Boolean): String = when {
        !supported -> "Not available"
        !allowed -> "Not allowed yet"
        !show -> "Off"
        running -> "On"
        else -> "Not showing"
    }

    fun horizontal(dp: Float): String = when {
        abs(dp) < 0.5f -> "Centered"
        dp > 0 -> "${dp.roundToInt()} dp right"
        else -> "${(-dp).roundToInt()} dp left"
    }

    fun vertical(dp: Float): String = when {
        abs(dp) < 0.5f -> "Centered"
        dp > 0 -> "${dp.roundToInt()} dp lower"
        else -> "${(-dp).roundToInt()} dp higher"
    }

    fun size(scale: Float): String = "${(scale * 100).roundToInt()} %"
}
