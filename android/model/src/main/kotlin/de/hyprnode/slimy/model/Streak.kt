package de.hyprnode.slimy.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The daily gift and the visit streak, as saved.
 *
 * Days are counted as calendar dates (Gregorian, local time), never as
 * 24-hour spans, so daylight saving and travelling don't break a streak.
 * The calendar of seven gifts moves on with every gift opened: missing a
 * day never takes a gift away, only the streak count starts again.
 */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = StreakState.Coding::class)
data class StreakState(
    /** Day key (yyyy-MM-dd) of the last gift opened. */
    var lastDay: String? = null,
    /** Days in a row with a gift opened, ending on `lastDay`. */
    var count: Int = 0,
    var best: Int = 0,
    /** Gifts opened in total. Moves the seven-day calendar along. */
    var opened: Int = 0,
) {
    internal object Coding : TolerantSerializer<StreakState>(StreakState.generatedSerializer()) {
        override fun decode(fields: JsonFields): StreakState = StreakState(
            lastDay = fields.value("lastDay", String.serializer().nullable, null),
            count = maxOf(0, fields.int("count", 0)),
            best = maxOf(0, fields.int("best", 0)),
            opened = maxOf(0, fields.int("opened", 0)),
        )
    }
}

/** One gift in the seven-day calendar. */
data class DailyGift(
    /** 1...7: its place in this week's calendar. */
    val day: Int,
    val coins: Int,
    /** A catalog item, if the gift holds one. */
    val item: String?,
    /** A crate. The seventh day brings a mystery crate. */
    val crate: CrateKind? = null,
) {
    val id: Int get() = day
    val isSpecial: Boolean get() = day == days

    /** "20 coins", the crate or the item's name. */
    val title: String
        get() {
            crate?.let { return it.title }
            item?.let { Catalog.item(it) }?.let { return it.title }
            return "$coins coins"
        }

    /** Everything in it: "20 coins", or "Mystery crate and 60 coins". */
    val summary: String
        get() {
            crate?.let { return "${it.title} and $coins coins" }
            item?.let { Catalog.item(it) }?.let { return "${it.title} and $coins coins" }
            return "$coins coins"
        }

    /** Item sprite for tiles and celebrations. */
    val icon: String
        get() {
            crate?.let { return it.sprite }
            item?.let { Catalog.item(it) }?.let { return it.sprite }
            return "coin"
        }

    companion object {
        const val days = 7
    }
}

/** The gift calendar as it stands right now, for the UI. */
data class StreakInfo(
    /** Days in a row. Still counts while the last gift was today or yesterday. */
    val current: Int,
    val best: Int,
    /** Gifts opened in total. */
    val opened: Int,
    /** Today's gift is waiting. */
    val canClaim: Boolean,
    val claimedToday: Boolean,
    /** 1...7: today's place in the calendar, opened or not. */
    val today: Int,
    /** This week's seven gifts. */
    val week: List<DailyGift>,
    /** When the next gift can be opened, if it can't be opened now. */
    val nextGift: Instant?,
) {
    val todayGift: DailyGift get() = week[today - 1]

    fun isOpened(gift: DailyGift): Boolean = gift.day < today || (gift.day == today && claimedToday)
}

/** A gift opened and what came of it. */
data class ClaimedGift(val gift: DailyGift, val outcome: Outcome)

object Streak {
    /** Coins for days 1 to 7. The seventh day also brings a crate. */
    val coins = listOf(10, 15, 20, 25, 30, 40, 60)

    /** The crate on every seventh day. */
    val weekCrate: CrateKind = CrateKind.mystery

    /** Streak lengths that go into the memory book. */
    val milestones: Set<Int> = setOf(7, 30, 100, 365)
    const val xp = 8

    fun dayKey(date: Instant, calendar: SlimyCalendar = SlimyCalendar.current): String = Challenges.dayKey(date, calendar)

    /** Ledger id of a day's gift: paid once per date. */
    fun receipt(dayKey: String): String = "gift:$dayKey"

    /**
     * Whole calendar days from one day key to another, negative if [to] is
     * earlier. Compares dates only. Null if a key is damaged.
     */
    fun days(from: String, to: String): Int? {
        val a = date(from) ?: return null
        val b = date(to) ?: return null
        return ChronoUnit.DAYS.between(a, b).toInt()
    }

