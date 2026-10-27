package de.hyprnode.slimy.ui.screens.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.art.LiveSlimeView
import de.hyprnode.slimy.art.PixelItem
import de.hyprnode.slimy.art.PixelSlime
import de.hyprnode.slimy.core.Feedback
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.model.Achievement
import de.hyprnode.slimy.model.Achievements
import de.hyprnode.slimy.model.Catalog
import de.hyprnode.slimy.model.ChallengeState
import de.hyprnode.slimy.model.ChallengeTemplate
import de.hyprnode.slimy.model.Challenges
import de.hyprnode.slimy.model.CrateKind
import de.hyprnode.slimy.model.Food
import de.hyprnode.slimy.model.GameID
import de.hyprnode.slimy.model.GameState
import de.hyprnode.slimy.model.Habitat
import de.hyprnode.slimy.model.ItemKind
import de.hyprnode.slimy.model.MemoryEntry
import de.hyprnode.slimy.model.Personality
import de.hyprnode.slimy.model.Rarity
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.SlimeSprites
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.model.Stage
import de.hyprnode.slimy.model.StatKey
import de.hyprnode.slimy.model.claimChallenge
import de.hyprnode.slimy.model.owns
import de.hyprnode.slimy.model.record
import de.hyprnode.slimy.ui.kit.AppButton
import de.hyprnode.slimy.ui.kit.ChipTabs
import de.hyprnode.slimy.ui.kit.GroupedDivider
import de.hyprnode.slimy.ui.kit.GroupedRow
import de.hyprnode.slimy.ui.kit.GroupedSection
import de.hyprnode.slimy.ui.kit.ItemIcon
import de.hyprnode.slimy.ui.kit.Meter
import de.hyprnode.slimy.ui.kit.SheetScaffold
import de.hyprnode.slimy.ui.kit.Theme
import de.hyprnode.slimy.ui.screens.common.AdaptiveGrid
import de.hyprnode.slimy.ui.screens.common.ScreenColumn
import de.hyprnode.slimy.ui.screens.common.grayscale
import de.hyprnode.slimy.ui.screens.common.isAccessibilityTextSize
import de.hyprnode.slimy.ui.screens.crates.CrateCopy
import de.hyprnode.slimy.ui.screens.crates.CrateIcon
import de.hyprnode.slimy.ui.screens.crates.CrateSprite
import de.hyprnode.slimy.ui.screens.crates.EffectIcon
import de.hyprnode.slimy.ui.screens.crates.RarityBadge
import de.hyprnode.slimy.ui.screens.crates.displayColor
import de.hyprnode.slimy.ui.screens.shop.CroppedHabitat
import java.text.NumberFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

enum class JournalTab(val title: String) {
    challenges("Challenges"), achievements("Achievements"), memories("Memories"), collection("Collection"), about("About"),
}

/**
 * The slime's diary: today's challenges, achievements, memories, the
 * collection and who the slime is. The photo booth opens from here
 * (App/UI/Screens/JournalView.swift).
 */
@Composable
fun JournalScreen(onClose: () -> Unit, onPhotoBooth: () -> Unit) {
    val store = LocalGameStore.current
    val s by store.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(JournalTab.challenges) }

    LaunchedEffect(Unit) { store.tick() }

    SheetScaffold(title = "Journal", onDone = onClose) { padding ->
        ScreenColumn(padding, spacing = 20.dp) {
            GroupedSection {
                JournalHeader(s)
                GroupedDivider()
                GroupedRow(
                    title = "Photo Booth",
                    titleColor = Theme.accent,
                    icon = { Icon(Icons.Outlined.PhotoCamera, contentDescription = null, tint = Theme.accent) },
                    onClickLabel = "Make a picture of ${s.slime.name} to share",
                    onClick = {
                        Feedback.shared.play(Sound.tap)
                        onPhotoBooth()
                    },
                )
            }
            ChipTabs(options = JournalTab.entries.map { it to it.title }, selection = tab, onSelect = { tab = it })
            when (tab) {
                JournalTab.challenges -> ChallengesContent(s)
                JournalTab.achievements -> AchievementsContent(s)
                JournalTab.memories -> MemoriesContent(s)
                JournalTab.collection -> CollectionContent(s)
                JournalTab.about -> AboutContent(s)
            }
        }
    }
}

