package de.hyprnode.slimy.ui.screens.friends

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.EmojiPeople
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.core.Feedback
import de.hyprnode.slimy.core.LocalFriendsStore
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.model.Mood
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.model.prefixChars
import de.hyprnode.slimy.online.Friend
import de.hyprnode.slimy.online.FriendInvite
import de.hyprnode.slimy.online.FriendRequest
import de.hyprnode.slimy.online.FriendsStore
import de.hyprnode.slimy.online.HyprnodeAPI
import de.hyprnode.slimy.ui.kit.AppButton
import de.hyprnode.slimy.ui.kit.AppButtonKind
import de.hyprnode.slimy.ui.kit.GroupedDivider
import de.hyprnode.slimy.ui.kit.GroupedRow
import de.hyprnode.slimy.ui.kit.GroupedSection
import de.hyprnode.slimy.ui.kit.SheetScaffold
import de.hyprnode.slimy.ui.kit.Theme
import de.hyprnode.slimy.ui.screens.care.CareTogetherPage
import kotlinx.coroutines.launch

/** Where Friends can go from its list. */
internal sealed interface FriendsRoute {
    data class friend(val id: String) : FriendsRoute
    data object leaderboard : FriendsRoute
    data object privacy : FriendsRoute
    data object care : FriendsRoute
}

/**
 * Friends: requests, challenges, the friends themselves, pairing with a code,
 * caring for one slime together, the leaderboard and privacy. Offline it
 * explains what going online means and shows the friends from last time
 * (App/UI/Screens/FriendsView.swift). Pages open inside it with a back arrow;
 * Done closes it.
 */