    private fun date(key: String): LocalDate? {
        val parts = key.split("-").filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
        if (parts.size != 3 || parts[1] !in 1..12 || parts[2] !in 1..31) return null
        // Like DateComponents: a day past the month's end rolls over.
        return runCatching { LocalDate.of(parts[0], parts[1], 1).plusDays((parts[2] - 1).toLong()) }.getOrNull()
    }
}

/** The gift calendar at [now], without changing anything. */
fun GameState.streakInfo(now: Instant, calendar: SlimyCalendar = SlimyCalendar.current): StreakInfo {
    val today = Streak.dayKey(now, calendar)
    val gap = streak.lastDay?.let { Streak.days(it, today) }
    val claimedToday = gap == 0
    // One day behind the last gift (the clock went back, or a flight west):
    // wait for tomorrow. Further behind means the clock was set ahead
    // earlier; start again from here, like the needs do.
    val waiting = gap == -1
    val canClaim = hatched && !claimedToday && !waiting && !wallet.hasClaimed(Streak.receipt(today))
    val alive = gap?.let { it in -1..1 } ?: false
    val done = maxOf(0, if (claimedToday) streak.opened - 1 else streak.opened)
    val index = done % DailyGift.days
    val week = (0 until DailyGift.days).map { i ->
        val last = i == DailyGift.days - 1
        DailyGift(day = i + 1, coins = Streak.coins[i], item = null, crate = if (last) Streak.weekCrate else null)
    }
    var next: Instant? = null
    if (hatched && !canClaim) {
        val ahead = if (waiting) 2 else 1
        next = calendar.addingDays(ahead, calendar.startOfDay(now))
    }
    return StreakInfo(
        current = if (alive) streak.count else 0, best = streak.best, opened = streak.opened,
        canClaim = canClaim, claimedToday = claimedToday, today = index + 1, week = week, nextGift = next,
    )
}

/**
 * Opens today's gift: pays its coins (and on day 7 its crate), extends the
 * streak and checks achievements. Null if there's nothing to open.
 */
fun GameState.claimDailyGift(now: Instant, calendar: SlimyCalendar = SlimyCalendar.current): ClaimedGift? {
    tick(now, calendar)
    val info = streakInfo(now, calendar)
    if (!info.canClaim) return null
    val today = Streak.dayKey(now, calendar)
    val gift = info.todayGift
    if (!wallet.claim(Streak.receipt(today), coins = gift.coins, at = now)) return null

    val gap = streak.lastDay?.let { Streak.days(it, today) }
    val startedOver = gap != 1 && streak.count >= 2
    streak.count = if (gap == 1) streak.count + 1 else 1
    streak.best = maxOf(streak.best, streak.count)
    streak.lastDay = today
    streak.opened += 1
    gift.item?.let { inventory.owned = inventory.owned + it }
    gift.crate?.let { addCrate(it) }
    if (Streak.milestones.contains(streak.count)) {
        memory.add(MemoryEntry(kind = MemoryEntry.Kind.streak, at = now, detail = "${streak.count}", keepsake = true))
    }

    slime.isAsleep = false
    slime.needs.add(Need.love, 5.0)
    slime.needs.add(Need.`fun`, 4.0)
    slime.bond = minOf(100.0, slime.bond + 0.5)
    slime.feel(Emotion.joy, 0.2)
    slime.feel(Emotion.excitement, if (gift.isSpecial) 0.35 else 0.2)
    slime.react(Reaction.celebrate, now)
    noteCare(CareKind.gift, now = now)

    val category = when {
        gift.isSpecial -> LineCategory.giftWeek
        startedOver -> LineCategory.streakFresh
        streak.count >= 3 -> LineCategory.streak
        else -> LineCategory.dailyGift
    }
    val context = LineContext(item = gift.title.lowercase(), streak = streak.count)
    val line = StringRef(Dialogue.say(category, this, context, now, calendar))
    val events = gainXP(Streak.xp, now, line) + checkAchievements(now)
    slime.say = Utterance(text = line.value, at = now)
    return ClaimedGift(gift, Outcome(ok = true, line = line.value, events = events))
}
