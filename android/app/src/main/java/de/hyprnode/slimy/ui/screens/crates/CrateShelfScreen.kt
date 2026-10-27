package de.hyprnode.slimy.ui.screens.crates

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.art.PixelItem
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.model.CrateKind
import de.hyprnode.slimy.model.Crates
import de.hyprnode.slimy.model.Rarity
import de.hyprnode.slimy.ui.kit.AppButton
import de.hyprnode.slimy.ui.kit.AppButtonKind
import de.hyprnode.slimy.ui.kit.CoinCounter
import de.hyprnode.slimy.ui.kit.GroupedDivider
import de.hyprnode.slimy.ui.kit.GroupedRow
import de.hyprnode.slimy.ui.kit.GroupedSection
import de.hyprnode.slimy.ui.kit.SheetScaffold
import de.hyprnode.slimy.ui.kit.Theme
import de.hyprnode.slimy.ui.screens.common.ConfirmDialog
import de.hyprnode.slimy.ui.screens.common.PushedScaffold
import de.hyprnode.slimy.ui.screens.common.ScreenColumn
import de.hyprnode.slimy.ui.screens.common.isAccessibilityTextSize
import de.hyprnode.slimy.ui.screens.common.rememberAnnouncer
import java.text.NumberFormat
import java.util.Locale

/**
 * The crate shelf: the crates waiting to be opened, crates for sale for
 * coins, and the drop rates (App/UI/Screens/CrateShelfView.swift).
 */
@Composable
fun CrateShelfScreen(onClose: () -> Unit, onOpen: (CrateKind) -> Unit, onDropRates: () -> Unit) {
    val store = LocalGameStore.current
    val s by store.state.collectAsStateWithLifecycle()
    val announce = rememberAnnouncer()
    var buying by remember { mutableStateOf<CrateKind?>(null) }
    val owned = CrateKind.allCases.filter { (s.crates[it] ?: 0) > 0 }
    val forSale = CrateKind.allCases.filter { it.price != null }

    SheetScaffold(
        title = "Crates",
        onDone = onClose,
        actions = { CoinCounter(coins = s.wallet.coins, inToolbar = true) },
    ) { padding ->
        ScreenColumn(padding) {
            GroupedSection(header = "Your crates") {
                if (owned.isEmpty()) {
                    EmptyShelf()
                } else {
                    owned.forEachIndexed { index, kind ->
                        if (index > 0) GroupedDivider(inset = 76.dp)
                        OwnedCrateRow(kind, s.crates[kind] ?: 0) { onOpen(kind) }
                    }
                }
            }
            GroupedSection(header = "Buy with coins", footer = CrateCopy.noRealMoney) {
                forSale.forEachIndexed { index, kind ->
                    if (index > 0) GroupedDivider(inset = 76.dp)
                    ShelfBuyRow(kind, coins = s.wallet.coins, owned = s.crates[kind] ?: 0) { buying = kind }
                }
            }
            GroupedSection(footer = CrateCopy.mysteryHint) {
                GroupedRow(
                    title = "Drop rates",
                    icon = { Icon(Icons.Filled.Percent, contentDescription = null, tint = Theme.accent) },
                    trailing = { Chevron() },
                    onClick = onDropRates,
                )
            }
        }
    }

    buying?.let { kind ->
        val price = kind.price ?: return@let
        ConfirmDialog(
            title = "Buy a ${CrateCopy.name(kind).lowercase()}?",
            message = "What's inside is a surprise until you open it.",
            confirmLabel = "Buy for ${CrateCopy.coins(price)}",
            onConfirm = {
                // The store plays the coin sound, or its "nope" when it can't be bought.
                if (store.buyCrate(kind)) announce("Bought a ${CrateCopy.name(kind).lowercase()}. It's on the shelf.")
            },
            onDismiss = { buying = null },
        )
    }
}

