package de.hyprnode.slimy.ui.screens.settings

import android.app.ActivityManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.SlimyApplication
import de.hyprnode.slimy.core.Feedback
import de.hyprnode.slimy.core.LocalChatStore
import de.hyprnode.slimy.core.LocalFriendsStore
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.core.LocalIsland
import de.hyprnode.slimy.core.saveFileUri
import de.hyprnode.slimy.model.SaveFile
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.model.trimmedSwift
import de.hyprnode.slimy.online.FriendsStore
import de.hyprnode.slimy.platform.IslandController
import de.hyprnode.slimy.platform.Notifier
import de.hyprnode.slimy.ui.kit.GroupedDivider
import de.hyprnode.slimy.ui.kit.GroupedRow
import de.hyprnode.slimy.ui.kit.GroupedSection
import de.hyprnode.slimy.ui.kit.SheetScaffold
import de.hyprnode.slimy.ui.kit.Theme
import de.hyprnode.slimy.ui.screens.common.ConfirmDialog
import de.hyprnode.slimy.ui.screens.common.ScreenColumn
import de.hyprnode.slimy.ui.screens.common.findActivity
import de.hyprnode.slimy.ui.screens.common.openLink
import de.hyprnode.slimy.ui.screens.common.rememberIsResumed
import de.hyprnode.slimy.ui.screens.common.startSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Settings: the slime's chatter and Care Together, sound, notifications,
 * bedtime, the slime notification, phone reactions, online and privacy,
 * backups, help and starting over (App/UI/Screens/SettingsView.swift).
 */
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    onCareTogether: () -> Unit,
    onGoOnline: () -> Unit,
    onHelp: () -> Unit,
    onAbout: () -> Unit,
) {
    val store = LocalGameStore.current
    val island = LocalIsland.current
    val context = LocalContext.current
    val state by store.state.collectAsStateWithLifecycle()
    val resumed = rememberIsResumed()
    var authorized by remember { mutableStateOf<Boolean?>(null) }
    var backgroundRestricted by remember { mutableStateOf(false) }

    LaunchedEffect(state.settings) { Notifier.reschedule(store.state.value) }
    // Also runs when coming back from the system settings.
    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        authorized = Notifier.isAuthorized()
        backgroundRestricted = SettingsSystem.isBackgroundRestricted(context)
        island.refreshStatus()
    }

    SheetScaffold(title = "Settings", onDone = onClose) { padding ->
        ScreenColumn(padding) {
            SlimeSection(onCareTogether)
            SoundSection()
            NotificationSection(authorized) { authorized = it }
            BedtimeSection()
            // Read here, so coming back from the system settings shows the new answer.
            IslandSection(island.presence)
            PhoneSection(backgroundRestricted)
            OnlineSection(onGoOnline)
            PrivacySection()
            BackupSection()
            GroupedSection(header = "Support") {
                LinkRow("Help", Icons.AutoMirrored.Outlined.HelpOutline, onClick = onHelp)
                GroupedDivider(inset = 56.dp)
                LinkRow("About Slimy", Icons.Outlined.Info, onClick = onAbout)
            }
            StartOverSection(onClose)
        }
    }
}

// MARK: - Your slime

@Composable
private fun SlimeSection(onCareTogether: () -> Unit) {
    val store = LocalGameStore.current
    val state by store.state.collectAsStateWithLifecycle()
    val name = state.slime.name
    val partner = (state.care?.partnerName ?: "").trimmedSwift()
    val partnerText = when {
        state.care == null -> "Off"
        partner.isEmpty() -> "On"
        else -> "With $partner"
    }
    GroupedSection(
        header = "Your slime",
        footer = "With Care Together, one other person looks after $name with you on their phone. Everything either of you does for it shows up on both. Coins and things stay your own.",
    ) {
        SwitchRow(
            title = "Talks on its own",
            detail = "$name says what's on its mind every few seconds while Slimy is open.",
            checked = state.settings.chatty,
        ) { on -> store.setSettings { it.chatty = on } }
        GroupedDivider()
        GroupedRow(
            title = "Care Together",
            icon = { Icon(Icons.Filled.People, contentDescription = null, tint = Theme.accent) },
            trailing = { Text(partnerText, style = MaterialTheme.typography.bodyLarge, color = Theme.inkSoft) },
            onClick = onCareTogether,
        )
    }
}

