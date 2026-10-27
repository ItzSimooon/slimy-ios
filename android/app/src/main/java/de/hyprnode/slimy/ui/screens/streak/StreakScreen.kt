package de.hyprnode.slimy.ui.screens.streak

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.art.ArtView
import de.hyprnode.slimy.art.LiveSlimeView
import de.hyprnode.slimy.art.PixelItem
import de.hyprnode.slimy.art.rememberClock
import de.hyprnode.slimy.art.rememberReduceMotion
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.model.Catalog
import de.hyprnode.slimy.model.CrateKind
import de.hyprnode.slimy.model.DailyGift
import de.hyprnode.slimy.model.StreakInfo
import de.hyprnode.slimy.model.line
import de.hyprnode.slimy.model.streakInfo
import de.hyprnode.slimy.ui.kit.AppButton
import de.hyprnode.slimy.ui.kit.AppButtonKind
import de.hyprnode.slimy.ui.kit.GroupedDivider
import de.hyprnode.slimy.ui.kit.GroupedRow
import de.hyprnode.slimy.ui.kit.GroupedSection
import de.hyprnode.slimy.ui.kit.ItemIcon
import de.hyprnode.slimy.ui.kit.SheetScaffold
import de.hyprnode.slimy.ui.kit.SpeechBox
import de.hyprnode.slimy.ui.kit.Theme
import de.hyprnode.slimy.ui.screens.common.ScreenColumn
import de.hyprnode.slimy.ui.screens.common.rememberAnnouncer
import de.hyprnode.slimy.ui.screens.common.swiftSpring
import de.hyprnode.slimy.ui.screens.crates.CrateCopy
import de.hyprnode.slimy.ui.screens.crates.CrateIcon
import de.hyprnode.slimy.ui.screens.crates.CrateSprite
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The daily gift: a calendar of seven gifts, one for each day the player
 * drops by, and the streak of days in a row. The seventh gift brings a
 * mystery crate, which can be opened right here. Missing a day never takes
 * a gift away; only the streak starts over (App/UI/Screens/StreakView.swift).
 */
@Composable
fun StreakScreen(onClose: () -> Unit, onOpenCrate: (CrateKind) -> Unit) {
    val store = LocalGameStore.current
    val s by store.state.collectAsStateWithLifecycle()
    // Looked at again every minute, so the next day's gift appears while the screen is open.
    val clock by rememberClock(60_000L)
    val now = Instant.ofEpochMilli(clock)
    val info = s.streakInfo(now)

    SheetScaffold(title = "Daily Gift", onDone = onClose, inline = true) { padding ->
        ScreenColumn(padding) {
            // On the backdrop, not in a card: the speech bubble is card-coloured and would vanish.
            Hero(info, onOpenCrate)
            GroupedSection(
                header = "This Week",
                footer = "A new gift every day you visit. Missing a day never takes a gift away, only the streak starts over.",
            ) {
                Calendar(info)
            }
            GroupedSection {
                StatRow("Current streak", StreakText.days(info.current))
                GroupedDivider()
                StatRow("Best streak", StreakText.days(info.best))
                GroupedDivider()
                StatRow("Gifts opened", "${info.opened}")
            }
        }
    }
}

@Composable
private fun StatRow(title: String, value: String) {
    GroupedRow(
        title = title,
        modifier = Modifier.semantics(mergeDescendants = true) { },
        trailing = { Text(value, style = MaterialTheme.typography.bodyLarge, color = Theme.inkSoft) },
    )
}

@Composable
private fun Hero(info: StreakInfo, onOpenCrate: (CrateKind) -> Unit) {
    val store = LocalGameStore.current
    val s by store.state.collectAsStateWithLifecycle()
    val reduceMotion = rememberReduceMotion()
    val announce = rememberAnnouncer()
    val scope = rememberCoroutineScope()
    val pop = remember { Animatable(1f) }
    // Today's gift, still shown after it's opened.
    val gift = info.todayGift

    fun open() {
        val opened = store.claimDailyGift() ?: return
        announce("You got ${opened.summary}")
        if (reduceMotion) return
        scope.launch {
            pop.animateTo(1.15f, swiftSpring(0.25, 0.45))
            pop.animateTo(1f, swiftSpring(0.35, 0.6))
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AnimatedVisibility(visible = info.claimedToday, enter = fadeIn(), exit = fadeOut()) {
            SpeechBox(text = s.line(Instant.now()), modifier = Modifier.widthIn(max = 300.dp))
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            LiveSlimeView(height = 84.dp, interactive = false)
            ArtView(
                name = if (info.canClaim) "gift.closed" else "gift.open",
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .size(64.dp)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        scaleX = pop.value
                        scaleY = pop.value
                    },
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                StreakText.headline(info),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Theme.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(StreakText.subline(info, gift, Instant.now()), style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft, textAlign = TextAlign.Center)
        }
        if (info.canClaim) {
            AppButton(title = "Open Gift") { open() }
        } else if (info.claimedToday) {
            GiftReward(gift)
            val crate = gift.crate
            if (crate != null && (s.crates[crate] ?: 0) > 0) {
                AppButton(
                    title = "Open the ${CrateCopy.name(crate).lowercase()}",
                    systemIcon = Icons.Outlined.Inventory2,
                    kind = AppButtonKind.secondary,
                ) { onOpenCrate(crate) }
            }
        }
    }
}

/** What today's gift held. */
@Composable
private fun GiftReward(gift: DailyGift) {
    Row(
        Modifier.clearAndSetSemantics { contentDescription = "You got ${gift.summary}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        gift.crate?.let { CrateIcon(it, size = 40.dp) }
        gift.item?.let { ItemIcon(Catalog.item(it)?.sprite ?: it, size = 40.dp) }
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .clip(CircleShape)
                .background(Theme.well)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PixelItem("coin", Modifier.size(18.dp))
            Text("+${gift.coins}", style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold, color = Theme.ink)
        }
    }
}

// MARK: The week

@Composable
private fun Calendar(info: StreakInfo) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (gift in info.week.take(4)) DayTile(gift, StreakText.tileState(gift, info), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (gift in info.week.drop(4).take(2)) DayTile(gift, StreakText.tileState(gift, info), Modifier.weight(1f))
            info.week.getOrNull(6)?.let { DayTile(it, StreakText.tileState(it, info), Modifier.weight(2f)) }
        }
    }
}

