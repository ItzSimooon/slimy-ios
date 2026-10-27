package de.hyprnode.slimy.platform

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.LruCache
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import de.hyprnode.slimy.MainActivity
import de.hyprnode.slimy.R
import de.hyprnode.slimy.art.PixelGrid
import de.hyprnode.slimy.art.PixelPainter
import de.hyprnode.slimy.art.SlimeBitmap
import de.hyprnode.slimy.art.WorldArt
import de.hyprnode.slimy.model.Accessories
import de.hyprnode.slimy.model.Brand
import de.hyprnode.slimy.model.EmoteReel
import de.hyprnode.slimy.model.Need
import de.hyprnode.slimy.model.SlimeSprites
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.model.Stage
import de.hyprnode.slimy.ui.EXTRA_OPEN
import kotlin.math.floor

/**
 * Draws the slime notification: the Dynamic Island and Lock Screen card of
 * the iPhone app as an ongoing notification with a custom layout. Collapsed:
 * the slime, its name, what it says and five small need bars. Expanded: a
 * bigger slime, its level or the friend it is with, friends beside it, and
 * the five needs as buttons (Feed, Play, Sleep or Wake, Bath, Pet).
 *
 * Pixel art is drawn at a whole-number scale and shown at its own size, so
 * it stays crisp.
 */
internal object SlimeNotification {
    /** The one slime notification. */
    const val id = 7301

    /** The heads-up that pops up when the slime has news. */
    const val alertID = 7302

    private val cache = LruCache<String, Bitmap>(24)

    /** The ongoing notification for [state]. [frame] steps the animation; null shows the still pose. */
    fun build(context: Context, state: IslandState, frame: Int?, ongoing: Boolean): Notification {
        val small = RemoteViews(context.packageName, R.layout.slime_notification_small)
        val large = RemoteViews(context.packageName, R.layout.slime_notification_large)
        fillSmall(context, small, state, frame)
        fillLarge(context, large, state, frame)

        val wearActions = NotificationCompat.WearableExtender()
            .addAction(action(context, R.drawable.ic_action_feed, "Feed", IslandActionReceiver.ACTION_FEED))
            .addAction(action(context, R.drawable.ic_action_play, "Play", IslandActionReceiver.ACTION_PLAY))
            .addAction(action(context, R.drawable.ic_action_pet, "Pet", IslandActionReceiver.ACTION_PET))
            .addAction(action(context, R.drawable.ic_action_bath, "Bath", IslandActionReceiver.ACTION_BATH))

        return NotificationCompat.Builder(context, Notifier.slimeChannel)
            .setSmallIcon(R.drawable.ic_stat_slime)
            .setColor(Brand.accent(state.skin).toInt())
            // Read by TalkBack, watches and anything that can't show the layout.
            .setContentTitle(state.name)
            .setContentText(state.line)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(large)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setLocalOnly(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(context, "home", 10))
            .setDeleteIntent(broadcast(context, IslandActionReceiver.ACTION_DISMISSED))
            .extend(wearActions)
            .build()
    }

