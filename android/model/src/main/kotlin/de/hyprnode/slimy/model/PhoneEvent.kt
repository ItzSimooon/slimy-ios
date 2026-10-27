package de.hyprnode.slimy.model

import kotlinx.serialization.Serializable

/** A need change, like Swift's `(need: Need, amount: Double)`. */
data class NeedEffect(val need: Need, val amount: Double)

/** A feeling change, like Swift's `(emotion: Emotion, amount: Double)`. */
data class Feeling(val emotion: Emotion, val amount: Double)

/**
 * Things the phone does that the slime reacts to, noticed by PhoneMonitor.
 * Airplane mode and alarms can't be observed by apps; those cases stay so
 * older saves still load.
 */
@Serializable
enum class PhoneEvent {
    batteryLow, batteryCritical, batteryFull,
    chargerConnected, chargerDisconnected,
    wifiConnected, wifiDisconnected, cellular, offline, backOnline,
    airplaneOn, airplaneOff, lowPowerOn, lowPowerOff,
    goodMorning, goodNight, alarm, newHour;

    val id: String get() = name

    val title: String
        get() = when (this) {
            batteryLow -> "Battery low"
            batteryCritical -> "Battery almost empty"
            batteryFull -> "Battery full"
            chargerConnected -> "Charger plugged in"
            chargerDisconnected -> "Charger unplugged"
            wifiConnected -> "Wi-Fi connected"
            wifiDisconnected -> "Wi-Fi disconnected"
            cellular -> "Mobile data"
            offline -> "No internet"
            backOnline -> "Back online"
            airplaneOn -> "Airplane mode on"
            airplaneOff -> "Airplane mode off"
            lowPowerOn -> "Battery Saver on"
            lowPowerOff -> "Battery Saver off"
            goodMorning -> "Good morning"
            goodNight -> "Good night"
            alarm -> "Alarm"
            newHour -> "New hour"
        }

    /** The SF Symbol of the iPhone app; screens map it to a Material icon. */
    val symbol: String
        get() = when (this) {
            batteryLow -> "battery.25percent"
            batteryCritical -> "battery.0percent"
            batteryFull -> "battery.100percent"
            chargerConnected -> "bolt.fill"
            chargerDisconnected -> "powerplug"
            wifiConnected -> "wifi"
            wifiDisconnected -> "wifi.slash"
            cellular -> "antenna.radiowaves.left.and.right"
            offline -> "icloud.slash"
            backOnline -> "checkmark.icloud"
            airplaneOn, airplaneOff -> "airplane"
            lowPowerOn, lowPowerOff -> "leaf.fill"
            goodMorning -> "sun.max.fill"
            goodNight -> "moon.stars.fill"
            alarm -> "alarm.fill"
            newHour -> "clock.fill"
        }

    val reaction: Reaction
        get() = when (this) {
            batteryLow, batteryCritical -> Reaction.melt
            batteryFull, chargerConnected -> Reaction.zap
            chargerDisconnected, lowPowerOff, airplaneOff, backOnline -> Reaction.wave
            wifiConnected -> Reaction.play
            wifiDisconnected, cellular, offline, airplaneOn -> Reaction.confused
            lowPowerOn -> Reaction.melt
            goodMorning, alarm, newHour -> Reaction.wave
            goodNight -> Reaction.love
        }

    val wakesUp: Boolean
        get() = when (this) {
            batteryCritical, goodMorning, alarm -> true
            else -> false
        }

    val effect: NeedEffect?
        get() = when (this) {
            chargerConnected -> NeedEffect(Need.energy, 6.0)
            batteryFull -> NeedEffect(Need.`fun`, 5.0)
            wifiConnected -> NeedEffect(Need.`fun`, 4.0)
            goodMorning -> NeedEffect(Need.love, 4.0)
            else -> null
        }

    /** How it makes the slime feel. */
    val feeling: Feeling?
        get() = when (this) {
            batteryLow -> Feeling(Emotion.sadness, 0.1)
            batteryCritical -> Feeling(Emotion.fear, 0.3)
            batteryFull -> Feeling(Emotion.excitement, 0.2)
            chargerConnected -> Feeling(Emotion.joy, 0.15)
            chargerDisconnected -> Feeling(Emotion.curiosity, 0.1)
            wifiConnected -> Feeling(Emotion.curiosity, 0.15)
            wifiDisconnected -> Feeling(Emotion.fear, 0.1)
            cellular -> Feeling(Emotion.curiosity, 0.1)
            offline -> Feeling(Emotion.fear, 0.15)
            backOnline -> Feeling(Emotion.joy, 0.15)
            airplaneOn -> Feeling(Emotion.excitement, 0.2)
            airplaneOff -> Feeling(Emotion.joy, 0.1)
            lowPowerOn -> Feeling(Emotion.sleepiness, 0.15)
            lowPowerOff -> Feeling(Emotion.excitement, 0.1)
            goodMorning -> Feeling(Emotion.joy, 0.15)
            goodNight -> Feeling(Emotion.love, 0.1)
            alarm -> Feeling(Emotion.fear, 0.15)
            newHour -> null
        }

    /** Whether the island (the slime notification) should pop up with a sound. */
    val alerts: Boolean
        get() = when (this) {
            batteryLow, batteryCritical, batteryFull, offline, goodMorning, alarm -> true
            else -> false
        }

    val lines: List<String>
        get() = when (this) {
            batteryLow -> listOf("battery 20%. i'm melting", "getting all runny", "plug me in. please.")
            batteryCritical -> listOf("5%. i'm a puddle", "this is it. tell everyone bye", "battery. almost. gone.")
            batteryFull -> listOf("100%. i'm buzzing", "fully charged", "you can unplug now")
            chargerConnected -> listOf("power. yum.", "tingly", "*slurps electricity*")
            chargerDisconnected -> listOf("unplugged. free.", "bye cable", "let's go")
            wifiConnected -> listOf("wi-fi. zoom.", "hi router", "connected")
            wifiDisconnected -> listOf("where did the wi-fi go", "wi-fi gone", "hello? wi-fi?")
            cellular -> listOf("on mobile data now", "careful with the data", "over the air now")
            offline -> listOf("no internet", "offline. so quiet.", "where did everyone go")
            backOnline -> listOf("back online", "the internet is back", "signal!")
            airplaneOn -> listOf("airplane mode. are we flying?", "buckle up", "taking off")
            airplaneOff -> listOf("landed", "ground under my goo", "back on earth")
            lowPowerOn -> listOf("power saving. moving slowly", "saver mode", "only tiny wobbles now")
            lowPowerOff -> listOf("full power", "saver mode off", "i can wobble again")
            goodMorning -> listOf("good morning", "rise and shine", "new day. new goo.")
            goodNight -> listOf("sleep well", "good night", "time for slime dreams")
            alarm -> listOf("ALARM. GET UP.", "the alarm. go.", "up you get")
            newHour -> listOf("another hour already", "tick. tock.", "hourly wobble check")
        }

    companion object {
        val allCases: List<PhoneEvent> get() = entries
    }
}
