package de.hyprnode.slimy.ui.screens.common

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.ui.kit.Theme
import kotlin.math.PI
import kotlin.math.max

// Small pieces the screens share: a pushed screen's chrome, grids, dialogs,
// links and the odd helper SwiftUI has built in.

/**
 * A screen pushed onto another one (Help from Settings, Drop rates from the
 * crate shelf): its title and a back arrow, no Done button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PushedScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val behavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(behavior.nestedScrollConnection),
        containerColor = Theme.backdrop,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Theme.backdrop,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = behavior,
            )
        },
        content = content,
    )
}

/**
 * The scrolling body of a list-like screen: grouped sections one under the
 * other, 16 dp from the edges, no wider than a phone column.
 */
@Composable
fun ScreenColumn(
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    spacing: Dp = 24.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
        Spacer(Modifier.padding(bottom = 24.dp))
    }
}

/**
 * Cells at least [minCellWidth] wide, as many per row as fit (SwiftUI's
 * `GridItem(.adaptive(minimum:))`). For short lists inside a scrolling column.
 */
@Composable
fun <T> AdaptiveGrid(
    items: List<T>,
    minCellWidth: Dp,
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    rowSpacing: Dp = spacing,
    cell: @Composable (T) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = gridColumns(maxWidth.value, minCellWidth.value, spacing.value)
        Column(verticalArrangement = Arrangement.spacedBy(rowSpacing)) {
            for (row in items.chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    for (item in row) {
                        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { cell(item) }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** How many cells of at least [minCell] fit into [width] with [spacing] between them; at least one. */
fun gridColumns(width: Float, minCell: Float, spacing: Float): Int {
    if (width <= 0f || minCell <= 0f) return 1
    return max(1, ((width + spacing) / (minCell + spacing)).toInt())
}

/** A question with a confirming and a cancelling answer, the counterpart of a confirmation dialog. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissLabel: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = message?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) {
                Text(
                    confirmLabel,
                    color = if (destructive) Theme.danger else Theme.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        },
    )
}

/**
 * Draws its content without colour (SwiftUI's `.saturation(0)`) when
 * [enabled]. Works on every Android version: the content goes through a
 * colour-matrix layer.
 */
fun Modifier.grayscale(enabled: Boolean): Modifier = if (!enabled) this else drawWithCache {
    val paint = Paint().apply {
        colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    }
    onDrawWithContent {
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
            drawContent()
            canvas.restore()
        }
    }
}

/** Reads a line out with TalkBack without moving focus (`UIAccessibility.post(.announcement)`). */
@Composable
fun rememberAnnouncer(): (String) -> Unit {
    val view = LocalView.current
    return remember(view) {
        { text: String ->
            @Suppress("DEPRECATION")
            if (text.isNotEmpty()) view.announceForAccessibility(text)
        }
    }
}

/** Whether the screen is in front and the app is active (SwiftUI's `scenePhase == .active`). */
@Composable
fun rememberIsResumed(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    return state.isAtLeast(Lifecycle.State.RESUMED)
}

/** Text so large that rows should stack instead of sitting side by side (iOS accessibility sizes). */
@Composable
fun isAccessibilityTextSize(): Boolean = LocalDensity.current.fontScale >= 1.6f

/** The activity behind a composable's context, for system prompts and review flows. */
fun Context.findActivity(): ComponentActivity? {
    var current: Context? = this
    while (current != null) {
        if (current is ComponentActivity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

/** The plain [Activity] behind a context, if any. */
fun Context.findAnyActivity(): Activity? {
    var current: Context? = this
    while (current != null) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

/** Opens a web page in a Custom Tab, or the browser if there is none. */
fun openLink(context: Context, url: String) {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    } catch (e: Exception) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }
}

/** Starts an activity, ignoring the case where nothing on the phone can handle it. */
fun startSafely(context: Context, intent: Intent): Boolean = try {
    if (context.findAnyActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

/** Shares a line of text with the system share sheet. */
fun shareText(context: Context, text: String, subject: String? = null) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
    }
    startSafely(context, Intent.createChooser(send, null))
}

/**
 * A spring described like SwiftUI's `.spring(response:dampingFraction:)`:
 * [response] is the period in seconds of the undamped oscillation.
 */
fun <T> swiftSpring(response: Double, damping: Double, visibilityThreshold: T? = null): SpringSpec<T> {
    val omega = 2 * PI / response.coerceAtLeast(0.01)
    return spring(dampingRatio = damping.toFloat(), stiffness = (omega * omega).toFloat(), visibilityThreshold = visibilityThreshold)
}

/** The local context, as a shorthand for screens. */
@Composable
fun context(): Context = LocalContext.current