@Composable
fun FriendsScreen(onClose: () -> Unit) {
    val friends = LocalFriendsStore.current
    val stack = remember { mutableStateListOf<FriendsRoute>() }
    val holder = rememberSaveableStateHolder()
    val pop: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.isNotEmpty(), onBack = pop)

    LaunchedEffect(Unit) {
        friends.hasNews.value = false
        friends.refresh()
    }

    val top = stack.lastOrNull()
    holder.SaveableStateProvider(top?.toString() ?: "home") {
        when (top) {
            null -> FriendsHome(onClose = onClose, push = { stack.add(it) })
            is FriendsRoute.friend -> FriendDetailPage(top.id, onBack = pop, onClose = onClose)
            FriendsRoute.leaderboard -> LeaderboardPage(onBack = pop, onClose = onClose)
            FriendsRoute.privacy -> FriendPrivacyPage(onBack = pop, onClose = onClose)
            FriendsRoute.care -> CareTogetherPage(onBack = pop, onClose = onClose)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FriendsHome(onClose: () -> Unit, push: (FriendsRoute) -> Unit) {
    val friends = LocalFriendsStore.current
    val scope = rememberCoroutineScope()
    val isOnline by friends.isOnline.collectAsStateWithLifecycle()
    val pendingInvite by friends.pendingInviteCode.collectAsStateWithLifecycle()
    val actions = rememberFriendActions()
    var joinCode by rememberSaveable { mutableStateOf("") }
    var showGoOnline by rememberSaveable { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(pendingInvite) { pendingInvite?.let { joinCode = it } }

    Box(Modifier.fillMaxSize()) {
        SheetScaffold(title = "Friends", onDone = onClose) { padding ->
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    refreshing = true
                    scope.launch {
                        friends.refresh()
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
                ) {
                    // Sections bring their own gap below, so one that shows nothing leaves no space.
                    if (isOnline) {
                        onlineSections(friends, actions, push, onClose, joinCode, { joinCode = it })
                    } else {
                        offlineSections(friends, push) { showGoOnline = true }
                    }
                }
            }
        }
        FriendActionsHost(actions)
    }
    if (showGoOnline) {
        FullScreenSheet(onDismiss = { showGoOnline = false }) { GoOnlineScreen(onDone = { showGoOnline = false }) }
    }
}

/** The space below a section of the Friends list. */
private val sectionGap = Modifier.padding(bottom = 24.dp)

/** The friend in your slime notification first, then by name. */
private fun sorted(list: List<Friend>, partnerID: String?): List<Friend> =
    list.sortedWith(compareBy<Friend> { it.id != partnerID }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.slime.name })

// MARK: Online

private fun LazyListScope.onlineSections(
    friends: FriendsStore,
    actions: FriendActionCenter,
    push: (FriendsRoute) -> Unit,
    onClose: () -> Unit,
    joinCode: String,
    setJoinCode: (String) -> Unit,
) {
    item(key = "status") { StatusSection(push) }
    item(key = "requests") { RequestsSection(actions) }
    item(key = "challenges") { ChallengesSection(onClose) }
    item(key = "friends") { FriendsSection(actions, push) }
    item(key = "teamUp") { TeamUpSection(actions, joinCode, setJoinCode) }
    item(key = "waiting") { WaitingSection(actions) }
    item(key = "more") {
        GroupedSection {
            CareRow(push)
            GroupedDivider()
            GroupedRow(
                "Leaderboard",
                icon = { IconTile(Icons.Filled.EmojiEvents) },
                trailing = { Chevron() },
                onClick = { push(FriendsRoute.leaderboard) },
            )
            GroupedDivider()
            GroupedRow(
                "Privacy & blocked",
                icon = { IconTile(Icons.Filled.PrivacyTip) },
                trailing = { Chevron() },
                onClick = { push(FriendsRoute.privacy) },
            )
        }
    }
}

@Composable
private fun StatusSection(push: (FriendsRoute) -> Unit) {
    val friends = LocalFriendsStore.current
    val status by friends.status.collectAsStateWithLifecycle()
    val queue by friends.queue.collectAsStateWithLifecycle()
    val notice by friends.profileNotice.collectAsStateWithLifecycle()
    Column {
        val current = status
        if (current is FriendsStore.Status.unavailable) {
            GroupedSection(sectionGap) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(Icons.Filled.WifiOff, contentDescription = null, tint = Theme.gold)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Can't sync right now", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Theme.ink)
                        Text(current.message, style = MaterialTheme.typography.bodySmall, color = Theme.inkSoft)
                        if (queue.isNotEmpty()) {
                            Text(
                                if (queue.size == 1) "1 thing is waiting to be sent." else "${queue.size} things are waiting to be sent.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Theme.inkSoft,
                            )
                        }
                    }
                }
            }
        }
        notice?.let { text ->
            GroupedSection(sectionGap) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = Theme.gold)
                    Text(text, style = MaterialTheme.typography.bodySmall, color = Theme.ink)
                }
                GroupedDivider()
                GroupedRow("Change Names", trailing = { Chevron() }, onClick = { push(FriendsRoute.privacy) })
            }
        }
    }
}

@Composable
private fun RequestsSection(actions: FriendActionCenter) {
    val friends = LocalFriendsStore.current
    val requests by friends.requests.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf<Set<String>>(emptySet()) }
    if (requests.incoming.isEmpty()) return

    fun answer(request: FriendRequest, accept: Boolean) {
        busy = busy + request.id
        val name = request.slime?.name ?: request.ownerName
        friends.scope.launch {
            val ok = if (accept) friends.accept(request) else friends.decline(request)
            busy = busy - request.id
            if (ok) actions.say(if (accept) "You and $name are friends now." else "Request declined.") else actions.showError()
        }
    }

    GroupedSection(
        sectionGap,
        header = "Friend requests",
        footer = "Accepting shares your name and your slime's name, look and level with them.",
    ) {
        requests.incoming.forEachIndexed { index, request ->
            if (index > 0) GroupedDivider()
            FriendRequestRow(
                request = request,
                busy = request.id in busy,
                accept = { answer(request, true) },
                decline = { answer(request, false) },
                report = { actions.reportTarget = ReportTarget(request) },
                block = { actions.blockTarget = ReportTarget(request) },
            )
        }
    }
}

