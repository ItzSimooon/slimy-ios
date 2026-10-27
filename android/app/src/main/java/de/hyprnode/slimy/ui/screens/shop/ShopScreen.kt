package de.hyprnode.slimy.ui.screens.shop

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.art.PixelItem
import de.hyprnode.slimy.art.PixelSlime
import de.hyprnode.slimy.core.Feedback
import de.hyprnode.slimy.core.GameStore
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.model.Catalog
import de.hyprnode.slimy.model.CatalogItem
import de.hyprnode.slimy.model.CrateKind
import de.hyprnode.slimy.model.Crates
import de.hyprnode.slimy.model.FurnitureSlot
import de.hyprnode.slimy.model.GameState
import de.hyprnode.slimy.model.Habitat
import de.hyprnode.slimy.model.ItemKind
import de.hyprnode.slimy.model.Outcome
import de.hyprnode.slimy.model.Rarity
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.Slime
import de.hyprnode.slimy.model.SlimeSprites
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.model.buy
import de.hyprnode.slimy.model.equip
import de.hyprnode.slimy.model.isAvailable
import de.hyprnode.slimy.model.isEquipped
import de.hyprnode.slimy.model.move
import de.hyprnode.slimy.model.owns
import de.hyprnode.slimy.model.unequip
import de.hyprnode.slimy.ui.kit.AppButton
import de.hyprnode.slimy.ui.kit.AppButtonKind
import de.hyprnode.slimy.ui.kit.Card
import de.hyprnode.slimy.ui.kit.ChipTabs
import de.hyprnode.slimy.ui.kit.CoinCounter
import de.hyprnode.slimy.ui.kit.ItemIcon
import de.hyprnode.slimy.ui.kit.SheetScaffold
import de.hyprnode.slimy.ui.kit.Tag
import de.hyprnode.slimy.ui.kit.Theme
import de.hyprnode.slimy.ui.kit.TileButton
import de.hyprnode.slimy.ui.screens.common.AdaptiveGrid
import de.hyprnode.slimy.ui.screens.common.ConfirmDialog
import de.hyprnode.slimy.ui.screens.common.grayscale
import de.hyprnode.slimy.ui.screens.common.isAccessibilityTextSize
import de.hyprnode.slimy.ui.screens.common.rememberAnnouncer
import de.hyprnode.slimy.ui.screens.crates.CrateCopy
import de.hyprnode.slimy.ui.screens.crates.CrateSprite
import de.hyprnode.slimy.ui.screens.crates.EffectIcon
import de.hyprnode.slimy.ui.screens.crates.RarityBadge
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.time.Instant

/** Which shelf of the shop is showing. */
sealed class ShopTab {
    data object crates : ShopTab()
    data class kind(val kind: ItemKind) : ShopTab()
    data object places : ShopTab()
    data object colours : ShopTab()

    companion object {
        /** Crates first, then the item kinds, places and colours. */
        val all: List<Pair<ShopTab, String>>
            get() = listOf<Pair<ShopTab, String>>(crates to "Crates") +
                ItemKind.allCases.map { kind(it) to it.title } +
                listOf(places to "Places", colours to "Colours")
    }
}

/** What was tapped to try on. */
sealed class ShopPick {
    data class item(val id: String) : ShopPick()
    data class habitat(val habitat: Habitat) : ShopPick()
    data class skin(val skin: Skin) : ShopPick()
}

/**
 * Buy and wear things: crates, hats, glasses, scarves, effects, furniture,
 * places and colours. Tapping anything tries it on the slime first; the bar
 * at the bottom buys, wears or places what is picked. Crates are bought and
 * opened right on their cards (App/UI/Screens/ShopView.swift).
 */
