package de.hyprnode.slimy.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.core.LocalChatStore
import de.hyprnode.slimy.model.Slime
import de.hyprnode.slimy.online.ChatStore
import de.hyprnode.slimy.ui.kit.AppButton
import de.hyprnode.slimy.ui.kit.GroupedDivider
import de.hyprnode.slimy.ui.kit.GroupedRow
import de.hyprnode.slimy.ui.kit.GroupedSection
import de.hyprnode.slimy.ui.kit.Theme
import de.hyprnode.slimy.ui.screens.friends.ConfirmDialog
import de.hyprnode.slimy.ui.screens.friends.IconTile
import de.hyprnode.slimy.ui.screens.friends.InfoRow
import de.hyprnode.slimy.ui.screens.friends.openLink

/**
 * What the player needs to know before talking to the slime: what is sent,
 * who processes it and what is kept. Shown in the consent sheet and in About
 * AI Chat, so both always say the same (App/UI/Screens/ChatConsentView.swift).
 */
internal object ChatDisclosure {
    class Point(val icon: ImageVector, val title: String, val text: String)

    fun points(slimeName: String): List<Point> = listOf(
        Point(
            Icons.Outlined.ChatBubbleOutline, "What is sent",
            "Your message, the recent chat and a few facts about $slimeName: its name, age, level, mood, how it feels, its needs and favourite food, and the time of day. Your name and your device ID are never passed on to the AI services.",
        ),
        Point(
            Icons.Filled.Memory, "Who writes the reply",
            "An AI service writes $slimeName's replies in the mood it's in, and the chat can change how it feels. Cloudflare Workers AI and Groq process your message to do that.",
        ),
        Point(
            Icons.Filled.Lock, "What is kept",
            "Slimy's server doesn't keep your messages. The chat stays on this phone until you clear it. Please don't share personal details.",
        ),
        Point(
            Icons.Filled.Report, "Replies can be wrong",
            "AI makes the replies up, so they can be odd or wrong. Touch and hold a reply to report it.",
        ),
    )
}

/**
 * Asked once, before the first message leaves the phone. Agree turns on
 * `ChatStore.aiConsent`; Not Now (or swiping the sheet away) sends nothing.
 * [onClose] is called however it closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatConsentSheet(slime: Slime, onAgree: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = Theme.backdrop) {
        Column(
            Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ChatAvatar(slime, size = 72.dp, fill = Theme.well)
                Text(
                    "Talk with ${slime.name}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Theme.ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "${slime.name}'s replies are written by AI. Here's what happens when you send a message.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Theme.inkSoft,
                    textAlign = TextAlign.Center,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (point in ChatDisclosure.points(slime.name)) {
                    InfoRow(point.icon, point.title, point.text, Modifier.padding(horizontal = 0.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { openLink(context, ChatStore.privacyURL) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.PrivacyTip, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("  Privacy Policy", fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "You can turn this off any time in Settings under Privacy, or under About AI Chat in the chat menu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Theme.inkSoft,
                )
            }
            Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppButton("Agree") {
                    onAgree()
                    onClose()
                }
                TextButton(
                    onClick = onClose,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) { Text("Not Now", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** How AI chat works, the privacy policy, and the switch to stop it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatAIInfoSheet(slime: Slime, onClose: () -> Unit) {
    val chat = LocalChatStore.current
    val consent by chat.aiConsent.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmTurnOff by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Theme.backdrop) {
        Column(
            Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                "About AI Chat",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Theme.ink,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .semantics { heading() },
            )
            GroupedSection(header = "How it works") {
                ChatDisclosure.points(slime.name).forEachIndexed { index, point ->
                    if (index > 0) GroupedDivider()
                    InfoRow(point.icon, point.title, point.text)
                }
            }
            GroupedSection(
                header = "Your choice",
                footer = if (consent) {
                    "Turning it off stops sending messages to the AI service. You'll be asked again before your next message."
                } else {
                    "You'll be asked before your next message is sent."
                },
            ) {
                GroupedRow(
                    if (consent) "AI chat is on" else "AI chat is off",
                    icon = {
                        Icon(
                            if (consent) Icons.Filled.CheckCircle else Icons.Filled.PauseCircle,
                            contentDescription = null,
                            tint = if (consent) Theme.accent else Theme.inkSoft,
                        )
                    },
                )
                if (consent) {
                    GroupedDivider()
                    GroupedRow("Turn Off AI Chat", titleColor = Theme.danger, onClick = { confirmTurnOff = true })
                }
            }
            GroupedSection(
                footer = "Reporting a reply sends only its text to the Slimy team, so we can make the slime's answers better.",
            ) {
                GroupedRow(
                    "Privacy Policy",
                    titleColor = Theme.accent,
                    icon = { IconTile(Icons.Filled.PrivacyTip) },
                    onClick = { openLink(context, ChatStore.privacyURL) },
                )
            }
        }
    }
    if (confirmTurnOff) {
        ConfirmDialog(
            title = "Turn off AI chat?",
            message = "Your chat stays on this phone. Nothing more is sent until you agree again.",
            confirm = "Turn Off",
            onConfirm = { chat.revokeConsent() },
            onDismiss = { confirmTurnOff = false },
        )
    }
}
