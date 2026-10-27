package de.hyprnode.slimy.ui.screens.help

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.hyprnode.slimy.online.FriendsStore
import de.hyprnode.slimy.ui.kit.GroupedDivider
import de.hyprnode.slimy.ui.kit.GroupedRow
import de.hyprnode.slimy.ui.kit.GroupedSection
import de.hyprnode.slimy.ui.kit.Theme
import de.hyprnode.slimy.ui.screens.common.PushedScaffold
import de.hyprnode.slimy.ui.screens.common.ScreenColumn
import de.hyprnode.slimy.ui.screens.common.openLink

/**
 * Answers to common questions, the same as on slime.hyprnode.de/support,
 * told for Android (App/UI/Screens/HelpView.swift).
 */
@Composable
fun HelpScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    val shown = HelpTopic.filtered(HelpTopic.all, query)

    PushedScaffold(title = "Help", onBack = onBack) { padding ->
        ScreenColumn(padding, spacing = 20.dp) {
            TextField(
                value = query,
                onValueChange = { text ->
                    query = text
                    // Open every answer that matches, so the result is visible at once.
                    if (text.isNotBlank()) expanded = HelpTopic.filtered(HelpTopic.all, text).flatMap { t -> t.items.map { it.id } }.toSet()
                },
                placeholder = { Text("Search help") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
                    }
                } else {
                    null
                },
                singleLine = true,
                shape = RoundedCornerShape(Theme.smallRadius),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Theme.well,
                    unfocusedContainerColor = Theme.well,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (shown.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = Theme.inkSoft, modifier = Modifier.size(44.dp))
                    Text("No results for “${query.trim()}”", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, color = Theme.ink, textAlign = TextAlign.Center)
                    Text("Check the spelling or try a new search.", style = MaterialTheme.typography.bodyMedium,
                        color = Theme.inkSoft, textAlign = TextAlign.Center)
                }
            } else {
                for (topic in shown) {
                    GroupedSection(header = topic.title) {
                        topic.items.forEachIndexed { index, item ->
                            if (index > 0) GroupedDivider()
                            val open = expanded.contains(item.id)
                            HelpRow(item, open) { expanded = if (open) expanded - item.id else expanded + item.id }
                        }
                    }
                }
                GroupedSection(footer = "More help and ways to reach us are on slime.hyprnode.de/support.") {
                    GroupedRow(
                        title = "Contact Support",
                        titleColor = Theme.accent,
                        icon = { Icon(Icons.Filled.Email, contentDescription = null, tint = Theme.accent) },
                        trailing = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = Theme.inkSoft, modifier = Modifier.size(18.dp)) },
                        onClickLabel = "Open in your browser",
                        onClick = { openLink(context, FriendsStore.supportURL) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HelpRow(item: HelpItem, open: Boolean, toggle: () -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClickLabel = if (open) "Hide the answer" else "Show the answer", onClick = toggle)
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(item.question, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = Theme.ink,
                modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = Theme.accent)
        }
        AnimatedVisibility(visible = open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            SelectionContainer {
                Text(
                    item.answer,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Theme.inkSoft,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                )
            }
        }
    }
}

data class HelpItem(val id: String, val question: String, val answer: String)