@Composable
fun ShopScreen(onClose: () -> Unit, onOpenCrate: (CrateKind) -> Unit, onDropRates: () -> Unit) {
    val store = LocalGameStore.current
    val context = LocalContext.current
    val s by store.state.collectAsStateWithLifecycle()
    val announce = rememberAnnouncer()
    val bigText = isAccessibilityTextSize()

    var tab by remember { mutableStateOf<ShopTab>(ShopTab.kind(ItemKind.hat)) }
    var pick by remember { mutableStateOf<ShopPick?>(null) }
    var line by remember { mutableStateOf<String?>(null) }
    var lineID by remember { mutableIntStateOf(0) }
    var buying by remember { mutableStateOf<CrateKind?>(null) }
    // Everything that was already for sale on earlier visits, so new things get a tag.
    val newKeys = remember { ShopSeen.visit(context, store.state.value) }

    LaunchedEffect(lineID) {
        if (line == null) return@LaunchedEffect
        delay(4_000)
        line = null
    }

    fun say(text: String?, announceIt: Boolean) {
        if (text.isNullOrEmpty()) return
        line = text
        lineID += 1
        if (announceIt) announce(text)
    }

    val actions = ShopActions(
        store = store,
        say = ::say,
        clearPick = { pick = null },
    )

    SheetScaffold(
        title = "Shop",
        onDone = onClose,
        inline = true,
        actions = { CoinCounter(coins = s.wallet.coins, inToolbar = true) },
    ) { padding ->
        // The action bar brings its own bottom inset; the crate shelf gets it below its last card.
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            SlimeShowcase(
                habitat = ShopPreview.habitat(s, pick, tab),
                furniture = ShopPreview.furniture(s, pick),
                slime = ShopPreview.slime(s, pick),
                line = line,
                height = if (bigText) 140.dp else 196.dp,
                slimeHeight = if (bigText) 70.dp else 96.dp,
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 4.dp),
            )
            ChipTabs(
                options = ShopTab.all,
                selection = tab,
                onSelect = {
                    tab = it
                    pick = null
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                bleed = 16.dp,
            )
            HorizontalDivider(color = Theme.separator)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .then(if (tab == ShopTab.crates) Modifier.navigationBarsPadding() else Modifier)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ShopText.note(tab, s.slime.name)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Theme.inkSoft)
                }
                if (tab == ShopTab.crates) {
                    CrateCards(s, buy = { buying = it }, open = onOpenCrate, rates = onDropRates)
                } else {
                    ShopGrid(tab, s, pick, newKeys, actions) { next ->
                        Feedback.shared.play(Sound.tap)
                        pick = if (pick == next) null else next
                    }
                }
            }
            // Crates have their own buttons; nothing to try on.
            if (tab != ShopTab.crates) ActionBar(s, pick, actions)
        }
    }

    buying?.let { kind ->
        val price = kind.price
        if (price != null) {
            ConfirmDialog(
                title = "Buy a ${CrateCopy.name(kind).lowercase()}?",
                message = "What's inside is a surprise until you open it.",
                confirmLabel = "Buy for ${CrateCopy.coins(price)}",
                onConfirm = {
                    // The store plays the coin sound, or its "nope" when it can't be bought.
                    if (store.buyCrate(kind)) {
                        say(crateLines.random(), announceIt = false)
                        announce("Bought a ${CrateCopy.name(kind).lowercase()}. Open it whenever you like.")
                    }
                },
                onDismiss = { buying = null },
            )
        }
    }
}

private val crateLines = listOf("ooh, a crate!", "what's inside? open it!", "shake it! shake it!", "i heard something rattle!")

/** Buying, wearing and placing, with the slime's line in the speech bubble instead of a toast. */
private class ShopActions(
    val store: GameStore,
    val say: (String?, Boolean) -> Unit,
    val clearPick: () -> Unit,
) {
    fun buy(item: CatalogItem) {
        val out = purchase { it.buy(item, Instant.now()) }
        if (out.ok) {
            // It was just tried on, so it goes straight on (or into its spot).
            store.update { it.equip(item.id, Instant.now()) }
        }
        say(out.line, true)
    }

    fun buy(habitat: Habitat) {
        val out = purchase { it.buy(habitat, Instant.now()) }
        say(out.line, true)
    }

    /**
     * Runs a purchase through the store without the app-wide toast: the line
     * shows in the speech bubble at the top instead.
     */
    private fun purchase(rule: (GameState) -> Outcome): Outcome {
        var said = ""
        val out = store.apply(sound = Sound.coin) { s ->
            val result = rule(s)
            said = result.line
            result.copy(line = "")
        }
        return out.copy(line = said)
    }

    fun wear(item: CatalogItem) {
        store.update { it.equip(item.id, Instant.now()) }
        Feedback.shared.play(Sound.success)
        say(store.state.value.slime.say?.text, true)
    }

    fun takeOff(item: CatalogItem) {
        store.update { it.unequip(item.id) }
        clearPick()
    }

    fun move(habitat: Habitat) {
        store.update { it.move(habitat) }
        Feedback.shared.play(Sound.success)
    }

    fun dye(skin: Skin) {
        store.wear(skin)
        Feedback.shared.play(Sound.success)
    }
}

