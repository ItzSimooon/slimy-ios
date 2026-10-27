package de.hyprnode.slimy.arcade

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.art.PixelSlime
import de.hyprnode.slimy.art.rememberReduceMotion
import de.hyprnode.slimy.core.Feedback
import de.hyprnode.slimy.core.LocalDailyStore
import de.hyprnode.slimy.core.LocalFriendsStore
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.model.DailyPlanner
import de.hyprnode.slimy.model.GameID
import de.hyprnode.slimy.model.GameRecord
import de.hyprnode.slimy.model.Need
import de.hyprnode.slimy.model.SlimeSprites
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.model.Stage
import de.hyprnode.slimy.model.canPlay
import de.hyprnode.slimy.model.record
import de.hyprnode.slimy.online.Challenge
import de.hyprnode.slimy.online.LeaderboardEntry
import de.hyprnode.slimy.online.LeaderboardGame
import de.hyprnode.slimy.ui.kit.AppButton
import de.hyprnode.slimy.ui.kit.Card
import de.hyprnode.slimy.ui.kit.ChipTabs
import de.hyprnode.slimy.ui.kit.CoinCounter
import de.hyprnode.slimy.ui.kit.GroupedDivider
import de.hyprnode.slimy.ui.kit.GroupedSection
import de.hyprnode.slimy.ui.kit.ItemIcon
import de.hyprnode.slimy.ui.kit.Meter
import de.hyprnode.slimy.ui.kit.Tag
import de.hyprnode.slimy.ui.kit.Theme
import de.hyprnode.slimy.ui.kit.TileButton
import de.hyprnode.slimy.ui.screens.friends.GoOnlineScreen
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.roundToInt

/** The game on screen over the menu, and the friend's challenge it answers. */
private data class Playing(val game: GameID, val challenge: Challenge?)

/**
 * The arcade, shown full screen: a native menu with today's game, friends'
 * challenges and every game. Picking one hands over to [GameHost], which
 * plays it on top of the menu (App/Arcade/ArcadeView.swift).
 */
@Composable
fun ArcadeScreen(onClose: () -> Unit) {
    val store = LocalGameStore.current
    val friends = LocalFriendsStore.current
    val daily = LocalDailyStore.current
    val reduceMotion = rememberReduceMotion()
    val playChallenge by friends.playChallenge.collectAsStateWithLifecycle()
    var playing by remember { mutableStateOf<Playing?>(null) }
    var showLeaderboard by remember { mutableStateOf(false) }

    fun open(game: GameID, challenge: Challenge? = null) {
        val s = store.state.value
        if (!s.canPlay) {
            store.showToast("${s.slime.name} is too tired.", good = false)
            return
        }
        playing = Playing(game, challenge)
    }

    /** Opened to play a friend's challenge. */
    fun takeChallenge() {
        if (playing != null) return
        val c = friends.playChallenge.value ?: return
        friends.playChallenge.value = null
        open(c.game, c)
    }

    LaunchedEffect(Unit) {
        // Opened after midnight without leaving the app: today's game, not yesterday's.
        if (daily.plan.value.day != DailyPlanner.dayKey(Instant.now())) daily.refresh()
    }
    LaunchedEffect(playChallenge?.id) {
        if (playChallenge != null) takeChallenge()
    }
    BackHandler(enabled = playing == null) { onClose() }

    Box(Modifier.fillMaxSize()) {
        ArcadeMenu(
            paused = playing != null,
            open = { game, challenge -> open(game, challenge) },
            showLeaderboard = {
                Feedback.shared.play(Sound.tap)
                showLeaderboard = true
            },
            close = {
                Feedback.shared.play(Sound.tap)
                onClose()
            },
            modifier = if (playing != null) Modifier.clearAndSetSemantics { } else Modifier,
        )

        AnimatedContent(
            targetState = playing,
            transitionSpec = {
                if (reduceMotion) EnterTransition.None togetherWith ExitTransition.None
                else fadeIn(tween(200)) togetherWith fadeOut(tween(200))
            },
            label = "game",
        ) { shown ->
            if (shown != null) {
                GameHost(game = shown.game, challenge = shown.challenge) { playing = null }
            } else {
                Box(Modifier.fillMaxSize())
            }
        }
    }

    if (showLeaderboard) {
        LeaderboardSheet { showLeaderboard = false }
    }
}

