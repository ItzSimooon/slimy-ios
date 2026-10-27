package de.hyprnode.slimy.arcade

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.art.PixelItem
import de.hyprnode.slimy.art.rememberClock
import de.hyprnode.slimy.art.rememberReduceMotion
import de.hyprnode.slimy.art.referenceSeconds
import de.hyprnode.slimy.core.Feedback
import de.hyprnode.slimy.core.LocalDailyStore
import de.hyprnode.slimy.core.LocalFriendsStore
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.model.GameID
import de.hyprnode.slimy.model.GameResult
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.model.arcade.GameSession
import de.hyprnode.slimy.model.canPlay
import de.hyprnode.slimy.model.finish
import de.hyprnode.slimy.model.markTutorialSeen
import de.hyprnode.slimy.model.record
import de.hyprnode.slimy.online.Challenge
import de.hyprnode.slimy.ui.kit.AppButton
import de.hyprnode.slimy.ui.kit.AppButtonKind
import de.hyprnode.slimy.ui.kit.HUDButton
import de.hyprnode.slimy.ui.kit.ItemIcon
import de.hyprnode.slimy.ui.kit.Theme
import kotlinx.coroutines.launch
import java.time.Instant

/** What a finished round showed on its results card. */
data class RoundSummary(
    val result: GameResult,
    val paid: Boolean,
    val record: Boolean,
    /** Best score before this round. */
    val previousBest: Int,
    val line: String,
)

/** The card over the game, if any. */
private sealed class HostCard {
    data object tutorial : HostCard()
    data class results(val summary: RoundSummary) : HostCard()
    data class pause(val session: SessionState) : HostCard()
}

/** Where the day's tutorial is marked as seen (the day's id), like `UserDefaults` "slimy.daily.tutorial". */
private const val dailyTutorialKey = "slimy.daily.tutorial"

/**
 * Runs one game: introduction, pause, results and the reward. Leaving the
 * app pauses the round; the reward for a round is paid exactly once.
 * [challenge] is set when this round answers a friend's challenge: same
 * seed, same board.
 */