// MARK: Grid

@Composable
private fun ShopGrid(
    tab: ShopTab,
    s: GameState,
    pick: ShopPick?,
    newKeys: Set<String>,
    actions: ShopActions,
    select: (ShopPick) -> Unit,
) {
    when (tab) {
        ShopTab.crates -> Unit
        is ShopTab.kind -> AdaptiveGrid(Catalog.items(tab.kind), minCellWidth = 150.dp) { item ->
            ItemTile(item, s, pick, newKeys, actions, select)
        }
        ShopTab.places -> AdaptiveGrid(Habitat.allCases, minCellWidth = 150.dp) { habitat ->
            HabitatTile(habitat, s, pick, newKeys, actions, select)
        }
        ShopTab.colours -> AdaptiveGrid(ShopText.shownSkins(s), minCellWidth = 150.dp) { skin ->
            SkinTile(skin, s, pick, newKeys, actions, select)
        }
    }
}

@Composable
private fun ItemTile(item: CatalogItem, s: GameState, pick: ShopPick?, newKeys: Set<String>, actions: ShopActions, select: (ShopPick) -> Unit) {
    val owned = s.owns(item.id)
    val available = s.isAvailable(item.unlock)
    val status = when {
        s.isEquipped(item.id) -> TileStatus.active(if (item.kind == ItemKind.furniture) "In the room" else "Wearing")
        owned -> TileStatus.owned
        available -> if (item.price == 0) TileStatus.free else TileStatus.price(item.price, s.wallet.coins >= item.price)
        else -> TileStatus.locked(UnlockText.short(item.unlock))
    }
    val m = ShopModels.model(ShopPick.item(item.id), s, actions)
    ShopTile(
        title = item.title,
        status = status,
        isNew = newKeys.contains(item.id) && !owned,
        rare = item.rare,
        legendary = false,
        selected = pick == ShopPick.item(item.id),
        accessibility = ShopText.accessibility(item.title, item.rare, status),
        quickAction = m.quickAction,
        action = { select(ShopPick.item(item.id)) },
    ) {
        ShopItemArt(
            item,
            size = 64.dp,
            modifier = Modifier.grayscale(!(available || owned)).alpha(if (available || owned) 1f else 0.5f),
        )
    }
}

@Composable
private fun HabitatTile(habitat: Habitat, s: GameState, pick: ShopPick?, newKeys: Set<String>, actions: ShopActions, select: (ShopPick) -> Unit) {
    val owned = s.inventory.habitats.contains(habitat)
    val available = s.isAvailable(habitat.unlock)
    val status = when {
        s.inventory.habitat == habitat -> TileStatus.active("Home")
        owned -> TileStatus.owned
        available -> if (habitat.price == 0) TileStatus.free else TileStatus.price(habitat.price, s.wallet.coins >= habitat.price)
        else -> TileStatus.locked(UnlockText.short(habitat.unlock))
    }
    val m = ShopModels.model(ShopPick.habitat(habitat), s, actions)
    ShopTile(
        title = habitat.title,
        status = status,
        isNew = newKeys.contains("habitat.${habitat.name}") && !owned,
        rare = false,
        legendary = false,
        selected = pick == ShopPick.habitat(habitat),
        accessibility = ShopText.accessibility(habitat.title, false, status),
        quickAction = m.quickAction,
        action = { select(ShopPick.habitat(habitat)) },
    ) {
        CroppedHabitat(
            habitat = habitat,
            furniture = if (habitat == Habitat.room) s.inventory.furniture else emptyMap(),
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
                .clip(RoundedCornerShape(Theme.smallRadius))
                .grayscale(!(available || owned))
                .alpha(if (available || owned) 1f else 0.55f),
        )
    }
}