/** One day of the gift calendar. */
enum class DayStatus {
    opened, openedToday, ready, next, later;

    val isToday: Boolean get() = this == openedToday || this == ready
    val isOpened: Boolean get() = this == opened || this == openedToday

    val spoken: String
        get() = when (this) {
            opened -> "Opened"
            openedToday -> "Opened today"
            ready -> "Ready to open"
            next -> "Next"
            later -> "Later"
        }
}

@Composable
private fun DayTile(gift: DailyGift, state: DayStatus, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Theme.smallRadius)
    val fill = when {
        state.isToday -> Theme.accent.copy(alpha = 0.14f)
        gift.isSpecial -> Theme.gold.copy(alpha = 0.14f)
        else -> Theme.well
    }
    Box(
        modifier
            .heightIn(min = 88.dp)
            .clip(shape)
            .background(fill)
            .then(if (state.isToday) Modifier.border(2.dp, Theme.accent, shape) else Modifier)
            .clearAndSetSemantics {
                contentDescription = "Day ${gift.day}: ${gift.summary}"
                stateDescription = state.spoken
            },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (state.isToday) "Today" else "Day ${gift.day}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (state.isToday) Theme.accent else Theme.inkSoft,
                maxLines = 1,
            )
            Row(
                Modifier
                    .height(30.dp)
                    .alpha(if (state.isOpened) 0.4f else 1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                gift.crate?.let { CrateSprite(it, Modifier.size(30.dp)) }
                gift.item?.let { ArtView(Catalog.item(it)?.sprite ?: it, Modifier.size(30.dp)) }
                val small = gift.item != null || gift.crate != null
                PixelItem("coin", Modifier.size(if (small) 16.dp else 24.dp))
            }
            Text(
                StreakText.tileLabel(gift),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (state.isOpened) Theme.inkSoft else Theme.ink,
                textAlign = TextAlign.Center,
                maxLines = if (gift.crate != null) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.isOpened) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = Theme.accent,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(16.dp),
            )
        }
    }
}

/** The gift screen's words, kept apart from the views so they can be tested. */
object StreakText {
    fun days(n: Int): String = if (n == 1) "1 day" else "$n days"

    fun headline(info: StreakInfo): String {
        if (info.canClaim) return if (info.todayGift.isSpecial) "The big gift is here" else "Today's gift is here"
        if (info.claimedToday) return if (info.current >= 2) "${info.current} days in a row" else "Gift opened"
        return "See you tomorrow"
    }

    fun subline(info: StreakInfo, gift: DailyGift, now: Instant): String {
        if (info.canClaim) return "Day ${info.today} of ${DailyGift.days}: ${gift.summary}"
        val next = info.nextGift ?: return "Come back tomorrow for the next one."
        return "The next gift comes ${wait(next, now)}."
    }

    /** "in 5 hours", "in 20 minutes". */
    fun wait(until: Instant, now: Instant): String {
        val seconds = (until.toEpochMilli() - now.toEpochMilli()) / 1000.0
        val minutes = max(1, ceil(seconds / 60).toInt())
        if (minutes < 60) return if (minutes == 1) "in a minute" else "in $minutes minutes"
        val hours = (minutes / 60.0).roundToInt()
        return if (hours == 1) "in an hour" else "in $hours hours"
    }

    fun tileState(gift: DailyGift, info: StreakInfo): DayStatus {
        if (info.isOpened(gift)) return if (gift.day == info.today) DayStatus.openedToday else DayStatus.opened
        if (gift.day == info.today) return if (info.canClaim) DayStatus.ready else DayStatus.next
        return DayStatus.later
    }

    fun tileLabel(gift: DailyGift): String {
        val parts = mutableListOf<String>()
        gift.crate?.let { parts.add(CrateCopy.name(it)) }
        gift.item?.let { Catalog.item(it) }?.let { parts.add(it.title) }
        if (parts.isEmpty()) return "${gift.coins} coins"
        return (parts + "${gift.coins}").joinToString(" + ")
    }
}
