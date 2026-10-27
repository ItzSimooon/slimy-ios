package de.hyprnode.slimy.ui.screens.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.EmojiPeople
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.art.PixelItem
import de.hyprnode.slimy.art.PixelSlime
import de.hyprnode.slimy.core.Feedback
import de.hyprnode.slimy.core.LocalFriendsStore
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.core.Toast
import de.hyprnode.slimy.model.Accessories
import de.hyprnode.slimy.model.Food
import de.hyprnode.slimy.model.Mood
import de.hyprnode.slimy.model.SlimeSprites
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.model.Stage
import de.hyprnode.slimy.online.Challenge
import de.hyprnode.slimy.online.Friend
import de.hyprnode.slimy.online.FriendMemory
import de.hyprnode.slimy.online.FriendRequest
import de.hyprnode.slimy.online.FriendsStore
import de.hyprnode.slimy.online.InteractionKind
import de.hyprnode.slimy.online.MessagePreset
import de.hyprnode.slimy.ui.kit.AppButton
import de.hyprnode.slimy.ui.kit.AppButtonKind
import de.hyprnode.slimy.ui.kit.CoinCounter
import de.hyprnode.slimy.ui.kit.ItemIcon
import de.hyprnode.slimy.ui.kit.Tag
import de.hyprnode.slimy.ui.kit.Theme
import de.hyprnode.slimy.ui.kit.TileButton
import kotlinx.coroutines.launch

// Pieces shared by the Friends screens: portraits, rows, quick actions, the
// gift and greeting sheets, confirmation dialogs and the toast
// (App/UI/Screens/FriendsComponents.swift).

/** A friend's slime, standing still. */
@Composable
internal fun FriendPortrait(
    skin: Skin,
    stage: Stage,
    mood: Mood,
    modifier: Modifier = Modifier,
    accessories: Accessories = Accessories(),
) {
    val anim = SpriteAnim.of(mood)
    val frames = SlimeSprites.frames(stage, anim)
    val anchors = SlimeSprites.anchors(stage, anim)
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        PixelSlime(
            frame = frames.firstOrNull() ?: SlimeSprites.blank,
            skin = skin,
            accessories = accessories,
            stage = stage,
            anchor = anchors.firstOrNull(),
        )
    }
}

/** A slime portrait on a soft square, the leading image of a friend row. */
@Composable
internal fun FriendAvatar(
    skin: Skin,
    stage: Stage,
    mood: Mood = Mood.idle,
    accessories: Accessories = Accessories(),
    size: Dp = 56.dp,
) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.26f))
            .background(Theme.well)
            .padding(size * 0.12f)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        FriendPortrait(skin, stage, mood, accessories = accessories)
    }
}

@Composable
internal fun FriendAvatar(friend: Friend, size: Dp = 56.dp) {
    FriendAvatar(friend.slime.skin, friend.stage, friend.slime.mood, friend.slime.equipped ?: Accessories(), size)
}

/** Someone a report or a block is about. */
internal data class ReportTarget(
    val deviceID: String,
    /** The slime's name when known, otherwise the owner's. */
    val name: String,
    val ownerName: String,
) {
    constructor(friend: Friend) : this(friend.id, friend.slime.name, friend.ownerName)
    constructor(request: FriendRequest) : this(request.deviceId, request.slime?.name ?: request.ownerName, request.ownerName)
}

// MARK: - Quick actions

/** What you can do with a friend in one tap. */
internal enum class FriendQuickAction {
    hug, visit, gift, greet;

    val title: String
        get() = when (this) {
            hug -> "Hug"
            visit -> "Visit"
            gift -> "Gift"
            greet -> "Greet"
        }

    val icon: ImageVector
        get() = when (this) {
            hug -> Icons.Filled.EmojiPeople
            visit -> Icons.Filled.Home
            gift -> Icons.Filled.CardGiftcard
            greet -> Icons.Filled.WavingHand
        }
}

