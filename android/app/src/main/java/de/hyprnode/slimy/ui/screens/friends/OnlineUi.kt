package de.hyprnode.slimy.ui.screens.friends

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.hyprnode.slimy.core.Toast
import de.hyprnode.slimy.model.timeIntervalSince
import de.hyprnode.slimy.online.PairCode
import de.hyprnode.slimy.ui.kit.AppButton
import de.hyprnode.slimy.ui.kit.AppButtonKind
import de.hyprnode.slimy.ui.kit.Theme
import de.hyprnode.slimy.ui.kit.ToastBanner
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

// Small pieces shared by the Friends, Care Together and Chat screens: full
// screen sheets, links, sharing, dates, toasts, confirmations and code cards.

/** A sheet that covers the screen (the iPhone app's `.sheet`), drawn edge to edge on the backdrop. */
@Composable
internal fun FullScreenSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = Theme.backdrop) { content() }
    }
}

/** A back arrow for pages pushed inside a sheet. */
@Composable
internal fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
    }
}

/** Opens a web page in a Custom Tab (the iPhone app's `Link`), or the browser when there is no Custom Tabs browser. */
internal fun openLink(context: Context, url: String) {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (ignored: Exception) {
            // No browser at all.
        }
    } catch (e: Exception) {
        // Nothing to open it with.
    }
}

/** The system share sheet with a line of text (the iPhone app's `ShareLink`). */
internal fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    try {
        context.startActivity(Intent.createChooser(send, null))
    } catch (e: Exception) {
        // Nothing can share text.
    }
}

/** "5 minutes ago", "in 2 days" (`.relative(presentation: .named)`). */
internal fun relativeTime(date: Instant, now: Instant = Instant.now()): String =
    DateUtils.getRelativeTimeSpanString(date.toEpochMilli(), now.toEpochMilli(), DateUtils.MINUTE_IN_MILLIS).toString()

/** The time of day in the phone's 12 or 24 hour style (`.formatted(date: .omitted, time: .shortened)`). */
internal fun shortTime(context: Context, date: Instant): String = DateFormat.getTimeFormat(context).format(Date.from(date))

/** "25 Oct 2026" (`.abbreviated`). */
internal fun mediumDate(date: Instant): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(date)

/** "25 October 2026" (`.long`). */
internal fun longDate(date: Instant): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withZone(ZoneId.systemDefault()).format(date)

/** A short confirmation at the bottom (or top) that hides itself after a few seconds. TalkBack reads it. */
@Composable
internal fun BoxScope.ToastOverlay(toast: Toast?, onDismiss: (Toast) -> Unit, top: Boolean = false, seconds: Long = 3) {
    var shown by remember { mutableStateOf(toast) }
    LaunchedEffect(toast) {
        if (toast != null) {
            shown = toast
            delay(seconds * 1000)
            onDismiss(toast)
        }
    }
    AnimatedVisibility(
        visible = toast != null,
        modifier = Modifier
            .align(if (top) Alignment.TopCenter else Alignment.BottomCenter)
            .then(if (top) Modifier.statusBarsPadding() else Modifier.navigationBarsPadding())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        enter = fadeIn() + slideInVertically { if (top) -it else it },
        exit = fadeOut() + slideOutVertically { if (top) -it else it },
    ) {
        shown?.let { current ->
            ToastBanner(current, Modifier.clickable { onDismiss(current) })
        }
    }
}

/** A question before something that can't be undone (`confirmationDialog`). */
@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
    dismiss: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) {
                Text(confirm, color = if (destructive) Theme.danger else Theme.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss) } },
    )
}

/** A header above a grouped section with something on its right, like "Friends … 3 of 20". */
@Composable
internal fun SectionHeader(title: String, trailing: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = Theme.inkSoft,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelLarge, color = Theme.inkSoft)
    }
}

/** Small print under a section. */
@Composable
internal fun SectionFooter(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = Theme.inkSoft,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

/** A Material icon with a bold line and an explanation under it (the iPhone app's info rows). */
@Composable
internal fun InfoRow(icon: ImageVector, title: String, detail: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Theme.accent, modifier = Modifier.padding(top = 2.dp).size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Theme.ink)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft)
        }
    }
}

/** A trailing chevron for rows that open another page. */
@Composable
internal fun Chevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = Theme.inkSoft.copy(alpha = 0.6f),
    )
}

/** A spinner with a line next to it, read as one by TalkBack. */
@Composable
internal fun WaitingLine(text: String, modifier: Modifier = Modifier, color: Color = Theme.inkSoft) {
    Row(
        modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Theme.accent)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/**
 * A code to give to someone: big letters, until when it works, Copy and
 * Share. [label] is "Your code" or "Your care code". [extra] goes under the
 * buttons (Care Together's "Waiting for someone to join…").
 */
@Composable
internal fun CodeCard(
    code: PairCode,
    label: String,
    shareMessage: String,
    modifier: Modifier = Modifier,
    extra: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft)
        Text(
            code.code,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 34.sp,
            letterSpacing = 6.sp,
            color = Theme.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = "$label: ${code.code.toList().joinToString(" ")}"
            },
        )
        Text(
            "Valid until ${shortTime(context, code.expiresAt)}. Works once.",
            style = MaterialTheme.typography.bodySmall,
            color = Theme.inkSoft,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppButton(
                title = if (copied) "Copied" else "Copy",
                systemIcon = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                kind = AppButtonKind.secondary,
                modifier = Modifier.weight(1f),
            ) {
                clipboard.setText(AnnotatedString(code.code))
                copied = true
            }
            AppButton(title = "Share", systemIcon = Icons.Filled.Share, modifier = Modifier.weight(1f)) {
                shareText(context, shareMessage)
            }
        }
        extra()
    }
}

/** Recomposes when [date] has passed, so a card about a code that ran out goes away. */
@Composable
internal fun rememberExpiry(date: Instant?): Boolean {
    var passed by remember(date) { mutableStateOf(date == null || date <= Instant.now()) }
    LaunchedEffect(date) {
        if (date == null) return@LaunchedEffect
        val wait = date.timeIntervalSince(Instant.now())
        if (wait > 0) delay((wait * 1000).toLong() + 1000)
        passed = true
    }
    return passed
}

/** A ticking clock for "5 min ago" lines. */
@Composable
internal fun rememberNow(periodMillis: Long = 20_000): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(periodMillis) {
        while (true) {
            delay(periodMillis)
            now = Instant.now()
        }
    }
    return now
}

/** Space at the end of a list so the last section clears the navigation bar. */
internal fun listPadding(scaffold: PaddingValues, top: Dp = 8.dp): PaddingValues = PaddingValues(
    start = 16.dp,
    end = 16.dp,
    top = scaffold.calculateTopPadding() + top,
    bottom = scaffold.calculateBottomPadding() + 32.dp,
)

/** A little square icon background for rows. */
@Composable
internal fun IconTile(icon: ImageVector, tint: Color = Theme.accent, size: Dp = 36.dp) {
    Box(
        Modifier
            .size(size)
            .background(Theme.well, RoundedCornerShape(size * 0.26f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

/** A fixed gap. */
@Composable
internal fun Gap(width: Dp) {
    Spacer(Modifier.width(width))
}
