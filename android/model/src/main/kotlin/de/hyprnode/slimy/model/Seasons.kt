package de.hyprnode.slimy.model

import java.time.Instant

/**
 * The time of year. Decorations, lines and the seasonal shop follow it.
 * Always worked out on the Gregorian calendar in the player's time zone.
 */
enum class Season {
    spring, summer, autumn, winter,

    /** All of October: pumpkins, bats and spooky lines. */
    halloween,

    /** All of December: snow, lights and cozy lines. */
    christmas,

    /** New Year's Eve from 6 pm and New Year's Day: fireworks. */
    newYear;

    /** Lines the slime mixes into its small talk during this season. */
    val lines: LineCategory?
        get() = when (this) {
            halloween -> LineCategory.spooky
            christmas -> LineCategory.cozy
            newYear -> LineCategory.newYear
            else -> null
        }

    /** What it says about the time of year, in every season. */
    val talk: LineCategory
        get() = when (this) {
            spring -> LineCategory.spring
            summer -> LineCategory.summer
            autumn -> LineCategory.autumn
            winter -> LineCategory.winter
            halloween -> LineCategory.spooky
            christmas -> LineCategory.cozy
            newYear -> LineCategory.newYear
        }

    companion object {
        val allCases: List<Season> get() = entries

        fun at(date: Instant, calendar: SlimyCalendar = SlimyCalendar.current): Season {
            val month = calendar.month(date)
            val day = calendar.day(date)
            val hour = calendar.hour(date)
            if (month == 1 && day == 1) return newYear
            if (month == 12 && day == 31 && hour >= 18) return newYear
            return when (month) {
                10 -> halloween
                12 -> christmas
                in 3..5 -> spring
                in 6..8 -> summer
                9, 11 -> autumn
                else -> winter
            }
        }
    }
}

/**
 * What the sky is doing, the same for everyone in three-hour blocks. Worked
 * out exactly like the habitat scene's weather, so the slime talks about the
 * rain or snow you can see.
 */
enum class Sky {
    clear, cloudy, rain, snow;

    /** What the slime says about it; nothing for a clear sky. */
    val talk: LineCategory?
        get() = when (this) {
            clear -> null
            cloudy -> LineCategory.cloudTalk
            rain -> LineCategory.rainTalk
            snow -> LineCategory.snowTalk
        }

    companion object {
        val allCases: List<Sky> get() = entries

        fun at(date: Instant, calendar: SlimyCalendar = SlimyCalendar.current): Sky {
            val block = calendar.hour(date) / 3
            val rng = SplitMix(SplitMix.seed(Challenges.dayKey(date, calendar) + "w$block"))
            val roll = rng.unit()
            return when (calendar.month(date)) {
                12 -> {
                    // December snows more often.
                    if (roll < 0.4) clear else if (roll < 0.55) cloudy else snow
                }
                1, 2 -> if (roll < 0.6) clear else if (roll < 0.8) cloudy else snow
                else -> if (roll < 0.6) clear else if (roll < 0.8) cloudy else rain
            }
        }
    }
}

/** A day with a small gift, once a year. */
enum class Holiday {
    halloween, holidays, newYear;

    val greeting: String
        get() = when (this) {
            halloween -> "Happy Halloween"
            holidays -> "Happy holidays"
            newYear -> "Happy New Year"
        }

    val coins: Int
        get() = when (this) {
            halloween -> 31
            holidays -> 25
            newYear -> 30
        }

    /** Item sprite for the celebration and the memory book. */
    val icon: String
        get() = when (this) {
            halloween -> "hat.pumpkin"
            holidays -> "starJelly"
            newYear -> "hat.party"
        }

    val lines: LineCategory
        get() = when (this) {
            halloween -> LineCategory.spooky
            holidays -> LineCategory.cozy
            newYear -> LineCategory.newYear
        }

    /** Ledger id: paid once per holiday and year. */
    fun receipt(year: Int): String = "holiday:$name:$year"

    companion object {
        val allCases: List<Holiday> get() = entries

        fun at(date: Instant, calendar: SlimyCalendar = SlimyCalendar.current): Holiday? {
            val month = calendar.month(date)
            val day = calendar.day(date)
            return when {
                month == 10 && day == 31 -> halloween
                month == 12 && day in 24..26 -> holidays
                month == 1 && day == 1 -> newYear
                else -> null
            }
        }
    }
}

/** The slime's hatch-day anniversary. */
object Birthday {
    /**
     * How old the slime turns today, or null if today isn't its birthday.
     * Slimes hatched on 29 February celebrate on the 28th in other years.
     */
    fun years(born: Instant, at: Instant, calendar: SlimyCalendar = SlimyCalendar.current): Int? {
        val b = calendar.localDate(born)
        val n = calendar.localDate(at)
        val years = n.year - b.year
        if (years < 1) return null
        var day = b.dayOfMonth
        if (b.monthValue == 2 && b.dayOfMonth == 29 && !isLeap(n.year)) day = 28
        return if (n.monthValue == b.monthValue && n.dayOfMonth == day) years else null
    }

    /** Ledger id: paid once per year. */
    fun receipt(year: Int): String = "birthday:$year"

    fun coins(years: Int): Int = minOf(200, 50 * maxOf(1, years))

    private fun isLeap(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
}
