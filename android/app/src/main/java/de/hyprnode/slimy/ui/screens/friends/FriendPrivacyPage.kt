package de.hyprnode.slimy.ui.screens.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.core.LocalFriendsStore
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.core.Toast
import de.hyprnode.slimy.model.prefixChars
import de.hyprnode.slimy.online.BlockedDevice
import de.hyprnode.slimy.online.CareRole
import de.hyprnode.slimy.online.FriendsStore
import de.hyprnode.slimy.online.HyprnodeAPI
import de.hyprnode.slimy.ui.kit.AppButton
import de.hyprnode.slimy.ui.kit.GroupedDivider
import de.hyprnode.slimy.ui.kit.GroupedRow
import de.hyprnode.slimy.ui.kit.GroupedSection
import de.hyprnode.slimy.ui.kit.SheetScaffold
import de.hyprnode.slimy.ui.kit.Theme
import kotlinx.coroutines.launch

/**
 * Names, what friends see, who may ask, who is blocked, going offline and
 * deleting everything on the server (App/UI/Screens/FriendsPrivacy.swift).
 */
@Composable
internal fun FriendPrivacyPage(onBack: () -> Unit, onClose: () -> Unit) {
    val friends = LocalFriendsStore.current
    val store = LocalGameStore.current
    val state by store.state.collectAsStateWithLifecycle()
    val isOnline by friends.isOnline.collectAsStateWithLifecycle()
    val hasAccount by friends.hasServerAccount.collectAsStateWithLifecycle()
    val ownerName by friends.ownerName.collectAsStateWithLifecycle()
    val privacy by friends.privacy.collectAsStateWithLifecycle()
    val blocked by friends.blocked.collectAsStateWithLifecycle()
    val profileNotice by friends.profileNotice.collectAsStateWithLifecycle()
    val careNameNotice by friends.careNameNotice.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var ownerDraft by rememberSaveable { mutableStateOf(friends.ownerName.value) }
    var slimeDraft by rememberSaveable { mutableStateOf(store.state.value.slime.name) }
    var savingNames by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var pendingShareMood by remember { mutableStateOf<Boolean?>(null) }
    var pendingAcceptRequests by remember { mutableStateOf<Boolean?>(null) }
    var unblocking by remember { mutableStateOf<String?>(null) }
    var confirmOffline by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var showGoOnline by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<Toast?>(null) }

    LaunchedEffect(Unit) { friends.loadBlocked() }

    fun showStoreError() {
        toast = Toast(friends.lastError.value ?: "Something went wrong. Please try again.", good = false)
        friends.lastError.value = null
    }

    val cleanedOwner = HyprnodeAPI.cleanedName(ownerDraft)
    val cleanedSlime = HyprnodeAPI.cleanedName(slimeDraft).prefixChars(HyprnodeAPI.slimeNameLimit)
    val namesValid = cleanedOwner.isNotEmpty() && cleanedSlime.isNotEmpty()
    val namesChanged = cleanedOwner != ownerName || cleanedSlime != state.slime.name

    fun saveNames() {
        val owner = cleanedOwner
        val slime = cleanedSlime
        val previousSlime = store.state.value.slime.name
        savingNames = true
        nameError = null
        friends.scope.launch {
            if (slime != previousSlime) store.update { it.slime.name = slime }
            var ok = true
            if (owner != friends.ownerName.value) {
                ok = friends.rename(owner)
            } else if (slime != previousSlime && friends.isOnline.value) {
                ok = friends.uploadProfileNow()
            }
            savingNames = false
            if (ok) {
                ownerDraft = owner
                slimeDraft = slime
                toast = Toast("Names saved.", good = true)
            } else {
                // Keep the old names everywhere. A refused name is shown next
                // to the fields, anything else as a toast.
                if (slime != previousSlime) store.update { it.slime.name = previousSlime }
                val refused = friends.nameError.value
                if (refused != null) {
                    nameError = refused
                } else {
                    toast = Toast(friends.lastError.value ?: "The names couldn't be saved.", good = false)
                }
                friends.nameError.value = null
                friends.lastError.value = null
            }
        }
    }

    fun unblock(device: BlockedDevice) {
        unblocking = device.deviceId
        friends.scope.launch {
            val ok = friends.unblock(device)
            unblocking = null
            if (ok) toast = Toast("${device.ownerName} is unblocked.", good = true) else showStoreError()
        }
    }

    fun deleteData() {
        deleting = true
        friends.scope.launch {
            val ok = friends.deleteAccount()
            deleting = false
            if (ok) {
                ownerDraft = ""
                toast = Toast("Your data on slime.hyprnode.de is deleted.", good = true)
            } else {
                showStoreError()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        SheetScaffold(
            title = "Privacy & blocked",
            onDone = onClose,
            inline = true,
            navigationIcon = { BackButton(onBack) },
        ) { padding ->
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .imePadding(),
                contentPadding = listPadding(padding),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                item {
                    GroupedSection(
                        header = "Names friends see",
                        footer = "Use a first name or a nickname. Names that are rude or contain links, email addresses or phone numbers aren't accepted.",
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = ownerDraft,
                                onValueChange = {
                                    ownerDraft = it.prefixChars(HyprnodeAPI.nameLimit)
                                    if (!savingNames) nameError = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Your name") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                            )
                            OutlinedTextField(
                                value = slimeDraft,
                                onValueChange = {
                                    slimeDraft = it.prefixChars(HyprnodeAPI.slimeNameLimit)
                                    if (!savingNames) nameError = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Your slime") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                            )
                            if (namesChanged) {
                                AppButton(
                                    title = if (savingNames) "Saving…" else "Save Names",
                                    enabled = namesValid && !savingNames,
                                    action = { saveNames() },
                                )
                            }
                            (nameError ?: profileNotice ?: careNameNotice)?.let { WarningLine(it) }
                        }
                    }
                }
                if (isOnline) {
                    item {
                        GroupedSection(
                            header = "What friends see",
                            footer = "Friends always see your name and your slime's name, look and level. Never your location, your contacts or anything else on your phone. With requests off, nobody new can add you and you can't make a code.",
                        ) {
                            SwitchRow("Show my slime's mood", pendingShareMood ?: privacy.shareMood) { value ->
                                pendingShareMood = value
                                friends.scope.launch {
                                    val ok = friends.setPrivacy { it.copy(shareMood = value) }
                                    pendingShareMood = null
                                    if (!ok) showStoreError()
                                }
                            }
                            GroupedDivider()
                            SwitchRow("Allow new friend requests", pendingAcceptRequests ?: privacy.acceptRequests) { value ->
                                pendingAcceptRequests = value
                                friends.scope.launch {
                                    val ok = friends.setPrivacy { it.copy(acceptRequests = value) }
                                    pendingAcceptRequests = null
                                    if (!ok) showStoreError()
                                }
                            }
                        }
                    }
                    item {
                        GroupedSection(
                            header = "Blocked",
                            footer = "Blocking ends the friendship. Blocked people can't send you requests, greetings or gifts, and your codes look unknown to them. They aren't notified.",
                        ) {
                            if (blocked.isEmpty()) {
                                Text(
                                    "Nobody is blocked.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Theme.inkSoft,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                            blocked.forEachIndexed { index, device ->
                                if (index > 0) GroupedDivider()
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 56.dp)
                                        .padding(start = 16.dp, end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(
                                        Modifier
                                            .weight(1f)
                                            .semantics(mergeDescendants = true) {},
                                    ) {
                                        Text(device.ownerName, style = MaterialTheme.typography.bodyLarge, color = Theme.ink)
                                        Text(
                                            "Blocked ${mediumDate(device.since)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Theme.inkSoft,
                                        )
                                    }
                                    if (unblocking == device.deviceId) {
                                        CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), strokeWidth = 2.dp)
                                    } else {
                                        TextButton(
                                            onClick = { unblock(device) },
                                            modifier = Modifier.semantics { contentDescription = "Unblock ${device.ownerName}" },
                                        ) { Text("Unblock") }
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    GroupedSection(
                        header = "Connection",
                        footer = if (isOnline) {
                            "Going offline stops all syncing, Care Together included. Your friends stay saved on this phone, and your data stays on the server until you delete it."
                        } else {
                            "Friends, the leaderboard, Care Together and chat need slime.hyprnode.de. Going online asks you to agree to the Terms first."
                        },
                    ) {
                        if (isOnline) {
                            GroupedRow(
                                "Online",
                                icon = { Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Theme.accent) },
                                trailing = { Text("slime.hyprnode.de", style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft) },
                            )
                            GroupedDivider()
                            GroupedRow("Go Offline", titleColor = Theme.danger, onClick = { confirmOffline = true })
                        } else {
                            GroupedRow(
                                "Offline",
                                icon = { Icon(Icons.Filled.WifiOff, contentDescription = null, tint = Theme.inkSoft) },
                                trailing = { Text("Nothing is sent", style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft) },
                            )
                            GroupedDivider()
                            GroupedRow("Go Online…", titleColor = Theme.accent, onClick = { showGoOnline = true })
                        }
                    }
                }
                if (hasAccount) {
                    item {
                        GroupedSection(
                            header = "Your data",
                            footer = "Deletes your device, name, friendships, memories, scores, Care Together and everything friends sent you from slime.hyprnode.de, and takes Slimy offline. Your slime on this phone stays.",
                        ) {
                            if (deleting) {
                                WaitingLine("Deleting…", Modifier.padding(16.dp))
                            } else {
                                GroupedRow(
                                    "Delete My Data on the Server",
                                    titleColor = Theme.danger,
                                    onClick = { confirmDelete = true },
                                )
                            }
                        }
                    }
                }
                item {
                    GroupedSection {
                        GroupedRow(
                            "Privacy Policy",
                            titleColor = Theme.accent,
                            icon = { IconTile(Icons.Filled.PrivacyTip) },
                            onClick = { openLink(context, FriendsStore.privacyURL) },
                        )
                        GroupedDivider()
                        GroupedRow(
                            "Terms of Use",
                            titleColor = Theme.accent,
                            icon = { IconTile(Icons.Filled.Description) },
                            onClick = { openLink(context, FriendsStore.termsURL) },
                        )
                        GroupedDivider()
                        GroupedRow(
                            "Support",
                            titleColor = Theme.accent,
                            icon = { IconTile(Icons.AutoMirrored.Filled.HelpOutline) },
                            onClick = { openLink(context, FriendsStore.supportURL) },
                        )
                    }
                }
            }
        }
        ToastOverlay(toast, onDismiss = { if (toast == it) toast = null })
    }

    if (confirmOffline) {
        val link = state.care
        ConfirmDialog(
            title = "Go offline?",
            message = if (link == null) {
                "Slimy stops talking to slime.hyprnode.de. Your friends stay saved on this phone and you can go online again any time."
            } else {
                "Slimy stops talking to slime.hyprnode.de. Your friends stay saved on this phone. What you do for ${state.slime.name} reaches ${link.partnerName} once you go online again."
            },
            confirm = "Go Offline",
            onConfirm = {
                friends.goOffline()
                toast = Toast("You're offline. Nothing is sent to slime.hyprnode.de.", good = true)
            },
            onDismiss = { confirmOffline = false },
        )
    }
    if (confirmDelete) {
        val base = "Your friendships, memories, scores and everything friends sent you are deleted for good, and Slimy goes offline."
        val link = state.care
        val message = when {
            link == null -> "$base Your slime on this phone stays. This can't be undone."
            link.role == CareRole.joiner ->
                "$base Caring for ${state.slime.name} with ${link.partnerName} stops, and your own slime comes back. This can't be undone."
            else ->
                "$base ${link.partnerName} stops caring for ${state.slime.name} with you; it stays on this phone. This can't be undone."
        }
        ConfirmDialog(
            title = "Delete your data on slime.hyprnode.de?",
            message = message,
            confirm = "Delete Data",
            onConfirm = { deleteData() },
            onDismiss = { confirmDelete = false },
        )
    }
    if (showGoOnline) {
        FullScreenSheet(onDismiss = { showGoOnline = false }) { GoOnlineScreen(onDone = { showGoOnline = false }) }
    }
}
