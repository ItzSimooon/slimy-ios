package de.hyprnode.slimy.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The calendar the game rules count days, hours and seasons with: always
 * Gregorian (like `Calendar.gregorian(like:)` on iOS), in a time zone, with
 * the week rules of a region. The Swift code's `calendar: Calendar = .current`
 * parameters become `calendar: SlimyCalendar = SlimyCalendar.current`.
 *
 * @param firstWeekday 1 = Sunday … 7 = Saturday, as on iOS.
 */
data class SlimyCalendar(
    val zone: ZoneId,
    val firstWeekday: Int = 1,
    val minimumDaysInFirstWeek: Int = 1,
) {
    private val weekFields: WeekFields
        get() = WeekFields.of(dayOfWeek(firstWeekday), minimumDaysInFirstWeek.coerceIn(1, 7))

    fun local(date: Instant): ZonedDateTime = date.atZone(zone)

    fun year(date: Instant): Int = local(date).year
    fun month(date: Instant): Int = local(date).monthValue
    fun day(date: Instant): Int = local(date).dayOfMonth
    fun hour(date: Instant): Int = local(date).hour
    fun minute(date: Instant): Int = local(date).minute
    fun second(date: Instant): Int = local(date).second

    /** 1 = Sunday … 7 = Saturday. */
    fun weekday(date: Instant): Int = local(date).dayOfWeek.value % 7 + 1

    fun weekOfYear(date: Instant): Int = local(date).get(weekFields.weekOfWeekBasedYear())
    fun yearForWeekOfYear(date: Instant): Int = local(date).get(weekFields.weekBasedYear())

    /** Midnight of the day [date] falls on. */
    fun startOfDay(date: Instant): Instant = local(date).toLocalDate().atStartOfDay(zone).toInstant()

    /** [date] moved by whole calendar days, keeping the wall-clock time. */
    fun addingDays(days: Int, to: Instant): Instant = local(to).plusDays(days.toLong()).toInstant()

    /** Whole days from one moment to another, like `dateComponents([.day], from:to:).day`. */
    fun daysBetween(from: Instant, to: Instant): Int =
        ChronoUnit.DAYS.between(local(from), local(to)).toInt()

    /** `calendar.date(from: DateComponents(...))`. */
    fun date(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0): Instant =
        LocalDateTime.of(year, month, day, hour, minute, second).atZone(zone).toInstant()

    fun localDate(date: Instant): LocalDate = local(date).toLocalDate()

    companion object {
        /** The phone's own calendar right now: its time zone and its region's weeks. */
        val current: SlimyCalendar
            get() {
                val fields = WeekFields.of(Locale.getDefault())
                return SlimyCalendar(
                    zone = ZoneId.systemDefault(),
                    firstWeekday = fields.firstDayOfWeek.value % 7 + 1,
                    minimumDaysInFirstWeek = fields.minimalDaysInFirstWeek,
                )
            }

        /** A Gregorian calendar in a time zone, with iOS's default week rules (Sunday, 1 day). */
        fun gregorian(zone: ZoneId): SlimyCalendar = SlimyCalendar(zone)

        fun gregorian(zoneId: String): SlimyCalendar = SlimyCalendar(ZoneId.of(zoneId))

        /** Every calendar here is Gregorian already; kept so ported code reads like the Swift. */
        fun gregorian(like: SlimyCalendar): SlimyCalendar = like

        private fun dayOfWeek(weekday: Int): DayOfWeek {
            // 1 = Sunday … 7 = Saturday → java.time's Monday = 1 … Sunday = 7.
            val w = ((weekday - 1) % 7 + 7) % 7 // 0 = Sunday
            return if (w == 0) DayOfWeek.SUNDAY else DayOfWeek.of(w)
        }
    }
}
