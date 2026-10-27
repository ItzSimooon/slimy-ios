package de.hyprnode.slimy.platform

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import de.hyprnode.slimy.MainActivity
import de.hyprnode.slimy.SlimyApplication
import de.hyprnode.slimy.art.PixelPainter
import de.hyprnode.slimy.art.SlimeBitmap
import de.hyprnode.slimy.art.WorldArt
import de.hyprnode.slimy.model.Brand
import de.hyprnode.slimy.model.EmoteReel
import de.hyprnode.slimy.model.Need
import de.hyprnode.slimy.model.SlimeSprites
import de.hyprnode.slimy.ui.EXTRA_OPEN
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.math.floor

// Home-screen widgets: the port of Widget/HomeWidgets.swift. Material colours
// for light and dark around the pixel slime, which is drawn at a whole-number
// scale and shown at its own size, so it stays crisp. The small widget shows
// the slime and how it is doing; the medium one adds the needs and Feed and
// Pet buttons, which look after the slime without opening the app.

/** Redraws the widgets and builds what they show. */
object SlimeWidgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Redraws every placed widget (GameStore's `reloadWidgets` hook, after the buttons). */
    fun reloadAll(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { SlimeSmallWidget().updateAll(app) }
            runCatching { SlimeMediumWidget().updateAll(app) }
        }
    }

    /** The slime as the widgets show it now, straight from the game in this process. */
    internal fun entry(context: Context, now: Instant = Instant.now()): SlimeEntry {
        val app = context.applicationContext as? SlimyApplication ?: return SlimeEntry(null, now)
        return SlimeEntry.of(app.store.state.value, now)
    }

    /** The pixel art one widget needs, drawn before composing. */
    internal class Art(
        val slime: Bitmap,
        val status: Bitmap?,
        val needs: Map<Need, Bitmap>,
        val feed: Bitmap?,
        val pet: Bitmap?,
        val density: Float,
    )

    internal fun art(context: Context, entry: SlimeEntry): Art {
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        fun scale(heightDp: Float, artHeight: Int): Int =
            floor(heightDp * density / artHeight.coerceAtLeast(1)).toInt().coerceIn(1, 8)
        fun withDensity(bitmap: Bitmap): Bitmap = bitmap.also { it.density = metrics.densityDpi }
        fun item(name: String, heightDp: Float): Bitmap? {
            val sprite = WorldArt.sprite(name) ?: return null
            val grid = PixelPainter.item(sprite)
            return withDensity(grid.toBitmap(scale(heightDp, grid.height)))
        }
        fun emote(reel: EmoteReel, heightDp: Float): Bitmap {
            val rows = reel.poster
            return withDensity(PixelPainter.icon(rows, reel.color).toBitmap(scale(heightDp, rows.size)))
        }

        val anim = entry.anim
        val slime = withDensity(
            SlimeBitmap.frame(
                entry.stage, anim, anim.posterIndex(entry.date), entry.skin,
                scale(64f, SlimeSprites.height), entry.accessories,
            ),
        )
        val status = entry.status
        val statusArt = status.item?.let { item(it, 14f) } ?: status.reel?.let { emote(it, 12f) }
        val needs = Need.allCases.mapNotNull { need -> item(Brand.icon(need), 12f)?.let { need to it } }.toMap()
        return Art(
            slime = slime,
            status = statusArt,
            needs = needs,
            feed = item(Brand.icon(Need.hunger), 16f),
            pet = item(Brand.icon(Need.love), 16f),
            density = density,
        )
    }

    internal fun openApp(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN, "home")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    internal val small = DpSize(110.dp, 110.dp)
    internal val medium = DpSize(250.dp, 110.dp)
}

/** The slime and how it is doing ("Slime", 2 × 2). Wider, it shows the medium layout. */
class SlimeSmallWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SlimeWidgets.small, SlimeWidgets.medium))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = withContext(Dispatchers.Main) { SlimeWidgets.entry(context) }
        val art = SlimeWidgets.art(context, entry)
        provideContent { SlimeWidgetContent(entry, art, SlimeWidgets.openApp(context)) }
    }
}