@Composable
private fun SkinTile(skin: Skin, s: GameState, pick: ShopPick?, newKeys: Set<String>, actions: ShopActions, select: (ShopPick) -> Unit) {
    val unlocked = s.slime.isUnlocked(skin)
    val status = when {
        s.slime.skin == skin -> TileStatus.active("Wearing")
        unlocked -> TileStatus.owned
        else -> TileStatus.locked("Level ${skin.unlockLevel}")
    }
    val m = ShopModels.model(ShopPick.skin(skin), s, actions)
    val spoken = ShopText.accessibility(skin.title, false, status) + if (skin.isSecret) ", legendary" else ""
    ShopTile(
        title = skin.title,
        status = status,
        isNew = newKeys.contains("skin.${skin.name}") && s.slime.skin != skin,
        rare = false,
        legendary = skin.isSecret,
        selected = pick == ShopPick.skin(skin),
        accessibility = spoken,
        quickAction = m.quickAction,
        action = { select(ShopPick.skin(skin)) },
    ) {
        ShopSkinArt(
            skin,
            s.slime,
            Modifier
                .size(width = 77.dp, height = 64.dp)
                .grayscale(!unlocked)
                .alpha(if (unlocked) 1f else 0.45f),
        )
    }
}

/** How a tile shows its price or state. */
sealed class TileStatus {
    data class price(val price: Int, val affordable: Boolean) : TileStatus()
    data object free : TileStatus()
    data object owned : TileStatus()
    data class active(val text: String) : TileStatus()
    data class locked(val text: String) : TileStatus()
}

/** Gold dark enough for small text on light backgrounds. */
@Composable
private fun rareGold(): Color = if (Theme.isDark()) Color(0xFFFFD166) else Color(0xFF8A5A00)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShopTile(
    title: String,
    status: TileStatus,
    isNew: Boolean,
    rare: Boolean,
    legendary: Boolean,
    selected: Boolean,
    accessibility: String,
    quickAction: QuickAction?,
    action: () -> Unit,
    art: @Composable () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Theme.radius)
    val accent = Theme.accent
    Box {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Theme.card)
                .then(if (selected) Modifier.border(2.5.dp, accent, shape) else Modifier)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = androidx.compose.material3.ripple(),
                    role = Role.Button,
                    onClickLabel = "Show a preview at the top",
                    onLongClick = if (quickAction != null) ({ menu = true }) else null,
                    onClick = action,
                )
                .semantics {
                    contentDescription = accessibility + if (isNew) ", new" else ""
                    this.selected = selected
                    if (quickAction != null) {
                        customActions = listOf(CustomAccessibilityAction(quickAction.title) {
                            quickAction.run()
                            true
                        })
                    }
                },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { }
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { art() }
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Theme.ink,
                    textAlign = TextAlign.Center,
                    minLines = 2,
                    maxLines = 2,
                )
                StatusLine(status)
            }
            Column(
                Modifier
                    .padding(8.dp)
                    .clearAndSetSemantics { },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (isNew) Tag("NEW")
                if (rare) Tag("RARE", color = rareGold())
                if (legendary) RarityBadge(Rarity.legendary, compact = true)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (quickAction != null) {
                DropdownMenuItem(text = { Text(quickAction.title) }, onClick = {
                    menu = false
                    quickAction.run()
                })
            }
        }
    }
}

@Composable
private fun StatusLine(status: TileStatus) {
    val style = MaterialTheme.typography.bodyMedium
    when (status) {
        is TileStatus.price -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PixelItem("coin", Modifier.size(14.dp))
            Text(
                NumberFormat.getIntegerInstance().format(status.price),
                style = style.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                color = if (status.affordable) Theme.ink else Theme.inkSoft,
            )
        }
        TileStatus.free -> Text("Free", style = style, fontWeight = FontWeight.SemiBold, color = Theme.accent)
        TileStatus.owned -> Text("Owned", style = style, color = Theme.inkSoft)
        is TileStatus.active -> IconLine(Icons.Filled.CheckCircle, status.text, Theme.accent, FontWeight.SemiBold)
        is TileStatus.locked -> IconLine(Icons.Filled.Lock, status.text, Theme.inkSoft, FontWeight.Normal)
    }
}

@Composable
private fun IconLine(icon: ImageVector, text: String, color: Color, weight: FontWeight) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = weight, color = color, maxLines = 1)
    }
}

// MARK: Action bar

@Composable
private fun ActionBar(s: GameState, pick: ShopPick?, actions: ShopActions) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = Theme.separator)
            Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                if (pick == null) {
                    Text(
                        "Tap anything to try it on.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Theme.inkSoft,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 12.dp),
                    )
                } else {
                    PickBar(pick, s, actions)
                }
            }
        }
    }
}