// MARK: - Header

@Composable
private fun JournalHeader(s: GameState) {
    val slime = s.slime
    val day = slime.ageDays() + 1
    val texts: @Composable ColumnScope.() -> Unit = {
        Text(slime.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Theme.ink, maxLines = 2)
        Text("${slime.stage.title} · Level ${slime.level} · Day $day", style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft)
        Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Text("Bond", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Theme.inkSoft)
                Spacer(Modifier.weight(1f))
                Text(slime.bondStage.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Theme.inkSoft)
            }
            Meter(value = slime.bond / 100, color = Theme.love)
        }
    }
    val modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 14.dp)
        .clearAndSetSemantics {
            contentDescription = "${slime.name}, ${slime.stage.title}, level ${slime.level}, day $day"
            stateDescription = "Bond: ${slime.bondStage.title}, ${slime.bond.toInt()} of 100"
        }
    if (isAccessibilityTextSize()) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LiveSlimeView(height = 64.dp, interactive = false)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = texts)
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LiveSlimeView(height = 64.dp, interactive = false)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), content = texts)
        }
    }
}

// MARK: - Challenges

@Composable
private fun ChallengesContent(s: GameState) {
    val store = LocalGameStore.current
    val board = s.progress.challenges
    val claimable = (board.daily + board.weekly).filter { !it.claimed && Challenges.isDone(it, s) }
    val claimableCoins = claimable.sumOf { Challenges.template(it.id)?.coins ?: 0 }

    fun claim(id: String) {
        store.apply(sound = Sound.coin) { it.claimChallenge(id, Instant.now()) }
    }

    if (claimable.size > 1) {
        GroupedSection {
            GroupedRow(
                title = "Claim all rewards",
                titleColor = Theme.accent,
                icon = { PixelItem("coin", Modifier.size(20.dp)) },
                trailing = {
                    Text("+$claimableCoins", style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.SemiBold, color = Theme.accent)
                },
                modifier = Modifier.semantics { contentDescription = "Claim all rewards, $claimableCoins coins" },
                onClick = { for (challenge in claimable) claim(challenge.id) },
            )
        }
    }
    GroupedSection(header = "Today", footer = "New challenges every morning.") {
        if (board.daily.isEmpty()) {
            PlainRow("Today's challenges show up in the morning.")
        } else {
            board.daily.forEachIndexed { index, challenge ->
                if (index > 0) GroupedDivider(inset = 68.dp)
                ChallengeRow(challenge, s) { claim(challenge.id) }
            }
        }
    }
    GroupedSection(header = "This week", footer = "New weekly challenges every Monday.") {
        if (board.weekly.isEmpty()) {
            PlainRow("This week's challenges show up on Monday.")
        } else {
            board.weekly.forEachIndexed { index, challenge ->
                if (index > 0) GroupedDivider(inset = 68.dp)
                ChallengeRow(challenge, s) { claim(challenge.id) }
            }
        }
    }
}

@Composable
private fun PlainRow(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = Theme.inkSoft, modifier = Modifier.padding(16.dp))
}