@Composable
private fun ChallengesSection(onClose: () -> Unit) {
    val friends = LocalFriendsStore.current
    // Read so the derived lists below recompose.
    friends.challenges.collectAsStateWithLifecycle().value
    val open = friends.openChallenges
    val waiting = friends.waitingChallenges
    val finished = friends.finishedChallenges.take(3)
    if (open.isEmpty() && waiting.isEmpty() && finished.isEmpty()) return
    GroupedSection(
        sectionGap,
        header = "Challenges",
        footer = "You both play the same board. Send a challenge from the arcade after a round.",
    ) {
        var first = true
        for (challenge in open) {
            if (!first) GroupedDivider()
            first = false
            FriendChallengeRow(challenge) {
                friends.playChallenge.value = challenge
                onClose()
            }
        }
        for (challenge in waiting + finished) {
            if (!first) GroupedDivider()
            first = false
            FriendChallengeResultRow(challenge)
        }
    }
}

@Composable
private fun FriendsSection(actions: FriendActionCenter, push: (FriendsRoute) -> Unit) {
    val friends = LocalFriendsStore.current
    val list by friends.friends.collectAsStateWithLifecycle()
    val partnerID by friends.partnerID.collectAsStateWithLifecycle()
    val partner = list.firstOrNull { it.id == partnerID }?.id
    Column(sectionGap) {
        SectionHeader("Friends", if (list.isEmpty()) null else "${list.size} of ${FriendsStore.maxFriends}")
        GroupedSection(footer = "Hug, visit, send gifts and greetings. A friend's menu has more.") {
            if (list.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("No friends yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Theme.ink)
                    Text(
                        "Share your code with someone who has Slimy, or enter theirs below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Theme.inkSoft,
                    )
                }
            }
            sorted(list, partner).forEachIndexed { index, friend ->
                if (index > 0) GroupedDivider()
                val isPartner = friend.id == partner
                FriendListRow(
                    friend = friend,
                    isPartner = isPartner,
                    busy = friend.id in actions.busy,
                    open = { push(FriendsRoute.friend(friend.id)) },
                    menu = FriendMenu(
                        isPartner = isPartner,
                        showInNotification = {
                            friends.partnerID.value = friend.id
                            actions.say("${friend.slime.name} now sits next to your slime in the slime notification.")
                        },
                        report = { actions.reportTarget = ReportTarget(friend) },
                        remove = { actions.removeFor = friend },
                        block = { actions.blockTarget = ReportTarget(friend) },
                    ),
                    action = { item -> actions.quick(item, friend) },
                )
            }
        }
    }
}

@Composable
private fun TeamUpSection(actions: FriendActionCenter, joinCode: String, setJoinCode: (String) -> Unit) {
    val friends = LocalFriendsStore.current
    val privacy by friends.privacy.collectAsStateWithLifecycle()
    val pairCode by friends.pairCode.collectAsStateWithLifecycle()
    val expired = rememberExpiry(pairCode?.expiresAt)
    val focus = LocalFocusManager.current
    var makingCode by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }

    fun connect() {
        val code = FriendInvite.sanitize(joinCode)
        if (code.length != FriendInvite.length || joining) return
        focus.clearFocus()
        joining = true
        friends.scope.launch {
            val line = friends.join(code)
            joining = false
            if (line != null) {
                setJoinCode("")
                actions.say(line)
            } else {
                actions.showError()
            }
        }
    }

    GroupedSection(
        sectionGap,
        header = "Team up",
        footer = "Codes work once, for 10 minutes. You can have up to ${FriendsStore.maxFriends} friends.",
    ) {
        val code = pairCode?.takeIf { !expired }
        when {
            !privacy.acceptRequests -> Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(Icons.Filled.PersonOff, contentDescription = null, tint = Theme.inkSoft)
                Text(
                    "New requests are off, so you can't make a code. Turn them on under Privacy & blocked.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Theme.inkSoft,
                )
            }
            code != null -> CodeCard(code, "Your code", FriendInvite.message(code.code))
            else -> AppButton(
                title = if (makingCode) "Getting your code…" else "Get My Code",
                modifier = Modifier.padding(16.dp),
                systemIcon = Icons.Filled.Tag,
                kind = AppButtonKind.secondary,
                enabled = !makingCode,
            ) {
                makingCode = true
                friends.scope.launch {
                    val ok = friends.makeCode()
                    makingCode = false
                    if (!ok) actions.showError()
                }
            }
        }
        GroupedDivider()
        CodeField(
            value = joinCode,
            onValueChange = { setJoinCode(FriendInvite.sanitize(it)) },
            label = "Friend's code",
            button = if (joining) "Connecting…" else "Connect",
            enabled = joinCode.length == FriendInvite.length && !joining,
            onSubmit = { connect() },
        )
    }
}