@Composable
fun GameHost(game: GameID, challenge: Challenge? = null, close: () -> Unit) {
    val store = LocalGameStore.current
    val friends = LocalFriendsStore.current
    val daily = LocalDailyStore.current
    val context = LocalContext.current
    val reduceMotion = rememberReduceMotion()
    val state by store.state.collectAsStateWithLifecycle()
    val plan by daily.plan.collectAsStateWithLifecycle()
    val isOnline by friends.isOnline.collectAsStateWithLifecycle()
    val friendList by friends.friends.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { context.getSharedPreferences("slimy", Context.MODE_PRIVATE) }

    var session by remember { mutableStateOf<SessionState?>(null) }
    var showTutorial by remember { mutableStateOf(false) }
    var confirmQuit by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<RoundSummary?>(null) }
    var lastSeed by remember { mutableStateOf(0uL) }
    var choosingFriend by remember { mutableStateOf(false) }
    var challengeLine by remember { mutableStateOf<String?>(null) }

    // The daily game brings its own name, rules and target.
    val spec = if (game == GameID.daily) plan.game else null
    val title = spec?.title ?: game.title
    val blurb = spec?.blurb ?: game.blurb
    val rules = spec?.tutorial ?: game.tutorial
    val goodScore = spec?.goodScore ?: game.goodScore

    fun tutorialSeen(): Boolean {
        if (spec != null) return prefs.getString(dailyTutorialKey, null) == spec.id
        return store.state.value.record(game).tutorialSeen
    }

    fun markTutorialSeen() {
        if (spec != null) {
            prefs.edit().putString(dailyTutorialKey, spec.id).apply()
        } else {
            store.update { it.markTutorialSeen(game) }
        }
    }

    fun start() {
        val now = store.state.value
        if (!now.canPlay) {
            store.showToast("${now.slime.name} is too tired.", good = false)
            close()
            return
        }
        confirmQuit = false
        challengeLine = null
        // A challenge is played once on its seed; "Again" afterwards is a normal round.
        val seed = if (summary == null && challenge != null) challenge.seedValue else null
        summary = null
        val next = GameSession(game, seed, spec)
        lastSeed = next.seed
        session = SessionState(next)
    }

    fun finish() {
        val s = session ?: return
        if (summary != null) return
        val result = s.session.result(Instant.now())
        if (result.score <= 0) {
            summary = RoundSummary(result, paid = false, record = false,
                previousBest = store.state.value.record(game).best, line = ArcadeRules.noPointsLine)
            Feedback.shared.play(Sound.whoosh)
            return
        }
        if (challenge != null && s.seed == challenge.seedValue) {
            ArcadeScope.launch { friends.answer(challenge, result.score) }
        }
        val before = store.state.value.record(game)
        // The results card shows the slime's line, so it isn't repeated in a toast.
        val outcome = store.apply(sound = Sound.coin, toast = false) { it.finish(result) }
        if (outcome.ok) {
            friends.contributeCoop(result)
            // Friends see your best scores on their leaderboard.
            if (game != GameID.daily && friends.isOnline.value) {
                val best = store.state.value.record(game).best
                ArcadeScope.launch { friends.uploadRecords(mapOf(game to best)) }
            }
        }
        // Every day's game is different, so its scores are never records.
        val record = game != GameID.daily && outcome.ok && result.score > before.best && before.plays > 0
        summary = RoundSummary(result, paid = outcome.ok, record = record, previousBest = before.best, line = outcome.line)
        if (record) Feedback.shared.play(Sound.levelUp)
    }

    LaunchedEffect(Unit) {
        if (tutorialSeen()) start() else showTutorial = true
    }
    // Leaving the app (or a call, the notification shade) pauses the round.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { session?.paused = true }
    val ended = session?.ended ?: false
    LaunchedEffect(session, ended) { if (ended) finish() }

    BackHandler {
        val s = session
        when {
            showTutorial -> if (s == null) close() else showTutorial = false
            summary != null -> close()
            s != null && s.paused && !s.ended -> if (confirmQuit) confirmQuit = false else s.paused = false
            s != null && !s.ended -> s.paused = true
            else -> close()
        }
    }

    val card: HostCard? = when {
        showTutorial -> HostCard.tutorial
        summary != null -> HostCard.results(summary!!)
        session?.paused == true -> HostCard.pause(session!!)
        else -> null
    }
    val modalShown = card != null

    Box(
        Modifier
            .fillMaxSize()
            .background(Theme.backdrop),
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                .then(if (modalShown) Modifier.clearAndSetSemantics { } else Modifier),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TopBar(
                session = session,
                title = title,
                subtitle = ArcadeRules.hostSubtitle(
                    goodScore = goodScore,
                    isDaily = spec != null,
                    challengeFriend = challenge?.takeIf { lastSeed == it.seedValue && summary == null }?.friendName,
                    challengeScore = challenge?.theirScore ?: 0,
                    best = state.record(game).best,
                ),
                close = close,
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Theme.radius))
                    .background(Theme.card),
            ) {
                val s = session
                if (s != null) {
                    key(s.id) { GameBoard(s) }
                    if (!s.running) Box(Modifier.matchParentSize().swallowTouches())
                }
            }
        }

        AnimatedContent(
            targetState = card,
            transitionSpec = {
                if (reduceMotion) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    (fadeIn(tween(250)) + scaleIn(tween(250), initialScale = 0.96f)) togetherWith
                        (fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.96f))
                }
            },
            contentKey = { it?.javaClass },
            label = "hostCard",
        ) { shown ->
            when (shown) {
                null -> Box(Modifier.fillMaxSize())
                HostCard.tutorial -> HostModal {
                    Tutorial(
                        game = game,
                        title = title,
                        blurb = blurb,
                        rules = rules,
                        goodScore = goodScore,
                        fromPause = session != null,
                        back = close,
                        go = {
                            markTutorialSeen()
                            showTutorial = false
                            start()
                        },
                        gotIt = { showTutorial = false },
                    )
                }
                is HostCard.pause -> HostModal {
                    PauseMenu(
                        session = shown.session,
                        confirmQuit = confirmQuit,
                        setConfirmQuit = { confirmQuit = it },
                        howToPlay = { showTutorial = true },
                    )
                }
                is HostCard.results -> HostModal {
                    val sum = summary ?: shown.summary
                    Results(
                        summary = sum,
                        game = game,
                        best = state.record(game).best,
                        canPlay = state.canPlay,
                        slimeName = state.slime.name,
                        reduceMotion = reduceMotion,
                        challengeResult = challenge?.takeIf { lastSeed == it.seedValue }?.let { c ->
                            val theirs = c.theirScore ?: 0
                            Pair(ArcadeRules.challengeOutcome(c.friendName, sum.result.score, theirs), sum.result.score > theirs)
                        },
                        canChallenge = sum.result.score > 0 && game != GameID.daily && isOnline && friendList.isNotEmpty(),
                        challengeLine = challengeLine,
                        chooseFriend = { choosingFriend = true },
                        back = close,
                        again = { start() },
                    )
                }
            }
        }

        val pending = summary
        if (choosingFriend && pending != null) {
            AlertDialog(
                onDismissRequest = { choosingFriend = false },
                title = { Text("Who do you want to challenge?") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("They play the same board and try to beat ${pending.result.score}.")
                        for (friend in friendList) {
                            TextButton(
                                onClick = {
                                    choosingFriend = false
                                    val seed = lastSeed
                                    scope.launch {
                                        challengeLine = friends.challenge(friend, pending.result, seed) ?: friends.lastError.value
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(friend.slime.name, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { choosingFriend = false }) { Text("Cancel") }
                },
            )
        }
    }
}

// MARK: Top bar

@Composable
private fun TopBar(session: SessionState?, title: String, subtitle: String, close: () -> Unit) {
    val running = session?.let { !it.ended } ?: false
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HUDButton(
            icon = if (running) Icons.Filled.Pause else Icons.Filled.Close,
            label = if (running) "Pause" else "Close",
        ) {
            if (session != null && !session.ended) session.paused = true else close()
        }
        Column(
            Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) { },
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Theme.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Theme.inkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (session != null) {
            if (session.status.isNotEmpty()) {
                Text(
                    session.status,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                    color = Theme.inkSoft,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Theme.well)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            val score = session.score
            Text(
                "$score",
                style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.Bold,
                color = Theme.ink,
                maxLines = 1,
                modifier = Modifier.clearAndSetSemantics { contentDescription = "$score points" },
            )
        }
    }
}

// MARK: Cards

/** A card over the dimmed game: the tutorial, the pause menu or the results. */
@Composable
private fun HostModal(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .swallowTouches()
            .semantics { isTraversalGroup = true },
    ) {
        val minHeight = maxHeight
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .safeDrawingPadding()
                    .padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(Theme.card)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun Tutorial(
    game: GameID,
    title: String,
    blurb: String,
    rules: List<String>,
    goodScore: Int,
    fromPause: Boolean,
    back: () -> Unit,
    go: () -> Unit,
    gotIt: () -> Unit,
) {
    ItemIcon(game.icon, Modifier.clearAndSetSemantics { }, size = 72.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Theme.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(blurb, style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft, textAlign = TextAlign.Center)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((i, line) in rules.withIndex()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Theme.accent)
                        .clearAndSetSemantics { },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${i + 1}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Theme.onAccent)
                }
                Text(line, style = MaterialTheme.typography.bodyLarge, color = Theme.ink, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Star, contentDescription = null, tint = Theme.inkSoft, modifier = Modifier.size(16.dp))
        Text("Anything from $goodScore points is a good round.", style = MaterialTheme.typography.bodySmall, color = Theme.inkSoft)
    }
    if (!fromPause) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppButton("Back", Modifier.weight(1f), kind = AppButtonKind.quiet, action = back)
            AppButton("Let's Go", Modifier.weight(1f), systemIcon = Icons.Filled.PlayArrow, action = go)
        }
    } else {
        // Opened from the pause menu: the round stays paused.
        AppButton("Got It", action = gotIt)
    }
}

