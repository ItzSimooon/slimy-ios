package de.hyprnode.slimy.ui.screens.root

import android.app.Activity
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.play.core.review.ReviewManagerFactory
import de.hyprnode.slimy.art.ArtView
import de.hyprnode.slimy.art.referenceSeconds
import de.hyprnode.slimy.art.rememberFrameClock
import de.hyprnode.slimy.art.rememberReduceMotion
import de.hyprnode.slimy.core.Celebration
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.core.Toast
import de.hyprnode.slimy.model.CrateKind
import de.hyprnode.slimy.model.SplitMix
import de.hyprnode.slimy.ui.kit.AppButton
import de.hyprnode.slimy.ui.kit.Theme
import de.hyprnode.slimy.ui.kit.ToastBanner
import de.hyprnode.slimy.ui.screens.common.findAnyActivity
import de.hyprnode.slimy.ui.screens.crates.CrateSprite
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.sin

/**
 * Fanfare for level-ups, achievements, records, birthdays. After a happy one
 * (a level-up from level 3 on) it may ask for a Play Store rating, once per
 * app version, when the last card is closed (Play In-App Review; nothing
 * happens outside Play).
 */
@Composable
fun CelebrationOverlay() {
    val store = LocalGameStore.current
    val context = LocalContext.current
    val celebrations by store.celebrations.collectAsStateWithLifecycle()
    val reduceMotion = rememberReduceMotion()
    var reviewDue by remember { mutableStateOf(false) }

    fun dismiss() {
        val first = store.celebrations.value.firstOrNull() ?: return
        if (first.asksForReview && store.mayAskForReview) reviewDue = true
        store.dismissCelebration(first.id)
    }

    LaunchedEffect(celebrations.isEmpty(), reviewDue) {
        if (!celebrations.isEmpty() || !reviewDue) return@LaunchedEffect
        reviewDue = false
        // A beat after the card is gone, so the two don't collide.
        delay(700)
        if (!store.mayAskForReview || store.celebrations.value.isNotEmpty()) return@LaunchedEffect
        store.noteReviewAsked()
        context.findAnyActivity()?.let { askForReview(it) }
    }

    val first = celebrations.firstOrNull()
    AnimatedVisibility(visible = first != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { dismiss() }
                    .clearAndSetSemantics { },
            )
            if (!reduceMotion) Confetti()
            AnimatedContent(
                targetState = first,
                transitionSpec = {
                    if (reduceMotion) {
                        fadeIn(tween(200)) togetherWith fadeOut(tween(150))
                    } else {
                        (fadeIn(tween(200)) + scaleIn(tween(250), initialScale = 0.9f)) togetherWith fadeOut(tween(150))
                    }
                },
                contentKey = { it?.id },
                label = "celebration",
            ) { shown ->
                if (shown != null) CelebrationCard(shown) { dismiss() }
            }
        }
    }
    BackHandler(enabled = first != null) { dismiss() }
}

@Composable
private fun CelebrationCard(c: Celebration, onContinue: () -> Unit) {
    Column(
        Modifier
            .safeDrawingPadding()
            .padding(24.dp)
            .widthIn(max = 340.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Theme.card)
            .semantics { paneTitle = c.title }
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CelebrationIcon(c.icon, Modifier.padding(top = 6.dp).size(64.dp))
        Text(
            c.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Theme.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(c.detail, style = MaterialTheme.typography.bodyLarge, color = Theme.inkSoft, textAlign = TextAlign.Center)
        AppButton(title = "Continue", modifier = Modifier.padding(top = 4.dp), action = onContinue)
    }
}

/** A crate leaves out the empty rows above its lid, so it sits as centred as every other icon. */
@Composable
private fun CelebrationIcon(name: String, modifier: Modifier) {
    val kind = CrateKind.allCases.firstOrNull { it.sprite == name }
    if (kind != null) CrateSprite(kind, modifier) else ArtView(name, modifier)
}

@Composable
private fun Confetti() {
    val clock by rememberFrameClock(maxFps = 20)
    val colors = listOf(Theme.love, Theme.gold, Theme.sky, Theme.accent)
    Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        val t = referenceSeconds(clock)
        val rng = SplitMix(11uL)
        val side = 6.dp.toPx()
        for (i in 0 until 40) {
            val x = rng.unit() * size.width
            val speed = (60 + rng.unit() * 90) * density
            val y = ((rng.unit() * size.height + t * speed) % max(1.0, size.height.toDouble()))
            drawRect(
                color = colors[i % colors.size],
                topLeft = Offset((x + sin(t * 2 + i) * 8 * density).toFloat(), y.toFloat()),
                size = Size(side, side),
            )
        }
    }
}

private fun askForReview(activity: Activity) {
    try {
        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow().addOnCompleteListener { task ->
            if (task.isSuccessful) manager.launchReviewFlow(activity, task.result)
        }
    } catch (_: Exception) {
        // Not installed from Play, or Play isn't there: nothing to ask.
    }
}

/** The toast, top centre, long enough to read; TalkBack reads it out (the banner is a live region). */
@Composable
fun ToastLayer() {
    val store = LocalGameStore.current
    val toast by store.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val reduceMotion = rememberReduceMotion()
    val current by rememberUpdatedState(toast)
    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = toast != null,
            enter = if (reduceMotion) fadeIn(tween(200)) else slideInVertically { -it } + fadeIn(),
            exit = if (reduceMotion) fadeOut(tween(200)) else slideOutVertically { -it } + fadeOut(),
        ) {
            val shown: Toast? = current
            if (shown != null) ToastBanner(toast = shown, modifier = Modifier.widthIn(max = 520.dp))
        }
    }
    LaunchedEffect(toast?.id) {
        val shown = toast ?: return@LaunchedEffect
        val talkBack = context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        delay(ToastTiming.millis(shown.text, talkBack))
        store.dismissToast(shown)
    }
}

/** How long a toast stays: long enough to read, at most five seconds. */
object ToastTiming {
    fun millis(text: String, talkBack: Boolean): Long {
        val reading = text.length * 0.06
        val seconds = if (talkBack) 5.0 else reading.coerceIn(2.4, 5.0)
        return (seconds * 1000).toLong()
    }
}