@Composable
private fun PickBar(pick: ShopPick, s: GameState, actions: ShopActions) {
    val m = ShopModels.model(pick, s, actions)
    val bigText = isAccessibilityTextSize()
    val info: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PickArt(pick, s)
            Column(
                Modifier.semantics(mergeDescendants = true) { },
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(m.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Theme.ink, maxLines = 2)
                    if (m.rare) Tag("RARE", color = rareGold())
                    if (m.legendary) RarityBadge(Rarity.legendary, compact = true)
                }
                Text(m.detail, style = MaterialTheme.typography.bodyMedium, color = if (m.warning) Theme.danger else Theme.inkSoft)
            }
        }
    }
    val button: @Composable () -> Unit = {
        val title = m.buttonTitle
        if (title != null) {
            AppButton(
                title = title,
                icon = m.buttonIcon,
                systemIcon = if (m.buttonLocked) Icons.Filled.Lock else null,
                kind = m.buttonKind,
                compact = true,
                enabled = m.enabled,
            ) { m.run() }
        }
    }
    if (bigText) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            info(Modifier)
            button()
        }
    } else {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            info(Modifier.weight(1f))
            button()
        }
    }
}

@Composable
private fun PickArt(pick: ShopPick, s: GameState) {
    when (pick) {
        is ShopPick.item -> Catalog.item(pick.id)?.let { ShopItemArt(it, size = 44.dp) }
        is ShopPick.habitat -> CroppedHabitat(
            habitat = pick.habitat,
            furniture = if (pick.habitat == Habitat.room) s.inventory.furniture else emptyMap(),
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(11.dp)),
        )
        is ShopPick.skin -> Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Theme.well),
            contentAlignment = Alignment.Center,
        ) {
            ShopSkinArt(pick.skin, s.slime, Modifier.size(width = 40.dp, height = 33.dp))
        }
    }
}

/** The main action of a pick, for the tile's long-press menu and TalkBack. */
class QuickAction(val title: String, val run: () -> Unit)

/** What the action bar shows for the picked thing. */
data class PickModel(
    val title: String,
    val detail: String,
    val warning: Boolean = false,
    val rare: Boolean = false,
    /** A secret colour found in a crate. */
    val legendary: Boolean = false,
    val buttonTitle: String? = null,
    val buttonIcon: String? = null,
    /** The button shows a lock ("Locked"). */
    val buttonLocked: Boolean = false,
    val buttonKind: AppButtonKind = AppButtonKind.primary,
    val enabled: Boolean = true,
    val run: () -> Unit = {},
) {
    val quickAction: QuickAction?
        get() {
            val title = buttonTitle ?: return null
            if (!enabled || title == "Locked") return null
            return QuickAction(title, run)
        }
}

private object ShopModels {
    fun model(pick: ShopPick, s: GameState, actions: ShopActions): PickModel = when (pick) {
        is ShopPick.item -> Catalog.item(pick.id)?.let { ShopText.itemModel(it, s, actions::buy, actions::wear, actions::takeOff) }
            ?: PickModel("", "")
        is ShopPick.habitat -> ShopText.habitatModel(pick.habitat, s, { actions.buy(it) }, actions::move)
        is ShopPick.skin -> ShopText.skinModel(pick.skin, s, actions::dye)
    }
}

/** The shop's words and rules, kept apart from the views so they can be tested. */
object ShopText {
    fun note(tab: ShopTab, name: String): String? = when (tab) {
        ShopTab.crates -> CrateCopy.contents
        is ShopTab.kind -> when (tab.kind) {
            ItemKind.furniture -> "Furniture goes in the room, one piece per spot."
            ItemKind.effect -> "Effects float around $name wherever it goes."
            else -> null
        }
        ShopTab.places -> "Garden and beach have weather. Furniture stays in the room."
        ShopTab.colours -> "New colours unlock as $name levels up. They're free."
    }

    /** Every colour, except a secret one nobody has found yet: those are never for sale and never shown locked. */
    fun shownSkins(s: GameState): List<Skin> = Skin.allCases.filter { !it.isSecret || s.slime.isUnlocked(it) }

