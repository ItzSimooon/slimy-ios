package de.hyprnode.slimy.platform

import de.hyprnode.slimy.model.Accessories
import de.hyprnode.slimy.model.Brand
import de.hyprnode.slimy.model.EmoteReel
import de.hyprnode.slimy.model.GameState
import de.hyprnode.slimy.model.Need
import de.hyprnode.slimy.model.Needs
import de.hyprnode.slimy.model.SleepSchedule
import de.hyprnode.slimy.model.Slime
import de.hyprnode.slimy.model.SlimyCalendar
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.model.Stage
import de.hyprnode.slimy.model.WidgetSnapshot
import de.hyprnode.slimy.model.timeIntervalSince
import de.hyprnode.slimy.model.trimmedSwift
import java.time.Instant

/**
 * What the home-screen widgets show at one moment: the port of
 * Widget/WidgetData.swift. Built from a [WidgetSnapshot], with the needs
 * projected forward so they keep moving between app launches.
 *
 * [schedule] is the bedtime the player chose, so the projected sleep matches
 * what the app will show; without it the one in the snapshot (or the
 * default bedtime) is used.
 */
class SlimeEntry(
    /** The slime as last saved. Null before it has hatched. */
    val snapshot: WidgetSnapshot?,
    val date: Instant,
    schedule: SleepSchedule? = null,
    calendar: SlimyCalendar = SlimyCalendar.current,
) {
    /** Needs as they probably are at [date]. */
    val needs: Needs

    /** Whether the slime is probably asleep at [date]: it goes to bed and wakes up on its own. */
    val isAsleep: Boolean

    init {
        if (snapshot == null) {
            needs = Needs()
            isAsleep = false
        } else {
            val slime = Slime()
            slime.needs = snapshot.needs.copy()
            slime.isAsleep = snapshot.isAsleep
            slime.personality = snapshot.personality.copy()
            slime.lastUpdate = snapshot.writtenAt
            slime.advance(date, schedule ?: snapshot.sleep ?: SleepSchedule(), calendar)
            needs = slime.needs
            isAsleep = slime.isAsleep
        }
    }

    val name: String get() = snapshot?.name ?: "Slime"

    /** The need worth mentioning, if one is low. */
    val lowNeed: Need?
        get() {
            if (isAsleep) return null
            val lowest = needs.lowest
            return if (lowest.value < 35) lowest.need else null
        }

    val anim: SpriteAnim
        get() {
            if (snapshot == null) return SpriteAnim.idle
            if (isAsleep) return SpriteAnim.sleep
            return if (lowNeed == null) SpriteAnim.idle else SpriteAnim.sad
        }

    val stage: Stage get() = snapshot?.stage ?: Stage.young
    val skin: Skin get() = snapshot?.skin ?: Skin.lime
    val accessories: Accessories get() = snapshot?.equipped ?: Accessories()

    /** The slime's own words, while they are fresh (saved in the last half hour and it's awake). */
    val speech: String?
        get() {
            val snapshot = snapshot ?: return null
            if (isAsleep || date.timeIntervalSince(snapshot.writtenAt) >= 1800) return null
            return snapshot.line.trimmedSwift().ifEmpty { null }
        }

    val status: SlimeStatus
        get() {
            val snapshot = snapshot
                ?: return SlimeStatus(
                    "Open Slimy", "Open the app to bring your slime here.",
                    symbol = "drop.fill", tint = null, sentence = "Open Slimy to see your slime",
                )
            val name = snapshot.name
            if (isAsleep) {
                return SlimeStatus(
                    "Asleep", "Wakes up on its own.", symbol = "moon.zzz.fill", tint = indigo,
                    sentence = "$name is asleep", reel = EmoteReel.sleepy,
                )
            }
            lowNeed?.let { need ->
                val tint = Brand.color(need)
                val item = Brand.icon(need)
                return when (need) {
                    Need.hunger -> SlimeStatus("Hungry", "A snack would help.", need.symbol, tint, "$name is hungry", item = item)
                    Need.`fun` -> SlimeStatus("Bored", "A game would cheer it up.", need.symbol, tint, "$name is bored", item = item)
                    Need.energy -> SlimeStatus("Tired", "A nap would help.", need.symbol, tint, "$name is tired", item = item)
                    Need.clean -> SlimeStatus("Sticky", "Time for a bubble bath.", need.symbol, tint, "$name needs a bath", item = item)
                    Need.love -> SlimeStatus("Wants cuddles", "Some petting would help.", need.symbol, tint, "$name wants cuddles", item = item)
                }
            }
            if (needs.average >= 70) {
                return SlimeStatus(
                    "Happy", "All needs looked after.", symbol = "face.smiling", tint = green,
                    sentence = "$name is happy", reel = EmoteReel.happyA,
                )
            }
            return SlimeStatus(
                "Doing fine", "Say hi when you can.", symbol = "face.smiling", tint = null,
                sentence = "$name is doing fine", reel = EmoteReel.happyB,
            )
        }

    /** One sentence for TalkBack. */
    val accessibilitySummary: String
        get() {
            val current = status
            if (snapshot == null) return "${current.title}. ${current.detail}"
            return "$name. ${current.title}. ${current.detail}"
        }

    companion object {
        private const val indigo = 0xFF5856D6
        private const val green = 0xFF34C759

        /** A made-up slime for previews. */
        fun sample(date: Instant): SlimeEntry {
            val snapshot = WidgetSnapshot.of(GameState.fresh(), date).copy(
                name = "Blob",
                skin = Skin.lime,
                stage = Stage.young,
                level = 6,
                coins = 120,
                isAsleep = false,
                needs = Needs(hunger = 58.0, `fun` = 82.0, energy = 88.0, clean = 74.0, love = 91.0),
                line = "hi! blub.",
                writtenAt = date,
                equipped = Accessories(),
            )
            return SlimeEntry(snapshot, date)
        }

        /** The widgets' entry for a game: null snapshot before hatching; needs as of the slime's last update. */
        fun of(state: GameState, now: Instant, calendar: SlimyCalendar = SlimyCalendar.current): SlimeEntry {
            if (!state.hatched) return SlimeEntry(null, now, calendar = calendar)
            val snapshot = WidgetSnapshot.of(state, now).copy(writtenAt = minOf(state.slime.lastUpdate, now))
            return SlimeEntry(snapshot, now, state.settings.sleep, calendar)
        }
    }
}

/** How the slime is doing, in plain English. */
data class SlimeStatus(
    val title: String,
    val detail: String,
    /** The SF Symbol of the iPhone widget. */
    val symbol: String,
    /** Colours the icon; null uses the secondary colour. ARGB. */
    val tint: Long?,
    /** One sentence: "Blob is hungry". */
    val sentence: String,
    /** The pixel item shown as the icon (a need's icon), if any. */
    val item: String? = null,
    /** The pixel emote shown as the icon (asleep, happy), if any. */
    val reel: EmoteReel? = null,
)
