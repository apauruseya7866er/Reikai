package reikai.domain.library

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
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

    private const val GRACE_PERIOD = 1L

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
     * An interval the user set never reads [chapterDates]; otherwise the release schedule the chapters show
     * is followed. [foundRelease] is whether the check that led here found new chapters, by the rule in
     * the app's `ChapterArrivals.kt`; the next check is then counted from that release.
     */
    suspend fun predict(
        fetchInterval: Int,
        nextUpdate: Instant?,
        lastUpdate: Instant?,
        dateTime: LocalDateTime,
        zone: TimeZone,
        window: ClosedRange<Instant>,
        foundRelease: Boolean = false,
        chapterDates: suspend () -> ChapterDates,
    ): ReleasePrediction {
        val today = dateTime.date
        val schedule = if (fetchInterval < 0) {
            // The check that found a release is the entry's last update
            val lastUpdateDay = if (foundRelease) today else lastUpdate?.toLocalDateTime(zone)?.date
            ReleaseSchedule.fixed(-fetchInterval, lastUpdateDay ?: today)
        } else {
            ReleaseSchedule.estimate(chapterDates(), zone, today)
        }

        val expectedDay = nextUpdate?.toLocalDateTime(zone)?.date
        // A release on or after the expected day means the next one is counted from it instead
        val isAwaitingRelease = nextUpdate != null &&
            expectedDay != null &&
            nextUpdate in window.start..window.endInclusive + 1.milliseconds &&
            (schedule.lastRelease == null || schedule.lastRelease < expectedDay)
        val next = when {
            foundRelease -> schedule.nextCheckAfterRelease(today).atStartOfDayIn(zone)
            isAwaitingRelease -> nextUpdate
            else -> schedule.nextCheck(today).atStartOfDayIn(zone)
        }
        return ReleasePrediction(if (fetchInterval < 0) fetchInterval else schedule.interval, next)
    }
}
