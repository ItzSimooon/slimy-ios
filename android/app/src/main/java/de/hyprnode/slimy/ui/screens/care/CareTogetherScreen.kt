package de.hyprnode.slimy.ui.screens.care

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FrontHand
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.core.Feedback
import de.hyprnode.slimy.core.LocalFriendsStore
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.core.Toast
import de.hyprnode.slimy.model.CareKind
import de.hyprnode.slimy.model.CareLink
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.model.maxOf
import de.hyprnode.slimy.model.timeIntervalSince
import de.hyprnode.slimy.online.CareActivity
import de.hyprnode.slimy.online.CareRole
import de.hyprnode.slimy.online.FriendInvite
import de.hyprnode.slimy.online.PendingCare
import de.hyprnode.slimy.ui.kit.AppButton
import de.hyprnode.slimy.ui.kit.AppButtonKind
import de.hyprnode.slimy.ui.kit.GroupedDivider
import de.hyprnode.slimy.ui.kit.GroupedRow
import de.hyprnode.slimy.ui.kit.GroupedSection
import de.hyprnode.slimy.ui.kit.ItemIcon
import de.hyprnode.slimy.ui.kit.SheetScaffold
import de.hyprnode.slimy.ui.kit.Tag
import de.hyprnode.slimy.ui.kit.Theme
import de.hyprnode.slimy.ui.screens.friends.BackButton
import de.hyprnode.slimy.ui.screens.friends.BanLine
import de.hyprnode.slimy.ui.screens.friends.Chevron
import de.hyprnode.slimy.ui.screens.friends.CodeCard
import de.hyprnode.slimy.ui.screens.friends.CodeField
import de.hyprnode.slimy.ui.screens.friends.ConfirmDialog
import de.hyprnode.slimy.ui.screens.friends.FriendPortrait
import de.hyprnode.slimy.ui.screens.friends.FriendPrivacyPage
import de.hyprnode.slimy.ui.screens.friends.FullScreenSheet
import de.hyprnode.slimy.ui.screens.friends.GoOnlineScreen
import de.hyprnode.slimy.ui.screens.friends.IconTile
import de.hyprnode.slimy.ui.screens.friends.InfoRow
import de.hyprnode.slimy.ui.screens.friends.ToastOverlay
import de.hyprnode.slimy.ui.screens.friends.WaitingLine
import de.hyprnode.slimy.ui.screens.friends.mediumDate
import de.hyprnode.slimy.ui.screens.friends.rememberExpiry
import de.hyprnode.slimy.ui.screens.friends.rememberNow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/** Care codes and the text the share sheet sends for them. */
object CareInvite {
    /** What the share sheet sends with a care code. */
    fun message(code: String, slimeName: String): String =
        "Care for my slime $slimeName with me in Slimy! Code: $code — get Slimy at https://slime.hyprnode.de"

    /** The code in a pasted invite ("… Code: ABC123 …"), or null when the text isn't one. */
    fun code(text: String): String? {
        val index = text.indexOf("Code:", ignoreCase = true)
        if (index < 0) return null
        val rest = text.substring(index + "Code:".length).trimStart(' ', '\t')
        val candidate = rest.take(FriendInvite.length).uppercase()
        if (candidate.length != FriendInvite.length || !candidate.all { it in FriendInvite.alphabet }) return null
        return candidate
    }
}

/**
 * Caring for one slime together: codes, joining, the partner's activity
 * (App/UI/Screens/CareTogetherView.swift). Two people look after one slime.
 * The host gets a code, the joiner enters it and adopts the host's slime
 * (their own waits safely on their phone). Once paired it shows the partner,
 * what they did recently, and how to stop.
 */
@Composable
fun CareTogetherScreen(onClose: () -> Unit) {
    CareTogetherPage(onBack = null, onClose = onClose)
}