@Composable
private fun ChallengeRow(challenge: ChallengeState, s: GameState, claim: () -> Unit) {
    val template = Challenges.template(challenge.id)
    val p = Challenges.progress(challenge, s)
    val done = p.value >= p.target
    val coins = template?.coins ?: 0
    val title = template?.title ?: "Challenge"
    val status = when {
        challenge.claimed -> "Done, reward claimed"
        done -> "Done, $coins coins to claim"
        else -> "${p.value} of ${p.target}, reward $coins coins"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = title
                stateDescription = status
                if (done && !challenge.claimed) {
                    customActions = listOf(CustomAccessibilityAction("Claim $coins coins") {
                        claim()
                        true
                    })
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ItemIcon(JournalText.challengeIcon(template), size = 40.dp)
        Column(Modifier.weight(1f).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Theme.ink)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Meter(
                    value = p.value.toDouble() / maxOf(1, p.target),
                    color = if (done) Theme.accent else Theme.sky,
                    modifier = Modifier.widthIn(max = 140.dp).weight(1f, fill = false),
                )
                Text("${p.value}/${p.target}", style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold, color = Theme.inkSoft)
            }
        }
        when {
            challenge.claimed -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Theme.accent, modifier = Modifier.size(24.dp))
            done -> AppButton(title = "+$coins", icon = "coin", compact = true, modifier = Modifier.clearAndSetSemantics { }, action = claim)
            else -> Row(Modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PixelItem("coin", Modifier.size(14.dp))
                Text(NumberFormat.getIntegerInstance().format(coins), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold, color = Theme.inkSoft)
            }
        }
    }
}

// MARK: - Achievements

@Composable
private fun AchievementsContent(s: GameState) {
    val unlocked = s.progress.achievements
    val total = Achievements.all.size
    val done = Achievements.all.mapNotNull { a -> unlocked[a.id]?.let { a to it } }.sortedByDescending { it.second }
    val open = Achievements.all.filter { unlocked[it.id] == null }
    val earned = done.sumOf { it.first.coins }

    GroupedSection {
        Column(
            Modifier
                .padding(16.dp)
                .semantics(mergeDescendants = true) { },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${done.size} of $total unlocked", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Theme.ink)
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.clearAndSetSemantics { contentDescription = "$earned coins earned" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PixelItem("coin", Modifier.size(14.dp))
                    Text(NumberFormat.getIntegerInstance().format(earned), style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold, color = Theme.inkSoft)
                }
            }
            Meter(value = done.size.toDouble() / maxOf(1, total), color = Theme.accent, height = 8.dp)
        }
    }
    if (done.isNotEmpty()) {
        GroupedSection(header = "Unlocked") {
            done.forEachIndexed { index, (achievement, date) ->
                if (index > 0) GroupedDivider(inset = 68.dp)
                AchievementRow(achievement, date)
            }
        }
    }
    if (open.isNotEmpty()) {
        GroupedSection(header = "Still to do") {
            open.forEachIndexed { index, achievement ->
                if (index > 0) GroupedDivider(inset = 68.dp)
                AchievementRow(achievement, null)
            }
        }
    }
}

@Composable
private fun AchievementRow(achievement: Achievement, date: Instant?) {
    // A hidden achievement stays a secret until it's earned.
    val secret = achievement.hidden && date == null
    val title = if (secret) "???" else achievement.title
    val detail = if (secret) "A secret. It shows itself once it's earned." else achievement.detail
    val status = if (date != null) "Unlocked ${JournalText.longDate(date)}" else "Locked, reward ${JournalText.achievementReward(achievement)}"
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clearAndSetSemantics {
                contentDescription = if (secret) "Secret achievement" else "${achievement.title}. ${achievement.detail}"
                stateDescription = status
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ItemIcon("furn.trophy", Modifier.grayscale(date == null).alpha(if (date == null) 0.45f else 1f), size = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Theme.ink)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft)
        }
        if (date != null) {
            Text(JournalText.mediumDate(date), style = MaterialTheme.typography.labelMedium, color = Theme.inkSoft, textAlign = TextAlign.End)
        } else {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PixelItem("coin", Modifier.size(14.dp))
                    Text(NumberFormat.getIntegerInstance().format(achievement.coins), style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold, color = Theme.inkSoft)
                }
                achievement.crate?.let { CrateSprite(it, Modifier.size(20.dp)) }
            }
        }
    }
}

// MARK: - Memories