// MARK: Menu

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArcadeMenu(
    paused: Boolean,
    open: (GameID, Challenge?) -> Unit,
    showLeaderboard: () -> Unit,
    close: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val store = LocalGameStore.current
    val friends = LocalFriendsStore.current
    val state by store.state.collectAsStateWithLifecycle()
    // The open challenges are worked out from the challenge list; collecting it keeps them fresh.
    val challenges by friends.challenges.collectAsStateWithLifecycle()
    val openChallenges = remember(challenges) { friends.openChallenges }
    val columns = if (LocalDensity.current.fontScale >= 1.5f) 1 else 2
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier.nestedScroll(behavior.nestedScrollConnection),
        containerColor = Theme.backdrop,
        topBar = {
            LargeTopAppBar(
                title = { Text("Arcade", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = close) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    CoinCounter(coins = state.wallet.coins, inToolbar = true)
                    IconButton(onClick = showLeaderboard) {
                        Icon(Icons.Outlined.EmojiEvents, contentDescription = "Leaderboard")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Theme.backdrop,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = behavior,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                ArcadeEnergyCard(state.slime.needs.energy, state.slime.name, state.canPlay)

                TodayCard(playable = state.canPlay, paused = paused) { open(GameID.daily, null) }

                if (openChallenges.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ArcadeSectionHeader("Challenges", "Same board as your friend. Beat their score.")
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Theme.radius))
                                .background(Theme.card),
                        ) {
                            for ((i, c) in openChallenges.withIndex()) {
                                if (i > 0) GroupedDivider(inset = 68.dp)
                                ArcadeChallengeRow(c, enabled = state.canPlay) { open(c.game, c) }
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ArcadeSectionHeader("Games", null)
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (row in GameID.classics.chunked(columns)) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                for (game in row) {
                                    val record = state.record(game)
                                    ArcadeGameTile(
                                        game = game,
                                        record = record,
                                        level = state.slime.level,
                                        favorite = state.slime.favoriteGame == game && record.plays >= 3,
                                        highlightNew = state.progress.stats.plays > 0,
                                        playable = state.canPlay,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight(),
                                    ) { open(game, null) }
                                }
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A bold section title over cards. */
@Composable
private fun ArcadeSectionHeader(title: String, detail: String?) {
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Theme.ink,
            modifier = Modifier.semantics { heading() },
        )
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft)
        }
    }
}

/** The slime's energy: every round uses some. */
@Composable
private fun ArcadeEnergyCard(energy: Double, name: String, canPlay: Boolean) {
    val percent = energy.roundToInt()
    val note = ArcadeRules.energyNote(energy, name, canPlay)
    Card(
        Modifier.clearAndSetSemantics {
            contentDescription = "Energy $percent percent"
            stateDescription = note
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ItemIcon(Theme.icon(Need.energy), size = 44.dp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Energy",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Theme.ink,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "$percent%",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.SemiBold,
                        color = Theme.inkSoft,
                    )
                }
                Meter(value = energy / 100, color = Theme.color(Need.energy), height = 8.dp)
                Text(note, style = MaterialTheme.typography.bodySmall, color = if (canPlay) Theme.inkSoft else Theme.danger)
            }
        }
    }
}

/** A friend's open challenge, as a list row. */
@Composable
private fun ArcadeChallengeRow(challenge: Challenge, enabled: Boolean, open: () -> Unit) {
    val ends = ArcadeRules.relative(challenge.expiresAt, Instant.now())
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = "Plays ${challenge.game.title} on your friend's board",
            ) {
                Feedback.shared.play(Sound.tap)
                open()
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ItemIcon(challenge.game.icon, size = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${challenge.friendName} challenges you",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = Theme.ink,
            )
            Text(
                "${challenge.game.title} · Beat ${challenge.theirScore ?: 0}",
                style = MaterialTheme.typography.bodyMedium,
                color = Theme.inkSoft,
            )
            Text("Ends $ends", style = MaterialTheme.typography.bodySmall, color = Theme.inkSoft)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Theme.inkSoft.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** One game in the grid: its pixel icon, record and lock state. */
@Composable
private fun ArcadeGameTile(
    game: GameID,
    record: GameRecord,
    level: Int,
    favorite: Boolean,
    /** Marks games you haven't tried yet, once you have played some. */
    highlightNew: Boolean,
    playable: Boolean,
    modifier: Modifier = Modifier,
    open: () -> Unit,
) {
    val locked = level < game.unlockLevel
    val value = buildList {
        add(
            when {
                locked -> "Unlocks at level ${game.unlockLevel}"
                record.plays == 0 -> "Not played yet"
                else -> "Best ${record.best} points"
            },
        )
        if (favorite) add("Favourite game")
    }.joinToString(", ")
    val card = Theme.card
    TileButton(
        onClick = {
            Feedback.shared.play(Sound.tap)
            open()
        },
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = game.title
            stateDescription = value
        },
        fill = card,
        enabled = !locked && playable,
        onClickLabel = if (locked) null else game.blurb,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 136.dp)
                .padding(14.dp)
                .clearAndSetSemantics { },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box {
                    ItemIcon(game.icon, Modifier.grayscale(locked), size = 52.dp)
                    if (locked) {
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = 5.dp, y = 5.dp)
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF8E8E93))
                                .border(2.dp, card, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (favorite) {
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Theme.love.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Favorite, contentDescription = null, tint = Theme.love, modifier = Modifier.size(14.dp))
                    }
                } else if (highlightNew && !locked && record.plays == 0) {
                    Tag("NEW")
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    game.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Theme.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                when {
                    locked -> IconLabel(Icons.Filled.Lock, "Level ${game.unlockLevel}")
                    record.plays == 0 -> Text(
                        "Not played yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Theme.inkSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    else -> IconLabel(Icons.Filled.EmojiEvents, "Best ${record.best}")
                }
            }
        }
    }
}