@Composable
private fun EmptyShelf() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.Inventory2, contentDescription = null, tint = Theme.inkSoft, modifier = Modifier.size(44.dp))
        Text("No crates yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Theme.ink)
        Text(
            "Crates come with the seventh daily gift, every fifth level, now and then after a great arcade round, and with some achievements. You can also buy one with coins below.",
            style = MaterialTheme.typography.bodyMedium,
            color = Theme.inkSoft,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun Chevron() {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Theme.inkSoft)
}

/** A crate on the shelf, how many there are, and Open. */
@Composable
private fun OwnedCrateRow(kind: CrateKind, count: Int, open: () -> Unit) {
    val countText = if (count == 1) "1 to open" else "$count to open"
    val info: @Composable (Modifier) -> Unit = { m ->
        Row(
            m.clearAndSetSemantics { contentDescription = "${CrateCopy.name(kind)}, $countText" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CrateIcon(kind)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(CrateCopy.name(kind), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Theme.ink)
                Text(countText, style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft)
            }
        }
    }
    val button: @Composable () -> Unit = {
        AppButton(
            title = "Open",
            systemIcon = Icons.Outlined.Inventory2,
            compact = true,
            modifier = Modifier.semantics { contentDescription = "Open ${CrateCopy.name(kind).lowercase()}" },
            action = open,
        )
    }
    ShelfRowLayout(info, button)
}

/** A crate for sale: price, and Buy (asks first). */
@Composable
private fun ShelfBuyRow(kind: CrateKind, coins: Int, owned: Int, buy: () -> Unit) {
    val price = kind.price ?: 0
    val missing = price - coins
    val full = owned >= Crates.maxStack
    val spoken = buildString {
        append(if (missing > 0) "${CrateCopy.coins(price)}, not enough coins. ${kind.blurb}" else "${CrateCopy.coins(price)}. ${kind.blurb}")
        if (full) append(" ${CrateCopy.shelfFull}")
    }
    val info: @Composable (Modifier) -> Unit = { m ->
        Row(
            m.clearAndSetSemantics { contentDescription = "${CrateCopy.name(kind)}, $spoken" },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CrateIcon(kind)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(CrateCopy.name(kind), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Theme.ink)
                Text(kind.blurb, style = MaterialTheme.typography.bodySmall, color = Theme.inkSoft)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PixelItem("coin", Modifier.size(14.dp))
                    Text(
                        NumberFormat.getIntegerInstance().format(price),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.SemiBold,
                        color = if (missing > 0) Theme.inkSoft else Theme.ink,
                    )
                }
                if (missing > 0) Text(CrateCopy.needMore(missing), style = MaterialTheme.typography.bodySmall, color = Theme.danger)
                if (full) Text(CrateCopy.shelfFull, style = MaterialTheme.typography.bodySmall, color = Theme.inkSoft)
            }
        }
    }
    val button: @Composable () -> Unit = {
        AppButton(
            title = "Buy",
            icon = "coin",
            kind = AppButtonKind.secondary,
            compact = true,
            enabled = missing <= 0 && !full,
            modifier = Modifier.semantics { contentDescription = "Buy ${CrateCopy.name(kind).lowercase()} for ${CrateCopy.coins(price)}" },
            action = buy,
        )
    }
    ShelfRowLayout(info, button)
}

/** Side by side, or stacked when the text is very large. */
@Composable
private fun ShelfRowLayout(info: @Composable (Modifier) -> Unit, button: @Composable () -> Unit) {
    if (isAccessibilityTextSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            info(Modifier)
            button()
        }
    } else {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            info(Modifier.weight(1f))
            button()
        }
    }
}

// MARK: - Drop rates

/**
 * The odds in every crate, rarity by rarity. The legendary reward stays a
 * secret: its row only says "???" (App/UI/Screens/CrateDropRates.swift).
 * [pushed] shows a back arrow (from the crate shelf); otherwise a Done button.
 */
@Composable
fun DropRatesScreen(onClose: () -> Unit, pushed: Boolean = true) {
    val content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit = { padding ->
        ScreenColumn(padding) {
            GroupedSection {
                Text(
                    CrateCopy.contents,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Theme.inkSoft,
                    modifier = Modifier.padding(16.dp),
                )
            }
            for (kind in CrateKind.allCases) {
                Column {
                    Row(
                        Modifier
                            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                            .clearAndSetSemantics {
                                contentDescription = CrateCopy.name(kind)
                                heading()
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CrateSprite(kind, Modifier.size(22.dp))
                        Text(CrateCopy.name(kind), style = MaterialTheme.typography.labelLarge, color = Theme.inkSoft)
                    }
                    GroupedSection(footer = DropRates.footer(kind)) {
                        DropRates.rows(kind).forEachIndexed { index, row ->
                            if (index > 0) GroupedDivider()
                            RateRow(row)
                        }
                    }
                }
            }
            GroupedSection {
                Row(
                    Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PixelItem("coin", Modifier.size(20.dp))
                    Text(CrateCopy.noRealMoney, style = MaterialTheme.typography.bodyMedium, color = Theme.inkSoft)
                }
            }
        }
    }
    if (pushed) {
        PushedScaffold(title = "Drop Rates", onBack = onClose, content = content)
    } else {
        SheetScaffold(title = "Drop Rates", onDone = onClose, inline = true, content = content)
    }
}

@Composable
private fun RateRow(row: DropRates.Rate) {
    val color = row.rarity.displayColor
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .clearAndSetSemantics { contentDescription = "${row.rarity.title}, ${row.spoken}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
                .border(0.5.dp, Theme.separator, CircleShape),
        )
        Text(row.rarity.title, style = MaterialTheme.typography.bodyLarge, color = Theme.ink)
        Spacer(Modifier.weight(1f))
        Text(
            row.text,
            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = if (row.rarity == Rarity.legendary) Theme.inkSoft else Theme.ink,
        )
    }
}

/** The odds, from the game's own drop table. */
object DropRates {
    data class Rate(
        val rarity: Rarity,
        /** "62.5%", or "???" for the secret. */
        val text: String,
        val spoken: String,
    )

    /**
     * Each rarity's share of the crate. Shares are worked out from the
     * table's total, so they add up however the table writes its chances.
     */
    fun rows(kind: CrateKind, locale: Locale = Locale.getDefault()): List<Rate> {
        val rates = Crates.dropRates(kind).filter { it.chance > 0 }
        val total = rates.sumOf { it.chance }
        val format = NumberFormat.getPercentInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 1
        }
        return rates.sortedBy { it.rarity.ordinal }.map { entry ->
            if (entry.rarity == Rarity.legendary) {
                Rate(entry.rarity, "???", "Secret")
            } else {
                val share = if (total > 0) entry.chance / total else 0.0
                val text = format.format(share)
                Rate(entry.rarity, text, text)
            }
        }
    }

    fun footer(kind: CrateKind): String {
        val price = kind.price ?: return CrateCopy.mysteryHint
        return "${CrateCopy.coins(price)} in the shop, or as a reward in the game."
    }
}