/** Hug, visit, gift and greet as a row of tiles; one menu button at very large text sizes, where four tiles don't fit. */
@Composable
internal fun FriendQuickActions(
    friend: Friend,
    busy: Boolean = false,
    enabled: Boolean = true,
    action: (FriendQuickAction) -> Unit,
) {
    val large = LocalDensity.current.fontScale >= 1.6f
    if (large) {
        var open by remember { mutableStateOf(false) }
        Box {
            AppButton(
                title = "Hug, visit, gift …",
                systemIcon = Icons.Filled.WavingHand,
                kind = AppButtonKind.secondary,
                enabled = enabled && !busy,
            ) { open = true }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (item in FriendQuickAction.entries) {
                    DropdownMenuItem(
                        text = { Text(item.title) },
                        leadingIcon = { Icon(item.icon, contentDescription = null) },
                        onClick = {
                            open = false
                            action(item)
                        },
                    )
                }
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (item in FriendQuickAction.entries) {
                TileButton(
                    onClick = {
                        Feedback.shared.play(Sound.tap)
                        action(item)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                        .semantics { contentDescription = "${item.title} ${friend.slime.name}" },
                    fill = Theme.well,
                    radius = Theme.smallRadius,
                    enabled = enabled && !busy,
                ) {
                    Column(
                        Modifier
                            .align(Alignment.Center)
                            .padding(vertical = 6.dp)
                            .clearAndSetSemantics {},
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(item.icon, contentDescription = null, tint = Theme.accent, modifier = Modifier.size(22.dp))
                        Text(
                            item.title,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Theme.accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Rows

/** What a friend's overflow menu offers (the iPhone app's swipe actions and context menu). */
internal class FriendMenu(
    val isPartner: Boolean,
    val showInNotification: () -> Unit,
    val report: () -> Unit,
    val remove: () -> Unit,
    val block: () -> Unit,
)

/**
 * A friend in the list: portrait, names and level, then the quick actions.
 * Without [open] it is read-only (shown while offline).
 */
@Composable
internal fun FriendListRow(
    friend: Friend,
    isPartner: Boolean = false,
    busy: Boolean = false,
    open: (() -> Unit)? = null,
    menu: FriendMenu? = null,
    action: ((FriendQuickAction) -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val summary = Modifier
                .weight(1f)
                .then(
                    if (open != null) {
                        Modifier
                            .clip(RoundedCornerShape(Theme.smallRadius))
                            .clickable(onClickLabel = "Show memories and your shared garden", role = Role.Button, onClick = open)
                    } else {
                        Modifier
                    },
                )
            Row(
                summary.clearAndSetSemantics {
                    contentDescription = friendDescription(friend, isPartner)
                    if (open != null) {
                        role = Role.Button
                        onClick(label = "Show memories and your shared garden") {
                            open()
                            true
                        }
                    }
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FriendAvatar(friend)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            friend.slime.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Theme.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isPartner) Tag("Notification")
                    }
                    Text(
                        "${friend.ownerName} · Level ${friend.slime.level}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Theme.inkSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MoodLabel(friend)
                }
                if (open != null) Chevron()
            }
            if (menu != null) FriendMenuButton(friend, menu)
        }
        if (action != null) FriendQuickActions(friend, busy = busy, action = action)
    }
}

@Composable
private fun MoodLabel(friend: Friend) {
    val hidden = friend.slime.happiness == null
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            if (hidden) Icons.Filled.VisibilityOff else friend.slime.mood.friendIcon,
            contentDescription = null,
            tint = Theme.inkSoft,
            modifier = Modifier.size(14.dp),
        )
        Text(
            if (hidden) "Mood hidden" else friend.slime.mood.friendTitle,
            style = MaterialTheme.typography.bodySmall,
            color = Theme.inkSoft,
            maxLines = 1,
        )
    }
}

private fun friendDescription(friend: Friend, isPartner: Boolean): String {
    val parts = mutableListOf("${friend.slime.name}, ${friend.ownerName}'s slime", "level ${friend.slime.level}")
    if (friend.slime.happiness != null) parts.add(friend.slime.mood.friendTitle.lowercase())
    if (isPartner) parts.add("in your slime notification")
    return parts.joinToString(", ")
}

@Composable
private fun FriendMenuButton(friend: Friend, menu: FriendMenu) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More for ${friend.slime.name}", tint = Theme.inkSoft)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (menu.isPartner) "In Your Notification" else "Show in My Notification") },
                leadingIcon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                enabled = !menu.isPartner,
                onClick = {
                    open = false
                    menu.showInNotification()
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Report…") },
                leadingIcon = { Icon(Icons.Filled.Report, contentDescription = null) },
                onClick = {
                    open = false
                    menu.report()
                },
            )
            DropdownMenuItem(
                text = { Text("Remove Friend", color = Theme.danger) },
                leadingIcon = { Icon(Icons.Filled.PersonRemove, contentDescription = null, tint = Theme.danger) },
                onClick = {
                    open = false
                    menu.remove()
                },
            )
            DropdownMenuItem(
                text = { Text("Block", color = Theme.danger) },
                leadingIcon = { Icon(Icons.Filled.Block, contentDescription = null, tint = Theme.danger) },
                onClick = {
                    open = false
                    menu.block()
                },
            )
        }
    }
}

/** An incoming friend request with Accept and Decline, and Report and Block in a menu. */
@Composable
internal fun FriendRequestRow(
    request: FriendRequest,
    busy: Boolean,
    accept: () -> Unit,
    decline: () -> Unit,
    report: () -> Unit,
    block: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val slime = request.slime
            if (slime != null) {
                FriendAvatar(slime.skin, slime.stage ?: Stage.forLevel(slime.level), slime.mood, slime.equipped ?: Accessories())
            } else {
                Icon(
                    Icons.Filled.AccountCircle,
                    contentDescription = null,
                    tint = Theme.inkSoft,
                    modifier = Modifier.size(56.dp),
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    slime?.name ?: request.ownerName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Theme.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (slime == null) "wants to be friends" else "from ${request.ownerName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Theme.inkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(relativeTime(request.createdAt), style = MaterialTheme.typography.bodySmall, color = Theme.inkSoft)
            }
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "More options for ${request.ownerName}",
                        tint = Theme.inkSoft,
                    )
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(
                        text = { Text("Report…") },
                        leadingIcon = { Icon(Icons.Filled.Report, contentDescription = null) },
                        onClick = {
                            open = false
                            report()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Block", color = Theme.danger) },
                        leadingIcon = { Icon(Icons.Filled.Block, contentDescription = null, tint = Theme.danger) },
                        onClick = {
                            open = false
                            block()
                        },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppButton("Accept", Modifier.weight(1f), systemIcon = Icons.Filled.Check, enabled = !busy, action = accept)
            AppButton("Decline", Modifier.weight(1f), kind = AppButtonKind.secondary, enabled = !busy, action = decline)
        }
    }
}

/** A challenge a friend sent you, with a Play button. */
@Composable
internal fun FriendChallengeRow(challenge: Challenge, play: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ItemIcon(challenge.game.icon, size = 44.dp)
        Column(
            Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(challenge.game.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Theme.ink)
            Text(
                "${challenge.friendName} scored ${challenge.theirScore ?: 0}. Beat it on the same board.",
                style = MaterialTheme.typography.bodyMedium,
                color = Theme.inkSoft,
            )
            Text("Ends ${relativeTimeInSentence(challenge.expiresAt)}", style = MaterialTheme.typography.bodySmall, color = Theme.inkSoft)
        }
        AppButton(
            "Play",
            Modifier.semantics { contentDescription = "Play ${challenge.game.title} against ${challenge.friendName}" },
            compact = true,
            action = play,
        )
    }
}