    fun accessibility(title: String, rare: Boolean, status: TileStatus): String {
        val parts = mutableListOf(title)
        if (rare) parts.add("rare")
        when (status) {
            is TileStatus.price -> parts.add(if (status.affordable) "${status.price} coins" else "${status.price} coins, not enough coins")
            TileStatus.free -> parts.add("free")
            TileStatus.owned -> parts.add("owned")
            is TileStatus.active -> parts.add(status.text.lowercase())
            is TileStatus.locked -> parts.add("locked, ${status.text}")
        }
        return parts.joinToString(", ")
    }

    fun needMore(missing: Int): String = if (missing == 1) "You need 1 more coin." else "You need $missing more coins."

    fun priceText(price: Int): String = if (price == 0) "Free" else "$price coins"

    fun itemModel(
        item: CatalogItem,
        s: GameState,
        buy: (CatalogItem) -> Unit = {},
        wear: (CatalogItem) -> Unit = {},
        takeOff: (CatalogItem) -> Unit = {},
    ): PickModel {
        val furniture = item.kind == ItemKind.furniture
        if (s.isEquipped(item.id)) {
            val slot = item.slot
            val detail = when {
                furniture && slot != null -> "In the room · ${slot.title}"
                furniture -> "In the room"
                else -> "Wearing it now"
            }
            return PickModel(
                title = item.title, detail = detail, rare = item.rare,
                buttonTitle = if (furniture) "Remove" else "Take off", buttonKind = AppButtonKind.quiet,
                run = { takeOff(item) },
            )
        }
        if (s.owns(item.id)) {
            return PickModel(
                title = item.title, detail = ownedDetail(item, s), rare = item.rare,
                buttonTitle = if (furniture) "Place" else "Wear", run = { wear(item) },
            )
        }
        if (s.isAvailable(item.unlock)) {
            val missing = item.price - s.wallet.coins
            return PickModel(
                title = item.title,
                detail = if (missing > 0) needMore(missing) else priceText(item.price),
                warning = missing > 0,
                rare = item.rare,
                buttonTitle = if (item.price == 0) "Get" else "Buy",
                buttonIcon = if (item.price == 0) null else "coin",
                enabled = missing <= 0,
                run = { buy(item) },
            )
        }
        return PickModel(
            title = item.title, detail = UnlockText.long(item.unlock), rare = item.rare,
            buttonTitle = "Locked", buttonLocked = true, buttonKind = AppButtonKind.quiet, enabled = false,
        )
    }

    private fun ownedDetail(item: CatalogItem, s: GameState): String {
        val slot = item.slot ?: return "Owned"
        val current = s.inventory.furniture[slot]
        val other = current?.takeIf { it != item.id }?.let { Catalog.item(it) }
        if (other != null) return "${slot.title} · replaces ${other.title.lowercase()}"
        return "Owned · ${slot.title.lowercase()}"
    }

    fun habitatModel(habitat: Habitat, s: GameState, buy: (Habitat) -> Unit = {}, move: (Habitat) -> Unit = {}): PickModel {
        val weather = if (habitat.hasWeather) " · has weather" else ""
        if (s.inventory.habitat == habitat) {
            return PickModel(title = habitat.title, detail = "${s.slime.name} lives here$weather")
        }
        if (s.inventory.habitats.contains(habitat)) {
            return PickModel(title = habitat.title, detail = "Owned$weather", buttonTitle = "Move here", run = { move(habitat) })
        }
        if (s.isAvailable(habitat.unlock)) {
            val missing = habitat.price - s.wallet.coins
            return PickModel(
                title = habitat.title,
                detail = if (missing > 0) needMore(missing) else priceText(habitat.price) + weather,
                warning = missing > 0,
                buttonTitle = if (habitat.price == 0) "Get" else "Buy",
                buttonIcon = if (habitat.price == 0) null else "coin",
                enabled = missing <= 0,
                run = { buy(habitat) },
            )
        }
        return PickModel(
            title = habitat.title, detail = UnlockText.long(habitat.unlock),
            buttonTitle = "Locked", buttonLocked = true, buttonKind = AppButtonKind.quiet, enabled = false,
        )
    }