/** A six-character code field with its button. */
@Composable
internal fun CodeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    button: String,
    enabled: Boolean,
    onSubmit: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "$label. Six letters and numbers." },
            placeholder = { Text(label) },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
        )
        AppButton(button, compact = true, enabled = enabled, action = onSubmit)
    }
}

@Composable
private fun WaitingSection(actions: FriendActionCenter) {
    val friends = LocalFriendsStore.current
    val requests by friends.requests.collectAsStateWithLifecycle()
    if (requests.outgoing.isEmpty()) return
    GroupedSection(sectionGap, header = "Waiting for an answer") {
        requests.outgoing.forEachIndexed { index, request ->
            if (index > 0) GroupedDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        request.slime?.name ?: request.ownerName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Theme.ink,
                    )
                    Text("Sent ${relativeTimeInSentence(request.createdAt)}", style = MaterialTheme.typography.bodySmall, color = Theme.inkSoft)
                }
                TextButton(
                    onClick = { actions.perform(success = "Request withdrawn.") { friends.decline(request) } },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Withdraw request to ${request.ownerName}" },
                ) { Text("Withdraw") }
            }
        }
    }
}

/** Care Together, with who you share your slime with. */
@Composable
private fun CareRow(push: (FriendsRoute) -> Unit) {
    val friends = LocalFriendsStore.current
    val store = LocalGameStore.current
    val state by store.state.collectAsStateWithLifecycle()
    val pending by friends.pendingCare.collectAsStateWithLifecycle()
    val careCode by friends.careCode.collectAsStateWithLifecycle()
    val expired = rememberExpiry(careCode?.expiresAt)
    val link = state.care
    val value = when {
        link != null -> link.partnerName
        pending != null || (careCode != null && !expired) -> "Waiting"
        else -> null
    }
    GroupedRow(
        "Care Together",
        icon = { IconTile(Icons.Filled.People) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft)
                Chevron()
            }
        },
        onClick = { push(FriendsRoute.care) },
    )
}

// MARK: Offline

private fun LazyListScope.offlineSections(friends: FriendsStore, push: (FriendsRoute) -> Unit, goOnline: () -> Unit) {
    item(key = "goOnline") { GoOnlineCard(goOnline) }
    item(key = "offlineFriends") {
        val list by friends.friends.collectAsStateWithLifecycle()
        val partnerID by friends.partnerID.collectAsStateWithLifecycle()
        if (list.isNotEmpty()) {
            GroupedSection(
                sectionGap,
                header = "Friends",
                footer = "This is how things looked last time. Go online to hug, visit and send gifts.",
            ) {
                sorted(list, partnerID).forEachIndexed { index, friend ->
                    if (index > 0) GroupedDivider()
                    FriendListRow(friend, isPartner = friend.id == partnerID)
                }
            }
        }
    }
    item(key = "offlineMore") {
        val hasAccount by friends.hasServerAccount.collectAsStateWithLifecycle()
        GroupedSection(footer = if (hasAccount) "You can delete what slime.hyprnode.de keeps about you at any time." else null) {
            CareRow(push)
            if (hasAccount) {
                GroupedDivider()
                GroupedRow(
                    "Privacy & your data",
                    icon = { IconTile(Icons.Filled.PrivacyTip) },
                    trailing = { Chevron() },
                    onClick = { push(FriendsRoute.privacy) },
                )
            }
        }
    }
}