@Composable
private fun MemoriesContent(s: GameState) {
    val entries = s.memory.keepsakes.sortedByDescending { it.at }
    if (entries.isEmpty()) {
        GroupedSection {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, tint = Theme.inkSoft, modifier = Modifier.size(44.dp))
                Text("No memories yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Theme.ink)
                Text(
                    "Big moments end up here: a favourite food found, records, new friends and growing up.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Theme.inkSoft,
                    textAlign = TextAlign.Center,
                )
            }
        }
        return
    }
    for ((month, monthEntries) in JournalText.months(entries)) {
        GroupedSection(header = JournalText.monthTitle(month)) {
            monthEntries.forEachIndexed { index, entry ->
                if (index > 0) GroupedDivider(inset = 68.dp)
                MemoryRow(entry)
            }
        }
    }
}

@Composable
private fun MemoryRow(entry: MemoryEntry) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MemoryIcon(entry.icon)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.summary, style = MaterialTheme.typography.bodyLarge, color = Theme.ink)
            Text(JournalText.mediumDate(entry.at), style = MaterialTheme.typography.labelMedium, color = Theme.inkSoft)
        }
    }
}

/**
 * Crates are drawn like everywhere else (their sprites leave room above the
 * lid), and effects, which have no sprite, show what floats around the slime.
 */
@Composable
private fun MemoryIcon(icon: String) {
    val crate = CrateKind.allCases.firstOrNull { it.sprite == icon }
    val item = Catalog.item(icon)
    when {
        crate != null -> CrateIcon(crate, size = 40.dp)
        item != null && item.kind == ItemKind.effect -> CollectionItemIcon(item, size = 40)
        else -> ItemIcon(icon, size = 40.dp)
    }
}

// MARK: - Collection

@Composable
private fun CollectionContent(s: GameState) {
    val tasted = s.inventory.foodsTasted
    val wardrobe = Catalog.items.filter { it.kind != ItemKind.furniture }
    val furniture = Catalog.items(ItemKind.furniture)
    // Secret colours live in the legendary slot under Crates.
    val colours = Skin.allCases.filter { !it.isSecret }
    val skins = colours.filter { s.slime.isUnlocked(it) }

    CollectionSection("Food", tasted.size, Food.allCases.size) {
        AdaptiveGrid(Food.allCases, minCellWidth = 64.dp, spacing = 8.dp, rowSpacing = 12.dp) { food ->
            val have = tasted.contains(food)
            CollectionCell(if (have) food.title else "???", have, if (have) food.title else "Not tried yet") {
                Box {
                    ItemIcon(food.sprite, size = 44.dp)
                    if (have && s.inventory.favoriteFound && food == s.slime.favorite) {
                        PixelItem("heart", Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-3).dp).size(width = 12.dp, height = 10.dp))
                    }
                }
            }
        }
    }
    CollectionSection("Wardrobe", wardrobe.count { s.owns(it.id) }, wardrobe.size) {
        AdaptiveGrid(wardrobe, minCellWidth = 64.dp, spacing = 8.dp, rowSpacing = 12.dp) { item ->
            val have = s.owns(item.id)
            CollectionCell(item.title, have, "${item.title}, ${if (have) "owned" else "not owned yet"}") {
                CollectionItemIcon(item)
            }
        }
    }
    CollectionSection("Furniture", furniture.count { s.owns(it.id) }, furniture.size) {
        AdaptiveGrid(furniture, minCellWidth = 64.dp, spacing = 8.dp, rowSpacing = 12.dp) { item ->
            val have = s.owns(item.id)
            CollectionCell(item.title, have, "${item.title}, ${if (have) "owned" else "not owned yet"}") {
                ItemIcon(item.sprite, size = 44.dp)
            }
        }
    }
    CollectionSection("Places", s.inventory.habitats.size, Habitat.allCases.size) {
        AdaptiveGrid(Habitat.allCases, minCellWidth = 109.dp, spacing = 10.dp, rowSpacing = 12.dp) { habitat ->
            val have = s.inventory.habitats.contains(habitat)
            CollectionCell(habitat.title, have, "${habitat.title}, ${if (have) "owned" else "not owned yet"}") {
                CroppedHabitat(
                    habitat = habitat,
                    furniture = if (habitat == Habitat.room) s.inventory.furniture else emptyMap(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            }
        }
    }
    CollectionSection("Colours", skins.size, colours.size) {
        AdaptiveGrid(colours, minCellWidth = 64.dp, spacing = 8.dp, rowSpacing = 12.dp) { skin ->
            val have = s.slime.isUnlocked(skin)
            CollectionCell(skin.title, have, "${skin.title}, ${if (have) "unlocked" else "unlocks at level ${skin.unlockLevel}"}") {
                CollectionSkin(skin, s.slime.stage, Modifier.size(width = 53.dp, height = 44.dp))
            }
        }
    }
    CratesCollection(s)
    GroupedSection(header = "Records") {
        GameID.classics.forEachIndexed { index, game ->
            if (index > 0) GroupedDivider(inset = 66.dp)
            val record = s.record(game)
            val rounds = if (record.plays == 0) "Not played yet" else "${record.plays} ${if (record.plays == 1) "round" else "rounds"}"
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .clearAndSetSemantics {
                        contentDescription = game.title
                        stateDescription = if (record.plays == 0) rounds else "Best ${record.best}, $rounds"
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ItemIcon(game.icon, size = 36.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(game.title, style = MaterialTheme.typography.bodyLarge, color = Theme.ink)
                    Text(rounds, style = MaterialTheme.typography.labelMedium, color = Theme.inkSoft)
                }
                if (record.plays > 0) {
                    Text(NumberFormat.getIntegerInstance().format(record.best),
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.SemiBold, color = Theme.ink)
                }
            }
        }
    }
}

@Composable
private fun CollectionSection(title: String, have: Int, total: Int, content: @Composable () -> Unit) {
    Column {
        Row(
            Modifier
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                .clearAndSetSemantics {
                    contentDescription = "$title, $have of $total"
                    heading()
                },
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = Theme.inkSoft)
            Spacer(Modifier.weight(1f))
            Text("$have/$total", style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), color = Theme.inkSoft)
        }
        GroupedSection {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) { content() }
        }
    }
}

