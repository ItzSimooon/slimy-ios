package de.hyprnode.slimy.ui.screens.friends

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.hyprnode.slimy.core.Feedback
import de.hyprnode.slimy.core.LocalFriendsStore
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.model.prefixChars
import de.hyprnode.slimy.online.HyprnodeAPI
import de.hyprnode.slimy.online.ReportReason
import de.hyprnode.slimy.ui.kit.AppButton
import de.hyprnode.slimy.ui.kit.GroupedDivider
import de.hyprnode.slimy.ui.kit.GroupedSection
import de.hyprnode.slimy.ui.kit.Theme
import kotlinx.coroutines.launch

/**
 * Reporting a person to the Slimy team: a reason, an optional note, and an
 * optional block, which is a separate step on purpose
 * (App/UI/Screens/FriendsReport.swift).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FriendsReportSheet(target: ReportTarget, onDone: () -> Unit) {
    val friends = LocalFriendsStore.current
    val focus = LocalFocusManager.current
    var reason by rememberSaveable { mutableStateOf(ReportReason.inappropriateName) }
    var note by rememberSaveable { mutableStateOf("") }
    var alsoBlock by rememberSaveable { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sent by rememberSaveable { mutableStateOf(false) }
    var blockProblem by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = sending) {}

    fun send() {
        focus.clearFocus()
        error = null
        sending = true
        val blocking = alsoBlock
        friends.scope.launch {
            val ok = friends.report(target.deviceID, reason, note, text = null)
            if (!ok) {
                sending = false
                error = friends.lastError.value ?: "The report couldn't be sent. Please try again."
                friends.lastError.value = null
                return@launch
            }
            if (blocking) {
                val blocked = friends.block(target.deviceID)
                if (!blocked) {
                    blockProblem = friends.lastError.value ?: "Please try again."
                    friends.lastError.value = null
                }
            }
            sending = false
            Feedback.shared.play(Sound.success)
            sent = true
        }
    }

    Scaffold(
        containerColor = Theme.backdrop,
        topBar = {
            TopAppBar(
                title = { Text("Report") },
                navigationIcon = {
                    if (!sent) {
                        IconButton(onClick = onDone, enabled = !sending) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel")
                        }
                    }
                },
                actions = {
                    if (sending) {
                        CircularProgressIndicator(Modifier.padding(end = 16.dp).size(22.dp), strokeWidth = 2.dp)
                    } else if (!sent) {
                        TextButton(onClick = { send() }) { Text("Send", fontWeight = FontWeight.SemiBold) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Theme.backdrop),
            )
        },
    ) { padding ->
        if (sent) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Theme.accent, modifier = Modifier.size(48.dp))
                Text(
                    "Thanks for telling us",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Theme.ink,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "The Slimy team will look at your report. Thank you for helping keep Slimy friendly.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Theme.inkSoft,
                    textAlign = TextAlign.Center,
                )
                if (alsoBlock) {
                    val problem = blockProblem
                    Text(
                        if (problem != null) {
                            "Blocking didn't work: $problem You can block ${target.ownerName} from the friends list."
                        } else {
                            "${target.ownerName} is blocked."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (problem != null) Theme.danger else Theme.inkSoft,
                        textAlign = TextAlign.Center,
                    )
                }
                AppButton("Done", compact = true, action = onDone)
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .imePadding(),
                contentPadding = listPadding(padding),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                item {
                    GroupedSection(header = "What's wrong with ${target.ownerName}?") {
                        Column(Modifier.selectableGroup()) {
                            ReportReason.forPeople.forEachIndexed { index, option ->
                                if (index > 0) GroupedDivider()
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 52.dp)
                                        .selectable(selected = reason == option, role = Role.RadioButton) { reason = option }
                                        .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(option.title, style = MaterialTheme.typography.bodyLarge, color = Theme.ink, modifier = Modifier.weight(1f))
                                    RadioButton(selected = reason == option, onClick = null)
                                }
                            }
                        }
                    }
                }
                item {
                    Column {
                        SectionHeader("Details")
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it.prefixChars(HyprnodeAPI.reportNoteLimit) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("What happened? (optional)") },
                            minLines = 3,
                            maxLines = 6,
                        )
                        SectionFooter("${note.length} of ${HyprnodeAPI.reportNoteLimit} characters")
                    }
                }
                item {
                    GroupedSection(
                        footer = "Reports go to the Slimy team. A person reviews each one and can reset names or ban a device. ${target.ownerName} isn't told who reported them. A report doesn't block anyone; turn on Block to stop hearing from them right away.",
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .selectable(selected = alsoBlock, role = Role.Switch) { alsoBlock = !alsoBlock }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Also block ${target.ownerName}", style = MaterialTheme.typography.bodyLarge, color = Theme.ink, modifier = Modifier.weight(1f))
                            Switch(checked = alsoBlock, onCheckedChange = null)
                        }
                    }
                }
                error?.let { message ->
                    item {
                        Row(
                            Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = Theme.danger)
                            Text(message, color = Theme.danger, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
