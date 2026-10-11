package reikai.domain.library

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

internal data class ReleaseSchedule(
    /** Days between releases, which is what the fetch interval shows. */
    val interval: Int,
    /** The most recent release day the next ones are counted from, if any chapter has a date. */
    val lastRelease: LocalDate?,
    val pattern: ReleasePattern,
)

internal sealed interface ReleasePattern {

    /** The first expected release day after [date]. */
    fun nextAfter(date: LocalDate): LocalDate

    data class EveryDays(val days: Int) : ReleasePattern {
        override fun nextAfter(date: LocalDate) = date.plus(DatePeriod(days = days))
    }

    data class Weekdays(val days: Set<DayOfWeek>) : ReleasePattern {
        override fun nextAfter(date: LocalDate): LocalDate {
            return generateSequence(date.plus(DatePeriod(days = 1))) { it.plus(DatePeriod(days = 1)) }
                .first { it.dayOfWeek in days }
        }
    }

    /** Releases on [day] of every month, or its last day in months too short for it. */
    data class DayOfMonth(val day: Int) : ReleasePattern {
        override fun nextAfter(date: LocalDate): LocalDate {
            val sameMonth = date.inMonthClamped(day)
            if (sameMonth > date) return sameMonth
            return date.plus(DatePeriod(months = 1)).inMonthClamped(day)
        }

        private fun LocalDate.inMonthClamped(day: Int): LocalDate {
            val firstOfMonth = LocalDate(year, month, 1)
            val lastDay = firstOfMonth.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1)).day
            return LocalDate(year, month, day.coerceAtMost(lastDay))
        }
    }
}