    /** The heads-up for news: the slime's name, what it says, and its picture. */
    fun alert(context: Context, alert: AlertConfiguration, state: IslandState): Notification {
        val picture = slimeBitmap(context, state.slimeStage, state.anim, state.anim.posterIndex(state.anchor),
            state.skin, state.accessories, heightDp = 48f, mirrored = false)
        return NotificationCompat.Builder(context, Notifier.alertChannel)
            .setSmallIcon(R.drawable.ic_stat_slime)
            .setColor(Brand.accent(state.skin).toInt())
            .setLargeIcon(picture)
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60 * 1000L)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(context, "home", 11))
            .build()
    }

    // MARK: Layouts

    private val bars = mapOf(
        Need.hunger to R.id.small_bar_hunger,
        Need.`fun` to R.id.small_bar_fun,
        Need.energy to R.id.small_bar_energy,
        Need.clean to R.id.small_bar_clean,
        Need.love to R.id.small_bar_love,
    )

    private class Tile(val tile: Int, val icon: Int, val bar: Int, val label: Int)

    private val tiles = mapOf(
        Need.hunger to Tile(R.id.tile_hunger, R.id.icon_hunger, R.id.bar_hunger, R.id.label_hunger),
        Need.`fun` to Tile(R.id.tile_fun, R.id.icon_fun, R.id.bar_fun, R.id.label_fun),
        Need.energy to Tile(R.id.tile_energy, R.id.icon_energy, R.id.bar_energy, R.id.label_energy),
        Need.clean to Tile(R.id.tile_clean, R.id.icon_clean, R.id.bar_clean, R.id.label_clean),
        Need.love to Tile(R.id.tile_love, R.id.icon_love, R.id.bar_love, R.id.label_love),
    )

    private fun fillSmall(context: Context, views: RemoteViews, s: IslandState, frame: Int?) {
        views.setImageViewBitmap(R.id.slime, slime(context, s, frame, heightDp = 32f))
        views.setTextViewText(R.id.name, s.name)
        views.setTextViewText(R.id.line, s.line)
        // Right of the name: the friend sharing a moment, else what the slime is emoting.
        val partner = s.partner
        val badge = if (partner != null) {
            friend(context, partner, s, frame, heightDp = 16f)
        } else {
            emote(context, s.emote, heightDp = 12f)
        }
        views.setImageViewBitmap(R.id.badge, badge)
        for ((need, id) in bars) views.setProgressBar(id, 100, s.value(need).coerceIn(0, 100), false)
        views.setContentDescription(R.id.small_bars, Need.allCases.joinToString(", ") { "${it.title} ${s.value(it)} percent" })
    }

    private fun fillLarge(context: Context, views: RemoteViews, s: IslandState, frame: Int?) {
        views.setImageViewBitmap(R.id.big_slime, slime(context, s, frame, heightDp = 48f))
        views.setContentDescription(R.id.big_slime, s.name)
        views.setTextViewText(R.id.big_name, s.name)
        views.setTextViewText(R.id.big_detail, s.detail)
        views.setTextViewText(R.id.big_line, s.line)
        views.setContentDescription(R.id.big_line, "${s.name} says: ${s.line}")
        views.setImageViewBitmap(R.id.big_badge, emote(context, s.emote, heightDp = 14f))

        // Beside the slime: the friend sharing a moment, or up to three friends.
        val partner = s.partner
        val shown: List<FriendSlime> = when {
            partner != null -> listOf(partner)
            else -> s.friends.orEmpty().take(3)
        }
        val slots = listOf(R.id.friend_1, R.id.friend_2, R.id.friend_3)
        for ((i, slot) in slots.withIndex()) {
            val f = shown.getOrNull(i)
            if (f == null) {
                views.setViewVisibility(slot, View.GONE)
                continue
            }
            val height = if (partner != null) 48f else if (i == 0) 30f else 26f
            views.setViewVisibility(slot, View.VISIBLE)
            views.setImageViewBitmap(slot, friend(context, f, s, frame, heightDp = height))
            views.setContentDescription(slot, "${f.name}, ${f.owner}'s slime")
        }
        views.setViewVisibility(R.id.big_friends, if (shown.isEmpty()) View.GONE else View.VISIBLE)

        for ((need, t) in tiles) {
            val value = s.value(need).coerceIn(0, 100)
            val title = s.actionTitle(need)
            views.setImageViewBitmap(t.icon, item(context, Brand.icon(need), heightDp = 16f))
            views.setProgressBar(t.bar, 100, value, false)
            views.setTextViewText(t.label, title)
            views.setContentDescription(t.tile, "$title, ${need.title} $value percent")
            views.setOnClickPendingIntent(t.tile, tileIntent(context, need))
        }
    }

    // MARK: Intents

    private fun tileIntent(context: Context, need: Need): PendingIntent = when (need) {
        Need.hunger -> broadcast(context, IslandActionReceiver.ACTION_FEED)
        // A game needs the screen: Play opens the arcade.
        Need.`fun` -> openApp(context, "arcade", 12)
        Need.energy -> broadcast(context, IslandActionReceiver.ACTION_SLEEP)
        Need.clean -> broadcast(context, IslandActionReceiver.ACTION_BATH)
        Need.love -> broadcast(context, IslandActionReceiver.ACTION_PET)
    }

    private fun action(context: Context, icon: Int, title: String, action: String): NotificationCompat.Action =
        NotificationCompat.Action.Builder(icon, title, broadcast(context, action)).build()

    fun broadcast(context: Context, action: String): PendingIntent {
        val intent = Intent(context, IslandActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context, IslandActionReceiver.requestCode(action), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun openApp(context: Context, place: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN, place)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // MARK: Pixel art

    private fun slime(context: Context, s: IslandState, frame: Int?, heightDp: Float): Bitmap =
        slimeBitmap(context, s.slimeStage, s.anim, frame ?: s.anim.posterIndex(s.anchor), s.skin, s.accessories,
            heightDp, mirrored = false)

    /** A friend's slime, facing yours. During a shared moment both play the same animation. */
    private fun friend(context: Context, f: FriendSlime, s: IslandState, frame: Int?, heightDp: Float): Bitmap {
        val anim = s.moment?.anim ?: SpriteAnim.of(f.mood)
        return slimeBitmap(context, f.stage, anim, frame ?: anim.posterIndex(s.anchor), f.skin,
            f.equipped ?: Accessories(), heightDp, mirrored = true)
    }

    /** The frame of an animation at a moment: one frame a second from [anchorMillis]. */
    fun frameIndex(stage: Stage, anim: SpriteAnim, anchorMillis: Long, nowMillis: Long): Int {
        val count = SlimeSprites.frames(stage, anim).size.coerceAtLeast(1)
        val elapsed = ((nowMillis - anchorMillis) / 1000L).coerceAtLeast(0L)
        return (elapsed % count).toInt()
    }

    fun slimeBitmap(
        context: Context,
        stage: Stage,
        anim: SpriteAnim,
        frame: Int,
        skin: Skin,
        accessories: Accessories,
        heightDp: Float,
        mirrored: Boolean,
    ): Bitmap {
        val scale = scaleFor(context, heightDp, SlimeSprites.height)
        val count = SlimeSprites.frames(stage, anim).size.coerceAtLeast(1)
        val i = ((frame % count) + count) % count
        val key = "slime|$stage|$anim|$i|$skin|${accessories.all.joinToString(",")}|$scale|$mirrored"
        return cached(context, key) {
            val grid = SlimeBitmap.pixels(stage, anim, i, skin, accessories)
            (if (mirrored) grid.mirrored() else grid).toBitmap(scale)
        }
    }

    private fun emote(context: Context, reel: EmoteReel, heightDp: Float): Bitmap {
        val rows = reel.poster
        val scale = scaleFor(context, heightDp, rows.size.coerceAtLeast(1))
        return cached(context, "emote|$reel|$scale") { PixelPainter.icon(rows, reel.color).toBitmap(scale) }
    }

    private fun item(context: Context, name: String, heightDp: Float): Bitmap {
        val sprite = WorldArt.sprite(name)
        return cached(context, "item|$name|$heightDp") {
            if (sprite == null) {
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            } else {
                val grid = PixelPainter.item(sprite)
                grid.toBitmap(scaleFor(context, heightDp, grid.height))
            }
        }
    }

    /**
     * The largest whole-number scale that keeps art [artHeight] pixels tall
     * within [heightDp]. At most 8, so the notification's bitmaps stay well
     * inside what one update may carry.
     */
    private fun scaleFor(context: Context, heightDp: Float, artHeight: Int): Int {
        val px = heightDp * context.resources.displayMetrics.density
        return floor(px / artHeight.coerceAtLeast(1)).toInt().coerceIn(1, maxScale)
    }

    private const val maxScale = 8

    /** Bitmaps carry the screen's density, so an ImageView shows them pixel for pixel. */
    private fun cached(context: Context, key: String, make: () -> Bitmap): Bitmap {
        cache.get(key)?.let { return it }
        val bitmap = make()
        bitmap.density = context.resources.displayMetrics.densityDpi
        cache.put(key, bitmap)
        return bitmap
    }

    private fun PixelGrid.mirrored(): PixelGrid {
        val out = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) out[y * width + (width - 1 - x)] = pixels[y * width + x]
        }
        return PixelGrid(width, height, out)
    }
}