/** Care Together as a page: with [onBack] it was pushed from Friends and shows a back arrow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CareTogetherPage(onBack: (() -> Unit)?, onClose: () -> Unit) {
    var showPrivacy by rememberSaveable { mutableStateOf(false) }
    if (showPrivacy) {
        BackHandler { showPrivacy = false }
        FriendPrivacyPage(onBack = { showPrivacy = false }, onClose = onClose)
        return
    }

    val friends = LocalFriendsStore.current
    val store = LocalGameStore.current
    val state by store.state.collectAsStateWithLifecycle()
    val isOnline by friends.isOnline.collectAsStateWithLifecycle()
    val pending by friends.pendingCare.collectAsStateWithLifecycle()
    val careCode by friends.careCode.collectAsStateWithLifecycle()
    val careProblem by friends.careProblem.collectAsStateWithLifecycle()
    val careNameNotice by friends.careNameNotice.collectAsStateWithLifecycle()
    val activity by friends.careActivity.collectAsStateWithLifecycle()
    val since by friends.careSince.collectAsStateWithLifecycle()
    val syncedAt by friends.careSyncedAt.collectAsStateWithLifecycle()
    val ban by friends.banNotice.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    // Keeps "5 min ago" and the code's validity current.
    val now = rememberNow()
    val codeExpired = rememberExpiry(careCode?.expiresAt)

    var joinCode by rememberSaveable { mutableStateOf("") }
    var joining by remember { mutableStateOf(false) }
    var makingCode by remember { mutableStateOf(false) }
    var showGoOnline by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var showJoinConfirm by remember { mutableStateOf(false) }
    // The pairing the join question was already asked for, so it pops up once.
    var askedJoin by rememberSaveable { mutableStateOf<String?>(null) }
    var answeringJoin by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<Toast?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    val slime = state.slime
    val link = state.care

    fun showError() {
        toast = Toast(friends.lastError.value ?: "Something went wrong. Please try again.", good = false)
        friends.lastError.value = null
    }

    fun askToJoinIfNeeded() {
        val current = friends.pendingCare.value ?: return
        if (current.confirmed || askedJoin == current.partnerID || answeringJoin) return
        askedJoin = current.partnerID
        showJoinConfirm = true
    }

    fun confirmJoin() {
        answeringJoin = true
        friends.scope.launch {
            friends.confirmJoin()
            answeringJoin = false
        }
    }

    fun cancelJoin() {
        answeringJoin = true
        friends.scope.launch {
            val ok = friends.cancelJoin()
            answeringJoin = false
            if (ok) toast = Toast("You didn't join. ${store.state.value.slime.name} stays as it was.", good = true) else showError()
        }
    }

    fun makeCode() {
        makingCode = true
        friends.scope.launch {
            val ok = friends.makeCareCode()
            makingCode = false
            if (!ok) showError()
        }
    }

    fun join() {
        val code = FriendInvite.sanitize(joinCode)
        if (code.length != FriendInvite.length || joining) return
        focus.clearFocus()
        joining = true
        friends.scope.launch {
            val ok = friends.joinCare(code)
            joining = false
            if (ok) {
                joinCode = ""
                Feedback.shared.play(Sound.success)
            } else {
                showError()
            }
        }
    }

    fun stopSharing() {
        stopping = true
        val partner = store.state.value.care?.partnerName ?: "your partner"
        friends.scope.launch {
            val ok = friends.leaveCare()
            stopping = false
            if (ok) {
                Feedback.shared.play(Sound.success)
                toast = Toast("You stopped sharing with $partner. ${store.state.value.slime.name} is here with you.", good = true)
            } else {
                showError()
            }
        }
    }

    LaunchedEffect(Unit) { friends.syncCare() }
    // Asks once the partner's slime is here, or after a short wait anyway.
    LaunchedEffect(pending?.partnerID) {
        val current = friends.pendingCare.value ?: return@LaunchedEffect
        if (current.confirmed) return@LaunchedEffect
        if (current.snapshot == null) {
            val waited = Instant.now().timeIntervalSince(current.joinedAt)
            if (waited < joinAskDelay) delay(((joinAskDelay - waited) * 1000).toLong())
        }
        askToJoinIfNeeded()
    }
    LaunchedEffect(pending?.snapshot != null) {
        if (pending?.snapshot != null) askToJoinIfNeeded()
    }

    val pendingTitle = pending?.let { p -> p.slimeName?.takeIf { it.isNotEmpty() } ?: "${p.partnerName}'s slime" } ?: "the slime"
    val joinQuestion = pending?.let { "Care for $pendingTitle with ${it.partnerName}?" } ?: "Join?"
    val joinExplanation =
        "$pendingTitle moves onto this phone and you both look after it. Your own slime, ${slime.name}, waits safely on this phone and comes back if you stop sharing. Coins and things stay your own."

    Box(Modifier.fillMaxSize()) {
        SheetScaffold(
            title = "Care Together",
            onDone = onClose,
            inline = true,
            navigationIcon = { if (onBack != null) BackButton(onBack) },
        ) { padding ->
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    refreshing = true
                    scope.launch {
                        friends.syncCare()
                        refreshing = false
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding()),
            ) {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .imePadding(),
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, padding.calculateBottomPadding() + 32.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    item { Header(slime.name, link, slime) }
                    val currentPending = pending
                    if (link != null) {
                        if (!isOnline) item { OfflineNotice(slime.name, link) { showGoOnline = true } }
                        val problem = careProblem
                        if (problem != null && isOnline) {
                            item { ProblemSection(problem, careNameNotice != null) { showPrivacy = true } }
                        }
                        item { PartnerSection(link, slime.name, since) }
                        item { ActivitySection(link, slime.name, activity, syncedAt, now) }
                        item {
                            StopSection(link, slime.name, stopping, isOnline) { confirmStop = true }
                        }
                    } else if (currentPending != null) {
                        if (!isOnline) item { GoOnlineSection(ban) { showGoOnline = true } }
                        item {
                            PendingSection(
                                currentPending, pendingTitle, joinQuestion, joinExplanation, answeringJoin,
                                confirm = { confirmJoin() }, cancel = { cancelJoin() },
                            )
                        }
                    } else {
                        item { HowItWorks() }
                        if (isOnline) {
                            item {
                                val code = careCode?.takeIf { !codeExpired }
                                GroupedSection(
                                    header = "Share ${slime.name}",
                                    footer = "Whoever enters your code cares for ${slime.name} with you. ${slime.name} stays yours; their own slime waits on their phone. Codes work once, for 10 minutes.",
                                ) {
                                    if (code != null) {
                                        CodeCard(code, "Your care code", CareInvite.message(code.code, slime.name)) {
                                            WaitingLine("Waiting for someone to join…")
                                        }
                                    } else {
                                        AppButton(
                                            title = if (makingCode) "Getting a code…" else "Get a Care Code",
                                            modifier = Modifier.padding(16.dp),
                                            systemIcon = Icons.Filled.Tag,
                                            enabled = !makingCode,
                                            action = { makeCode() },
                                        )
                                    }
                                }
                            }
                            item {
                                GroupedSection(
                                    header = "Join someone's slime",
                                    footer = "Enter the code your partner got here in Care Together. You'll look after their slime; ${slime.name} waits safely on this phone and comes back if you stop sharing.",
                                ) {
                                    CodeField(
                                        value = joinCode,
                                        onValueChange = { joinCode = CareInvite.code(it) ?: FriendInvite.sanitize(it) },
                                        label = "Partner's code",
                                        button = if (joining) "Joining…" else "Join",
                                        enabled = joinCode.length == FriendInvite.length && !joining,
                                        onSubmit = { join() },
                                    )
                                }
                            }
                        } else {
                            item { GoOnlineSection(ban) { showGoOnline = true } }
                        }
                    }
                }
            }
        }
        ToastOverlay(toast, onDismiss = { if (toast == it) toast = null })
    }

    if (showJoinConfirm && pending != null) {
        AlertDialog(
            onDismissRequest = { showJoinConfirm = false },
            title = { Text(joinQuestion) },
            text = { Text(joinExplanation) },
            confirmButton = {
                TextButton(onClick = {
                    showJoinConfirm = false
                    confirmJoin()
                }) { Text("Care for $pendingTitle", fontWeight = FontWeight.SemiBold) }
            },
            // Only closes the question; "Don't Join" on the page declines.
            dismissButton = { TextButton(onClick = { showJoinConfirm = false }) { Text("Not Now") } },
        )
    }
    if (confirmStop && link != null) {
        ConfirmDialog(
            title = "Stop sharing ${slime.name}?",
            message = if (link.role == CareRole.host) {
                "${link.partnerName} stops caring for ${slime.name} and gets their own slime back. ${slime.name} stays with you on this phone, with everything you both did for it."
            } else {
                val own = link.ownBackup?.name ?: "your own slime"
                "${slime.name} goes back to ${link.partnerName} only. $own comes back to this phone right away. Coins and things stay as they are."
            },
            confirm = "Stop Sharing",
            onConfirm = { stopSharing() },
            onDismiss = { confirmStop = false },
        )
    }
    if (showGoOnline) {
        FullScreenSheet(onDismiss = { showGoOnline = false }) { GoOnlineScreen(onDone = { showGoOnline = false }) }
    }
}

/** How long to wait for the partner's slime before asking anyway (seconds). */
private const val joinAskDelay = 12.0