data class HelpTopic(val id: String, val title: String, val items: List<HelpItem>) {
    companion object {
        /** Topics whose questions or answers contain [query]; everything when it's empty. */
        fun filtered(topics: List<HelpTopic>, query: String): List<HelpTopic> {
            val text = query.trim()
            if (text.isEmpty()) return topics
            return topics.mapNotNull { topic ->
                val items = topic.items.filter { it.question.contains(text, ignoreCase = true) || it.answer.contains(text, ignoreCase = true) }
                if (items.isEmpty()) null else topic.copy(items = items)
            }
        }

        private val island = HelpTopic("island", "Slime notification", listOf(
            HelpItem(
                "island.gone",
                "My slime disappeared from my notifications.",
                "Android removes the slime notification when you swipe it away, when notifications are turned off for Slimy, or after the phone restarts. Open Slimy and your slime comes right back. Without opening the app, touch and hold the Slimy icon on your home screen and choose Summon Slime. If it still doesn't appear, check that notifications are on for Slimy in the Settings app, and that Always bring it back is on in Slimy's Settings.",
            ),
            HelpItem(
                "island.summon",
                "How do I add Summon Slime to my home screen?",
                "Touch and hold the Slimy icon on your home screen. Summon Slime is in the list that opens: tap it to bring your slime back, or drag it onto your home screen to keep it one tap away.",
            ),
            HelpItem(
                "island.still",
                "The slime in the notification doesn't move.",
                "Check that Animation is on in Slimy's Settings. With Animation off, the notification shows a still picture that changes with your slime's mood.",
            ),
            HelpItem(
                "island.late",
                "Why does my slime react late to my phone?",
                "While Slimy is open it reacts right away. When it's closed, it notices changes the next time Android lets it run, for example now and then in the background or while charging, so a reaction can come a little later. If battery use for Slimy is restricted in the Settings app, it mostly reacts while the app is open.",
            ),
        ))

        private val slime = HelpTopic("slime", "Your slime", listOf(
            HelpItem(
                "slime.feelings",
                "Does my slime have feelings?",
                "Yes. It can feel joy, sadness, anger, fear, love, curiosity, boredom, excitement and sleepiness, some more strongly than others. Meals, games, baths, cuddles, friends and chats change how it feels, and its feelings settle again over time. Its mood shows under its name on the home screen, in how it moves and in what it says.",
            ),
            HelpItem(
                "slime.talking",
                "Why does my slime keep talking?",
                "While Slimy is open, your slime says what's on its mind every few seconds: how it feels, what it needs, the time of day, the weather or what just happened. It never repeats itself straight away. For a quieter slime, turn off Talks on its own in Settings.",
            ),
        ))

        private val crates = HelpTopic("crates", "Crates", listOf(
            HelpItem(
                "crates.get",
                "How do I get crates?",
                "Buy them with coins: a wooden crate costs 80 coins, a golden crate 300. Mystery crates can't be bought: one comes with the gift on day 7 of a streak, with every fifth level, with some achievements and, now and then, after a great round in the arcade. Tap a crate a few times to open it.",
            ),
            HelpItem(
                "crates.inside",
                "What's inside a crate?",
                "Coins, food, hats and other things to wear, furniture, new colours for your slime, or even another crate. Rewards are common, rare, epic or legendary, and one legendary reward is a secret. If you get something you already have, it turns into coins instead.",
            ),
            HelpItem(
                "crates.odds",
                "What are the chances of each reward?",
                "Tap Drop rates in the shop or next to your crates to see the chances for every kind of crate. Wooden crates never hold a legendary reward; golden and mystery crates can. Which legendary reward that is stays a secret until you find it.",
            ),
            HelpItem(
                "crates.money",
                "Do crates cost real money?",
                "No. Crates are earned in the game or bought with coins, and coins can't be bought. Slimy has no in-app purchases.",
            ),
        ))

        private val care = HelpTopic("care", "Care Together", listOf(
            HelpItem(
                "care.how",
                "How can two people look after one slime?",
                "Open Care Together in Friends or Settings and go online. One of you shares a code, the other enters it within 10 minutes. From then on both phones show the same slime, and when either of you feeds it, plays with it, gives it a bath, pets it, puts it to bed, wakes it up or gives it a gift, it happens on the other phone too. You can see who did what and when.",
            ),
            HelpItem(
                "care.own",
                "What happens to my own slime when I join?",
                "It's kept safe on your phone while you look after your partner's slime, and it comes back when you stop caring together. If you shared the code, the slime stays yours when you stop.",
            ),
            HelpItem(
                "care.personal",
                "What stays my own?",
                "Your coins, the things you bought, your records, your challenges and your settings. Only the slime is shared. You can care for one slime together with one person at a time.",
            ),
            HelpItem(
                "care.stop",
                "How do we stop caring together?",
                "Either of you can tap Stop Sharing in Care Together at any time, and the other phone stops sharing too. Whoever joined gets their own slime back; the slime you shared stays with the person who shared the code.",
            ),
        ))

        private val widgets = HelpTopic("install", "Widgets", listOf(
            HelpItem(
                "install.widgets",
                "My widgets stay empty.",
                "Open Slimy once so the widgets get your slime, then give them a moment. Android decides how often widgets refresh.",
            ),
        ))

        private val friends = HelpTopic("friends", "Friends", listOf(
            HelpItem(
                "friends.add",
                "How do I add a friend?",
                "Open Friends and go online. Then share your code, or enter your friend's code. A code works once and expires after 10 minutes, so if one doesn't work, ask for a new one.",
            ),
            HelpItem(
                "friends.together",
                "What can our slimes do together?",
                "Send hugs and gifts, say hello, visit each other, challenge each other to a game, grow your shared garden and compare scores on the leaderboard. To look after one slime together, use Care Together.",
            ),
            HelpItem(
                "friends.strangers",
                "Can strangers find me?",
                "No. There's no search and no public profile. Only people you give a code to can send you a friend request, and you decide whether to accept it.",
            ),
            HelpItem(
                "friends.report",
                "How do I report or block someone?",
                "In Friends, tap a friend and choose Report or Block at the bottom of their page, or touch and hold them in the list. For a friend request, tap the three dots next to it. Blocking ends the friendship right away, nothing from that device reaches you anymore, and they aren't notified. You can unblock them later under Privacy & blocked in Friends. A report goes to the Slimy team for review and doesn't block anyone by itself, so block them too if you don't want to hear from them again. To report something your slime said in chat, touch and hold the reply.",
            ),
        ))

        private val chat = HelpTopic("chat", "Talking with your slime", listOf(
            HelpItem(
                "chat.data",
                "What happens to my chat messages?",
                "Nothing is sent until you agree in the chat before your first message. Then your message, the recent chat and a few facts about your slime, like its name, how it feels and its needs, go to Cloudflare Workers AI and Groq, which write the reply. Your name and device ID are never passed on to them, and slime.hyprnode.de doesn't keep the messages. The chat itself stays on this phone. You can turn Talk with AI off any time in Settings under Privacy, or under About AI Chat in the chat menu.",
            ),
            HelpItem(
                "chat.mood",
                "Does the chat change how my slime feels?",
                "It can. Your slime answers in its current mood, and what you write can cheer it up, calm it down, make it shy or get it excited, just like caring for it does. Its new mood shows on the home screen.",
            ),
            HelpItem(
                "chat.sleepy",
                "My slime is too sleepy to talk.",
                "Talking uses a limited amount of AI each day. When it runs out, or the AI services are busy, your slime gets sleepy. Try again later.",
            ),
        ))

        private val data = HelpTopic("data", "Your data", listOf(
            HelpItem(
                "data.delete",
                "How do I delete my data?",
                "Your slime, coins and things live on this phone. If you went online, slime.hyprnode.de stores your online profile, your friends and what they sent you, and while you use Care Together, the shared slime and what you both did for it. To delete that, go to Settings, then Privacy, and tap Delete My Data on the Server. You'll find the same button in Friends under Privacy & blocked. To remove your slime from this phone, use Start over in Settings.",
            ),
            HelpItem(
                "data.move",
                "How do I move my slime to a new phone?",
                "In Settings under Backup, tap Export Save and keep the file in your files or send it to the new phone. After hatching on the new phone, open Settings and tap Import Save; your old slime replaces the new one. Saves work on iPhone and Android alike. Friends aren't part of a save, so add them again after going online.",
            ),
            HelpItem(
                "data.cost",
                "Does Slimy cost anything?",
                "No. There are no purchases and no ads. Coins are earned in the app, by playing and from daily gifts, and can't be bought. Crates are earned or bought with coins, never with real money.",
            ),
        ))

        val all: List<HelpTopic> = listOf(island, slime, crates, care, widgets, friends, chat, data)
    }
}
