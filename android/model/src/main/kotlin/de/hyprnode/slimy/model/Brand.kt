package de.hyprnode.slimy.model

/**
 * Colours shared by app, widgets and the notification: the dark palette of
 * the island, the slime's pixel-art colours, and the need tints. ARGB longs
 * for Compose's `Color(Long)`. No gradients, no glows.
 */
object Brand {
    val bg: Long = argb(0x101411)
    val panel: Long = argb(0x171D19)
    val raised: Long = argb(0x212A24)
    val line: Long = argb(0x2E3A32)
    val text: Long = argb(0xE9F1E4)
    val muted: Long = argb(0x8D9D91)
    val slime: Long = argb(0x7DE67A)

    /** hyprnode violet, wordmark only. */
    val hypr: Long = argb(0x9B7BFF)

    /** Hearts, blush. */
    val warm: Long = argb(0xF07A9A)

    /** zzz, bubbles, sparks. */
    val cool: Long = argb(0x7FD6F0)

    /** Coins. */
    val gold: Long = argb(0xFFD166)
    val danger: Long = argb(0xE85D5D)

    /** Pixel-art colour of a need (emotes in the island). */
    fun color(need: Need): Long = when (need) {
        Need.hunger -> argb(0xF2A65A)
        Need.`fun` -> argb(0x6CC4E8)
        Need.energy -> argb(0xE8D55A)
        Need.clean -> argb(0x7FD9B0)
        Need.love -> argb(0xF07A9A)
    }

    /** The pixel icon of a need, the same one the app's need rings show. */
    fun icon(need: Need): String = when (need) {
        Need.hunger -> "apple"
        Need.`fun` -> "ball"
        Need.energy -> "starJelly"
        Need.clean -> "bubbleItem"
        Need.love -> "heart"
    }

    /** Accent for a slime: its own jelly colour (the Void skin uses violet). */
    fun accent(skin: Skin): Long = if (skin == Skin.void) hypr else skin.body
}