    fun skinModel(skin: Skin, s: GameState, dye: (Skin) -> Unit = {}): PickModel {
        if (s.slime.skin == skin) {
            return PickModel(title = skin.title, detail = "${s.slime.name}'s colour right now", legendary = skin.isSecret)
        }
        if (s.slime.isUnlocked(skin)) {
            return PickModel(
                title = skin.title,
                detail = if (skin.isSecret) "Found in a crate" else "Unlocked",
                legendary = skin.isSecret,
                buttonTitle = "Use colour",
                run = { dye(skin) },
            )
        }
        // Never listed before it's found; nothing to say about it.
        if (skin.isSecret) return PickModel(title = "???", detail = "Only found in crates.")
        return PickModel(
            title = skin.title, detail = "Unlocks at level ${skin.unlockLevel}.",
            buttonTitle = "Locked", buttonLocked = true, buttonKind = AppButtonKind.quiet, enabled = false,
        )
    }
}

/** What the showcase shows while something is tried on. */
object ShopPreview {
    fun slime(s: GameState, pick: ShopPick?): Slime {
        val slime = s.slime.deepCopy()
        when (pick) {
            is ShopPick.item -> {
                val item = Catalog.item(pick.id)
                if (item != null && !s.isEquipped(pick.id)) {
                    slime.equipped = when (item.kind) {
                        ItemKind.hat -> slime.equipped.copy(hat = pick.id)
                        ItemKind.glasses -> slime.equipped.copy(glasses = pick.id)
                        ItemKind.scarf -> slime.equipped.copy(scarf = pick.id)
                        ItemKind.effect -> slime.equipped.copy(effect = pick.id)
                        ItemKind.furniture -> slime.equipped
                    }
                }
            }
            is ShopPick.skin -> slime.skin = pick.skin
            else -> Unit
        }
        return slime
    }

    fun habitat(s: GameState, pick: ShopPick?, tab: ShopTab): Habitat {
        if (pick is ShopPick.habitat) return pick.habitat
        if (tab == ShopTab.kind(ItemKind.furniture)) return Habitat.room
        return s.inventory.habitat
    }

    fun furniture(s: GameState, pick: ShopPick?): Map<FurnitureSlot, String> {
        val furniture = s.inventory.furniture.toMutableMap()
        if (pick is ShopPick.item) {
            val slot = Catalog.item(pick.id)?.slot
            if (slot != null) furniture[slot] = pick.id
        }
        return furniture
    }
}

/**
 * Tags for things that are new in the shop: everything for sale is noted on
 * each visit (prefs "slimy", key "slimy.shop.seen", like the iPhone app's
 * `@AppStorage`); the first visit marks everything as seen.
 */
object ShopSeen {
    private const val key = "slimy.shop.seen"

    /** Notes this visit and returns the keys that are new since the last one. */
    fun visit(context: Context, s: GameState): Set<String> {
        val prefs = context.getSharedPreferences("slimy", Context.MODE_PRIVATE)
        val (fresh, stored) = update(prefs.getString(key, "") ?: "", forSale(s))
        prefs.edit().putString(key, stored).apply()
        return fresh
    }

    /** The new keys and the value to store, from the stored value and what is for sale now. */
    fun update(stored: String, current: Set<String>): Pair<Set<String>, String> {
        val parts = stored.split(",").filter { it.isNotEmpty() }
        val started = parts.firstOrNull() == "v1"
        val seen = if (started) parts.drop(1).toSet() else emptySet()
        val fresh = if (started) current - seen else emptySet()
        val value = (listOf("v1") + (seen + current).sorted()).joinToString(",")
        return fresh to value
    }

    fun forSale(s: GameState): Set<String> {
        val keys = mutableSetOf<String>()
        for (item in Catalog.items) if (s.isAvailable(item.unlock) && !s.owns(item.id)) keys.add(item.id)
        for (habitat in Habitat.allCases) {
            if (s.isAvailable(habitat.unlock) && !s.inventory.habitats.contains(habitat)) keys.add("habitat.${habitat.name}")
        }
        for (skin in Skin.allCases) if (s.slime.isUnlocked(skin)) keys.add("skin.${skin.name}")
        return keys
    }
}

// MARK: Crates

@Composable
private fun CrateCards(s: GameState, buy: (CrateKind) -> Unit, open: (CrateKind) -> Unit, rates: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (kind in CrateKind.allCases) {
            ShopCrateCard(kind, owned = s.crates[kind] ?: 0, coins = s.wallet.coins, buy = { buy(kind) }, open = { open(kind) })
        }
        AppButton(title = "Drop rates", systemIcon = Icons.Filled.Percent, kind = AppButtonKind.quiet, action = rates)
        Text(CrateCopy.noRealMoney, style = MaterialTheme.typography.bodySmall, color = Theme.inkSoft)
    }
}

