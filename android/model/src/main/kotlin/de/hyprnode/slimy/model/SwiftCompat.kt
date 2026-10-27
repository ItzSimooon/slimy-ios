package de.hyprnode.slimy.model

import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

// Small helpers that keep the port of the iPhone app's Swift code literal:
// the same time arithmetic, the same rounding and the same UUID strings, so
// rewards, seeds and saves come out exactly as they do on iOS.

/** Swift's `Date.distantPast` (0001-01-01 in Apple's calendar), as the saves store it. */
val DistantPast: Instant = Instant.ofEpochSecond(-62_135_769_600L)

/** Seconds since 1970 as a Double, like `Date.timeIntervalSince1970`. */
val Instant.timeIntervalSince1970: Double
    get() = epochSecond.toDouble() + nano / 1_000_000_000.0

/** Seconds from [other] to this instant, like `Date.timeIntervalSince(_:)`. */
fun Instant.timeIntervalSince(other: Instant): Double =
    (epochSecond - other.epochSecond).toDouble() + (nano - other.nano) / 1_000_000_000.0

/** This instant moved by [seconds] (may be negative), like `Date.addingTimeInterval(_:)`. */
fun Instant.addingTimeInterval(seconds: Double): Instant {
    if (!seconds.isFinite()) return this
    val whole = floor(seconds)
    val nanos = ((seconds - whole) * 1_000_000_000.0).toLong()
    return plusSeconds(whole.toLong()).plusNanos(nanos)
}

/** An instant from seconds since 1970, like `Date(timeIntervalSince1970:)`. */
fun instantOf(secondsSince1970: Double): Instant {
    val whole = floor(secondsSince1970)
    val nanos = Math.round((secondsSince1970 - whole) * 1_000_000_000.0)
    return Instant.ofEpochSecond(whole.toLong()).plusNanos(nanos)
}

fun maxOf(a: Instant, b: Instant): Instant = if (a >= b) a else b
fun minOf(a: Instant, b: Instant): Instant = if (a <= b) a else b

/** Swift's `rounded()`: to nearest, halves away from zero (Kotlin's `round` rounds halves to even). */
fun Double.rounded(): Double {
    if (!isFinite()) return this
    val a = abs(this)
    val f = floor(a)
    val r = if (a - f >= 0.5) f + 1 else f
    return if (this < 0) -r else r
}

/** Swift's `rounded(.up)`. */
fun Double.roundedUp(): Double = ceil(this)

/** Swift's `Int(x)` for a Double: truncates toward zero. */
fun Double.swiftInt(): Int = toInt()

/** `UUID().uuidString`: upper case, as Swift writes it. */
val UUID.uuidString: String get() = toString().uppercase()

/** A new random id in Swift's `UUID().uuidString` form. */
fun newUUIDString(): String = UUID.randomUUID().uuidString

/** Swift's `String.prefix(n)` (by characters; never splits a surrogate pair). */
fun String.prefixChars(n: Int): String {
    if (n <= 0) return ""
    val count = codePointCount(0, length)
    if (count <= n) return this
    return substring(0, offsetByCodePoints(0, n))
}

/** `trimmingCharacters(in: .whitespacesAndNewlines)`. */
fun String.trimmedSwift(): String = trim { it.isWhitespace() }

/** Raw value of an enum whose cases are named like the Swift ones. */
val Enum<*>.rawValue: String get() = name

/** `SomeEnum(rawValue: raw)`: the case with that raw value, or null. */
inline fun <reified E : Enum<E>> enumOf(raw: String?): E? =
    if (raw == null) null else enumValues<E>().firstOrNull { it.name == raw }