@Composable
private fun PauseMenu(
    session: SessionState,
    confirmQuit: Boolean,
    setConfirmQuit: (Boolean) -> Unit,
    howToPlay: () -> Unit,
) {
    Icon(Icons.Filled.PauseCircle, contentDescription = null, tint = Theme.accent, modifier = Modifier.size(44.dp))
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Paused",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Theme.ink,
            modifier = Modifier.semantics { heading() },
        )
        Text(ArcadeRules.pointsSoFar(session.score), style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft)
    }
    if (confirmQuit) {
        Text(
            ArcadeRules.quitWarning(session.score),
            style = MaterialTheme.typography.bodyLarge,
            color = Theme.ink,
            textAlign = TextAlign.Center,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppButton("Keep Playing", Modifier.weight(1f), kind = AppButtonKind.secondary) { setConfirmQuit(false) }
            AppButton("End Round", Modifier.weight(1f), kind = AppButtonKind.danger) {
                setConfirmQuit(false)
                session.paused = false
                session.end()
            }
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AppButton("Resume", systemIcon = Icons.Filled.PlayArrow) { session.paused = false }
            AppButton("How to Play", systemIcon = Icons.AutoMirrored.Filled.HelpOutline, kind = AppButtonKind.secondary, action = howToPlay)
            AppButton("End Round", systemIcon = Icons.Filled.SportsScore, kind = AppButtonKind.quiet) { setConfirmQuit(true) }
        }
    }
}