/** A challenge you sent that isn't answered yet, or one you both played. */
@Composable
internal fun FriendChallengeResultRow(challenge: Challenge) {
    val mine = challenge.myScore
    val theirs = challenge.theirScore
    val outcome: Int? = if (mine != null && theirs != null) mine.compareTo(theirs).coerceIn(-1, 1) else null
    val icon = when (outcome) {
        1 -> Icons.Filled.EmojiEvents
        0 -> Icons.Filled.Balance
        null -> Icons.Filled.HourglassTop
        else -> Icons.Filled.Flag
    }
    val color = when (outcome) {
        1 -> Theme.gold
        null -> Theme.sky
        else -> Theme.inkSoft
    }
    val game = challenge.game.title
    val friend = challenge.friendName
    val headline = when (outcome) {
        1 -> "You beat $friend in $game"
        0 -> "A tie with $friend in $game"
        null -> "Waiting for $friend"
        else -> "$friend won $game"
    }
    val detail = if (mine != null && theirs != null) {
        "You $mine, $friend $theirs"
    } else {
        "$game · your score ${mine ?: 0} · ends ${relativeTimeInSentence(challenge.expiresAt)}"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(headline, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Theme.ink)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Theme.inkSoft)
        }
    }
}

// MARK: - Acting on friends

/**
 * Runs friend actions for a screen and holds what its dialogs and sheets
 * show, so the list and the detail screen behave the same. Actions run in
 * the store's scope, so they finish when the screen closes.
 */
