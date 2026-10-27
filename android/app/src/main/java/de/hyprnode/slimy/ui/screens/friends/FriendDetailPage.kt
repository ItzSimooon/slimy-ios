package de.hyprnode.slimy.ui.screens.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.core.LocalFriendsStore
import de.hyprnode.slimy.model.Accessories
import de.hyprnode.slimy.online.CoopStatus
import de.hyprnode.slimy.online.Friend
import de.hyprnode.slimy.online.FriendDetail
import de.hyprnode.slimy.online.FriendMemory
import de.hyprnode.slimy.online.InteractionKind
import de.hyprnode.slimy.ui.kit.GroupedDivider
import de.hyprnode.slimy.ui.kit.GroupedRow
import de.hyprnode.slimy.ui.kit.GroupedSection
import de.hyprnode.slimy.ui.kit.Meter
import de.hyprnode.slimy.ui.kit.SheetScaffold
import de.hyprnode.slimy.ui.kit.Tag
import de.hyprnode.slimy.ui.kit.Theme
import kotlinx.coroutines.launch

/**
 * One friend: how their slime is doing, what's left today, the shared
 * garden and memories, with the same actions as the list
 * (App/UI/Screens/FriendsDetail.swift).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FriendDetailPage(friendID: String, onBack: () -> Unit, onClose: () -> Unit) {
    val friends = LocalFriendsStore.current
    val list by friends.friends.collectAsStateWithLifecycle()
    val details by friends.details.collectAsStateWithLifecycle()
    val isOnline by friends.isOnline.collectAsStateWithLifecycle()
    val actions = rememberFriendActions()
    val scope = rememberCoroutineScope()
    var loadError by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val friend = list.firstOrNull { it.id == friendID }

    suspend fun load() {
        val current = friends.friend(friendID) ?: return
        if (!friends.isOnline.value) return
        loadError = null
        friends.loadDetail(current)
        if (friends.details.value[friendID] == null) {
            loadError = friends.lastError.value ?: "Couldn't load this friend. Pull down to try again."
            friends.lastError.value = null
        }
    }

    LaunchedEffect(friendID) { load() }
    LaunchedEffect(actions.gone) { if (actions.gone == friendID) onBack() }

    Box(Modifier.fillMaxSize()) {
        SheetScaffold(
            title = friend?.slime?.name ?: "Friend",
            onDone = onClose,
            inline = true,
            navigationIcon = { BackButton(onBack) },
        ) { padding ->
            if (friend == null) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    Icon(Icons.Filled.PersonOff, contentDescription = null, tint = Theme.inkSoft, modifier = Modifier.size(48.dp))
                    Text("Not friends anymore", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Theme.ink)
                    Text(
                        "This slime is no longer on your friends list.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Theme.inkSoft,
                        textAlign = TextAlign.Center,
                    )
                }
                return@SheetScaffold
            }
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
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    item { Header(friend) }
                    item { Together(friend, actions) }
                    val detail = details[friendID]
                    val error = loadError
                    if (detail != null) {
                        item { Today(detail) }
                        item { Garden(detail.coop, friend) }
                        item { Memories(detail.memories) }
                    } else if (error != null) {
                        item {
                            GroupedSection {
                                Row(
                                    Modifier.padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(Icons.Filled.WifiOff, contentDescription = null, tint = Theme.inkSoft)
                                    Text(error, style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft)
                                }
                                GroupedDivider()
                                GroupedRow("Try Again", titleColor = Theme.accent, onClick = { scope.launch { load() } })
                            }
                        }
                    } else if (isOnline) {
                        item {
                            GroupedSection {
                                WaitingLine("Loading memories and garden…", Modifier.padding(16.dp))
                            }
                        }
                    }
                    item { Manage(friend, actions, isOnline) }
                }
            }
        }
        FriendActionsHost(actions)
    }
}

@Composable
private fun Header(friend: Friend) {
    val friends = LocalFriendsStore.current
    val partnerID by friends.partnerID.collectAsStateWithLifecycle()
    Column {
        GroupedSection {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FriendPortrait(
                    friend.slime.skin, friend.stage, friend.slime.mood,
                    Modifier.size(width = 128.dp, height = 104.dp), friend.slime.equipped ?: Accessories(),
                )
                Column(
                    Modifier.semantics(mergeDescendants = true) {},
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(friend.slime.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Theme.ink)
                    Text(
                        "${friend.ownerName}'s ${friend.stage.title.lowercase()} · Level ${friend.slime.level}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Theme.inkSoft,
                        textAlign = TextAlign.Center,
                    )
                }
                if (partnerID == friend.id) Tag("In your notification")
            }
            GroupedDivider()
            val happiness = friend.slime.happiness
            GroupedRow(
                if (happiness == null) "Mood" else friend.slime.mood.friendTitle,
                icon = {
                    Icon(
                        if (happiness == null) Icons.Filled.VisibilityOff else friend.slime.mood.friendIcon,
                        contentDescription = null,
                        tint = Theme.accent,
                    )
                },
                trailing = {
                    Text(
                        if (happiness == null) "Hidden by ${friend.ownerName}" else "Happiness $happiness%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Theme.inkSoft,
                    )
                },
            )
        }
        SectionFooter("Updated ${relativeTimeInSentence(friend.slime.updatedAt)}.")
    }
}

@Composable
private fun Together(friend: Friend, actions: FriendActionCenter) {
    val friends = LocalFriendsStore.current
    val partnerID by friends.partnerID.collectAsStateWithLifecycle()
    val isOnline by friends.isOnline.collectAsStateWithLifecycle()
    val isPartner = partnerID == friend.id
    GroupedSection(header = "Together", footer = "The friend you choose sits next to your slime in the slime notification.") {
        Box(Modifier.padding(16.dp)) {
            FriendQuickActions(friend, busy = friend.id in actions.busy, enabled = isOnline) { item -> actions.quick(item, friend) }
        }
        GroupedDivider()
        GroupedRow(
            if (isPartner) "In your notification" else "Show in my notification",
            titleColor = if (isPartner) Theme.inkSoft else Theme.accent,
            icon = {
                Icon(
                    if (isPartner) Icons.Filled.CheckCircle else Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = Theme.accent,
                )
            },
            enabled = !isPartner,
            onClick = {
                friends.partnerID.value = friend.id
                actions.say("${friend.slime.name} now sits next to your slime in the slime notification.")
            },
        )
    }
}

@Composable
private fun Today(detail: FriendDetail) {
    GroupedSection(
        header = "Left today",
        footer = "Hugs, visits and gifts reach each friend a few times a day. The counts start over tomorrow.",
    ) {
        InteractionKind.entries.forEachIndexed { index, kind ->
            if (index > 0) GroupedDivider()
            val left = maxOf(0, detail.today[kind.name] ?: 0)
            GroupedRow(
                kind.title,
                icon = { Icon(kind.icon, contentDescription = null, tint = Theme.accent) },
                trailing = {
                    Text(
                        if (left == 0) "Done for today" else "$left left",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Theme.inkSoft,
                    )
                },
            )
        }
    }
}

@Composable
private fun Garden(coop: CoopStatus, friend: Friend) {
    GroupedSection(
        header = "Shared garden",
        footer = "Every round either of you plays in the arcade makes the garden grow. Fill it this week and both slimes are happy.",
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (coop.done) "Full this week" else "${coop.total} of ${coop.goal}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Theme.ink,
                    modifier = Modifier.weight(1f),
                )
                if (coop.done) Icon(Icons.Filled.Verified, contentDescription = null, tint = Theme.accent)
            }
            Meter(coop.total.toDouble() / maxOf(1, coop.goal).toDouble(), color = Theme.accent, height = 8.dp)
            Text(
                "You ${coop.mine} · ${friend.slime.name} ${coop.theirs}",
                style = MaterialTheme.typography.bodyMedium,
                color = Theme.inkSoft,
            )
        }
    }
}

@Composable
private fun Memories(all: List<FriendMemory>) {
    val memories = all.filter { it.isShown }
    GroupedSection(header = "Memories") {
        if (memories.isEmpty()) {
            Text(
                "No memories yet. A hug is a good start.",
                style = MaterialTheme.typography.bodyMedium,
                color = Theme.inkSoft,
                modifier = Modifier.padding(16.dp),
            )
        }
        memories.forEachIndexed { index, memory ->
            if (index > 0) GroupedDivider()
            GroupedRow(
                memory.title,
                icon = { Icon(memory.icon, contentDescription = null, tint = Theme.accent) },
                trailing = { Text(mediumDate(memory.at), style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft) },
            )
        }
    }
}

@Composable
private fun Manage(friend: Friend, actions: FriendActionCenter, isOnline: Boolean) {
    GroupedSection(footer = "Friends since ${longDate(friend.since)}.") {
        GroupedRow(
            "Report ${friend.ownerName}…",
            titleColor = Theme.accent,
            icon = { Icon(Icons.Filled.Report, contentDescription = null, tint = Theme.accent) },
            enabled = isOnline,
            onClick = { actions.reportTarget = ReportTarget(friend) },
        )
        GroupedDivider()
        GroupedRow(
            "Remove Friend",
            titleColor = Theme.danger,
            icon = { Icon(Icons.Filled.PersonRemove, contentDescription = null, tint = Theme.danger) },
            enabled = isOnline,
            onClick = { actions.removeFor = friend },
        )
        GroupedDivider()
        GroupedRow(
            "Block ${friend.ownerName}",
            titleColor = Theme.danger,
            icon = { Icon(Icons.Filled.Block, contentDescription = null, tint = Theme.danger) },
            enabled = isOnline,
            onClick = { actions.blockTarget = ReportTarget(friend) },
        )
    }
}
