package de.hyprnode.slimy.ui.screens.about

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.EmojiPeople
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.hyprnode.slimy.art.LiveSlimeView
import de.hyprnode.slimy.ui.kit.AppButton
import de.hyprnode.slimy.ui.kit.SheetScaffold
import de.hyprnode.slimy.ui.kit.Theme

/**
 * The highlights of a release, shown once to people who had Slimy before it
 * (AppRoot presents it; someone who just installed sees onboarding instead).
 * For a release worth telling about, set [version] to its marketing version
 * without the patch number and replace the items (App/UI/Screens/WhatsNewView.swift).
 */
object WhatsNew {
    data class Item(val icon: ImageVector, val title: String, val detail: String)

    const val version = "1.1"

    val items: List<Item> = listOf(
        Item(Icons.Filled.Inventory2, "Crates with a secret",
            "Earn crates or buy them with coins, then tap them open. The drop rates are listed in the app, and one legendary reward is a secret."),
        Item(Icons.Filled.People, "Care Together",
            "Share your slime with one other person. Every meal, game, bath and cuddle shows up on both phones."),
        Item(Icons.Filled.ChatBubble, "A chattier slime",
            "It says what's on its mind every few seconds while you watch. You can turn this off in Settings."),
        Item(Icons.Filled.Mood, "Real feelings",
            "Your slime can be cheerful, curious, grumpy, sleepy and more. You see it in its mood, in how it moves and in what it says."),
        Item(Icons.Filled.Forum, "Chats with feeling",
            "When you talk with your slime, it answers in its mood, and your words can cheer it up."),
        Item(Icons.Filled.EmojiPeople, "Friends, simpler",
            "The love meter and kisses are gone. Hugs, visits, gifts, greetings, challenges and the shared garden stay."),
    )

    private const val seenKey = "slimy.whatsNew.seen"

    /** True when this build's notes haven't been shown yet. Patch releases (1.1.1) share the notes of their release (1.1). */
    fun isDue(context: Context, appVersion: String): Boolean =
        isDue(appVersion, prefs(context).getString(seenKey, null))

    fun isDue(appVersion: String, seen: String?): Boolean =
        AppVersion.marketing(appVersion) == version && seen != version

    fun markSeen(context: Context) {
        prefs(context).edit().putString(seenKey, version).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("slimy", Context.MODE_PRIVATE)
}

/** What's New. */
@Composable
fun WhatsNewScreen(onClose: () -> Unit) {
    SheetScaffold(title = "What's New", onDone = onClose) { padding ->
        // The Continue bar brings its own bottom inset.
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(top = 4.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiveSlimeView(height = 96.dp, interactive = false, modifier = Modifier.clearAndSetSemantics { })
                    Text("Slimy ${WhatsNew.version}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Theme.inkSoft)
                }
                Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    for (item in WhatsNew.items) ItemRow(item)
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.navigationBarsPadding()) {
                    HorizontalDivider(color = Theme.separator)
                    AppButton(
                        title = "Continue",
                        modifier = Modifier
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                            .widthIn(max = 560.dp),
                        action = onClose,
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: WhatsNew.Item) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(item.icon, contentDescription = null, tint = Theme.accent, modifier = Modifier.width(40.dp).size(30.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Theme.ink)
            Text(item.detail, style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft)
        }
    }
}