// MARK: - Sound

@Composable
private fun SoundSection() {
    val store = LocalGameStore.current
    val settings by store.state.collectAsStateWithLifecycle()
    GroupedSection(header = "Sound & Haptics", footer = "Sounds and music are muted while your phone is on silent.") {
        SwitchRow("Sounds", checked = settings.settings.sound) { on -> store.setSettings { it.sound = on } }
        GroupedDivider()
        SwitchRow("Music", checked = settings.settings.music) { on ->
            store.setSettings { it.music = on }
            Feedback.shared.updateMusic()
        }
        GroupedDivider()
        SwitchRow("Vibration", checked = settings.settings.haptics) { on -> store.setSettings { it.haptics = on } }
    }
}

// MARK: - Notifications

@Composable
private fun NotificationSection(authorized: Boolean?, onStatus: (Boolean) -> Unit) {
    val store = LocalGameStore.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by store.state.collectAsStateWithLifecycle()
    val prefs = state.settings.notifications
    var asking by remember { mutableStateOf(false) }
    val status = SettingsSystem.notificationStatus(authorized, prefs.asked, Build.VERSION.SDK_INT)

    GroupedSection(
        header = "Notifications",
        footer = "Only the kinds you switch on, and nothing during quiet hours. Alerts from friends and gifts, and the slime notification popping up with a sound, stop after ${Notifier.dailyLimit} a day.",
    ) {
        when (status) {
            NotificationStatus.notDetermined -> {
                BusyRow("Allow Notifications", Icons.Filled.NotificationsActive, busy = asking, enabled = !asking) {
                    scope.launch {
                        asking = true
                        val granted = Notifier.requestPermission(context.findActivity())
                        onStatus(Notifier.isAuthorized())
                        if (granted) Notifier.reschedule(store.state.value)
                        asking = false
                    }
                }
                GroupedDivider()
            }
            NotificationStatus.denied -> {
                Notice("Notifications are turned off for Slimy in the Settings app.")
                GroupedDivider()
                ActionRow("Turn On in Settings") { SettingsSystem.openNotificationSettings(context) }
                GroupedDivider()
            }
            NotificationStatus.allowed -> Unit
        }
        SwitchRow("When it needs something", checked = prefs.needs) { on -> store.setSettings { it.notifications.needs = on } }
        GroupedDivider()
        SwitchRow("Daily challenges and gift", checked = prefs.daily) { on -> store.setSettings { it.notifications.daily = on } }
        GroupedDivider()
        SwitchRow("Friends", checked = prefs.friends) { on -> store.setSettings { it.notifications.friends = on } }
        GroupedDivider()
        SwitchRow("Gifts", checked = prefs.gifts) { on -> store.setSettings { it.notifications.gifts = on } }
        GroupedDivider()
        SwitchRow("Little hellos", checked = prefs.world) { on -> store.setSettings { it.notifications.world = on } }
        GroupedDivider()
        HourPickerRow("Quiet from", prefs.quietFrom, avoiding = prefs.quietUntil) { h -> store.setSettings { it.notifications.quietFrom = h } }
        GroupedDivider()
        HourPickerRow("Quiet until", prefs.quietUntil, avoiding = prefs.quietFrom) { h -> store.setSettings { it.notifications.quietUntil = h } }
    }
}

// MARK: - Bedtime

@Composable
private fun BedtimeSection() {
    val store = LocalGameStore.current
    val state by store.state.collectAsStateWithLifecycle()
    val schedule = state.settings.sleep
    GroupedSection(header = "Bedtime", footer = "You can still wake ${state.slime.name} up or put it to bed any time.") {
        SwitchRow("Goes to bed by itself", checked = schedule.auto) { on -> store.setSettings { it.sleep.auto = on } }
        GroupedDivider()
        HourPickerRow("Bedtime", schedule.bedtime, avoiding = schedule.wake) { h -> store.setSettings { it.sleep.bedtime = h } }
        GroupedDivider()
        HourPickerRow("Wake-up time", schedule.wake, avoiding = schedule.bedtime) { h -> store.setSettings { it.sleep.wake = h } }
    }
}

