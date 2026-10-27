package de.hyprnode.slimy.ui.screens.chat

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hyprnode.slimy.art.PixelSlime
import de.hyprnode.slimy.art.SlimeMoodWord
import de.hyprnode.slimy.art.rememberReduceMotion
import de.hyprnode.slimy.core.Feedback
import de.hyprnode.slimy.model.Emotion
import de.hyprnode.slimy.model.Slime
import de.hyprnode.slimy.model.SlimeSprites
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.online.ChatMessage
import de.hyprnode.slimy.online.ChatStore
import de.hyprnode.slimy.ui.kit.AppButton
import de.hyprnode.slimy.ui.kit.Card
import de.hyprnode.slimy.ui.kit.Theme
import de.hyprnode.slimy.ui.kit.TileButton
import java.time.Instant
import java.time.ZoneId
import kotlin.math.sin

// The pieces of the chat screen: bubbles, notes, the typing dots, the input
// bar and the card that asks you to go online first (App/UI/Screens/ChatParts.swift).

/** The slime's face next to its messages: a still pixel portrait in a circle. */
@Composable
internal fun ChatAvatar(slime: Slime, size: Dp = 30.dp, fill: Color = Theme.card) {
    val anim = SpriteAnim.of(slime.mood)
    val frames = SlimeSprites.frames(slime.stage, anim)
    val anchors = SlimeSprites.anchors(slime.stage, anim)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(fill)
            .padding(size * 0.12f)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        PixelSlime(
            frame = frames.firstOrNull() ?: SlimeSprites.blank,
            skin = slime.skin,
            accessories = slime.equipped,
            stage = slime.stage,
            anchor = anchors.firstOrNull(),
        )
    }
}

/**
 * How the chat puts the slime's feelings into words: the header says what the
 * home screen says (`SlimeMoodWord`), and each reply carries the feeling it
 * expressed.
 */
internal object ChatMood {
    /** From this intensity on, a feeling takes its stronger word. */
    private const val strong = 0.55

    /** One feeling as a word that follows "feeling": "content" when mild, "cheerful" when strong. */
    fun word(emotion: Emotion, intensity: Double): String {
        val isStrong = intensity >= strong
        return when (emotion) {
            Emotion.joy -> if (isStrong) "cheerful" else "content"
            Emotion.sadness -> if (isStrong) "sad" else "glum"
            Emotion.anger -> if (isStrong) "cross" else "grumpy"
            Emotion.fear -> if (isStrong) "scared" else "nervous"
            Emotion.love -> if (isStrong) "cuddly" else "fond"
            Emotion.curiosity -> if (isStrong) "fascinated" else "curious"
            Emotion.boredom -> if (isStrong) "bored" else "a bit bored"
            Emotion.excitement -> if (isStrong) "excited" else "eager"
            Emotion.sleepiness -> if (isStrong) "sleepy" else "drowsy"
        }
    }

    /** A small touch of colour next to the word. */
    @Composable
    fun tint(emotion: Emotion): Color = when (emotion) {
        Emotion.joy, Emotion.excitement -> Theme.gold
        Emotion.love -> Theme.love
        Emotion.sadness, Emotion.fear -> Theme.sky
        Emotion.anger -> Theme.danger
        Emotion.curiosity -> Theme.accent
        Emotion.boredom, Emotion.sleepiness -> Theme.inkSoft
    }

    /** The line under the slime's name in the chat header: the same word the home screen shows under its name. */
    fun status(slime: Slime): String = if (slime.isAsleep) "Asleep" else "Feeling ${SlimeMoodWord.word(slime)}"

    /** The word under a reply. Replies saved before the server sent an intensity count as mild. */
    fun word(message: ChatMessage): String? {
        val emotion = message.emotion ?: return null
        return word(emotion, message.intensity ?: 0.5)
    }
}

/** A message bubble. On the last message of a run, the corner nearest the sender is tighter, like a small tail. */
private fun bubbleShape(mine: Boolean, tail: Boolean): RoundedCornerShape = RoundedCornerShape(
    topStart = 20.dp,
    topEnd = 20.dp,
    bottomEnd = if (mine && tail) 6.dp else 20.dp,
    bottomStart = if (!mine && tail) 6.dp else 20.dp,
)