/** A crate in the shop: its price, how many are waiting, Buy and Open. */
@Composable
private fun ShopCrateCard(kind: CrateKind, owned: Int, coins: Int, buy: () -> Unit, open: () -> Unit) {
    val bigText = isAccessibilityTextSize()
    val price = kind.price
    val full = price != null && owned >= Crates.maxStack
    val ownedText = when (owned) {
        0 -> "None yet"
        1 -> "1 to open"
        else -> "$owned to open"
    }
    val spoken = buildString {
        val parts = mutableListOf<String>()
        if (price != null) {
            parts.add(CrateCopy.coins(price))
            if (coins < price) parts.add("not enough coins")
        }
        parts.add(ownedText.lowercase())
        append("${parts.joinToString(", ")}. ${kind.blurb}")
        if (full) append(" ${CrateCopy.shelfFull}")
    }
    Card {
        Row(
            Modifier.clearAndSetSemantics { contentDescription = "${CrateCopy.name(kind)}, $spoken" },
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(Theme.smallRadius))
                    .background(Theme.well)
                    .padding(10.dp),
            ) {
                CrateSprite(kind, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(CrateCopy.name(kind), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Theme.ink)
                Text(kind.blurb, style = MaterialTheme.typography.bodySmall, color = Theme.inkSoft)
                if (price != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PixelItem("coin", Modifier.size(14.dp))
                        Text(
                            NumberFormat.getIntegerInstance().format(price),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.SemiBold,
                            color = if (coins >= price) Theme.ink else Theme.inkSoft,
                        )
                    }
                    if (coins < price) {
                        Text(CrateCopy.needMore(price - coins), style = MaterialTheme.typography.bodySmall, color = Theme.danger)
                    }
                }
                Text(ownedText, style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft)
            }
        }
        if (price == null && owned == 0) {
            Text(CrateCopy.mysteryHint, style = MaterialTheme.typography.bodySmall, color = Theme.inkSoft)
        }
        if (full) {
            Text(CrateCopy.shelfFull, style = MaterialTheme.typography.bodySmall, color = Theme.inkSoft)
        }
        if (price != null || owned > 0) {
            val buttons: @Composable (Modifier) -> Unit = { m ->
                if (price != null) {
                    AppButton(
                        title = "Buy",
                        icon = "coin",
                        kind = AppButtonKind.secondary,
                        enabled = coins >= price && !full,
                        modifier = m.semantics { contentDescription = "Buy ${CrateCopy.name(kind).lowercase()} for ${CrateCopy.coins(price)}" },
                        action = buy,
                    )
                }
                if (owned > 0) {
                    AppButton(
                        title = "Open",
                        systemIcon = Icons.Outlined.Inventory2,
                        modifier = m.semantics { contentDescription = "Open ${CrateCopy.name(kind).lowercase()}" },
                        action = open,
                    )
                }
            }
            if (bigText) {
                Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { buttons(Modifier) }
            } else {
                Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    buttons(Modifier.weight(1f))
                }
            }
        }
    }
}

// MARK: Art

/** An item's icon. Effects have no sprite of their own, so they show what they make float around the slime. */
@Composable
fun ShopItemArt(item: CatalogItem, modifier: Modifier = Modifier, size: Dp = 64.dp) {
    if (item.kind == ItemKind.effect) {
        Box(
            modifier
                .size(size)
                .clip(RoundedCornerShape(size * 0.26f))
                .background(Theme.well)
                .padding(size * 0.22f),
            contentAlignment = Alignment.Center,
        ) {
            EffectIcon(item.id, Modifier.fillMaxSize())
        }
    } else {
        ItemIcon(item.sprite, modifier, size = size)
    }
}

/** The slime in another colour, wearing what it wears now. */
@Composable
fun ShopSkinArt(skin: Skin, slime: Slime, modifier: Modifier = Modifier) {
    val frames = SlimeSprites.frames(slime.stage, SpriteAnim.idle)
    val anchors = SlimeSprites.anchors(slime.stage, SpriteAnim.idle)
    PixelSlime(
        frame = frames.firstOrNull() ?: SlimeSprites.blank,
        skin = skin,
        accessories = slime.equipped,
        stage = slime.stage,
        anchor = anchors.firstOrNull(),
        modifier = modifier,
    )
}
