package de.hyprnode.slimy.model

/**
 * Small deterministic random generator, so worlds and challenges are
 * reproducible from a seed and testable. Bit for bit the same sequence as
 * the iPhone app's `SplitMix`, so everyone gets the same challenges, daily
 * game and crate rolls on both platforms.
 */
class SplitMix(seed: ULong) {
    var state: ULong = seed

    fun next(): ULong {
        state += 0x9E3779B97F4A7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return z xor (z shr 31)
    }

    /** 0 ..< 1. */
    fun unit(): Double = (next() shr 11).toDouble() / (1L shl 53).toDouble()

    /** A value in [range], inclusive. */
    fun int(range: IntRange): Int {
        val count = (range.last - range.first + 1).toULong()
        return range.first + (next() % count).toInt()
    }

    /** `Int(rng.next() % UInt64(count))`. */
    fun nextIndex(count: Int): Int = (next() % count.toULong()).toInt()

    fun copy(): SplitMix = SplitMix(state)

    companion object {
        /** FNV-1a over the UTF-8 bytes. */
        fun seed(string: String): ULong {
            var h = 0xcbf29ce484222325uL
            for (b in string.toByteArray(Charsets.UTF_8)) {
                h = (h xor (b.toInt() and 0xFF).toULong()) * 0x100000001b3uL
            }
            return h
        }
    }
}

/** The tiny LCG the scenes use for star fields and decoration (`SeededRandom`). */
class SeededRandom(seed: ULong) {
    var state: ULong = seed

    /** 0 ... ~1, like the Swift `CGFloat(state >> 33) / CGFloat(UInt32.max >> 1)`. */
    fun next(): Double {
        state = state * 6364136223846793005uL + 1442695040888963407uL
        return (state shr 33).toDouble() / (UInt.MAX_VALUE shr 1).toDouble()
    }
}