@Stable
internal class FriendActionCenter(private val friends: FriendsStore) {
    var giftFor by mutableStateOf<Friend?>(null)
    var greetFor by mutableStateOf<Friend?>(null)
    var reportTarget by mutableStateOf<ReportTarget?>(null)
    var blockTarget by mutableStateOf<ReportTarget?>(null)
    var removeFor by mutableStateOf<Friend?>(null)
    var toast by mutableStateOf<Toast?>(null)

    /** Friends with an action on its way. */
    var busy by mutableStateOf<Set<String>>(emptySet())
        private set

    /** The device that was just removed or blocked from here. */
    var gone by mutableStateOf<String?>(null)
        private set

    fun quick(action: FriendQuickAction, friend: Friend) {
        when (action) {
            FriendQuickAction.hug -> interact(InteractionKind.hug, friend)
            FriendQuickAction.visit -> interact(InteractionKind.visit, friend)
            FriendQuickAction.gift -> giftFor = friend
            FriendQuickAction.greet -> greetFor = friend
        }
    }

    fun interact(kind: InteractionKind, friend: Friend, food: Food? = null) {
        if (friend.id in busy) return
        busy = busy + friend.id
        friends.scope.launch {
            val line = friends.interact(friend, kind, food)
            busy = busy - friend.id
            announce(line)
        }
    }

    fun greet(friend: Friend, preset: MessagePreset) {
        friends.scope.launch { announce(friends.send(preset, friend)) }
    }

    fun remove(friend: Friend) {
        friends.scope.launch {
            if (friends.remove(friend)) {
                gone = friend.id
                toast = Toast("${friend.slime.name} is no longer your friend.", good = true)
            } else {
                showError()
            }
        }
    }

    fun block(target: ReportTarget) {
        friends.scope.launch {
            if (friends.block(target.deviceID)) {
                gone = target.deviceID
                toast = Toast("${target.ownerName} is blocked.", good = true)
            } else {
                showError()
            }
        }
    }

    /** Runs [work]; shows [success] when it returns true, the store's error otherwise. */
    fun perform(success: String? = null, work: suspend () -> Boolean) {
        friends.scope.launch {
            if (work()) {
                if (success != null) toast = Toast(success, good = true)
            } else {
                showError()
            }
        }
    }

    fun say(text: String, good: Boolean = true) {
        toast = Toast(text, good)
    }

    /** Moves the store's last error into the toast. */
    fun showError() {
        val message = friends.lastError.value ?: "Something went wrong. Please try again."
        friends.lastError.value = null
        toast = Toast(message, good = false)
    }

    private fun announce(line: String?) {
        if (line != null) toast = Toast(line, good = true) else showError()
    }
}

@Composable
internal fun rememberFriendActions(): FriendActionCenter {
    val friends = LocalFriendsStore.current
    return remember(friends) { FriendActionCenter(friends) }
}

/**
 * The gift sheet, greeting picker, report sheet, remove and block
 * confirmations and the toast of a [FriendActionCenter]. Put it last in a
 * full-size Box over the screen.
 */
@Composable
internal fun BoxScope.FriendActionsHost(center: FriendActionCenter) {
    center.giftFor?.let { friend ->
        FriendsGiftSheet(friend, onDismiss = { center.giftFor = null }) { food ->
            center.interact(InteractionKind.gift, friend, food)
        }
    }
    center.greetFor?.let { friend ->
        GreetingSheet(friend, onDismiss = { center.greetFor = null }) { preset -> center.greet(friend, preset) }
    }
    center.reportTarget?.let { target ->
        FullScreenSheet(onDismiss = { center.reportTarget = null }) {
            FriendsReportSheet(target, onDone = { center.reportTarget = null })
        }
    }
    center.removeFor?.let { friend ->
        ConfirmDialog(
            title = "Remove ${friend.slime.name}?",
            message = "You and ${friend.ownerName} stop being friends. Memories and your shared garden are gone, and you'd need a new code to connect again.",
            confirm = "Remove Friend",
            onConfirm = { center.remove(friend) },
            onDismiss = { center.removeFor = null },
        )
    }
    center.blockTarget?.let { target ->
        ConfirmDialog(
            title = "Block ${target.ownerName}?",
            message = "You won't be friends anymore and nothing from ${target.ownerName} will reach you. They aren't notified. You can unblock them under Privacy & blocked.",
            confirm = "Block",
            onConfirm = { center.block(target) },
            onDismiss = { center.blockTarget = null },
        )
    }
    ToastOverlay(center.toast, onDismiss = { if (center.toast == it) center.toast = null })
}

// MARK: - Gift and greeting sheets

