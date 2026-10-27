package de.hyprnode.slimy.ui.screens.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.core.LocalFriendsStore
import de.hyprnode.slimy.model.GameID
import de.hyprnode.slimy.online.LeaderboardEntry
import de.hyprnode.slimy.online.LeaderboardGame
import de.hyprnode.slimy.ui.kit.AppButton
import de.hyprnode.slimy.ui.kit.ChipTabs
import de.hyprnode.slimy.ui.kit.GroupedDivider
import de.hyprnode.slimy.ui.kit.GroupedSection
import de.hyprnode.slimy.ui.kit.SheetScaffold
import de.hyprnode.slimy.ui.kit.Tag
import de.hyprnode.slimy.ui.kit.Theme
import kotlinx.coroutines.launch
import java.text.NumberFormat

/**
 * The friends leaderboard on its own, for screens outside Friends (the
 * arcade): best scores of you and your friends, one game at a time, opening
 * on [game] when given (App/UI/Screens/LeaderboardView.swift, `LeaderboardSheet`).
 */
@Composable
fun LeaderboardScreen(onClose: () -> Unit, game: GameID? = null) {
    LeaderboardPage(onBack = null, onClose = onClose, game = game)
}

/** Best scores of you and your friends, one game at a time, with your own row highlighted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LeaderboardPage(onBack: (() -> Unit)?, onClose: () -> Unit, game: GameID? = null) {
    val friends = LocalFriendsStore.current
    val isOnline by friends.isOnline.collectAsStateWithLifecycle()
    val games by friends.leaderboard.collectAsStateWithLifecycle()
    val loading by friends.isLoadingLeaderboard.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var selection by rememberSaveable { mutableStateOf(game) }
    var showGoOnline by remember { mutableStateOf(false) }
    // Why the board couldn't load, while there is nothing to show.
    var loadError by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun load() {
        friends.loadLeaderboard()
        if (!friends.isOnline.value) {
            loadError = null
            return
        }
        loadError = if (friends.leaderboard.value.isEmpty()) friends.lastError.value else null
        friends.lastError.value = null
    }

    LaunchedEffect(isOnline) { if (isOnline) load() else friends.loadLeaderboard() }

    SheetScaffold(
        title = "Leaderboard",
        onDone = onClose,
        inline = onBack != null,
        navigationIcon = { if (onBack != null) BackButton(onBack) },
    ) { padding ->
        when {
            !isOnline -> EmptyState(
                padding, Icons.Filled.EmojiEvents, "Compare scores with friends",
                "Go online to see your friends' best scores next to yours.",
            ) {
                AppButton("Go Online", compact = true) { showGoOnline = true }
            }
            games.isEmpty() && loading -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { WaitingLine("Loading scores…") }
            games.isEmpty() -> {
                val error = loadError
                if (error != null) {
                    EmptyState(padding, Icons.Filled.WifiOff, "Couldn't load scores", error) {
                        TextButton(onClick = { scope.launch { load() } }) { Text("Try Again") }
                    }
                } else {
                    EmptyState(
                        padding, Icons.Filled.EmojiEvents, "No scores yet",
                        "Play a game in the arcade. Your best scores and your friends' show up here.",
                    ) {
                        TextButton(onClick = { scope.launch { load() } }) { Text("Refresh") }
                    }
                }
            }
            else -> {
                val current = games.firstOrNull { it.game == selection } ?: games[0]
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = {
                        refreshing = true
                        scope.launch {
                            load()
                            refreshing = false
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = padding.calculateTopPadding()),
                ) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, padding.calculateBottomPadding() + 32.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (games.size > 1) {
                            item {
                                ChipTabs(
                                    options = games.map { it.game to it.game.title },
                                    selection = current.game,
                                    onSelect = { selection = it },
                                )
                            }
                        }
                        item {
                            Column {
                                SectionHeader(current.game.title)
                                GroupedSection(footer = summary(current)) {
                                    current.entries.forEachIndexed { index, entry ->
                                        if (index > 0) GroupedDivider()
                                        LeaderboardRow(entry, current.rank(entry))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showGoOnline) {
        FullScreenSheet(onDismiss = { showGoOnline = false }) { GoOnlineScreen(onDone = { showGoOnline = false }) }
    }
}

@Composable
private fun EmptyState(
    padding: PaddingValues,
    icon: ImageVector,
    title: String,
    text: String,
    action: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, tint = Theme.inkSoft, modifier = Modifier.size(48.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Theme.ink, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft, textAlign = TextAlign.Center)
        action()
    }
}

private fun summary(game: LeaderboardGame): String {
    val players = game.entries.size
    val mine = game.mine ?: return "You haven't set a score in ${game.game.title} yet. Only each player's best round counts."
    val rank = game.rank(mine)
    if (players == 1) return "Only you so far. Challenge a friend from the arcade."
    if (rank == 1) return "You're first of $players. Only each player's best round counts."
    return "You're number $rank of $players. Only each player's best round counts."
}

/** One place in a game's leaderboard. */
@Composable
private fun LeaderboardRow(entry: LeaderboardEntry, rank: Int) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (entry.me) youTint else Theme.card)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clearAndSetSemantics {
                contentDescription = "Place $rank: ${entry.slimeName}, ${entry.ownerName}${if (entry.me) ", you" else ""}"
                stateDescription = "${entry.best} points"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            numbers.format(rank),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (rank == 1) Theme.gold else Theme.inkSoft,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 28.dp),
        )
        FriendAvatar(entry.skin, entry.stage, size = 44.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    entry.slimeName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Theme.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (entry.me) Tag("You")
            }
            Text(entry.ownerName, style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            numbers.format(entry.best),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Theme.ink,
        )
    }
}
