package reikai.domain.library

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.math.absoluteValue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** One entry's chapter dates, one of each per chapter, which a prediction reads. */
class ChapterDates(val upload: List<Instant?>, val fetch: List<Instant>)

/** A predicted interval in days, negative when the user set it, and when the entry is next due. */
data class ReleasePrediction(val interval: Int, val nextUpdate: Instant)

/**
 * Smart update's release prediction over plain dates, so manga (`FetchInterval`, upstream's) and novels
 * compute it once. An interval is in days; a negative one was set by the user and is kept as it is.
 */
object ReleaseInterval {

    const val MAX_INTERVAL = 28

    private const val DEFAULT_INTERVAL = 7
    private const val GRACE_PERIOD = 1L
    private const val HIATUS_CYCLES = 4
    private const val MIN_RELEASES_FOR_PATTERN = 4
    private const val MAX_WEEKDAYS = 3
    private const val WEEKDAY_COVERAGE = 0.8
    private const val DAY_OF_MONTH_TOLERANCE = 3
    private val MONTHLY_GAPS = 26..35

    /** The days around [date] an entry's next update has to fall in to be fetched now. */
    fun window(date: LocalDate, zone: TimeZone): ClosedRange<Instant> {
        val today = date.atStartOfDayIn(zone)
        return (today - GRACE_PERIOD.days)..(today + GRACE_PERIOD.days)
    }

    /** [window], or [today]'s when there is none, which callers pass to mean "now". */
    fun windowOrToday(window: ClosedRange<Instant>?, today: LocalDate, zone: TimeZone): ClosedRange<Instant> =
        window ?: window(today, zone)

    /**
     * Whether a sync that left the chapter list unchanged still predicts again: when asked for by hand,
     * when the entry was never predicted, or when its prediction is missing or has fallen behind [window].
     * Without a window only the first two apply. A changed list always predicts.
     */
    fun needsPrediction(
        manualFetch: Boolean,
        fetchInterval: Int,
        nextUpdate: Instant?,
        window: ClosedRange<Instant>?,
    ): Boolean {
        val isBeforeWindow = window != null && (nextUpdate == null || nextUpdate < window.start)
        return manualFetch || fetchInterval == 0 || isBeforeWindow
    }

    /**
     * The interval and next update of an entry stored with [fetchInterval], [nextUpdate] and [lastUpdate].
     * An interval the user set counts whole cycles from the last update and never reads [chapterDates];
     * otherwise the release schedule the chapters show is followed.
     */
    suspend fun predict(
        fetchInterval: Int,
        nextUpdate: Instant?,
        lastUpdate: Instant?,
        dateTime: LocalDateTime,
        zone: TimeZone,
        window: ClosedRange<Instant>,
        chapterDates: suspend () -> ChapterDates,
    ): ReleasePrediction {
        if (fetchInterval < 0) {
            val next = customNextUpdate(nextUpdate, lastUpdate, fetchInterval.absoluteValue, dateTime, zone, window)
            return ReleasePrediction(fetchInterval, next)
        }
        val schedule = schedule(chapterDates(), zone, dateTime.date)
        val next = nextUpdate(nextUpdate, lastUpdate, schedule, dateTime.date, zone, window)
        return ReleasePrediction(schedule.interval, next)
    }

    /**
     * The median gap between the latest release days, upload dates when three or more are known and fetch
     * dates otherwise, else a week, plus a weekday or monthly pattern when the days show one.
     */
    internal fun schedule(dates: ChapterDates, zone: TimeZone, today: LocalDate): ReleaseSchedule {
        val chapterWindow = if (dates.upload.size <= 8) 3 else 10

        // Sources that date chapters ahead of their release would otherwise pull the schedule into the future
        val latestPlausibleDay = today.plus(DatePeriod(days = 1))
        fun releaseDays(instants: Sequence<Instant>): List<LocalDate> {
            return instants
                .map { it.toLocalDateTime(zone).date }
                .filter { it <= latestPlausibleDay }
                .distinct()
                .sortedDescending()
                .take(chapterWindow)
                .toList()
        }

        val uploadDays = releaseDays(dates.upload.asSequence().filterNotNull())
        val days = uploadDays.takeIf { it.size >= 3 }
            ?: releaseDays(dates.fetch.asSequence()).takeIf { it.size >= 3 }
            ?: return ReleaseSchedule(
                interval = DEFAULT_INTERVAL,
                lastRelease = uploadDays.firstOrNull(),
                pattern = ReleasePattern.EveryDays(DEFAULT_INTERVAL),
            )

        val gaps = days.zipWithNext { newer, older -> older.daysUntil(newer) }.sorted()
        val medianGap = gaps[(gaps.size - 1) / 2]
        val interval = medianGap.coerceIn(1, MAX_INTERVAL)

        return ReleaseSchedule(
            interval = interval,
            lastRelease = days.first(),
            pattern = detectPattern(days, medianGap) ?: ReleasePattern.EveryDays(interval),
        )
    }