/** SwiftUI's `Label`: a small icon before the text. */
@Composable
private fun IconLabel(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Theme.inkSoft, modifier = Modifier.size(14.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = Theme.inkSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// MARK: Leaderboard

/**
 * Best scores of you and your friends, one game at a time, with your own
 * row highlighted (the arcade's trophy button; App/UI/Screens/LeaderboardView.swift).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeaderboardSheet(onDone: () -> Unit) {
    val friends = LocalFriendsStore.current
    val isOnline by friends.isOnline.collectAsStateWithLifecycle()
    val board by friends.leaderboard.collectAsStateWithLifecycle()
    val loading by friends.isLoadingLeaderboard.collectAsStateWithLifecycle()
    var selection by remember { mutableStateOf<GameID?>(null) }
    /** Why the board couldn't load, while there is nothing to show. */
    var loadError by remember { mutableStateOf<String?>(null) }
    var showGoOnline by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    suspend fun load() {
        friends.loadLeaderboard()
        if (!friends.isOnline.value) {
            loadError = null
            return
        }
        loadError = if (friends.leaderboard.value.isEmpty()) friends.lastError.value else null
        friends.lastError.value = null
    }

    LaunchedEffect(isOnline) { load() }

    ModalBottomSheet(onDismissRequest = onDone, sheetState = sheet, containerColor = Theme.backdrop) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Leaderboard",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Theme.ink,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                TextButton(onClick = onDone) { Text("Done", fontWeight = FontWeight.SemiBold) }
            }
            when {
                !isOnline -> EmptyBoard(
                    Icons.Outlined.EmojiEvents,
                    "Compare scores with friends",
                    "Go online to see your friends' best scores next to yours.",
                ) {
                    AppButton("Go Online", compact = true) { showGoOnline = true }
                }
                board.isEmpty() && loading -> Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Loading scores…", style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft)
                }
                board.isEmpty() && loadError != null -> EmptyBoard(
                    Icons.Filled.WifiOff,
                    "Couldn't load scores",
                    loadError ?: "",
                ) {
                    TextButton(onClick = { scope.launch { load() } }) { Text("Try Again") }
                }
                board.isEmpty() -> EmptyBoard(
                    Icons.Outlined.EmojiEvents,
                    "No scores yet",
                    "Play a game in the arcade. Your best scores and your friends' show up here.",
                ) {
                    TextButton(onClick = { scope.launch { load() } }) { Text("Refresh") }
                }
                else -> Board(board, selection) { selection = it }
            }
        }
    }

    if (showGoOnline) {
        Dialog(
            onDismissRequest = { showGoOnline = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            GoOnlineScreen(onDone = { showGoOnline = false })
        }
    }
}

@Composable
private fun EmptyBoard(icon: ImageVector, title: String, detail: String, action: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Theme.inkSoft, modifier = Modifier.size(44.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Theme.ink, textAlign = TextAlign.Center)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft, textAlign = TextAlign.Center)
        action()
    }
}

@Composable
private fun Board(games: List<LeaderboardGame>, selection: GameID?, select: (GameID) -> Unit) {
    val current = games.firstOrNull { it.game == selection } ?: games[0]
    if (games.size > 1) {
        ChipTabs(options = games.map { it.game to it.game.title }, selection = current.game, onSelect = select, bleed = 16.dp)
    }
    GroupedSection(header = current.game.title, footer = leaderboardSummary(current)) {
        for ((i, entry) in current.entries.withIndex()) {
            if (i > 0) GroupedDivider(inset = 56.dp)
            LeaderboardRow(entry, current.rank(entry))
        }
    }
}

private fun leaderboardSummary(game: LeaderboardGame): String {
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
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (entry.me) Theme.accent.copy(alpha = 0.14f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clearAndSetSemantics {
                contentDescription = "Place $rank: ${entry.slimeName}, ${entry.ownerName}${if (entry.me) ", you" else ""}"
                stateDescription = "${entry.best} points"
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$rank",
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = if (rank == 1) Theme.gold else Theme.inkSoft,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 28.dp),
        )
        SlimeAvatar(entry.skin, entry.stage, 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
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
            "${entry.best}",
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = Theme.ink,
        )
    }
}

/** A friend's slime in a round well, still. */
@Composable
private fun SlimeAvatar(skin: Skin, stage: Stage, size: Dp) {
    val frame = SlimeSprites.frames(stage, SpriteAnim.idle).firstOrNull() ?: SlimeSprites.blank
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Theme.well),
        contentAlignment = Alignment.Center,
    ) {
        PixelSlime(frame = frame, skin = skin, stage = stage, modifier = Modifier.size(width = size * 0.8f, height = size * 0.66f))
    }
}

/** Bath time (App/UI/Screens/BathView.swift). */
@Composable
fun BathScreen(onClose: () -> Unit) {
    BathView(onClose)
}