@Composable
private fun Header(name: String, link: CareLink?, slime: de.hyprnode.slimy.model.Slime) {
    GroupedSection {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FriendPortrait(slime.skin, slime.stage, slime.mood, Modifier.size(width = 112.dp, height = 92.dp), slime.equipped)
            Text(
                if (link != null) "$name has two caretakers" else "One slime, two caretakers",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Theme.ink,
                textAlign = TextAlign.Center,
            )
            Text(
                if (link != null) {
                    "You and ${link.partnerName} look after $name together. Whatever either of you does reaches the other phone."
                } else {
                    "Share $name with one person you trust, or join theirs, and look after it together."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Theme.inkSoft,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun HowItWorks() {
    GroupedSection(header = "How it works") {
        InfoRow(
            Icons.Filled.People, "One slime, two caretakers",
            "Feeding, playing, baths, pets and bedtime on either phone reach the other one, and you both see what the other did.",
        )
        GroupedDivider()
        InfoRow(
            Icons.Filled.ShoppingBag, "Coins and things stay yours",
            "Coins, things you own, records, challenges and settings stay personal. Only the slime is shared.",
        )
        GroupedDivider()
        InfoRow(
            Icons.Filled.Security, "Your own slime waits safely",
            "If you join someone's slime, yours stays on this phone and comes back as soon as you stop sharing.",
        )
    }
}

@Composable
private fun GoOnlineSection(ban: String?, goOnline: () -> Unit) {
    GroupedSection {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Care Together needs slime.hyprnode.de",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Theme.ink,
            )
            Text(
                "Both phones talk through the server to share the slime. Going online asks you to agree to the Terms first.",
                style = MaterialTheme.typography.bodyMedium,
                color = Theme.inkSoft,
            )
            ban?.let { BanLine(it) }
            AppButton("Go Online", systemIcon = Icons.Filled.People, action = goOnline)
        }
    }
}

@Composable
private fun PendingSection(
    pending: PendingCare,
    title: String,
    question: String,
    explanation: String,
    answering: Boolean,
    confirm: () -> Unit,
    cancel: () -> Unit,
) {
    GroupedSection(
        header = "Joining ${pending.partnerName}",
        footer = "The slime comes over from ${pending.partnerName}'s phone. That happens while ${pending.partnerName} has Slimy open.",
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (pending.confirmed) {
                WaitingLine("Waiting for $title to arrive…", color = Theme.ink)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(question, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Theme.ink)
                    Text(explanation, style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft)
                    if (pending.snapshot == null) {
                        WaitingLine("Getting ${pending.partnerName}'s slime…", Modifier.padding(top = 2.dp))
                    }
                }
                AppButton("Care for $title", systemIcon = Icons.Filled.Check, enabled = !answering, action = confirm)
            }
            AppButton(
                if (pending.confirmed) "Cancel" else "Don't Join",
                kind = AppButtonKind.secondary,
                enabled = !answering,
                action = cancel,
            )
        }
    }
}