// MARK: - Slime notification

@Composable
private fun IslandSection(presence: IslandController.Presence) {
    val island = LocalIsland.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isLive by island.isLive.collectAsStateWithLifecycle()
    val problem by island.problem.collectAsStateWithLifecycle()
    val keepAlive by island.keepAlive.collectAsStateWithLifecycle()
    val animated by island.animated.collectAsStateWithLifecycle()
    var working by remember { mutableStateOf(false) }
    val unavailable = presence == IslandController.Presence.unavailable

    GroupedSection(
        header = presence.title,
        footer = if (unavailable) null else
            "Android can take the slime notification away, for example after a restart or when you swipe it away. Slimy brings your slime back when you open the app, or when you use Summon Slime: touch and hold the Slimy icon on your home screen.",
    ) {
        if (unavailable) {
            Notice(presence.explanation)
            GroupedDivider()
            ActionRow("Turn On in Settings") { SettingsSystem.openNotificationSettings(context) }
        } else {
            ValueRow("Status", if (isLive) "Showing" else "Not showing")
            problem?.let {
                GroupedDivider()
                Notice(it)
            }
            GroupedDivider()
            SwitchRow("Always bring it back", checked = keepAlive) { on ->
                island.keepAlive.value = on
                if (on) scope.launch { island.ensureRunning() }
            }
            GroupedDivider()
            SwitchRow(
                "Animation",
                detail = "When it's off, your slime shows as a still picture.",
                checked = animated,
            ) { on -> island.animated.value = on }
            GroupedDivider()
            BusyRow(
                if (isLive) "Restart" else "Start",
                if (isLive) Icons.Filled.Refresh else Icons.Filled.PlayArrow,
                busy = working,
                enabled = !working,
            ) {
                scope.launch {
                    working = true
                    island.ensureRunning(restart = true)
                    working = false
                }
            }
            if (isLive) {
                GroupedDivider()
                BusyRow("Hide", Icons.Filled.VisibilityOff, busy = false, enabled = !working) {
                    scope.launch { island.stop() }
                }
            }
        }
    }
}

// MARK: - Phone reactions

@Composable
private fun PhoneSection(backgroundRestricted: Boolean) {
    val store = LocalGameStore.current
    val context = LocalContext.current
    val state by store.state.collectAsStateWithLifecycle()
    val enabled = state.settings.liveEvents
    var checked by remember { mutableStateOf(false) }

    LaunchedEffect(checked) {
        if (!checked) return@LaunchedEffect
        delay(2_000)
        checked = false
    }

    GroupedSection(
        header = "Reacting to your phone",
        footer = "Battery, charger, Battery Saver, Wi-Fi, mobile data, no internet, morning and night. No setup needed. While Slimy is open it reacts right away. When it's closed, it notices the next time Android lets it run, so a reaction can come a little later.",
    ) {
        SwitchRow("React to my phone", checked = enabled) { on -> store.setSettings { it.liveEvents = on } }
        GroupedDivider()
        BusyRow(
            if (checked) "Checked" else "Check Now",
            if (checked) Icons.Filled.Check else Icons.Filled.Smartphone,
            busy = false,
            enabled = enabled && !checked,
        ) {
            (context.applicationContext as SlimyApplication).phoneMonitor.check()
            checked = true
        }
        if (enabled && backgroundRestricted) {
            GroupedDivider()
            Notice("Background use is restricted for Slimy, so it mostly reacts while the app is open.")
            GroupedDivider()
            ActionRow("Turn On in Settings") { SettingsSystem.openAppSettings(context) }
        }
    }
}

// MARK: - Online

