package de.hyprnode.slimy.ui.screens.kitchen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.art.PixelItem
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.model.Food
import de.hyprnode.slimy.model.GameState
import de.hyprnode.slimy.model.Need
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.model.feed
import de.hyprnode.slimy.model.line
import de.hyprnode.slimy.model.servings
import de.hyprnode.slimy.ui.kit.Card
import de.hyprnode.slimy.ui.kit.CoinCounter
import de.hyprnode.slimy.ui.kit.ItemIcon
import de.hyprnode.slimy.ui.kit.Meter
import de.hyprnode.slimy.ui.kit.SheetScaffold
import de.hyprnode.slimy.ui.kit.Tag
import de.hyprnode.slimy.ui.kit.Theme
import de.hyprnode.slimy.ui.kit.TileButton
import de.hyprnode.slimy.ui.screens.common.AdaptiveGrid
import de.hyprnode.slimy.ui.screens.common.ScreenColumn
import de.hyprnode.slimy.ui.screens.common.grayscale
import de.hyprnode.slimy.ui.screens.common.rememberAnnouncer
import de.hyprnode.slimy.ui.screens.shop.SlimeShowcase
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Feeding: every food, what it costs and how much it fills. Tapping a food
 * feeds it right away and the screen stays open for another bite. Free
 * servings from crates are used before any coins (App/UI/Screens/KitchenView.swift).
 */
@Composable
fun KitchenScreen(onClose: () -> Unit) {
    val store = LocalGameStore.current
    val s by store.state.collectAsStateWithLifecycle()
    val announce = rememberAnnouncer()
    var line by remember { mutableStateOf<String?>(null) }
    var lineID by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { store.tick() }
    LaunchedEffect(lineID) {
        if (line == null) return@LaunchedEffect
        delay(4_000)
        line = null
    }

    fun feed(food: Food) {
        // GameStore plays the eating sound and its haptic, or a soft "nope".
        // The line shows in the speech bubble above instead of the app-wide
        // toast, which would cover the title bar, so TalkBack hears it from here.
        var said = ""
        store.apply(sound = Sound.eat) { state ->
            val result = state.feed(food, Instant.now())
            said = result.line
            result.copy(line = "")
        }
        if (said.isEmpty()) return
        line = said
        lineID += 1
        announce(said)
    }

    SheetScaffold(
        title = "Kitchen",
        onDone = onClose,
        actions = { CoinCounter(coins = s.wallet.coins, inToolbar = true) },
    ) { padding ->
        ScreenColumn(padding, spacing = 16.dp) {
            KitchenHeader(s, line)
            AdaptiveGrid(Food.allCases, minCellWidth = 104.dp) { food ->
                val servings = s.servings(food)
                FoodTile(
                    food = food,
                    locked = servings == 0 && s.slime.level < food.unlockLevel,
                    affordable = servings > 0 || s.wallet.coins >= food.price,
                    tasted = s.inventory.foodsTasted.contains(food),
                    favorite = s.inventory.favoriteFound && food == s.slime.favorite,
                    servings = servings,
                ) { feed(food) }
            }
            KitchenFooter(s)
        }
    }
}

@Composable
private fun KitchenHeader(s: GameState, line: String?) {
    val hunger = s.slime.needs.hunger
    val full = hunger >= 97
    val percent = hunger.roundToInt()
    Card {
        SlimeShowcase(
            habitat = s.inventory.habitat,
            furniture = s.inventory.furniture,
            line = line ?: s.line(Instant.now()),
            height = 168.dp,
            slimeHeight = 78.dp,
        )
        Column(
            Modifier.clearAndSetSemantics {
                contentDescription = Need.hunger.title
                stateDescription = if (full) "$percent percent, full" else "$percent percent"
            },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelItem(Theme.icon(Need.hunger), Modifier.size(18.dp))
                Text(Need.hunger.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Theme.ink)
                if (full) Tag("FULL")
                Spacer(Modifier.weight(1f))
                Text(
                    "$percent%",
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                    color = Theme.inkSoft,
                )
            }
            Meter(value = hunger / 100, color = if (hunger < 25) Theme.danger else Theme.color(Need.hunger), height = 8.dp)
        }
        Row(
            Modifier.clearAndSetSemantics {
                contentDescription = favouriteText(s)
            },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (s.inventory.favoriteFound) {
                PixelItem("heart", Modifier.padding(top = 2.dp).size(width = 18.dp, height = 15.dp))
            } else {
                Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = null, tint = Theme.inkSoft, modifier = Modifier.size(20.dp))
            }
            Text(favouriteText(s), style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft)
        }
    }
}