/**
 * One message from you or from the slime. Slime replies sit on the left next
 * to its portrait; yours sit on the right in the accent colour. Touch and
 * hold for Copy (and Report on a reply). Bubbles use the system font so long
 * replies stay easy to read.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatBubbleRow(
    message: ChatMessage,
    slime: Slime,
    endsRun: Boolean,
    onCopy: () -> Unit,
    onReport: () -> Unit,
) {
    val mine = message.role == ChatMessage.Role.me
    var menu by remember { mutableStateOf(false) }
    val avatarSize = with(LocalDensity.current) { (30 * fontScale).coerceAtMost(44f).dp }
    val word = ChatMood.word(message)
    val details = buildList {
        if (word != null) add("Feeling $word")
        if (message.reported) add("Reported")
        if (mine && message.failed) add("Not delivered")
    }.joinToString(", ")
    val actions = buildList {
        add(CustomAccessibilityAction("Copy") { onCopy(); true })
        if (!mine && !message.reported) add(CustomAccessibilityAction("Report reply") { onReport(); true })
    }
    val semantics = Modifier.clearAndSetSemantics {
        contentDescription = if (mine) "You: ${message.text}" else "${slime.name}: ${message.text}"
        if (details.isNotEmpty()) stateDescription = details
        customActions = actions
    }

    val bubble: @Composable () -> Unit = {
        Box {
            Text(
                message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (mine) Theme.onAccent else Theme.ink,
                modifier = Modifier
                    .alpha(if (message.failed) 0.6f else 1f)
                    .clip(bubbleShape(mine, endsRun))
                    .background(if (mine) Theme.accent else Theme.card)
                    .combinedClickable(onClick = {}, onLongClick = { menu = true })
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Copy") },
                    leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                    onClick = {
                        menu = false
                        onCopy()
                    },
                )
                if (!mine) {
                    if (message.reported) {
                        DropdownMenuItem(
                            text = { Text("Reported") },
                            leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null) },
                            enabled = false,
                            onClick = {},
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Report Reply") },
                            leadingIcon = { Icon(Icons.Filled.Report, contentDescription = null) },
                            onClick = {
                                menu = false
                                onReport()
                            },
                        )
                    }
                }
            }
        }
    }

    if (mine) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(semantics),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Bottom,
        ) {
            Spacer(Modifier.widthIn(min = 52.dp))
            if (message.failed) {
                Icon(
                    Icons.Filled.Error,
                    contentDescription = null,
                    tint = Theme.danger,
                    modifier = Modifier
                        .padding(end = 6.dp, bottom = 8.dp)
                        .size(20.dp),
                )
            }
            Box(Modifier.weight(1f, fill = false)) { bubble() }
        }
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .then(semantics),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (endsRun) ChatAvatar(slime, avatarSize) else Spacer(Modifier.size(avatarSize, 1.dp))
                    Box(Modifier.weight(1f, fill = false)) { bubble() }
                }
                if (message.emotion != null || message.reported) {
                    Row(
                        Modifier.padding(start = avatarSize + 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val emotion = message.emotion
                        if (emotion != null && word != null) ChatMoodLabel(emotion, word)
                        if (message.reported) {
                            Text("Reported", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Theme.inkSoft)
                        }
                    }
                }
            }
            Spacer(Modifier.widthIn(min = 44.dp))
        }
    }
}

/** The feeling a reply expressed, quietly under its bubble: one pixel in the feeling's colour and its word. */
@Composable
private fun ChatMoodLabel(emotion: Emotion, word: String) {
    val pixel = with(LocalDensity.current) { (6 * fontScale).dp }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            Modifier
                .size(pixel)
                .background(ChatMood.tint(emotion)),
        )
        Text(word, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Theme.inkSoft)
    }
}

/** A line from the app between the bubbles, for example an error with a Try Again button. */
@Composable
internal fun ChatNoteRow(message: ChatMessage, canRetry: Boolean, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (message.failed) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Theme.danger, modifier = Modifier.size(16.dp))
            }
            Text(
                message.text,
                style = MaterialTheme.typography.bodySmall,
                color = Theme.inkSoft,
                textAlign = TextAlign.Center,
            )
        }
        if (message.retryID != null) {
            Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .padding(vertical = 7.dp)
                    .clip(CircleShape)
                    .background(Theme.accent.copy(alpha = if (canRetry) 0.14f else 0.06f))
                    .clickable(enabled = canRetry, role = Role.Button, onClick = onRetry)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val color = if (canRetry) Theme.accent else Theme.inkSoft
                Icon(Icons.Filled.Refresh, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                Text("Try Again", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = color)
            }
        }
    }
}