/** Crates opened so far, and the legendary slot: a question mark until the secret colour turns up. */
@Composable
private fun CratesCollection(s: GameState) {
    val opened = s.progress.stats.cratesOpened
    val waiting = CrateKind.allCases.sumOf { s.crates[it] ?: 0 }
    val waitingText = when (waiting) {
        0 -> "None waiting"
        1 -> "1 waiting to be opened"
        else -> "$waiting waiting to be opened"
    }
    val found = Skin.allCases.firstOrNull { it.isSecret && s.slime.isUnlocked(it) }
    GroupedSection(header = "Crates") {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .clearAndSetSemantics {
                    contentDescription = "Crates opened"
                    stateDescription = "$opened, ${waitingText.lowercase()}"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CrateIcon(CrateKind.golden, size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Crates opened", style = MaterialTheme.typography.bodyLarge, color = Theme.ink)
                Text(waitingText, style = MaterialTheme.typography.labelMedium, color = Theme.inkSoft)
            }
            Text(NumberFormat.getIntegerInstance().format(opened), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold, color = Theme.ink)
        }
        GroupedDivider(inset = 84.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clearAndSetSemantics {
                    contentDescription = "Legendary"
                    stateDescription = found?.let { "${it.title}, found" } ?: "Still a secret"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (found != null) {
                Box(
                    Modifier
                        .size(width = 56.dp, height = 52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Rarity.legendary.displayColor.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CollectionSkin(found, s.slime.stage, Modifier.size(width = 44.dp, height = 37.dp))
                }
            } else {
                Box(
                    Modifier
                        .size(width = 56.dp, height = 52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Theme.well),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.QuestionMark, contentDescription = null, tint = Theme.inkSoft)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RarityBadge(Rarity.legendary, compact = true)
                Text(found?.title ?: "???", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Theme.ink)
                Text(
                    if (found == null) "Still a secret. Some crates hold it, very rarely." else "Found in a crate. Wear it any time from the shop.",
                    style = MaterialTheme.typography.labelMedium,
                    color = Theme.inkSoft,
                )
            }
        }
    }
}

@Composable
private fun CollectionCell(title: String, have: Boolean, accessibility: String, art: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = accessibility },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.grayscale(!have).alpha(if (have) 1f else 0.35f), contentAlignment = Alignment.Center) { art() }
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (have) Theme.ink else Theme.inkSoft,
            textAlign = TextAlign.Center,
            minLines = 2,
            maxLines = 2,
        )
    }
}