private fun favouriteText(s: GameState): String = if (s.inventory.favoriteFound) {
    "${s.slime.name} loves ${s.slime.favorite.title.lowercase()}. It fills more and gives extra love."
} else {
    "${s.slime.name} has a favourite food. Try new things to find it."
}

@Composable
private fun KitchenFooter(s: GameState) {
    val tried = s.inventory.foodsTasted.size
    val total = Food.allCases.size
    val hint = KitchenText.footerHint(tried, total, s.slime.name)
    Card(Modifier.clearAndSetSemantics { contentDescription = "Tried $tried of $total. $hint" }) {
        Text("Tried $tried of $total", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Theme.ink)
        Meter(value = tried.toDouble() / maxOf(1, total))
        Text(hint, style = MaterialTheme.typography.bodySmall, color = Theme.inkSoft)
    }
}

@Composable
private fun FoodTile(
    food: Food,
    locked: Boolean,
    affordable: Boolean,
    tasted: Boolean,
    favorite: Boolean,
    /** Free servings from crates. */
    servings: Int,
    action: () -> Unit,
) {
    val spoken = KitchenText.accessibility(food, locked, affordable, tasted, favorite, servings)
    TileButton(
        onClick = action,
        enabled = !locked,
        onClickLabel = if (locked) null else "Feed your slime",
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = spoken },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { }
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.padding(top = 4.dp)) {
                ItemIcon(food.sprite, Modifier.grayscale(locked), size = 56.dp)
                if (favorite) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 8.dp, y = (-8).dp)
                            .clip(CircleShape)
                            .background(Theme.card)
                            .padding(4.dp),
                    ) {
                        PixelItem("heart", Modifier.size(width = 16.dp, height = 13.dp))
                    }
                }
            }
            Text(
                food.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Theme.ink,
                textAlign = TextAlign.Center,
                minLines = 2,
                maxLines = 2,
            )
            if (locked) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = Theme.inkSoft, modifier = Modifier.size(14.dp))
                    Text("Level ${food.unlockLevel}", style = MaterialTheme.typography.labelMedium, color = Theme.inkSoft)
                }
            } else {
                FoodPrice(food, servings, affordable)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Filled.Restaurant, contentDescription = null, tint = Theme.inkSoft, modifier = Modifier.size(13.dp))
                Text("+${food.nutrition.toInt()}", style = MaterialTheme.typography.labelMedium, color = Theme.inkSoft)
            }
        }
        if (!tasted && !locked) Tag("NEW", Modifier.align(Alignment.TopStart).padding(8.dp).clearAndSetSemantics { })
    }
}

@Composable
private fun FoodPrice(food: Food, servings: Int, affordable: Boolean) {
    val style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
    when {
        servings > 0 -> Text("Free × $servings", style = style, fontWeight = FontWeight.SemiBold, color = Theme.accent)
        food.price == 0 -> Text("Free", style = style, fontWeight = FontWeight.SemiBold, color = Theme.accent)
        else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PixelItem("coin", Modifier.size(13.dp))
            Text(
                NumberFormat.getIntegerInstance().format(food.price),
                style = style,
                fontWeight = FontWeight.SemiBold,
                color = if (affordable) Theme.ink else Theme.inkSoft,
            )
        }
    }
}

/** The kitchen's words, kept apart from the views so they can be tested. */
object KitchenText {
    fun footerHint(tried: Int, total: Int, name: String): String =
        if (tried >= total) {
            "Every food tried. A true gourmet."
        } else {
            "Try every food once for the Gourmet achievement. New foods unlock as $name levels up."
        }

    /** What TalkBack says for a food tile. */
    fun accessibility(food: Food, locked: Boolean, affordable: Boolean, tasted: Boolean, favorite: Boolean, servings: Int): String {
        val parts = mutableListOf(food.title)
        if (favorite) parts.add("favourite")
        if (locked) {
            parts.add("locked, unlocks at level ${food.unlockLevel}")
        } else {
            if (servings > 0) {
                parts.add(if (servings == 1) "1 free serving" else "$servings free servings")
            } else {
                parts.add(if (food.price == 0) "free" else "${food.price} coins")
                if (!affordable) parts.add("not enough coins")
            }
            if (!tasted) parts.add("not tried yet")
        }
        parts.add("fills ${food.nutrition.toInt()}")
        return parts.joinToString(", ")
    }
}