@Composable
private fun OnlineSection(onGoOnline: () -> Unit) {
    val store = LocalGameStore.current
    val friends = LocalFriendsStore.current
    val online by friends.isOnline.collectAsStateWithLifecycle()
    var confirmOffline by remember { mutableStateOf(false) }

    GroupedSection(
        header = "Online",
        footer = "Friends, the leaderboard, Care Together and talking with your slime need slime.hyprnode.de. Your slime, coins and things always stay on this phone; with Care Together, your slime is also shared with your partner.",
    ) {
        GroupedRow(
            title = "Status",
            modifier = Modifier.semantics(mergeDescendants = true) { },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .padding(end = 6.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (online) Theme.accent else Theme.inkSoft),
                    )
                    Text(if (online) "Online" else "Offline", style = MaterialTheme.typography.bodyLarge, color = Theme.inkSoft)
                }
            },
        )
        GroupedDivider()
        if (online) {
            ActionRow("Go Offline", color = Theme.danger) { confirmOffline = true }
        } else {
            ActionRow("Go Online…", onClick = onGoOnline)
        }
    }

    if (confirmOffline) {
        ConfirmDialog(
            title = "Go offline?",
            message = "Slimy stops talking to slime.hyprnode.de. Your friends stay saved on this phone and you can go online again any time.",
            confirmLabel = "Go Offline",
            destructive = true,
            onConfirm = {
                friends.goOffline()
                store.showToast("You're offline. Nothing is sent to slime.hyprnode.de.", good = true)
            },
            onDismiss = { confirmOffline = false },
        )
    }
}

// MARK: - Privacy

@Composable
private fun PrivacySection() {
    val store = LocalGameStore.current
    val friends = LocalFriendsStore.current
    val chat = LocalChatStore.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val consent by chat.aiConsent.collectAsStateWithLifecycle()
    val hasAccount by friends.hasServerAccount.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    GroupedSection(
        header = "Privacy",
        footer = "With Talk with AI on, your message, the recent chat and a few facts about your slime, like how it feels, go to Cloudflare Workers AI and Groq, which write the reply. Your name and device ID are never passed on to them, and slime.hyprnode.de doesn't keep the messages.",
    ) {
        // Consent is only given in the chat's own consent screen, before the first message; here it can only be taken back.
        if (consent) {
            SwitchRow("Talk with AI", detail = "Your slime answers what you write in chat.", checked = true) { on ->
                if (!on) chat.revokeConsent()
            }
        } else {
            GroupedRow(
                title = "Talk with AI",
                subtitle = "The chat asks you before your first message is sent.",
                modifier = Modifier.semantics(mergeDescendants = true) { },
                trailing = { Text("Off", style = MaterialTheme.typography.bodyLarge, color = Theme.inkSoft) },
            )
        }
        if (hasAccount) {
            GroupedDivider()
            BusyRow("Delete My Data on the Server", Icons.Filled.Delete, busy = deleting, enabled = !deleting, color = Theme.danger) {
                confirmDelete = true
            }
        }
        GroupedDivider()
        LinkRow("Privacy Policy", Icons.Outlined.PanTool, external = true) { openLink(context, FriendsStore.privacyURL) }
        GroupedDivider(inset = 56.dp)
        LinkRow("Terms of Use", Icons.Outlined.Description, external = true) { openLink(context, FriendsStore.termsURL) }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete your data on slime.hyprnode.de?",
            message = "Your friendships, scores, Care Together and everything friends sent you are deleted for good, and Slimy goes offline. Your slime on this phone stays. This can't be undone.",
            confirmLabel = "Delete Data",
            destructive = true,
            onConfirm = {
                scope.launch {
                    deleting = true
                    val done = friends.deleteAccount()
                    deleting = false
                    if (done) {
                        store.showToast("Your data on slime.hyprnode.de is deleted.", good = true)
                    } else {
                        store.showToast(
                            friends.lastError.value ?: "Couldn't reach slime.hyprnode.de. Check your connection and try again.",
                            good = false,
                        )
                    }
                    friends.lastError.value = null
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

// MARK: - Backup

@Composable
private fun BackupSection() {
    val store = LocalGameStore.current
    val friends = LocalFriendsStore.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by store.state.collectAsStateWithLifecycle()
    val name = state.slime.name
    val sharing = state.care != null
    var staged by remember { mutableStateOf<ByteArray?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { SaveImport.stage(context, uri) } }
            result.onSuccess { bytes ->
                // Let the file picker finish closing before the dialog opens.
                delay(350)
                staged = bytes
            }.onFailure { error ->
                store.showToast((error as? SaveFile.ProblemException)?.problem?.message ?: "Couldn't read that file.", good = false)
            }
        }
    }

    GroupedSection(
        header = "Backup",
        footer = if (sharing) {
            "Export a save to keep a copy in your files. While you care for $name together, a save can't be imported; stop sharing in Care Together first."
        } else {
            "Your slime lives only on this phone. Export a save to keep a copy in your files or to move your slime to a new phone. Friends aren't part of a save."
        },
    ) {
        LinkRow("Export Save", Icons.Filled.Share) {
            try {
                val file = store.exportSave()
                val uri = context.saveFileUri(file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, file.name)
                    clipData = ClipData.newRawUri(file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startSafely(context, Intent.createChooser(send, "Export Save"))
            } catch (e: Exception) {
                store.showToast("Couldn't create the save file. Try again in a moment.", good = false)
            }
        }
        GroupedDivider(inset = 56.dp)
        // A save would replace the slime you share; stop sharing first.
        LinkRow("Import Save…", Icons.Filled.Download, enabled = !sharing) { picker.launch(arrayOf("*/*")) }
    }

    staged?.let { bytes ->
        ConfirmDialog(
            title = "Replace $name?",
            message = "$name and everything it has will be replaced by the slime in this save. Export a save first if you want to keep $name. This can't be undone.",
            confirmLabel = "Replace with This Save",
            destructive = true,
            onConfirm = {
                try {
                    // importSave also reschedules notifications and updates the slime notification.
                    store.importSave(ByteArrayInputStream(bytes))
                    Feedback.shared.updateMusic()
                    // Friends see the imported slime's name, look and stage. Does nothing while offline.
                    scope.launch { friends.refresh() }
                    Feedback.shared.play(Sound.success)
                    store.showToast("Save imported. Welcome back, ${store.state.value.slime.name}.", good = true)
                } catch (e: SaveFile.ProblemException) {
                    store.showToast(e.problem.message, good = false)
                } catch (e: Exception) {
                    store.showToast("That file isn't a Slimy save, or it's damaged.", good = false)
                }
            },
            onDismiss = { staged = null },
        )
    }
}

/** Reads a picked file while access to it is granted, so it can still be imported after the confirmation. */
object SaveImport {
    fun stage(context: Context, uri: Uri): ByteArray {
        val resolver = context.contentResolver
        val size = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull()
        if (size != null && size > SaveFile.maxBytes) throw SaveFile.ProblemException(SaveFile.Problem.tooLarge)
        val input = resolver.openInputStream(uri) ?: throw SaveFile.ProblemException(SaveFile.Problem.unreadable)
        return input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > SaveFile.maxBytes) throw SaveFile.ProblemException(SaveFile.Problem.tooLarge)
            }
            out.toByteArray()
        }
    }
}