/** Accessories show their sprite; effects show what floats around the slime. */
@Composable
private fun CollectionItemIcon(item: de.hyprnode.slimy.model.CatalogItem, size: Int = 44) {
    if (item.kind == ItemKind.effect) {
        Box(
            Modifier
                .size(size.dp)
                .clip(RoundedCornerShape((size * 0.26f).dp))
                .background(Theme.well)
                .padding((size * 0.23f).dp),
            contentAlignment = Alignment.Center,
        ) {
            EffectIcon(item.id, Modifier.fillMaxSize())
        }
    } else {
        ItemIcon(item.sprite, size = size.dp)
    }
}

@Composable
private fun CollectionSkin(skin: Skin, stage: Stage, modifier: Modifier = Modifier) {
    val frames = SlimeSprites.frames(stage, SpriteAnim.idle)
    val anchors = SlimeSprites.anchors(stage, SpriteAnim.idle)
    PixelSlime(frame = frames.firstOrNull() ?: SlimeSprites.blank, skin = skin, stage = stage, anchor = anchors.firstOrNull(), modifier = modifier)
}

// MARK: - About

@Composable
private fun AboutContent(s: GameState) {
    val slime = s.slime
    val stats = s.progress.stats
    val traits = slime.personality.top.map { it.title }

    GroupedSection(header = "Personality", footer = "Its personality shifts a little with how you spend time together.") {
        Column(
            Modifier
                .padding(16.dp)
                .semantics(mergeDescendants = true) { },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("${slime.name} is ${traits.joinToString(" and ")}.", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Theme.ink)
            Text(slime.personality.dominant.blurb, style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft)
        }
        for (trait in Personality.Trait.allCases) {
            GroupedDivider()
            val value = slime.personality[trait].coerceIn(0.0, 1.0)
            val title = trait.title.replaceFirstChar { it.uppercase() }
            val percent = (value * 100).roundToInt()
            Column(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .clearAndSetSemantics {
                        contentDescription = title
                        stateDescription = "$percent percent"
                    },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row {
                    Text(title, style = MaterialTheme.typography.bodyMedium, color = Theme.ink)
                    Spacer(Modifier.weight(1f))
                    Text("$percent%", style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = Theme.inkSoft)
                }
                Meter(value = value, color = Theme.sky)
            }
        }
    }

    GroupedSection(header = "Your bond", footer = "Grows with petting, feeding, baths and playing together. It never goes down.") {
        Column(
            Modifier
                .padding(16.dp)
                .clearAndSetSemantics {
                    contentDescription = "Bond"
                    stateDescription = "${slime.bondStage.title}, ${slime.bond.toInt()} of 100"
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row {
                Text(slime.bondStage.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Theme.ink)
                Spacer(Modifier.weight(1f))
                Text("${slime.bond.toInt()} / 100", style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = Theme.inkSoft)
            }
            Meter(value = slime.bond / 100, color = Theme.love, height = 8.dp)
            JournalText.nextBond(slime.bond)?.let { (title, at) ->
                Text("$title at $at", style = MaterialTheme.typography.labelMedium, color = Theme.inkSoft)
            }
        }
    }

    GroupedSection(header = "Facts") {
        FactRow("Birthday", JournalText.longDate(slime.birthday))
        GroupedDivider()
        FactRow("Age", JournalText.days(slime.ageDays()))
        GroupedDivider()
        GroupedRow(
            title = "Level",
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Meter(value = slime.levelProgress, height = 5.dp, modifier = Modifier.width(60.dp))
                    Text("${slime.level}", style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"), color = Theme.inkSoft)
                }
            },
        )
        GroupedDivider()
        FactRow("Colour", slime.skin.title)
        GroupedDivider()
        FactRow("Favourite food", if (s.inventory.favoriteFound) slime.favorite.title else "Still a secret")
        GroupedDivider()
        FactRow("Favourite game", slime.favoriteGame.title)
    }

    GroupedSection(header = "Together") {
        val rows = listOf(
            "Meals" to stats.feeds,
            "Pets" to stats.pets,
            "Baths" to stats.baths,
            "Bedtimes" to stats.sleeps,
            "Rounds played" to stats.plays,
            "Really good rounds" to stats.goodGames,
            "Gifts given" to stats.giftsSent,
            "Gifts received" to stats.giftsReceived,
            "Friends" to stats.friends,
            "Coins earned" to s.wallet.earnedTotal,
            "Days visited" to s.player.daysActive.size,
            "Longest gift streak" to s.streak.best,
        )
        rows.forEachIndexed { index, (title, value) ->
            if (index > 0) GroupedDivider()
            FactRow(title, NumberFormat.getIntegerInstance().format(value))
        }
    }
}