/** The slime, its needs and Feed and Pet buttons ("Slime and needs", 4 × 2). Narrower, it shows the small layout. */
class SlimeMediumWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SlimeWidgets.small, SlimeWidgets.medium))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = withContext(Dispatchers.Main) { SlimeWidgets.entry(context) }
        val art = SlimeWidgets.art(context, entry)
        provideContent { SlimeWidgetContent(entry, art, SlimeWidgets.openApp(context)) }
    }
}

class SlimeSmallWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SlimeSmallWidget()
}

class SlimeMediumWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SlimeMediumWidget()
}

/** The medium widget's Feed button: feeds the slime its favourite food if affordable, otherwise an apple. */
class FeedSlimeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        widgetAction(context, IslandActionReceiver.ACTION_FEED)
    }
}

/** The medium widget's Pet button. */
class PetSlimeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        widgetAction(context, IslandActionReceiver.ACTION_PET)
    }
}

private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

private suspend fun widgetAction(context: Context, action: String) {
    val app = context.applicationContext as? SlimyApplication ?: return
    IslandActionReceiver.perform(app, action)
    SlimeWidgets.reloadAll(app)
    // The notification settles once the reaction has played (and comes back if it's missing).
    widgetScope.launch { app.island.settle() }
}

// MARK: Colours

private object WidgetInk {
    val text = ColorProvider(day = Color(0xFF1C1C1E), night = Color.White)
    val secondary = ColorProvider(day = Color(0xFF6C6C70), night = Color(0xFFAEAEB2))

    /** The system background with a faint wash of the slime's own colour. */
    fun background(accent: Long?): ColorProvider {
        val day = Color.White
        val night = Color(0xFF1C1C1E)
        if (accent == null) return ColorProvider(day = day, night = night)
        val a = Color(accent)
        return ColorProvider(day = lerp(day, a, 0.1f), night = lerp(night, a, 0.1f))
    }

    fun need(need: Need): ColorProvider = when (need) {
        Need.hunger -> ColorProvider(day = Color(0xFFD27A22), night = Color(0xFFF2A65A))
        Need.`fun` -> ColorProvider(day = Color(0xFF2A8DBC), night = Color(0xFF6CC4E8))
        Need.energy -> ColorProvider(day = Color(0xFFA88F0E), night = Color(0xFFE8D55A))
        Need.clean -> ColorProvider(day = Color(0xFF2A9A6C), night = Color(0xFF7FD9B0))
        Need.love -> ColorProvider(day = Color(0xFFD24C76), night = Color(0xFFF07A9A))
    }

    val track = ColorProvider(day = Color(0x1F000000), night = Color(0x33FFFFFF))

    fun button(need: Need): ColorProvider {
        val c = Color(Brand.color(need))
        return ColorProvider(day = c.copy(alpha = 0.22f), night = c.copy(alpha = 0.22f))
    }
}

// MARK: Content

@Composable
private fun SlimeWidgetContent(entry: SlimeEntry, art: SlimeWidgets.Art, open: Intent) {
    val size = LocalSize.current
    val modifier = GlanceModifier
        .fillMaxSize()
        .appWidgetBackground()
        .background(WidgetInk.background(entry.snapshot?.skin?.let { Brand.accent(it) }))
        .cornerRadius(20.dp)
        .clickable(actionStartActivity(open))
    if (size.width >= SlimeWidgets.medium.width) {
        MediumSlime(entry, art, modifier)
    } else {
        SmallSlime(entry, art, modifier)
    }
}

@Composable
private fun PixelImage(bitmap: Bitmap, density: Float, description: String?, modifier: GlanceModifier = GlanceModifier) {
    Image(
        provider = ImageProvider(bitmap),
        contentDescription = description,
        modifier = modifier.size((bitmap.width / density).dp, (bitmap.height / density).dp),
    )
}

/** Name on the left, level on the right. */
@Composable
private fun WidgetHeader(entry: SlimeEntry, longLevel: Boolean) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            entry.name,
            style = TextStyle(color = WidgetInk.text, fontSize = 16.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        entry.snapshot?.level?.let { level ->
            Text(
                if (longLevel) "Level $level" else "Lv $level",
                style = TextStyle(color = WidgetInk.secondary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                modifier = GlanceModifier.semantics { contentDescription = "Level $level" },
            )
        }
    }
}

