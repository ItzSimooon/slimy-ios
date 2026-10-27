package de.hyprnode.slimy.model

/**
 * Sound effects: the wav files of the iPhone app (Resources/Sounds, made by
 * tools/make_sounds.py), shipped as Android assets named `<rawValue>.wav`.
 * The app's `Feedback` plays them.
 */
enum class Sound {
    tap, hop, land, eat, pet, splash, sleep, wake, coin, levelUp, success, nope, pop, friend,
    perfect, miss, whoosh, dig, treasure,
    tone1, tone2, tone3, tone4, tone5, tone6, tone7, tone8, tone9;

    /** The asset file name. */
    val fileName: String get() = "$name.wav"

    companion object {
        val allCases: List<Sound> get() = entries

        fun tone(i: Int): Sound =
            listOf(tone1, tone2, tone3, tone4, tone5, tone6, tone7, tone8, tone9)[i.coerceIn(0, 8)]
    }
}