@Composable
private fun GoOnlineCard(goOnline: () -> Unit) {
    val friends = LocalFriendsStore.current
    val store = LocalGameStore.current
    val state by store.state.collectAsStateWithLifecycle()
    val invite by friends.pendingInviteCode.collectAsStateWithLifecycle()
    val ban by friends.banNotice.collectAsStateWithLifecycle()
    val slime = state.slime
    GroupedSection(
        sectionGap,
        footer = "Friends is optional. Your slime and every game work without it, and nothing leaves this phone until you go online.",
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FriendPortrait(slime.skin, slime.stage, Mood.idle, Modifier.size(width = 104.dp, height = 86.dp), slime.equipped)
            Text("Play with friends", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Theme.ink)
            Text(
                "Connect with people who have Slimy. Your slimes can hug, visit, send gifts and challenge each other, and you can care for one slime together.",
                style = MaterialTheme.typography.bodyMedium,
                color = Theme.inkSoft,
                textAlign = TextAlign.Center,
            )
            invite?.let {
                Text(
                    "Go online to connect with code $it.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Theme.ink,
                    textAlign = TextAlign.Center,
                )
            }
            ban?.let { BanLine(it) }
            AppButton("Go Online", systemIcon = Icons.Filled.People, action = goOnline)
        }
    }
}

/** Why online features stopped, in red. */
@Composable
internal fun BanLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Dangerous, contentDescription = null, tint = Theme.danger)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Theme.danger)
    }
}

// MARK: - Going online