/** "Hungry", with the need's pixel icon. */
@Composable
private fun StatusLabel(entry: SlimeEntry, art: SlimeWidgets.Art, fontSize: Float) {
    val status = entry.status
    Row(verticalAlignment = Alignment.CenterVertically) {
        art.status?.let {
            PixelImage(it, art.density, null)
            Spacer(GlanceModifier.width(5.dp))
        }
        Text(
            status.title,
            style = TextStyle(color = WidgetInk.text, fontSize = fontSize.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
    }
}

@Composable
private fun SmallSlime(entry: SlimeEntry, art: SlimeWidgets.Art, modifier: GlanceModifier) {
    Column(modifier = modifier.padding(12.dp).semantics { contentDescription = entry.accessibilitySummary }) {
        WidgetHeader(entry, longLevel = false)
        Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
            PixelImage(art.slime, art.density, null)
        }
        StatusLabel(entry, art, fontSize = 13f)
    }
}

@Composable
private fun MediumSlime(entry: SlimeEntry, art: SlimeWidgets.Art, modifier: GlanceModifier) {
    val status = entry.status
    Row(modifier = modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = GlanceModifier.width(120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PixelImage(art.slime, art.density, entry.name)
            if (entry.snapshot != null) {
                Spacer(GlanceModifier.height(6.dp))
                NeedMeters(entry, art)
            }
        }
        Spacer(GlanceModifier.width(12.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            WidgetHeader(entry, longLevel = true)
            StatusLabel(entry, art, fontSize = 14f)
            // The slime's own words while they're fresh and nothing is low, otherwise what would help.
            val speech = if (entry.lowNeed == null) entry.speech else null
            Text(
                speech ?: status.detail,
                style = TextStyle(color = WidgetInk.secondary, fontSize = 12.sp),
                maxLines = 2,
                modifier = if (speech != null) {
                    GlanceModifier.semantics { contentDescription = "${entry.name} says: $speech" }
                } else {
                    GlanceModifier
                },
            )
            Spacer(GlanceModifier.defaultWeight())
            if (entry.snapshot != null) {
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    ActionButton("Feed", Need.hunger, art.feed, art.density, actionRunCallback<FeedSlimeAction>(), GlanceModifier.defaultWeight())
                    Spacer(GlanceModifier.width(8.dp))
                    ActionButton("Pet", Need.love, art.pet, art.density, actionRunCallback<PetSlimeAction>(), GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

/** The five needs: pixel icon over a small meter. */
@Composable
private fun NeedMeters(entry: SlimeEntry, art: SlimeWidgets.Art) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for ((i, need) in Need.allCases.withIndex()) {
            if (i > 0) Spacer(GlanceModifier.width(4.dp))
            val value = entry.needs[need].coerceIn(0.0, 100.0)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = GlanceModifier.semantics { contentDescription = "${need.title} ${value.toInt()} percent" },
            ) {
                art.needs[need]?.let { PixelImage(it, art.density, null) }
                Spacer(GlanceModifier.height(3.dp))
                LinearProgressIndicator(
                    progress = (value / 100).toFloat(),
                    modifier = GlanceModifier.width(18.dp).height(4.dp),
                    color = WidgetInk.need(need),
                    backgroundColor = WidgetInk.track,
                )
            }
        }
    }
}

/** A rounded button in the medium widget, 48 dp tall. Runs in the app's process; the widget redraws when it's done. */
@Composable
private fun ActionButton(
    title: String,
    need: Need,
    icon: Bitmap?,
    density: Float,
    action: androidx.glance.action.Action,
    modifier: GlanceModifier,
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .background(WidgetInk.button(need))
            .cornerRadius(24.dp)
            .clickable(action)
            .semantics { contentDescription = title },
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon?.let {
            PixelImage(it, density, null)
            Spacer(GlanceModifier.width(6.dp))
        }
        Text(title, style = TextStyle(color = WidgetInk.text, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
    }
}