// MARK: - Start over

@Composable
private fun StartOverSection(onClose: () -> Unit) {
    val store = LocalGameStore.current
    val chat = LocalChatStore.current
    val state by store.state.collectAsStateWithLifecycle()
    val name = state.slime.name
    val sharing = state.care != null
    var confirm by remember { mutableStateOf(false) }

    GroupedSection(
        header = "Start over",
        footer = if (sharing) {
            "You care for $name together with someone. Stop sharing in Care Together before you release a slime."
        } else {
            "Export a save first if you might want $name back."
        },
    ) {
        // A shared slime belongs to two people; stop sharing first.
        ActionRow("Release $name…", color = Theme.danger, enabled = !sharing) { confirm = true }
    }

    if (confirm) {
        ConfirmDialog(
            title = "Release $name?",
            message = "$name will be gone for good, with its levels, coins, things and your chat with it. Your friends and online profile aren't affected. This can't be undone.",
            confirmLabel = "Release $name",
            destructive = true,
            onConfirm = {
                onClose()
                store.releaseSlime()
                // The conversation belonged to this slime.
                chat.clear()
                chat.draft.value = ""
            },
            onDismiss = { confirm = false },
        )
    }
}

// MARK: - Rows

/** A switch with its title (and a line of explanation); the whole row toggles. */
@Composable
internal fun SwitchRow(
    title: String,
    detail: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    GroupedRow(
        title = title,
        subtitle = detail,
        enabled = enabled,
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

/** A tappable row in the accent colour (or [color]), like a button in a list. */
@Composable
private fun ActionRow(title: String, color: Color? = null, enabled: Boolean = true, onClick: () -> Unit) {
    GroupedRow(title = title, titleColor = color ?: Theme.accent, enabled = enabled, onClick = onClick)
}

/** A row with an icon that opens something; [external] adds the arrow for links that leave the app. */
@Composable
private fun LinkRow(title: String, icon: ImageVector, enabled: Boolean = true, external: Boolean = false, onClick: () -> Unit) {
    GroupedRow(
        title = title,
        titleColor = Theme.accent,
        enabled = enabled,
        icon = { Icon(icon, contentDescription = null, tint = Theme.accent) },
        trailing = if (external) {
            { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = Theme.inkSoft, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
        onClickLabel = if (external) "Open in your browser" else null,
        onClick = onClick,
    )
}

/** A button row that shows a spinner while its work runs. */
@Composable
private fun BusyRow(title: String, icon: ImageVector, busy: Boolean, enabled: Boolean, color: Color? = null, onClick: () -> Unit) {
    val tint = color ?: Theme.accent
    GroupedRow(
        title = title,
        titleColor = tint,
        enabled = enabled,
        icon = { Icon(icon, contentDescription = null, tint = tint) },
        trailing = if (busy) {
            { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Theme.accent) }
        } else {
            null
        },
        onClick = onClick,
    )
}

@Composable
private fun ValueRow(title: String, value: String) {
    GroupedRow(
        title = title,
        modifier = Modifier.semantics(mergeDescendants = true) { },
        trailing = { Text(value, style = MaterialTheme.typography.bodyLarge, color = Theme.inkSoft) },
    )
}

/** Something Android keeps Slimy from doing, and why it matters. */
@Composable
private fun Notice(text: String) {
    GroupedRow(
        title = text,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFFF9500)) },
    )
}