    /**
     * Finds a calendar pattern a plain day interval can't follow: a few fixed weekdays, or a day of the
     * month. Both need a few releases to be told apart from chance.
     */
    private fun detectPattern(days: List<LocalDate>, medianGap: Int): ReleasePattern? {
        if (days.size < MIN_RELEASES_FOR_PATTERN) return null

        if (medianGap in MONTHLY_GAPS) {
            val daysOfMonth = days.map { it.day }
            // The tolerance also holds the last day of the month, which moves between the 28th and the 31st
            return if (daysOfMonth.max() - daysOfMonth.min() <= DAY_OF_MONTH_TOLERANCE) {
                ReleasePattern.DayOfMonth(daysOfMonth.sorted()[(daysOfMonth.size - 1) / 2])
            } else {
                null
            }
        }

        val byFrequency = days.groupingBy { it.dayOfWeek }.eachCount().entries.sortedByDescending { it.value }
        val weekdays = mutableSetOf<DayOfWeek>()
        var covered = 0
        for ((dayOfWeek, count) in byFrequency) {
            if (covered >= days.size * WEEKDAY_COVERAGE) break
            weekdays += dayOfWeek
            covered += count
        }
        // One weekday is a weekly interval, and an irregular schedule spreads over most of the week
        if (weekdays.size !in 2..MAX_WEEKDAYS || covered < days.size * WEEKDAY_COVERAGE) return null
        // Releasing on several weekdays means more than one release a week
        if (medianGap > 7 / weekdays.size + 1) return null
        return ReleasePattern.Weekdays(weekdays)
    }

    private fun nextUpdate(
        nextUpdate: Instant?,
        lastUpdate: Instant?,
        schedule: ReleaseSchedule,
        today: LocalDate,
        zone: TimeZone,
        window: ClosedRange<Instant>,
    ): Instant {
        val lastRelease = listOfNotNull(schedule.lastRelease, lastUpdate?.toLocalDateTime(zone)?.date).maxOrNull()

        // A release on or after the expected day means the next one should be counted from it instead
        if (
            nextUpdate != null &&
            nextUpdate in window.start..window.endInclusive + 1.milliseconds &&
            (lastRelease == null || lastRelease < nextUpdate.toLocalDateTime(zone).date)
        ) {
            return nextUpdate
        }

        val anchor = schedule.lastRelease ?: lastRelease ?: today
        val daysSinceRelease = anchor.daysUntil(today)
        val expected = if (daysSinceRelease > schedule.interval * HIATUS_CYCLES) {
            // Past a few missed releases the schedule no longer holds, so checks spread out until it resumes
            val interval = backOff(schedule.interval, daysSinceRelease)
            anchor.plus(DatePeriod(days = ceilDiv(daysSinceRelease, interval) * interval))
        } else {
            // Today counts, so a release expected today keeps being checked for until the window passes it
            generateSequence(schedule.pattern.nextAfter(anchor)) { schedule.pattern.nextAfter(it) }
                .first { it >= today }
        }
        return expected.atStartOfDayIn(zone)
    }

    private fun customNextUpdate(
        nextUpdate: Instant?,
        lastUpdate: Instant?,
        interval: Int,
        dateTime: LocalDateTime,
        zone: TimeZone,
        window: ClosedRange<Instant>,
    ): Instant {
        if (nextUpdate != null && nextUpdate in window.start..window.endInclusive + 1.milliseconds) {
            return nextUpdate
        }

        val latestDate = (lastUpdate ?: Clock.System.now()).toLocalDateTime(zone).date
        val cycle = latestDate.daysUntil(dateTime.date).floorDiv(interval)
        return latestDate.plus(DatePeriod(days = (cycle + 1) * interval)).atStartOfDayIn(zone)
    }

    private fun backOff(interval: Int, daysSinceRelease: Int): Int {
        var backedOff = interval
        while (backedOff < MAX_INTERVAL && daysSinceRelease > backedOff * HIATUS_CYCLES) {
            backedOff *= 2
        }
        return backedOff.coerceAtMost(MAX_INTERVAL)
    }

    private fun ceilDiv(a: Int, b: Int) = -Math.floorDiv(-a, b)
}