/**
 * Consent before anything goes to slime.hyprnode.de: what friends see, what
 * the server keeps, the names, and the Terms. Nothing is sent before the
 * player agrees and taps Go Online; it closes itself once the device is
 * online (App/UI/Screens/GoOnlineView.swift). [onDone] closes it, online or not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoOnlineScreen(onDone: () -> Unit) {
    val friends = LocalFriendsStore.current
    val store = LocalGameStore.current
    val state by store.state.collectAsStateWithLifecycle()
    val ban by friends.banNotice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val ownerFocus = remember { FocusRequester() }

    var ownerName by rememberSaveable { mutableStateOf(friends.ownerName.value) }
    var slimeName by rememberSaveable { mutableStateOf(store.state.value.slime.name) }
    var agreed by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var nameError by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = busy) {}
    LaunchedEffect(Unit) {
        if (ownerName.isEmpty()) runCatching { ownerFocus.requestFocus() }
    }

    val cleanedOwner = HyprnodeAPI.cleanedName(ownerName)
    val cleanedSlime = HyprnodeAPI.cleanedName(slimeName).prefixChars(HyprnodeAPI.slimeNameLimit)
    val canSubmit = agreed && !busy && cleanedOwner.isNotEmpty() && cleanedSlime.isNotEmpty()

    fun submit() {
        if (!canSubmit) return
        focus.clearFocus()
        error = null
        nameError = null
        busy = true
        val owner = cleanedOwner
        val slime = cleanedSlime
        val previousSlime = store.state.value.slime.name
        val previousOwner = friends.ownerName.value
        if (slime != previousSlime) store.update { it.slime.name = slime }
        friends.ownerName.value = owner
        friends.scope.launch {
            val ok = friends.goOnline()
            busy = false
            if (ok) {
                Feedback.shared.play(Sound.success)
                onDone()
            } else {
                // Nothing changes when going online fails, not even the names;
                // the fields keep what was typed.
                if (slime != previousSlime) store.update { it.slime.name = previousSlime }
                friends.ownerName.value = previousOwner
                val refused = friends.nameError.value
                if (refused != null) {
                    nameError = refused
                } else {
                    error = friends.lastError.value ?: "Couldn't go online. Please try again."
                }
                friends.nameError.value = null
                friends.lastError.value = null
            }
        }
    }

    Scaffold(
        containerColor = Theme.backdrop,
        topBar = {
            TopAppBar(
                title = { Text("Go Online") },
                navigationIcon = {
                    IconButton(onClick = onDone, enabled = !busy) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Theme.backdrop),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = listPadding(padding),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                val slime = state.slime
                GroupedSection {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        FriendPortrait(slime.skin, slime.stage, Mood.idle, Modifier.size(width = 96.dp, height = 80.dp), slime.equipped)
                        Text(
                            "Friends and chat use slime.hyprnode.de",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Theme.ink,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            "Slimy works fully offline. Going online lets your slime meet friends, compare scores, be cared for by two people and talk with you. Here is exactly what that shares.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Theme.inkSoft,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            item {
                GroupedSection(header = "What friends see") {
                    InfoRow(Icons.Filled.Badge, "Your name", "The name you enter below, next to your slime.")
                    GroupedDivider()
                    InfoRow(Icons.Filled.WaterDrop, "Your slime", "Its name, colour, outfit, level and mood. You can hide the mood later.")
                    GroupedDivider()
                    InfoRow(
                        Icons.Filled.EmojiPeople, "What you do together",
                        "Hugs, visits, gifts, greetings, challenge scores and your best game scores.",
                    )
                    GroupedDivider()
                    InfoRow(
                        Icons.Filled.People, "Care Together, if you use it",
                        "The one person you share a slime with sees that slime and everything either of you does for it.",
                    )
                }
            }
            item {
                GroupedSection(header = "What goes to the server") {
                    InfoRow(
                        Icons.Filled.Storage, "Stored on slime.hyprnode.de",
                        "A random device ID, the names and slime details above, your friendships, best scores and reports you send, and what friends sent you for 30 days. While you care for a slime together, also that slime and your care actions for 30 days.",
                    )
                    GroupedDivider()
                    InfoRow(
                        Icons.Filled.VisibilityOff, "Never collected",
                        "No Google account, phone number, email, contacts, location, advertising ID or tracking.",
                    )
                    GroupedDivider()
                    InfoRow(
                        Icons.Filled.Forum, "Chat",
                        "If you talk to your slime, your messages go through the server to an AI service that writes the replies. You're asked before the first one. Chats aren't stored on the server.",
                    )
                }
            }
            item {
                GroupedSection(
                    header = "Names friends see",
                    footer = "Please don't use your full name. Rude names, links, email addresses and phone numbers aren't accepted. Changing your slime's name here renames it everywhere.",
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = ownerName,
                            onValueChange = {
                                ownerName = it.prefixChars(HyprnodeAPI.nameLimit)
                                nameError = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(ownerFocus),
                            label = { Text("Your name") },
                            placeholder = { Text("First name or nickname") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        )
                        OutlinedTextField(
                            value = slimeName,
                            onValueChange = {
                                slimeName = it.prefixChars(HyprnodeAPI.slimeNameLimit)
                                nameError = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Your slime") },
                            placeholder = { Text("Slime's name") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                        )
                        nameError?.let { WarningLine(it) }
                    }
                }
            }
            item {
                GroupedSection(footer = "Be kind. Anyone can report a name or a person, and the Slimy team can reset names or block devices.") {
                    GroupedRow(
                        "Terms of Use",
                        titleColor = Theme.accent,
                        icon = { IconTile(Icons.Filled.Description) },
                        onClick = { openLink(context, FriendsStore.termsURL) },
                    )
                    GroupedDivider()
                    GroupedRow(
                        "Privacy Policy",
                        titleColor = Theme.accent,
                        icon = { IconTile(Icons.Filled.PrivacyTip) },
                        onClick = { openLink(context, FriendsStore.privacyURL) },
                    )
                    GroupedDivider()
                    SwitchRow("I agree to the Terms of Use and the Privacy Policy", agreed) { agreed = it }
                }
            }
            val message = ban ?: error
            if (message != null) {
                item { WarningLine(message, Modifier.padding(horizontal = 16.dp)) }
            }
            item {
                Column {
                    AppButton(
                        title = if (busy) "Going online…" else "Go Online",
                        systemIcon = if (busy) null else Icons.Filled.People,
                        enabled = canSubmit,
                        action = { submit() },
                    )
                    SectionFooter("You can go offline, or delete everything on the server, at any time in Friends under Privacy & blocked.")
                }
            }
        }
    }
}

/** A row with a switch, the whole row tappable. */
@Composable
internal fun SwitchRow(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = Theme.ink, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A warning in red with its triangle. */
@Composable
internal fun WarningLine(text: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = Theme.danger, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Theme.danger)
    }
}