/** Picks a full hour, shown in the phone's own time format. [avoiding] can't be picked, so a range never starts where it ends. */
@Composable
private fun HourPickerRow(title: String, hour: Int, avoiding: Int?, onChange: (Int) -> Unit) {
    val context = LocalContext.current
    val is24 = DateFormat.is24HourFormat(context)
    val current = hour.coerceIn(0, 23)
    var open by remember { mutableStateOf(false) }
    Box {
        GroupedRow(
            title = title,
            modifier = Modifier.semantics { stateDescription = SettingsSystem.hourLabel(current, is24) },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(SettingsSystem.hourLabel(current, is24), style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium, color = Theme.accent)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = Theme.accent)
                }
            },
            onClickLabel = "Change",
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.fillMaxWidth(0.5f)) {
            for (value in SettingsSystem.hourChoices(current, avoiding)) {
                DropdownMenuItem(
                    text = { Text(SettingsSystem.hourLabel(value, is24)) },
                    trailingIcon = if (value == current) {
                        { Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Theme.accent) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        onChange(value)
                    },
                )
            }
        }
    }
}

enum class NotificationStatus { notDetermined, denied, allowed }

/** What the system allows Slimy, and the ways to its settings. */
object SettingsSystem {
    /**
     * Whether to offer "Allow Notifications", say they're off, or nothing.
     * [authorized] is null until it has been checked. From Android 13 a
     * phone that was never asked counts as not decided yet.
     */
    fun notificationStatus(authorized: Boolean?, asked: Boolean, sdk: Int): NotificationStatus = when {
        authorized == true -> NotificationStatus.allowed
        authorized == null -> if (asked) NotificationStatus.allowed else NotificationStatus.notDetermined
        sdk >= 33 && !asked -> NotificationStatus.notDetermined
        else -> NotificationStatus.denied
    }

    /** The hours a picker offers: all but [avoiding], unless that is the one picked. */
    fun hourChoices(current: Int, avoiding: Int?): List<Int> = (0 until 24).filter { it != avoiding || it == current }

    /** "22:00" or "10:00 PM", like the phone's clock. */
    fun hourLabel(hour: Int, is24: Boolean, locale: Locale = Locale.getDefault()): String =
        LocalTime.of(hour.coerceIn(0, 23), 0).format(DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm a", locale))

    fun isBackgroundRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        return manager.isBackgroundRestricted
    }

    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        if (!startSafely(context, intent)) openAppSettings(context)
    }

    fun openAppSettings(context: Context) {
        startSafely(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }
}