@Composable
private fun Results(
    summary: RoundSummary,
    game: GameID,
    best: Int,
    canPlay: Boolean,
    slimeName: String,
    reduceMotion: Boolean,
    /** The line about the friend's challenge and whether you won, when this round answered one. */
    challengeResult: Pair<String, Boolean>?,
    canChallenge: Boolean,
    challengeLine: String?,
    chooseFriend: () -> Unit,
    back: () -> Unit,
    again: () -> Unit,
) {
    ResultSlime(
        anim = if (summary.record) SpriteAnim.celebrate else if (summary.paid) SpriteAnim.play else SpriteAnim.sad,
        reduceMotion = reduceMotion,
    )
    val score = summary.result.score
    Column(
        Modifier.semantics(mergeDescendants = true) { },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            ArcadeRules.headline(summary.record, summary.paid, score, summary.result.goodScore),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (summary.record) Theme.gold else Theme.inkSoft,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "$score",
            style = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            color = Theme.ink,
            maxLines = 1,
        )
        Text(if (score == 1) "point" else "points", style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft)
    }

    if (summary.paid) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Reward("coin", "+${summary.result.coins}", "${summary.result.coins} coins")
            Reward("starJelly", "+${summary.result.xp} XP", "${summary.result.xp} experience")
        }
        Text(
            summary.line,
            style = Theme.voice(16.sp),
            color = Theme.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.clearAndSetSemantics { contentDescription = "$slimeName says: ${summary.line}" },
        )
    } else {
        Text(summary.line, style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft, textAlign = TextAlign.Center)
    }

    if (game != GameID.daily && summary.paid) {
        Text(
            if (summary.record) "Previous best: ${summary.previousBest}" else "Best: $best",
            style = MaterialTheme.typography.bodySmall,
            color = Theme.inkSoft,
        )
    }

    if (challengeResult != null) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (challengeResult.second) Icons.Filled.EmojiEvents else Icons.Filled.Flag,
                contentDescription = null,
                tint = Theme.love,
                modifier = Modifier.size(18.dp),
            )
            Text(
                challengeResult.first,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Theme.love,
                textAlign = TextAlign.Center,
            )
        }
    } else if (canChallenge) {
        AppButton("Challenge a Friend", icon = "flag", kind = AppButtonKind.secondary, action = chooseFriend)
    }
    if (challengeLine != null) {
        Text(challengeLine, style = MaterialTheme.typography.bodySmall, color = Theme.inkSoft, textAlign = TextAlign.Center)
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AppButton("Back", Modifier.weight(1f), kind = AppButtonKind.quiet, action = back)
        AppButton("Again", Modifier.weight(1f), systemIcon = Icons.Filled.Refresh, enabled = canPlay, action = again)
    }
    if (!canPlay) {
        Text(
            "$slimeName is too tired for another round.",
            style = MaterialTheme.typography.bodySmall,
            color = Theme.inkSoft,
            textAlign = TextAlign.Center,
        )
    }
}

/** The slime reacting to the round: twelve frames a second, holding still with reduced motion. */
@Composable
private fun ResultSlime(anim: SpriteAnim, reduceMotion: Boolean) {
    val clock by rememberClock(1000L / 12, running = !reduceMotion)
    GameSlime(height = 72.dp, time = referenceSeconds(clock), anim = anim, modifier = Modifier.height(72.dp))
}

@Composable
private fun Reward(icon: String, text: String, label: String) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(Theme.well)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelItem(icon, Modifier.size(18.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = Theme.ink,
        )
    }
}

// MARK: The board

/** Picks the view for a game. */
@Composable
private fun GameBoard(session: SessionState) {
    when (session.game) {
        GameID.snackCatcher -> SnackCatcherView(session)
        GameID.slimeJump -> SlimeJumpView(session)
        GameID.bubblePop -> BubblePopView(session)
        GameID.memoryGarden -> MemoryGardenView(session)
        GameID.slimeRacing -> SlimeRacingView(session)
        GameID.rhythmBounce -> RhythmBounceView(session)
        GameID.gardenPuzzle -> GardenPuzzleView(session)
        GameID.treasureDig -> TreasureDigView(session)
        GameID.basketball -> BasketballView(session)
        GameID.colorMatch -> ColorMatchView(session)
        GameID.slimeStack -> SlimeStackView(session)
        GameID.jellyDash -> JellyDashView(session)
        GameID.daily -> {
            val spec = session.spec
            if (spec != null) {
                BlockGameView(session, spec)
            } else {
                LaunchedEffect(session.id) { session.end() }
            }
        }
    }
}