/** Three dots in a slime bubble while the reply is on its way. With reduced motion the dots hold still. */
@Composable
internal fun ChatTypingIndicator(slime: Slime) {
    val reduceMotion = rememberReduceMotion()
    val avatarSize = with(LocalDensity.current) { (30 * fontScale).coerceAtMost(44f).dp }
    val dot = with(LocalDensity.current) { (8 * fontScale).coerceAtMost(12f).dp }
    val transition = rememberInfiniteTransition(label = "typing")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1050, easing = LinearEasing), RepeatMode.Restart),
        label = "typingPhase",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "${slime.name} is typing" },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChatAvatar(slime, avatarSize)
        Row(
            Modifier
                .clip(bubbleShape(mine = false, tail = true))
                .background(Theme.card)
                .padding(horizontal = 16.dp, vertical = 15.dp),
            horizontalArrangement = Arrangement.spacedBy(dot * 0.6f),
        ) {
            val soft = Theme.inkSoft
            for (i in 0 until 3) {
                val wave = if (reduceMotion) 0.5f else ((sin(t - i * 0.9f) + 1f) / 2f)
                Box(
                    Modifier
                        .offset(y = if (reduceMotion) 0.dp else (-3 * wave).dp)
                        .size(dot)
                        .alpha(0.35f + 0.65f * wave)
                        .background(soft, CircleShape),
                )
            }
        }
    }
}

/** The field and the send button, pinned to the bottom of the chat. */
@Composable
internal fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    canSend: Boolean,
    focusRequester: FocusRequester,
    onSend: () -> Unit,
) {
    val hasText = text.isNotBlank()
    val enabled = canSend && hasText
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
            HorizontalDivider(thickness = 0.5.dp, color = Theme.separator)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextField(
                    value = text,
                    onValueChange = { onTextChange(it.take(ChatStore.maxLength)) },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    placeholder = { Text("Message") },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Theme.well,
                        unfocusedContainerColor = Theme.well,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
                IconButton(
                    onClick = onSend,
                    enabled = enabled,
                    modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                    ),
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(if (enabled) Theme.accent else Theme.well, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.ArrowUpward,
                            contentDescription = "Send",
                            tint = if (enabled) Theme.onAccent else Theme.inkSoft,
                        )
                    }
                }
            }
            if (text.length > ChatStore.maxLength - 60) {
                Text(
                    "${text.length} of ${ChatStore.maxLength}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (text.length >= ChatStore.maxLength) Theme.danger else Theme.inkSoft,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 64.dp, bottom = 4.dp),
                )
            }
        }
    }
}

/** Instead of the input bar while the app is offline: talking needs the server, so it asks you to go online first. */
@Composable
internal fun ChatOfflineCard(onGoOnline: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            HorizontalDivider(thickness = 0.5.dp, color = Theme.separator)
            Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fill = Theme.backdrop) {
                Row(
                    Modifier.semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Filled.CellTower, contentDescription = null, tint = Theme.accent, modifier = Modifier.size(28.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Go online to talk", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Theme.ink)
                        Text(
                            "An AI service writes your slime's replies through Slimy's server, so talking works only while you're online.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Theme.inkSoft,
                        )
                    }
                }
                AppButton("Go Online", systemIcon = Icons.Filled.CellTower, action = onGoOnline)
            }
        }
    }
}

/** Things to say when the chat is empty. Tapping one sends it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatSuggestions(enabled: Boolean, onPick: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (suggestion in suggestions) {
            TileButton(
                onClick = {
                    Feedback.shared.play(Sound.tap)
                    onPick(suggestion)
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "$suggestion. Sends this message." },
                radius = 24.dp,
                enabled = enabled,
            ) {
                Text(
                    suggestion,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Theme.accent,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** Says that the replies are AI-written; tapping it explains more. */
@Composable
internal fun ChatAINotice(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = "Explains how AI chat works", role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = Theme.inkSoft, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(6.dp))
        Text(
            "Replies are written by AI and can be wrong.",
            style = MaterialTheme.typography.bodySmall,
            color = Theme.inkSoft,
            textAlign = TextAlign.Center,
        )
    }
}

internal val suggestions = listOf("How are you?", "What did you eat?", "Tell me a secret")

internal object ChatTimestamp {
    /** "Today 14:32", "Yesterday 09:10", or the date for older messages. */
    fun text(context: Context, date: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val day = date.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        val time = DateUtils.formatDateTime(context, date.toEpochMilli(), DateUtils.FORMAT_SHOW_TIME)
        if (day == today) return "Today $time"
        if (day == today.minusDays(1)) return "Yesterday $time"
        return DateUtils.formatDateTime(
            context, date.toEpochMilli(),
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or
                DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_SHOW_TIME,
        )
    }
}