@Composable
private fun OfflineNotice(name: String, link: CareLink, goOnline: () -> Unit) {
    GroupedSection {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Filled.WifiOff, contentDescription = null, tint = Theme.inkSoft)
            Text(
                "You're offline. What you do for $name reaches ${link.partnerName} once you go online again.",
                style = MaterialTheme.typography.bodyMedium,
                color = Theme.ink,
            )
        }
        GroupedDivider()
        GroupedRow("Go Online…", titleColor = Theme.accent, onClick = goOnline)
    }
}

@Composable
private fun ProblemSection(problem: String, nameRefused: Boolean, rename: () -> Unit) {
    GroupedSection {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = Theme.gold)
            Text(problem, style = MaterialTheme.typography.bodyMedium, color = Theme.ink)
        }
        if (nameRefused) {
            // The server refused the shared slime's name: rename it where the names friends see are edited.
            GroupedDivider()
            GroupedRow(
                "Choose a Different Name",
                titleColor = Theme.accent,
                icon = { Icon(Icons.Filled.Edit, contentDescription = null, tint = Theme.accent) },
                trailing = { Chevron() },
                onClick = rename,
            )
        }
    }
}

@Composable
private fun PartnerSection(link: CareLink, name: String, since: Instant?) {
    GroupedSection(header = "Caring with") {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = Theme.accent, modifier = Modifier.size(44.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        link.partnerName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Theme.ink,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Tag(if (link.role == CareRole.host) "Joined you" else "Host")
                }
                Text(
                    if (link.role == CareRole.host) {
                        "Joined to care for $name with you."
                    } else {
                        "$name came from ${link.partnerName}'s phone. You care for it together."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Theme.inkSoft,
                )
            }
        }
        if (since != null) {
            GroupedDivider()
            GroupedRow(
                "Together since",
                trailing = { Text(mediumDate(since), style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft) },
            )
        }
    }
}

