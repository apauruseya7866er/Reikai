package reikai.domain.track

import eu.kanade.tachiyomi.util.lang.convertEpochMillisZone
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** One chapter as binding a tracker reads it. */
data class BindChapter(val number: Double, val read: Boolean)

/** What binding a tracker pushes from local reading; null leaves the tracker's own value. */
data class BindBackfill(val lastChapterRead: Double?, val startDate: Instant?)

/**
 * Upstream's `AddTracks.bind` rule, which the manga and novel bind both call. Only an entry with a read
 * chapter pushes anything: the end of its unbroken read run when the tracker is behind it, and the first
 * read as the start date when the tracker has none, ignoring removed history (no read time). [readAts]
 * runs only when that date is wanted.
 */
suspend fun bindBackfill(
    chapters: List<BindChapter>,
    trackLastChapterRead: Double,
    trackStartDate: Instant?,
    localZone: TimeZone,
    readAts: suspend () -> List<Instant?>,
): BindBackfill {
    if (chapters.none { it.read }) return BindBackfill(lastChapterRead = null, startDate = null)
    val latestRead = chapters.sortedBy { it.number }.takeWhile { it.read }.lastOrNull()?.number ?: -1.0
    val startDate = if (trackStartDate == null) {
        readAts().filterNotNull().minOrNull()
            ?.let {
                Instant.fromEpochMilliseconds(it.toEpochMilliseconds().convertEpochMillisZone(localZone, TimeZone.UTC))
            }
    } else {
        null
    }
    return BindBackfill(lastChapterRead = latestRead.takeIf { it > trackLastChapterRead }, startDate = startDate)
}
