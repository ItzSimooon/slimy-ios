package de.hyprnode.slimy.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.art.LiveSlimeView
import de.hyprnode.slimy.core.Feedback
import de.hyprnode.slimy.core.LocalChatStore
import de.hyprnode.slimy.core.LocalFriendsStore
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.core.Toast
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.model.timeIntervalSince
import de.hyprnode.slimy.model.trimmedSwift
import de.hyprnode.slimy.online.ChatMessage
import de.hyprnode.slimy.ui.kit.Theme
import de.hyprnode.slimy.ui.screens.friends.ConfirmDialog
import de.hyprnode.slimy.ui.screens.friends.FullScreenSheet
import de.hyprnode.slimy.ui.screens.friends.GoOnlineScreen
import de.hyprnode.slimy.ui.screens.friends.ToastOverlay
import kotlinx.coroutines.launch

private enum class ChatSheet { consent, goOnline, aiInfo }

/** What waits for the consent sheet: a new message or a Try Again. */
private sealed class PendingSend {
    data class message(val text: String, val fromDraft: Boolean) : PendingSend()
    data class retry(val note: ChatMessage) : PendingSend()
}

/**
 * Talking to the slime, titled with its name (App/UI/Screens/ChatView.swift,
 * ChatConsentView.swift). Replies are written by an AI service through the
 * Slimy server, so the chat only sends once the player went online and
 * agreed to that in the consent sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onClose: () -> Unit) {
    val chat = LocalChatStore.current
    val store = LocalGameStore.current
    val friends = LocalFriendsStore.current
    val state by store.state.collectAsStateWithLifecycle()
    val messages by chat.messages.collectAsStateWithLifecycle()
    val thinking by chat.isThinking.collectAsStateWithLifecycle()
    val storedDraft by chat.draft.collectAsStateWithLifecycle()
    val isOnline by friends.isOnline.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    val inputFocus = remember { FocusRequester() }

    var sheet by remember { mutableStateOf<ChatSheet?>(null) }
    var pending by remember { mutableStateOf<PendingSend?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<Toast?>(null) }
    var menu by remember { mutableStateOf(false) }
    // The field's own copy, so typing never waits for the store.
    var draft by remember { mutableStateOf(chat.draft.value) }
    LaunchedEffect(storedDraft) { if (storedDraft != draft) draft = storedDraft }

    val slime = state.slime
    // Sending is possible apart from consent, which the send itself asks for.
    val canSend = isOnline && !thinking

    fun submit(text: String, fromDraft: Boolean) {
        val trimmed = text.trimmedSwift()
        if (trimmed.isEmpty() || !canSend) return
        if (!chat.aiConsent.value) {
            // Nothing leaves the phone before the player agreed.
            pending = PendingSend.message(trimmed, fromDraft)
            focus.clearFocus()
            sheet = ChatSheet.consent
            return
        }
        if (fromDraft) {
            draft = ""
            chat.draft.value = ""
        }
        Feedback.shared.play(Sound.tap)
        chat.scope.launch {
            val sent = chat.send(trimmed)
            // It couldn't go out after all: give the text back.
            if (!sent && fromDraft && chat.draft.value.isEmpty()) chat.draft.value = trimmed
        }
    }

    fun retry(note: ChatMessage) {
        if (!canSend) return
        if (!chat.aiConsent.value) {
            pending = PendingSend.retry(note)
            sheet = ChatSheet.consent
            return
        }
        Feedback.shared.play(Sound.tap)
        chat.scope.launch { chat.retry(note) }
    }

    fun closeSheet() {
        val closed = sheet
        sheet = null
        if (closed != ChatSheet.consent) return
        val waiting = pending ?: return
        pending = null
        if (!chat.aiConsent.value) return
        when (waiting) {
            is PendingSend.message -> submit(waiting.text, waiting.fromDraft)
            is PendingSend.retry -> retry(waiting.note)
        }
    }

    fun copy(message: ChatMessage) {
        clipboard.setText(AnnotatedString(message.text))
        toast = Toast("Copied", good = true)
    }

    fun report(message: ChatMessage) {
        if (message.reported) return
        if (!friends.isOnline.value) {
            toast = Toast("Go online to report a reply.", good = false)
            return
        }
        chat.scope.launch {
            friends.lastError.value = null
            toast = if (chat.report(message)) {
                Toast("Thanks. We'll look at this reply.", good = true)
            } else {
                // For example the daily report limit, in the server's words.
                val reason = friends.lastError.value ?: "Couldn't send the report. Try again later."
                friends.lastError.value = null
                Toast(reason, good = false)
            }
        }
    }

    // Back to the chat once going online worked.
    LaunchedEffect(isOnline) { if (isOnline && sheet == ChatSheet.goOnline) sheet = null }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Theme.backdrop,
            topBar = {
                TopAppBar(
                    title = {
                        Column(Modifier.semantics(mergeDescendants = true) { heading() }) {
                            Text(
                                slime.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                when {
                                    !isOnline -> "Offline"
                                    thinking -> "Typing…"
                                    else -> ChatMood.status(slime)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (thinking) Theme.accent else Theme.inkSoft,
                                maxLines = 1,
                            )
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Chat options")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("About AI Chat") },
                                    leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                                    onClick = {
                                        menu = false
                                        sheet = ChatSheet.aiInfo
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Clear Chat", color = if (messages.isEmpty()) Theme.inkSoft else Theme.danger) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = null,
                                            tint = if (messages.isEmpty()) Theme.inkSoft else Theme.danger,
                                        )
                                    },
                                    enabled = messages.isNotEmpty(),
                                    onClick = {
                                        menu = false
                                        confirmClear = true
                                    },
                                )
                            }
                        }
                        TextButton(onClick = onClose) { Text("Done", fontWeight = FontWeight.SemiBold) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Theme.backdrop),
                )
            },
            bottomBar = {
                if (isOnline) {
                    ChatInputBar(
                        text = draft,
                        onTextChange = {
                            draft = it
                            chat.draft.value = it
                        },
                        canSend = canSend,
                        focusRequester = inputFocus,
                        onSend = { submit(draft, fromDraft = true) },
                    )
                } else {
                    ChatOfflineCard { sheet = ChatSheet.goOnline }
                }
            },
        ) { padding ->
            if (messages.isEmpty() && !thinking) {
                EmptyChat(padding, slime.name, isOnline, canSend, onPick = { submit(it, fromDraft = false) }) {
                    sheet = ChatSheet.aiInfo
                }
            } else {
                MessageList(
                    padding = padding,
                    messages = messages,
                    thinking = thinking,
                    canSend = canSend,
                    slime = slime,
                    onInfo = { sheet = ChatSheet.aiInfo },
                    onRetry = { retry(it) },
                    onCopy = { copy(it) },
                    onReport = { report(it) },
                    context = context,
                )
            }
        }
        ToastOverlay(toast, onDismiss = { if (toast == it) toast = null }, top = true, seconds = 2)
    }

    when (sheet) {
        ChatSheet.consent -> ChatConsentSheet(slime, onAgree = { chat.aiConsent.value = true }, onClose = { closeSheet() })
        ChatSheet.aiInfo -> ChatAIInfoSheet(slime, onClose = { closeSheet() })
        ChatSheet.goOnline -> FullScreenSheet(onDismiss = { closeSheet() }) { GoOnlineScreen(onDone = { closeSheet() }) }
        null -> {}
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "Clear this chat?",
            message = "The conversation is deleted from this phone. This can't be undone.",
            confirm = "Clear Chat",
            onConfirm = { chat.clear() },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun EmptyChat(
    padding: PaddingValues,
    name: String,
    isOnline: Boolean,
    canSend: Boolean,
    onPick: (String) -> Unit,
    onInfo: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
    ) {
        LiveSlimeView(height = 96.dp, interactive = false)
        Text(
            "hi! it's me, $name.\nlet's talk.",
            style = Theme.voice(22.sp),
            color = Theme.ink,
            textAlign = TextAlign.Center,
        )
        Text(
            if (isOnline) {
                "Ask how it feels, what it ate or how its day is going."
            } else {
                "Once you're online, you can ask how it feels, what it ate or how its day is going."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Theme.inkSoft,
            textAlign = TextAlign.Center,
        )
        if (isOnline) {
            Box(Modifier.padding(top = 4.dp)) { ChatSuggestions(enabled = canSend, onPick = onPick) }
        }
        ChatAINotice(onInfo)
    }
}

@Composable
private fun MessageList(
    padding: PaddingValues,
    messages: List<ChatMessage>,
    thinking: Boolean,
    canSend: Boolean,
    slime: de.hyprnode.slimy.model.Slime,
    onInfo: () -> Unit,
    onRetry: (ChatMessage) -> Unit,
    onCopy: (ChatMessage) -> Unit,
    onReport: (ChatMessage) -> Unit,
    context: android.content.Context,
) {
    val list = rememberLazyListState()
    val count = 1 + messages.size + if (thinking) 1 else 0
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(messages.lastOrNull()?.id, thinking) {
        if (placed) list.animateScrollToItem(count - 1) else list.scrollToItem(count - 1)
        placed = true
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = list,
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = padding.calculateTopPadding() + 4.dp,
            bottom = padding.calculateBottomPadding() + 12.dp,
        ),
    ) {
        item(key = "notice") {
            Box(Modifier.padding(bottom = 4.dp)) { ChatAINotice(onInfo) }
        }
        itemsIndexed(messages, key = { _, m -> m.id.toString() }) { index, message ->
            val previous = messages.getOrNull(index - 1)
            val next = messages.getOrNull(index + 1)
            // Tight between messages from the same sender, roomier between turns.
            val gap = when {
                previous == null -> 0.dp
                previous.role == message.role && message.role != ChatMessage.Role.note -> 3.dp
                else -> 12.dp
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = gap)
                    .animateItem(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // A time above the first message and after a pause of an hour or more.
                val stamp = if (previous == null || message.date.timeIntervalSince(previous.date) >= 3600) {
                    ChatTimestamp.text(context, message.date)
                } else {
                    null
                }
                if (stamp != null) {
                    Text(
                        stamp,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Theme.inkSoft,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (previous == null) 0.dp else 8.dp),
                    )
                }
                when (message.role) {
                    ChatMessage.Role.note -> ChatNoteRow(message, canRetry = canSend) { onRetry(message) }
                    else -> ChatBubbleRow(
                        message = message,
                        slime = slime,
                        endsRun = next?.role != message.role,
                        onCopy = { onCopy(message) },
                        onReport = { onReport(message) },
                    )
                }
            }
        }
        if (thinking) {
            item(key = "typing") {
                Box(
                    Modifier
                        .padding(top = 10.dp)
                        .sizeIn(minHeight = 1.dp)
                        .animateItem(),
                ) { ChatTypingIndicator(slime) }
            }
        }
    }
}