@Composable
private fun FactRow(title: String, value: String) {
    GroupedRow(
        title = title,
        modifier = Modifier.semantics(mergeDescendants = true) { },
        trailing = { Text(value, style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"), color = Theme.inkSoft) },
    )
}

/** The journal's words, kept apart from the views so they can be tested. */
object JournalText {
    private val statIcons: Map<StatKey, String> = mapOf(
        StatKey.feeds to "apple", StatKey.pets to "heart", StatKey.baths to "bubbleItem", StatKey.plays to "ball",
        StatKey.goodGames to "furn.trophy", StatKey.sleeps to "starJelly", StatKey.giftsSent to "chest",
        StatKey.giftsReceived to "chest", StatKey.eventsSeen to "speech", StatKey.friends to "heart",
        StatKey.hugs to "heart", StatKey.visits to "flower", StatKey.purchases to "coin",
    )

    fun challengeIcon(template: ChallengeTemplate?): String {
        val goal = template?.goal ?: return "coin"
        return when (goal) {
            is ChallengeTemplate.Goal.score -> goal.game.icon
            is ChallengeTemplate.Goal.stat -> statIcons[goal.key] ?: "coin"
        }
    }

    fun achievementReward(achievement: Achievement): String {
        val crate = achievement.crate ?: return CrateCopy.coins(achievement.coins)
        return "${CrateCopy.coins(achievement.coins)} and a ${CrateCopy.name(crate).lowercase()}"
    }

    fun days(n: Int): String = if (n == 1) "1 day" else "$n days"

    /** The next bond stage and where it starts, or null at the top. */
    fun nextBond(bond: Double): Pair<String, Int>? = when {
        bond < 20 -> "Next: Familiar" to 20
        bond < 50 -> "Next: Close" to 50
        bond < 80 -> "Next: Inseparable" to 80
        else -> null
    }

    /** Memories grouped by month, newest first, in the order given. */
    fun months(entries: List<MemoryEntry>, zone: ZoneId = ZoneId.systemDefault()): List<Pair<YearMonth, List<MemoryEntry>>> {
        val out = mutableListOf<Pair<YearMonth, MutableList<MemoryEntry>>>()
        for (entry in entries) {
            val month = YearMonth.from(entry.at.atZone(zone))
            val last = out.lastOrNull()
            if (last != null && last.first == month) last.second.add(entry) else out.add(month to mutableListOf(entry))
        }
        return out
    }

    fun monthTitle(month: YearMonth, locale: Locale = Locale.getDefault()): String =
        month.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale))

    fun longDate(date: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withZone(zone).format(date)

    fun mediumDate(date: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(zone).format(date)
}