@Composable
private fun ActivitySection(link: CareLink, name: String, activity: List<CareActivity>, syncedAt: Instant?, now: Instant) {
    val kept = "What ${link.partnerName} did in the last 7 days."
    val footer = if (syncedAt == null) kept else "$kept Updated ${CareActivity.ago(syncedAt, maxOf(now, syncedAt))}."
    GroupedSection(header = "Recent activity", footer = footer) {
        if (activity.isEmpty()) {
            Text(
                "Nothing yet. When ${link.partnerName} feeds, plays with or pets $name, it shows up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = Theme.inkSoft,
                modifier = Modifier.padding(16.dp),
            )
        }
        activity.forEachIndexed { index, entry ->
            if (index > 0) GroupedDivider()
            ActivityRow(entry, now)
        }
    }
}

/** "Anna fed Blob · 5 min ago", with the food or game it was about. */
@Composable
private fun ActivityRow(entry: CareActivity, now: Instant) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val sprite = entry.sprite
        if (sprite != null) ItemIcon(sprite, size = 36.dp) else IconTile(entry.kind.careIcon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val inkSoft = Theme.inkSoft
            Text(
                buildAnnotatedString {
                    append(entry.text)
                    withStyle(SpanStyle(color = inkSoft)) { append(" · ${CareActivity.ago(entry.at, maxOf(now, entry.at))}") }
                },
                style = MaterialTheme.typography.bodyLarge,
                color = Theme.ink,
            )
            entry.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Theme.inkSoft) }
        }
    }
}

@Composable
private fun StopSection(link: CareLink, name: String, stopping: Boolean, isOnline: Boolean, stop: () -> Unit) {
    GroupedSection(
        footer = if (link.role == CareRole.host) {
            "$name stays with you if you stop. ${link.partnerName} gets their own slime back."
        } else {
            "If you stop, $name stays with ${link.partnerName} and your own slime comes back."
        },
    ) {
        if (stopping) {
            WaitingLine("Stopping…", Modifier.padding(16.dp))
        } else {
            GroupedRow("Stop Sharing", titleColor = Theme.danger, enabled = isOnline, onClick = stop)
        }
    }
}

/** The symbol for a care action without a food or game sprite. */
private val CareKind.careIcon: ImageVector
    get() = when (this) {
        CareKind.feed -> Icons.Filled.Restaurant
        CareKind.play -> Icons.Filled.SportsEsports
        CareKind.bath -> Icons.Filled.WaterDrop
        CareKind.pet -> Icons.Filled.FrontHand
        CareKind.sleep -> Icons.Filled.Bedtime
        CareKind.wake -> Icons.Filled.WbSunny
        CareKind.gift -> Icons.Filled.CardGiftcard
    }
