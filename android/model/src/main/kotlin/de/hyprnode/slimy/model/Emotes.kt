package de.hyprnode.slimy.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * A loop of ten pixel icons the slime "says" next to it. Names match the
 * reels in tools/emotes.py and the sprite data's `emoteReels`.
 */
@Serializable
enum class EmoteReel {
    happyA, happyB, happyC, hunger, `fun`, sleepy, clean, love,
    battery, zap, wifi, confused, morning, night;

    /** ARGB colour the icons are drawn in. */
    val color: Long
        get() = when (this) {
            happyA, happyB, happyC, love -> Brand.warm
            hunger -> Brand.color(Need.hunger)
            `fun` -> Brand.color(Need.`fun`)
            sleepy, night -> Brand.cool
            clean -> Brand.color(Need.clean)
            battery -> Brand.danger
            zap, morning -> Brand.gold
            wifi -> Brand.slime
            confused -> Brand.cool
        }

    val fontName: String get() = "Emote-$name"

    /** The icon names of the loop, in order. */
    val icons: List<String> get() = SlimeSprites.emoteReels[name] ?: listOf("heart")

    /** The first icon of the reel, for when it can't animate ('#' = filled). */
    val poster: List<String>
        get() {
            val first = SlimeSprites.emoteReels[name]?.firstOrNull() ?: "heart"
            return SlimeSprites.emoteIcons[first] ?: emptyList()
        }

    companion object {
        val allCases: List<EmoteReel> get() = entries

        /** One of the happy loops, changing with every update so it feels random. */
        fun happy(at: Instant, calendar: SlimyCalendar = SlimyCalendar.current): EmoteReel =
            listOf(happyA, happyB, happyC)[calendar.minute(at) % 3]

        fun of(need: Need): EmoteReel = when (need) {
            Need.hunger -> hunger
            Need.`fun` -> `fun`
            Need.energy -> sleepy
            Need.clean -> clean
            Need.love -> love
        }

        fun of(event: PhoneEvent): EmoteReel? = when (event) {
            PhoneEvent.batteryLow, PhoneEvent.batteryCritical, PhoneEvent.lowPowerOn -> battery
            PhoneEvent.batteryFull, PhoneEvent.chargerConnected -> zap
            PhoneEvent.wifiConnected, PhoneEvent.backOnline -> wifi
            PhoneEvent.wifiDisconnected, PhoneEvent.cellular, PhoneEvent.offline, PhoneEvent.airplaneOn -> confused
            PhoneEvent.goodMorning, PhoneEvent.alarm -> morning
            PhoneEvent.goodNight -> night
            PhoneEvent.chargerDisconnected, PhoneEvent.airplaneOff, PhoneEvent.lowPowerOff, PhoneEvent.newHour -> null
        }
    }
}