/** Food to send a friend, paid with coins. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FriendsGiftSheet(friend: Friend, onDismiss: () -> Unit, send: (Food) -> Unit) {
    val store = LocalGameStore.current
    val state by store.state.collectAsStateWithLifecycle()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val foods = Food.entries.filter { it.price > 0 && state.slime.level >= it.unlockLevel }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Theme.backdrop) {
        LazyColumn(Modifier.navigationBarsPadding()) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 16.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Gift for ${friend.slime.name}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Theme.ink,
                        modifier = Modifier.weight(1f),
                    )
                    CoinCounter(state.wallet.coins)
                }
            }
            items(foods, key = { it.name }) { food ->
                val affordable = state.wallet.coins >= food.price
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(enabled = affordable, role = Role.Button) {
                            send(food)
                            onDismiss()
                        }
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .clearAndSetSemantics {
                            contentDescription = "${food.title}, ${food.price} coins" + if (affordable) "" else ". Not enough coins."
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ItemIcon(food.sprite, size = 40.dp)
                    Text(
                        food.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (affordable) Theme.ink else Theme.inkSoft,
                        modifier = Modifier.weight(1f),
                    )
                    PixelItem("coin", Modifier.size(14.dp))
                    Text(
                        "${food.price}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (affordable) Theme.ink else Theme.inkSoft,
                    )
                }
            }
            item {
                Text(
                    "${friend.slime.name} gets to eat it. You can send each friend three gifts a day. More food unlocks as your slime levels up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Theme.inkSoft,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }
    }
}

/** Short preset greetings, so they're always friendly. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GreetingSheet(friend: Friend, onDismiss: () -> Unit, send: (MessagePreset) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Theme.backdrop) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                "Send a greeting",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Theme.ink,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                "To ${friend.slime.name}. Greetings are short presets, so they're always friendly.",
                style = MaterialTheme.typography.bodyMedium,
                color = Theme.inkSoft,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
            )
            for (preset in MessagePreset.entries) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable(role = Role.Button) {
                            onDismiss()
                            send(preset)
                        }
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    PixelItem(preset.icon, Modifier.size(22.dp))
                    Text(preset.text, style = MaterialTheme.typography.bodyLarge, color = Theme.ink)
                }
            }
        }
    }
}

// MARK: - Titles

internal val InteractionKind.title: String
    get() = when (this) {
        InteractionKind.hug -> "Hugs"
        InteractionKind.visit -> "Visits"
        InteractionKind.gift -> "Gifts"
    }

internal val InteractionKind.icon: ImageVector
    get() = when (this) {
        InteractionKind.hug -> Icons.Filled.EmojiPeople
        InteractionKind.visit -> Icons.Filled.Home
        InteractionKind.gift -> Icons.Filled.CardGiftcard
    }

/**
 * Shown in the list. The server still keeps memories about love for older
 * app builds; those stay hidden. Kinds from a newer server show with a
 * general title.
 */
internal val FriendMemory.isShown: Boolean
    get() = !(kind.startsWith("love.") || kind == "inLove" || kind == "first.kiss")

internal val FriendMemory.title: String
    get() = when (kind) {
        "met" -> "Became friends"
        "first.hug" -> "First hug"
        "first.visit" -> "First visit"
        "first.gift" -> "First gift"
        "firstChallengeWin" -> "First challenge won"
        "firstCoop" -> "First full garden"
        else -> "A moment together"
    }

internal val FriendMemory.icon: ImageVector
    get() = when (kind) {
        "met" -> Icons.Filled.People
        "first.hug" -> Icons.Filled.EmojiPeople
        "first.visit" -> Icons.Filled.Home
        "first.gift" -> Icons.Filled.CardGiftcard
        "firstChallengeWin" -> Icons.Filled.EmojiEvents
        "firstCoop" -> Icons.Filled.Eco
        else -> Icons.Filled.AutoAwesome
    }

/** How a friend's slime is doing, in a few words. */
internal val Mood.friendTitle: String
    get() = when (this) {
        Mood.idle -> "Doing fine"
        Mood.sad -> "Feeling a bit low"
        Mood.sleep -> "Sleeping"
    }

internal val Mood.friendIcon: ImageVector
    get() = when (this) {
        Mood.idle -> Icons.Filled.SentimentSatisfied
        Mood.sad -> Icons.Filled.Cloud
        Mood.sleep -> Icons.Filled.Bedtime
    }

/** A section's card background colour for a row that is you. */
internal val youTint: Color @Composable get() = Theme.accent.copy(alpha = 0.14f)
